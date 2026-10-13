package ants

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    @BeforeEach
    fun setUp() {
        agentTask(claude)
    }

    private val init = """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}"""
    private val usage = """{"m":{"inputTokens":1,"outputTokens":2,"cacheReadInputTokens":3,"cacheCreationInputTokens":4}}"""
    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","modelUsage":$usage,""" +
        """"structured_output":{"outcome":"done","summary":"Drafted a **dry** hello.","output":{"text":"Hello"}}}"""

    private fun start(): String {
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        return browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say *hello*")).path.removePrefix("/runs/")
    }

    @Test
    fun `a finished Run has a Report with the start form, each Task's summary, tokens and Transcript links`() {
        claude.session("draft", init, done)
        val run = start()
        eventually { assertContains(browser.get("/runs/$run").body, """<a href="/runs/$run/report">Report</a>""") }

        val report = browser.get("/runs/$run/report").body
        assertContains(report, Regex("<h1>Agent Task, [^<]+</h1>"))
        assertContains(report, "<p>Agent Task · Completed</p>")
        assertContains(report, "<h2>Start form</h2>")
        assertContains(report, "<p><strong>Prompt</strong></p>\n<p>Say <em>hello</em></p>")
        assertContains(report, "<h2>Tasks · 10 tokens</h2>")
        assertContains(report, """<li><a rel="nofollow" href="/runs/$run/tasks/draft/transcript">draft</a> · done: Drafted a <strong>dry</strong> hello. · 10 tokens</li>""")
    }

    @Test
    fun `cleanup leaves only the Tickets, the Report and the Transcripts`() {
        claude.session("draft", init, "wait go", done)
        val run = start()
        val workspace = workspace(run)
        eventually { assertEquals(1, claude.launches("draft").size) }
        workspace.resolve("tickets/issues").createDirectories().resolve("01-hello.md").writeText("Status: done\n")
        workspace.resolve("repos/app").createDirectories().resolve("README.md").writeText("app\n")
        workspace.resolve("scout").createDirectories().resolve("notes.md").writeText("notes\n")
        claude.release("go")

        eventually { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        eventually { assertEquals(listOf("report.md", "tasks", "tickets"), workspace.listDirectoryEntries().map { it.name }.sorted()) }
        assertEquals("Status: done\n", workspace.resolve("tickets/issues/01-hello.md").readText())
        assertTrue(workspace.resolve("tasks/draft/transcript.jsonl").exists())
        assertFalse("Dismiss" in browser.get("/runs/$run").body)
    }
}
