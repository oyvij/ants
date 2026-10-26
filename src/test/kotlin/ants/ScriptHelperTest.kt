package ants

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.io.path.deleteIfExists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptHelperTest : AntsTest() {
    private val browser by lazy { browser().also { it.login(); it.get("/scripts/new") } }
    private val json = JsonMapper.shared()
    private val secrets = home.resolve("secrets.yaml")

    @Autowired
    private lateinit var chats: PageChats

    private fun open(): String = json.readValue(browser.post("/script-helper", emptyMap()).body, Map::class.java)["token"].toString()

    private fun tools(token: String) = ((mcp(port, token, "tools/list").second["result"] as Map<*, *>)["tools"] as List<*>).map { (it as Map<*, *>)["name"] }

    private fun call(token: String, name: String, arguments: Map<String, Any?> = emptyMap()) = mcpCall(port, token, name, arguments)

    @Test
    fun `a Script editor's token opens the helper's tools only, and buffer reads the page's unsaved code`() {
        val token = open()
        assertEquals(listOf("buffer", "replace_buffer", "schemas", "replace_schemas", "check", "test_run"), tools(token))
        assertEquals(true, call(token, "diagram").second)

        browser.actAsPage(token, chats, "/script-helper/answer") { tool, _ -> "console.log('unsaved')\n".takeIf { tool == "buffer" } }
        val (text, failed) = call(token, "buffer")
        assertFalse(failed, text)
        assertEquals("console.log('unsaved')\n", text)
    }

    @Test
    fun `replace_buffer gives the page the new code, and the page's refusal comes back as the tool's error`() {
        val token = open()
        val replaced = mutableListOf<Any?>()
        browser.actAsPage(token, chats, "/script-helper/answer") { tool, arguments -> null.also { if (tool == "replace_buffer") replaced += arguments["code"] } }
        val (text, failed) = call(token, "replace_buffer", mapOf("code" to "console.log('new')\n"))
        assertFalse(failed, text)
        assertEquals(listOf<Any?>("console.log('new')\n"), replaced)

        val refusing = open()
        val events = browser.events("builder=$refusing", "builder")
        Thread.ofVirtual().start {
            val relayed = json.readValue(events.take(), Map::class.java)
            browser.post("/script-helper/answer", mapOf("token" to refusing, "call" to relayed["id"].toString(), "error" to "The editor is read-only."))
        }
        eventually { assertTrue(chats.connected(refusing)) }
        val (refusal, refused) = call(refusing, "replace_buffer", mapOf("code" to "x"))
        assertTrue(refused)
        assertContains(refusal, "The editor is read-only.")
    }

    @Test
    fun `schemas reads the page's unsaved input and output schemas, and no output rows is no output schema`() {
        var output = listOf(listOf("output[0].name", "ts"), listOf("output[0].type", "string"), listOf("output[0].required", "on"))
        fun form() = listOf(
            listOf("name", "post-message"), listOf("code", "ants.output({ts: '1'})\n"),
            listOf("inputs[0].name", "text"), listOf("inputs[0].type", "string"), listOf("inputs[0].description", "What to post"), listOf("inputs[0].required", "on"),
            listOf("inputs[1].name", "ticket"), listOf("inputs[1].type", "object"),
            listOf("inputs[1].rows[0].name", "labels"), listOf("inputs[1].rows[0].type", "array:string"),
        ) + output
        val token = open()
        browser.actAsPage(token, chats, "/script-helper/answer") { tool, _ -> form().takeIf { tool == "form" } }

        val (text, failed) = call(token, "schemas")
        assertFalse(failed, text)
        assertEquals(mapOf(
            "inputs" to mapOf("type" to "object", "properties" to mapOf(
                "text" to mapOf("type" to "string", "description" to "What to post"),
                "ticket" to mapOf("type" to "object", "properties" to mapOf("labels" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
            ), "required" to listOf("text")),
            "output" to mapOf("type" to "object", "properties" to mapOf("ts" to mapOf("type" to "string")), "required" to listOf("ts")),
        ), json.readValue(text, Map::class.java))

        output = emptyList()
        assertEquals(null, json.readValue(call(token, "schemas").first, Map::class.java).let { assertTrue("output" in it); it["output"] })
    }

    @Test
    fun `replace_schemas gives the page rows for each schema it's given, and refuses a schema that isn't an object's`() {
        val token = open()
        val replaced = mutableListOf<Map<*, *>>()
        browser.actAsPage(token, chats, "/script-helper/answer") { tool, arguments -> null.also { if (tool == "replace_schemas") replaced += arguments } }
        val inputs = mapOf("type" to "object", "properties" to mapOf(
            "text" to mapOf("type" to "string", "description" to "What to post"),
            "ticket" to mapOf("type" to "object", "properties" to mapOf("title" to mapOf("type" to "string")), "required" to listOf("title")),
        ), "required" to listOf("text"))

        val (text, failed) = call(token, "replace_schemas", mapOf("inputs" to inputs))
        assertFalse(failed, text)
        val page = replaced.single()
        assertEquals(setOf("inputs"), page.keys)
        val rows = page["inputs"].toString()
        assertContains(rows, """name="inputs[0].name" value="text"""")
        assertContains(rows, """name="inputs[0].description" value="What to post"""")
        assertContains(rows, """name="inputs[1].rows[0].name" value="title"""")
        assertContains(rows, """<option value="object" selected>""")
        assertEquals(2, Regex("""<input type="checkbox" name="[^"]+\.required" data-field="required" checked>""").findAll(rows).count(), rows)
        assertContains(text, "unsaved")

        val (none, removed) = call(token, "replace_schemas", mapOf("output" to mapOf("type" to "object", "properties" to emptyMap<String, Any>())))
        assertFalse(removed, none)
        assertEquals(setOf("output"), replaced.last().keys)
        assertFalse("<li" in replaced.last()["output"].toString())

        val (refusal, refused) = call(token, "replace_schemas", mapOf("output" to mapOf("type" to "string")))
        assertTrue(refused)
        assertContains(refusal, "object")
        val (nothing, empty) = call(token, "replace_schemas")
        assertTrue(empty, nothing)
        assertEquals(2, replaced.size)
    }

    @Test
    fun `check answers the findings the editor shows for the page's unsaved code and schemas`() {
        val form = listOf(
            listOf("name", "post-message"), listOf("code", "console.log(ants.inputs.txt)\nants.output({posted: true})\n"),
            listOf("inputs[0].name", "text"), listOf("inputs[0].type", "string"),
            listOf("output[0].name", "ts"), listOf("output[0].type", "string"), listOf("output[0].required", "on"),
        )
        // Its own node, so the syntax check runs whatever an earlier test's settings or the tests' PATH hold.
        home.resolve("settings.yaml").writeText("node: {path: ${ScriptsTest.NODE}}\nclaude: {env: [PATH, HOME]}\n")
        val token = open()
        browser.actAsPage(token, chats, "/script-helper/answer") { tool, _ -> form.takeIf { tool == "form" } }

        val (text, failed) = call(token, "check")
        assertFalse(failed, text)
        val findings = json.readValue(text, Map::class.java)["findings"] as List<*>
        val txt = findings.map { it as Map<*, *> }.single { "txt" in it["message"].toString() }
        assertEquals("error" to 1, txt["severity"] to txt["line"])
        assertTrue(findings.any { "posted" in (it as Map<*, *>)["message"].toString() && it["line"] == 2 }, text)
        val editor = json.readValue(browser.post("/script-check", form.map { (name, value) -> name to value }).body, Map::class.java)
        assertEquals(editor, json.readValue(text, Map::class.java))
    }

    @Test
    fun `test_run runs the page's unsaved code with the sample inputs and the Script's real Secrets, and gives back only masked output`() {
        home.resolve("settings.yaml").writeText("node: {path: ${ScriptsTest.NODE}}\nclaude: {env: [PATH, HOME]}\nsecrets:\n  npm: [NPM_TOKEN]\n")
        secrets.writeText("npm: s3cret-npm\n")
        try {
            browser.get("/scripts/new")
            browser.post("/scripts/new", listOf("name" to "post-message", "description" to "Posts a message.", "code" to "console.log('saved')\n",
                "inputs[0].name" to "text", "inputs[0].type" to "string", "vars" to "", "secrets[0].name" to "SLACK_TOKEN", "secrets[0].value" to "xoxb-s3cret", "secrets[0].was" to ""))
            val unsaved = """
                const inputs = ants.inputs
                console.log(`unsaved ${'$'}{inputs.text} to ${'$'}{process.env.CHANNEL} with ${'$'}{process.env.SLACK_TOKEN} and ${'$'}{process.env.NPM_TOKEN}`)
                ants.output({posted: inputs.text, token: process.env.SLACK_TOKEN})
            """.trimIndent()
            var code = unsaved
            fun form() = listOf(
                listOf("name", "post-message"), listOf("description", "Posts a message."), listOf("script", "post-message"), listOf("code", code),
                listOf("inputs[0].name", "text"), listOf("inputs[0].type", "string"), listOf("vars", "CHANNEL=general"), listOf("secrets[0].name", "SLACK_TOKEN"), listOf("secrets[0].value", ""),
                listOf("secrets[0].was", "SLACK_TOKEN"), listOf("linked", "npm"),
            )
            val token = open()
            browser.actAsPage(token, chats, "/script-helper/answer") { tool, _ -> form().takeIf { tool == "form" } }

            val (text, failed) = call(token, "test_run", mapOf("inputs" to mapOf("text" to "hello")))
            assertFalse(failed, text)
            assertFalse("s3cret" in text, text)
            val result = json.readValue(text, Map::class.java)
            assertEquals(0, result["exitCode"])
            assertEquals("unsaved hello to general with •••• and ••••\n", result["output"])
            assertEquals(mapOf("posted" to "hello", "token" to "••••"), result["payload"])
            assertFalse(home.resolve("scripts").listDirectoryEntries().any { "unsaved" in it.resolve("script.mjs").readText() })

            code = "console.error(process.env.SLACK_TOKEN)\nprocess.exit(3)\n"
            val (failure, failing) = call(token, "test_run")
            assertTrue(failing, failure)
            assertEquals(mapOf("exitCode" to 3, "output" to "••••\n", "payload" to null, "mismatches" to emptyList<String>()), json.readValue(failure, Map::class.java))
        } finally {
            secrets.deleteIfExists()
        }
    }

    @Test
    fun `the token dies once the page's stream has gone`() {
        chats.grace = Duration.ofSeconds(1)
        try {
            val token = open()
            val page = browser().also { it.login() }
            page.actAsPage(token, chats, "/script-helper/answer") { _, _ -> "" }
            Thread.sleep(2000)
            assertEquals(200, mcp(port, token, "tools/list").first)
            page.close()
            eventually { assertEquals(401, mcp(port, token, "tools/list").first) }
            assertContains(send(token, "Write it.").body, "session has ended")
        } finally {
            chats.grace = Duration.ofMinutes(10)
        }
    }

    @Test
    fun `a message launches claude on the helper's tools alone, with the helper's instructions`() {
        val claude = FakeClaude()
        settings(claude)
        claude.session("script-helper", """{"type":"result","subtype":"success","result":"Done.","session_id":"${'$'}SESSION"}""")
        val token = open()
        assertEquals(emptyMap<Any, Any>(), json.readValue(send(token, "Post the hand-off's message to Slack.").body, Map::class.java))
        eventually { assertEquals(1, claude.launches("script-helper").size) }
        val launch = claude.launches("script-helper").single()
        val ants = (json.readValue(launch.flag("--mcp-config"), Map::class.java)["mcpServers"] as Map<*, *>)["ants"] as Map<*, *>
        assertEquals(mapOf("Authorization" to "Bearer $token"), ants["headers"])
        assertEquals("mcp__ants__*", launch.flag("--allowedTools"))
        assertEquals("", launch.flag("--tools"))
        assertEquals("# You are the Script editor's helper", launch.flag("--append-system-prompt"))
        assertTrue("Post the hand-off's message to Slack." in launch.stdin)
        assertFalse(launch.cwd.startsWith(home.toString()))
        assertEquals(0, claude.launches("process-builder").size)
    }

    private fun send(token: String, message: String) = browser.post("/script-helper/send", mapOf("token" to token, "message" to message))
}
