package ants

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeoutException
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
    private val skills: Skills, private val json: JsonMapper,
) {
    private val pages = ConcurrentHashMap<String, Page>()

    /** How long a page has to answer a relayed call. */
    internal var timeLimit: Duration = TIME_LIMIT

    /** One open modeler page: the calls waiting to go out on its SSE, and those waiting for its answer. */
    private inner class Page : McpTools {
        /** When the page's SSE last asked for calls to send; it has never asked while [polled] is false. */
        @Volatile
        var seen = System.nanoTime()

        @Volatile
        var polled = false

        val outbox = ConcurrentLinkedQueue<String>()
        val waiting = ConcurrentHashMap<String, CompletableFuture<String>>()

        val connected get() = polled && System.nanoTime() - seen < CONNECTED.toNanos()
        override val alive get() = System.nanoTime() - seen < GONE.toNanos()
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
        fun saved(page: String) = json.writeValueAsString(mapOf("name" to page.substringAfterLast('/'), "page" to page))

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
        pages.entries.removeIf { (token, page) -> !page.alive.also { if (!it) mcp.close(token) } }
        val page = Page()
        val (token, _) = mcp.open(page) // ponytail: the --mcp-config is dropped; the claude session (#171) keeps it
        pages[token] = page
        return mapOf("token" to token)
    }

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
        private val GONE = Duration.ofMinutes(1)

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
            Mcp.tool("copy", "Copies an Agent, Skill or Template, such as a shipped one, into the user's own as `<name>-copy`, which can then be changed. Answers the copy's name and page.",
                mapOf("kind" to "agent, skill or template.", "name" to "The name of the one to copy.")),
        ) + WRITE
    }
}
