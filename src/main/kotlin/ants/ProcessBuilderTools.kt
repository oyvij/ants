package ants

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.util.HtmlUtils
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
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeoutException
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * The Process Builder's MCP session (ADR 0019): a modeler page that can be edited by hand opens one and gets a token
 * of its own on `/mcp`, which opens only the modeler tools. A tool about the diagram is relayed to the page that holds
 * the token, as an event `builder` on its SSE (`/live?builder=<token>`); the page answers from bpmn-js with a POST to
 * `/process-builder/answer`. The catalogue tools are answered by Ants, from what the modeler's own endpoints serve, and
 * the Checks by the Checks Save and deploy runs, on the page's diagram. Creating, changing and copying Agents, Skills
 * and Templates go through their pages' own save and Copy, and answer the item's page. The token dies once the page's SSE has been
 * gone for a minute.
 */
@RestController
class ProcessBuilderTools(
    private val mcp: Mcp, private val agents: Agents, private val templates: Templates, private val processes: Processes, private val catalogue: Catalogue,
    private val skills: Skills, private val json: JsonMapper, private val launcher: AgentLauncher, private val settings: Settings,
) {
    private val pages = ConcurrentHashMap<String, Page>()

    /** How long a page has to answer a relayed call. */
    internal var timeLimit: Duration = TIME_LIMIT

    /** A page whose SSE last asked longer ago than this has gone: its token dies and its claude ends. */
    internal var gone: Duration = GONE

    /**
     * One open modeler page: the calls waiting to go out on its SSE, and those waiting for its answer; and its claude
     * session, launched on the first message and resumed by the next one after it died, in a scratch folder of its own
     * (claude finds a session to resume by its working directory), and the dialog it has shown.
     */
    private inner class Page : McpTools {
        /** The `--mcp-config` that reaches this page's tools. */
        lateinit var config: String

        val session = UUID.randomUUID().toString()
        val scratch: Path by lazy { Files.createTempDirectory("process-builder") }
        var launched = false

        @Volatile
        var claude: Process? = null
        var stdin: AgentLauncher.Stdin? = null

        /** Whether a turn runs: from a message sent until claude's `result` line, or its end. */
        @Volatile
        var busy = false

        @Volatile
        var closed = false

        /** The dialog, claude's stream-json lines folded in as an Agent Task's Transcript folds them. Guarded by itself. */
        val dialog = Transcript()

        /** The pages of the Agents, Skills and Templates this session made or changed, in order. */
        val made: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** The key of the Process whose Build log this session started from, which the panel links to. */
        @Volatile
        var handoff: String? = null

        /** The first prompt, with the Build log, and the user's own message: the dialog shows its replay as the message alone. */
        @Volatile
        private var opening: Pair<String, String>? = null

        /** When the page's SSE last asked for calls to send; it has never asked while [polled] is false. */
        @Volatile
        var seen = System.nanoTime()

        @Volatile
        var polled = false

        val outbox = ConcurrentLinkedQueue<String>()
        val waiting = ConcurrentHashMap<String, CompletableFuture<String>>()

        val connected get() = polled && System.nanoTime() - seen < CONNECTED.toNanos()
        override val alive get() = !closed && System.nanoTime() - seen < gone.toNanos()
        override val tools = TOOLS

        override fun call(name: String, arguments: Map<*, *>): Pair<String, Boolean> = try {
            fun argument(key: String) = arguments[key]?.toString() ?: throw Refused("$name needs $key.")
            val text = when (name) {
                in RELAYED -> ask(name, arguments)
                "agents" -> json.writeValueAsString(agents.catalogue()["agents"])
                "skills" -> json.writeValueAsString(agents.catalogue()["skills"])
                "skill" -> catalogue.skill(argument("name")).let { skill ->
                    json.writeValueAsString(mapOf(
                        "name" to skill.name, "description" to skill.description, "inputs" to skill.inputs, "uses" to skill.uses,
                        "instructions" to frontmatter(skill.file.readText()).second.trim(), "output" to skill.outputSchema.takeIf { it.isNotEmpty() },
                    ))
                }
                "processes" -> json.writeValueAsString(processes.processes())
                "process" -> argument("key").let { key ->
                    if (processes.processes().none { it["key"] == key }) throw Refused("No Process $key is deployed.")
                    processes.diagram(key).decodeToString()
                }
                "built_in_tasks" -> json.writeValueAsString(builtIns)
                "templates" -> json.writeValueAsString(templates.json())
                "references" -> json.writeValueAsString(processes.inputs(xml(), argument("element")))
                "checks" -> json.writeValueAsString(processes.checks(xml()))
                "create_agent" -> saved(agents.write(argument("file"), null))
                "change_agent" -> saved(agents.write(argument("file"), argument("name")))
                "create_skill" -> saved(skills.write(argument("skill"), arguments["schema"]?.toString().orEmpty(), null))
                "change_skill" -> saved(skills.write(argument("skill"), arguments["schema"]?.toString().orEmpty(), argument("name")))
                "build_log" -> argument("markdown").let { markdown ->
                    val key = process(parse(xml().toByteArray())).getAttribute("id")
                    buildLog(key).also { it.parent.createDirectories() }.writeAtomically(markdown)
                    "The Build log of $key is now what you gave."
                }
                "copy" -> argument("name").let { item ->
                    saved(when (argument("kind")) {
                        "agent" -> agents.copy(item)
                        "skill" -> skills.copy(item)
                        "template" -> templates.copy(item)
                        else -> throw Refused("copy copies an agent, a skill or a template.")
                    }.removePrefix("redirect:"))
                }
                else -> throw Refused("No tool $name here.")
            }
            text to false
        } catch (e: Exception) {
            ((e as? ResponseStatusException)?.reason ?: e.message ?: e.toString()) to true
        }

        /** What a create, change or copy answers: the item's name and its page, which the panel links to. */
        fun saved(page: String) = json.writeValueAsString(mapOf("name" to page.substringAfterLast('/'), "page" to page)).also { made += page }

        /**
         * Sends [message] into the session, launching claude first when none is live. A new session on the Process [key]
         * gets its Build log, when there is one, before the message: the handoff from the dialogs before it.
         */
        @Synchronized
        fun send(message: String, key: String?) {
            val (cli, model) = settings.processBuilder()
            if (cli != "claude") throw Refused(CLAUDE_ONLY)
            val log = key?.takeIf { !launched }?.let { runCatching { buildLog(it) }.getOrNull() }?.takeIf { it.exists() }
            if (claude?.isAlive != true || stdin?.open != true) launch(model)
            val prompt = log?.let { "The Build log of this Process, from earlier dialogs about it:\n\n${it.readText().trim()}\n\nFrom the user:\n$message" }
            if (prompt != null) {
                handoff = key
                opening = prompt to message
            }
            busy = true
            if (!stdin!!.say(prompt ?: message)) busy = false.also { throw Refused("claude ended before it read the message. Send it again to resume the session.") }
        }

        private fun launch(model: String?) {
            // Locked down by its tools (ADR 0019): none of claude's own, only Ants' modeler tools, no settings or hooks of the user's.
            val flags = AgentLauncher.STREAM_JSON + listOf(
                if (launched) "--resume" else "--session-id", session,
                "--permission-mode", "dontAsk",
                "--setting-sources", "",
                "--settings", """{"disableAllHooks":true}""",
                "--strict-mcp-config",
                "--mcp-config", config,
                "--disable-slash-commands",
                "--tools", "",
                "--allowedTools", "mcp__ants__*",
                "--append-system-prompt", INSTRUCTIONS,
            ) + (model?.let { listOf("--model", it) } ?: emptyList())
            val process = launcher.cli("claude", scratch, flags).start()
            launched = true
            val stdin = AgentLauncher.Stdin(process.outputWriter(), "process-builder", launcher.home)
            claude = process
            this.stdin = stdin
            val stderr = launcher.drain(process)
            Thread.ofVirtual().start { read(process, stdin, stderr) }
            Thread.ofVirtual().start {
                while (process.isAlive) {
                    if (!alive) end(this)
                    Thread.sleep(500)
                }
            }
        }

        /** Folds claude's lines into the dialog until it ends; a turn ends at its `result` line. */
        private fun read(process: Process, stdin: AgentLauncher.Stdin, stderr: () -> String?) {
            process.inputReader().forEachLine { line ->
                val event = runCatching { json.readValue(line, Map::class.java) }.getOrNull() ?: return@forEachLine
                val apiKey = (event["apiKeySource"] as? String)?.takeIf { event["subtype"] == "init" && it != "none" }
                if (event["error"] == "authentication_failed" || apiKey != null) {
                    note(AgentLauncher.LOST_LOGIN + (apiKey?.let { ": claude would use an API key (apiKeySource: $it), and Ants runs only on its own login." } ?: "."))
                    stdin.close()
                    launcher.kill(process)
                } else if (event["type"] == "result") busy = false // its text is the last reply's, already shown
                else synchronized(dialog) { dialog.add(opening?.let { shown(event, it) } ?: line) }
            }
            val exit = process.waitFor()
            busy = false
            if (exit != 0 && !stdin.stopping && !closed) note("claude ended (exit $exit)${stderr()?.let { ": ${it.lines().last()}" } ?: ""}. Your next message resumes the session.")
        }

        /** The replay of the first prompt [opening] as the user's own message, the Build log left out; null for any other line. */
        private fun shown(event: Map<*, *>, opening: Pair<String, String>): String? {
            val (prompt, message) = opening
            val content = (event["message"] as? Map<*, *>)?.get("content")
            val text = content as? String ?: (content as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull { it["type"] == "text" }?.get("text")
            if (event["type"] != "user" || text != prompt) return null
            this.opening = null
            return json.writeValueAsString(event + ("message" to mapOf("role" to "user", "content" to message)))
        }

        /** A line of Ants' own in the dialog. */
        private fun note(text: String) = synchronized(dialog) {
            dialog.add(json.writeValueAsString(mapOf("type" to "ants", "subtype" to "note", "at" to Instant.now().toString(), "text" to text)))
        }

        /** The dialog as the panel shows it: the entries, the reply streaming in, what it made, and whether a turn runs. */
        fun html(): String = synchronized(dialog) {
            (handoff?.let { """<p class="ants" id="pb-handoff">Started from this Process's <a href="/processes/${HtmlUtils.htmlEscape(it)}/build-log" target="_blank">Build log</a>.</p>""" } ?: "") +
                """<div id="pb-entries">${dialog.roots.joinToString("").replace("<b>mcp__ants__", "<b>")}</div>${dialog.html("live")}""" +
                (if (made.isEmpty()) "" else """<ul id="pb-made" aria-label="Made">""" + made.joinToString("") { page ->
                    val kind = KINDS[page.split('/')[1]]
                    """<li><a href="${HtmlUtils.htmlEscape(page)}" target="_blank">$kind ${HtmlUtils.htmlEscape(page.substringAfterLast('/'))}</a></li>"""
                } + "</ul>") +
                """<div id="pb-state" data-busy="$busy">${if (busy) """<span class="spin">✻</span> <span class="shimmer" data-rotate>Pondering…</span>""" else ""}</div>"""
        }

        /** The page's diagram as BPMN XML, as Save and deploy would send it. */
        fun xml(): String = json.readValue(ask("xml", emptyMap<String, Any>()), String::class.java)

        /** Relays [tool] to the page and gives its answer, JSON text; fails when no page is connected or it doesn't answer in time. */
        fun ask(tool: String, arguments: Map<*, *>): String {
            if (!connected) throw Refused("No modeler page is open for this Process Builder session, so `$tool` can't be read: the page was closed or lost its connection.")
            val id = UUID.randomUUID().toString()
            val answer = CompletableFuture<String>().also { waiting[id] = it }
            val event = json.writeValueAsString(mapOf("id" to id, "tool" to tool, "arguments" to arguments))
            outbox += event
            try {
                return answer.get(timeLimit.toNanos(), NANOSECONDS)
            } catch (_: TimeoutException) {
                throw Refused("The modeler page didn't answer `$tool` within ${timeLimit.toString().removePrefix("PT").lowercase()}.")
            } catch (e: ExecutionException) {
                throw Refused("The modeler page couldn't answer `$tool`: ${e.cause?.message}")
            } finally {
                waiting -= id
                outbox -= event
            }
        }
    }

    /** Opens a session for the modeler page asking: its token. */
    @PostMapping("/process-builder")
    fun open(): Map<String, String> {
        pages.values.filter { !it.alive }.forEach(::end)
        val page = Page()
        val (token, config) = mcp.open(page)
        page.config = config
        pages[token] = page
        return mapOf("token" to token)
    }

    /** Sends the user's [message] into the page's claude session: empty, or the [error] that kept it from going. */
    @PostMapping("/process-builder/send")
    fun send(@RequestParam token: String, @RequestParam message: String, @RequestParam(required = false) key: String?): Map<String, String> = try {
        if (message.isBlank()) throw Refused("Write a message to send.")
        (pages[token] ?: throw Refused("This page's Process Builder session has ended. Reload the page.")).send(message, key)
        emptyMap()
    } catch (e: Exception) {
        mapOf("error" to (e.message ?: e.toString()))
    }

    /** A Process's Build log, to read: the Markdown the Process Builder last wrote for it. */
    @GetMapping("/processes/{key}/build-log", produces = ["text/plain;charset=UTF-8"])
    fun readBuildLog(@PathVariable key: String): String =
        runCatching { buildLog(key) }.getOrNull()?.takeIf { it.exists() }?.readText() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$key has no Build log.")

    /**
     * Where the Process [key]'s Build log lives: `ANTS_HOME/processes/<key>/build-log.md`, apart from deploys and drafts,
     * so it outlives both. A key that isn't a plain folder name is refused, so nothing is written outside that folder.
     */
    private fun buildLog(key: String): Path {
        if (!SAFE_KEY.matches(key)) throw Refused("The Process id `$key` can't name a folder, so it gets no Build log: give it letters, digits, `_`, `-` and `.` only.")
        return launcher.home.resolve("processes").resolve(key).resolve("build-log.md")
    }

    /** Stop: interrupts the turn running in the page's session. */
    @PostMapping("/process-builder/stop")
    fun stop(@RequestParam token: String) {
        pages[token]?.stdin?.interrupt()
    }

    /** The page is closing: its session ends now, not a minute later. */
    @PostMapping("/process-builder/close")
    fun close(@RequestParam token: String) {
        pages[token]?.let(::end)
    }

    /** Ends [page]'s session: its claude, its token and its place here. */
    private fun end(page: Page) {
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

    /** Whether no turn runs in the session of the page holding [token]. */
    internal fun idle(token: String) = pages[token]?.busy == false

    /** The page's answer to the relayed call [call]: the [answer] as JSON text, or the [error] it met. */
    @PostMapping("/process-builder/answer")
    fun answer(@RequestParam token: String, @RequestParam call: String, @RequestParam(required = false) answer: String?, @RequestParam(required = false) error: String?) {
        val waiting = pages[token]?.waiting?.get(call) ?: return
        if (error != null) waiting.completeExceptionally(Refused(error)) else waiting.complete(answer ?: "null")
    }

    /** The page's part of its SSE: each call relayed to it, as an event `builder`, `{id, tool, arguments}`. */
    fun relay(token: String): Part? = pages[token]?.takeIf { it.alive }?.let { page ->
        Part("builder", Duration.ofMillis(100)) {
            page.seen = System.nanoTime()
            page.polled = true
            generateSequence { page.outbox.poll() }.toList()
        }
    }

    /** Whether the page holding [token] follows its SSE now. */
    internal fun connected(token: String) = pages[token]?.connected == true

    companion object {
        val TIME_LIMIT: Duration = Duration.ofSeconds(30)

        /** A page whose SSE last asked longer ago than this is not connected; its next stream asks again within seconds. */
        private val CONNECTED = Duration.ofSeconds(10)

        /** A page whose SSE last asked longer ago than this has gone, and its token dies. */
        val GONE: Duration = Duration.ofMinutes(1)

        /** A Process id that is a plain folder name: no separator, and never `.` or `..`. */
        private val SAFE_KEY = Regex("[A-Za-z_][A-Za-z0-9_.-]*")

        const val CLAUDE_ONLY = "The Process Builder runs on claude only for now. Set process-builder's cli to claude in settings.yaml."

        /** The Process Builder's standing instructions, shipped read-only. */
        private val INSTRUCTIONS = ProcessBuilderTools::class.java.getResource("/process-builder.md")!!.readText()

        private val KINDS = mapOf("agents" to "Agent", "skills" to "Skill", "templates" to "Template")

        private fun number(what: String) = mapOf("type" to "number", "description" to what)

        /** A `{name: text}` argument's schema. */
        private fun strings(what: String) = mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string"), "description" to what)

        private fun yesNo(what: String) = mapOf("type" to "boolean", "description" to what)

        private const val AT = "Its top-left corner on the diagram, as `diagram` gives positions."

        /** The tools that change the diagram: relayed to the page, which makes each call one undo step (modeler.js). */
        private val WRITE = listOf(
            Mcp.tool("add_element", "Adds an element to the open diagram, as the palette does, and gives its id. Without x and y it goes right of the " +
                "rightmost element in its container. Connect it with `connect`.", mapOf(
                "kind" to mapOf("type" to "string", "enum" to listOf("start", "timer-start", "end", "timer", "exclusive", "parallel", "agent", "user", "built-in",
                    "call", "subprocess", "annotation", "group"), "description" to "What it is: exclusive is a decision; subprocess an expanded embedded sub-process."),
                "id" to "Its id, a JUEL name such as `grill` (letters, digits and underscores), by which later Tasks read its Result; left out, bpmn-js makes one.",
                "name" to "Its name on the diagram.",
                "x" to number(AT), "y" to number(AT),
                "in" to "The id of the sub-process to add it in; left out, the Process itself.",
                "topic" to "A Built-in Task's topic (see `built_in_tasks`); left out, clone-repos.",
            ), optional = setOf("id", "name", "x", "y", "in", "topic")),
            Mcp.tool("remove", "Removes an element, with its connections, or a connection.", mapOf("id" to "The element's or connection's id.")),
            Mcp.tool("rename", "Names an element or connection, as the diagram shows it.", mapOf("id" to "The element's or connection's id.", "name" to "Its new name; empty clears it.")),
            Mcp.tool("move", "Moves an element, its connections following.", mapOf("id" to "The element's id.", "x" to number(AT), "y" to number(AT))),
            Mcp.tool("connect", "Connects two elements with a sequence flow (an association, to an annotation), and gives its id.", mapOf(
                "from" to "The source element's id.", "to" to "The target element's id.", "id" to "The connection's id; left out, bpmn-js makes one.",
                "name" to "Its label on the diagram, such as `yes`.",
            ), optional = setOf("id", "name")),
            Mcp.tool("set_condition", "Sets the condition of a path out of a decision (exclusive gateway): the Run takes it when the JUEL holds.", mapOf(
                "id" to "The connection's id.", "condition" to "A JUEL expression, such as `\${grill.output.verdict == 'redo'}` or `\${runs.grill >= 10}`; empty removes it.",
            )),
            Mcp.tool("set_default", "Makes a path out of a decision its default, taken when no other condition holds; its condition goes.", mapOf("id" to "The connection's id.")),
            Mcp.tool("set_agent_task", "Sets an Agent Task's Agent, Skill, inputs, Result schema and toggles, as the properties panel does: a new Agent picks its first Skill unless " +
                "skill is given, and the inputs are the ones the Skill declares, keeping values already filled in.", mapOf(
                "id" to "The Agent Task's id.", "agent" to "The Agent's name (see `agents`).", "skill" to "One of the Agent's Skills (see `skills`).",
                "inputs" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string"),
                    "description" to "Values by input name, each text or a `\${…}` reference (see `references`), such as {\"prompt\": \"\${prompt}\"}."),
                "resultSchema" to "A JSON Schema of the Result, overriding the Skill's, so later Tasks read its fields as \${id.output.<field>}; empty removes it.",
                "resume" to yesNo("Whether the flow coming back (Round answers, Send back) resumes its session."),
                "vault" to yesNo("Whether it reads the Vault and Source map; left unset, the Skill's choice."),
                "answerHistory" to yesNo("Whether it reads the Answer History; left unset, the Skill's choice."),
            ), optional = setOf("agent", "skill", "inputs", "resultSchema", "resume", "vault", "answerHistory")),
            Mcp.tool("set_start_form", "Replaces the start form's fields, in order: to add, change, remove or reorder fields, give the whole list, " +
                "starting from `diagram`'s startForm.", mapOf(
                "fields" to mapOf("type" to "array", "description" to "The fields. Tasks read each one as \${id}.", "items" to mapOf(
                    "type" to "object", "required" to listOf("label"), "properties" to mapOf(
                        "label" to mapOf("type" to "string"),
                        "id" to mapOf("type" to "string", "description" to "The variable name; left out, derived from the label (camelCase)."),
                        "type" to mapOf("type" to "string", "enum" to FieldType.entries.map { it.name.lowercase() }, "description" to "Left out, text."),
                        "required" to mapOf("type" to "boolean"),
                        "help" to mapOf("type" to "string"),
                        "example" to mapOf("type" to "string"),
                        "stored" to mapOf("type" to "string", "description" to "The value a Schedule starts with."),
                    ),
                )),
            )),
            Mcp.tool("set_user_task", "Sets a User Task's kind and its fields, as the properties panel does: a new kind drops the fields only the old kind " +
                "has. Later Tasks read its Result: approve and review-changes give \${id.approved} and \${id.comment} (review-changes also \${id.comments}), " +
                "answer-round gives \${id.answers}.", mapOf(
                "id" to "The User Task's id.",
                "kind" to mapOf("type" to "string", "enum" to UserTaskKind.entries.map { it.value ?: "approve" }, "description" to "What the user does; left out, the kind it is now."),
                "fields" to strings("Values by field, empty clearing one: approve takes title (what is asked) and show (a Run workspace file, or a `\${…}` " +
                    "shown as markdown) and send-back; review-changes takes walkthrough and send-back; answer-round takes asker. send-back, walkthrough and " +
                    "asker name an Agent Task: the one Send back resumes, whose Story set is shown, and whose Round is answered."),
            ), optional = setOf("kind", "fields")),
            Mcp.tool("set_call", "Sets a call activity's Process and inputs, as the properties panel does: its inputs are the called Process's start-form " +
                "fields (see `processes`), keeping values already filled in. Later Tasks read its Result as \${id.<task-id>…}.", mapOf(
                "id" to "The call activity's id.", "process" to "The key of the deployed Process it calls.",
                "inputs" to strings("Values by start-form field name, each text or a `\${…}` reference (see `references`)."),
            ), optional = setOf("process", "inputs")),
            Mcp.tool("set_built_in", "Sets a Built-in Task's topic and inputs. clone-repos reads the inputs repos (Source map names or git URLs, such as " +
                "`\${scout.output.repos}`) and branch; accept-recommendations names its asker.", mapOf(
                "id" to "The Built-in Task's id.", "topic" to "Its topic (see `built_in_tasks`).",
                "inputs" to strings("Values by input name, each text or a `\${…}` reference; empty removes one."),
                "asker" to "accept-recommendations only: the Agent Task whose Round it answers; empty clears it.",
            ), optional = setOf("topic", "inputs", "asker")),
            Mcp.tool("set_multi_instance", "Runs an Agent Task, sub-process or call activity once per item of a list, as the wrench menu's toggles and the " +
                "properties panel do. Its Result becomes a list of its instances' Results, in item order.", mapOf(
                "id" to "The element's id.",
                "mode" to mapOf("type" to "string", "enum" to listOf("parallel", "sequential", "none"), "description" to "none makes it run once; left out, the mode it has."),
                "collection" to "The list, a `\${…}` such as `\${totickets.output.tickets}`.",
                "elementVariable" to "The name each instance reads its item as, such as `ticket`; left out, item.",
                "itemsAreTickets" to yesNo("Whether each item is a Ticket, which goes in-review as its instance ends."),
            ), optional = setOf("mode", "collection", "elementVariable", "itemsAreTickets")),
            Mcp.tool("drop_template", "Drops a copy of a Template (see `templates`) as a collapsed sub-process, as the palette does, and gives its id. " +
                "It keeps the Template's ids, suffixed _2, _3 … on a clash. It reloads the diagram, so Undo can't take it back.", mapOf(
                "name" to "The Template's name.", "x" to number(AT), "y" to number(AT),
            ), optional = setOf("x", "y")),
        )

        private val RELAYED = setOf("diagram") + WRITE.map { it["name"] }

        private val TOOLS = listOf(
            Mcp.tool("diagram", "The diagram open in the modeler, as it is now with the user's unsaved edits: its elements (id, kind, name, " +
                "position and size, the sub-process they are in, their properties (multiInstance among them), their inputs, and the outputs later Tasks read of a " +
                "Task's Result), its connections (from, to, name, condition, " +
                "and whether it is its gateway's default) and the start form's fields."),
            Mcp.tool("agents", "The Agents a Task can pick: each one's name, description, the Skills it runs, and whether it is the user's own (else read-only)."),
            Mcp.tool("skills", "The Skills: each one's name, description, the inputs it declares (which an Agent Task fills), and the Result schema it promises (output)."),
            Mcp.tool("skill", "One Skill in full: its instructions, the inputs it declares, the Skills it uses and its Result schema.", mapOf("name" to "The Skill's name.")),
            Mcp.tool("processes", "The deployed Processes, the shipped ones included, which a call activity can call: each one's key, name and start-form fields (its inputs)."),
            Mcp.tool("process", "A deployed Process's BPMN XML, to read as an example.", mapOf("key" to "The Process's key.")),
            Mcp.tool("built_in_tasks", "The Built-in Tasks, by topic: what each one is called and what Ants does in it."),
            Mcp.tool("templates", "The Templates, small diagrams to drop in as a collapsed sub-process: each one's name, title and whether it is shipped."),
            Mcp.tool("references", "The `\${…}` references an element of the open diagram can read: start-form fields, Ants' Run variables and the Results of the Tasks before it.",
                mapOf("element" to "The element's id.")),
            Mcp.tool("checks", "The Checks on the open diagram, as Save and deploy runs them: each problem's message, the element it is on, and whether it is only a warning."),
            Mcp.tool("create_agent", "Creates one of the user's own Agents from its agent file, Claude Code's format: frontmatter (name, description, " +
                "cli claude or codex, skills, tools as permission rules such as `Read, Bash(git log *)`, model; a codex Agent has a sandbox instead of tools) " +
                "and its standing instructions below. Saved at once, so a Task can pick it; answers its name and page, or what is wrong with it.",
                mapOf("file" to "The agent file.")),
            Mcp.tool("create_skill", "Creates one of the user's own Skills from its SKILL.md (frontmatter name and description, " +
                "metadata.ants-inputs for its inputs; its instructions below) and its Result schema. Saved at once; answers its name and page, or what is wrong with it.",
                mapOf("skill" to "The SKILL.md.", "schema" to "result.schema.json, a JSON Schema of the Result it promises; none when left out."), optional = setOf("schema")),
            Mcp.tool("change_agent", "Replaces one of the user's own Agents with a new agent file; a new name in it renames the Agent. Shipped and outside Agents are " +
                "read-only: copy one first.", mapOf("name" to "The Agent's name now.", "file" to "The whole new agent file.")),
            Mcp.tool("change_skill", "Replaces one of the user's own Skills' SKILL.md and Result schema; a new name in it renames the Skill. Shipped and outside Skills are " +
                "read-only: copy one first.",
                mapOf("name" to "The Skill's name now.", "skill" to "The whole new SKILL.md.", "schema" to "The whole new result.schema.json; none when left out."),
                optional = setOf("schema")),
            Mcp.tool("build_log", "Replaces this Process's Build log, the Markdown handoff the next dialog about it starts from: what the Process is for " +
                "and the user's intent, the decisions taken, and what is left. Rewrite it whole at the end of every turn; keep it compact.",
                mapOf("markdown" to "The whole new Build log.")),
            Mcp.tool("copy", "Copies an Agent, Skill or Template, such as a shipped one, into the user's own as `<name>-copy`, which can then be changed. Answers the copy's name and page.",
                mapOf("kind" to "agent, skill or template.", "name" to "The name of the one to copy.")),
        ) + WRITE
    }
}
