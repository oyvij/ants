package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

abstract class PageChat(protected val chats: PageChats) : McpTools {
    abstract val name: String

    abstract val title: String

    protected abstract val pageName: String

    protected abstract val instructions: String

    protected abstract val idPrefix: String

    protected abstract val lines: Map<String, (Map<*, *>) -> String>

    protected open fun firstPrompt(message: String): String? = null

    /**
     * Whether claude may Read the tool results it saved to a file in this session, the only file it may read: an answer
     * too large for the dialog is then never a dead end.
     */
    protected open val readsOwnToolResults = false

    protected open fun head(): String = ""

    protected open fun foot(): String = ""

    /** The `--mcp-config` that reaches this page's tools. */
    lateinit var config: String

    val session = UUID.randomUUID().toString()
    val scratch: Path by lazy { Files.createTempDirectory(name) }

    /** Whether claude has started [session] (its `system/init`), so a relaunch resumes it; a launch that died before has none to resume. */
    @Volatile
    var launched = false

    @Volatile
    var claude: Process? = null
    internal var stdin: AgentLauncher.Stdin? = null

    /** Whether a turn runs: from a message sent until claude's `result` line, or its end. */
    @Volatile
    var busy = false

    @Volatile
    var closed = false

    /** When the last message was sent or the last turn ended, which [PageChats.idleTimeout] counts from. */
    @Volatile
    var active = System.nanoTime()

    /** The dialog, claude's stream-json lines folded in as an Agent Task's Transcript folds them, each tool call as [lines] words it. Guarded by itself. */
    val dialog = Transcript { name, input -> lines[name.removePrefix("mcp__ants__").takeIf { it != name }]?.invoke(input) }

    @Volatile
    private var opening: Pair<String, String>? = null

    /** When the page's SSE last asked for calls to send; it has never asked while [polled] is false. */
    @Volatile
    var seen = System.nanoTime()

    @Volatile
    var polled = false

    /** The page's newest SSE: only it takes the calls, as one from before a reload may still be polling until a write fails. */
    val streams = AtomicInteger()

    val outbox = ConcurrentLinkedQueue<String>()
    val waiting = ConcurrentHashMap<String, CompletableFuture<String>>()

    val connected get() = polled && System.nanoTime() - seen < CONNECTED.toNanos()
    override val alive get() = !closed

    /** Sends [message] into the session, launching claude first when none is live. */
    @Synchronized
    fun send(message: String) {
        val (cli, model) = chats.settings.chat(name)
        if (cli != "claude") throw Refused(claudeOnly(title, name))
        val starting = claude?.isAlive != true || stdin?.open != true
        val prompt = if (starting && !launched) firstPrompt(message) else null
        if (starting) launch(model)
        if (prompt != null) opening = prompt to message
        busy = true
        active = System.nanoTime()
        if (!stdin!!.say(prompt ?: message)) busy = false.also { throw Refused("claude ended before it read the message. Send it again to resume the session.") }
    }

    private fun launch(model: String?) {
        val flags = AgentLauncher.STREAM_JSON + listOf(
            if (launched) "--resume" else "--session-id", session,
            "--permission-mode", "dontAsk",
            "--setting-sources", "",
            "--settings", """{"disableAllHooks":true}""",
            "--strict-mcp-config",
            "--mcp-config", config,
            "--disable-slash-commands",
            "--tools", if (readsOwnToolResults) "Read" else "",
            "--allowedTools", listOfNotNull("mcp__ants__*", toolResults().takeIf { readsOwnToolResults }).joinToString(","),
            "--append-system-prompt", instructions,
        ) + (model?.let { listOf("--model", it) } ?: emptyList())
        val process = chats.launcher.cli("claude", scratch, flags).start()
        val stdin = AgentLauncher.Stdin(process.outputWriter(), name, chats.launcher.home)
        claude = process
        this.stdin = stdin
        val stderr = chats.launcher.drain(process)
        Thread.ofVirtual().start { read(process, stdin, stderr) }
    }

    /** The Read rule for where claude saves this session's too-large tool results: `<config>/projects/<cwd>/<session>/tool-results/`. */
    private fun toolResults() = "Read(/${chats.launcher.home.resolve("claude").toAbsolutePath()}/projects/*/$session/tool-results/**)"

