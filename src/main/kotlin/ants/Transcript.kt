package ants

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.web.util.HtmlUtils
import tools.jackson.databind.json.JsonMapper
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * One Agent Task's Transcript, folded into the elements its page shows. The file, `runs/<run>/tasks/<task-id>/
 * transcript.jsonl`, holds the raw stream-json lines of every launch as they arrived, plus Ants' own lines
 * (`"type":"ants"`): a `launch` divider before each launch and an `end` line after it. [add] folds in one line and
 * says which elements it changed, so a page following the file patches only those.
 */
class Transcript {
    /** The entries in order, as HTML; entry i is the element with id `e<i>`. A Subagent's block is composed in [html]. */
    private val entries = mutableListOf<String>()
    private val launches = mutableListOf<Launch>()
    private val tools = mutableMapOf<Any?, Int>() // a tool_use id → its entry
    private val blocks = mutableMapOf<Int, Subagent>() // a block's entry → its Subagent
    private val subagents = mutableMapOf<Any?, Subagent>() // its tool_use id and its agent_id → a Subagent
    private val nested = mutableSetOf<Int>() // the entries inside a block
    private val changed = linkedSetOf<String>()
    private val live = StringBuilder() // the text block streaming in now
    private var verb = "Starting"
    private var seen = Instant.EPOCH // the latest time a line carried

    private class Launch(val entry: Int, val number: Int, val start: Instant) {
        var end: Instant? = null
        var prompted = false
        val usage = mutableMapOf<Any?, Long>() // a message id → its tokens, while no `result` has said better
        var modelUsage: Long? = null
        val tokens get() = modelUsage ?: usage.values.sum()
    }

    /** A Subagent the session started, shown as a block of the entries it added, [entries]. */
    private class Subagent(val entry: Int, val type: String, val description: String, val start: Instant) {
        val entries = mutableListOf<Int>()
        var end: Instant? = null
        var status = "running"
    }

    /** The top-level entries, as the page first shows them. */
    val roots get() = entries.indices.filter { it !in nested }.map { html("e$it") }

    /** Every launch's tokens: the last `result`'s `modelUsage`, which counts Subagents too, or so far the messages'. */
    val tokens get() = launches.sumOf { it.tokens }

    private val running get() = launches.isNotEmpty() && launches.last().end == null

    /** Folds in one line of the file and returns the ids of the elements it changed. */
    fun add(line: String): Set<String> {
        val event = runCatching { json.readValue(line, Map::class.java) }.getOrNull() ?: return emptySet()
        val watched = FIXED + blocks.keys.map { "e${it}s" }
        val before = watched.associateWith(::html)
        changed.clear()
        (event["timestamp"] ?: event["at"])?.let { seen = runCatching { Instant.parse(it.toString()) }.getOrDefault(seen) }
        val launch = launches.lastOrNull()
        when (event["type"]) {
            "ants" -> ants(event)
            "user" -> user(event, launch)
            "assistant" -> assistant(event, launch)
            "stream_event" -> stream(event["event"] as? Map<*, *> ?: emptyMap<Any, Any>())
            "system" -> system(event)
            "result" -> result(event, launch)
        }
        launches.lastOrNull()?.let { put(it.entry, divider(it)) }
        return changed + watched.filter { html(it) != before[it] }
    }

    /**
     * The element with [id]: an entry, a Subagent block's summary (`e<i>s`), or `live`, `status`, `tokens` or
     * `subagents`, the count and a chip per running Subagent.
     */
    fun html(id: String): String = when (id) {
        "live" -> """<div id="live">${if (live.isEmpty()) "" else markdown(live)}</div>"""
        "tokens" -> """<span id="tokens">${tokens(tokens)}</span>"""
        "status" -> if (!running) """<div id="status"></div>""" else launches.last().let {
            """<div id="status"><span class="spinner">✻</span> $verb… <span data-since="${it.start.toEpochMilli()}">""" +
                """${elapsed(Duration.between(it.start, Instant.now()))}</span> · ${tokens(it.tokens)}</div>"""
        }
        "subagents" -> """<div id="subagents">""" + (if (blocks.isEmpty()) "" else "⑂ ${blocks.size}") +
            blocks.values.filter { running && it.end == null }.joinToString("") {
                """ <span class="chip">⑂ ${esc(it.type)} · ${esc(it.description)} · ${time(it)}</span>"""
            } + "</div>"
        else -> if (id.endsWith("s")) summary(blocks.getValue(id.removePrefix("e").removeSuffix("s").toInt()))
        else id.removePrefix("e").toInt().let { entry ->
            blocks[entry]?.let { block ->
                """<details class="subagent" id="$id"${if (running && block.end == null) " open" else ""}>${summary(block)}""" +
                    """<div>${block.entries.joinToString("") { html("e$it") }}</div></details>"""
            } ?: entries[entry]
        }
    }

    private fun summary(block: Subagent) = """<summary id="e${block.entry}s">⑂ <b>${esc(block.type)}</b> ${esc(block.description)}""" +
        (if (block.end != null || running) " · ${time(block)}" else "") + (if (block.status in OK) "" else " · ${esc(block.status)}") + "</summary>"

    /** How long [block] ran, or a ticking time while it runs. */
    private fun time(block: Subagent) = block.end?.let { elapsed(Duration.between(block.start, it)) }
        ?: """<span data-since="${block.start.toEpochMilli()}">${elapsed(Duration.between(block.start, Instant.now()))}</span>"""

    private fun ants(event: Map<*, *>) {
        val at = Instant.parse(event["at"].toString())
        when (event["subtype"]) {
            "launch" -> {
                val launch = Launch(entries.size, launches.size + 1, at)
                launches += launch
                append { divider(launch) }
                verb = "Starting"
                live.clear()
            }
            else -> {
                if (event["subtype"] == "end") launches.lastOrNull()?.end = at
                live.clear()
                append { """<p class="ants" id="$it">${esc(event["text"])}</p>""" }
            }
        }
    }

    private fun user(event: Map<*, *>, launch: Launch?) {
        val parent = subagent(event)
        fun said(text: Any?) {
            if (parent != null) {
                append(parent) { """<details class="prompt" id="$it"><summary>Prompt</summary><pre>${esc(text)}</pre></details>""" }
            } else if (launch != null && !launch.prompted) {
                launch.prompted = true
                append { """<details class="prompt" id="$it"><summary>Prompt</summary><pre>${esc(text)}</pre></details>""" }
            } else {
                append { """<p class="user" id="$it">› ${esc(text)}</p>""" }
            }
        }
        when (val content = (event["message"] as? Map<*, *>)?.get("content")) {
            is String -> said(content)
            is List<*> -> for (block in content.filterIsInstance<Map<*, *>>()) when (block["type"]) {
                "text" -> said(block["text"])
                "tool_result" -> {
                    if (parent == null) verb = "Thinking"
                    // without task_* events, such as for a type the Limit denies, the tool's result ends the Subagent
                    subagents[block["tool_use_id"]]?.takeIf { it.end == null }?.let {
                        it.end = seen
                        if (block["is_error"] == true) it.status = "failed" else if (it.status == "running") it.status = "completed"
                    }
                    val entry = tools[block["tool_use_id"]] ?: continue
                    if (block["is_error"] == true) put(entry, entries[entry].replaceFirst("""class="tool"""", """class="tool error""""))
                }
            }
        }
    }

    private fun assistant(event: Map<*, *>, launch: Launch?) {
        val message = event["message"] as? Map<*, *> ?: return
        (message["usage"] as? Map<*, *>)?.let {
            launch?.usage?.set(message["id"], sum(it, "input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens"))
        }
        val parent = subagent(event) // a Subagent's own messages go into its block, and leave the live text and verb alone
        for (block in (message["content"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) when (block["type"]) {
            "text" -> {
                if (parent == null) live.clear()
                append(parent) { """<div class="text" id="$it">${markdown(block["text"].toString())}</div>""" }
            }
            "thinking" -> append(parent) { """<details class="thinking" id="$it"><summary>✻ Thinking</summary>${esc(block["thinking"])}</details>""" }
            "tool_use" -> {
                val name = block["name"].toString()
                val input = block["input"] as? Map<*, *> ?: emptyMap<Any, Any>()
                if (parent == null && name in AgentLauncher.SUBAGENT_TOOLS) {
                    spawn(block["id"], input["subagent_type"]?.toString() ?: "general-purpose", input["description"]?.toString().orEmpty())
                } else {
                    val argument = input.values.firstOrNull { it is String }?.toString().orEmpty()
                    tools[block["id"]] = append(parent) {
                        """<div class="tool" id="$it">⏺ <b>${esc(name)}</b> <code>${esc(argument.lines().first().take(120))}</code></div>"""
                    }
                }
                if (parent == null) verb = VERBS[name] ?: "Working"
            }
        }
    }

    /** A Subagent's lifecycle: `task_started` ties its agent_id to its block, `task_updated` and `task_notification` end it. */
    private fun system(event: Map<*, *>) {
        when (event["subtype"]) {
            "status" -> verb = "Thinking"
            "task_started" -> {
                val block = subagents[event["tool_use_id"]]
                    ?: spawn(event["tool_use_id"], event["subagent_type"]?.toString() ?: "general-purpose", event["description"]?.toString().orEmpty())
                subagents[event["task_id"]] = block
            }
            "task_updated", "task_notification" -> {
                val block = subagents[event["task_id"]] ?: return
                val patch = event["patch"] as? Map<*, *> ?: event
                patch["status"]?.let { block.status = it.toString() }
                (patch["end_time"] as? Number)?.let { block.end = Instant.ofEpochMilli(it.toLong()) }
                if (block.status != "running" && block.end == null) block.end = seen
            }
        }
    }

    /** Opens a block for the Subagent the tool call [id] starts. */
    private fun spawn(id: Any?, type: String, description: String): Subagent {
        val entry = entries.size
        val block = Subagent(entry, type, description, seen)
        blocks[entry] = block
        append { "" } // the block's HTML is composed from its entries
        subagents[id] = block
        return block
    }

    /** The Subagent whose block [event] belongs in: by `agent_id`, else by `parent_tool_use_id`; null for the session's own. */
    private fun subagent(event: Map<*, *>) = event["agent_id"]?.let(subagents::get) ?: event["parent_tool_use_id"]?.let(subagents::get)

    private fun stream(event: Map<*, *>) {
        when (event["type"]) {
            "content_block_start" -> when ((event["content_block"] as? Map<*, *>)?.get("type")) {
                "text" -> live.clear().also { verb = "Writing" }
                "thinking" -> verb = "Thinking"
            }
            "content_block_delta" -> ((event["delta"] as? Map<*, *>)?.get("text") as? String)?.let(live::append)
        }
    }

    private fun result(event: Map<*, *>, launch: Launch?) {
        // modelUsage is cumulative for the process, so the last result line of a launch holds the launch's tokens.
        (event["modelUsage"] as? Map<*, *>)?.let { models ->
            launch?.modelUsage = models.values.filterIsInstance<Map<*, *>>()
                .sumOf { sum(it, "inputTokens", "outputTokens", "cacheReadInputTokens", "cacheCreationInputTokens") }
        }
        val result = event["structured_output"] as? Map<*, *>
        append { id ->
            if (result == null) """<div class="result" id="$id"><b>Result: ${esc(event["subtype"])}</b> ${esc(event["result"] ?: "")}</div>"""
            else """<div class="result" id="$id"><b>Result: ${esc(result["outcome"])}</b> ${esc(result["summary"])}""" +
                (result["output"]?.let { "<pre>${esc(json.writerWithDefaultPrettyPrinter().writeValueAsString(it))}</pre>" } ?: "") +
                (result["needs"]?.let { "<p>${esc(it)}</p>" } ?: "") + "</div>"
        }
    }

    private fun divider(launch: Launch) = """<h3 class="launch" id="e${launch.entry}">Launch ${launch.number} · """ +
        "${TIME.format(launch.start)} · ${tokens(launch.tokens)}</h3>"

    private fun append(html: (id: String) -> String): Int {
        val entry = entries.size
        entries += html("e$entry")
        changed += "e$entry"
        return entry
    }

    /** Appends an entry inside [parent]'s block, marked so a page following the file puts it there; or at the top. */
    private fun append(parent: Subagent?, html: (id: String) -> String): Int {
        if (parent == null) return append(html)
        val entry = append { id -> html(id).replaceFirst(""" id="$id"""", """ id="$id" data-in="e${parent.entry}"""") }
        nested += entry
        parent.entries += entry
        return entry
    }

    private fun put(entry: Int, html: String) {
        if (entries[entry] == html) return
        entries[entry] = html
        changed += "e$entry"
    }

    /** Appends to a Transcript file, a whole line per write, so a reader stops only at a line's end. */
    class Writer(private val file: Path) {
        init {
            file.parent.createDirectories()
        }

        fun line(line: String) {
            if (line.isNotBlank()) Files.writeString(file, "$line\n", CREATE, APPEND)
        }

        /** One of Ants' own lines: `launch`, or `end` with how the launch ended. */
        fun ants(subtype: String, text: String? = null) =
            line(json.writeValueAsString(listOfNotNull("type" to "ants", "subtype" to subtype, "at" to Instant.now().toString(), text?.let { "text" to it }).toMap()))
    }

    companion object {
        private val FIXED = listOf("live", "status", "tokens", "subagents")
        private val OK = setOf("running", "completed") // a Subagent's statuses its summary leaves unsaid
        private val VERBS = mapOf(
            "Read" to "Reading", "Edit" to "Editing", "Write" to "Writing", "NotebookEdit" to "Editing", "Grep" to "Searching",
            "Glob" to "Searching", "Bash" to "Running", "WebFetch" to "Browsing", "WebSearch" to "Browsing", "Agent" to "Delegating",
            "Task" to "Delegating", "TodoWrite" to "Planning",
        )
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        private val json = JsonMapper.shared()
        private val parser = Parser.builder().build()
        private val renderer = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build()

        fun file(workspace: Path, task: String): Path = workspace.resolve("tasks").resolve(task).resolve("transcript.jsonl")

        /** The Transcript of every Task in the Run workspace [workspace] that has one, by Task id. */
        fun all(workspace: Path): Map<String, Transcript> = workspace.resolve("tasks").takeIf { it.isDirectory() }
            ?.listDirectoryEntries()?.sortedBy { it.name }?.filter { it.resolve("transcript.jsonl").exists() }
            ?.associate { it.name to Transcript().apply { read(file(workspace, it.name)).first.forEach(::add) } }
            .orEmpty()

        /** The whole lines of [file] between byte offsets [from] and [until], and the offset after the last of them. */
        fun read(file: Path, from: Long = 0, until: Long = Long.MAX_VALUE): Pair<List<String>, Long> {
            if (!file.exists()) return emptyList<String>() to from
            RandomAccessFile(file.toFile(), "r").use { raf ->
                val bytes = ByteArray((minOf(raf.length(), until) - from).coerceAtLeast(0).toInt())
                raf.seek(from)
                raf.readFully(bytes)
                val end = bytes.lastIndexOf('\n'.code.toByte())
                if (end < 0) return emptyList<String>() to from
                return String(bytes, 0, end, Charsets.UTF_8).split('\n') to from + end + 1
            }
        }

        private fun markdown(text: CharSequence) = renderer.render(parser.parse(text.toString()))

        private fun esc(value: Any?) = HtmlUtils.htmlEscape(value?.toString().orEmpty())

        private fun sum(usage: Map<*, *>, vararg keys: String) = keys.sumOf { (usage[it] as? Number)?.toLong() ?: 0 }

        private fun elapsed(time: Duration) = when {
            time.toHours() > 0 -> "${time.toHours()}h ${time.toMinutesPart()}m"
            time.toMinutes() > 0 -> "${time.toMinutes()}m ${time.toSecondsPart()}s"
            else -> "${time.seconds}s"
        }
    }
}

/** A token count as the pages show it. */
fun tokens(count: Long) = String.format(Locale.ROOT, "%,d tokens", count)

/** The Transcript page, which replays the file and then follows it over SSE, with Send and Stop while the Task runs. */
@Controller
class Transcripts(@Value("\${ants.home}") home: Path, private val launcher: AgentLauncher) {
    private val runs = home.resolve("runs")

    @GetMapping("/runs/{run}/tasks/{task}/transcript")
    fun page(@PathVariable run: String, @PathVariable task: String, model: Model): String {
        val (lines, offset) = Transcript.read(file(run, task))
        model.addAttribute("run", run)
        model.addAttribute("task", task)
        model.addAttribute("transcript", Transcript().apply { lines.forEach(::add) })
        model.addAttribute("offset", offset)
        model.addAttribute("live", launcher.live(run, task))
        return "transcript"
    }

    /** Send: a message into the Task's live session, refused once the session takes no more. */
    @PostMapping("/runs/{run}/tasks/{task}/transcript/send")
    fun send(@PathVariable run: String, @PathVariable task: String, @RequestParam message: String, model: Model): String {
        val error = when {
            message.isBlank() -> "Write a message to send."
            !launcher.send(run, task, message) -> "Not sent: the Agent's session has ended, as Ants holds its Result or it is not running."
            else -> return "redirect:/runs/$run/tasks/$task/transcript"
        }
        model.addAttribute("error", error)
        return page(run, task, model)
    }

    /** Stop: interrupts the Task's live session, which puts it on the Notification list as `[stopped]`. */
    @PostMapping("/runs/{run}/tasks/{task}/transcript/stop")
    fun stop(@PathVariable run: String, @PathVariable task: String, model: Model): String {
        if (launcher.stop(run, task)) return "redirect:/runs/$run/tasks/$task/transcript"
        model.addAttribute("error", "Not stopped: the Agent's session has already ended.")
        return page(run, task, model)
    }

    /** The changed elements of the Transcript from byte [from] on, as `entry` events, for as long as the page is open. */
    @GetMapping("/runs/{run}/tasks/{task}/transcript/events")
    fun events(@PathVariable run: String, @PathVariable task: String, @RequestParam from: Long): SseEmitter {
        val file = file(run, task)
        val emitter = SseEmitter(0L)
        val open = AtomicBoolean(true)
        emitter.onCompletion { open.set(false) }
        emitter.onError { open.set(false) }
        Thread.ofVirtual().start {
            try {
                val transcript = Transcript()
                var offset = Transcript.read(file, 0, from).let { (lines, end) -> lines.forEach(transcript::add); end }
                var quiet = 0
                // ponytail: each open page polls its file every 250 ms; an in-process notification once pages are many
                while (open.get()) {
                    val (lines, end) = Transcript.read(file, offset)
                    offset = end
                    val changed = lines.flatMapTo(linkedSetOf()) { transcript.add(it) }
                    for (id in changed) emitter.send(SseEmitter.event().name("entry").data(transcript.html(id)))
                    if (changed.isEmpty() && ++quiet % 40 == 0) emitter.send(SseEmitter.event().comment("")) // finds a closed page
                    Thread.sleep(250)
                }
            } catch (_: Exception) {
                emitter.complete() // the page went away
            }
        }
        return emitter
    }

    private fun file(run: String, task: String) = Transcript.file(runs.resolve(run), task).normalize()
        .also { if (!it.startsWith(runs) || it.nameCount != runs.nameCount + 4) throw ResponseStatusException(HttpStatus.NOT_FOUND) }
}
