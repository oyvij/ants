package ants

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Process Builder's claude session (ADR 0019): one live `claude -p` per modeler page, locked down to Ants' modeler
 * tools, which the user's messages go into. The test acts as the page: it opens a session and sends messages.
 */
class ProcessBuilderTest : AntsTest() {
    private val browser by lazy { browser().also { it.login(); it.get("/processes") } }
    private val claude = FakeClaude()
    private val json = JsonMapper.shared()

    @Autowired
    private lateinit var builder: ProcessBuilderTools

    private fun open(): String = json.readValue(browser.post("/process-builder", emptyMap()).body, Map::class.java)["token"].toString()

    private fun send(token: String, message: String) = browser.post("/process-builder/send", mapOf("token" to token, "message" to message))

    private fun launches() = claude.launches("process-builder")

    @Test
    fun `a message launches claude locked down to Ants' modeler tools, on Ants' own login, with the shipped instructions`() {
        settings(claude, "process-builder:\n  model: opus\n")
        claude.session("process-builder", """{"type":"result","subtype":"success","result":"Hello.","session_id":"${'$'}SESSION"}""")
        val token = open()
        send(token, "Every morning, pick a small issue and get it to a PR.")
        eventually { assertEquals(1, launches().size) }
        val launch = launches().single()

        assertContains(launch.argv, "--strict-mcp-config")
        val ants = (json.readValue(launch.flag("--mcp-config"), Map::class.java)["mcpServers"] as Map<*, *>)["ants"] as Map<*, *>
        assertEquals(mapOf("Authorization" to "Bearer $token"), ants["headers"])
        assertEquals("mcp__ants__*", launch.flag("--allowedTools"))
        assertEquals("", launch.flag("--tools")) // no Bash, Read, Write or any other of claude's own tools
        assertEquals("", launch.flag("--setting-sources"))
        assertEquals("""{"disableAllHooks":true}""", launch.flag("--settings"))
        assertEquals("stream-json", launch.flag("--input-format"))
        assertEquals("stream-json", launch.flag("--output-format"))
        assertEquals("opus", launch.flag("--model"))
        assertTrue(launch.flag("--session-id")!!.isNotEmpty())
        assertEquals("# You are the Process Builder", launch.flag("--append-system-prompt")) // argv is recorded a line at a time
        assertContains(launch.argv, "## Work from intent, not orders")
        assertFalse(launch.env.keys.any { it in AgentLauncher.PAID })
        assertEquals(home.resolve("claude").toString(), launch.env["CLAUDE_CONFIG_DIR"])
        assertFalse(launch.cwd.startsWith(home.toString())) // a scratch folder
        assertTrue("Every morning" in launch.stdin)
    }

    @Test
    fun `a follow-up goes into the same session, and once claude has died the next message resumes it`() {
        settings(claude)
        val done = """{"type":"result","subtype":"success","result":"Done.","session_id":"${'$'}SESSION"}"""
        claude.session("process-builder", 1, done, "read", done, "exit 0")
        claude.session("process-builder", 2, done)
        val token = open()
        send(token, "First.")
        eventually { assertEquals(1, launches().size) }
        send(token, "Second.")
        eventually { assertTrue("Second." in launches().single().stdin) }
        eventually { assertTrue(builder.idle(token)) }
        send(token, "Third.")
        eventually { assertEquals(2, launches().size) }
        val (first, second) = launches()
        assertEquals(first.flag("--session-id"), second.flag("--resume"))
        assertEquals(first.cwd, second.cwd) // claude finds a session by its working directory
        assertTrue("Third." in second.stdin)
    }

    @Test
    fun `the dialog shows the replies and tool calls, and links each Agent the Process Builder makes`() {
        settings(claude)
        claude.session("process-builder",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Who answers the questions?"}]},"session_id":"${'$'}SESSION"}""",
            """mcp create_agent {"file":"---\nname: picker\ndescription: Picks an issue.\n---\nPick one.\n"}""",
            """{"type":"result","subtype":"success","result":"Done.","session_id":"${'$'}SESSION"}""")
        val token = open()
        val dialog = browser.events("builder=$token", "dialog")
        send(token, "Build it.")
        val html = dialog.until { "/agents/picker" in it }
        assertTrue("Build it." in html, html)
        assertTrue("Who answers the questions?" in html, html)
        assertTrue("create_agent" in html, html)
        assertTrue("""<a href="/agents/picker" target="_blank">Agent picker</a>""" in html, html) // a new tab keeps the unsaved diagram
    }

    @Test
    fun `Stop interrupts the turn, and closing the page ends claude`() {
        settings(claude)
        claude.session("process-builder", "read", """{"type":"result","subtype":"error_during_execution","session_id":"${'$'}SESSION"}""", "wait never")
        val token = open()
        send(token, "Build it.")
        eventually { assertEquals(1, launches().size) }
        assertFalse(builder.idle(token))
        browser.post("/process-builder/stop", mapOf("token" to token))
        eventually { assertTrue("interrupt" in launches().single().stdin) }
        eventually { assertTrue(builder.idle(token)) }

        browser.post("/process-builder/close", mapOf("token" to token))
        eventually { assertTrue(launches().single().killed) }
    }

    @Test
    fun `a page whose stream has gone for a while ends its claude`() {
        settings(claude)
        claude.session("process-builder", "wait never")
        builder.gone = Duration.ofSeconds(1)
        try {
            val token = open()
            send(token, "Build it.")
            eventually { assertEquals(1, launches().size) }
            eventually { assertTrue(launches().single().killed) }
            assertNotEquals(200, mcp(port, token, "tools/list").first)
        } finally {
            builder.gone = ProcessBuilderTools.GONE
        }
    }

    @Test
    fun `on codex the panel says the Process Builder runs on claude only`() {
        settings(claude, "process-builder:\n  cli: codex\n")
        val token = open()
        assertTrue("The Process Builder runs on claude only for now" in send(token, "Build it.").body)
        assertEquals(0, launches().size)
    }
}
