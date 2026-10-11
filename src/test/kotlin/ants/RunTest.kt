package ants

import org.junit.jupiter.api.Test
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    /** Starts a Run of [key], whose Processes here have no start form. */
    private fun start(key: String): Browser.Page {
        browser.get("/processes/$key")
        return browser.post("/processes/$key/runs", mapOf())
    }

    @Test
    fun `Start creates a Run, whose workspace is gone but for its Report once it ends`() {
        home.resolve("settings.yaml").writeText(vault)
        browser.import("start-end.bpmn")
        val run = start("start-end")
        val id = run.path.removePrefix("/runs/")
        assertContains(run.body, "Completed")
        assertContains(browser.get("/runs").body, """<a href="/runs/$id">Start to end</a>""")
        assertEquals(listOf("report.md"), home.resolve("runs/$id").toFile().list()?.toList())
    }

    private val claude = FakeClaude()

    /** loop-then-wait's Agent Task `a` is done at once, so the Run loops through it twice, then waits on User Task `b`. */
    private fun importLoopThenWait() {
        agentTask(claude)
        claude.session("draft", """{"type":"result","subtype":"success","is_error":false,"session_id":"s","structured_output":{"outcome":"done","summary":"Drafted.","output":{}}}""")
        browser.import("loop-then-wait.bpmn")
    }

    @Test
    fun `a live Run has its workspace, null Task results and each Task's run count`() {
        importLoopThenWait()
        val run = start("loop-then-wait")
        assertContains(run.body, "Running")
        assertTrue(home.resolve("runs/${run.path.removePrefix("/runs/")}").isDirectory())
        eventually {
            val variables = browser.get(run.path).body.replace("&#34;", "\"")
            assertContains(variables, "<dt>b</dt><dd><code>null</code></dd>")
            assertContains(variables, """<dt>runs</dt><dd><code>{"a":2,"b":1}</code></dd>""")
        }
    }

    @Test
    fun `many Runs of one Process are live at once`() {
        importLoopThenWait()
        val ids = List(2) { start("loop-then-wait").path.removePrefix("/runs/") }
        val runs = browser.get("/runs").body
        for (id in ids) {
            assertContains(runs, Regex("""<a href="/runs/$id">Loop then wait</a></td><td>Running</td>"""))
            assertTrue(home.resolve("runs/$id").isDirectory())
        }
    }
}
