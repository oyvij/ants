package ants

import org.junit.jupiter.api.Test
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class AnswerRoundTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    private fun round(summary: String, vararg questions: String) =
        """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"$summary","output":{"questions":[${questions.joinToString(",")}]}}}"""

    private fun question(id: String, title: String) =
        """{"id":"$id","title":"$title","ask":"Ask $id?","context":"Settles $id.","recommendation":"Recommend $id.","why":"Because $id."}"""

    /**
     * Starts a Run of `round.bpmn`: Grill the idea → Asked? → Answer the Round → back to Grill the idea, until a Round
     * asks nothing; the Round's timer boundary is [timer] (an hour in the file).
     */
    private fun start(timer: String = "PT1H"): String {
        agentTask(claude, mapOf("grill" to """{"type":"object","properties":{"questions":{"type":"array"}}}"""))
        browser.get("/processes")
        browser.upload("/processes", "file", "round.bpmn", javaClass.getResource("/round.bpmn")!!.readText().replace("PT1H", timer).toByteArray())
        browser.get("/processes/round")
        return browser.post("/processes/round/runs", emptyMap()).path.removePrefix("/runs/")
    }

    private fun row(run: String) = Regex("""<tr>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
        .find(browser.get("/notifications").body)?.groupValues?.get(1)

    private fun entry(run: String): String {
        var path = ""
        eventually { path = Regex("""href="(/notifications/[^"]+)"""").find(assertNotNull(row(run)))!!.groupValues[1] }
        return path
    }

    private fun page(run: String) = browser.get("/runs/$run").body

    private val history get() = home.resolve("answer-history")

    @Test
    fun `a grill loop asks two Rounds, resumes the asker's session with each Round's stamped answers, and ends on an empty one`() {
        claude.session("grill", 1, round("First round.", question("who", "Who exports"), question("scope", "Which invoices")))
        claude.session("grill", 2, round("Second round.", question("format", "Number format")))
        claude.session("grill", 3, round("Nothing left."))
        val run = start()

        val first = entry(run)
        for (cell in listOf("? [questions]", "Answer the Round", "First round.")) assertContains(row(run)!!, cell)
        val body = browser.get(first).body
        for (text in listOf("Who exports", "Ask who?", "Settles who.", "Because who.", ">Recommend who.</textarea>")) assertContains(body, text)
        browser.post("$first/answers", mapOf("answer.who" to "Recommend who.", "answer.scope" to "Only admins."))

        val second = entry(run)
        browser.post("$second/answers", mapOf("answer.format" to "Recommend format."))
        eventually { assertContains(page(run), "State: Completed") }

        val (one, two, three) = claude.launches("grill")
        val session = one.flag("--session-id")
        assertEquals(listOf(session, session), listOf(two.flag("--resume"), three.flag("--resume")))
        assertEquals(
            """{"type":"user","message":{"role":"user","content":"{\"answers\":[{\"id\":\"who\",\"answer\":\"Recommend who.\",\"source\":\"accepted\"},""" +
                """{\"id\":\"scope\",\"answer\":\"Only admins.\",\"source\":\"written\"}]}"}}""",
            two.stdin,
        )
        assertEquals(
            """{"type":"user","message":{"role":"user","content":"{\"answers\":[{\"id\":\"format\",\"answer\":\"Recommend format.\",\"source\":\"accepted\"}]}"}}""",
            three.stdin,
        )
    }

    @Test
    fun `a Round with an unanswered question is refused`() {
        claude.session("grill", 1, round("First round.", question("who", "Who exports"), question("scope", "Which invoices")))
        val run = start()
        val entry = entry(run)
        val refused = browser.post("$entry/answers", mapOf("answer.who" to "Everyone.", "answer.scope" to " "))
        assertContains(refused.body, "Every question needs an answer.")
        assertEquals(entry, entry(run))
        assertEquals(1, claude.launches("grill").size)
    }

    @Test
    fun `the user's answers go to the Answer History, and every Round to the Run log and the Report`() {
        claude.session("grill", 1, round("First round.", question("who", "Who exports"), question("scope", "Which invoices")))
        claude.session("grill", 2, "wait release", round("Nothing left."))
        val run = start()
        browser.post("${entry(run)}/answers", mapOf("answer.who" to "Recommend who.", "answer.scope" to "Only admins."))
        eventually { assertContains(home.resolve("runs/$run/run-log.md").readText(), "Answer the Round · Round 1\n\n- Who exports: Recommend who. (accepted)\n- Which invoices: Only admins. (written)\n") }
        claude.release("release")
        eventually { assertContains(page(run), "State: Completed") }

        val file = history.listDirectoryEntries("*$run*.md").single()
        val written = file.readText()
        for (text in listOf("Grill", run, "Answer the Round", "Ask who?", "Settles who.", "Recommend who.", "Because who.", "Only admins.", "accepted", "written")) {
            assertContains(written, text)
        }
        val index = history.resolve("INDEX.md").readText()
        assertContains(index, "[Who exports](${file.fileName}#who) · Settles who. · Grill · ")
        assertContains(index, "[Which invoices](${file.fileName}#scope) · Settles scope. · Grill · ")

        val report = browser.get("/runs/$run/report").body
        assertContains(report, "Answer the Round · Round 1")
        assertContains(report, "Which invoices: Only admins. (written)")
    }

    @Test
    fun `a Round its timer boundary took away says so, and sends nothing`() {
        claude.session("grill", 1, round("First round.", question("who", "Who exports")))
        val run = start(timer = "PT2S")
        val entry = entry(run)
        eventually { assertContains(page(run), "State: Completed") }
        assertEquals(null, row(run))
        val gone = browser.post("$entry/answers", mapOf("answer.who" to "Everyone."))
        assertContains(gone.body, "Nothing is waiting on you here any more")
        assertFalse(history.exists() && history.listDirectoryEntries("*$run*").isNotEmpty())
        assertEquals(1, claude.launches("grill").size)
    }
}
