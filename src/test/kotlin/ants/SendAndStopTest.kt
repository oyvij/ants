package ants

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class SendAndStopTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    @BeforeEach
    fun setUp() {
        home.resolve("settings.yaml").writeText("claude:\n  command: [${claude.command}]\n")
        home.resolve("agents").createDirectories().resolve("writer.md").writeText("Write plainly.\n")
        home.resolve("skills/draft").createDirectories().resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts a text.\n---\nDraft the text.\n")
        home.resolve("skills/draft/result.schema.json").toFile().delete()
    }

    private val init = """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}"""
    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"done","summary":"Drafted.","output":{"text":"Hello"}}}"""

    private fun start(): String {
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        return browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    private fun transcript(run: String) = "/runs/$run/tasks/draft/transcript"

    /** The Transcript page, once it offers Send and Stop. */
    private fun live(run: String): String {
        var page = ""
        eventually { page = browser.get(transcript(run)).body.also { assertContains(it, "<button>Send</button>") } }
        return page
    }

    @Test
    fun `Send delivers a user message into the live session, and it shows in the Transcript and the Run log`() {
        claude.session("draft", init, "read", "wait release", done)
        val run = start()
        live(run)
        val sent = browser.post("${transcript(run)}/send", mapOf("message" to "Keep it short."))
        assertEquals(transcript(run), sent.path)
        eventually { assertContains(browser.get(transcript(run)).body, """<p class="user" id="e2">› Keep it short.</p>""") }
        assertContains(home.resolve("runs/$run/run-log.md").readText(), "Draft · Send\n\nKeep it short.\n")
        claude.release("release")
        eventually { assertContains(browser.get("/runs/$run").body, "State: Completed") }
        assertContains(claude.launches("draft").single().stdin, """{"type":"user","message":{"role":"user","content":"Keep it short."}}""")
    }

    @Test
    fun `a message still unread when a Result arrives keeps the session open, and the next Result counts`() {
        claude.session("draft", init, "wait sent", done.replace("Drafted.", "First draft."), "read", done.replace("Drafted.", "Second draft."))
        val run = start()
        live(run)
        browser.post("${transcript(run)}/send", mapOf("message" to "Shorter, please."))
        claude.release("sent")
        eventually { assertContains(browser.get("/runs/$run").body, "State: Completed") }
        assertContains(browser.get("/runs/$run").body, "Second draft.")
    }

    @Test
    fun `Send after a valid Result is refused with a message`() {
        claude.session("draft", init, done, "wait release")
        val run = start()
        eventually { assertContains(browser.get(transcript(run)).body, "Result: done") }
        val refused = browser.post("${transcript(run)}/send", mapOf("message" to "One more thing."))
        assertContains(refused.body, "Not sent: the Agent's session has ended")
        assertFalse("<button>Send</button>" in refused.body)
        claude.release("release")
        eventually { assertContains(browser.get("/runs/$run").body, "State: Completed") }
        assertFalse("One more thing." in claude.launches("draft").single().stdin)
    }

    @Test
    fun `Stop leaves the Task stopped, not failed, and Continue resumes the same session`() {
        claude.session(
            "draft", 1, init, "read",
            """{"type":"control_response","response":{"subtype":"success","request_id":"r1","response":{"still_queued":[]}}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user for tool use]"}]},"session_id":"${'$'}SESSION"}""",
            """{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"${'$'}SESSION","terminal_reason":"aborted_tools"}""",
            "exit 1",
        )
        claude.session("draft", 2, init, done)
        val run = start()
        live(run)
        assertEquals(transcript(run), browser.post("${transcript(run)}/stop", emptyMap()).path)
        var entry = ""
        eventually {
            val row = assertNotNull(Regex("""<tr>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
                .find(browser.get("/notifications").body)).groupValues[1]
            entry = Regex("""href="(/notifications/[^"]+)"""").find(row)!!.groupValues[1]
        }
        val page = browser.get(entry).body
        assertContains(page, "■ [stopped] Draft")
        assertContains(page, "Stopped by the user.")
        assertContains(browser.get(transcript(run)).body, "[stopped] Stopped by the user.")
        assertContains(claude.launches("draft")[0].stdin, """{"type":"control_request","request_id":""")
        assertContains(claude.launches("draft")[0].stdin, """"request":{"subtype":"interrupt"}}""")

        browser.post(entry, mapOf("action" to "continue", "instruction" to "Carry on, but shorter."))
        eventually { assertContains(browser.get("/runs/$run").body, "State: Completed") }
        val (first, second) = claude.launches("draft")
        assertEquals(first.flag("--session-id"), second.flag("--resume"))
    }

    /** Stops the Run's live session, which then plays [after], and returns the Run's row on the Notification list. */
    private fun stopThen(vararg after: String): String {
        claude.session("draft", init, "read", *after)
        val run = start()
        live(run)
        browser.post("${transcript(run)}/stop", emptyMap())
        var row = ""
        eventually {
            row = assertNotNull(Regex("""<tr>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
                .find(browser.get("/notifications").body)).groupValues[1]
        }
        return row
    }

    @Test
    fun `after Stop only error_during_execution is stopped, any other result without a Result fails`() {
        val row = stopThen("""{"type":"result","subtype":"error_max_turns","is_error":true,"session_id":"${'$'}SESSION"}""", "exit 1")
        assertContains(row, "✕ [failed]")
        assertContains(row, "claude ended without a Result: error_max_turns")
    }

    @Test
    fun `a CLI that exits with no result after Stop is stopped`() {
        assertContains(stopThen("exit 1"), "■ [stopped]")
    }
}
