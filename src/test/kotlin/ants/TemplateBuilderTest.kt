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
 * The Template Builder's claude session: the Process Builder's, on the user's own Template's modeler page, with its own
 * instructions and its own `template-builder` settings. The test acts as the page: it opens a session and sends messages.
 */
class TemplateBuilderTest : AntsTest() {
    private val browser by lazy { browser().also { it.login(); it.get("/templates") } }
    private val claude = FakeClaude()
    private val json = JsonMapper.shared()

    @Autowired
    private lateinit var builder: PageChats

    private fun open(): String = json.readValue(browser.post("/template-builder", emptyMap()).body, Map::class.java)["token"].toString()

    private fun send(token: String, message: String) = browser.post("/template-builder/send", mapOf("token" to token, "message" to message))

    private val init = """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}"""

    private fun launches() = claude.launches("template-builder")

    @Test
    fun `a message launches claude locked down to Ants' tools, with the Template Builder's instructions and the template-builder model`() {
        settings(claude, "process-builder:\n  model: opus\ntemplate-builder:\n  model: sonnet\n")
        claude.session("template-builder", """{"type":"result","subtype":"success","result":"Hello.","session_id":"${'$'}SESSION"}""")
        val token = open()
        send(token, "A review loop: an Agent reviews, I approve or send it back.")
        eventually { assertEquals(1, launches().size) }
        val launch = launches().single()

        assertContains(launch.argv, "--strict-mcp-config")
        val ants = (json.readValue(launch.flag("--mcp-config"), Map::class.java)["mcpServers"] as Map<*, *>)["ants"] as Map<*, *>
        assertEquals(mapOf("Authorization" to "Bearer $token"), ants["headers"])
        val session = launch.flag("--session-id")!!
        assertEquals("mcp__ants__*,Read(/${home.resolve("claude").toAbsolutePath()}/projects/*/$session/tool-results/**)", launch.flag("--allowedTools"))
        assertEquals("Read", launch.flag("--tools"))
        assertEquals("", launch.flag("--setting-sources"))
        assertEquals("""{"disableAllHooks":true}""", launch.flag("--settings"))
        assertEquals("sonnet", launch.flag("--model"))
        assertEquals("# You are the Template Builder", launch.flag("--append-system-prompt"))
        assertContains(launch.argv, "## Reuse before you create")
        assertContains(launch.argv, "## Lay the diagram out to be read")
        assertFalse(launch.env.keys.any { it in AgentLauncher.PAID })
        assertFalse(launch.cwd.startsWith(home.toString()))
        assertTrue("A review loop" in launch.stdin)
        assertEquals(0, claude.launches("process-builder").size)
    }

    @Test
    fun `once claude has died the next message resumes the session`() {
        settings(claude)
        val done = """{"type":"result","subtype":"success","result":"Done.","session_id":"${'$'}SESSION"}"""
        claude.session("template-builder", 1, init, done, "exit 0")
        claude.session("template-builder", 2, done)
        val token = open()
        send(token, "First.")
        eventually { assertEquals(1, launches().size) }
        eventually { assertFalse(builder.running(token)) }
        send(token, "Second.")
        eventually { assertEquals(2, launches().size) }
        val (first, second) = launches()
        assertEquals(first.flag("--session-id"), second.flag("--resume"))
        assertTrue("Second." in second.stdin)
    }

    @Test
    fun `Stop interrupts the turn`() {
        settings(claude)
        claude.session("template-builder", "read", """{"type":"result","subtype":"error_during_execution","session_id":"${'$'}SESSION"}""")
        val token = open()
        send(token, "Build it.")
        eventually { assertEquals(1, launches().size) }
        browser.post("/template-builder/stop", mapOf("token" to token))
        eventually { assertTrue("interrupt" in launches().single().stdin) }
        eventually { assertTrue(builder.idle(token)) }
    }

    @Test
    fun `a Template page reloaded in its tab gets its session back, and never a Process Builder's`() {
        settings(claude)
        val token = open()
        assertEquals(token, json.readValue(browser.post("/template-builder", mapOf("token" to token)).body, Map::class.java)["token"])
        val process = json.readValue(browser.post("/process-builder", emptyMap()).body, Map::class.java)["token"].toString()
        assertNotEquals(process, json.readValue(browser.post("/template-builder", mapOf("token" to process)).body, Map::class.java)["token"])
    }

    @Test
    fun `a session no message has come to for a while ends its claude, though its page is open`() {
        settings(claude)
        claude.session("template-builder", """{"type":"result","subtype":"success","result":"Done.","session_id":"${'$'}SESSION"}""")
        builder.idleTimeout = Duration.ofSeconds(1)
        try {
            val token = open()
            send(token, "Build it.")
            eventually { assertEquals(1, launches().size) }
            eventually { assertFalse(builder.running(token)) }
        } finally {
            builder.idleTimeout = Duration.ofHours(2)
        }
    }

    @Test
    fun `on codex the column says the Template Builder runs on claude only`() {
        settings(claude, "template-builder:\n  cli: codex\n")
        val token = open()
        assertContains(send(token, "Build it.").body, "The Template Builder runs on claude only for now. Set template-builder's cli to claude in settings.yaml.")
        assertEquals(0, launches().size)
    }
}
