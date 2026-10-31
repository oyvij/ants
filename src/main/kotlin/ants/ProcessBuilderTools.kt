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
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/** What create_skill and change_skill answer when given a `schema`: a Skill has none (ADR 0024), so it isn't written. */
const val SCHEMA_IGNORED = "The schema wasn't written: a Skill has no Result schema. Put it on the Agent Task that needs it, as set_agent_task's resultSchema."

data class BuilderColumn(val name: String, val title: String, val intro: String, val placeholder: String) {
    val path = "/$name"

    fun off(settings: Settings) = if (settings.chat(name).first == "claude") "" else PageChat.claudeOnly(title, name)

    companion object {
        val PROCESS = BuilderColumn("process-builder", "Process Builder",
            "Say what this Process should get done, loosely is fine: the Process Builder asks about the gaps, proposes a shape and builds it here, each change one undo step.",
            "e.g. Every morning, pick a small issue and get it to a PR. (Enter sends, Shift+Enter adds a line)")
        val TEMPLATE = BuilderColumn("template-builder", "Template Builder",
            "Say what this Template should do, loosely is fine: the Template Builder asks about the gaps, proposes a shape and builds it here, each change one undo step, unsaved until you Save.",
            "e.g. A review loop: an Agent reviews, I approve or send it back. (Enter sends, Shift+Enter adds a line)")
    }
}

/**
 * The Process Builder's MCP session (ADR 0019): a modeler page that can be edited by hand opens one and gets a token
 * of its own on `/mcp`, which opens only the modeler tools. A tool that changes the diagram is relayed to the page that
 * holds the token, as an event `builder` on its SSE (`/live?builder=<token>`); the page answers from bpmn-js with a POST
 * to `/process-builder/answer`. Reading it, Ants asks the page for its XML and reads that as it reads a Template or a
 * deployed Process ([Diagrams]). The catalogue tools are answered by Ants, from what the modeler's own endpoints serve, and
 * the Checks by the Checks Publish runs, on the page's diagram. Creating, changing and copying Agents, Skills
 * and Templates go through their pages' own save and Copy, and answer the item's page.
 */
