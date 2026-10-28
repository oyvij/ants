package ants

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
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
    private fun start(timer: String = "PT1H", process: String = "round", cap: Int = 10, quiz: String? = """{"type":"object","properties":{"questions":{"type":"array"}}}""", assignment: String? = null): String {
        agentTask(
            claude,
            mapOf(
                "quiz" to quiz,
                "stand-in" to """{"type":"object","properties":{"answers":{"type":"array"}}}""",
            ),
        )
        browser.get("/processes")
        val bpmn = javaClass.getResource("/$process.bpmn")!!.readText().replace("PT1H", timer).replace("runs.grill >= 10", "runs.grill >= $cap")
            .let { if (assignment == null) it else it.replace("""name="ants.skills" value="quiz"""", """name="ants.assignment" value="$assignment"""") }
        browser.upload("/processes", "file", "$process.bpmn", bpmn.toByteArray())
        browser.get("/processes/$process")
        return browser.post("/processes/$process/runs", emptyMap()).path.removePrefix("/runs/")
    }

    /** The answer-round Skill's Result: [answers] as `{id, answer, why}`. */
    private fun answered(vararg answers: String) =
        """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Answered.","output":{"answers":[${answers.joinToString(",")}]}}}"""

    private fun historyOf(run: String) = if (history.exists()) history.listDirectoryEntries("*$run*") else emptyList()

    private fun row(run: String) = Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
        .find(browser.get("/notifications").body)?.groupValues?.get(1)

    private fun entry(run: String, seconds: Long = 10): String {
        var path = ""
        eventually(seconds) { path = Regex("""href="(/notifications/[^"]+)"""").find(assertNotNull(row(run)))!!.groupValues[1] }
        return path
    }

    private fun page(run: String) = browser.get("/runs/$run").body

    private val history get() = home.resolve("answer-history")

    @Test
    fun `a grill loop asks two Rounds, resumes the asker's session with each Round's stamped answers, and ends on an empty one`() {
        claude.session("quiz", 1, round("First round.", question("who", "Who exports"), question("scope", "Which invoices")))
        claude.session("quiz", 2, round("Second round.", question("format", "Number format")))
        claude.session("quiz", 3, round("Nothing left."))
        val run = start()

        val first = entry(run)
        for (cell in listOf("<i>?</i>[questions]", "Answer the Round", "First round.")) assertContains(row(run)!!, cell)
        val body = browser.get(first).body
        for (text in listOf("Who exports", "Ask who?", "Settles who.", "Because who.", ">Recommend who.</textarea>")) assertContains(body, text)
        assertContains(browser.post("$first/answers", mapOf("answer.who" to "Recommend who.", "answer.scope" to "Only admins.")).body, "<b>Round 1 sent</b>")

        val second = entry(run)
        browser.post("$second/answers", mapOf("answer.format" to "Recommend format."))
        eventually { assertContains(page(run), """id="state">Completed<""") }

        val (one, two, three) = claude.launches("quiz")
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
    fun `a Task that asks Rounds gets the Round protocol in its hand-off, and questions added to a Result schema without them`() {
        claude.session("quiz", 1, round("First round.", question("who", "Who exports")))
        val run = start(quiz = """{"type":"object","properties":{"note":{"type":"string"}},"additionalProperties":false}""")
        val body = browser.get(entry(run)).body
        for (text in listOf("Who exports", "Ask who?", ">Recommend who.</textarea>")) assertContains(body, text)

        val launch = claude.launches("quiz").single()
        for (text in listOf(
            "A Round is one Result:", "`id`", "`title`", "`ask`", "`context`", "`recommendation`", "`why`", "on a phone",
            "{answers: [{id, answer, source}]}", "Never ask a settled question again unless a later answer reopens it",
        )) assertContains(launch.stdin, text)
        val argv = launch.argv
        val output = (JsonMapper.shared().readValue(argv[argv.indexOf("--json-schema") + 1], Map::class.java)["properties"] as Map<*, *>)["output"] as Map<*, *>
        assertEquals(setOf("note", "questions"), (output["properties"] as Map<*, *>).keys)
        assertEquals(listOf("questions"), output["required"])
        val item = ((output["properties"] as Map<*, *>)["questions"] as Map<*, *>)["items"] as Map<*, *>
        assertEquals(listOf("id", "title", "ask", "context", "recommendation", "why"), item["required"])
    }

    @Test
    fun `a Task with an Assignment that asks Rounds gets the ranking, the Round protocol and questions in its schema`() {
        claude.session("grill", 1, round("First round.", question("who", "Who exports")))
        val run = start(assignment = "Grill the idea.")
        assertContains(browser.get(entry(run)).body, "Who exports")

        val launch = claude.launches("grill").single()
        for (text in listOf("Grill the idea.", RANKING, "A Round is one Result:")) assertContains(launch.stdin, text)
        val argv = launch.argv
        val output = (JsonMapper.shared().readValue(argv[argv.indexOf("--json-schema") + 1], Map::class.java)["properties"] as Map<*, *>)["output"] as Map<*, *>
        assertEquals(listOf("questions"), output["required"])
        assertEquals("object", output["type"]) // a valid object schema though the Task declares none
    }

    @Test
    fun `the Answer page shows the question's Markdown, backticks as code and lists as lists, its HTML escaped, and the recommendation to send back as written`() {
        val ticked = """{"id":"run","title":"How it runs","ask":"Run `hello.js` or a `bin`?","context":"`<b>` stays text.\n\n- `node hello.js`\n- a `bin` entry",""" +
            """"recommendation":"Run `node hello.js`.","why":"One `file`."}"""
        claude.session("quiz", 1, round("Hello CLI: `hello.js`", ticked))
        val body = browser.get(entry(start())).body
        for (text in listOf(
            "Hello CLI: <code>hello.js</code></h1>", "Run <code>hello.js</code> or a <code>bin</code>?", "<code>&lt;b&gt;</code> stays text.", "<li><code>node hello.js</code></li>",
            "Run <code>node hello.js</code>.</p>", "One <code>file</code>.", ">Run `node hello.js`.</textarea>",
        )) assertContains(body, text)
    }

    @Test
    fun `a Round with an unanswered question is refused`() {
        claude.session("quiz", 1, round("First round.", question("who", "Who exports"), question("scope", "Which invoices")))
        val run = start()
        val entry = entry(run)
        val refused = browser.post("$entry/answers", mapOf("answer.who" to "Everyone.", "answer.scope" to " "))
        assertContains(refused.body, "Every question needs an answer.")
        assertEquals(entry, entry(run))
        assertEquals(1, claude.launches("quiz").size)
    }

    @Test
    fun `the user's answers go to the Answer History, and every Round to the Run log and the Report`() {
        claude.session("quiz", 1, round("First round.", question("who", "Who exports"), question("scope", "Which invoices")))
        claude.session("quiz", 2, "wait release", round("Nothing left."))
        val run = start()
        browser.post("${entry(run)}/answers", mapOf("answer.who" to "Recommend who.", "answer.scope" to "Only admins."))
        eventually { assertContains(workspace(run).resolve("run-log.md").readText(), "Answer the Round · Round 1\n\n- Who exports: Recommend who. (accepted)\n- Which invoices: Only admins. (written)\n") }
        claude.release("release")
        eventually { assertContains(page(run), """id="state">Completed<""") }

        val file = history.listDirectoryEntries("*$run*.md").single()
        val written = file.readText()
        for (text in listOf("Grill", run, "Answer the Round", "Ask who?", "Settles who.", "Recommend who.", "Because who.", "Only admins.", "accepted", "written")) {
            assertContains(written, text)
        }
        val index = history.resolve("INDEX.md").readText()
        assertContains(index, "[Who exports](${file.fileName}#who) · Settles who. · Grill · ")
        assertContains(index, "[Which invoices](${file.fileName}#scope) · Settles scope. · Grill · ")

        val report = browser.report(run)
        assertContains(report, "Answer the Round · Round 1")
        assertContains(report, "Which invoices: Only admins. (written)")
    }

    @Test
    fun `a Round its timer boundary took away says so, and sends nothing`() {
        claude.session("quiz", 1, round("First round.", question("who", "Who exports")))
        val run = start(timer = "PT2S")
        val entry = entry(run)
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertEquals(null, row(run))
        val gone = browser.post("$entry/answers", mapOf("answer.who" to "Everyone."))
        assertContains(gone.body, "Nothing is waiting on you here any more")
        assertFalse(history.exists() && history.listDirectoryEntries("*$run*").isNotEmpty())
        assertEquals(1, claude.launches("quiz").size)
    }

    @Test
    fun `an answer-round Agent answers a Round, its answers stamped agent, resumes the asker and keeps them out of the Answer History`() {
        claude.session("quiz", 1, round("First round.", question("who", "Who exports"), question("scope", "Which invoices")))
        // It leaves `scope` unanswered, so that one gets its recommendation.
        claude.session("stand-in", answered("""{"id":"who","answer":"Admins.","why":"The Vault says so."}"""))
        claude.session("quiz", 2, round("Nothing left."))
        val run = start(process = "proxy")
        eventually { assertContains(page(run), """id="state">Completed<""") }

        val prompt = claude.launches("stand-in").single().stdin
        assertContains(prompt, "The Answer History is ${home.resolve("answer-history")}.")
        assertContains(prompt, "Ask who?")
        val (one, two) = claude.launches("quiz")
        assertEquals(one.flag("--session-id"), two.flag("--resume"))
        assertEquals(
            """{"type":"user","message":{"role":"user","content":"{\"answers\":[{\"id\":\"who\",\"answer\":\"Admins.\",\"source\":\"agent\"},""" +
                """{\"id\":\"scope\",\"answer\":\"Recommend scope.\",\"source\":\"recommended\"}]}"}}""",
            two.stdin,
        )
        assertEquals(emptyList(), historyOf(run))
        val report = browser.report(run)
        for (text in listOf("Answer for me · Round 1", "Who exports: Admins. (agent)", "Which invoices: Recommend scope. (recommended)")) assertContains(report, text)
    }

    @Test
    fun `a timer boundary on an unanswered Round falls back to Accept recommendations, which resumes the asker`() {
        claude.session("quiz", 1, round("First round.", question("who", "Who exports"), question("scope", "Which invoices")))
        claude.session("quiz", 2, round("Nothing left."))
        val run = start(timer = "PT2S", process = "proposed", cap = 1)
        val entry = entry(run)
        eventually { assertContains(page(run), """id="state">Completed<""") }

        val (one, two) = claude.launches("quiz")
        assertEquals(one.flag("--session-id"), two.flag("--resume"))
        assertEquals(
            """{"type":"user","message":{"role":"user","content":"{\"answers\":[{\"id\":\"who\",\"answer\":\"Recommend who.\",\"source\":\"recommended\"},""" +
                """{\"id\":\"scope\",\"answer\":\"Recommend scope.\",\"source\":\"recommended\"}]}"}}""",
            two.stdin,
        )
        assertContains(browser.post("$entry/answers", mapOf("answer.who" to "Everyone.", "answer.scope" to "All.")).body, "Nothing is waiting on you here any more")
        assertEquals(emptyList(), historyOf(run))
        val report = browser.report(run)
        for (text in listOf("Accept recommendations · Round 1", "Who exports: Recommend who. (recommended)")) assertContains(report, text)
    }

    @Test
    fun `the cap hands a loop Accept recommendations paces to the user at Round 10, and only the user's Round enters the Answer History`() {
        claude.session("quiz", round("Another round.", question("who", "Who exports")))
        claude.session("quiz", 11, round("Nothing left."))
        val run = start(process = "proposed")

        // Ten launches and nine Accept recommendations, each waiting on its worker's next poll: about 0.4 s a Round, and
        // twice that on a busy machine (9.4 s under full CPU load), so the wait has room for all ten.
        val entry = entry(run, seconds = 30)
        assertEquals(10, claude.launches("quiz").size)
        assertContains(browser.get(entry).body, "Round 10")
        browser.post("$entry/answers", mapOf("answer.who" to "Only admins."))
        eventually { assertContains(page(run), """id="state">Completed<""") }

        assertEquals(11, claude.launches("quiz").size)
        val file = historyOf(run).single()
        assertContains(file.fileName.toString(), "-10.md")
        assertContains(file.readText(), "Only admins.")
        assertFalse("Recommend who." in file.readText().substringAfter("**Answer**"))

        val report = browser.report(run)
        val (mine, others) = report.substringAfter("<h2>Rounds</h2>").split("<h2>Answers you didn't give</h2>")
        for (text in listOf("Answer the Round · Round 10", "Who exports: Only admins. (written)")) assertContains(mine, text)
        assertFalse("recommended" in mine)
        for (n in 1..9) assertContains(others, "Accept recommendations · Round $n<")
        assertContains(others, "Who exports: Recommend who. (recommended)")
        assertFalse("Only admins." in others)
    }
}
