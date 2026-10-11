package ants

import jakarta.annotation.PreDestroy
import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.externaltask.LockedExternalTask
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
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
 * session with their instruction, Start fresh starts a new one with it under "From the user:". A Task that hits the
 * Usage limit pauses its CLI until the reset, then continues its session the same way, with the reset message.
 */
@Component
class AgentLauncher(
    private val externalTasks: ExternalTaskService,
    private val runtime: RuntimeService,
    private val settings: Settings,
    private val catalogue: Catalogue,
    private val clock: Clock,
    @Value("\${ants.home}") private val home: Path,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val poller = Executors.newSingleThreadScheduledExecutor()
    private val clis = ConcurrentHashMap.newKeySet<Process>()
    private val live = AtomicInteger()
    private val usageFile = home.resolve("claude-usage.json")

    // ponytail: one pause gate per CLI, not per model, kept in memory; after a restart the held Tasks wait out
    // their locks, which end at the reset, and the first Task to hit the limit again shuts the gate again.
    private val gates = ConcurrentHashMap<String, Instant>() // CLI -> when its gate opens
    private val held = ConcurrentHashMap<String, String>() // external task id -> the CLI holding it

    /** How many Agent Tasks are running now. */
    val running get() = live.get()

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
        clis.forEach(Process::destroy)
    }

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
        val session = resume ?: UUID.randomUUID().toString()
        var transcript: Transcript.Writer? = null
        try {
            val agent = catalogue.agent(task.extensionProperties["ants.agent"] ?: error("${task.activityId} names no ants.agent"))
            if (hold(task, agent.cli)) return // keeping its choice for the launch after the reset
            runtime.removeVariablesLocal(task.executionId, listOf(ACTION, INSTRUCTION, SESSION)) // the choice is used once
            val run = runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult().rootProcessInstanceId
            val workspace = home.resolve("runs").resolve(run)
            transcript = Transcript.Writer(Transcript.file(workspace, task.activityId)).apply { ants("launch") }
            val result = try {
                session(task, agent, workspace, transcript, session, resume != null, task.variables[INSTRUCTION] as? String)
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
            val reason = if (stopping) "Ants stopped." else "[failed] ${e.message ?: e}"
            runCatching { transcript?.ants("end", reason) }
            if (stopping) return
            log.warn("Agent Task ${task.activityId} failed", e)
            incident(task, session, reason, (e as? InvalidResult)?.details)
        }
    }

    /**
     * Puts [task] on hold as an incident, its tag and reason in [message] and anything longer in [details], keeping
     * its [session] for Continue.
     */
    private fun incident(task: LockedExternalTask, session: String, message: String, details: String?) = runCatching {
        runtime.setVariableLocal(task.executionId, SESSION, session)
        externalTasks.handleFailure(task.id, WORKER, message, details, 0, 0)
    }.onFailure { log.warn("Agent Task ${task.activityId} couldn't be put on hold", it) }

    /**
     * Runs the Task's Skill in one CLI session in [workspace], every line it prints going to [transcript], and returns
     * its Result. A new [session] starts with the hand-off prompt and the user's [instruction], if any; a [resume]d one
     * with the instruction alone.
     */
    private fun session(
        task: LockedExternalTask, agent: Agent, workspace: Path, transcript: Transcript.Writer, session: String, resume: Boolean, instruction: String?,
    ): Map<*, *> {
        val skill = catalogue.skill(task.extensionProperties["ants.skill"] ?: error("${task.activityId} names no ants.skill"))
        check(agent.cli == "claude") { "Agent ${agent.name} runs ${agent.cli}, and only claude runs for now" }
        val used = catalogue.uses(skill)
        // ponytail: until the Vault is handed to Tasks (its path in the hand-off line and an --add-dir), a Task that
        // gets the Vault fails rather than run without it.
        (listOf(skill) + used).firstOrNull { it.vault }?.let { error("The Task uses the Vault, as ${it.name} opts in, and no Vault can be handed to a Task yet") }
        refuseBareByDefault(agent.cli, workspace)

        val handOff = "Follow the Skill in ${skill.file} for the workspace at $workspace." +
            (if (used.isEmpty()) "" else " It uses the Skills ${used.joinToString { "${it.name} (${it.file})" }}.")
        val inputs = task.variables.filterKeys { !it.startsWith("ants.") }.toSortedMap()
        val prompt = if (resume) instruction.orEmpty()
        else "$handOff\n\n" + json.writeValueAsString(inputs) + (if (instruction.isNullOrBlank()) "" else "\n\nFrom the user:\n$instruction")
        val schema = ResultContract.schema(skill.outputSchema)
        val process = cli(agent.cli, workspace, flags(agent, schema, listOf(skill) + used, session, resume)).start()
        clis += process
        try {
            val stdin = process.outputWriter()
            fun say(content: String) {
                stdin.write(json.writeValueAsString(mapOf("type" to "user", "message" to mapOf("role" to "user", "content" to content))))
                stdin.newLine()
                stdin.flush()
            }
            say(prompt)
            var valid: Map<*, *>? = null
            var failure: Exception? = null
            var retried = false
            process.inputReader().forEachLine { line ->
                transcript.line(line)
                if (valid != null || failure != null) return@forEachLine
                val event = runCatching { json.readValue(line, Map::class.java) }.getOrNull() ?: return@forEachLine
                if (event["type"] == "rate_limit_event") keepUsage(event)
                usageLimit(event)?.let { throw UsageLimit(it) } // and the CLI is killed, to resume after the reset
                if (event["type"] != "result") return@forEachLine
                val result = event["structured_output"]
                val errors = result?.let { ResultContract.errors(it, schema, workspace) }
                when {
                    errors == null -> failure = IllegalStateException("${agent.cli} ended without a Result: ${event["subtype"]} ${event["result"] ?: ""}".trim())
                    errors.isEmpty() -> { valid = result as Map<*, *> }
                    !retried -> {
                        retried = true
                        say("Your Result is invalid:\n${errors.joinToString("\n") { "- $it" }}\nAnswer again with a valid Result.")
                    }
                    else -> failure = InvalidResult("The Result is invalid: ${errors.joinToString("; ")}", errors.joinToString("\n") + "\n\n" + json.writeValueAsString(result))
                }
                if (valid != null || failure != null) stdin.close() // the session ends once Ants holds its Result
            }
            val exit = process.waitFor()
            failure?.let { throw it }
            check(exit == 0) { "${agent.cli} exited with code $exit" }
            return valid ?: error("${agent.cli} ended without a Result")
        } finally {
            process.destroy() // already gone, unless the session was cut short
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
        ) + skills.flatMap { listOf("--add-dir", it.folder.toString()) } + (if (agent.model != null) listOf("--model", agent.model) else emptyList()) +
            (if (agent.instructions.isNotEmpty()) listOf("--append-system-prompt", agent.instructions) else emptyList())
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
    private fun cli(cli: String, workspace: Path, args: List<String>) = ProcessBuilder(settings.command(cli) + args)
        .directory(workspace.toFile())
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .apply {
            environment().keys.retainAll(settings.env(cli).toSet())
            environment() -= listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_RETRY_WATCHDOG")
            environment() += mapOf(
                "CLAUDE_CONFIG_DIR" to home.resolve("claude").toString(),
                "CLAUDE_CODE_DISABLE_BACKGROUND_TASKS" to "1",
                "CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH" to "1",
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

    /** A second invalid Result: its errors and the Result itself, kept whole for the `[failed]` page. */
    private class InvalidResult(message: String, val details: String) : Exception(message)

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
        private val DEFAULT_TOOLS = listOf("Bash", "Edit", "Glob", "Grep", "NotebookEdit", "Read", "TodoWrite", "WebFetch", "WebSearch", "Write")
        private val json = JsonMapper.shared()
    }
}

/** [instant] as the user reads it on this [Clock]: `15:45`, with the day in front when it isn't today. */
fun Clock.local(instant: Instant): String {
    val time = instant.atZone(zone)
    return time.format(DateTimeFormatter.ofPattern(if (time.toLocalDate() == LocalDate.now(this)) "HH:mm" else "EEE d MMM HH:mm"))
}