@RestController
class ProcessBuilderTools(
    private val chats: PageChats, private val agents: Agents, private val templates: Templates, private val processes: Processes, private val catalogue: Catalogue,
    private val skills: Skills, private val scripts: Scripts, private val json: JsonMapper, private val launcher: AgentLauncher, private val diagrams: Diagrams,
) {
    private abstract inner class Builder(column: BuilderColumn) : PageChat(chats) {
        override val name = column.name
        override val title = column.title
        override val pageName = "modeler page"
        override val idPrefix = "pb"
        override val lines = LINES
        override val readsOwnToolResults = true

        /** The pages of the Agents, Skills and Templates this session made or changed, in order. */
        val made: MutableSet<String> = ConcurrentHashMap.newKeySet()

        abstract val kind: String

        abstract fun buildLogName(): String

        abstract fun buildLog(name: String): Path

        abstract fun buildLogPage(name: String): String

        @Volatile
        var handoff: String? = null

        fun writeBuildLog(markdown: String): String {
            val name = buildLogName()
            buildLog(name).also { it.parent.createDirectories() }.writeAtomically(markdown)
            return "The Build log of $name is now what you gave."
        }

        override fun firstPrompt(message: String): String? {
            val name = runCatching { buildLogName() }.getOrNull()
            val log = name?.let { runCatching { buildLog(it) }.getOrNull() }?.takeIf { it.exists() } ?: return null
            handoff = name
            return "The Build log of this $kind, from earlier dialogs about it:\n\n${log.readText().trim()}\n\nFrom the user:\n$message"
        }

        override fun head() =
            handoff?.let { """<p class="ants" id="pb-handoff">Started from this $kind's <a href="${HtmlUtils.htmlEscape(buildLogPage(it))}" target="_blank">Build log</a>.</p>""" } ?: ""

        override fun call(name: String, arguments: Map<*, *>): Pair<String, Boolean> = try {
            fun argument(key: String) = arguments[key]?.toString() ?: throw Refused("$name needs $key.")
            val text = when (name) {
                "set_agent_task" -> ask(name, arguments).let { written ->
                    val refusals = processes.checks(xml()).filter { it.element == arguments["id"] && !it.warning }.map { it.message }
                    json.writeValueAsString(json.readValue(written, Map::class.java) + ("checks" to refusals))
                }
                "add_element", "insert" -> ask(name, arguments - "properties" + ("properties" to scriptProperties(arguments)))
                in RELAYED -> ask(name, arguments)
                "scripts" -> json.writeValueAsString(scripts.all().map { mapOf("name" to it.name, "description" to it.description, "inputs" to it.inputs, "output" to it.output) })
                "agents", "skills" -> json.writeValueAsString((agents.catalogue()[name] as List<*>).map { (it as Map<*, *>) - "id" })
                "skill" -> catalogue.skill(argument("name")).let { skill ->
                    if (catalogue.hides(skill.file)) throw Refused("No Skill named ${skill.name}")
                    json.writeValueAsString(mapOf(
                        "name" to skill.name, "description" to skill.description, "instructions" to frontmatter(skill.file.readText()).second.trim(),
                        "files" to skill.folder.walk().filter { it != skill.file && it.isRegularFile() }.map { it.relativeTo(skill.folder).toString() }.sorted().toList(),
                    ))
                }
                "processes" -> json.writeValueAsString(processes.processes())
                "diagram" -> summary(diagrams.read(xml().toByteArray()), arguments)
                "process" -> summary(deployed(argument("key")), arguments)
                "template" -> summary(diagrams.read(templates.diagram(argument("name"))), arguments)
                "element" -> json.writeValueAsString(diagrams.element(when {
                    arguments["process"] != null -> deployed(argument("process"))
                    arguments["template"] != null -> diagrams.read(templates.diagram(argument("template")))
                    else -> diagrams.read(xml().toByteArray())
                }, argument("id")))
                "built_in_tasks" -> json.writeValueAsString(BUILT_IN_TASKS)
                "templates" -> json.writeValueAsString(templates.json())
                "references" -> json.writeValueAsString(processes.inputs(xml(), argument("element")))
                "checks" -> json.writeValueAsString(processes.checks(xml()))
                "create_agent" -> saved(agents.write(argument("file"), null))
                "change_agent" -> saved(agents.write(argument("file"), argument("name")))
                "create_skill" -> saved(skills.write(argument("skill"), null), arguments)
                "change_skill" -> saved(skills.write(argument("skill"), argument("name")), arguments)
                "set_your_skills" -> saved(agents.setYourSkills(argument("name"), (arguments["skills"] as? List<*> ?: throw Refused("$name needs skills.")).map(Any?::toString)))
                "delete_agent" -> deleted(agents.delete(argument("name")))
                "delete_skill" -> deleted(skills.delete(argument("name")))
                "build_log" -> writeBuildLog(argument("markdown"))
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
        fun saved(page: String, arguments: Map<*, *> = emptyMap<String, Any>()) = json.writeValueAsString(
            mapOf("name" to page.substringAfterLast('/'), "page" to page) + listOfNotNull(("note" to SCHEMA_IGNORED).takeIf { arguments["schema"] != null }),
        ).also { made += page }

        fun scriptProperties(arguments: Map<*, *>): Map<String, String> {
            val named = arguments["script"]?.toString()
            if (arguments["kind"] != "script") return if (named == null && arguments["inputs"] == null) emptyMap() else throw Refused("Only a Script Task takes a script or inputs.")
            if (named == null) return emptyMap()
            val script = scripts.named(named) ?: throw Refused("No Script named $named. $MISSING_SCRIPT")
            return mapOf(SCRIPT to script.id, nameBeside(script.id) to script.name)
        }

        /** What diagram, process and template answer: the part of [diagram] the [arguments] scope, one page of it. */
        fun summary(diagram: Diagram, arguments: Map<*, *>): String {
            val ids = arguments["ids"]?.let { it as? List<*> ?: throw Refused("ids must be a list of element ids.") }?.map(Any?::toString)
            val cursor = arguments["cursor"]?.toString()?.let { it.toDoubleOrNull()?.toInt() ?: throw Refused("cursor must be a number, not $it.") } ?: 0
            return json.writeValueAsString(diagrams.summary(diagram, arguments["in"]?.toString()?.takeIf(String::isNotEmpty), ids, cursor))
        }

        fun deployed(key: String): Diagram {
            if (processes.processes().none { it["key"] == key }) throw Refused("No Process $key is deployed.")
            return diagrams.read(processes.diagram(key))
        }

        fun deleted(page: String) = "Deleted the ${KINDS[page.split('/')[1]]} ${page.substringAfterLast('/')}.".also { made -= page }

        override fun foot() = if (made.isEmpty()) "" else """<ul id="pb-made" aria-label="Made">""" + made.joinToString("") { page ->
            val kind = KINDS[page.split('/')[1]]
            """<li><a href="${HtmlUtils.htmlEscape(page)}" target="_blank">$kind ${HtmlUtils.htmlEscape(page.substringAfterLast('/'))}</a></li>"""
        } + "</ul>"

        /** The page's diagram as BPMN XML, as Publish would send it. */
        fun xml(): String = json.readValue(ask("xml", emptyMap<String, Any>()), String::class.java)
    }

    private inner class ProcessPage : Builder(BuilderColumn.PROCESS) {
        override val instructions = INSTRUCTIONS
        override val tools = TOOLS
        override val kind = "Process"

        /** The process id of the page's diagram, which keys its Build log. */
        override fun buildLogName(): String = process(parse(xml().toByteArray())).getAttribute("id")

        override fun buildLog(name: String) = this@ProcessBuilderTools.buildLog(name)

        override fun buildLogPage(name: String) = "/processes/$name/build-log"
    }

    private inner class TemplatePage(private val template: String) : Builder(BuilderColumn.TEMPLATE) {
        override val instructions = TEMPLATE_INSTRUCTIONS
        override val tools = TEMPLATE_TOOLS
        override val kind = "Template"

        override fun buildLogName() = template

        override fun buildLog(name: String) = templates.buildLog(name)

        override fun buildLogPage(name: String) = "/templates/$name/build-log"
    }

    @PostMapping("/process-builder")
    fun open(@RequestParam(required = false) token: String?): Map<String, String> = mapOf("token" to chats.open(token, ProcessPage::class.java) { ProcessPage() })

    @PostMapping("/process-builder/send")
    fun send(@RequestParam token: String, @RequestParam message: String): Map<String, String> = chats.send(token, message, BuilderColumn.PROCESS.title)

    @PostMapping("/template-builder")
    fun openTemplate(@RequestParam(required = false) token: String?, @RequestParam template: String): Map<String, String> {
        if (!templates.isYours(template)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Template of your own named $template.")
        return mapOf("token" to chats.open(token, TemplatePage::class.java) { TemplatePage(template) })
    }

    @PostMapping("/template-builder/send")
    fun sendTemplate(@RequestParam token: String, @RequestParam message: String): Map<String, String> = chats.send(token, message, BuilderColumn.TEMPLATE.title)

    /** A Process's Build log, to read: the Markdown the Process Builder last wrote for it. */
    @GetMapping("/processes/{key}/build-log", produces = ["text/plain;charset=UTF-8"])
    fun readBuildLog(@PathVariable key: String): String =
        runCatching { buildLog(key) }.getOrNull()?.takeIf { it.exists() }?.readText() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$key has no Build log.")

    /**
     * Where the Process [key]'s Build log lives: `ANTS_HOME/processes/<key>/build-log.md`, apart from deploys and drafts,
     * so it outlives both. A key that isn't a plain folder name is refused, so nothing is written outside that folder.
     */
    private fun buildLog(key: String): Path = processFolder(launcher.home, key)?.resolve("build-log.md") ?: throw Refused("The Process id `$key` can't name a folder, so it gets no Build log: give it letters, digits, `_`, `-` and `.` only.")

    @PostMapping("/process-builder/stop", "/template-builder/stop")
    fun stop(@RequestParam token: String) = chats.stop(token)

    @PostMapping("/process-builder/answer", "/template-builder/answer")
    fun answer(@RequestParam token: String, @RequestParam call: String, @RequestParam(required = false) answer: String?, @RequestParam(required = false) error: String?) =
        chats.answer(token, call, answer, error)

    companion object {
        /** The Process Builder's standing instructions, shipped read-only. */
        private val INSTRUCTIONS = ProcessBuilderTools::class.java.getResource("/process-builder.md")!!.readText()

        private val TEMPLATE_INSTRUCTIONS = ProcessBuilderTools::class.java.getResource("/template-builder.md")!!.readText()

        private val BUILT_IN_TASKS = builtIns + (ScriptTask.TOPIC to mapOf("name" to elementKinds.getValue("script").getValue("name"),
            "does" to "runs one of your Scripts (see `scripts`) in the Run workspace: draw it with add_element's script kind, not as a Built-in Task."))

        private const val MISSING_SCRIPT = "You can't write or change a Script. When a Task needs one that doesn't exist, tell the user so plainly and " +
            "hand them a text to paste into the Script editor's helper, saying what the Script must do, its inputs as a JSON Schema (types, descriptions, " +
            "which are required) and its payload as a JSON Schema. Make the gap prominent in your reply, so the user doesn't forget it before deploying."

        /** How a Script Task is wired from its Script's schemas (see `scripts`). */
        private const val WIRED = "A Script Task sets every input its input schema marks required, with a value or `\${…}` that fits its type; " +
            "later Tasks read only the payload fields its output schema declares, as \${<task id>.payload.<field>} (any field, when it has no output schema)."

        private val KINDS = mapOf("agents" to "Agent", "skills" to "Skill", "templates" to "Template")

        private fun buildLogTool(kind: String) = Mcp.tool("build_log", "Replaces this $kind's Build log, the Markdown handoff the next dialog about it starts from: " +
            "what the $kind is for and the user's intent, the decisions taken, and what is left. Rewrite it whole at the end of every turn; keep it compact.",
            mapOf("markdown" to "The whole new Build log."))

        private fun number(what: String) = mapOf("type" to "number", "description" to what)

        /** A `{name: text}` argument's schema. */
        private fun strings(what: String) = mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string"), "description" to what)

        private fun yesNo(what: String) = mapOf("type" to "boolean", "description" to what)

        /** What a Skill is, for create_skill and change_skill (ADR 0024). */
        private const val PLAIN = "A Skill is plain and reusable: it must not mention Runs, Tasks or Rounds, nor declare inputs or a Result. What a step must do " +
            "and hand back goes on its Agent Task (set_agent_task's assignment and resultSchema), and the Agent invokes a Skill on its own."

        /** How diagram, process and template answer a large diagram. */
        private const val SUMMARY = "A text longer than ${Diagrams.SHORT} characters shows its start, its length and truncated: `element` gives it in full. " +
            "A large diagram comes a page at a time: when the answer has next, call again with that cursor."

        /** What diagram, process and template read of a diagram: part of it, and the page. */
        private val SCOPE = mapOf(
            "in" to "A sub-process's id: only what is in it.",
            "ids" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Only these elements, with their connections."),
            "cursor" to number("Where the page starts: the next of the answer before; left out, the start."),
        )

        private const val AT = "Its top-left corner on the diagram, as `diagram` gives positions."

        /** The tools that change the diagram: relayed to the page, which makes each call one undo step (modeler.js). */
        private val WRITE = listOf(
            Mcp.tool("add_element", "Adds an element to the open diagram, as the palette does, and gives its id. Without x and y it goes right of the " +
                "rightmost element in its container. Connect it with `connect`.", mapOf(
                "kind" to mapOf("type" to "string", "enum" to drawnKinds.keys.toList(), "description" to "What it is: exclusive is a decision; subprocess an expanded embedded sub-process."),
                "id" to "Its id, a JUEL name such as `grill` (letters, digits and underscores), by which later Tasks read its Result; left out, bpmn-js makes one.",
                "name" to "Its name on the diagram.",
                "x" to number(AT), "y" to number(AT),
                "in" to "The id of the sub-process to add it in; left out, the Process itself.",
                "topic" to "A Built-in Task's topic (see `built_in_tasks`); left out, clone-repos.",
                "script" to "A Script Task's Script, by its name (see `scripts`); Ants links it by its id. $MISSING_SCRIPT",
                "inputs" to strings("A Script Task's inputs, by the Script's input names, each text or a `\${…}` reference (see `references`). $WIRED"),
            ), optional = setOf("id", "name", "x", "y", "in", "topic", "script", "inputs")),
            Mcp.tool("remove", "Removes an element, with its connections, or a connection: by its id, or a connection by the elements it connects.", mapOf(
                "id" to "The element's or connection's id.", "from" to "A connection's source element, with to.", "to" to "A connection's target element, with from.",
            ), optional = setOf("id", "from", "to")),
            Mcp.tool("insert", "Puts a new element on the connection from one element to another, as one step: the connection now ends at the new " +
                "element, keeping its id, name, condition and default, and a new connection goes on from it to where the old one went. Answers the new " +
                "element's id and both connections' ids. Without x and y it goes between the two; move what is in the way.", mapOf(
                "from" to "The connection's source element.", "to" to "The connection's target element.",
                "kind" to mapOf("type" to "string", "enum" to (drawnKinds.keys - setOf("start", "end", "annotation", "group")).toList(), "description" to "What it is, as add_element takes it."),
                "id" to "Its id, a JUEL name such as `repos_missing`; left out, bpmn-js makes one.",
                "name" to "Its name on the diagram.", "x" to number(AT), "y" to number(AT),
                "topic" to "A Built-in Task's topic (see `built_in_tasks`); left out, clone-repos.",
                "script" to "A Script Task's Script, by its name (see `scripts`). $MISSING_SCRIPT",
                "inputs" to strings("A Script Task's inputs, by the Script's input names. $WIRED"),
                "connection" to "The new connection's id, out of the new element; left out, bpmn-js makes one.",
            ), optional = setOf("id", "name", "x", "y", "topic", "script", "inputs", "connection")),
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
            Mcp.tool("set_agent_task", "Sets an Agent Task's Agent, Assignment, Skills, inputs, Result schema and toggles, as the properties panel does. " +
                "A Task needs an Assignment, or no Assignment and exactly one listed Skill, which is then its job (as typing /<skill>). Its listed Skills " +
                "must be its Agent's (see `agents`); a new Agent drops the listed Skills it lacks, and picks its first Skill for a Task with neither. " +
                "Don't name a Skill to use in the Assignment: the list decides it. Answers what was written, and the Checks Publish " +
                "refuses on this Task: fix each before you're done.", mapOf(
                "id" to "The Agent Task's id.", "agent" to "The Agent's name (see `agents`).",
                "assignment" to "The job, in a few lines: what this step must do and hand back. Empty removes it. To change part of it, give assignmentEdit or assignmentAppend instead.",
                "assignmentEdit" to mapOf("type" to "object", "required" to listOf("old", "new"), "properties" to mapOf("old" to mapOf("type" to "string"), "new" to mapOf("type" to "string")),
                    "description" to "Replaces the text old in the Assignment with new, the rest kept as it is; refused unless old is in it exactly once."),
                "assignmentAppend" to "Adds this to the end of the Assignment, on a line of its own, after assignmentEdit when both are given.",
                "skills" to mapOf("type" to listOf("array", "null"), "items" to mapOf("type" to "string"),
                    "description" to "The Agent's Skills this Task may use: [] for none, null for all of the Agent's."),
                "inputs" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string"),
                    "description" to "Values by input name, each text or a `\${…}` reference (see `references`), such as {\"prompt\": \"\${prompt}\"}."),
                "resultSchema" to "A JSON Schema of the Result the Task hands back, so later Tasks read its fields as \${id.output.<field>}; empty removes it, " +
"and the Task hands back only done or stuck with a summary. To change part of it, give resultSchemaPatch instead.",
                "resultSchemaPatch" to mapOf("type" to "object", "description" to "Changes the Result schema in place, keeping the rest as written: " +
                    "properties adds or replaces fields (each a JSON Schema), remove takes fields out (and out of required), required and optional " +
                    "add field names to required or take them out of it.", "properties" to mapOf(
                    "properties" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to "object")),
                    "remove" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "required" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "optional" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                )),
                "resume" to yesNo("Whether the flow coming back (Round answers, Send back) resumes its session."),
                "vault" to yesNo("Whether it reads the Vault and Source map."),
                "answerHistory" to yesNo("Whether it reads the Answer History."),
            ), optional = setOf("agent", "assignment", "assignmentEdit", "assignmentAppend", "skills", "inputs", "resultSchema", "resultSchemaPatch", "resume", "vault", "answerHistory")),
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
                        "stored" to mapOf("type" to "string", "description" to "The value the start form is prefilled with."),
                    ),
                )),
            )),
            Mcp.tool("set_user_task", "Sets a User Task's kind and its fields, as the properties panel does: a new kind drops the fields only the old kind " +
                "has. Later Tasks read its Result: approve and review-changes give \${id.approved} and \${id.comment} (review-changes also \${id.comments}), " +
                "answer-round gives \${id.answers}.", mapOf(
                "id" to "The User Task's id.",
                "kind" to mapOf("type" to "string", "enum" to UserTaskKind.entries.map { it.key }, "description" to "What the user does; left out, the kind it is now."),
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
                "`\${scout.output.repos}`) and branch; accept-recommendations names its asker. It sets a Script Task's inputs too, by its Script's input names (see `scripts`). $WIRED", mapOf(
                "id" to "The Built-in Task's or Script Task's id.", "topic" to "Its topic (see `built_in_tasks`).",
                "inputs" to strings("Values by input name, each text or a `\${…}` reference; empty removes one."),
                "asker" to builtIns.filterValues { "asker" in it }.map { (topic, it) -> "$topic only: ${it["asker"]}" }.joinToString(" ") + " Empty clears it.",
            ), optional = setOf("topic", "inputs", "asker")),
            Mcp.tool("set_multi_instance", "Runs an Agent Task, sub-process or call activity once per item of a list, as the wrench menu's toggles and the " +
                "properties panel do. Its Result becomes a list of its instances' Results, in item order.", mapOf(
                "id" to "The element's id.",
                "mode" to mapOf("type" to "string", "enum" to multiInstanceModes, "description" to "none makes it run once; left out, the mode it has."),
                "collection" to "The list, a `\${…}` such as `\${totickets.output.tickets}`.",
                "elementVariable" to "The name each instance reads its item as, such as `ticket`; left out, item.",
                "itemsAreTickets" to yesNo("Whether each item is a Ticket, which goes in-review as its instance ends."),
            ), optional = setOf("mode", "collection", "elementVariable", "itemsAreTickets")),
            Mcp.tool("drop_template", "Drops a copy of a Template (see `templates`) as a collapsed sub-process, as the palette does, and gives its id. " +
                "It keeps the Template's ids, suffixed _2, _3 … on a clash. It reloads the diagram, so Undo can't take it back.", mapOf(
                "name" to "The Template's name.", "x" to number(AT), "y" to number(AT),
            ), optional = setOf("x", "y")),
        )

        private val RELAYED = WRITE.map { it["name"] }.toSet()

        /** `<i>value</i>`, escaped: what a tool call's line names. */
        private fun named(value: Any?) = "<i>${HtmlUtils.htmlEscape(value?.toString().orEmpty())}</i>"

        /** The `name:` in a file's frontmatter, as an Agent file and a SKILL.md have one. */
        private fun nameIn(file: Any?) = Regex("(?m)^name:\\s*(.+)$").find(file?.toString().orEmpty())?.groupValues?.get(1)?.trim()

        /** How the dialog shows a call of each tool, as HTML, from its arguments: "added Agent Task <i>Grill</i>". */
        private val LINES: Map<String, (Map<*, *>) -> String> = mapOf(
            "diagram" to { it -> "read the diagram" + (it["in"]?.let { inside -> " in ${named(inside)}" } ?: "") },
            "element" to { it -> "read ${named(it["id"])}" + ((it["process"] ?: it["template"])?.let { from -> " in ${named(from)}" } ?: "") },
            "template" to { it -> "read the Template ${named(it["name"])}" },
            "agents" to { _ -> "read the Agents" },
            "skills" to { _ -> "read the Skills" },
            "skill" to { it -> "read the Skill ${named(it["name"])}" },
            "scripts" to { _ -> "read the Scripts" },
            "processes" to { _ -> "read the Processes" },
            "process" to { it -> "read the Process ${named(it["key"])}" },
            "built_in_tasks" to { _ -> "read the Built-in Tasks" },
            "templates" to { _ -> "read the Templates" },
            "references" to { it -> "read what ${named(it["element"])} can refer to" },
            "checks" to { _ -> "read the Checks" },
            "create_agent" to { it -> "created the Agent ${named(nameIn(it["file"]))}" },
            "change_agent" to { it -> "changed the Agent ${named(it["name"])}" },
            "create_skill" to { it -> "created the Skill ${named(nameIn(it["skill"]))}" },
            "change_skill" to { it -> "changed the Skill ${named(it["name"])}" },
            "set_your_skills" to { it ->
                val skills = (it["skills"] as? List<*>).orEmpty()
                "set Your Skills on ${named(it["name"])} to " + if (skills.isEmpty()) "none" else skills.joinToString { name -> named(name) }
            },
            "delete_agent" to { it -> "deleted the Agent ${named(it["name"])}" },
            "delete_skill" to { it -> "deleted the Skill ${named(it["name"])}" },
            "build_log" to { _ -> "rewrote the Build log" },
            "copy" to { it -> "copied the ${it["kind"]?.toString()?.let { kind -> KINDS[kind + "s"] } ?: "item"} ${named(it["name"])}" },
            "add_element" to { it ->
                "added " + (elementKinds[it["kind"]]?.get("name") ?: it["kind"]?.toString().orEmpty()).let(HtmlUtils::htmlEscape) +
                    ((it["name"] ?: it["id"])?.let { name -> " " + named(name) } ?: "")
            },
            "remove" to { it -> "removed " + (it["id"]?.let(::named) ?: "the connection from ${named(it["from"])} to ${named(it["to"])}") },
            "insert" to { it ->
                "inserted " + (elementKinds[it["kind"]]?.get("name") ?: it["kind"]?.toString().orEmpty()).let(HtmlUtils::htmlEscape) +
                    ((it["name"] ?: it["id"])?.let { name -> " " + named(name) } ?: "") + " between ${named(it["from"])} and ${named(it["to"])}"
            },
            "rename" to { it -> "renamed ${named(it["id"])} to ${named(it["name"])}" },
            "move" to { it -> "moved ${named(it["id"])}" },
            "connect" to { it -> "connected ${named(it["from"])} to ${named(it["to"])}" },
            "set_condition" to { it -> "set the condition on ${named(it["id"])}" },
            "set_default" to { it -> "made ${named(it["id"])} the default path" },
            "set_agent_task" to { it ->
                val skills = (it["skills"] as? List<*>)?.let { names -> if (names.isEmpty()) "no Skills" else "the Skills " + names.joinToString { name -> named(name) } }
                    ?: "the Agent's Skills".takeIf { _ -> it.containsKey("skills") }
                val assignment = (it["assignment"] as? String)?.trim()?.takeIf(String::isNotEmpty)
                    ?.let { text -> "the Assignment " + named(if (text.length > 80) text.take(79) + "…" else text) }
                "set up the Agent Task ${named(it["id"])}" + listOfNotNull(assignment, skills).joinToString(" and ").let { parts -> if (parts.isEmpty()) "" else " with $parts" }
            },
            "set_start_form" to { _ -> "set the start form" },
            "set_user_task" to { it -> "set up the User Task ${named(it["id"])}" },
            "set_call" to { it -> "set up the call activity ${named(it["id"])}" },
            "set_built_in" to { it -> "set up the Built-in Task ${named(it["id"])}" },
            "set_multi_instance" to { it -> "set how ${named(it["id"])} runs per item" },
            "drop_template" to { it -> "dropped the Template ${named(it["name"])}" },
        )

        private val TOOLS = listOf(
            Mcp.tool("diagram", "The diagram open in the modeler, as it is now with the user's unsaved edits: its elements (id, kind, name, " +
                "the sub-process they are in, position and size, their incoming and outgoing connections' ids, their properties (multiInstance among them), " +
                "their inputs, and the outputs later Tasks read of a Task's Result), their connections (id, from, to, name, condition, and whether it is " +
                "its decision's default) and the start form's fields. $SUMMARY", SCOPE, optional = SCOPE.keys),
            Mcp.tool("element", "One element in full: every property untruncated (Assignment, Skills, Agent, Result schema, inputs, toggles, " +
                "multi-instance, a User Task's fields), its incoming and outgoing connections, and for a sub-process what is in it, as `diagram` gives it. " +
                "Of the open diagram, or of a deployed Process or a Template.", mapOf(
                "id" to "The element's id.", "process" to "A deployed Process's key, to read it there.", "template" to "A Template's name, to read it there.",
            ), optional = setOf("process", "template")),
            Mcp.tool("agents", "The Agents a Task can pick: each one's name, description, the Skills it runs, and whether it is the user's own (else read-only)."),
            Mcp.tool("skills", "The Skills: each one's name, description, and whether it is the user's own (else read-only)."),
            Mcp.tool("skill", "One Skill in full: its instructions and the files beside its SKILL.md.", mapOf("name" to "The Skill's name.")),
            Mcp.tool("scripts", "The user's Scripts, which a Script Task runs: each one's name, description, input schema (`inputs`, a JSON Schema " +
                "whose properties are its inputs) and output schema (`output`, the payload's JSON Schema, or null when any payload goes), never its code. " +
                "$WIRED $MISSING_SCRIPT"),
            Mcp.tool("processes", "The deployed Processes, the shipped ones included, which a call activity can call: each one's key, name and start-form fields (its inputs)."),
            Mcp.tool("process", "A deployed Process, to read as an example, as `diagram` reads the open one. $SUMMARY",
                mapOf("key" to "The Process's key.") + SCOPE, optional = SCOPE.keys),
            Mcp.tool("built_in_tasks", "The Built-in Tasks, by topic: what each one is called and what Ants does in it."),
            Mcp.tool("templates", "The Templates, small diagrams to drop in as a collapsed sub-process: each one's name, title and whether it is shipped."),
            Mcp.tool("template", "A Template's diagram, as `diagram` reads the open one. $SUMMARY", mapOf("name" to "The Template's name.") + SCOPE, optional = SCOPE.keys),
            Mcp.tool("references", "The `\${…}` references an element of the open diagram can read: start-form fields, Ants' Run variables and the Results of the Tasks before it.",
                mapOf("element" to "The element's id.")),
            Mcp.tool("checks", "The Checks on the open diagram, as Publish runs them: each problem's message, the element it is on, and whether it is only a warning."),
            Mcp.tool("create_agent", "Creates one of the user's own Agents from its agent file, Claude Code's format: frontmatter (name, description, " +
                "cli claude or codex, skills, tools as permission rules such as `Read, Bash(git log *)`, model; a codex Agent has a sandbox instead of tools) " +
                "and its standing instructions below. Saved at once, so a Task can pick it; answers its name and page, or what is wrong with it.",
                mapOf("file" to "The agent file.")),
            Mcp.tool("create_skill", "Creates one of the user's own Skills from its SKILL.md, as Claude Code knows one: frontmatter name and description " +
                "(when to use it), and how to do the work below. $PLAIN Saved at once; answers its name and page, or what is wrong with it.",
                mapOf("skill" to "The SKILL.md.")),
            Mcp.tool("change_agent", "Replaces one of the user's own Agents with a new agent file; a new name in it renames the Agent. Shipped and outside Agents are " +
                "read-only: copy one first.", mapOf("name" to "The Agent's name now.", "file" to "The whole new agent file.")),
            Mcp.tool("change_skill", "Replaces one of the user's own Skills' SKILL.md; a new name in it renames the Skill. $PLAIN Shipped and outside " +
                "Skills are read-only: copy one first.",
                mapOf("name" to "The Skill's name now.", "skill" to "The whole new SKILL.md.")),
            Mcp.tool("delete_agent", "Deletes one of the user's own Agents, such as one you created that the user doesn't want. Shipped and outside Agents are " +
                "read-only and can't be deleted. Ask the user first: a Task that runs it fails the Checks.", mapOf("name" to "The Agent's name.")),
            Mcp.tool("delete_skill", "Deletes one of the user's own Skills with its folder, such as one you created that the user doesn't want. Shipped and outside " +
                "Skills are read-only and can't be deleted. Ask the user first: a Task that runs it fails the Checks.", mapOf("name" to "The Skill's name.")),
            buildLogTool("Process"),
            Mcp.tool("copy", "Copies an Agent, Skill or Template, such as a shipped one, into the user's own as `<name>-copy`, which can then be changed. Answers the copy's name and page.",
                mapOf("kind" to "agent, skill or template.", "name" to "The name of the one to copy.")),
            Mcp.tool("set_your_skills", "Sets Your Skills on a shipped Agent: the Skills the user adds to it beside the ones Ants ships with it, which an update to " +
                "Ants keeps. Use it to give a shipped Agent a Skill instead of copying the Agent. Only Skills: the user adds Your tools on the Agent's page, and its instructions stay Ants'. " +
                "Published at once, so a Task on the Agent can list them; the whole list each time, an empty one removing them all. Answers the Agent's name and page.",
                mapOf("name" to "The shipped Agent's name.", "skills" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "The Skills' names."))),
        ) + WRITE

        private val TEMPLATE_TOOLS = TOOLS.map { if (it["name"] == "build_log") buildLogTool("Template") else it }
    }
}
