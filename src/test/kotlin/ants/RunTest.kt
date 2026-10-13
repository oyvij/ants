package ants

import org.junit.jupiter.api.Test
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
    fun `Start creates a Run, whose workspace is gone but for its Report once it ends`() {
        home.resolve("settings.yaml").writeText(vault)
        browser.import("start-end.bpmn")
        val run = start("start-end")
        val id = run.path.removePrefix("/runs/")
        assertContains(run.body, "Completed")
        assertContains(runs("Ended"), """<a href="/runs/$id">Start to end, """)
        assertEquals(listOf("report.md"), workspace(id).toFile().list()?.toList())
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
        assertTrue(workspace(run.path.removePrefix("/runs/")).isDirectory())
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
        for (id in ids) {
            val row = """<tr class="click" data-href="/runs/$id"><td><b><a href="/runs/$id">Loop then wait, [^<]+</a></b></td><td class="muted">Loop then wait</td>\s*""" +
                """<td><span class="pill p-waiting">Waiting on you</span> <span class="tag t-approve"><i>✓</i>\[approve]</span> <span class="small">b</span>"""
            eventually { assertContains(runs("Live"), Regex(row)) }
            assertTrue(workspace(id).isDirectory())
        }
    }

    /** The Runs list's [section], Coming up, Live or Ended, from its heading to the next. */
    private fun runs(section: String) = browser.get("/runs").body.substringAfter("<h2>$section</h2>").substringBefore("<h2>").substringBefore("</main>")

    @Test
    fun `the Runs list says how many Agent Tasks run of the cap, and Now shows a running Agent Task's live line`() {
        agentTask(claude)
        claude.session("draft", "wait never")
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        val id = browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
        val line = """data-href="/runs/$id">.*?<b class="small">Draft</b><div class="small"><span class="spin">✻</span> <span class="shimmer">Starting…</span> """ +
            """<span class="muted num">\(<span data-since="\d+">\d+s</span> · ↓ 0 tokens\)</span>"""
        eventually { assertContains(runs("Live"), Regex(line, RegexOption.DOT_MATCHES_ALL)) }
        assertContains(browser.get("/runs").body, """<div class="sub">1 of 3 Agent Tasks running.</div>""")
    }

    @Test
    fun `an ended Run's row offers its Report, and a cancelled one's Dismiss`() {
        browser.import("start-end.bpmn")
        val done = start("start-end").path.removePrefix("/runs/")
        importLoopThenWait()
        val cancelled = start("loop-then-wait").path.removePrefix("/runs/")
        browser.post("/runs/$cancelled/cancel", mapOf())
        val ended = runs("Ended")
        assertContains(ended, Regex("""data-href="/runs/$done">.*?<span class="pill p-done">Done · \d\d:\d\d</span>.*?<a href="/runs/$done/report">Report</a>""", RegexOption.DOT_MATCHES_ALL))
        assertContains(ended, Regex("""data-href="/runs/$cancelled">.*?<span class="pill p-failed">Cancelled · \d\d:\d\d</span>.*?<form method="post" action="/runs/$cancelled/dismiss">""", RegexOption.DOT_MATCHES_ALL))
        assertFalse("/runs/$done\"" in runs("Live"))
    }
}
