package ants

import org.junit.jupiter.api.Test
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    private fun start(key: String, prompt: String): Browser.Page {
        browser.get("/processes/$key")
        return browser.post("/processes/$key/runs", mapOf("prompt" to prompt))
    }

    @Test
    fun `Start with a prompt creates a Run, whose workspace is gone once it ends`() {
        browser.import("start-end.bpmn")
        val run = start("start-end", "Ship it")
        val id = run.path.removePrefix("/runs/")
        assertContains(run.body, "Ship it")
        assertContains(run.body, "Completed")
        assertContains(browser.get("/runs").body, """<a href="/runs/$id">Start to end</a>""")
        assertFalse(home.resolve("runs/$id").exists())
    }

    @Test
    fun `Start without a prompt is refused`() {
        browser.import("start-end.bpmn")
        assertEquals(400, start("start-end", " ").status)
    }

    @Test
    fun `a live Run has its workspace, null Task results and each Task's run count`() {
        browser.import("loop-then-wait.bpmn")
        val run = start("loop-then-wait", "Loop")
        assertContains(run.body, "Running")
        assertTrue(home.resolve("runs/${run.path.removePrefix("/runs/")}").isDirectory())
        val variables = run.body.replace("&#34;", "\"")
        assertContains(variables, "<dt>a</dt><dd><code>null</code></dd>")
        assertContains(variables, "<dt>b</dt><dd><code>null</code></dd>")
        assertContains(variables, """<dt>runs</dt><dd><code>{"a":2,"b":1}</code></dd>""")
    }

    @Test
    fun `many Runs of one Process are live at once`() {
        browser.import("loop-then-wait.bpmn")
        val ids = List(2) { start("loop-then-wait", "Run $it").path.removePrefix("/runs/") }
        val runs = browser.get("/runs").body
        for (id in ids) {
            assertContains(runs, Regex("""<a href="/runs/$id">Loop then wait</a></td><td>Running</td>"""))
            assertTrue(home.resolve("runs/$id").isDirectory())
        }
    }
}
