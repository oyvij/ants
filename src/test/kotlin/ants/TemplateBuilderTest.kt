package ants

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
