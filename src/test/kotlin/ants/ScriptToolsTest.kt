package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptToolsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()
    private val json = JsonMapper.shared()
    private val secrets = home.resolve("secrets.yaml")

    @Autowired
    lateinit var scripts: Scripts

    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"done","summary":"Drafted.","output":{}}}"""

    @AfterEach
    fun noSecrets() {
        secrets.deleteIfExists()
    }

    private fun script(id: String, name: String, description: String = "Does $name.", inputs: List<String> = emptyList(), code: String = "console.log('hi')\n") =
        Script(id, name, description, untypedInputs(*inputs.toTypedArray())).also { scripts.save(it, code, emptyMap()) }

    private fun agentWithScripts(vararg ids: String, cli: String = "") {
        agentTask(claude, agent = cli + "scripts: [${ids.joinToString(", ")}]\n", extra = "node: {path: ${ScriptsTest.NODE}}\n", env = listOf("PATH", "HOME"))
        claude.session("draft", "wait go", done)
    }

    private fun start(properties: String = ""): String {
        val bpmn = javaClass.getResource("/agent-task.bpmn")!!.readText()
            .replace("""<operaton:property name="ants.skills" value="draft" />""", """<operaton:property name="ants.skills" value="draft" />$properties""")
        browser.get("/processes")
        browser.upload("/processes", "file", "agent-task.bpmn", bpmn.toByteArray())
        browser.get("/processes/agent-task")
        return browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    private fun launch(): FakeClaude.Launch {
        eventually { assertEquals(1, claude.launches("draft").size) }
        return claude.launches("draft").single()
    }

    private fun server(launch: FakeClaude.Launch): Pair<String, String> {
        val ants = (json.readValue(launch.flag("--mcp-config"), Map::class.java)["mcpServers"] as Map<*, *>)["ants"] as Map<*, *>
        return ants["url"].toString() to (ants["headers"] as Map<*, *>)["Authorization"].toString()
    }

    private fun rpc(launch: FakeClaude.Launch, method: String, params: Map<String, Any?> = emptyMap()): Map<*, *> {
        val (url, authorization) = server(launch)
        val body = json.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))
        val request = HttpRequest.newBuilder(URI(url)).POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json").header("Authorization", authorization).build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return json.readValue(response.body(), Map::class.java)["result"] as Map<*, *>
    }

    private fun tools(launch: FakeClaude.Launch) = (rpc(launch, "tools/list")["tools"] as List<*>).map { it as Map<*, *> }

    private fun scriptTools(launch: FakeClaude.Launch) = launch.flag("--allowedTools")!!.split(",").filter { it.startsWith("mcp__ants__script_") }

    @Test
    fun `a claude Agent Task gets exactly its Agent's Scripts as tools, each with the Script's description and inputs as its schema`() {
        script("s-post01", "post-message", "Posts a message to Slack.", listOf("channel", "text"))
        script("s-tidy01", "tidy")
        script("s-other1", "other")
        agentWithScripts("s-post01", "s-tidy01")
        start()
        val launch = launch()

        assertEquals(listOf("mcp__ants__script_post-message", "mcp__ants__script_tidy"), scriptTools(launch).sorted())
        val listed = tools(launch).associateBy { it["name"] }
        assertEquals(setOf("run", "script_post-message", "script_tidy"), listed.keys)
        val post = listed.getValue("script_post-message")
        assertEquals("Posts a message to Slack.", post["description"])
        val schema = post["inputSchema"] as Map<*, *>
        assertEquals("object", schema["type"])
        assertEquals(setOf("channel", "text"), (schema["properties"] as Map<*, *>).keys)
        assertEquals(listOf("channel", "text"), schema["required"])
        assertEquals(emptyMap<Any, Any>(), (listed.getValue("script_tidy")["inputSchema"] as Map<*, *>)["properties"])
        claude.release("go")
    }

    @Test
    fun `a Script tool's description says what the payload holds, from the output schema, and a payload that breaks it answers an error naming the mismatch`() {
        val output = mapOf("type" to "object", "properties" to mapOf("ts" to mapOf("type" to "string", "description" to "When it was posted.")), "required" to listOf("ts"))
        Script("s-post01", "post-message", "Posts a message.", untypedInputs("ts"), output).also { scripts.save(it, "ants.output({ts: ants.inputs.ts})\n", emptyMap()) }
        script("s-tidy01", "tidy")
        agentWithScripts("s-post01", "s-tidy01")
        start()
        val launch = launch()

        val listed = tools(launch).associateBy { it["name"] }
        assertEquals(
            "Posts a message.\n\nThe answer's payload holds what it hands back, fitting this JSON Schema: " +
                """{"type":"object","properties":{"ts":{"type":"string","description":"When it was posted."}},"required":["ts"]}""",
            listed.getValue("script_post-message")["description"],
        )
        assertEquals("Does tidy.", listed.getValue("script_tidy")["description"])

        val (fits, fine) = call(launch, "script_post-message", mapOf("ts" to "1.2"))
        assertEquals(false, fine, fits.toString())
        assertEquals(mapOf("ts" to "1.2"), fits["payload"])

        val (broken, error) = call(launch, "script_post-message", mapOf("ts" to 12))
        assertEquals(true, error)
        assertEquals(0, broken["exitCode"])
        assertEquals("The payload breaks the Script's output schema:\n- /ts: integer found, string expected\n", broken["output"])
        assertEquals(mapOf("ts" to 12), broken["payload"])
        claude.release("go")
    }

    private fun call(launch: FakeClaude.Launch, tool: String, arguments: Map<String, Any?>): Pair<Map<*, *>, Boolean> {
        val result = rpc(launch, "tools/call", mapOf("name" to tool, "arguments" to arguments))
        val text = ((result["content"] as List<*>).single() as Map<*, *>)["text"].toString()
        return json.readValue(text, Map::class.java) to (result["isError"] == true)
    }

    @Test
    fun `calling a Script tool runs the Script in the Run workspace and answers with its exit code, masked output and payload, an error when it exits non-zero`() {
        val code = """
            |const inputs = ants.inputs
            |console.log(`to ${'$'}{inputs.channel} in ${'$'}{process.cwd()} with ${'$'}{process.env.SLACK_TOKEN}`)
            |ants.output({posted: inputs.text, run: process.env.ANTS_RUN_ID})
            |process.exit(inputs.fail ? 4 : 0)
        """.trimMargin()
        Script("s-post01", "post-message", "Posts a message.", untypedInputs("channel", "text", "fail"), secrets = listOf("SLACK_TOKEN"))
            .also { scripts.save(it, code, mapOf("SLACK_TOKEN" to "s3cret-slack")) }
        agentWithScripts("s-post01")
        val run = start()
        val launch = launch()
        val workspace = workspace(run).toRealPath()

        val (posted, failed) = call(launch, "script_post-message", mapOf("channel" to "#dev", "text" to "hello", "fail" to false))
        assertEquals(false, failed)
        assertEquals(mapOf("exitCode" to 0, "output" to "to #dev in $workspace with ••••\n", "payload" to mapOf("posted" to "hello", "run" to run)), posted)

        val (refused, error) = call(launch, "script_post-message", mapOf("channel" to "#dev", "text" to "hello", "fail" to true))
        assertEquals(true, error)
        assertEquals(4, refused["exitCode"])

        claude.release("go")
        eventually { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        assertFalse(Files.walk(workspace).filter { it.isRegularFile() }.anyMatch { "s3cret" in it.readText() || "ants.output" in it.readText() })
    }

    @Test
    fun `ants scripts narrows the Agent's Scripts to the ones the Task lists, by id or by name`() {
        script("s-post01", "post-message")
        script("s-tidy01", "tidy")
        agentWithScripts("s-post01", "s-tidy01")
        start("""<operaton:property name="ants.scripts" value="tidy" />""")
        val launch = launch()

        assertEquals(listOf("mcp__ants__script_tidy"), scriptTools(launch))
        assertEquals(setOf("run", "script_tidy"), tools(launch).map { it["name"] }.toSet())
        claude.release("go")
    }

    @Test
    fun `a Script name a tool name can't hold is sanitised and cut to fit, and each tool name is unique within the launch`() {
        script("s-slack01", "post to slack!")
        script("s-slack02", "post to slack?")
        script("s-long01", "a".repeat(60))
        agentWithScripts("s-slack01", "s-slack02", "s-long01")
        start()
        val launch = launch()

        val names = scriptTools(launch)
        assertEquals(listOf("mcp__ants__script_" + "a".repeat(46), "mcp__ants__script_post_to_slack_", "mcp__ants__script_post_to_slack__2"), names.sorted())
        assertTrue(names.all { Regex("[A-Za-z0-9_-]{1,64}").matches(it) })
        assertEquals(names.map { it.removePrefix("mcp__ants__") }.toSet() + "run", tools(launch).map { it["name"] }.toSet())
        claude.release("go")
    }

    @Test
    fun `a Script that throws answers with Node's error but not the line of source Node quotes`() {
        script("s-throw01", "thrower", code = "const secretPlan = 'the-plan'\nthrow new Error('boom ' + secretPlan.length)\n")
        agentWithScripts("s-throw01")
        start()
        val launch = launch()

        val (thrown, failed) = call(launch, "script_thrower", emptyMap())
        assertEquals(true, failed)
        val output = thrown["output"].toString()
        assertContains(output, "Error: boom 8")
        assertFalse("secretPlan" in output, output)
        claude.release("go")
    }
}
