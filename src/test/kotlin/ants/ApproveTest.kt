package ants

import org.junit.jupiter.api.Test
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class ApproveTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    private fun result(result: String) = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":$result}"""
    private val drafted = result("""{"outcome":"done","summary":"Drafted.","output":{"notes":"notes.md"}}""")
    private val polished = result("""{"outcome":"done","summary":"Polished.","output":{}}""")

    /** Starts a Run of `approve.bpmn`: Draft → Check the draft (an Approve showing `notes.md`) → Polish, or back to Draft. */
    private fun start(extra: String = ""): String {
        agentTask(claude, mapOf("draft" to """{"type":"object","properties":{"notes":{"type":"string","format":"path"}}}""", "polish" to null), extra = extra)
        browser.import("approve.bpmn")
        browser.get("/processes/approve")
        val run = browser.post("/processes/approve/runs", emptyMap()).path.removePrefix("/runs/")
        workspace(run).resolve("notes.md").writeText("# Notes\n\nThe *draft* reads well.\n")
        return run
    }

    /** The Run's row on the Notification list, or null. */
    private fun row(run: String) = Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
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
    fun `an Approve appears as ✓ approve and its page renders the shown file as markdown`() {
        claude.session("draft", drafted)
        val run = start()
        val entry = entry(run)
        for (cell in listOf("<i>✓</i>[approve]", "Check the draft", "Approve the notes")) assertContains(row(run)!!, cell)
        val body = browser.get(entry).body
        assertContains(body, """<h1 data-flash="waiting">Check the draft <span class="pill p-waiting">Waiting on you</span> <span class="tag t-approve"><i>✓</i>[approve]</span></h1>""")
        assertContains(body, """<div class="sub">Approve the notes</div>""")
        assertContains(body, """<div class="muted small mono">notes.md</div><h1>Notes</h1>""")
        assertContains(body, "The <em>draft</em> reads well.")
        assertContains(body, "Required to send back. The Agent's session resumes with it.")
        assertContains(body, """<div class="composer">""")
    }

    @Test
    fun `Approve completes with approved true and the comment`() {
        claude.session("draft", drafted)
        claude.session("polish", polished)
        val run = start()
        val acted = browser.post(entry(run), mapOf("action" to "approve", "comment" to "Ship it."))
        assertEquals(null, row(run))
        assertEquals("/notifications", acted.path)
        assertContains(acted.body, "<b>Approved</b><span>Check the draft in ")
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertContains(claude.launches("polish").single().stdin, """{\"verdict\":{\"approved\":true,\"comment\":\"Ship it.\"}}""")
        assertEquals(1, claude.launches("draft").size)
    }

    @Test
    fun `Send back without a comment is refused, and with one it resumes the named Task's session`() {
        claude.session("draft", drafted)
        claude.session("draft", 2, "wait release", drafted)
        claude.session("polish", polished)
        val run = start()
        val entry = entry(run)

        val refused = browser.post(entry, mapOf("action" to "send-back", "comment" to " "))
        assertContains(refused.body, "Send back needs a comment.")
        assertEquals(entry, entry(run))

        browser.post(entry, mapOf("action" to "send-back", "comment" to "Shorter, please."))
        assertContains(runLog(run), "Check the draft · Send back\n\nShorter, please.\n")
        claude.release("release")
        val again = entry(run) // the revised draft waits for approval again
        assertNotEquals(entry, again)
        browser.post(again, mapOf("action" to "approve", "comment" to ""))
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val (first, second) = claude.launches("draft")
        assertEquals(first.flag("--session-id"), second.flag("--resume"))
        assertEquals("""{"type":"user","message":{"role":"user","content":"Shorter, please."}}""", second.stdin)
        assertContains(claude.launches("polish").single().stdin, """{\"verdict\":{\"approved\":true,\"comment\":\"\"}}""")
    }

    @Test
    fun `with a Profile that has instructions, a Send back's fix launches with the first launch's --settings`() {
        claude.session("draft", drafted)
        claude.session("polish", polished)
        instruct("work", "Keep it short.\n")
        val run = start("profiles:\n  work:\n    vault: notes\n")
        browser.post(entry(run), mapOf("action" to "send-back", "comment" to "Shorter, please."))
        eventually { assertEquals(2, claude.launches("draft").size) }
        browser.post(entry(run), mapOf("action" to "approve", "comment" to ""))
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val (first, fix) = claude.launches("draft")
        assertEquals(excludingAbove(workspace(run).toRealPath()), first.flag("--settings"))
        assertEquals(first.flag("--settings"), fix.flag("--settings"))
    }
}
