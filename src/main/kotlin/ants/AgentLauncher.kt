package ants

import jakarta.annotation.PreDestroy
import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.externaltask.LockedExternalTask
import org.operaton.bpm.model.bpmn.instance.FlowElement
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
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The in-process worker. It fetches ready Agent Tasks (external tasks on topic `agent`) only for free slots under
 * the cap, oldest first across Runs, and runs each one's Skill through `claude -p`. A `done` Result completes the
 * task, stored as a process variable named after the Task's BPMN id; a `stuck` one or a failure is an incident. When
 * the user acts on it, the Notification list leaves their choice in the Task's local variables: Continue resumes the
 * session with their instruction, Start fresh starts a new one with it under "From the user:". While a session runs,
 * the user can [send] it a message or [stop] it. A Task that hits the Usage limit pauses its CLI until the reset, then
 * continues its session the same way, with the reset message.
 */
@Component
class AgentLauncher(
    private val externalTasks: ExternalTaskService,
    private val runtime: RuntimeService,
    private val repository: RepositoryService,
    private val settings: Settings,
    private val catalogue: Catalogue,
    private val clock: Clock,
    @Value("\${ants.home}") private val home: Path,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val poller = Executors.newSingleThreadScheduledExecutor()
    /** Each live CLI process and the Run it works for. */
    private val clis = ConcurrentHashMap<Process, String>()
    private val live = AtomicInteger()
    private val stdins = ConcurrentHashMap<String, Stdin>() // by `<run>/<task id>`
    private val usageFile = home.resolve("claude-usage.json")

    // ponytail: one pause gate per CLI, not per model, kept in memory; after a restart the held Tasks wait out
    // their locks, which end at the reset, and the first Task to hit the limit again shuts the gate again.
    private val gates = ConcurrentHashMap<String, Instant>() // CLI -> when its gate opens
    private val held = ConcurrentHashMap<String, String>() // external task id -> the CLI holding it

    /** How many Agent Tasks are running now. */
    val running get() = live.get()

    /** Whether [task] of [run] has a session that still takes messages. */
    fun live(run: String, task: String) = stdins["$run/$task"]?.open == true

    /**
     * Writes [message] into the live session of [task] in [run], which reads it at its next tool boundary, and appends
     * it to the Run log. False when no session takes it any more, such as once Ants holds the Task's Result.
     */
    fun send(run: String, task: String, message: String): Boolean {
        val stdin = stdins["$run/$task"]?.takeIf { it.say(message) } ?: return false
        runLog(home.resolve("runs").resolve(run), stdin.task, "Send", message)
        return true
    }

    /** Interrupts the live session of [task] in [run], which leaves the Task `[stopped]`. False when there is none. */
    fun stop(run: String, task: String) = stdins["$run/$task"]?.interrupt() == true

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
    fun stop() {
        poller.shutdownNow()
        clis.keys.forEach(Process::destroy)
    }

    /** Kills the live CLI processes of [run], once the Run is cancelled. */
    fun cancel(run: String) = clis.filterValues { it == run }.keys.forEach { it.toHandle().destroy() }

    private fun fetch() {
        try {
            gates.filterValues { !it.isAfter(clock.instant()) }.keys.forEach(::tryNow)
            val free = settings.agentTasks() - live.get()
            if (free <= 0) return
            externalTasks.fetchAndLock().workerId(WORKER).maxTasks(free).orderByCreateTime().asc()
                .subscribe().topic(TOPIC, LOCK.toMillis()).localVariables().includeExtensionProperties().execute()
                .forEach { task ->
                    live.incrementAndGet()
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

    /** The CLIs paused by the Usage limit, and when each resumes. */
    fun pauses(): Map<String, Instant> = gates.toMap()

    /** When the Task with external task [id] resumes, if a pause holds it. */
    fun pausedUntil(id: String): Instant? = held[id]?.let(gates::get)

    /** Opens [cli]'s gate and unlocks the Tasks it held, so they launch as slots free up. */
    fun tryNow(cli: String) = synchronized(gates) {
        gates.remove(cli)
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
            val agent = catalogue.agent(task.extensionProperties["ants.agent"] ?: error("${task.activityId} names no ants.agent"))
            if (hold(task, agent.cli)) return // keeping its choice for the launch after the reset
            runtime.removeVariablesLocal(task.executionId, listOf(ACTION, INSTRUCTION, SESSION)) // the choice is used once
            val run = runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult().rootProcessInstanceId
            val workspace = home.resolve("runs").resolve(run)
            val skill = catalogue.skill(task.extensionProperties["ants.skill"] ?: error("${task.activityId} names no ants.skill"))
            check(agent.cli == "claude") { "Agent ${agent.name} runs ${agent.cli}, and only claude runs for now" }
            val used = catalogue.uses(skill)
            // ponytail: a Task whose catalogue.vault(skill) is on runs without the Vault until #69 hands it over (its
            // path in the hand-off line and an --add-dir).
            refuseBareByDefault(agent.cli, workspace)
            session = session ?: UUID.randomUUID().toString()
            transcript = Transcript.Writer(Transcript.file(workspace, task.activityId)).apply { ants("launch") }
            val result = try {
                session(task, agent, skill, used, run, workspace, transcript, session, resume != null, task.variables[INSTRUCTION] as? String)
            } catch (limit: UsageLimit) {
                val until = limit.resetsAt + Duration.ofMinutes(1)
                transcript.ants("end", "Paused by the ${agent.cli} usage limit until ${clock.local(until)}.")
                runtime.setVariablesLocal(task.executionId, mapOf(ACTION to CONTINUE, INSTRUCTION to RESET, SESSION to session))
                hold(task, agent.cli, until)
                return
            }
            if (result["outcome"] == "done") {
                transcript.ants("end", "Done.")
                externalTasks.complete(task.id, WORKER, mapOf(task.activityId to result))
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
     * Puts [task] on hold as an incident, its tag and reason in [message] and anything longer in [details], keeping
     * its [session], if claude ever started one, for Continue.
     */
    private fun incident(task: LockedExternalTask, session: String?, message: String, details: String?) = runCatching {
        if (session != null) runtime.setVariableLocal(task.executionId, SESSION, session)
        externalTasks.handleFailure(task.id, WORKER, message, details, 0, 0)
    }.onFailure { log.warn("Agent Task ${task.activityId} couldn't be put on hold", it) }

    /**
     * Runs [skill], which uses [used], in one CLI session in [workspace], every line it prints going to [transcript],
     * and returns its Result: the last valid one, once every message written to the session has been read. A new
     * [session] starts with the hand-off prompt and the user's [instruction], if any; a [resume]d one with the
     * instruction alone.
     */
    private fun session(
        task: LockedExternalTask, agent: Agent, skill: Skill, used: List<Skill>, run: String, workspace: Path, transcript: Transcript.Writer,
        session: String, resume: Boolean, instruction: String?,
    ): Map<*, *> {
        val handOff = "Follow the Skill in ${skill.file} for the workspace at $workspace." +
            (if (used.isEmpty()) "" else " It uses the Skills ${used.joinToString { "${it.name} (${it.file})" }}.")
        val inputs = task.variables.filterKeys { !it.startsWith("ants.") }.toSortedMap()
        val prompt = if (resume) instruction.orEmpty()
        else "$handOff\n\n" + json.writeValueAsString(inputs) + (if (instruction.isNullOrBlank()) "" else "\n\nFrom the user:\n$instruction")
        val schema = ResultContract.schema(skill.outputSchema)
        val process = cli(agent.cli, workspace, flags(agent, schema, listOf(skill) + used, session, resume)).start()
        val stdin = Stdin(process.outputWriter(), name(task))
        clis[process] = workspace.fileName.toString() // the Run workspace is runs/<run>/
        val stderr = ArrayDeque<String>()
        val drain = Thread.ofVirtual().start {
            process.errorReader().forEachLine { synchronized(stderr) { stderr += it; if (stderr.size > STDERR_LINES) stderr.removeFirst() } }
        }
        try {
            stdin.say(prompt)
            stdins["$run/${task.activityId}"] = stdin // only now, so the prompt goes first
            var valid: Map<*, *>? = null
            var failure: Failed? = null
            var stopped = false
            var retried = false
            var limit: Instant? = null
            process.inputReader().forEachLine { line ->
                transcript.line(line)
                if (failure != null || stopped || limit != null || (valid != null && !stdin.open)) return@forEachLine
                val event = runCatching { json.readValue(line, Map::class.java) }.getOrNull() ?: return@forEachLine
                if (event["type"] == "user" && event["isReplay"] == true) stdin.read()
                if (event["type"] == "rate_limit_event") keepUsage(event)
                // ponytail: claude sends system/init only once it has read the prompt, so with an API key the first
                // request may already be on its way when Ants kills it.
                val apiKey = (event["apiKeySource"] as? String)?.takeIf { event["subtype"] == "init" && it != "none" }
                if (event["error"] == "authentication_failed" || apiKey != null) {
                    failure = Failed(LOST_LOGIN, apiKey?.let { "${agent.cli} would use an API key (apiKeySource: $it), and Ants runs only on its own login." })
                    stdin.close()
                    process.toHandle().destroy() // unlike Process.destroy, leaves the rest of stdout to read
                    return@forEachLine
                }
                usageLimit(event)?.let {
                    limit = it // the session continues after the reset
                    stdin.close()
                    process.toHandle().destroy()
                    return@forEachLine
                }
                if (event["type"] != "result") return@forEachLine
                val result = event["structured_output"]
                val errors = result?.let { ResultContract.errors(it, schema, workspace) }
                valid = null // the last result line is the one that counts
                when {
                    errors == null && stdin.stopping -> stopped = true
                    errors == null -> failure = Failed(
                        listOf("${agent.cli} ended without a Result:", event["subtype"], event["result"], (event["errors"] as? List<*>)?.joinToString("; "))
                            .filter { it != null && it != "" }.joinToString(" "),
                    )
                    errors.isEmpty() -> { valid = result as Map<*, *> }
                    !retried -> {
                        retried = true
                        stdin.say("Your Result is invalid:\n${errors.joinToString("\n") { "- $it" }}\nAnswer again with a valid Result.")
                    }
                    else -> failure = Failed("The Result is invalid: ${errors.joinToString("; ")}", errors.joinToString("\n") + "\n\n" + json.writeValueAsString(result))
                }
                if (failure != null || stopped) stdin.close() else if (valid != null) stdin.closeWhenRead()
            }
            val exit = process.waitFor()
            drain.join()
            limit?.let { throw UsageLimit(it) }
            val tail = synchronized(stderr) { stderr.joinToString("\n") }.ifEmpty { null }
            if (stopped) throw Stopped()
            failure?.let { throw Failed(it.message, it.details ?: tail) }
            if (exit != 0) throw Failed("${agent.cli} exited with code $exit", tail)
            return valid ?: throw Failed("${agent.cli} ended without a Result", tail)
        } finally {
            stdin.close()
            stdins.remove("$run/${task.activityId}", stdin)
            clis -= process
        }
    }

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

    /** The Task's name in its Process, for the Run log. */
    private fun name(task: LockedExternalTask) =
        repository.getBpmnModelInstance(task.processDefinitionId).getModelElementById<FlowElement>(task.activityId)?.name ?: task.activityId

    /**
     * A session's stdin. It stays open for the user's messages until Ants holds a valid Result and every message
     * written has been read. A message counts as read when the CLI replays it (`--replay-user-messages`), which claude
     * does as it takes the message in: at the next tool boundary, or in a turn of its own after a `result` line.
     */
    private class Stdin(private val writer: BufferedWriter, val task: String) {
        private var unread = 0

        @Volatile
        var open = true
            private set

        /** Whether the user stopped the session, so the `error_during_execution` that follows is `[stopped]`. */
        @Volatile
        var stopping = false
            private set

        @Synchronized
        fun say(message: String) = write(mapOf("type" to "user", "message" to mapOf("role" to "user", "content" to message)))
            .also { if (it) unread++ }

        @Synchronized
        fun interrupt() = write(mapOf("type" to "control_request", "request_id" to UUID.randomUUID().toString(), "request" to mapOf("subtype" to "interrupt")))
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

    private fun flags(agent: Agent, schema: Map<*, *>, skills: List<Skill>, session: String, resume: Boolean): List<String> {
        val tools = (agent.tools ?: DEFAULT_TOOLS).filter { it.substringBefore('(') != "Skill" }
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
        ) + subagents(tools) + skills.flatMap { listOf("--add-dir", it.folder.toString()) } + (if (agent.model != null) listOf("--model", agent.model) else emptyList()) +
            (if (agent.instructions.isNotEmpty()) listOf("--append-system-prompt", agent.instructions) else emptyList())
    }

    /**
     * Every offered claude Agent as a Subagent type (`--agents`), and when the Agent tool among [tools] has a Limit,
     * such as `Agent(searcher)`, every other type denied: claude lets the Agent tool start any type that isn't denied.
     */
    private fun subagents(tools: List<String>): List<String> {
        val offered = catalogue.agents().filter { it.subagent && it.cli == "claude" }.associate { agent ->
            agent.name to listOfNotNull(
                "description" to agent.description,
                "prompt" to agent.instructions,
                agent.tools?.let { rules -> "tools" to rules.map { it.substringBefore('(') }.distinct().filter { it != "Skill" } },
                agent.model?.let { "model" to it },
            ).toMap()
        }
        val agentTool = tools.filter { it.substringBefore('(') in SUBAGENT_TOOLS }
        val allowed = agentTool.takeIf { rules -> rules.isNotEmpty() && rules.all { '(' in it } }
            ?.flatMap { it.substringAfter('(').removeSuffix(")").split(',').map(String::trim) }
        val denied = allowed?.let { (BUILT_IN_SUBAGENTS + offered.keys).filter { it !in allowed }.map { "Agent($it)" } }.orEmpty()
        return (if (offered.isEmpty()) emptyList() else listOf("--agents", json.writeValueAsString(offered))) +
            (if (denied.isEmpty()) emptyList() else listOf("--disallowedTools", denied.joinToString(",")))
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
    fun usage(): Map<*, *> = runCatching { json.readValue(usageFile.readText(), Map::class.java) }.getOrDefault(emptyMap<Any, Any>())

    private fun keepUsage(event: Map<*, *>) {
        val windows = (event["rate_limit_info"] as? Map<*, *>)?.get("unifiedWindows") ?: return
        usageFile.writeText(json.writeValueAsString(windows))
    }

    /** The CLI's command prefix with [args], in [workspace], on the narrowed environment. */
    fun cli(cli: String, workspace: Path, args: List<String>): ProcessBuilder = ProcessBuilder(settings.command(cli) + args)
        .directory(workspace.toFile())
        .apply {
            environment().keys.retainAll(settings.env(cli).toSet())
            environment() -= listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_RETRY_WATCHDOG")
            environment() += mapOf(
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
    private class Failed(override val message: String, val details: String? = null) : Exception(message)

    /** The Usage limit cut the session short; it continues once the limit resets at [resetsAt]. */
    private class UsageLimit(val resetsAt: Instant) : Exception("Usage limit")

    companion object {
        const val TOPIC = "agent"

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
        private const val LOST_LOGIN = "Ants' Claude login is missing or expired. Log in again (Settings)"

        /** An Agent's tools when its file names none. claude 2.1.295 has no TodoWrite; its task-list tools are these four Task*. */
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

/** [instant] as the user reads it on this [Clock]: `15:45`, with the day in front when it isn't today. */
fun Clock.local(instant: Instant): String {
    val time = instant.atZone(zone)
    return time.format(DateTimeFormatter.ofPattern(if (time.toLocalDate() == LocalDate.now(this)) "HH:mm" else "EEE d MMM HH:mm"))
}
