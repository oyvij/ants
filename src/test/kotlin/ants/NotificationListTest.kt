package ants

import org.junit.jupiter.api.Test
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NotificationListTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    /**
     * Starts a Run of [process] while the Skill [skill] is there, as the pre-Run check needs, and deletes it before the
     * Run's Tasks launch.
     */
    private fun startWithout(skill: String, process: String = "agent-task"): String {
        settings(claude, "agent-tasks: 0\n") // nothing launches yet
        browser.import("$process.bpmn")
        browser.get("/processes/$process")
        val run = browser.post("/processes/$process/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
        home.resolve("skills/$skill").toFile().deleteRecursively()
        settings(claude)
        return run
    }

    private fun setUp() = agentTask(claude)

    private fun result(result: String) = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":$result}"""
    private val stuck = result("""{"outcome":"stuck","summary":"No tone given.","needs":"Which tone, dry or warm?"}""")
    private val done = result("""{"outcome":"done","summary":"Drafted.","output":{"text":"Hello"}}""")

    private fun start(): String {
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        return browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    /** The Run's row on the Notification list, or null. */
    private fun row(run: String) = Regex("""<tr>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
        .find(browser.get("/notifications").body)?.groupValues?.get(1)

    /** The path of the Run's entry, once it is on the Notification list. */
    private fun entry(run: String): String {
        var path = ""
        eventually { path = Regex("""href="(/notifications/[^"]+)"""").find(assertNotNull(row(run)))!!.groupValues[1] }
        return path
    }

    private fun page(run: String) = browser.get("/runs/$run").body

    private fun runLog(run: String) = workspace(run).resolve("run-log.md").readText()

    @Test
    fun `a stuck Agent Task appears on the Notification list as ⚠ stuck with its summary`() {
        setUp()
        claude.session("draft", stuck)
        val run = start()
        entry(run)
        val row = row(run)!!
        for (cell in listOf("⚠ [stuck]", "Agent Task", "Draft", "No tone given.", "<td>0 min</td>")) assertContains(row, cell)
        val page = browser.get(entry(run)).body
        assertContains(page, """<h1>Draft <span class="pill p-stuck">Stuck</span> <span class="tag t-stuck"><i>⚠</i>Stuck</span></h1>""")
        assertContains(page, """<div class="card"><h3>What it needs</h3><p>Which tone, dry or warm?</p>""")
        assertContains(page, """<summary class="small">The session so far</summary>""")
        assertContains(page, """href="/runs/$run/tasks/draft/transcript"""")
        assertContains(page, "Required to continue the session. Optional when starting fresh.")
        assertContains(page, """<div class="composer">""")
        assertFalse("Cancel the Run" in page) // only a [failed] entry offers it
    }

    @Test
    fun `Continue resumes the same session id with the instruction, and can't be sent empty`() {
        setUp()
        claude.session("draft", stuck)
        claude.session("draft", 2, "wait release", done)
        val run = start()
        val entry = entry(run)

        val refused = browser.post(entry, mapOf("action" to "continue", "instruction" to " "))
        assertContains(refused.body, "Continue the session needs an instruction")
        assertEquals(entry, entry(run))

        val acted = browser.post(entry, mapOf("action" to "continue", "instruction" to "Dry, please."))
        assertEquals(null, row(run)) // gone the moment the user acts
        assertEquals("/notifications", acted.path) // nothing else of this Run is waiting
        assertContains(runLog(run), "Draft · Continue the session\n\nDry, please.\n")
        claude.release("release")
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val (first, second) = claude.launches("draft")
        assertEquals(first.flag("--session-id"), second.flag("--resume"))
        assertEquals(null, second.flag("--session-id"))
        assertEquals("""{"type":"user","message":{"role":"user","content":"Dry, please."}}""", second.stdin)
    }

    @Test
    fun `Start fresh launches a new session id with the instruction under From the user`() {
        setUp()
        claude.session("draft", stuck)
        claude.session("draft", 2, "wait release", done)
        val run = start()
        browser.post(entry(run), mapOf("action" to "fresh", "instruction" to "Warm, please."))
        assertContains(runLog(run), "Draft · Start fresh\n\nWarm, please.\n")
        claude.release("release")
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val (first, second) = claude.launches("draft")
        assertNotEquals(first.flag("--session-id"), assertNotNull(second.flag("--session-id")))
        assertEquals(null, second.flag("--resume"))
        assertContains(second.stdin, """Follow the Skill in ${home.resolve("skills/draft/SKILL.md")}""")
        assertContains(second.stdin, """\n\nFrom the user:\nWarm, please."}}""")
    }

    @Test
    fun `Start fresh needs no instruction`() {
        setUp()
        claude.session("draft", stuck)
        claude.session("draft", 2, done)
        val run = start()
        browser.post(entry(run), mapOf("action" to "fresh", "instruction" to ""))
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertFalse("From the user" in claude.launches("draft")[1].stdin)
    }

    @Test
    fun `Next up lands on a new entry from the same Run`() {
        setUp()
        claude.session("draft", stuck)
        claude.session("draft", 2, result("""{"outcome":"stuck","summary":"Still no tone.","needs":"Dry or warm?"}"""))
        val run = start()
        val first = entry(run)
        val next = browser.post(first, mapOf("action" to "continue", "instruction" to "Pick one."))
        assertNotEquals(first, next.path)
        assertContains(next.body, "Dry or warm?")
    }

    @Test
    fun `the nav and tab-title count follow the Notification list live`() {
        setUp()
        claude.session("draft", stuck)
        claude.session("draft", 2, done)
        assertContains(browser.get("/notifications").body, """<script src="/shell.js"""")
        val counts = browser.events("", "count")
        val before = counts.next().toInt()
        val run = start()
        assertEquals(before + 1, counts.next().toInt())
        browser.post(entry(run), mapOf("action" to "continue", "instruction" to "Dry."))
        assertEquals(before, counts.next().toInt())
    }

    /** The page of the Run's first entry, quotes unescaped. */
    private fun entryPage(run: String) = browser.get(entry(run)).body.replace("&#34;", "\"").replace("&#39;", "'")

    @Test
    fun `a crashing CLI gives ✕ failed with its last stderr lines`() {
        setUp()
        claude.session("draft", *Array(25) { "stderr line ${it + 1}" }, "exit 3")
        val run = start()
        entry(run)
        for (cell in listOf("✕ [failed]", "claude exited with code 3")) assertContains(row(run)!!, cell)
        val page = entryPage(run)
        assertContains(page, (6..25).joinToString("\n") { "line $it" })
        assertFalse("line 5\n" in page)
    }

    @Test
    fun `a second invalid Result gives ✕ failed showing the invalid Result`() {
        setUp()
        val invalid = result("""{"outcome":"done","output":{"text":"Hello"}}""")
        claude.session("draft", invalid, "read", invalid)
        val run = start()
        entry(run)
        assertContains(row(run)!!, "The Result is invalid: ")
        assertContains(entryPage(run), """{"outcome":"done","output":{"text":"Hello"}}""")
    }

    @Test
    fun `a lost login fails as Ants' Claude login is missing or expired`() {
        setUp()
        // As the real claude 2.1.295 answers on an empty CLAUDE_CONFIG_DIR.
        claude.session(
            "draft",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Not logged in · Please run /login"}]},"error":"authentication_failed","is_api_error_message":true}""",
            """{"type":"result","subtype":"success","is_error":true,"result":"Not logged in · Please run /login","session_id":"${'$'}SESSION"}""",
        )
        val run = start()
        entry(run)
        assertContains(row(run)!!, "✕ [failed]")
        assertContains(row(run)!!.replace("&#39;", "'"), "Ants' Claude login is missing or expired. Log in again (Settings)")
    }

    @Test
    fun `an API key in use fails as a lost login, and the launch is killed`() {
        setUp()
        claude.session("draft", """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"ANTHROPIC_API_KEY"}""", "wait never", done)
        val run = start()
        entry(run)
        assertContains(row(run)!!.replace("&#39;", "'"), "Ants' Claude login is missing or expired. Log in again (Settings)")
        assertContains(entryPage(run), "apiKeySource: ANTHROPIC_API_KEY")
        eventually { assert(claude.launches("draft").single().killed) }
    }

    @Test
    fun `a missing Skill fails before launching`() {
        setUp()
        val run = startWithout("draft")
        entry(run)
        assertContains(row(run)!!, "✕ [failed]")
        assertContains(row(run)!!, "No Skill named draft")
        assertEquals(emptyList(), claude.launches("draft"))
    }

    @Test
    fun `a launch that never started writes no divider and keeps no session, so Continue starts afresh once the cause is fixed`() {
        setUp()
        val run = startWithout("draft")
        val entry = entry(run)
        assertFalse(workspace(run).resolve("tasks/draft/transcript.jsonl").exists())

        setUp()
        claude.session("draft", done)
        browser.get(entry)
        browser.post(entry, mapOf("action" to "continue", "instruction" to "Dry."))
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val launch = claude.launches("draft").single()
        assertNotNull(launch.flag("--session-id"))
        assertEquals(null, launch.flag("--resume"))
        assertContains(launch.stdin, "From the user:\\nDry.")
    }

    @Test
    fun `a failed resume gives ✕ failed with claude's reason`() {
        setUp()
        claude.session("draft", stuck)
        // As the real claude 2.1.295 answers --resume of a session it doesn't have.
        claude.session(
            "draft", 2,
            "stderr No conversation found with session ID: ${'$'}SESSION",
            """{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":0,"session_id":"${'$'}SESSION","errors":["No conversation found with session ID: ${'$'}SESSION"]}""",
            "exit 1",
        )
        val run = start()
        browser.post(entry(run), mapOf("action" to "continue", "instruction" to "Dry."))
        val session = claude.launches("draft").first().flag("--session-id")
        eventually { assertContains(assertNotNull(row(run)), "✕ [failed]") }
        assertContains(row(run)!!, "claude ended without a Result: error_during_execution No conversation found with session ID: $session")
    }

    @Test
    fun `Cancel the Run ends the Run, kills its live Agent Tasks and keeps its Run workspace until the user dismisses it`() {
        // Deployed with the Skill check, which is then deleted, so the Task check fails at launch.
        agentTask(claude, mapOf("draft" to null, "check" to null))
        claude.session("draft", "wait never", done)
        val run = startWithout("check", "parallel-tasks")
        eventually { assertEquals(1, claude.launches("draft").size) }
        val entry = entry(run) // Check fails: its Skill is missing
        assertContains(browser.get(entry).body, "Cancel the Run")

        val cancelled = browser.post(entry, mapOf("action" to "cancel", "instruction" to ""))
        assertEquals("/runs/$run", cancelled.path)
        assertContains(cancelled.body, """id="state">Cancelled<""")
        assertEquals(null, row(run))
        eventually { assertTrue(claude.launches("draft").single().killed) }
        val transcript = workspace(run).resolve("tasks/draft/transcript.jsonl")
        eventually { assertContains(transcript.readText(), "Cancelled with the Run.") }
        assertFalse("[failed]" in transcript.readText())
        assertTrue(workspace(run).isDirectory())
        assertContains(runLog(run), "Check · Cancel the Run")
        assertContains(browser.get("/runs/$run/report").body, " · Cancelled</p>") // the Report is written as the Run ends
        workspace(run).resolve("tickets").createDirectories().resolve("spec.md").writeText("# Hello\n")

        val dismissed = browser.post("/runs/$run/dismiss", emptyMap())
        assertEquals("/runs/$run", dismissed.path)
        assertFalse(workspace(run).resolve("run-log.md").exists())
        assertTrue(workspace(run).resolve("tasks/draft/transcript.jsonl").exists()) // Transcripts outlive the Run
        assertTrue(workspace(run).resolve("tickets/spec.md").exists())
        assertContains(browser.get(dismissed.path).body, """<a href="/runs/$run/report">Report</a>""")
        assertFalse("Dismiss" in dismissed.body)
    }

    private fun BlockingQueue<String>.next() = assertNotNull(poll(10, SECONDS), "no count within 10 seconds")
}
