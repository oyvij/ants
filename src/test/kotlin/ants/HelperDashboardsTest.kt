package ants

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Process Builder, the Template Builder and the script helper change Dashboards (ADR 0032): each change is
 * suggested in one sentence, and Ants refuses it until the user has replied. The test acts as the page: it opens a
 * session and sends messages, and the fake claude writes and calls the tools.
 */
class HelperDashboardsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login(); it.get("/processes") } }
    private val claude = FakeClaude()
    private val json = JsonMapper.shared()

    @Autowired
    private lateinit var dashboards: Dashboards

    @Autowired
    private lateinit var chats: PageChats

    private val init = """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}"""
    private val done = """{"type":"result","subtype":"success","result":"Done.","session_id":"${'$'}SESSION"}"""

    private fun says(text: String) =
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":${json.writeValueAsString(text)}}]},"session_id":"${'$'}SESSION"}"""

    /** The helpers by their page's path, each with what its page posts to open it. */
    private val helpers = mapOf(
        "process-builder" to emptyMap(),
        "template-builder" to mapOf("template" to "dashboarded"),
        "script-helper" to emptyMap<String, String>(),
    )

    private fun open(helper: String): String {
        if (helper == "template-builder") browser.post("/templates/new", mapOf("name" to "dashboarded"))
        return json.readValue(browser.post("/$helper", helpers.getValue(helper)).body, Map::class.java)["token"].toString()
    }

    private fun send(helper: String, token: String, message: String) = assertEquals("{}", browser.post("/$helper/send", mapOf("token" to token, "message" to message)).body)

    private fun tools(token: String) = ((mcp(port, token, "tools/list").second["result"] as Map<*, *>)["tools"] as List<*>).map { (it as Map<*, *>)["name"] }

    /** Each MCP answer the [helper]'s launch got, in order: its text, and whether it failed. */
    private fun answers(helper: String): List<Pair<String, Boolean>> = (claude.launches(helper).singleOrNull()?.runCatching { file("mcp") }?.getOrNull() ?: "").lines().filter(String::isNotBlank).map { line ->
        val result = json.readValue(line, Map::class.java)["result"] as Map<*, *>
        ((result["content"] as List<*>).single() as Map<*, *>)["text"].toString() to (result["isError"] == true)
    }

    @Test
    fun `each helper gets the tools to list, create, rename and delete Dashboards and Widgets`() {
        for (helper in helpers.keys) {
            val names = tools(open(helper))
            for (tool in listOf("dashboards", "create_dashboard", "rename_dashboard", "delete_dashboard", "add_widget", "rename_widget", "remove_widget")) {
                assertContains(names, tool, helper)
            }
        }
    }

    @Test
    fun `a change the helper suggests is refused until the user replies, and goes through after the reply, as its instructions say`() {
        settings(claude)
        for (helper in helpers.keys) {
            val title = "Disk of $helper"
            claude.session(helper, init,
                says("Shall I add a Dashboard $title?"),
                """mcp create_dashboard {"title":"$title"}""",
                done, "read",
                """mcp create_dashboard {"title":"$title"}""",
                done)
            val token = open(helper)
            send(helper, token, "Make me a Dashboard for disk use.")
            eventually { assertEquals(1, answers(helper).size) }
            val (refusal, refused) = answers(helper).single()
            assertTrue(refused, refusal)
            assertContains(refusal, "the user has replied since")
            assertTrue(dashboards.all().none { it.title == title })

            send(helper, token, "Yes, do.")
            eventually { assertEquals(2, answers(helper).size) }
            val (created, failed) = answers(helper).last()
            assertFalse(failed, created)
            assertEquals(1, dashboards.all().count { it.title == title }, helper)
            val told = claude.launches(helper).single().argv.joinToString("\n")
            for (rule in listOf("## Dashboards", "Suggest each Dashboard change in one sentence", "hard\n  no, never raise that change again")) assertTrue(rule in told, "$helper: $rule")
        }
    }

    @Test
    fun `after one reply the helper adds several Widgets, one with a readable id, lists them with what uses them, and a remove names what breaks`() {
        settings(claude, "node: {path: ${ScriptsTest.NODE}}\n")
        browser.get("/")
        val lab = browser.post("/dashboards", mapOf()).path.removePrefix("/dashboards/")
        claude.session("process-builder", init,
            says("Shall I add a number Widget disk and a log Widget to Home lab?"),
            done, "read",
            """mcp add_widget {"dashboard":"$lab","type":"number","title":"Disk used","id":"disk"}""",
            """mcp add_widget {"dashboard":"$lab","type":"chart","kind":"bar","title":"Load"}""",
            """mcp add_widget {"dashboard":"$lab","type":"text","id":"disk"}""",
            """mcp add_widget {"dashboard":"$lab","type":"text","id":"Not/Readable"}""",
            done, "wait listed",
            """mcp dashboards""",
            says("Remove disk? The Script measure-disk Posts to it and would break."),
            done, "read",
            """mcp remove_widget {"dashboard":"$lab","widget":"disk"}""",
            done)
        val token = open("process-builder")
        send("process-builder", token, "Show my disk use on Home lab.")
        eventually { assertTrue(chats.idle(token)) } // the suggestion written, the turn done
        send("process-builder", token, "Yes.")
        eventually { assertEquals(4, answers("process-builder").size) }
        val (disk, load, again, unreadable) = answers("process-builder")
        assertEquals("Added the number Widget Disk used at $lab/disk; it accepts {value: number, unit?: string, delta?: number}." to false, disk)
        assertFalse(load.second, load.first)
        assertContains(load.first, "Added the chart Widget Load at $lab/w-")
        assertTrue(again.second, again.first)
        assertContains(again.first, "has or had a Widget disk")
        assertTrue(unreadable.second, unreadable.first)
        assertContains(unreadable.first, "A Widget id is lowercase letters")
        assertEquals("/scripts/measure-disk", browser.publishNew("/scripts", listOf("name" to "measure-disk", "description" to "Measures.", "code" to "console.log('hi')\n", "vars" to "", "widgets" to "$lab/disk")).path)

        claude.release("listed")
        eventually { assertEquals(5, answers("process-builder").size) }
        val listed = json.readValue(answers("process-builder")[4].first, List::class.java).map { it as Map<*, *> }.single { it["id"] == lab }
        val widgets = (listed["widgets"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("$lab/disk"), widgets.filter { it["title"] == "Disk used" }.map { it["address"] })
        assertEquals(listOf("measure-disk"), widgets.single { it["id"] == "disk" }["usedBy"])
        assertEquals("bar", widgets.single { it["title"] == "Load" }["kind"])
        assertEquals(listOf("measure-disk"), listed["usedBy"])

        eventually { assertTrue(chats.idle(token)) }
        send("process-builder", token, "Yes, remove it.")
        eventually { assertEquals(6, answers("process-builder").size) }
        val (removed, failed) = answers("process-builder")[5]
        assertFalse(failed, removed)
        assertContains(removed, "Removed the Widget $lab/disk. The Scripts measure-disk still list it")
        assertTrue(dashboards.get(lab).widgets.none { it.id == "disk" })
    }
}