    /** Folds claude's lines into the dialog until it ends; a turn ends at its `result` line. */
    private fun read(process: Process, stdin: AgentLauncher.Stdin, stderr: () -> String?) {
        process.inputReader().forEachLine { line ->
            val event = runCatching { chats.json.readValue(line, Map::class.java) }.getOrNull() ?: return@forEachLine
            if (event["type"] == "system" && event["subtype"] == "init") launched = true
            val apiKey = (event["apiKeySource"] as? String)?.takeIf { event["subtype"] == "init" && it != "none" }
            if (event["error"] == "authentication_failed" || apiKey != null) {
                note(AgentLauncher.LOST_LOGIN + (apiKey?.let { ": claude would use an API key (apiKeySource: $it), and Ants runs only on its own login." } ?: "."))
                stdin.close()
                chats.launcher.kill(process)
            } else if (event["type"] == "result") { // its text is the last reply's, already shown
                busy = false
                active = System.nanoTime()
            }
            else synchronized(dialog) { dialog.add(opening?.let { shown(event, it) } ?: line) }
        }
        val exit = process.waitFor()
        busy = false
        if (exit != 0 && stdin.open && !stdin.stopping && !closed) note("claude ended (exit $exit)${stderr()?.let { ": ${it.lines().last()}" } ?: ""}. Your next message resumes the session.")
    }

