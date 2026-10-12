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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
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
    private val clock: Clock,
    @Value("\${ants.home}") private val home: Path,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val poller = Executors.newSingleThreadScheduledExecutor()
    /** Each live CLI process and the Run it works for. */
    private val clis = ConcurrentHashMap<Process, String>()
    private val live = AtomicInteger()
    private val stdins = ConcurrentHashMap<String, Live>() // by key(run, instance(task))

    private fun key(run: String, task: String) = "$run/$task"
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

    private class Held(val reason: String, val check: () -> String?)

    /** Why the Task with external task [id] is held before launch, such as `Blocked by 01`, if it is. */
    fun heldBy(id: String): String? = heldOnTickets[id]?.reason

    /** How many Agent Tasks are running now. */
    val running get() = live.get()

    /** How a message Sent to [task] of [run] reaches its session, or null when no session takes messages. */
    fun sending(run: String, task: String) = stdins[key(run, task)]?.takeIf { it.open }?.hint

    /**
     * Sends [message] into the live session of [task] in [run], where claude reads it at its next tool boundary and
     * codex has its turn stopped and resumes with it ([Live.hint]), and appends
     * it to the Run log. False when no session takes it any more, such as once Ants holds the Task's Result.
     */
    fun send(run: String, task: String, message: String): Boolean {
        val stdin = stdins[key(run, task)]?.takeIf { it.say(message) } ?: return false
        runLog(home.runWorkspace(run), stdin.task, "Send", message)
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
    private fun kill(process: Process) {
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
                    else if (reason != null) heldOnTickets[id] = Held(reason, held.check)
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
                        launch(task)
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
        try {
            val run = runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult().rootProcessInstanceId
            val workspace = home.runWorkspace(run)
            if (ticketed(task, run, workspace)) return
            val agent = catalogue.agent(task.extensionProperties[AGENT] ?: error("${task.activityId} names no $AGENT"))
            if (hold(task, agent.cli)) return // keeping its choice for the launch after the reset
            runtime.removeVariablesLocal(task.executionId, listOf(ACTION, INSTRUCTION, SESSION)) // the choice is used once
            val skill = catalogue.skill(task.extensionProperties[SKILL] ?: error("${task.activityId} names no $SKILL"))
            val used = catalogue.uses(skill)
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
            transcript = Transcript.Writer(Transcript.file(workspace, instance)).apply { ants("launch") }
            session?.let(last::writeText)
            val started = { id: String -> session = id; last.writeText(id) }
            val resumed = resume != null || again != null // claude knows the session already
            // The user's Continue, or a Send back to a session claude has: their words alone, no hand-off again.
            val continued = resume != null || (comment != null && again != null)
            val result = try {
                var result = session(task, instance, agent, skill, used, run, workspace, cwd, transcript, session, started, resumed, task.variables[INSTRUCTION] as? String ?: comment, handedOff = !continued)
                // A landing that conflicts resumes the session to resolve it, and is retried after each fix.
                var rounds = 0
                while (parallel && result["outcome"] == "done") {
                    val conflict = landing.land(workspace, instance) ?: break
                    if (rounds++ == CONFLICT_ROUNDS) throw Failed("${conflict.substringBefore("\n\n")} It still conflicts after $CONFLICT_ROUNDS rounds.", conflict.substringAfter("\n\n"))
                    val resolve = "$conflict\n\nThe rebase was aborted. Rebase onto the work branch in each repo named, resolve the conflicts, and answer again with your Result."
                    result = session(task, instance, agent, skill, used, run, workspace, cwd, transcript, session, started, true, resolve, handedOff = false)
                }
                result
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
                else -> "[failed] ${e.message ?: e}"
            }
            runCatching { transcript?.ants("end", reason) }
            if (stopping || cancelled) return
            if (e !is Stopped) log.warn("Agent Task ${task.activityId} failed", e)
            incident(task, session, reason, (e as? Failed)?.details)
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
        heldOnTickets[task.id] = Held(check() ?: return false, check)
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

    /**
     * Puts [task] on hold as an incident, its tag and reason in [message] and anything longer in [details], keeping
     * its [session], if claude ever started one, for Continue.
     */
    private fun incident(task: LockedExternalTask, session: String?, message: String, details: String?) = runCatching {
        if (session != null) runtime.setVariableLocal(task.executionId, SESSION, session)
        externalTasks.handleFailure(task.id, WORKER, message, details, 0, 0)
    }.onFailure { log.warn("Agent Task ${task.activityId} couldn't be put on hold", it) }

    /**
     * Runs [skill], which uses [used], in one CLI session in [cwd] (the Run [workspace], or an instance's folder in it),
     * every line it prints going to [transcript], and returns its Result: the last valid one, once every message written to the session has been read. A new
     * [session] starts with the hand-off prompt and the user's [instruction], if any; a [resume]d one with the
     * instruction alone, unless it is [handedOff] again, as when the flow comes back to the Task. A CLI that names its
     * own sessions, codex, tells Ants a new one's id through [started].
     */
    private fun session(
        task: LockedExternalTask, instance: String, agent: Agent, skill: Skill, used: List<Skill>, run: String, workspace: Path, cwd: Path, transcript: Transcript.Writer,
        session: String?, started: (String) -> Unit, resume: Boolean, instruction: String?, handedOff: Boolean = !resume,
    ): Map<*, *> {
        val inputs = inputs(task)
        val vault = vault(task, skill)
        val prompt = if (!handedOff) instruction.orEmpty()
        else handOff(task, skill, used, cwd, vault) + "\n\n" + json.writeValueAsString(inputs) + (if (instruction.isNullOrBlank()) "" else "\n\nFrom the user:\n$instruction")
        // The Task's result-schema override replaces the Skill's result.schema.json; Checks refuse one that isn't valid.
        val override = task.extensionProperties[RESULT_SCHEMA]?.takeIf(String::isNotBlank)?.let { json.readValue(it, Map::class.java) }
        val output = override ?: skill.outputSchema
        val schema = ResultContract.schema(output)
        val judge = Judge(agent.cli, schema, workspace, walkthrough(task))
        // A knowledge Task, one with an `approval` input, may write the Vault only once it holds the user's approval
        // (ADR 0007): until then the Vault is read-only to it, but for Bash. codex can read it without an --add-dir.
        val gated = vault?.takeIf { "approval" in inputs && (inputs["approval"] as? Map<*, *>)?.get("approved") != true }
        if (agent.cli == "codex") return codex(task, instance, agent, run, workspace, cwd, vault.takeIf { gated == null }, transcript, session, started, prompt, ResultContract.strict(output), judge)
        val shared = if (cwd == workspace) emptyList() else listOf("--add-dir", "$workspace") // what an instance's links lead to
        val places = vault?.let { listOf("--add-dir", "$it") }.orEmpty() // the Vault's path goes nowhere else (ADR 0002)
            .plus(answerHistory(task, skill)?.let { listOf("--add-dir", "${it.createDirectories()}") }.orEmpty()) // so a Read reaches it (ADR 0010)
        val process = cli(agent.cli, cwd, flags(agent, schema, listOf(skill) + used, session!!, resume, gated?.let { listOf("Write(/$it/**)", "Edit(/$it/**)") }.orEmpty()) + shared + places).start()
        val stdin = Stdin(process.outputWriter(), name(task))
        clis[process] = workspace.fileName.toString() // the Run workspace is runs/<run>/
        val stderr = drain(process)
        try {
            stdin.say(prompt)
            stdins[key(run, instance)] = stdin // only now, so the prompt goes first
            var stopped = false
            var limit: Instant? = null
            var answered = false // a result line came after the user's Stop
            process.inputReader().forEachLine { line ->
                transcript.line(line)
                if (judge.failure != null || stopped || limit != null || (judge.valid != null && !stdin.open)) return@forEachLine
                val event = runCatching { json.readValue(line, Map::class.java) }.getOrNull() ?: return@forEachLine
                if (event["type"] == "user" && event["isReplay"] == true) stdin.read()
                if (event["type"] == "rate_limit_event") keepUsage(event)
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
            val tail = stderr()
            if (stopped || (stdin.stopping && !answered && judge.failure == null)) throw Stopped()
            judge.failure?.let { throw Failed(it.message, it.details ?: tail) }
            if (exit != 0) throw Failed("${agent.cli} exited with code $exit", tail)
            return judge.valid ?: throw Failed("${agent.cli} ended without a Result", tail)
        } finally {
            stdin.close()
            stdins.remove(key(run, instance), stdin)
            clis -= process
        }
    }

    /**
     * Runs a codex session, one `codex exec` per turn, each after the first resuming the session (codex's thread) the
     * first one started. codex reads its prompt from stdin to the end and takes nothing more while a turn runs, so a
     * message the user Sends interrupts the turn (SIGINT, as Ctrl-C does) and resumes the session with it, as does a
     * Result sent back to fix. Its Result is its last message, JSON under `--output-schema`. Settled from codex-rs's
     * source (#73): `read_prompt_from_stdin` in exec/src/lib.rs reads stdin with `read_to_end`, and an interrupted turn
     * prints no `turn.completed` and exits 1.
     */
    private fun codex(
        task: LockedExternalTask, instance: String, agent: Agent, run: String, workspace: Path, cwd: Path, vault: Path?, transcript: Transcript.Writer,
        session: String?, started: (String) -> Unit, prompt: String, schema: Map<*, *>, judge: Judge,
    ): Map<*, *> {
        Agent.sandboxError(agent.cli, agent.sandbox)?.let { throw Failed(it) } // never passed on to --sandbox
        val schemaFile =Transcript.file(workspace, instance).resolveSibling("result.schema.json").apply { writeText(json.writeValueAsString(schema)) }
        val turns = Turns(name(task))
        stdins[key(run, instance)] = turns
        var thread = session
        var message = prompt
        try {
            while (true) {
                val resume = thread?.let { listOf("resume", it) }.orEmpty()
                // codex doesn't print what it was told, so Ants writes it the way claude replays it.
                transcript.line(json.writeValueAsString(mapOf("type" to "user", "message" to mapOf("role" to "user", "content" to message))))
                val process = cli(agent.cli, cwd, codexFlags(agent, schemaFile, listOfNotNull(workspace.takeIf { cwd != workspace }, vault)) + resume + "-").start()
                clis[process] = workspace.fileName.toString()
                val stderr = drain(process)
                val fixes = mutableListOf<String>()
                var limit: Instant? = null
                var error: String? = null // the last error codex printed
                try {
                    turns.running(process)
                    runCatching { process.outputWriter().use { it.write(message) } } // a codex that already exited leaves a broken pipe
                    var answer: String? = null // its last message
                    process.inputReader().forEachLine { line ->
                        transcript.line(line)
                        if (judge.failure != null || limit != null) return@forEachLine
                        val event = runCatching { json.readValue(line, Map::class.java) }.getOrNull() ?: return@forEachLine
                        val item = event["item"] as? Map<*, *>
                        when (event["type"]) {
                            "thread.started" -> event["thread_id"]?.toString()?.takeIf { it != thread }?.let { thread = it; started(it) }
                            "item.completed" -> if (item?.get("type") == "agent_message") answer = item["text"]?.toString()
                            "turn.completed" -> judge.judge(answer?.let { runCatching { ResultContract.withoutNulls(json.readValue(it, Map::class.java)) }.getOrNull() }, listOf(answer), fixes::add)
                            "error", "turn.failed" -> {
                                error = (event["message"] ?: (event["error"] as? Map<*, *>)?.get("message"))?.toString()
                                limit = error?.let(::codexLimit)?.also { kill(process) } // the session continues after the reset
                            }
                        }
                    }
                    val exit = process.waitFor()
                    limit?.let { throw UsageLimit(it) }
                    val tail = stderr()
                    val sent = turns.ended(judge.valid != null && fixes.isEmpty())
                    if (turns.stopping) return judge.valid ?: throw Stopped()
                    judge.failure?.let { throw Failed(it.message, it.details ?: tail) }
                    if (fixes.isEmpty() && sent.isEmpty()) {
                        judge.valid?.let { return it }
                        throw Failed(if (exit != 0) "${agent.cli} exited with code $exit" + (error?.let { ": $it" } ?: "") else "${agent.cli} ended without a Result", tail)
                    }
                    if (thread == null) throw Failed("${agent.cli} started no session to resume", tail)
                    message = (fixes + sent).joinToString("\n\n")
                } finally {
                    clis -= process
                }
            }
        } finally {
            turns.close()
            stdins.remove(key(run, instance), turns)
        }
    }

    /** Reads [process]'s stderr as it comes, and returns what gives its last lines once it has ended. */
    private fun drain(process: Process): () -> String? {
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
     * Judges each Result a session gives as it comes, the last valid one counting. An invalid one, or a walkthrough's
     * Story set failing its checks, is sent back into the session once; the second is a [failure].
     */
    private inner class Judge(private val cli: String, private val schema: Map<*, *>, private val workspace: Path, private val walkthrough: Boolean) {
        var valid: Map<*, *>? = null
            private set
        var failure: Failed? = null
        private var retried = false
        private var storyChecked = false // a walkthrough's Story set failed the checks once already

        /** Judges [result], null when a turn ended without one, as [ended] tells; [say] sends a fix into the session. */
        fun judge(result: Any?, ended: List<Any?>, say: (String) -> Unit) {
            val errors = result?.let { ResultContract.errors(it, schema, workspace) }
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
     * The Vault's folder when [task] gets the Vault: its toggle ([VAULT]) has the final say, else its [skill]'s opt-in.
     * Null when off, or with no Vault configured, which the pre-Run check (#55) refuses.
     */
    // ponytail: the default Vault, until a Process picks its own.
    private fun vault(task: LockedExternalTask, skill: Skill): Path? = settings.vaults()[settings.defaultVault()]?.let(Settings::expand)
        ?.takeIf { task.extensionProperties[VAULT]?.toBooleanStrictOrNull() ?: catalogue.vault(skill) }

    /**
     * The hand-off line: the Skill and the workspace, the Skills it [used], and where the [vault] and Source map and the
     * Answer History are, each only when on: the Task's [ANSWER_HISTORY] toggle has the final say, else the Skill's opt-in.
     */
    // ponytail: the default Source map, until a Process picks its own.
    private fun handOff(task: LockedExternalTask, skill: Skill, used: List<Skill>, workspace: Path, vault: Path?): String {
        val places = if (vault == null) emptyList() else listOfNotNull(
            "the Vault is $vault",
            settings.sourceMaps()[settings.defaultSourceMap()]?.let { "the Source map is ${Settings.expand(it)}" },
        )
        return "Follow the Skill in ${skill.file} for the workspace at $workspace." +
            (if (used.isEmpty()) "" else " It uses the Skills ${used.joinToString { "${it.name} (${it.file})" }}.") +
            (if (places.isEmpty()) "" else " " + places.joinToString(" and ").replaceFirstChar(Char::uppercase) + ".") +
            (answerHistory(task, skill)?.let { " The Answer History is $it." } ?: "")
    }

    /** The Answer History's folder when [task] gets it: its toggle ([ANSWER_HISTORY]) has the final say, else its [skill]'s opt-in. */
    private fun answerHistory(task: LockedExternalTask, skill: Skill): Path? = home.resolve("answer-history")
        .takeIf { task.extensionProperties[ANSWER_HISTORY]?.toBooleanStrictOrNull() ?: skill.answerHistory }

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
    private fun name(task: LockedExternalTask) =
        repository.getBpmnModelInstance(task.processDefinitionId).getModelElementById<FlowElement>(task.activityId)?.name ?: task.activityId

    /** A running session the user can Send a message into ([say]) or Stop ([interrupt]) while it is [open]. */
    private interface Live {
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
    private class Stdin(private val writer: BufferedWriter, override val task: String) : Live {
        private var unread = 0

        @Volatile
        override var open = true
            private set

        override val hint = "The Agent reads it at its next tool call."

        /** Whether the user stopped the session, so the `error_during_execution` that follows is `[stopped]`. */
        @Volatile
        var stopping = false
            private set

        @Synchronized
        override fun say(message: String) = write(mapOf("type" to "user", "message" to mapOf("role" to "user", "content" to message)))
            .also { if (it) unread++ }

        @Synchronized
        override fun interrupt() = write(mapOf("type" to "control_request", "request_id" to UUID.randomUUID().toString(), "request" to mapOf("subtype" to "interrupt")))
            .also { if (it) stopping = true }

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

    /**
     * A codex session's turns, one process each. A message Sent, or Stop, interrupts the running turn as Ctrl-C does;
     * the session resumes with the messages once the turn has [ended]. It stays open until Ants holds a valid Result
     * and no message came during the turn that gave it.
     */
    private class Turns(override val task: String) : Live {
        private val sent = mutableListOf<String>()
        private var process: Process? = null

        @Volatile
        override var open = true
            private set

        override val hint = "codex takes no message while it works: Send stops it and resumes its session with your message."

        @Volatile
        var stopping = false
            private set

        @Synchronized
        override fun say(message: String): Boolean {
            if (!open) return false
            sent += message
            process?.let(::interrupt)
            return true
        }

        @Synchronized
        override fun interrupt(): Boolean {
            if (!open) return false
            stopping = true
            process?.let(::interrupt)
            return true
        }

        /** The next turn runs in [process], interrupted at once if a message or Stop came since the last one ended. */
        @Synchronized
        fun running(process: Process) {
            this.process = process
            if (sent.isNotEmpty() || stopping) interrupt(process)
        }

        /** The messages Sent during the turn that has just ended; with none, a turn that gave a valid Result ([done]) closes the session. */
        @Synchronized
        fun ended(done: Boolean): List<String> {
            process = null
            val taken = sent.toList()
            sent.clear()
            if (taken.isEmpty() && done) open = false
            return taken
        }

        @Synchronized
        fun close() {
            open = false
        }

        /** SIGINT to [process] and every process under it, as Ctrl-C in a terminal: codex ends its turn and exits. */
        private fun interrupt(process: Process) = (listOf(process.toHandle()) + process.descendants().toList())
            .forEach { runCatching { ProcessBuilder("kill", "-INT", "${it.pid()}").start() } }
    }

    /** claude's flags, with the permission rules [denied] besides the Subagent types a Limit leaves out. */
    private fun flags(agent: Agent, schema: Map<*, *>, skills: List<Skill>, session: String, resume: Boolean, denied: List<String>): List<String> {
        val tools = (agent.tools ?: DEFAULT_TOOLS).filter { it.substringBefore('(') != "Skill" }
        val (subagents, types) = subagents(tools)
        val disallowed = types + denied
        return listOf(
            "-p",
            "--output-format", "stream-json", "--verbose", "--include-partial-messages",
            "--input-format", "stream-json", "--replay-user-messages",
            "--json-schema", json.writeValueAsString(schema),
            if (resume) "--resume" else "--session-id", session,
            "--permission-mode", "dontAsk",
            "--setting-sources", "user,project",
            "--settings", """{"disableAllHooks":true}""",
            "--strict-mcp-config",
            "--disable-slash-commands",
            "--forward-subagent-text",
            "--tools", tools.map { it.substringBefore('(') }.distinct().joinToString(","),
            "--allowedTools", tools.joinToString(","),
        ) + subagents + (if (disallowed.isEmpty()) emptyList() else listOf("--disallowedTools", disallowed.joinToString(","))) + skills.flatMap { listOf("--add-dir", it.folder.toString()) } + (if (agent.model != null) listOf("--model", agent.model) else emptyList()) +
            (if (agent.instructions.isNotEmpty()) listOf("--append-system-prompt", agent.instructions) else emptyList())
    }

    /**
     * codex's flags: JSON lines out, the Result's [schema] file, the Agent's sandbox setting instead of tools, its model,
     * and its standing instructions as `developer_instructions` (a JSON string is a TOML one too). The Run workspace
     * isn't a git repository. The [writable] folders besides the working one are an instance's Run workspace and the Vault.
     */
    private fun codexFlags(agent: Agent, schema: Path, writable: List<Path>): List<String> =
        listOf("exec", "--json", "--skip-git-repo-check", "--output-schema", "$schema", "--sandbox", agent.sandbox) +
            (if (agent.model != null) listOf("-m", agent.model) else emptyList()) +
            (if (agent.instructions.isNotEmpty()) listOf("-c", "developer_instructions=" + json.writeValueAsString(agent.instructions)) else emptyList()) +
            writable.flatMap { listOf("--add-dir", "$it") }

    /**
     * When codex's Usage limit resets, from its [message], or null when the message isn't one. codex says
     * "You’ve hit your usage limit. … Try again at 3:45 PM." (or "… or try again at …"), with the date in front,
     * "Oct 13th, 2026 3:45 PM", when it isn't today, in local time; "Try again later." means 30 minutes
     * (`UsageLimitReachedError` and `format_retry_timestamp` in codex-rs/protocol/src/error.rs).
     */
    private fun codexLimit(message: String): Instant? {
        if (!message.contains("usage limit", ignoreCase = true)) return null
        val at = Regex("""(?i)try again at (?:(\w{3}) (\d{1,2})(?:st|nd|rd|th), (\d{4}) )?(\d{1,2}:\d{2} [AP]M)""").find(message)
        return at?.destructured?.let { (month, day, year, time) ->
            runCatching {
                val date = if (year.isEmpty()) LocalDate.now(clock) else LocalDate.parse("$month $day $year", DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH))
                LocalTime.parse(time.uppercase(), DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).atDate(date).atZone(clock.zone).toInstant()
            }.getOrNull()
        } ?: (clock.instant() + Duration.ofMinutes(30))
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
        val offered = catalogue.agents().filter { it.subagent && it.cli == "claude" }.associate { agent ->
            agent.name to listOfNotNull(
                "description" to agent.description,
                "prompt" to agent.instructions,
                agent.tools?.let { rules -> "tools" to rules.filter { it.substringBefore('(') != "Skill" } },
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
            environment() -= listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_RETRY_WATCHDOG", "OPENAI_API_KEY", "CODEX_API_KEY") // never paid per token
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

    /** The user stopped the session: `[stopped]`, not a failure. */
    private class Stopped : Exception("Stopped by the user.")

    /** A failure of Ants' own, with what the `[failed]` page shows: the last stderr lines, or an invalid Result whole. */
    class Failed(override val message: String, val details: String? = null) : Exception(message)

    /** The Usage limit cut the session short; it continues once the limit resets at [resetsAt]. */
    private class UsageLimit(val resetsAt: Instant) : Exception("Usage limit")

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
        private const val WORKER = "ants"
        private const val RESET = "The usage limit has reset. Carry on where you stopped."
        private val LOCK = Duration.ofDays(365)
        private const val STDERR_LINES = 20
        /** How often a conflicting landing resumes the session before the Task is `[failed]`. */
        private const val CONFLICT_ROUNDS = 2
        private val GRACE = Duration.ofSeconds(5)
        const val LOST_LOGIN = "Ants' Claude login is missing or expired. Log in again (Settings)"

        /**
         * An Agent's tools when its file names none. claude 2.1.295 has no TodoWrite; its checklist tools are these four
         * Task*. Not `Agent`: an Agent starts Subagents only when its file names the Agent tool, as the implementer's does.
         */
        val DEFAULT_TOOLS = listOf(
            "Bash", "Edit", "Glob", "Grep", "NotebookEdit", "Read", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "WebFetch", "WebSearch", "Write",
        )
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
