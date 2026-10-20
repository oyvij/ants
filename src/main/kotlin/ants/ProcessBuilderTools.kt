package ants

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
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
 * the Checks by the Checks Save and deploy runs, on the page's diagram. The token dies once the page's SSE has been
 * gone for a minute.
 */
@RestController
class ProcessBuilderTools(
    private val mcp: Mcp, private val agents: Agents, private val templates: Templates, private val processes: Processes, private val catalogue: Catalogue,
    private val json: JsonMapper,
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
                "diagram" -> ask(name, arguments)
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
                else -> throw Refused("No tool $name here.")
            }
            text to false
        } catch (e: Exception) {
            (e.message ?: e.toString()) to true
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

        private val TOOLS = listOf(
            Mcp.tool("diagram", "The diagram open in the modeler, as it is now with the user's unsaved edits: its elements (id, kind, name, " +
                "position and size, the sub-process they are in, and their properties and inputs), its connections (from, to, name, condition, " +
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
        )
    }
}
