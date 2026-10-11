package ants

import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    /** Starts a Run of [key], whose Processes here have no start form. */
    private fun start(key: String): Browser.Page {
        browser.get("/processes/$key")
        return browser.post("/processes/$key/runs", mapOf())
    }

    @Test
    fun `Start creates a Run, whose workspace is gone once it ends`() {
        browser.import("start-end.bpmn")
        val run = start("start-end")
        val id = run.path.removePrefix("/runs/")
        assertContains(run.body, "Completed")
        assertContains(browser.get("/runs").body, """<a href="/runs/$id">Start to end</a>""")
        assertFalse(home.resolve("runs/$id").exists())
    }

    private val claude = FakeClaude()

    /** loop-then-wait's Agent Task `a` is done at once, so the Run loops through it twice, then waits on User Task `b`. */
    private fun importLoopThenWait() {
        home.resolve("settings.yaml").writeText("claude:\n  command: [${claude.command}]\n")
        home.resolve("agents").createDirectories().resolve("writer.md").writeText("Write plainly.\n")
        val skill = home.resolve("skills/draft").createDirectories()
        skill.resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts a text.\n---\nDraft the text.\n")
        skill.resolve("result.schema.json").deleteIfExists()
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
