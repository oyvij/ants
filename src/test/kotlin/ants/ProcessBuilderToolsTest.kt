package ants

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Process Builder's MCP session (ADR 0019): a modeler page opens it and gets a token of its own on `/mcp`, which
 * opens only the modeler tools. The test acts as the page: it follows its SSE and answers each call relayed to it.
 */
class ProcessBuilderToolsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login(); it.get("/processes") } }
    private val json = JsonMapper.shared()

    @Autowired
    private lateinit var protectedCommands: ProtectedCommands

    @Autowired
    private lateinit var builder: ProcessBuilderTools

    private val agentTaskXml = javaClass.getResource("/agent-task.bpmn")!!.readText()

    /** Opens a Process Builder session as a modeler page does; its token. */
    private fun open(): String = json.readValue(browser.post("/process-builder", emptyMap()).body, Map::class.java)["token"].toString()

    private fun tools(token: String) = ((mcp(port, token, "tools/list").second["result"] as Map<*, *>)["tools"] as List<*>).map { (it as Map<*, *>)["name"] }

    private fun call(token: String, name: String, arguments: Map<String, Any?> = emptyMap()) = mcpCall(port, token, name, arguments)

    /** Acts as the page holding [token]: answers each relayed call with what [answer] gives for its tool. */
    private fun page(token: String, answer: (String, Map<*, *>) -> Any?) {
        val events = browser.events("builder=$token", "builder")
        Thread.ofVirtual().start {
            while (true) {
                val relayed = json.readValue(events.take(), Map::class.java)
                browser.post("/process-builder/answer", mapOf("token" to token, "call" to relayed["id"].toString(),
                    "answer" to json.writeValueAsString(answer(relayed["tool"].toString(), relayed["arguments"] as Map<*, *>))))
            }
        }
        eventually { assertTrue(builder.connected(token)) }
    }

    @Test
    fun `a page's token lists the modeler's read tools and not run, and a Task's token lists run and no modeler tool`() {
        val page = open()
        val task = protectedCommands.open(Files.createTempDirectory("workspace")).first
        try {
            assertEquals(listOf("diagram", "agents", "skills", "skill", "processes", "process", "built_in_tasks", "templates", "references", "checks"), tools(page))
            assertEquals(listOf("run"), tools(task))
            assertTrue(call(task, "diagram").second)
            assertTrue(call(page, "run", mapOf("command" to "ls")).second)
        } finally {
            protectedCommands.close(task)
        }
    }

    @Test
    fun `the diagram tool is relayed to the page and answered with what it sends back`() {
        val token = open()
        val diagram = mapOf("elements" to listOf(mapOf("id" to "start", "kind" to "start")), "connections" to emptyList<Any>(), "startForm" to emptyList<Any>())
        page(token) { tool, _ -> diagram.takeIf { tool == "diagram" } }
        val (text, failed) = call(token, "diagram")
        assertEquals(false, failed, text)
        assertEquals(diagram, json.readValue(text, Map::class.java))
    }

    @Test
    fun `the catalogue tools and the Checks answer as the modeler's endpoints do, the Checks on the page's diagram`() {
        agentTask(null, mapOf("draft" to """{"type":"object","properties":{"notes":{"type":"string"}}}"""))
        browser.import("agent-task.bpmn")
        val token = open()
        val broken = agentTaskXml.replace("value=\"writer\"", "value=\"nobody\"")
        page(token) { tool, _ -> broken.takeIf { tool == "xml" } }

        fun tool(name: String, arguments: Map<String, Any?> = emptyMap()) = call(token, name, arguments).also { assertEquals(false, it.second, it.first) }.first.let { json.readTree(it) }
        fun endpoint(body: String) = json.readTree(body)
        val catalogue = endpoint(browser.get("/catalogue.json").body)
        assertEquals(catalogue["agents"], tool("agents"))
        assertEquals(catalogue["skills"], tool("skills"))
        assertEquals(endpoint(browser.get("/processes.json").body), tool("processes"))
        assertEquals(endpoint(browser.get("/templates.json").body), tool("templates"))
        assertEquals(json.valueToTree(builtIns), tool("built_in_tasks"))
        assertEquals(endpoint(browser.post("/processes/checks", mapOf("bpmn" to broken)).body), tool("checks"))
        assertTrue(tool("checks").toString().contains("nobody"))
        assertEquals(endpoint(browser.post("/processes/inputs", mapOf("bpmn" to broken, "element" to "draft")).body), tool("references", mapOf("element" to "draft")))

        val skill = tool("skill", mapOf("name" to "draft"))
        assertEquals("Do it.", skill["instructions"].asString())
        assertEquals(listOf("notes"), skill["output"]["properties"].propertyNames().toList())
        assertTrue(call(token, "skill", mapOf("name" to "nothing")).second)
        assertTrue(call(token, "process", mapOf("key" to "agent-task")).first.contains("""<bpmn:serviceTask id="draft""""))
        assertTrue(call(token, "process", mapOf("key" to "nothing")).second)
    }

    @Test
    fun `a diagram call with no page connected fails at once, and one the page never answers fails at the time limit`() {
        val token = open()
        val start = System.nanoTime()
        val (text, failed) = call(token, "diagram")
        assertTrue(failed)
        assertTrue("No modeler page" in text, text)
        assertTrue(Duration.ofNanos(System.nanoTime() - start) < Duration.ofSeconds(2))

        builder.timeLimit = Duration.ofMillis(500)
        try {
            browser.events("builder=$token", "builder") // connected, never answering
            eventually { assertTrue(builder.connected(token)) }
            val late = CompletableFuture.supplyAsync { call(token, "diagram") }.get(10, SECONDS)
            assertTrue(late.second)
            assertTrue("didn't answer" in late.first, late.first)
        } finally {
            builder.timeLimit = ProcessBuilderTools.TIME_LIMIT
        }
    }
}

/** Calls Ants' `/mcp` on [port] with [token]: the HTTP status and the JSON-RPC response. */
fun mcp(port: Int, token: String, method: String, params: Map<String, Any?> = emptyMap()): Pair<Int, Map<*, *>> {
    val json = JsonMapper.shared()
    val body = json.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))
    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp")).POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json").header("Authorization", "Bearer $token")
    val response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
    return response.statusCode() to json.readValue(response.body(), Map::class.java)
}

/** Calls the MCP tool [name] with [token]: its text, and whether it failed. */
fun mcpCall(port: Int, token: String, name: String, arguments: Map<String, Any?> = emptyMap()): Pair<String, Boolean> {
    val result = mcp(port, token, "tools/call", mapOf("name" to name, "arguments" to arguments)).second["result"] as Map<*, *>
    return ((result["content"] as List<*>).single() as Map<*, *>)["text"].toString() to (result["isError"] == true)
}
