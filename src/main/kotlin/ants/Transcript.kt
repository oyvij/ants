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
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * One Agent Task's Transcript, folded into the elements its page shows. The file, `tasks/<task-id>/
 * transcript.jsonl` in the Run workspace, holds the raw stream-json lines of every launch as they arrived, plus Ants' own lines
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
    private var soFarTokens = 0L
    private var thread: Any? = null // the codex session the lines are of
    private val threadTotals = mutableMapOf<Any?, Long>() // a codex session → its total tokens, as its last turn.completed gave it

    /** The latest So far paragraph, if one has been written. */
    var soFar: String? = null
        private set

    /** The latest Result, the last `result` line's `structured_output`, if one has come. */
    var result: Map<*, *>? = null
        private set

    /** The latest text block, as HTML. */
    var output: String? = null
        private set

    /** The Agent's checklist in this launch, its todos from claude's Task* tools, by their id. */
    val todos = linkedMapOf<String, Todo>()

    class Todo(val subject: String, val status: String)

    private class Launch(val entry: Int, val number: Int, val start: Instant) {
        var end: Instant? = null
        var prompted = false
        val usage = mutableMapOf<Any?, Long>() // a message id → its tokens, while no `result` has said better
        var modelUsage: Long? = null
        var before: Long? = null // its codex session's total tokens before it
        val tokens get() = modelUsage ?: usage.values.sum()
    }

    /** A Subagent the session, or the Subagent [parent], started, shown as a block of the entries it added, [entries]. */
    private class Subagent(val entry: Int, val type: String, val description: String, val start: Instant, val parent: Subagent?) {
        val entries = mutableListOf<Int>()
        var end: Instant? = null
        var status = "running"
    }

    /** The top-level entries, as the page first shows them. */
    val roots get() = entries.indices.filter { it !in nested }.map { html("e$it") }

    /**
     * Every launch's tokens: the last `result`'s `modelUsage`, which counts Subagents too, or so far the messages'.
     * The So far rewrites count on the Task too.
     */
    val tokens get() = launches.sumOf { it.tokens } + soFarTokens

    val running get() = launches.isNotEmpty() && launches.last().end == null

    /** The Subagents running now. */
    val runningSubagents get() = if (running) blocks.values.count { it.end == null } else 0

    /** While a launch runs, its live line: the spinner, what it is doing, and how long it has run with its tokens. */
    val line: String?
        get() = if (!running) null else launches.last().let {
            """<span class="spin">✻</span> <span class="shimmer">$verb…</span> <span class="muted num">(<span data-since="${it.start.toEpochMilli()}">""" +
                """${elapsed(Duration.between(it.start, Instant.now()))}</span> · ↓ ${tokens(it.tokens)})</span>"""
        }

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
            // codex exec --json
            "item.started", "item.completed" -> codexMessage(event)?.let { message(it, launch) }
                ?: item(event["item"] as? Map<*, *> ?: emptyMap<Any, Any>(), event["type"] == "item.completed")
            "thread.started" -> {
                thread = event["thread_id"]
                if (launch != null && launch.before == null) launch.before = threadTotals[thread] ?: 0
            }
            // usage is the session's total so far (`usage_from_last_total` in codex-rs/exec), so a launch counts it
            // less the session's total before the launch.
            "turn.completed" -> (event["usage"] as? Map<*, *>)?.let {
                val total = sum(it, "input_tokens", "output_tokens")
                threadTotals[thread] = total
                launch?.modelUsage = total - (launch?.before ?: 0)
            }
            "error", "turn.failed" -> codexError(event)?.let { message ->
                append { """<div class="result" id="$it"><b>Error</b> ${esc(message)}</div>""" }
            }
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
        "status" -> """<div id="status">${line.orEmpty()}</div>"""
        "subagents" -> """<div id="subagents" class="chips">""" + blocks.values.filter { running && it.end == null }.joinToString(" ") {
            """<span class="chip"><span class="spin">✻</span> ${esc(it.type)} · ${esc(it.description)} · ${time(it)}</span>"""
        } + "</div>"
        "sofar" -> """<div class="sofar" id="sofar"><div class="row small"><b>So far</b></div>""" +
            markdown(soFar ?: result?.get("summary")?.toString() ?: "Nothing yet.") +
            (if (todos.isEmpty()) "" else """<div class="todos">""" + todos.values.joinToString("") {
                """<div class="todo ${TODO[it.status]?.second.orEmpty()}">${TODO[it.status]?.first ?: "☐"} ${esc(it.subject)}</div>"""
            } + "</div>") + "</div>"
        else -> if (id.endsWith("s")) summary(blocks.getValue(id.removePrefix("e").removeSuffix("s").toInt()))
        else id.removePrefix("e").toInt().let { entry ->
            blocks[entry]?.let { block ->
                val inside = block.parent?.let { """ data-in="e${it.entry}"""" }.orEmpty()
                """<details class="subagent" id="$id"$inside${if (running && block.end == null) " open" else ""}>${summary(block)}""" +
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
                todos.clear()
            }
            "so-far" -> {
                soFar = event["text"]?.toString()
                soFarTokens += (event["tokens"] as? Number)?.toLong() ?: 0
            }
            else -> {
                if (event["subtype"] == "end") launches.lastOrNull()?.end = at
                live.clear()
                append { """<p class="ants" id="$it">${esc(event["text"])}</p>""" }
            }
        }
    }

    private fun user(event: Map<*, *>, launch: Launch?) {
        // TaskCreate's tool result carries the id claude gave the new task.
        ((event["tool_use_result"] as? Map<*, *>)?.get("task") as? Map<*, *>)?.let { todos[it["id"].toString()] = Todo(it["subject"].toString(), "pending") }
        val parent = subagent(event)
        fun said(text: Any?) {
            if (parent != null) {
                append(parent) { """<details class="prompt" id="$it"><summary>Prompt</summary><pre>${esc(text)}</pre></details>""" }
            } else if (launch != null && !launch.prompted) {
                launch.prompted = true
                append { """<details class="prompt" id="$it"><summary>${if (launch.number == 1) "Opening prompt" else "Prompt"}</summary><pre>${esc(text)}</pre></details>""" }
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
                val html = markdown(block["text"].toString())
                if (parent == null) {
                    live.clear()
                    output = html
                }
                append(parent) { """<div class="text" id="$it">$html</div>""" }
            }
            "thinking" -> append(parent) { """<details class="thinking" id="$it"><summary>✻ Thinking</summary>${esc(block["thinking"])}</details>""" }
            "tool_use" -> {
                val name = block["name"].toString()
                val input = block["input"] as? Map<*, *> ?: emptyMap<Any, Any>()
                if (name in AgentLauncher.SUBAGENT_TOOLS) {
                    spawn(block["id"], input["subagent_type"]?.toString() ?: "general-purpose", input["description"]?.toString().orEmpty(), parent)
                } else {
                    val argument = input.values.firstOrNull { it is String }?.toString().orEmpty()
                    tools[block["id"]] = append(parent) {
                        """<div class="tool" id="$it"><b>${esc(name)}</b> <code>${esc(argument.lines().first().take(120))}</code></div>"""
                    }
                }
                if (parent == null) verb = VERBS[name] ?: "Working"
                if (name == "TaskUpdate") todo(input)
            }
        }
    }

    /** A TaskUpdate: a new status or subject for a task in the list, or `deleted`. */
    private fun todo(update: Map<*, *>) {
        val id = update["taskId"]?.toString() ?: return
        val todo = todos[id] ?: return
        if (update["status"] == "deleted") todos -= id
        else todos[id] = Todo(update["subject"]?.toString() ?: todo.subject, update["status"]?.toString() ?: todo.status)
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

    /** Opens a block for the Subagent the tool call [id] starts, inside [parent]'s block when a Subagent started it. */
    private fun spawn(id: Any?, type: String, description: String, parent: Subagent? = null): Subagent {
        val block = Subagent(entries.size, type, description, seen, parent)
        blocks[block.entry] = block
        append(parent) { "" } // the block's HTML is composed from its entries
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

    /** A codex message ([codexMessage]) as text, or as the Result when it is one (JSON under `--output-schema`). */
    private fun message(text: String, launch: Launch?) {
        val result = runCatching { json.readValue(text, Map::class.java) }.getOrNull()?.takeIf { "outcome" in it }
        if (result != null) result(mapOf("structured_output" to result), launch)
        else {
            live.clear()
            output = markdown(text)
            append { """<div class="text" id="$it">$output</div>""" }
        }
    }

    /** A codex item but a message: its reasoning, or a command, file change, search or MCP call as one tool line, marked when it fails. */
    private fun item(item: Map<*, *>, completed: Boolean) {
        val id = item["id"]
        when (item["type"]) {
            "reasoning" -> if (completed) append { """<details class="thinking" id="$it"><summary>✻ Thinking</summary>${esc(item["text"])}</details>""" }
            else -> {
                val (name, argument) = when (item["type"]) {
                    "command_execution" -> "Shell" to item["command"]
                    "file_change" -> "Edit" to (item["changes"] as? List<*>)?.joinToString(", ") { (it as? Map<*, *>)?.get("path").toString() }
                    "web_search" -> "WebSearch" to item["query"]
                    "mcp_tool_call" -> "${item["server"]}.${item["tool"]}" to item["arguments"]
                    else -> return
                }
                verb = VERBS[name] ?: "Working"
                val entry = tools.getOrPut(id) {
                    append { """<div class="tool" id="$it"><b>${esc(name)}</b> <code>${esc(argument?.toString().orEmpty().lines().first().take(120))}</code></div>""" }
                }
                if (completed && item["status"] == "failed") put(entry, entries[entry].replaceFirst("""class="tool"""", """class="tool error""""))
            }
        }
    }

    private fun result(event: Map<*, *>, launch: Launch?) {
        // modelUsage is cumulative for the process, so the last result line of a launch holds the launch's tokens.
        (event["modelUsage"] as? Map<*, *>)?.let { models ->
            launch?.modelUsage = models.values.filterIsInstance<Map<*, *>>()
                .sumOf { sum(it, "inputTokens", "outputTokens", "cacheReadInputTokens", "cacheCreationInputTokens") }
        }
        val result = event["structured_output"] as? Map<*, *>
        if (result != null) this.result = result
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

        // The So far writes into a Transcript its launch is writing to, and a long line goes out in several writes.
        // ponytail: one lock for every Transcript; one per file if writes ever queue
        fun line(line: String) {
            if (line.isNotBlank()) synchronized(Writer::class.java) { Files.writeString(file, "$line\n", CREATE, APPEND) }
        }

        /** One of Ants' own lines: `launch`, `end` with how the launch ended, or `so-far` with the paragraph and its [tokens]. */
        fun ants(subtype: String, text: String? = null, tokens: Long? = null) = line(
            json.writeValueAsString(
                listOfNotNull("type" to "ants", "subtype" to subtype, "at" to Instant.now().toString(), text?.let { "text" to it }, tokens?.let { "tokens" to it }).toMap(),
            ),
        )
    }

    companion object {
        private val FIXED = listOf("live", "status", "tokens", "subagents", "sofar")
        private val TODO = mapOf("completed" to ("☑" to "done"), "in_progress" to ("◐" to "now")) // a todo's status → its mark and class
        private val OK = setOf("running", "completed") // a Subagent's statuses its summary leaves unsaid
        private val VERBS = mapOf(
            "Read" to "Reading", "Edit" to "Editing", "Write" to "Writing", "NotebookEdit" to "Editing", "Grep" to "Searching",
            "Glob" to "Searching", "Bash" to "Running", "Shell" to "Running", "WebFetch" to "Browsing", "WebSearch" to "Browsing", "Agent" to "Delegating",
            "Task" to "Delegating", "TaskCreate" to "Planning", "TaskUpdate" to "Planning",
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

        /** [text] as HTML, its own HTML escaped and its URLs sanitized. */
        fun markdown(text: CharSequence) = renderer.render(parser.parse(text.toString()))

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
/** A token count, short: as it is under a thousand, then in k (one decimal under ten thousand), then in M. */
fun tokens(count: Long): String {
    fun short(n: Double) = String.format(Locale.ROOT, "%.1f", n).removeSuffix(".0")
    return when {
        count < 1_000 -> "$count tokens"
        count < 10_000 -> "${short(count / 1_000.0)}k tokens"
        count < 1_000_000 -> "${(count + 500) / 1_000}k tokens"
        else -> "${short(count / 1_000_000.0)}M tokens"
    }
}

/** The Transcript page, which replays the file and then follows it over SSE, with Send and Stop while the Task runs. */
@Controller
class Transcripts(
    @Value("\${ants.home}") home: Path, private val launcher: AgentLauncher, private val streams: Streams, private val names: RunNames, private val tasks: Runs,
) {
    private val runs = home.resolve("runs")

    @GetMapping("/runs/{run}/tasks/{task}/transcript")
    fun page(@PathVariable run: String, @PathVariable task: String, model: Model): String {
        val (lines, offset) = Transcript.read(file(run, task))
        val transcript = Transcript().apply { lines.forEach(::add) }
        model.addAttribute("run", run)
        model.addAttribute("runName", names.name(run))
        model.addAttribute("task", task)
        model.addAttribute("view", tasks.task(run, task, transcript))
        model.addAttribute("transcript", transcript)
        model.addAttribute("offset", offset)
        model.addAttribute("sendHint", launcher.sendHint(run, task))
        return "transcript"
    }

    /**
     * Send: a message into the Task's live session, refused once the session takes no more. From the Run page's Task
     * drawer ([back] is `run`) it returns to the Run page.
     */
    @PostMapping("/runs/{run}/tasks/{task}/transcript/send")
    fun send(
        @PathVariable run: String, @PathVariable task: String, @RequestParam message: String, @RequestParam(required = false) back: String?, model: Model,
    ): String {
        val error = when {
            message.isBlank() -> "Write a message to send."
            !launcher.send(run, task, message) -> "Not sent: the Agent's session has ended, as Ants holds its Result or it is not running."
            else -> return back(back, run, "/runs/$run/tasks/$task/transcript")
        }
        model.addAttribute("error", error)
        return page(run, task, model)
    }

    /** Stop: interrupts the Task's live session, which puts it on the Notification list as `[stopped]`. */
    @PostMapping("/runs/{run}/tasks/{task}/transcript/stop")
    fun stop(@PathVariable run: String, @PathVariable task: String, @RequestParam(required = false) back: String?, model: Model): String {
        if (launcher.stop(run, task)) return back(back, run, "/runs/$run/tasks/$task/transcript")
        model.addAttribute("error", "Not stopped: the Agent's session has already ended.")
        return page(run, task, model)
    }

    /** The changed elements of the Transcript from byte [from] on, as `transcript` events, for as long as the page is open. */
    fun events(run: String, task: String, from: Long): Part {
        val file = file(run, task)
        val transcript = Transcript()
        var offset = Transcript.read(file, 0, from).let { (lines, end) -> lines.forEach(transcript::add); end }
        return Part("transcript", Duration.ofMillis(250)) {
            val (lines, end) = Transcript.read(file, offset)
            offset = end
            lines.flatMapTo(linkedSetOf()) { transcript.add(it) }.map(transcript::html)
        }
    }

    private fun file(run: String, task: String) = Transcript.file(names.runWorkspace(run), task).normalize()
        .also { if (!it.startsWith(runs) || it.nameCount != runs.nameCount + 4) throw ResponseStatusException(HttpStatus.NOT_FOUND) }
}
