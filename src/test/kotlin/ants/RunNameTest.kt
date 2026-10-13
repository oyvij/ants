package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunNameTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    @Autowired
    lateinit var runtime: RuntimeService

    private val claude = FakeClaude()

    /** loop-then-wait waits on its User Task `b`, so its Run stays live. */
    private fun live(name: String): String {
        agentTask(claude)
        claude.session("draft", """{"type":"result","subtype":"success","is_error":false,"session_id":"s","structured_output":{"outcome":"done","summary":"Drafted.","output":{}}}""")
        browser.import("loop-then-wait.bpmn")
        browser.get("/processes/loop-then-wait")
        return browser.post("/processes/loop-then-wait/runs", mapOf(NAME to name)).path.removePrefix("/runs/")
    }

    @Test
    fun `a named Run shows its name and Process, and its Run workspace and Run log start with it`() {
        val run = live("Tiny todo app!")
        val workspace = workspace(run)
        assertEquals("tiny-todo-app-$run", workspace.name)
        assertTrue(workspace.isDirectory())
        assertTrue(workspace.resolve("run-log.md").readText().startsWith("# Tiny todo app!\n"))
        val page = browser.get("/runs/$run").body
        assertContains(page, "<title>Tiny todo app! · Ants</title>")
        assertContains(page, "<span id=\"name\">Tiny todo app!</span>")
        assertContains(page, """<a href="/processes/loop-then-wait">Loop then wait</a>""")
        assertContains(page, "<small>$run</small>")
        assertContains(page, "<code>$workspace</code>")
        assertContains(browser.get("/runs").body, """<a href="/runs/$run">Tiny todo app!</a></b></td><td class="muted">Loop then wait</td>""")
        eventually { assertContains(browser.get("/notifications").body, """<a href="/runs/$run">Tiny todo app!</a>""") }
    }

    @Test
    fun `an unnamed Run is named after its Process and start time, and a long name's slug stops at 40`() {
        home.resolve("settings.yaml").writeAtomically(vault)
        browser.import("start-end.bpmn")
        browser.get("/processes/start-end")
        val run = browser.post("/processes/start-end/runs", mapOf()).path.removePrefix("/runs/")
        val named = browser.get("/runs/$run").body
        assertContains(named, Regex("<span id=\"name\">Start to end, \\d{1,2} [A-Z][a-z]{2} \\d\\d:\\d\\d</span>"))
        assertTrue(Regex("start-to-end-\\d{1,2}-[a-z]{3}-\\d\\d-\\d\\d-$run").matches(workspace(run).name), workspace(run).name)

        val long = browser.post("/processes/start-end/runs", mapOf(NAME to "A very long name that goes on and on past forty")).path.removePrefix("/runs/")
        assertEquals("a-very-long-name-that-goes-on-and-on-pas-$long", workspace(long).name)
    }

    @Test
    fun `renaming a live or ended Run changes its name, not its folder`() {
        val run = live("First")
        val folder = workspace(run)
        browser.post("/runs/$run/name", mapOf("name" to "Second"))
        assertContains(browser.get("/runs/$run").body, "<span id=\"name\">Second</span>")
        assertEquals(folder, workspace(run))

        browser.post("/runs/$run/cancel", mapOf())
        browser.post("/runs/$run/name", mapOf("name" to "Third"))
        assertContains(browser.get("/runs/$run").body, "<span id=\"name\">Third</span>")
        assertContains(browser.get("/runs").body, """<a href="/runs/$run">Third</a>""")
        assertEquals(folder, workspace(run))
    }

    @Test
    fun `a Run started before Runs had names keeps runs-uuid`() {
        val run = live("Old")
        runtime.removeVariable(run, FOLDER)
        assertEquals(workspace(run), workspace(run))
    }
}
