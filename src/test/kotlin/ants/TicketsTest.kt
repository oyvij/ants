package ants

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class TicketsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    @BeforeEach
    fun setUp() {
        agentTask(claude)
    }

    private val init = """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}"""
    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"done","summary":"Cut the Tickets.","output":{}}}"""

    /** What a planner writes: the Spec and two Tickets, the first done, the second blocked by it. */
    private val written = arrayOf(
        """write tickets/spec.md # Greet the world\n\nStatus: open\n\nSay hello, then goodbye.\n""",
        """write tickets/issues/01-hello.md # Say hello\n\nStatus: done\n\nBlocked by: none\n\n## Acceptance criteria\n\n- [x] Says hello\n""",
        """write tickets/issues/02-goodbye.md # Say goodbye\n\nStatus: ready-for-agent\n\nBlocked by: 01\n\n""" +
            """## Acceptance criteria\n\n- [ ] Says goodbye\n- [ ] Waves\n\n## Comments\n\nLooks small.\n""",
    )

    /** Starts a Run whose Agent writes the Spec and its Tickets, then waits until the test lets it go on; the Run's id. */
    private fun start(vararg more: String): String {
        claude.session("draft", init, *written, *more, "wait go", done)
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        val run = browser.post("/processes/agent-task/runs", mapOf("prompt" to "Greet")).path.removePrefix("/runs/")
        eventually { assertContains(browser.get("/runs/$run/tickets/02-goodbye").body, "Say goodbye") }
        return run
    }

    @Test
    fun `Ticket files a fake Agent writes show on the Tickets page with n of m done`() {
        val run = start()

        val tickets = browser.get("/tickets").body
        assertContains(tickets, """<a href="/runs/$run/tickets/spec">Greet the world</a>""")
        assertContains(tickets, "<td>open</td><td>1/2 done</td>")
        assertContains(tickets, Regex("""<a href="/runs/$run">Agent Task, [^<]+</a> · Agent Task</td>"""))
    }

    @Test
    fun `a Ticket renders GitHub-style, with ticking checkboxes, Blocked by as links and Comments`() {
        val run = start()

        val page = browser.get("/runs/$run/tickets/02-goodbye").body
        assertContains(page, "<h1>Say goodbye</h1>")
        assertContains(page, """Blocked by: <a rel="nofollow" href="/runs/$run/tickets/01-hello">01</a>""")
        assertContains(page, """<input type="checkbox" data-line="8"> Says goodbye""")
        assertContains(page, "<h2>Comments</h2>\n<p>Looks small.</p>")
        val spec = browser.get("/runs/$run/tickets/spec").body
        assertContains(spec, """<a href="/runs/$run/tickets/01-hello">Say hello</a> · done""")
    }

    @Test
    fun `ticking a checkbox on the page updates the file`() {
        val run = start()
        val page = browser.get("/runs/$run/tickets/02-goodbye").body

        browser.post("/runs/$run/tickets/02-goodbye/tick", mapOf("line" to "9", "version" to version(page)))

        val file = workspace(run).resolve("tickets/issues/02-goodbye.md").readText()
        assertContains(file, "- [ ] Says goodbye\n- [x] Waves\n")
        assertContains(browser.get("/runs/$run/tickets/02-goodbye").body, """<input type="checkbox" checked="" data-line="9"> Waves""")

        val stale = browser.post("/runs/$run/tickets/02-goodbye/tick", mapOf("line" to "8", "version" to version(page)))
        assertContains(stale.body, "This file changed since you opened it, so nothing was saved.")
        assertContains(workspace(run).resolve("tickets/issues/02-goodbye.md").readText(), "- [ ] Says goodbye\n")
    }

    @Test
    fun `a Ticket's page follows the file as an Agent changes it`() {
        val run = start("wait edit", """write tickets/issues/02-goodbye.md # Say goodbye\n\nStatus: in-progress\n""")
        val events = browser.events("/runs/$run/tickets/02-goodbye/live")
        assertContains(events.poll(10, SECONDS)!!, "Status: ready-for-agent")

        claude.release("edit")

        assertContains(events.poll(10, SECONDS)!!, "Status: in-progress")
    }

    @Test
    fun `saving over a file an Agent changed is refused and shows the new version`() {
        val run = start("wait edit", """write tickets/issues/02-goodbye.md # Say goodbye\n\nStatus: in-progress\n\nThe Agent was here.\n""")
        val edit = browser.get("/runs/$run/tickets/02-goodbye/edit").body
        assertContains(edit, "- [ ] Says goodbye\n- [ ] Waves")
        claude.release("edit")
        val file = workspace(run).resolve("tickets/issues/02-goodbye.md")
        eventually { assertContains(file.readText(), "The Agent was here.") }

        val refused = browser.post("/runs/$run/tickets/02-goodbye/edit", mapOf("text" to "# Mine\n", "version" to version(edit)))

        assertContains(refused.body, "This file changed since you opened it, so nothing was saved.")
        assertContains(refused.body, "<p>The Agent was here.</p>")
        assertContains(refused.body, ">\n# Mine\n</textarea>")
        assertContains(file.readText(), "The Agent was here.")

        val saved = browser.post("/runs/$run/tickets/02-goodbye/edit", mapOf("text" to "# Mine\n", "version" to version(refused.body)))
        assertEquals("/runs/$run/tickets/02-goodbye", saved.path)
        assertEquals("# Mine\n", file.readText())
    }

    @Test
    fun `a ready-for-human Ticket is on the Notification list until marked done`() {
        val run = start("""write tickets/issues/03-sign.md # Sign the letter\n\nStatus: ready-for-human\n""")
        eventually { assertContains(browser.get("/notifications").body, "Sign the letter") }
        val list = browser.get("/notifications").body
        assertContains(list, "[ready-for-human]")
        assertContains(list, """<a href="/runs/$run">Agent Task, """)
        val id = Regex("""href="/notifications/([^"]+)">Sign the letter""").find(list)!!.groupValues[1]

        val page = browser.get("/notifications/$id")
        assertEquals("/runs/$run/tickets/03-sign", page.path)
        val done = browser.post("/runs/$run/tickets/03-sign/done", mapOf())

        assertEquals("/runs/$run/tickets/03-sign", done.path)
        assertContains(workspace(run).resolve("tickets/issues/03-sign.md").readText(), "Status: done\n")
        assertFalse("Sign the letter" in browser.get("/notifications").body)
    }

    @Test
    fun `when the Run ends its Spec closes, and its Tickets survive cleanup and are listed in the Report`() {
        val run = start()
        assertContains(workspace(run).resolve("docs/agents/issue-tracker.md").readText(), "`tickets/issues/<NN>-<slug>.md`")

        claude.release("go")

        eventually { assertContains(browser.get("/runs/$run").body, """<a href="/runs/$run/report">Report</a>""") }
        assertContains(browser.get("/tickets").body, "<td>closed</td><td>1/2 done</td>")
        assertContains(browser.get("/runs/$run/tickets/02-goodbye").body, "Says goodbye")
        val report = browser.get("/runs/$run/report").body
        assertContains(report, """<h2>Tickets</h2>""")
        assertContains(report, """<a rel="nofollow" href="/runs/$run/tickets/spec">Greet the world</a>""")
        assertContains(report, """<li><a rel="nofollow" href="/runs/$run/tickets/01-hello">Say hello</a> · done</li>""")
        assertContains(report, """<li><a rel="nofollow" href="/runs/$run/tickets/02-goodbye">Say goodbye</a> · ready-for-agent</li>""")
    }

    @Test
    fun `a cancelled Run's Spec becomes wontfix`() {
        val stuck = done.replace(""""outcome":"done","summary":"Cut the Tickets.","output":{}""", """"outcome":"stuck","summary":"Lost.","needs":"Help."""")
        claude.session("draft", init, *written, stuck)
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        val run = browser.post("/processes/agent-task/runs", mapOf("prompt" to "Greet")).path.removePrefix("/runs/")
        var entry = ""
        eventually { entry = assertNotNull(Regex("""href="(/notifications/[^"]+)">Draft""").find(browser.get("/notifications").body)).groupValues[1] }
        browser.get(entry)

        browser.post(entry, mapOf("action" to "cancel", "instruction" to ""))

        eventually { assertContains(browser.get("/tickets").body, """<a href="/runs/$run/tickets/spec">Greet the world</a></td><td>wontfix</td>""") }
    }

    /** The version of the file [page] shows. */
    private fun version(page: String) = Regex("""(?:data-version="|name="version" value=")(\w+)""").find(page)!!.groupValues[1]
}
