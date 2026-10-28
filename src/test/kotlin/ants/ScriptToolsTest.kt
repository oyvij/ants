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
    fun `a claude Agent Task gets exactly its Agent's Scripts as tools, each with the Script's description and input schema as its own`() {
        val inputs = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "channel" to mapOf("type" to "string", "description" to "Where to post."),
                "text" to mapOf("type" to "string"),
                "ticket" to mapOf(
                    "type" to "object", "description" to "The ticket it's about.",
                    "properties" to mapOf("title" to mapOf("type" to "string"), "labels" to mapOf("type" to "array", "items" to mapOf("type" to "string"))),
                    "required" to listOf("title"),
                ),
            ),
            "required" to listOf("channel"),
        )
        scripts.save(Script("s-post01", "post-message", "Posts a message to Slack.", inputs), "console.log('hi')\n", emptyMap())
        script("s-tidy01", "tidy")
        script("s-other1", "other")
        agentWithScripts("s-post01", "s-tidy01")
        start()
        val launch = launch()

        assertEquals(listOf("mcp__ants__script_post-message", "mcp__ants__script_tidy"), scriptTools(launch).sorted())
        val listed = tools(launch).associateBy { it["name"] }
        assertEquals(setOf("script_post-message", "script_tidy"), listed.keys)
        val post = listed.getValue("script_post-message")
        assertEquals("Posts a message to Slack.", post["description"])
        assertEquals(inputs, post["inputSchema"])
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
    fun `a Script tool runs the published Script while the Script has a Draft`() {
        script("s-post01", "post-message", description = "Posts as published.", inputs = listOf("text"), code = "ants.output({said: 'published'})\n")
        val draft = listOf("name" to "post-message", "description" to "Posts as drafted.", "code" to "ants.output({said: 'drafted'})\n", "vars" to "") + ScriptsTest.inputRows("text, channel")
        assertEquals(200, browser.autosave("/scripts/post-message", draft, "0").status)
        agentWithScripts("s-post01")
        start()
        val launch = launch()

        assertEquals(listOf("mcp__ants__script_post-message"), scriptTools(launch))
        val post = tools(launch).single { it["name"] == "script_post-message" }
        assertEquals("Posts as published.", post["description"])
        assertEquals(setOf("text"), (post["inputSchema"] as Map<*, *>)["properties"].let { it as Map<*, *> }.keys)
        assertEquals(mapOf("said" to "published"), call(launch, "script_post-message", mapOf("text" to "hi")).first["payload"])
        claude.release("go")
        browser.post("/scripts/post-message/discard", mapOf("confirm" to "on")) // ANTS_HOME's scripts folder starts empty for the next test, which would leave this Draft as a Draft-only post-message
    }

    @Test
    fun `an Agent whose file lists a Draft-only Script is never launched with it as a tool, and the Draft never runs`() {
        script("s-post01", "post-message")
        val page = browser.newDraft("/scripts")
        val drafted = listOf("name" to "drafted-only", "description" to "Only drafted.", "code" to "import {writeFileSync} from 'node:fs'\nwriteFileSync('ran.txt', 'ran')\n", "vars" to "")
        assertEquals(200, browser.autosave(page, drafted, draftVersion(browser.get(page).body)).status)
        val id = Drafts(home).all(DraftKind.SCRIPT).single().id
        agentWithScripts("s-post01", id) // written by hand: the Agents page's Publish refuses an Agent listing a Draft-only Script
        val run = start()

        eventually { assertContains(browser.get("/runs/$run").body, "No Script named $id") }
        assertEquals(0, claude.launches("draft").size)
        assertFalse(workspace(run).resolve("ran.txt").isRegularFile())
    }

    @Test
    fun `a Script tool call whose arguments break the input schema answers an error with the reason, and the Script doesn't run`() {
        val inputs = mapOf("type" to "object", "properties" to mapOf("count" to mapOf("type" to "number"), "text" to mapOf("type" to "string")), "required" to listOf("text"))
        scripts.save(Script("s-count01", "count", "Counts.", inputs), "import {writeFileSync} from 'node:fs'\nwriteFileSync('ran.txt', 'ran')\nants.output({ok: true})\n", emptyMap())
        agentWithScripts("s-count01")
        val run = start()
        val launch = launch()

        val (refused, error) = call(launch, "script_count", mapOf("count" to "three"))
        assertEquals(true, error)
        val output = refused["output"].toString()
        assertContains(output, "The inputs break the Script's input schema, so it didn't run:")
        assertContains(output, "/count")
        assertContains(output, "text")
        assertEquals(null, refused["payload"])
        assertFalse(workspace(run).resolve("ran.txt").isRegularFile())

        val (ran, failed) = call(launch, "script_count", mapOf("text" to "hi"))
        assertEquals(false, failed)
        assertEquals(mapOf("ok" to true), ran["payload"])
        claude.release("go")
    }

    @Test
    fun `ants scripts narrows the Agent's Scripts to the ones the Task lists, by id or by name`() {
        script("s-post01", "post-message")
        script("s-tidy01", "tidy")
        agentWithScripts("s-post01", "s-tidy01")
        start("""<operaton:property name="ants.scripts" value="tidy" />""")
        val launch = launch()

        assertEquals(listOf("mcp__ants__script_tidy"), scriptTools(launch))
        assertEquals(setOf("script_tidy"), tools(launch).map { it["name"] }.toSet())
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
        assertEquals(names.map { it.removePrefix("mcp__ants__") }.toSet(), tools(launch).map { it["name"] }.toSet())
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
