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

/**
 * The Process Builder's MCP session (ADR 0019): a modeler page that can be edited by hand opens one and gets a token
 * of its own on `/mcp`, which opens only the modeler tools. A tool about the diagram is relayed to the page that holds
 * the token, as an event `builder` on its SSE (`/live?builder=<token>`); the page answers from bpmn-js with a POST to
 * `/process-builder/answer`. The catalogue tools are answered by Ants, from what the modeler's own endpoints serve, and
 * the Checks by the Checks Save and deploy runs, on the page's diagram. Creating, changing and copying Agents, Skills
 * and Templates go through their pages' own save and Copy, and answer the item's page.
 */
@RestController
class ProcessBuilderTools(
    private val chats: PageChats, private val agents: Agents, private val templates: Templates, private val processes: Processes, private val catalogue: Catalogue,
    private val skills: Skills, private val scripts: Scripts, private val json: JsonMapper, private val launcher: AgentLauncher,
) {
    private inner class Page : PageChat(chats) {
        override val name = "process-builder"
        override val title = "Process Builder"
        override val pageName = "modeler page"
        override val instructions = INSTRUCTIONS
        override val idPrefix = "pb"
        override val lines = LINES
        override val tools = TOOLS

        /** The pages of the Agents, Skills and Templates this session made or changed, in order. */
        val made: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** The key of the Process whose Build log this session started from, which the panel links to. */
        @Volatile
        var handoff: String? = null

        override fun call(name: String, arguments: Map<*, *>): Pair<String, Boolean> = try {
            fun argument(key: String) = arguments[key]?.toString() ?: throw Refused("$name needs $key.")
            val text = when (name) {
                "set_agent_task" -> ask(name, arguments).let { written ->
                    val refusals = processes.checks(xml()).filter { it.element == arguments["id"] && !it.warning }.map { it.message }
                    json.writeValueAsString(json.readValue(written, Map::class.java) + ("checks" to refusals))
                }
                "add_element" -> ask(name, arguments - "properties" + ("properties" to scriptProperties(arguments)))
                in RELAYED -> ask(name, arguments)
                "scripts" -> json.writeValueAsString(scripts.all().map { mapOf("name" to it.name, "description" to it.description, "inputs" to it.inputs) })
                "agents", "skills" -> json.writeValueAsString((agents.catalogue()[name] as List<*>).map { (it as Map<*, *>) - "id" })
                "skill" -> catalogue.skill(argument("name")).let { skill ->
                    if (catalogue.hides(skill.file)) throw Refused("No Skill named ${skill.name}")
                    json.writeValueAsString(mapOf(
                        "name" to skill.name, "description" to skill.description, "instructions" to frontmatter(skill.file.readText()).second.trim(),
                        "files" to skill.folder.walk().filter { it != skill.file && it.isRegularFile() }.map { it.relativeTo(skill.folder).toString() }.sorted().toList(),
                    ))
                }
                "processes" -> json.writeValueAsString(processes.processes())
                "process" -> argument("key").let { key ->
                    if (processes.processes().none { it["key"] == key }) throw Refused("No Process $key is deployed.")
                    processes.diagram(key).decodeToString()
                }
                "built_in_tasks" -> json.writeValueAsString(BUILT_IN_TASKS)
                "templates" -> json.writeValueAsString(templates.json())
                "references" -> json.writeValueAsString(processes.inputs(xml(), argument("element")))
                "checks" -> json.writeValueAsString(processes.checks(xml()))
                "create_agent" -> saved(agents.write(argument("file"), null))
                "change_agent" -> saved(agents.write(argument("file"), argument("name")))
                "create_skill" -> saved(skills.write(argument("skill"), null), arguments)
                "change_skill" -> saved(skills.write(argument("skill"), argument("name")), arguments)
                "delete_agent" -> deleted(agents.delete(argument("name")))
                "delete_skill" -> deleted(skills.delete(argument("name")))
                "build_log" -> argument("markdown").let { markdown ->
                    val key = key()
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

        fun deleted(page: String) = "Deleted the ${KINDS[page.split('/')[1]]} ${page.substringAfterLast('/')}.".also { made -= page }

        override fun firstPrompt(message: String): String? {
            val key = runCatching { key() }.getOrNull()
            val log = key?.let { runCatching { buildLog(it) }.getOrNull() }?.takeIf { it.exists() } ?: return null
            handoff = key
            return "The Build log of this Process, from earlier dialogs about it:\n\n${log.readText().trim()}\n\nFrom the user:\n$message"
        }

        override fun head() =
            handoff?.let { """<p class="ants" id="pb-handoff">Started from this Process's <a href="/processes/${HtmlUtils.htmlEscape(it)}/build-log" target="_blank">Build log</a>.</p>""" } ?: ""

        override fun foot() = if (made.isEmpty()) "" else """<ul id="pb-made" aria-label="Made">""" + made.joinToString("") { page ->
            val kind = KINDS[page.split('/')[1]]
            """<li><a href="${HtmlUtils.htmlEscape(page)}" target="_blank">$kind ${HtmlUtils.htmlEscape(page.substringAfterLast('/'))}</a></li>"""
        } + "</ul>"

        /** The page's diagram as BPMN XML, as Save and deploy would send it. */
        fun xml(): String = json.readValue(ask("xml", emptyMap<String, Any>()), String::class.java)

        /** The process id of the page's diagram, which keys its Build log. */
        fun key(): String = process(parse(xml().toByteArray())).getAttribute("id")
    }

    @PostMapping("/process-builder")
    fun open(@RequestParam(required = false) token: String?): Map<String, String> = mapOf("token" to chats.open(token, Page::class.java) { Page() })

    @PostMapping("/process-builder/send")
    fun send(@RequestParam token: String, @RequestParam message: String): Map<String, String> = chats.send(token, message, "Process Builder")

    /** A Process's Build log, to read: the Markdown the Process Builder last wrote for it. */
    @GetMapping("/processes/{key}/build-log", produces = ["text/plain;charset=UTF-8"])
    fun readBuildLog(@PathVariable key: String): String =
        runCatching { buildLog(key) }.getOrNull()?.takeIf { it.exists() }?.readText() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$key has no Build log.")

    /**
     * Where the Process [key]'s Build log lives: `ANTS_HOME/processes/<key>/build-log.md`, apart from deploys and drafts,
     * so it outlives both. A key that isn't a plain folder name is refused, so nothing is written outside that folder.
     */
    private fun buildLog(key: String): Path = processFolder(launcher.home, key)?.resolve("build-log.md") ?: throw Refused("The Process id `$key` can't name a folder, so it gets no Build log: give it letters, digits, `_`, `-` and `.` only.")

    @PostMapping("/process-builder/stop")
    fun stop(@RequestParam token: String) = chats.stop(token)

    @PostMapping("/process-builder/answer")
    fun answer(@RequestParam token: String, @RequestParam call: String, @RequestParam(required = false) answer: String?, @RequestParam(required = false) error: String?) =
        chats.answer(token, call, answer, error)

    companion object {
        val CLAUDE_ONLY = PageChat.claudeOnly("Process Builder", "process-builder")

        /** The Process Builder's standing instructions, shipped read-only. */
        private val INSTRUCTIONS = ProcessBuilderTools::class.java.getResource("/process-builder.md")!!.readText()

        private val BUILT_IN_TASKS = builtIns + (ScriptTask.TOPIC to mapOf("name" to elementKinds.getValue("script").getValue("name"),
            "does" to "runs one of your Scripts (see `scripts`) in the Run workspace: draw it with add_element's script kind, not as a Built-in Task."))

        private const val MISSING_SCRIPT = "You can't write or change a Script. When a Task needs one that doesn't exist, tell the user so plainly and " +
            "hand them a text to paste into the Script editor's helper, saying what the Script must take, do and return."

        private val KINDS = mapOf("agents" to "Agent", "skills" to "Skill", "templates" to "Template")

        private fun number(what: String) = mapOf("type" to "number", "description" to what)

        /** A `{name: text}` argument's schema. */
        private fun strings(what: String) = mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string"), "description" to what)

        private fun yesNo(what: String) = mapOf("type" to "boolean", "description" to what)

        /** What a Skill is, for create_skill and change_skill (ADR 0024). */
        private const val PLAIN = "A Skill is plain and reusable: it must not mention Runs, Tasks or Rounds, nor declare inputs or a Result. What a step must do " +
            "and hand back goes on its Agent Task (set_agent_task's assignment and resultSchema), and the Agent invokes a Skill on its own."

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
                "inputs" to strings("A Script Task's inputs, by the Script's input names, each text or a `\${…}` reference (see `references`)."),
            ), optional = setOf("id", "name", "x", "y", "in", "topic", "script", "inputs")),
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
            Mcp.tool("set_agent_task", "Sets an Agent Task's Agent, Assignment, Skills, inputs, Result schema and toggles, as the properties panel does. " +
                "A Task needs an Assignment, or no Assignment and exactly one listed Skill, which is then its job (as typing /<skill>). Its listed Skills " +
                "must be its Agent's (see `agents`); a new Agent drops the listed Skills it lacks, and picks its first Skill for a Task with neither. " +
                "Don't name a Skill to use in the Assignment: the list decides it. Answers what was written, and the Checks Save and deploy " +
                "refuses on this Task: fix each before you're done.", mapOf(
                "id" to "The Agent Task's id.", "agent" to "The Agent's name (see `agents`).",
                "assignment" to "The job, in a few lines: what this step must do and hand back. Empty removes it.",
                "skills" to mapOf("type" to listOf("array", "null"), "items" to mapOf("type" to "string"),
                    "description" to "The Agent's Skills this Task may use: [] for none, null for all of the Agent's."),
                "inputs" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string"),
                    "description" to "Values by input name, each text or a `\${…}` reference (see `references`), such as {\"prompt\": \"\${prompt}\"}."),
                "resultSchema" to "A JSON Schema of the Result the Task hands back, so later Tasks read its fields as \${id.output.<field>}; empty removes it, " +
                    "and the Task hands back only done or stuck with a summary.",
                "resume" to yesNo("Whether the flow coming back (Round answers, Send back) resumes its session."),
                "vault" to yesNo("Whether it reads the Vault and Source map."),
                "answerHistory" to yesNo("Whether it reads the Answer History."),
            ), optional = setOf("agent", "assignment", "skills", "inputs", "resultSchema", "resume", "vault", "answerHistory")),
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
                "`\${scout.output.repos}`) and branch; accept-recommendations names its asker. It sets a Script Task's inputs too, by its Script's input names (see `scripts`).", mapOf(
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

        private val RELAYED = setOf("diagram") + WRITE.map { it["name"] }

        /** `<i>value</i>`, escaped: what a tool call's line names. */
        private fun named(value: Any?) = "<i>${HtmlUtils.htmlEscape(value?.toString().orEmpty())}</i>"

        /** The `name:` in a file's frontmatter, as an Agent file and a SKILL.md have one. */
        private fun nameIn(file: Any?) = Regex("(?m)^name:\\s*(.+)$").find(file?.toString().orEmpty())?.groupValues?.get(1)?.trim()

        /** How the dialog shows a call of each tool, as HTML, from its arguments: "added Agent Task <i>Grill</i>". */
        private val LINES: Map<String, (Map<*, *>) -> String> = mapOf(
            "diagram" to { _ -> "read the diagram" },
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
            "delete_agent" to { it -> "deleted the Agent ${named(it["name"])}" },
            "delete_skill" to { it -> "deleted the Skill ${named(it["name"])}" },
            "build_log" to { _ -> "rewrote the Build log" },
            "copy" to { it -> "copied the ${it["kind"]?.toString()?.let { kind -> KINDS[kind + "s"] } ?: "item"} ${named(it["name"])}" },
            "add_element" to { it ->
                "added " + (elementKinds[it["kind"]]?.get("name") ?: it["kind"]?.toString().orEmpty()).let(HtmlUtils::htmlEscape) +
                    ((it["name"] ?: it["id"])?.let { name -> " " + named(name) } ?: "")
            },
            "remove" to { it -> "removed ${named(it["id"])}" },
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
                "position and size, the sub-process they are in, their properties (multiInstance among them), their inputs, and the outputs later Tasks read of a " +
                "Task's Result), its connections (from, to, name, condition, " +
                "and whether it is its gateway's default) and the start form's fields."),
            Mcp.tool("agents", "The Agents a Task can pick: each one's name, description, the Skills it runs, and whether it is the user's own (else read-only)."),
            Mcp.tool("skills", "The Skills: each one's name, description, and whether it is the user's own (else read-only)."),
            Mcp.tool("skill", "One Skill in full: its instructions and the files beside its SKILL.md.", mapOf("name" to "The Skill's name.")),
            Mcp.tool("scripts", "The user's Scripts, which a Script Task runs: each one's name, description and inputs, never its code. $MISSING_SCRIPT"),
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
            Mcp.tool("build_log", "Replaces this Process's Build log, the Markdown handoff the next dialog about it starts from: what the Process is for " +
                "and the user's intent, the decisions taken, and what is left. Rewrite it whole at the end of every turn; keep it compact.",
                mapOf("markdown" to "The whole new Build log.")),
            Mcp.tool("copy", "Copies an Agent, Skill or Template, such as a shipped one, into the user's own as `<name>-copy`, which can then be changed. Answers the copy's name and page.",
                mapOf("kind" to "agent, skill or template.", "name" to "The name of the one to copy.")),
        ) + WRITE
    }
}
