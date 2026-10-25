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
        assertEquals(listOf("buffer", "replace_buffer", "test_run"), tools(token))
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
    fun `test_run runs the page's unsaved code with the sample inputs and the Script's real Secrets, and gives back only masked output`() {
        home.resolve("settings.yaml").writeText("node: {path: ${ScriptsTest.NODE}}\nclaude: {env: [PATH, HOME]}\nsecrets:\n  npm: [NPM_TOKEN]\n")
        secrets.writeText("npm: s3cret-npm\n")
        try {
            browser.get("/scripts/new")
            browser.post("/scripts/new", listOf("name" to "post-message", "description" to "Posts a message.", "code" to "console.log('saved')\n",
                "inputs" to "text", "vars" to "", "secrets[0].name" to "SLACK_TOKEN", "secrets[0].value" to "xoxb-s3cret", "secrets[0].was" to ""))
            val unsaved = """
                import {readFileSync, writeFileSync} from 'node:fs'
                const inputs = JSON.parse(readFileSync(0, 'utf8'))
                console.log(`unsaved ${'$'}{inputs.text} to ${'$'}{process.env.CHANNEL} with ${'$'}{process.env.SLACK_TOKEN} and ${'$'}{process.env.NPM_TOKEN}`)
                writeFileSync(process.env.ANTS_OUTPUT, JSON.stringify({posted: inputs.text, token: process.env.SLACK_TOKEN}))
            """.trimIndent()
            var code = unsaved
            fun form() = listOf(
                listOf("name", "post-message"), listOf("description", "Posts a message."), listOf("script", "post-message"), listOf("code", code),
                listOf("inputs", "text"), listOf("vars", "CHANNEL=general"), listOf("secrets[0].name", "SLACK_TOKEN"), listOf("secrets[0].value", ""),
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
            assertEquals(mapOf("exitCode" to 3, "output" to "••••\n", "payload" to null), json.readValue(failure, Map::class.java))
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