    private fun shown(event: Map<*, *>, opening: Pair<String, String>): String? {
        val (prompt, message) = opening
        val content = (event["message"] as? Map<*, *>)?.get("content")
        val text = content as? String ?: (content as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull { it["type"] == "text" }?.get("text")
        if (event["type"] != "user" || text != prompt) return null
        this.opening = null
        return chats.json.writeValueAsString(event + ("message" to mapOf("role" to "user", "content" to message)))
    }

    /**
     * Ends claude while no turn runs, as the page has gone or the session is idle: closing its stdin ends it, else it is
     * killed. The session stays, so the next message resumes it.
     */
    @Synchronized
    fun rest() {
        val process = claude?.takeIf { it.isAlive && !busy } ?: return
        stdin?.close()
        if (!process.waitFor(REST.toMillis(), MILLISECONDS)) chats.launcher.kill(process)
    }

    /** A line of Ants' own in the dialog. */
    private fun note(text: String) = synchronized(dialog) {
        dialog.add(chats.json.writeValueAsString(mapOf("type" to "ants", "subtype" to "note", "at" to Instant.now().toString(), "text" to text)))
    }

    fun html(): String = synchronized(dialog) {
        head() + """<div id="$idPrefix-entries">${dialog.roots.joinToString("")}</div>${dialog.html("live")}""" + foot() +
            """<div id="$idPrefix-state" data-busy="$busy">${if (busy) """<span class="spin">✻</span> <span class="shimmer" data-rotate>Pondering…</span>""" else ""}</div>"""
    }

    /** Relays [tool] to the page and gives its answer, JSON text; fails when no page is connected or it doesn't answer in time. */
    fun ask(tool: String, arguments: Map<*, *>): String {
        if (!connected) throw Refused("No $pageName is open for this $title session, so `$tool` can't be read: the page was closed or lost its connection.")
        val id = UUID.randomUUID().toString()
        val answer = CompletableFuture<String>().also { waiting[id] = it }
        val event = chats.json.writeValueAsString(mapOf("id" to id, "tool" to tool, "arguments" to arguments))
        outbox += event
        try {
            return answer.get(chats.timeLimit.toNanos(), NANOSECONDS)
        } catch (_: TimeoutException) {
            throw Refused("The $pageName didn't answer `$tool` within ${chats.timeLimit.toString().removePrefix("PT").lowercase()}.")
        } catch (e: ExecutionException) {
            throw Refused("The $pageName couldn't answer `$tool`: ${e.cause?.message}")
        } finally {
            waiting -= id
            outbox -= event
        }
    }

    companion object {
        /** A page whose SSE last asked longer ago than this is not connected; its next stream asks again within seconds. */
        private val CONNECTED = Duration.ofSeconds(10)

        /** How long a resting claude has to end once its stdin is closed, before it is killed. */
        private val REST = Duration.ofSeconds(5)

        fun claudeOnly(title: String, name: String) = "The $title runs on claude only for now. Set $name's cli to claude in settings.yaml."
    }
}

/**
 * A page reloaded in its tab keeps its token, so its session and dialog. Its claude ends, to resume at the next message,
 * once no turn runs and the page's SSE has been gone for [grace], or no message has come for [idleTimeout]; the token dies
 * once the SSE has been gone for [grace] with no session started, or for [KEPT] with one.
 */
@Component
class PageChats(
    private val mcp: Mcp, val launcher: AgentLauncher, val settings: Settings, val json: JsonMapper,
    @Value("\${ants.builder.disconnect-grace:10m}") grace: Duration, @Value("\${ants.builder.idle-timeout:2h}") idleTimeout: Duration,
) {
    private val pages = ConcurrentHashMap<String, PageChat>()

    /** How long a page has to answer a relayed call. */
    internal var timeLimit: Duration = TIME_LIMIT

    /** A page whose SSE last asked longer ago than this has gone: its claude ends once no turn runs. */
    internal var grace: Duration = grace

    /** A session no message has come to for this long ends its claude once no turn runs, even with its page open. */
    internal var idleTimeout: Duration = idleTimeout

    operator fun get(token: String): PageChat? = pages[token]

    /**
     * Opens a session for the page asking: its token. A page reloaded in its tab gives the [token] it had, and gets that
     * session back while it lives.
     */
    fun open(token: String?, kind: Class<out PageChat>, make: () -> PageChat): String {
        if (token != null && pages[token]?.takeIf { kind.isInstance(it) }?.closed == false) return token
        val page = make()
        val (opened, config) = mcp.open(page)
        page.config = config
        pages[opened] = page
        Thread.ofVirtual().start {
            while (!page.closed) {
                Thread.sleep(500)
                val away = System.nanoTime() - page.seen
                if (away > grace.toNanos() || System.nanoTime() - page.active > idleTimeout.toNanos()) page.rest()
                if (away > grace.toNanos() && (!page.launched || away > KEPT.toNanos()) && page.claude?.isAlive != true) end(page)
            }
        }
        return opened
    }

    /** Sends the user's [message] into the page's claude session: empty, or the error that kept it from going. */
    fun send(token: String, message: String, title: String): Map<String, String> = try {
        if (message.isBlank()) throw Refused("Write a message to send.")
        (pages[token] ?: throw Refused("This page's $title session has ended. Reload the page.")).send(message)
        emptyMap()
    } catch (e: Exception) {
        mapOf("error" to (e.message ?: e.toString()))
    }

    /** Stop: interrupts the turn running in the page's session. */
    fun stop(token: String) {
        pages[token]?.stdin?.interrupt()
    }

    /** Ends [page]'s session: its claude, its token and its place here. */
    private fun end(page: PageChat) {
        page.closed = true
        page.stdin?.close()
        page.claude?.let(launcher::kill)
        pages.entries.removeIf { (token, it) -> (it === page).also { gone -> if (gone) mcp.close(token) } }
    }

    /** The page's dialog, as an event `dialog` whenever it changes. */
    fun dialog(token: String): Part? = pages[token]?.let { page ->
        var last: String? = null
        Part("dialog", Duration.ofMillis(250)) { listOfNotNull(page.html().takeIf { it != last }?.also { last = it }) }
    }

    /** Whether claude runs for the session of the page holding [token]. */
    internal fun running(token: String) = pages[token]?.claude?.isAlive == true

    /** Whether no turn runs in the session of the page holding [token]. */
    internal fun idle(token: String) = pages[token]?.busy == false

    /** The page's answer to the relayed call [call]: the [answer] as JSON text, or the [error] it met. */
    fun answer(token: String, call: String, answer: String?, error: String?) {
        val waiting = pages[token]?.waiting?.get(call) ?: return
        if (error != null) waiting.completeExceptionally(Refused(error)) else waiting.complete(answer ?: "null")
    }

    /** The page's part of its SSE: each call relayed to it, as an event `builder`, `{id, tool, arguments}`. */
    fun relay(token: String): Part? = pages[token]?.takeIf { it.alive }?.let { page ->
        val stream = page.streams.incrementAndGet()
        Part("builder", Duration.ofMillis(100)) {
            if (page.streams.get() != stream) return@Part emptyList()
            page.seen = System.nanoTime()
            page.polled = true
            generateSequence { page.outbox.poll() }.toList()
        }
    }

    /** Whether the page holding [token] follows its SSE now. */
    internal fun connected(token: String) = pages[token]?.connected == true

    companion object {
        val TIME_LIMIT: Duration = Duration.ofSeconds(30)

        /** How long a page's session, its dialog, outlives its SSE, for the tab to come back to. */
        // ponytail: kept in memory, so a restart of Ants loses the dialog (the Build log is the handoff then)
        private val KEPT = Duration.ofDays(1)
    }
}
