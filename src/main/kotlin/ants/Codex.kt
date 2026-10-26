package ants

import org.operaton.bpm.engine.externaltask.LockedExternalTask
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.writeText

/*
 * codex: its runner, its flags and how Ants reads `codex exec --json`'s events, which the launch, the Transcript and the
 * So far share.
 */

/** The text of a completed `agent_message` [event], the Agent's message (its Result when it is the turn's last), or null. */
fun codexMessage(event: Map<*, *>): String? = (event["item"] as? Map<*, *>)
    ?.takeIf { event["type"] == "item.completed" && it["type"] == "agent_message" }?.get("text")?.toString()

/** The message of an `error` or `turn.failed` [event], or null for any other. */
fun codexError(event: Map<*, *>): String? = if (event["type"] != "error" && event["type"] != "turn.failed") null
else (event["message"] ?: (event["error"] as? Map<*, *>)?.get("message"))?.toString()

/**
 * Runs a codex session, one `codex exec` per turn, each after the first resuming the session (codex's thread) the
 * first one started. codex reads its prompt from stdin to the end and takes nothing more while a turn runs, so a
 * message the user Sends interrupts the turn (SIGINT, as Ctrl-C does) and resumes the session with it, as does a
 * Result sent back to fix. Its Result is its last message, JSON under `--output-schema`. Settled from codex-rs's
 * source (#73): `read_prompt_from_stdin` in exec/src/lib.rs reads stdin with `read_to_end`, and an interrupted turn
 * prints no `turn.completed` and exits 1.
 */
internal fun AgentLauncher.codex(
    task: LockedExternalTask, instance: String, agent: Agent, run: String, workspace: Path, cwd: Path, vault: Path?, transcript: Transcript.Writer,
    session: String?, started: (String) -> Unit, prompt: String, schema: Map<*, *>, judge: AgentLauncher.Judge, level: Level,
): Map<*, *> {
    Agent.sandboxError(agent.cli, agent.sandbox)?.let { throw AgentLauncher.Failed(it) } // never passed on to --sandbox
    val schemaFile = Transcript.file(workspace, instance).resolveSibling("result.schema.json").apply { writeText(json.writeValueAsString(schema)) }
    val turns = Turns(name(task))
    stdins[key(run, instance)] = turns
    var thread = session
    var message = prompt
    try {
        while (true) {
            val resume = thread?.let { listOf("resume", it) }.orEmpty()
            // codex doesn't print what it was told, so Ants writes it the way claude replays it.
            transcript.line(json.writeValueAsString(mapOf("type" to "user", "message" to mapOf("role" to "user", "content" to message))))
            val process = cli(agent.cli, cwd, codexFlags(agent, level, schemaFile, listOfNotNull(workspace.takeIf { cwd != workspace }, vault), referencedImages(message, home)) + resume + "-").start()
            clis[process] = run
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
                    codexMessage(event)?.let { answer = it }
                    codexError(event)?.let {
                        error = it
                        limit = codexLimit(it, clock)?.also { kill(process) } // the session continues after the reset
                    }
                    when (event["type"]) {
                        "thread.started" -> event["thread_id"]?.toString()?.takeIf { it != thread }?.let { thread = it; started(it) }
                        "turn.completed" -> judge.judge(answer?.let { runCatching { ResultContract.withoutNulls(json.readValue(it, Map::class.java)) }.getOrNull() }, listOf(answer), fixes::add)
                    }
                }
                val exit = process.waitFor()
                limit?.let { throw AgentLauncher.UsageLimit(it) }
                val tail = stderr()
                val sent = turns.ended(judge.valid != null && fixes.isEmpty())
                if (turns.stopping) return judge.valid ?: throw AgentLauncher.Stopped()
                judge.failure?.let { throw AgentLauncher.Failed(it.message, it.details ?: tail) }
                if (fixes.isEmpty() && sent.isEmpty()) {
                    judge.valid?.let { return it }
                    throw AgentLauncher.Failed(if (exit != 0) "${agent.cli} exited with code $exit" + (error?.let { ": $it" } ?: "") else "${agent.cli} ended without a Result", tail)
                }
                if (thread == null) throw AgentLauncher.Failed("${agent.cli} started no session to resume", tail)
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

/**
 * A codex session's turns, one process each. A message Sent, or Stop, interrupts the running turn as Ctrl-C does;
 * the session resumes with the messages once the turn has [ended]. It stays open until Ants holds a valid Result
 * and no message came during the turn that gave it.
 */
internal class Turns(override val task: String) : AgentLauncher.Live {
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

/**
 * codex's flags: JSON lines out, the Result's [schema] file, the Agent's sandbox setting instead of tools, its model,
 * and its standing instructions as `developer_instructions` (a JSON string is a TOML one too). The Run workspace
 * isn't a git repository. The [writable] folders besides the working one are an instance's Run workspace and the Vault.
 * The [images] the prompt references go with it as `-i`, which on `exec` takes several values, so another flag follows
 * them; `exec resume` adds them to the resumed turn's prompt too (`ExecCommand::Resume` in codex-rs/exec/src/lib.rs, 0.162).
 */
internal fun codexFlags(agent: Agent, level: Level, schema: Path, writable: List<Path>, images: List<Path>): List<String> =
    listOf("exec", "--json", "--skip-git-repo-check") + images.flatMap { listOf("-i", "$it") } +
        listOf("--output-schema", "$schema", "--sandbox", agent.sandbox) +
        level.codexFlags +
        (if (agent.instructions.isNotEmpty()) listOf("-c", "developer_instructions=" + json.writeValueAsString(agent.instructions)) else emptyList()) +
        writable.flatMap { listOf("--add-dir", "$it") }

/**
 * When codex's Usage limit resets, from its [message], or null when the message isn't one. codex says
 * "You’ve hit your usage limit. … Try again at 3:45 PM." (or "… or try again at …"), with the date in front,
 * "Oct 13th, 2026 3:45 PM", when it isn't today, in local time; "Try again later." means 30 minutes
 * (`UsageLimitReachedError` and `format_retry_timestamp` in codex-rs/protocol/src/error.rs).
 */
internal fun codexLimit(message: String, clock: Clock): Instant? {
    if (!message.contains("usage limit", ignoreCase = true)) return null
    val at = Regex("""(?i)try again at (?:(\w{3}) (\d{1,2})(?:st|nd|rd|th), (\d{4}) )?(\d{1,2}:\d{2} [AP]M)""").find(message)
    return at?.destructured?.let { (month, day, year, time) ->
        runCatching {
            val date = if (year.isEmpty()) LocalDate.now(clock) else LocalDate.parse("$month $day $year", DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH))
            LocalTime.parse(time.uppercase(), DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).atDate(date).atZone(clock.zone).toInstant()
        }.getOrNull()
    } ?: (clock.instant() + Duration.ofMinutes(30))
}

private val json = JsonMapper.shared()
