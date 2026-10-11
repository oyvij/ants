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
import java.util.concurrent.atomic.AtomicInteger

/**
 * The in-process worker. It fetches ready Agent Tasks (external tasks on topic `agent`) only for free slots under
 * the cap, oldest first across Runs, and runs each one's Skill through `claude -p`. A `done` Result completes the
 * task, stored as a process variable named after the Task's BPMN id; a `stuck` one or a failure is an incident.
 * A Task that hits the Usage limit pauses its CLI until the reset, then resumes its session.
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
     * paused. The hold is a failure with retries left, so it is no incident, and its lock ends at the reset; it keeps
     * the [session] to resume in its error details.
     */
    private fun hold(task: LockedExternalTask, cli: String, session: String?, until: Instant? = null): Boolean {
        synchronized(gates) {
            if (until != null) gates.merge(cli, until, ::maxOf)
            val reset = gates[cli] ?: return false
            val wait = Duration.between(clock.instant(), reset).toMillis().coerceAtLeast(0)
            externalTasks.handleFailure(task.id, WORKER, "$PAUSED $reset", session, 1, wait)
            held[task.id] = cli
            return true
        }
    }

    private fun launch(task: LockedExternalTask) {
        try {
            val agent = catalogue.agent(task.extensionProperties["ants.agent"] ?: error("${task.activityId} names no ants.agent"))
            val skill = catalogue.skill(task.extensionProperties["ants.skill"] ?: error("${task.activityId} names no ants.skill"))
            val resume = if (task.errorMessage?.startsWith(PAUSED) == true) externalTasks.getExternalTaskErrorDetails(task.id) else null
            if (hold(task, agent.cli, resume)) return
            val result = try {
                session(task, agent, skill, resume)
            } catch (limit: UsageLimit) {
                hold(task, agent.cli, limit.session, limit.resetsAt + Duration.ofMinutes(1))
                return
            }
            if (result["outcome"] == "done") externalTasks.complete(task.id, WORKER, mapOf(task.activityId to result))
            else incident(task, "[stuck] ${result["summary"]}", result["needs"].toString())
        } catch (e: Exception) {
            if (poller.isShutdown) return // Ants is stopping: the Task stays locked, and the next start unlocks it
            log.warn("Agent Task ${task.activityId} failed", e)
            incident(task, "[failed] ${e.message ?: e}", (e as? InvalidResult)?.details)
        }
    }

    /** Puts [task] on hold as an incident, its tag and reason in [message] and anything longer in [details]. */
    private fun incident(task: LockedExternalTask, message: String, details: String?) =
        runCatching { externalTasks.handleFailure(task.id, WORKER, message, details, 0, 0) }
            .onFailure { log.warn("Agent Task ${task.activityId} couldn't be put on hold", it) }

    /** Runs the Task's Skill in one CLI session, a new one or the [resume]d one, and returns its Result. */
    private fun session(task: LockedExternalTask, agent: Agent, skill: Skill, resume: String?): Map<*, *> {
        check(agent.cli == "claude") { "Agent ${agent.name} runs ${agent.cli}, and only claude runs for now" }
        val run = runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult().rootProcessInstanceId
        val workspace = home.resolve("runs").resolve(run)
        refuseBareByDefault(agent.cli, workspace)

        val prompt = if (resume != null) RESET else
            "Follow the Skill in ${skill.file} for the workspace at $workspace.\n\n" + json.writeValueAsString(task.variables.toSortedMap())
        val session = resume ?: UUID.randomUUID().toString()
        val schema = ResultContract.schema(skill.outputSchema)
        val process = cli(agent.cli, workspace, flags(agent, schema, skill, session, resume != null)).start()
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
                if (valid != null || failure != null) return@forEachLine
                val event = runCatching { json.readValue(line, Map::class.java) }.getOrNull() ?: return@forEachLine
                usageLimit(event)?.let { throw UsageLimit(session, it) } // and the CLI is killed, to resume after the reset
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

    private fun flags(agent: Agent, schema: Map<*, *>, skill: Skill, session: String, resume: Boolean): List<String> {
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
            "--add-dir", skill.folder.toString(),
        ) + (if (agent.model != null) listOf("--model", agent.model) else emptyList()) +
            (if (agent.instructions.isNotEmpty()) listOf("--append-system-prompt", agent.instructions) else emptyList())
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

    /** The Usage limit stopped [session]; it resumes once the limit resets at [resetsAt]. */
    private class UsageLimit(val session: String, val resetsAt: Instant) : Exception("Usage limit")

    companion object {
        const val TOPIC = "agent"
        private const val WORKER = "ants"
        private const val PAUSED = "Paused until"
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
