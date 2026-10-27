package ants

import jakarta.annotation.PreDestroy
import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.OptimisticLockingException
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.externaltask.LockedExternalTask
import org.operaton.bpm.model.bpmn.instance.BaseElement
import org.operaton.bpm.model.bpmn.instance.FlowElement
import org.operaton.bpm.model.bpmn.instance.UserTask
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * The in-process worker. It fetches ready Agent Tasks (external tasks on topic `agent`) only for free slots under
 * the cap, oldest first across Runs, and runs each one's Skill through its Agent's CLI, `claude -p` or `codex exec`. A `done` Result completes the
 * task, stored as a process variable named after the Task's BPMN id; a `stuck` one or a failure is an incident. When
 * the user acts on it, the Notification list leaves their choice in the Task's local variables: Continue resumes the
 * session with their instruction, Start fresh starts a new one with it under "From the user:". While a session runs,
 * the user can [send] it a message or [stop] it. A Task that hits the Usage limit pauses its CLI until the reset, then
 * continues its session the same way, with the reset message. A Task a Review changes names as its Walkthrough is done
 * only once its Story set passes Varde's checks ([storyProblems]).
 */
@Component
class AgentLauncher(
    private val externalTasks: ExternalTaskService,
    private val runtime: RuntimeService,
    private val repository: RepositoryService,
    private val settings: Settings,
    private val catalogue: Catalogue,
    private val landing: Landing,
    private val cloneRepos: CloneRepos,
    internal val clock: Clock,
    private val names: RunNames,
    private val scopes: Scopes,
    private val protectedCommands: ProtectedCommands,
    private val scriptTools: ScriptTools,
    private val scripts: Scripts,
    @Lazy private val migration: Migration, // lazy: it deploys through Processes, which reach this launcher
    @Value("\${ants.home}") internal val home: Path,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val poller = Executors.newSingleThreadScheduledExecutor()
    /** Each live CLI process and the Run it works for. */
    internal val clis = ConcurrentHashMap<Process, String>()
    private val live = AtomicInteger()
    internal val stdins = ConcurrentHashMap<String, Live>() // by key(run, instance(task))

    internal fun key(run: String, task: String) = "$run/$task"
    private val usageFile = home.resolve("claude-usage.json")

    // ponytail: one pause gate per CLI, not per model, kept in memory; after a restart the held Tasks wait out
    // their locks, which end at the reset, and the first Task to hit the limit again shuts the gate again.
    private val gates = ConcurrentHashMap<String, Instant>() // CLI -> when its gate opens
    private val held = ConcurrentHashMap<String, String>() // external task id -> the CLI holding it

    /**
     * Instances of an Items are Tickets multi-instance held before launch, by external task id: each keeps its lock
     * while held, and is unlocked to launch once [Held.check] finds its Ticket free. After a restart, [start] unlocks
     * them with every other long lock, and each is held again at its next fetch.
     */
    // ponytail: every poll (200 ms) re-reads each held instance's Tickets; watch tickets/ instead if many are ever held.
    private val heldOnTickets = ConcurrentHashMap<String, Held>()

    private class Held(val reason: String, val check: () -> String?, val task: LockedExternalTask)

    /** Why the Task with external task [id] is held before launch, such as `Blocked by 01`, if it is. */
    fun heldBy(id: String): String? = heldOnTickets[id]?.reason

    /** How many Agent Tasks are running now. */
    val running get() = live.get()

    /** How a message Sent to [task] of [run] reaches its session, or null when no session takes messages. */
    fun sendHint(run: String, task: String) = stdins[key(run, task)]?.takeIf { it.open }?.hint

    /**
     * Sends [message] into the live session of [task] in [run], where claude reads it at its next tool boundary and
     * codex has its turn stopped and resumes with it ([Live.hint]), and appends
     * it to the Run log. False when no session takes it any more, such as once Ants holds the Task's Result.
     */
    fun send(run: String, task: String, message: String): Boolean {
        val stdin = stdins[key(run, task)]?.takeIf { it.say(message) } ?: return false
        runLog(names.runWorkspace(run), stdin.task, "Send", message)
        return true
    }

    /** Interrupts the live session of [task] in [run], which leaves the Task `[stopped]`. False when there is none. */
    fun stop(run: String, task: String) = stdins[key(run, task)]?.interrupt() == true

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        // ponytail: one Ants per ANTS_HOME, so a lock left by the last one is a launch that died with it, and its Task
        // starts over; locks are long instead of extended, so this is also the only way a lock ever ends early.
        // A pause's lock ends at its reset, days away at most, and the engine's locks run on the system time.
        externalTasks.createExternalTaskQuery().topicName(TOPIC).locked().list()
            .filter { it.lockExpirationTime.toInstant() > Instant.now() + Duration.ofDays(30) }
            .forEach { externalTasks.unlock(it.id) }
        poller.scheduleWithFixedDelay(::fetch, 0, 200, MILLISECONDS)
    }

    @PreDestroy
    fun shutdown() {
        poller.shutdownNow()
        clis.keys.forEach(Process::destroy)
    }

    /** Kills the live CLI processes of [run], once the Run is cancelled. */
    fun cancel(run: String) = clis.filterValues { it == run }.keys.forEach(::kill)

    /**
     * Ends [process] and every process under it: asks them all to stop, then kills whichever are still alive after
     * [GRACE]. A CLI may ignore SIGTERM, or wait on a child that does, and its launch keeps its slot until the output of
     * every one of them has closed. Unlike [Process.destroy], it leaves the rest of stdout to read.
     */
    internal fun kill(process: Process) {
        val all = listOf(process.toHandle()) + process.descendants().toList() // while they still have their parent
        all.forEach(ProcessHandle::destroy)
        Thread.ofVirtual().start {
            process.waitFor(GRACE.toMillis(), MILLISECONDS)
            all.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
        }
    }

    private fun fetch() {
        try {
            gates.filterValues { !it.isAfter(clock.instant()) }.forEach { (cli, reset) -> tryNow(cli, reset) }
            if (heldOnTickets.isNotEmpty()) { // ended with their Runs, else released once their Ticket is free
                heldOnTickets.keys.retainAll(externalTasks.createExternalTaskQuery().externalTaskIdIn(heldOnTickets.keys.toSet()).list().map { it.id }.toSet())
                heldOnTickets.forEach { (id, held) ->
                    val reason = held.check()
                    if (reason == null && heldOnTickets.remove(id) != null) runCatching { externalTasks.unlock(id) }
                    else if (reason != null) heldOnTickets[id] = Held(reason, held.check, held.task)
                }
            }
            val fetched = synchronized(live) { // the slots So far takes too
                val free = settings.agentTasks() - live.get()
                if (free <= 0) return
                externalTasks.fetchAndLock().workerId(WORKER).maxTasks(free).orderByCreateTime().asc()
                    .subscribe().topic(TOPIC, LOCK.toMillis()).localVariables().enableCustomObjectDeserialization().includeExtensionProperties().execute()
                    .also { live.addAndGet(it.size) }
            }
            fetched.forEach { task ->
                Thread.ofVirtual().start {
                    try {
                        launch(migrated(task, repository, catalogue))
                    } finally {
                        live.decrementAndGet()
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Fetching Agent Tasks failed", e) // a failed fetch must not end the polling
        }
    }

    /** Runs [cli] in a free slot under the cap, as every CLI process does, or returns null when every slot is taken. */
    fun <T> inSlot(cli: () -> T): T? {
        synchronized(live) {
            if (live.get() >= settings.agentTasks()) return null
            live.incrementAndGet()
        }
        try {
            return cli()
        } finally {
            live.decrementAndGet()
        }
    }

    /** The CLIs paused by the Usage limit, and when each resumes. */
    fun pauses(): Map<String, Instant> = gates.toMap()

    /** When the Task with external task [id] resumes, if a pause holds it. */
    fun pausedUntil(id: String): Instant? = held[id]?.let(gates::get)

    /**
     * Opens [cli]'s gate and unlocks the Tasks it held, so they launch as slots free up; with [reset], only if the
     * gate still opens then, so a later reset a Task has just hit keeps it shut.
     */
    fun tryNow(cli: String, reset: Instant? = null) = synchronized(gates) {
        if (reset == null) gates.remove(cli) else if (!gates.remove(cli, reset)) return@synchronized
        held.filterValues { it == cli }.keys.forEach { id ->
            held.remove(id)
            runCatching { externalTasks.unlock(id) } // gone if its Run ended meanwhile
        }
    }

    /**
     * Holds [task] while [cli] is paused, first pausing it [until] a reset if given, and returns false when [cli] isn't
     * paused. The hold is a failure with retries left, so it is no incident, and its lock ends at the reset.
     */
    private fun hold(task: LockedExternalTask, cli: String, until: Instant? = null): Boolean {
        synchronized(gates) {
            if (until != null) gates.merge(cli, until, ::maxOf)
            val reset = gates[cli] ?: return false
            val wait = Duration.between(clock.instant(), reset).toMillis().coerceAtLeast(0)
            externalTasks.handleFailure(task.id, WORKER, "Paused until $reset", null, 1, wait)
            held[task.id] = cli
            return true
        }
    }

    /** Runs [task] once, between a divider and a line saying how it ended in its Transcript. */
    private fun launch(task: LockedExternalTask) {
        val resume = (task.variables[SESSION] as? String)?.takeIf { task.variables[ACTION] == CONTINUE }
        var session = resume // a new one only once claude starts, so Continue never resumes a session claude never saw
        var transcript: Transcript.Writer? = null
        var level: Int? = null
        try {
            val run = runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult().rootProcessInstanceId
            val workspace = names.runWorkspace(run)
            if (ticketed(task, run, workspace)) return
            migration.unmigrated(task)?.let { throw Failed(it) }
            val agent = Links(task.extensionProperties::get).agent(catalogue)
            val taskLevel = Level.of(task.extensionProperties[LEVEL])
            val onLevel = task.variables[REACHED_LEVEL] as? Int ?: agent.firstLevel(taskLevel)
            level = onLevel
            val launchLevel = agent.launchLevel(taskLevel, onLevel)
            if (hold(task, agent.cli)) return // keeping its choice for the launch after the reset
            runtime.removeVariablesLocal(task.executionId, listOf(ACTION, INSTRUCTION, SESSION, PERMISSION)) // the choice is used once
            val (skill, used) = skills(task.extensionProperties, agent, task.activityId)
            if (agent.cli == "claude") refuseBareByDefault(agent.cli, workspace)
            val instance = instance(task)
            // An instance of a parallel multi-instance works in a folder of its own, and lands when done.
            val parallel = runtime.inParallel(task.executionId, task.activityId)
            val cwd = if (parallel) landing.folder(workspace, instance, fresh = task.variables[ACTION] == FRESH) else workspace
            // An Approve's Send back leaves its comment for the Task it names, and a Round its answers for its asker, used once.
            val sent = sentBack(workspace, instance)
            val comment = sent.takeIf { resume == null && task.variables[ACTION] == null && it.exists() }?.readText()?.also { sent.deleteIfExists() }
            // With [RESUME], a Task the flow comes back to, as in a loop, resumes its last session, as one sent back
            // does; the user's Continue and Start fresh choose for themselves.
            val last = Transcript.file(workspace, instance).resolveSibling("session")
            val again = last.takeIf { resume == null && task.variables[ACTION] == null && (task.extensionProperties[RESUME] == "true" || comment != null) && it.exists() }?.readText()
            // codex names a new session itself, once it starts ([started]).
            session = session ?: again ?: UUID.randomUUID().toString().takeIf { agent.cli == "claude" }
            transcript = Transcript.Writer(Transcript.file(workspace, instance)).apply { ants("launch", chip = chip(task, agent, launchLevel)) }
            session?.let(last::writeText)
            val started = { id: String -> session = id; last.writeText(id) }
            val resumed = resume != null || again != null // claude knows the session already
            // The user's Continue, or a Send back to a session claude has: their words alone, no hand-off again.
            val continued = resume != null || (comment != null && again != null)
            val result = try {
                val result = session(task, instance, agent, skill, used, run, workspace, cwd, transcript, session, started, resumed, task.variables[INSTRUCTION] as? String ?: comment, handedOff = !continued, level = launchLevel)
                if (!parallel) result
                else land(workspace, instance, result) { session(task, instance, agent, skill, used, run, workspace, cwd, transcript, session, started, true, it, handedOff = false, level = launchLevel) }
            } catch (limit: UsageLimit) {
                val until = limit.resetsAt + Duration.ofMinutes(1)
                transcript.ants("end", "Paused by the ${agent.cli} usage limit until ${clock.local(until)}.")
                runtime.setVariablesLocal(task.executionId, mapOf(ACTION to CONTINUE, INSTRUCTION to RESET, SESSION to session))
                hold(task, agent.cli, until)
                return
            }
            if (result["outcome"] == "done") {
                transcript.ants("end", "Done.")
                // An answer-round Agent: Ants stamps its answers and hands them to the asker it names.
                val asker = task.extensionProperties[ASKER]
                if (asker == null) complete(task, result)
                else {
                    val given = (((result["output"] as? Map<*, *>)?.get("answers")) as? List<*>).orEmpty()
                        .mapNotNull { it as? Map<*, *> }.associate { it["id"].toString() to it["answer"].toString() }
                    runtime.answerFor(workspace, task.executionId, name(task), asker, given) { complete(task, result + ("output" to it)) }
                }
            } else {
                val reason = "[stuck] ${result["summary"]}"
                transcript.ants("end", reason)
                incident(task, session, reason, result["needs"].toString())
            }
        } catch (e: Exception) {
            val stopping = poller.isShutdown // the Task stays locked, and the next start unlocks it
            // Cancel the Run deletes it, then kills its CLIs.
            val cancelled = runCatching { runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).count() == 0L }.getOrDefault(false)
            val reason = when {
                stopping -> "Ants stopped."
                cancelled -> "Cancelled with the Run."
                e is Stopped -> "[stopped] ${e.message}"
                e is Refused -> "[permission] ${e.request.summary}"
                else -> "[failed] ${e.message ?: e}"
            }
            runCatching { transcript?.ants("end", reason) }
            if (stopping || cancelled) return
            if (e is Refused) runCatching { runtime.setVariableLocal(task.executionId, PERMISSION, e.request.json()) }
            if (e !is Stopped && e !is Refused) log.warn("Agent Task ${task.activityId} failed", e)
            if (e !is Stopped && e !is Refused && level != null && retry(task, session, level, reason, (e as? Failed)?.details)) return
            incident(task, session, reason, (e as? Failed)?.details)
        }
    }

    /** Lands the instance [instance]'s [result], when done; a landing that conflicts [resolve]s it in the session, and is retried after each fix. */
    private fun land(workspace: Path, instance: String, result: Map<*, *>, resolve: (String) -> Map<*, *>): Map<*, *> {
        var result = result
        var rounds = 0
        while (result["outcome"] == "done") {
            val conflict = landing.land(workspace, instance) ?: break
            if (rounds++ == CONFLICT_ROUNDS) throw Failed("${conflict.substringBefore("\n\n")} It still conflicts after $CONFLICT_ROUNDS rounds.", conflict.substringAfter("\n\n"))
            result = resolve("$conflict\n\nThe rebase was aborted. Rebase onto the work branch in each repo named, resolve the conflicts, and answer again with your Result.")
        }
        return result
    }

    /**
     * The `ready-for-human` Tickets sent back, by [key] of their Run and name, while their fix runs or once it didn't
     * land; gone once it lands.
     */
    // ponytail: kept in memory: a fix running dies with Ants, and after a restart its blocker stays `in-progress`, so its
    // Ticket waits for it, until the user Edits it back to `in-review` and can Send back once more.
    private val fixing = ConcurrentHashMap<String, Fixing>()

    /** A Ticket sent back: its fix [running], or [failed], as [summary] says. */
    class Fixing(val running: Boolean, val summary: String) {
        val failed get() = !running
    }

    /** How the fix of the Ticket [ticket] of [run] is going, if it is sent back. */
    fun fixing(run: String, ticket: String): Fixing? = fixing[key(run, ticket)]

    /**
     * **Send back** on the `ready-for-human` [ticket] of [run], whose instance is held: adds [comment] to its Comments,
     * then, one at a time, each in a slot, resumes the implementer of each Ticket in its `Blocked by` with the comment
     * and the Ticket's path (or starts it anew, without a session), in a new instance folder from the work branch head.
     * That Ticket is `in-progress` until its fix lands, then `in-review`, and the fix's Result replaces the instance's;
     * a fix that doesn't land puts it and those not fixed yet back `in-review`, as their last landing left them. Only
     * once every blocker has landed, and only for an implementer that is an instance of the held Task's own
     * multi-instance. Returns why it can't, or null.
     */
    @Synchronized // so one blocker is never fixed twice at once
    fun sendBack(run: String, ticket: Tracked, comment: String): String? {
        val workspace = names.runWorkspace(run)
        val id = key(run, ticket.name)
        val held = heldOnTickets.values.firstOrNull { held ->
            runCatching {
                runtime.createProcessInstanceQuery().processInstanceId(held.task.processInstanceId).singleResult()?.rootProcessInstanceId == run &&
                    ticket(workspace, run, runtime.ticketItem(held.task.executionId, held.task.activityId))?.number == ticket.number
            }.getOrDefault(false) // its Run just ended
        }?.task ?: return "This Ticket isn't waiting on you in a live Run."
        if (fixing[id]?.running == true) return "This Ticket is sent back already; its fix hasn't landed yet."
        val open = waitsFor(ticket, tickets(workspace, run))
        if (open.isNotEmpty()) return "${open.joinToString()} ${if (open.size == 1) "hasn't" else "haven't"} landed yet, so there is nothing to send back to."
        val fixes = ticket.blockedBy.mapNotNull { number ->
            val (index, key) = runtime.ticketSibling(held.executionId, held.activityId) { ticket(workspace, run, it)?.number == number.toInt() } ?: return@mapNotNull null
            Fix(number, index, key).takeIf { Transcript.file(workspace, key).exists() } // a blocker the user did has no implementer
        }
        if (fixes.isEmpty()) return "No Agent built what this Ticket checks."
        ticket.comment("Sent back by the user:\n\n${comment.trim()}")
        runLog(workspace, ticket.title, "Send back", comment)
        val message = "The user sent back ${ticket.title} ($TICKETS/${ticket.name}.md), which checks your work:\n\n${comment.trim()}\n\n" +
            "Fix it, then answer again with your Result."
        fixes.forEach { ticket(workspace, run, it.ticket)?.status(Tracked.IN_PROGRESS) }
        fixing[id] = Fixing(true, "Sent back; waiting on the fix of ${fixes.joinToString { it.ticket }}.")
        Thread.ofVirtual().start {
            for ((i, fix) in fixes.withIndex()) {
                try {
                    while (inSlot { fix(held, run, workspace, fix, message) } == null) Thread.sleep(200)
                    ticket(workspace, run, fix.ticket)?.status(Tracked.IN_REVIEW)
                } catch (e: Exception) {
                    if (e !is Stopped && e !is Failed) log.warn("The fix of ${fix.key} failed", e)
                    fixes.drop(i).forEach { ticket(workspace, run, it.ticket)?.status(Tracked.IN_REVIEW) }
                    fixing[id] = Fixing(false, "The fix of ${fix.ticket} didn't land: ${e.message ?: e}")
                    return@start
                }
            }
            fixing.remove(id)
        }
        return null
    }

    /** The instance [key] that implemented the Ticket numbered [ticket], the [index]th (0-based) of its multi-instance. */
    private data class Fix(val ticket: String, val index: Int, val key: String)

    /**
     * Runs the instance of [held]'s multi-instance that [fix] names again with [message]: its last session resumed,
     * or a new one handed off its inputs; then lands it and puts its Result in place of the old one. A `stuck` one, or
     * one cut short by the Usage limit, fails ([Failed]).
     */
    private fun fix(held: LockedExternalTask, run: String, workspace: Path, fix: Fix, message: String) {
        val key = fix.key
        val agent = Links(held.extensionProperties::get).agent(catalogue)
        val (skill, used) = skills(held.extensionProperties, agent, held.activityId)
        if (agent.cli == "claude") refuseBareByDefault(agent.cli, workspace)
        val last = Transcript.file(workspace, key).resolveSibling("session")
        val resumed = last.exists()
        var session = if (resumed) last.readText() else UUID.randomUUID().toString().takeIf { agent.cli == "claude" }
        val inputs = last.resolveSibling(INPUTS).takeIf { it.exists() }?.let { json.readValue(it.readText(), Map::class.java) }.orEmpty()
            .mapKeys { it.key.toString() }
        val cwd = landing.folder(workspace, key, fresh = true)
        val transcript = Transcript.Writer(Transcript.file(workspace, key)).apply { ants("launch", chip = chip(held, agent, agent.launchLevel(Level.of(held.extensionProperties[LEVEL])))) }
        session?.let(last::writeText)
        val started = { id: String -> session = id; last.writeText(id) }
        try {
            val first = session(held, key, agent, skill, used, run, workspace, cwd, transcript, session, started, resumed, message, inputs = inputs)
            val result = land(workspace, key, first) { session(held, key, agent, skill, used, run, workspace, cwd, transcript, session, started, true, it, handedOff = false) }
            if (result["outcome"] != "done") throw Failed("it ended stuck: ${result["summary"]} It needs: ${result["needs"]}")
            onClash { runtime.replaceResult(held.executionId, held.activityId, fix.index, result) }
            transcript.ants("end", "Done.")
        } catch (e: Exception) {
            transcript.ants("end", when (e) {
                is Stopped -> "[stopped] ${e.message}"
                is UsageLimit -> "Cut short by the ${agent.cli} usage limit, which resets at ${clock.local(e.resetsAt)}."
                else -> "[failed] ${e.message ?: e}"
            })
            if (e is UsageLimit) throw Failed("the ${agent.cli} usage limit cut it short; Send back again after ${clock.local(e.resetsAt)}.")
            throw e
        }
    }

    /**
     * Whether [task] of [run], an instance of an Items are Tickets multi-instance, launches nothing now: held while its
     * Ticket is the user's or blocked ([ants.held]), or done without a launch once the user has marked it done.
     */
    private fun ticketed(task: LockedExternalTask, run: String, workspace: Path): Boolean {
        val item = runtime.ticketItem(task.executionId, task.activityId) ?: return false
        val ticket = ticket(workspace, run, item) ?: error("The item $item names no Ticket in tickets/issues")
        if (ticket.done) {
            complete(task, mapOf("outcome" to "done", "summary" to "Done by the user.", "output" to emptyMap<String, Any>()))
            return true
        }
        val check = { ticket(workspace, run, item)?.let { if (it.done) null else held(it, tickets(workspace, run)) } }
        heldOnTickets[task.id] = Held(check() ?: return false, check, task)
        return true
    }

    /** Completes [task] with its [result] ([onClash]). */
    private fun complete(task: LockedExternalTask, result: Map<*, *>) = onClash { externalTasks.complete(task.id, WORKER, mapOf(task.activityId to result)) }

    /** The key of [task] in its Run, for its Transcript and live session ([taskKey]). */
    private fun instance(task: LockedExternalTask) = runtime.taskKey(task.executionId, task.activityId)

    /**
     * The Task's inputs: its input parameters, not the other local variables its execution may hold, such as a
     * multi-instance's loop variables or the Results its instance collects.
     */
    private fun inputs(task: LockedExternalTask): Map<String, Any?> {
        val names = repository.getBpmnModelInstance(task.processDefinitionId).getModelElementById<BaseElement>(task.activityId)
            ?.inputs().orEmpty().map { it.operatonName }
        return task.variables.filterKeys { it in names }.toSortedMap()
    }

    private fun chip(task: LockedExternalTask, agent: Agent, level: Level): ModelChip =
        ModelChip(agent.cli, level, retries(task), settings.retries())

    private fun retries(task: LockedExternalTask): Int = task.variables[RETRIES] as? Int ?: 0

    private fun retry(task: LockedExternalTask, session: String?, level: Int, reason: String, details: String?): Boolean = runCatching {
        val retries = retries(task)
        if (retries >= settings.retries()) return false
        val resume = session?.let { mapOf(ACTION to CONTINUE, INSTRUCTION to "Your last attempt failed: ${reason.removePrefix("[failed] ")}${details?.let { "\n\n$it" }.orEmpty()}\n\n$RETRY", SESSION to it) }.orEmpty()
        runtime.setVariablesLocal(task.executionId, mapOf(REACHED_LEVEL to Level.above(level), RETRIES to retries + 1) + resume)
        externalTasks.handleFailure(task.id, WORKER, reason, null, 1, 0)
    }.onFailure { log.warn("Agent Task ${task.activityId} couldn't be retried", it) }.isSuccess

    /**
     * Puts [task] on hold as an incident, its tag and reason in [message] and anything longer in [details], keeping
     * its [session], if claude ever started one, for Continue.
     */
    private fun incident(task: LockedExternalTask, session: String?, message: String, details: String?) = runCatching {
        if (session != null) runtime.setVariableLocal(task.executionId, SESSION, session)
        externalTasks.handleFailure(task.id, WORKER, message, details, 0, 0)
    }.onFailure { log.warn("Agent Task ${task.activityId} couldn't be put on hold", it) }

    /**
     * The Skill a Task with [properties] starts with, `/<skill>`, and the other Skills it may use (ADR 0024). With an
     * [ASSIGNMENT] it starts with none, and may use them all. Its own [SKILLS] list them, an empty one none; else the
     * [agent]'s. On a shipped Agent, whose Skills change only through Your Skills, a listed one it no longer allows, taken
     * out of Your Skills since the Process deployed, is left out; on the user's own Agent the Task keeps what it lists.
     * Without an Assignment the Task lists exactly one.
     */
    private fun skills(properties: Map<String, String>, agent: Agent, id: String): Pair<Skill?, List<Skill>> {
        val job = Job(properties::get)
        val revocable = catalogue.yours.has(agent)
        val allowed = Links(properties::get).skills(catalogue)?.map { it.getOrThrow() }?.filter { skill ->
            !revocable || catalogue.allows(agent, skill).also { if (!it) log.warn("$id lists the Skill ${skill.name}, which its Agent ${agent.name} no longer allows, so it is left out") }
        } ?: catalogue.allowed(agent)
        if (job.assignment != null) return null to allowed
        if (!job.valid) error("$id has no Assignment and doesn't list exactly one Skill")
        return allowed.single() to emptyList()
    }

    private fun allowedScripts(properties: Map<String, String>, agent: Agent): List<Script> =
        Links(properties::get).scripts(scripts)?.map { it.getOrThrow() } ?: agent.scripts.map { scripts.linked(it) }

    /**
     * Runs [skill], or with none the Task's Assignment, using [used] too, in one CLI session in [cwd] (the Run [workspace], or an instance's folder in it),
     * every line it prints going to [transcript], and returns its Result: the last valid one, once every message written to the session has been read. A new
     * [session] starts with the hand-off prompt and the user's [instruction], if any; a [resume]d one with the
     * instruction alone, unless it is [handedOff] again, as when the flow comes back to the Task. A CLI that names its
     * own sessions, codex, tells Ants a new one's id through [started].
     */
    private fun session(
        task: LockedExternalTask, instance: String, agent: Agent, skill: Skill?, used: List<Skill>, run: String, workspace: Path, cwd: Path, transcript: Transcript.Writer,
        session: String?, started: (String) -> Unit, resume: Boolean, instruction: String?, handedOff: Boolean = !resume, inputs: Map<String, Any?> = inputs(task),
        level: Level = agent.launchLevel(Level.of(task.extensionProperties[LEVEL])),
    ): Map<*, *> {
        // Kept for a Send back that finds no session to resume ([fix]).
        if (handedOff) Transcript.file(workspace, instance).resolveSibling(INPUTS).writeText(json.writeValueAsString(inputs))
        // A called Process's Task uses its top Run's Profile: [run] is the top Run.
        val source = scopes.of(run)
        val vault = vault(task, source)
        // At normal, a Run's hand-off line and flags stay as they were.
        val verbosity = source.verbosity.takeIf { it != Verbosity.NORMAL }
        // The Task's result schema; Checks refuse one that isn't valid.
        val asks = asksRounds(repository.getBpmnModelInstance(task.processDefinitionId).getModelElementById(task.activityId))
        val output = promised(task.extensionProperties[RESULT_SCHEMA], asks) ?: error("${task.activityId}'s result schema isn't a JSON object")
        val schema = ResultContract.schema(output)
        // A Run with repos picked clones exactly them, so the repos a Result names there go unchecked.
        val repos = source.repos(settings).takeIf { source.picked.isEmpty() && ResultContract.namesRepos(output) }
        val prompt = if (!handedOff) instruction.orEmpty()
        else handOff(agent.cli, task, skill, used, cwd, vault, source.sourceMap(settings), repos, verbosity) +
            task.extensionProperties[ASSIGNMENT]?.takeIf { skill == null }?.let { "\n\n$it" }.orEmpty() + "\n\n$RANKING" +
            (if (asks) "\n\n$ROUND_PROTOCOL" else "") + "\n\n" + json.writeValueAsString(inputs) + (if (instruction.isNullOrBlank()) "" else "\n\nFrom the user:\n$instruction")
        val judge = Judge(agent.cli, schema, workspace, walkthrough(task), repos)
        // A knowledge Task, one with an `approval` input, may write the Vault only once it holds the user's approval
        // (ADR 0007): until then the Vault is read-only to it, but for Bash. codex can read it without an --add-dir.
        val gated = vault?.takeIf { "approval" in inputs && (inputs["approval"] as? Map<*, *>)?.get("approved") != true }
        if (agent.cli == "codex") return codex(task, instance, agent, run, workspace, cwd, vault.takeIf { gated == null }, transcript, session, started, prompt, ResultContract.strict(output), judge, level)
        val shared = if (cwd == workspace) emptyList() else listOf("--add-dir", "$workspace") // what an instance's links lead to
        val places = vault?.let { listOf("--add-dir", "$it") }.orEmpty() // the Vault's path goes nowhere else (ADR 0002)
            .plus(answerHistory(task)?.let { listOf("--add-dir", "${it.createDirectories()}") }.orEmpty()) // so a Read reaches it (ADR 0010)
            .plus(if (verbosity == null) emptyList() else listOf("--add-dir", "${catalogue.verbosityFile.parent}"))
        val tools = scriptTools.of(allowedScripts(task.extensionProperties, agent), workspace, run)
        val agentTools = (catalogue.tools(agent) + granted(task)).distinct()
        val allowed = protectedCommands.allowed(agentTools)
        val (token, mcp) = protectedCommands.open(workspace, tools, allowed) // its Protected Commands run in the Run workspace
        val process = runCatching {
            val skills = listOfNotNull(skill) + used
            cli(agent.cli, cwd, flags(agent, agentTools, level, cwd, schema, skills, skillsFolder(workspace, instance, skills), session!!, resume, mcp, listOfNotNull(RUN_TOOL.takeIf { allowed.isNotEmpty() }) + tools.tools.map { Mcp.CLAUDE_PREFIX + it["name"] }, workspace, gated?.let { listOf("Write(/$it/**)", "Edit(/$it/**)") }.orEmpty()) + shared + places)
                .apply { environment()["ANTS_TASK"] = task.activityId } // names the Task to its session; the fake claude keys on it
                .start()
        }.onFailure { protectedCommands.close(token) }.getOrThrow()
        val stdin = Stdin(process.outputWriter(), name(task), home)
        clis[process] = run
        val stderr = drain(process)
        try {
            stdin.say(prompt)
            stdins[key(run, instance)] = stdin // only now, so the prompt goes first
            var stopped = false
            var limit: Instant? = null
            var answered = false // a result line came after the user's Stop
            var request: PermissionRequest? = null
            val calls = HashMap<Any?, Map<*, *>>()
            process.inputReader().forEachLine { line ->
                transcript.line(line)
                if (judge.failure != null || stopped || limit != null || (judge.valid != null && !stdin.open)) return@forEachLine
                val event = runCatching { json.readValue(line, Map::class.java) }.getOrNull() ?: return@forEachLine
                if (event["type"] == "user" && event["isReplay"] == true) stdin.read()
                if (event["type"] == "rate_limit_event") keepUsage(event)
                if (event["type"] == "assistant") ((event["message"] as? Map<*, *>)?.get("content") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
                    .filter { it["type"] == "tool_use" }.forEach { calls[it["id"]] = it["input"] as? Map<*, *> ?: emptyMap<Any, Any>() }
                if (request == null && event["type"] == "system" && event["subtype"] == "permission_denied") {
                    request = permissionRequest(agent, agentTools, cwd, event, calls[event["tool_use_id"]])
                    stdin.interrupt()
                    return@forEachLine
                }
                // ponytail: claude sends system/init only once it has read the prompt, so with an API key the first
                // request may already be on its way when Ants kills it.
                val apiKey = (event["apiKeySource"] as? String)?.takeIf { event["subtype"] == "init" && it != "none" }
                if (event["error"] == "authentication_failed" || apiKey != null) {
                    judge.failure = Failed(LOST_LOGIN, apiKey?.let { "${agent.cli} would use an API key (apiKeySource: $it), and Ants runs only on its own login." })
                    stdin.close()
                    kill(process)
                    return@forEachLine
                }
                usageLimit(event)?.let {
                    limit = it // the session continues after the reset
                    stdin.close()
                    kill(process)
                    return@forEachLine
                }
                if (event["type"] != "result") return@forEachLine
                if (stdin.stopping) answered = true
                val result = event["structured_output"]
                if (result == null && stdin.stopping && event["subtype"] == "error_during_execution") stopped = true
                else judge.judge(result, listOf(event["subtype"], event["result"], (event["errors"] as? List<*>)?.joinToString("; "))) { stdin.say(it) }
                if (judge.failure != null || stopped) stdin.close() else if (judge.valid != null) stdin.closeWhenRead()
            }
            val exit = process.waitFor()
            limit?.let { throw UsageLimit(it) }
            request?.takeIf { judge.valid?.get("outcome") != "done" }?.let { throw Refused(it) }
            val tail = stderr()
            if (stopped || (stdin.stopping && !answered && judge.failure == null)) throw Stopped()
            judge.failure?.let { throw Failed(it.message, it.details ?: tail) }
            if (exit != 0) throw Failed("${agent.cli} exited with code $exit", tail)
            return judge.valid ?: throw Failed("${agent.cli} ended without a Result", tail)
        } finally {
            stdin.close()
            stdins.remove(key(run, instance), stdin)
            clis -= process
            protectedCommands.close(token)
        }
    }

    private fun granted(task: LockedExternalTask): List<String> = PermissionRequest.granted(task.variables[GRANTED])

    private fun permissionRequest(agent: Agent, tools: List<String>, cwd: Path, event: Map<*, *>, input: Map<*, *>?): PermissionRequest {
        val tool = event["tool_name"].toString()
        val target = PermissionRequest.target(input)
        return PermissionRequest(agent.name, tool, target, PermissionRequest.limit(tool, target, cwd), whyNoAllow(tool, target, event["decision_reason_type"] == "rule", tools))
    }

    private fun whyNoAllow(tool: String, target: String?, rule: Boolean, tools: List<String>): String? {
        val commands = if (tool == "Bash") PermissionRequest.commands(target.orEmpty()) else emptyList()
        val protected = settings.protectedCommands().keys.map { Settings.strings(it).orEmpty() }.filter { it.isNotEmpty() }
            .firstOrNull { prefix -> commands.any { it.take(prefix.size) == prefix } }?.joinToString(" ")
        val own = (listOf(settings.secretsFile, settings.file).map { it.toAbsolutePath().toString() } + listOf("scripts", "drafts/scripts").map { "${home.resolve(it).toAbsolutePath()}/" })
        return when {
            protected != null -> if (protected in protectedCommands.allowed(tools)) "a Protected Command: run it with Ants' `run` tool, never Bash"
                else "a Protected Command: give the Agent `${ProtectedCommands.AGENT_TOOL}`"
            !rule -> null
            tool == "Bash" && "--output" in target.orEmpty() -> "git's `--output`, which writes any file"
            target != null && own.any { target.startsWith(it) } -> "Ants' own settings, Secrets and Scripts"
            tool in SUBAGENT_TOOLS -> "a Subagent type outside the Agent's `Agent(…)` Limit"
            else -> "a deny rule Ants adds"
        }
    }

    /** Reads [process]'s stderr as it comes, and returns what gives its last lines once it has ended. */
    internal fun drain(process: Process): () -> String? {
        val stderr = ArrayDeque<String>()
        val drain = Thread.ofVirtual().start {
            process.errorReader().forEachLine { synchronized(stderr) { stderr += it; if (stderr.size > STDERR_LINES) stderr.removeFirst() } }
        }
        return {
            drain.join()
            synchronized(stderr) { stderr.joinToString("\n") }.ifEmpty { null }
        }
    }

    /**
     * Judges each Result a session gives as it comes, the last valid one counting. An invalid one, its repos checked
     * against [sourceMap] when given, or a walkthrough's Story set failing its checks, is sent back into the session
     * once; the second is a [failure].
     */
    internal inner class Judge(private val cli: String, private val schema: Map<*, *>, private val workspace: Path, private val walkthrough: Boolean, private val sourceMap: List<Repo>?) {
        var valid: Map<*, *>? = null
            private set
        var failure: Failed? = null
        private var retried = false
        private var storyChecked = false // a walkthrough's Story set failed the checks once already

        /** Judges [result], null when a turn ended without one, as [ended] tells; [say] sends a fix into the session. */
        fun judge(result: Any?, ended: List<Any?>, say: (String) -> Unit) {
            val errors = result?.let { ResultContract.errors(it, schema, workspace, sourceMap) }
            valid = null // the last result is the one that counts
            when {
                errors == null -> failure = Failed((listOf("$cli ended without a Result:") + ended).filter { it != null && it != "" }.joinToString(" "))
                errors.isEmpty() -> try {
                    // A walkthrough's Story set must pass Varde's checks, with one round to fix the failing steps.
                    val problems = if (walkthrough && (result as Map<*, *>)["outcome"] == "done") storyProblems(workspace, cloneRepos.untrimmed) else emptyList()
                    when {
                        problems.isEmpty() -> { valid = result as Map<*, *> }
                        !storyChecked -> {
                            storyChecked = true
                            say(fixRequest(problems))
                        }
                        else -> failure = Failed("The Story set in $STORIES fails its checks: ${problems.joinToString()}", fixRequest(problems))
                    }
                } catch (refused: StorySetRefused) {
                    failure = Failed(refused.message!!)
                }
                !retried -> {
                    retried = true
                    say("Your Result is invalid:\n${errors.joinToString("\n") { "- $it" }}\nAnswer again with a valid Result.")
                }
                else -> failure = Failed("The Result is invalid: ${errors.joinToString("; ")}", errors.joinToString("\n") + "\n\n" + json.writeValueAsString(result))
            }
        }
    }

    /**
     * The Vault's folder when [task]'s toggle ([VAULT]) gives it the Vault. Null when off, or with no Vault configured
     * for the Run's Profile ([source]), which the pre-Run check (#55) refuses unless the user picked the Run's repos.
     */
    private fun vault(task: LockedExternalTask, source: RunSource): Path? = source.vault(settings)
        ?.takeIf { task.extensionProperties[VAULT]?.toBooleanStrictOrNull() ?: false }

    /**
     * The folder whose `.claude/skills` links each of [skills], the Skills a session of [instance] may use, so claude
     * loads them as its own (ADR 0024). Made anew for each launch, a resumed one too. It sits in the Run [workspace]
     * but outside what outlives the Run (`tasks/`), so it goes with the workspace ([cleanUp] doesn't follow links).
     */
    @OptIn(ExperimentalPathApi::class)
    private fun skillsFolder(workspace: Path, instance: String, skills: List<Skill>): Path? {
        val folder = workspace.resolve(".skills").resolve(instance)
        folder.deleteRecursively() // leaves what the links lead to
        if (skills.isEmpty()) return null
        val links = folder.resolve(".claude/skills").createDirectories()
        for (skill in skills.distinctBy { it.name }) links.resolve(skill.name).createSymbolicLinkPointingTo(skill.folder)
        return folder
    }

    /**
     * The hand-off line: for claude, `/<skill>`, which runs the Skill as a real skill, and the workspace; for codex, the Skill's file to follow and the workspace, the Skills it [used], where the [vault] and [sourceMap] and the
     * Answer History are, each only when on, the names of the [repos] its Result may name, if checked, and the
     * [verbosity] to write at, if any.
     */
    private fun handOff(cli: String, task: LockedExternalTask, skill: Skill?, used: List<Skill>, workspace: Path, vault: Path?, sourceMap: Path?, repos: List<Repo>?, verbosity: Verbosity?): String {
        val places = if (vault == null) emptyList() else listOfNotNull(
            "the Vault is $vault",
            sourceMap?.let { "the Source map is $it" },
        )
        // ponytail: codex keeps reading the Skill as a file until its skills are probed (#225).
        // claude shows the model no Skill only a user can invoke, so an Assignment that names one has it read as a file.
        val userOnly = used.filter { it.userOnly }.takeIf { skill == null && cli == "claude" }.orEmpty()
        return (if (skill == null) "The workspace is $workspace." else if (cli == "claude") "/${skill.name} The workspace is $workspace." else "Follow the Skill in ${skill.file} for the workspace at $workspace.") +
            (if (used.isEmpty() || cli == "claude") "" else (if (skill == null) " You may follow" else " It uses") + " the Skills ${used.joinToString { "${it.name} (${it.file})" }}.") +
            (if (userOnly.isEmpty()) "" else " Only a user can invoke the Skill${if (userOnly.size > 1) "s" else ""} ${userOnly.joinToString { "${it.name} (${it.file})" }}, " +
                "so where your Assignment names ${if (userOnly.size > 1) "one" else "it"}, read that file and follow it.") +
            (if (places.isEmpty()) "" else " " + places.joinToString(" and ").replaceFirstChar(Char::uppercase) + ".") +
            (answerHistory(task)?.let { " The Answer History is $it." } ?: "") +
            (repos?.let { " Name each repo in your Result by its Source map name or a git URL; ${ResultContract.names(it)}." } ?: "") +
            (verbosity?.let { " Write what the user reads at the ${it.value} verbosity defined in ${catalogue.verbosityFile}." } ?: "")
    }

    /** The Answer History's folder when [task]'s toggle ([ANSWER_HISTORY]) gives it the Answer History. */
    private fun answerHistory(task: LockedExternalTask): Path? = home.resolve("answer-history")
        .takeIf { task.extensionProperties[ANSWER_HISTORY]?.toBooleanStrictOrNull() ?: false }

    /**
     * When the Usage limit that [event] reports resets, or null if it reports none: a `rate_limit_event` rejection
     * gives `resetsAt`; one without it, a Subagent retrying on `rate_limit`, or a 429 `result` means 30 minutes.
     * Probed with claude 2.1.295 against a fake API (#36): a rejection is a top-level `rate_limit_event`, even when a
     * Subagent's request hit it, and it isn't retried. A plain 429 is retried as `subagent_retry` events first, and
     * the rejection without `resetsAt` comes only after the last retry. A 429 on the main session gives the rejection,
     * then a `result` with `api_error_status` 429, and a session that resumes with its first prompt intact.
     */
    private fun usageLimit(event: Map<*, *>): Instant? {
        val fallback = clock.instant() + Duration.ofMinutes(30)
        val info = event["rate_limit_info"] as? Map<*, *>
        return when {
            event["type"] == "rate_limit_event" && info?.get("status") == "rejected" ->
                (info["resetsAt"] as? Number)?.let { Instant.ofEpochSecond(it.toLong()) } ?: fallback
            (event["subagent_retry"] as? Map<*, *>)?.get("error_category") == "rate_limit" -> fallback
            event["type"] == "result" && event["api_error_status"] == 429 -> fallback
            else -> null
        }
    }

    /** Whether a Review changes names [task] as its Walkthrough, so its Story set is checked when it is done. */
    private fun walkthrough(task: LockedExternalTask) = repository.getBpmnModelInstance(task.processDefinitionId).getModelElementsByType(UserTask::class.java)
        .any { it.property(WALKTHROUGH) == task.activityId }

    /** The Task's name in its Process, for the Run log. */
    internal fun name(task: LockedExternalTask) =
        repository.getBpmnModelInstance(task.processDefinitionId).getModelElementById<FlowElement>(task.activityId)?.name ?: task.activityId

    /** A running session the user can Send a message into ([say]) or Stop ([interrupt]) while it is [open]. */
    internal interface Live {
        /** The Task's name, for the Run log. */
        val task: String
        val open: Boolean

        /** How a message Sent reaches the session, as the page says. */
        val hint: String
        fun say(message: String): Boolean
        fun interrupt(): Boolean
    }

    /**
     * A session's stdin. It stays open for the user's messages until Ants holds a valid Result and every message
     * written has been read. A message counts as read when the CLI replays it (`--replay-user-messages`), which claude
     * does as it takes the message in: at the next tool boundary, or in a turn of its own after a `result` line.
     */
    internal class Stdin(private val writer: BufferedWriter, override val task: String, private val home: Path) : Live {
        private var unread = 0

        @Volatile
        override var open = true
            private set

        override val hint = "It sees it at its next tool call."

        /** Whether the user stopped the session, so the `error_during_execution` that follows is `[stopped]`. */
        @Volatile
        var stopping = false
            private set

        @Synchronized
        override fun say(message: String) = write(mapOf("type" to "user", "message" to mapOf("role" to "user", "content" to userContent(message, home))))
            .also { if (it) unread++ }

        @Synchronized
        override fun interrupt(): Boolean {
            stopping = true // before the write, so the reader never sees the interrupt's result first
            return write(mapOf("type" to "control_request", "request_id" to UUID.randomUUID().toString(), "request" to mapOf("subtype" to "interrupt")))
                .also { if (!it) stopping = false }
        }

        @Synchronized
        fun read() {
            unread--
        }

        /** Closes stdin once every message written has been read; until then the session goes on to another Result. */
        @Synchronized
        fun closeWhenRead() {
            if (unread <= 0) close()
        }

        @Synchronized
        fun close() {
            if (open) runCatching { writer.close() } // a CLI that already exited leaves a broken pipe
            open = false
        }

        private fun write(message: Map<String, Any>): Boolean {
            if (!open) return false
            try {
                writer.write(json.writeValueAsString(message))
                writer.newLine()
                writer.flush()
                return true
            } catch (_: IOException) {
                close() // the CLI has exited
                return false
            }
        }
    }

    private fun flags(agent: Agent, agentTools: List<String>, level: Level, cwd: Path, schema: Map<*, *>, skills: List<Skill>, skillsFolder: Path?, session: String, resume: Boolean, mcp: String, antsTools: List<String>, workspace: Path, denied: List<String>): List<String> {
        val tools = agentTools.filter { it.substringBefore('(') !in OWN_TOOLS }.map { anchored(it, cwd) }
        val skill = listOf("Skill").takeIf { skillsFolder != null } // with no Skills allowed, no Skill tool
        val (subagents, types) = subagents(tools)
        // Probed on claude 2.1.299: a rule such as `Bash(git diff *)` lets `git diff --output=<any path>` write a file, and
        // the deny rule `Bash(git *--output*)` refuses it (git takes no abbreviation of `--output`)
        val gitOutput = if (tools.any { it.startsWith("Bash(git ") }) listOf("Bash(git *--output*)") else emptyList()
        val protectedCommandRules = settings.protectedCommands().keys.flatMap { listOf("Bash($it)", "Bash($it *)") }
        val disallowed = types + gitOutput + protectedCommandRules + denied + (listOf(settings.secretsFile, settings.file).map(Path::toAbsolutePath).map(Path::toString) + listOf("scripts", "drafts/scripts").map { "${home.resolve(it).toAbsolutePath()}/**" }).flatMap { listOf("Read(/$it)", "Edit(/$it)") }
        return STREAM_JSON + listOf(
            "--json-schema", json.writeValueAsString(schema),
            if (resume) "--resume" else "--session-id", session,
            "--permission-mode", "dontAsk",
            "--setting-sources", "user,project",
            "--settings", claudeSettings(workspace),
            "--strict-mcp-config",
            "--mcp-config", mcp,
            "--forward-subagent-text",
            // Skill, for the Skills [skillsFolder] loads: which are loaded is what limits them, not a Skill(x) rule.
            "--tools", (tools.map { it.substringBefore('(') } + skill.orEmpty()).distinct().joinToString(","),
            // claude checks a Write against Edit rules and ignores a Write(path) one, so a Write limit also goes out as Edit
            "--allowedTools", (tools + tools.filter { it.startsWith("Write(") }.map { "Edit(" + it.removePrefix("Write(") } + skill.orEmpty() + antsTools).distinct().joinToString(","),
        ) + subagents + (if (disallowed.isEmpty()) emptyList() else listOf("--disallowedTools", disallowed.joinToString(","))) + (skillsFolder?.let { listOf("--add-dir", "$it") } ?: emptyList()) +
            // A symlinked Skill's files can be read only with its real folder added too.
            skills.flatMap { listOf("--add-dir", it.folder.toString()) } + level.flags +
            (if (agent.instructions.isNotEmpty()) listOf("--append-system-prompt", agent.instructions) else emptyList())
    }

    private fun anchored(rule: String, cwd: Path): String {
        val tool = rule.substringBefore('(')
        val path = rule.substringAfter('(', "").removeSuffix(")").removePrefix("./")
        if (tool !in setOf("Write", "Edit") || path.isEmpty() || path.startsWith("/") || path.startsWith("~")) return rule
        return "$tool(/${cwd.toAbsolutePath()}/$path)"
    }

    private fun claudeSettings(workspace: Path): String {
        val base = mapOf("disableAllHooks" to true, "attribution" to mapOf("commit" to "", "pr" to "🐜 Created with [Ants](https://github.com/oyvij/ants)"))
        if (!workspace.resolve("AGENTS.md").exists()) return json.writeValueAsString(base)
        val above = generateSequence(workspace.toRealPath().parent) { it.parent }
            .flatMap { listOf(it.resolve("CLAUDE.md"), it.resolve("CLAUDE.local.md"), it.resolve(".claude/CLAUDE.md")) }
        return json.writeValueAsString(base +("claudeMdExcludes" to above.map(Path::toString).toList()))
    }

    /**
     * When the Agent tool is among [tools], every offered claude Agent as a Subagent type (`--agents`), and when the
     * Agent tool has a Limit, such as `Agent(searcher)`, every other type as a rule to deny: claude lets the Agent tool
     * start any type that isn't denied.
     */
    private fun subagents(tools: List<String>): Pair<List<String>, List<String>> {
        val agentTool = tools.filter { it.substringBefore('(') in SUBAGENT_TOOLS }
        if (agentTool.isEmpty()) return emptyList<String>() to emptyList()
        // Probed on claude 2.1.295 against a fake API: rules such as `Bash(git log *)` are accepted in `--agents`
        // tools, but a Subagent's permissions are its Task's (`--allowedTools` under dontAsk), so they narrow nothing more.
        val resolved = catalogue.agents(withHidden = true).map { it.name }.distinct().mapNotNull { runCatching { catalogue.agent(it) }.getOrNull() }
        val offered = resolved.filter { it.subagent && it.cli == "claude" }.associate { agent ->
            agent.name to listOfNotNull(
                "description" to catalogue.description(agent),
                "prompt" to agent.instructions,
                catalogue.subagentTools(agent)?.let { rules -> "tools" to rules.filter { it.substringBefore('(') !in OWN_TOOLS } },
                agent.model?.let { "model" to it },
            ).toMap()
        }
        val allowed = agentTool.takeIf { rules -> rules.all { '(' in it } }
            ?.flatMap { it.substringAfter('(').removeSuffix(")").split(',').map(String::trim) }
        val denied = allowed?.let { (BUILT_IN_SUBAGENTS + offered.keys).filter { it !in allowed }.map { "Agent($it)" } }.orEmpty()
        return (if (offered.isEmpty()) emptyList() else listOf("--agents", json.writeValueAsString(offered))) to denied
    }

    /** What `claude auth status` says of Ants' own Claude login, asked the way a launch runs; empty when it can't tell. */
    fun login(): Map<*, *> = runCatching {
        val process = cli("claude", home, listOf("auth", "status", "--json")).start()
        if (!process.waitFor(10, SECONDS)) process.destroyForcibly()
        json.readValue(process.inputReader().readText(), Map::class.java)
    }.getOrDefault(emptyMap<Any, Any>())

    /**
     * The latest usage claude reported: its `rate_limit_event`'s `unifiedWindows`, such as
     * `{"five_hour":{"utilization":0.04,"resetsAt":<epoch s>},"seven_day":{…}}`, utilization a fraction. Empty until a launch reports one.
     */
    fun usage(): Map<*, *> = if (!usageFile.exists()) emptyMap<Any, Any>()
    else runCatching { json.readValue(usageFile.readText(), Map::class.java) }
        .onFailure { log.warn("$usageFile isn't the usage claude last reported", it) }.getOrDefault(emptyMap<Any, Any>())

    /** Keeps the usage [event] reports; launches running at once each write it whole, and the last one wins. */
    private fun keepUsage(event: Map<*, *>) {
        val windows = (event["rate_limit_info"] as? Map<*, *>)?.get("unifiedWindows") ?: return
        usageFile.writeAtomically(json.writeValueAsString(windows))
    }

    /** The CLI's command prefix with [args], in [workspace], on the narrowed environment. */
    fun cli(cli: String, workspace: Path, args: List<String>): ProcessBuilder = ProcessBuilder(settings.command(cli) + args)
        .directory(workspace.toFile())
        .apply {
            environment().keys.retainAll(settings.env(cli).toSet())
            environment() -= PAID // never paid per token
            environment() += if (cli == "codex") mapOf("CODEX_HOME" to home.resolve("codex").createDirectories().toString()) // codex refuses one that isn't there
            else mapOf(
                "CLAUDE_CONFIG_DIR" to home.resolve("claude").toString(),
                "CLAUDE_CODE_DISABLE_BACKGROUND_TASKS" to "1",
                "CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH" to settings.subagentDepth().toString(),
            )
        }

    /** `--bare` never reads Ants' login, so a `-p` that turns it on by default would fail every Task on auth. */
    // ponytail: reads `--help` before every launch (~50 ms) and refuses when the `--bare` paragraph says "default";
    // wording like "not the default" would refuse too. Check for a real opt-out flag once claude ships one.
    private fun refuseBareByDefault(cli: String, workspace: Path) {
        val help = cli(cli, workspace, listOf("--help")).redirectErrorStream(true).start().run { inputReader().readText().also { waitFor() } }
        val bare = Regex("""^ *--bare\b.*?(?=^ {0,4}-|\z)""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)).find(help)?.value.orEmpty()
        check("default" !in bare.lowercase()) { "$cli -p now turns on --bare by default, which never reads Ants' login. Ants needs updating." }
    }

    internal class Refused(val request: PermissionRequest) : Exception(request.summary)

    /** The user stopped the session: `[stopped]`, not a failure. */
    internal class Stopped : Exception("Stopped by the user.")

    /** A failure of Ants' own, with what the `[failed]` page shows: the last stderr lines, or an invalid Result whole. */
    class Failed(override val message: String, val details: String? = null) : Exception(message)

    /** The Usage limit cut the session short; it continues once the limit resets at [resetsAt]. */
    internal class UsageLimit(val resetsAt: Instant) : Exception("Usage limit")

    companion object {
        const val TOPIC = "agent"

        /** Where an Approve's Send back, or a Round's answers, are left for the Task [key] of the Run in [workspace]. */
        fun sentBack(workspace: Path, key: String): Path = Transcript.file(workspace, key).resolveSibling("send-back")

        /** The user's choice on a held Task, [CONTINUE] or [FRESH], and their instruction, as local variables. */
        const val ACTION = "ants.action"
        const val INSTRUCTION = "ants.instruction"
        const val CONTINUE = "continue"
        const val FRESH = "fresh"

        /** The session id of a held Task's last launch. */
        private const val SESSION = "ants.session"

        const val PERMISSION = "ants.permission"
        const val GRANTED = "ants.granted"

        private const val REACHED_LEVEL = "ants.reached-level"
        private const val RETRIES = "ants.retries"
        private const val RETRY = "Carry on where you stopped, and answer again with your Result."

        /** The inputs a Task's last session was handed off, beside its Transcript. */
        private const val INPUTS = "inputs.json"
        private const val WORKER = "ants"
        private const val RESET = "The usage limit has reset. Carry on where you stopped."
        private val LOCK = Duration.ofDays(365)
        private const val STDERR_LINES = 20
        /** How often a conflicting landing resumes the session before the Task is `[failed]`. */
        private const val CONFLICT_ROUNDS = 2
        private val GRACE = Duration.ofSeconds(5)
        const val LOST_LOGIN = "Ants' Claude login is missing or expired. Log in again (Settings)"

        /**
         * The flags of a headless claude session both ways in stream-json, as an Agent Task and the Process Builder run
         * it: messages in on stdin, each replayed as claude reads it, and its events out with the text as it streams.
         */
        val STREAM_JSON = listOf(
            "-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages", "--input-format", "stream-json", "--replay-user-messages",
        )

        /**
         * An Agent's tools when its file names none. claude 2.1.295 has no TodoWrite; its checklist tools are these four
         * Task*. Not `Agent`: an Agent starts Subagents only when its file names the Agent tool, as the implementer's does.
         */
        val DEFAULT_TOOLS = listOf(
            "Bash", "Edit", "Glob", "Grep", "NotebookEdit", ProtectedCommands.AGENT_TOOL, "Read", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "WebFetch", "WebSearch", "Write",
        )
        /** The variables Ants strips from every environment it gives a CLI or a Protected Command, so nothing is paid per token. */
        val PAID = listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_RETRY_WATCHDOG", "OPENAI_API_KEY", "CODEX_API_KEY")

        /** Ants' MCP tool `run`, which runs a Protected Command ([ProtectedCommands]). */
        const val RUN_TOOL = "${Mcp.CLAUDE_PREFIX}run"

        private val OWN_TOOLS = setOf("Skill", ProtectedCommands.AGENT_TOOL)

        /** The tool that starts Subagents: `Agent`, formerly `Task`. */
        val SUBAGENT_TOOLS = setOf("Agent", "Task")

        // ponytail: the types claude 2.1.295 builds in, as its system/init lists them; a new built-in type escapes a
        // Limit until it is added here.
        private val BUILT_IN_SUBAGENTS = listOf("claude", "Explore", "general-purpose", "Plan", "statusline-setup")
        private val json = JsonMapper.shared()
    }
}

/**
 * Runs [complete], again while another path's Task wins the race to a shared execution, such as a parallel join, to
 * `runs` or to a multi-instance's Results: the engine rolls the loser back whole, so trying again is safe.
 */
fun onClash(complete: () -> Unit) {
    for (attempt in 1..10) {
        try {
            return complete()
        } catch (e: OptimisticLockingException) {
            if (attempt == 10) throw e
            Thread.sleep((10L..100L).random()) // apart, so the losers don't clash again
        }
    }
}

/** [instant] as the user reads it on this [Clock]: `15:45`, with the day in front when it isn't today. */
fun Clock.local(instant: Instant): String {
    val time = instant.atZone(zone)
    return time.format(DateTimeFormatter.ofPattern(if (time.toLocalDate() == LocalDate.now(this)) "HH:mm" else "EEE d MMM HH:mm"))
}
