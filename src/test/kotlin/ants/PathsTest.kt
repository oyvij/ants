package ants

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Build and ship's Answered as me, Take the recommendations and Hands-off until review, which differ from Human in the loop in who answers the
 * grilling Rounds, run end to end against fake Agents, bare remotes and a temporary Vault.
 */
class PathsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()
    private val api = repo("api")
    private val web = repo("web")

    private fun result(output: String) =
        """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Done.","output":$output}}"""

    private val question = """{"id":"who","title":"Who exports","ask":"Who exports?","context":"Settles who.","recommendation":"Admins.","why":"Because."}"""

    /** A Round asking [question], naming web as a repo the work needs. */
    private val round = result("""{"questions":[$question],"repos":["web"]}""")

    private fun row(run: String) = Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
        .find(browser.get("/notifications").body)?.groupValues?.get(1)

    /** Every tag the Run's Notification entries have shown while a test waited. */
    private val waited = mutableListOf<String>()

    /**
     * The Run's entry on the Notification list, once it is the one tagged [tag]. An entry tagged otherwise, other than
     * the one just answered, is a wait the test didn't expect, and fails at once, naming it.
     */
    private fun entry(run: String, tag: String, seconds: Long = 20): String {
        var path = ""
        eventually(seconds) {
            val row = assertNotNull(row(run))
            Regex("""\[[a-z-]+]""").find(row)?.value?.let {
                check(it in tag || it == waited.lastOrNull()) { "The Run waits at $it, after ${waited.ifEmpty { "nothing" }}, where $tag was expected: $row" }
                if (waited.lastOrNull() != it) waited += it
            }
            assertContains(row, tag)
            path = Regex("""href="(/notifications/[^"]+)"""").find(row)!!.groupValues[1]
        }
        return path
    }

    /** Sessions for everything after the grilling: one Ticket implemented, a Story set over it, the pull request, and Notes proposed, then written. */
    private fun afterTheGrilling() {
        claude.session("scout", "write scout/brief.md Hello.\\n", result("""{"repos":["api"],"branch":"add-hello","brief":"scout/brief.md"}"""))
        claude.session("tospec", "write tickets/spec.md # Hello\\n\\nSay hello.\\n", result("""{"spec":"tickets/spec.md"}"""))
        claude.session("totickets", "write tickets/issues/01-one.md # One\\n\\nStatus: ready-for-agent\\n\\nBlocked by: none\\n", result("""{"tickets":["tickets/issues/01-one.md"]}"""))
        claude.session(
            "implement",
            "sh cd repos/api && echo 01 > 01.txt && git add 01.txt && git -c user.name=Ants -c user.email=ants@example.com -c commit.gpgsign=false commit -qm 'Add 01.txt'",
            result("""{"review":"Reviewed the Ticket."}"""),
        )
        claude.session(
            "walkthrough",
            """write walkthrough/stories.json {"protocolVersion":1,"title":"One","ranges":[{"repo":"api","base":"origin/main","head":"HEAD"}],""" +
                """"stories":[{"id":"one","name":"One","premise":"The Ticket.","steps":[{"id":"file","name":"The file","claim":"It holds 01.","why":"Ticket 01.",""" +
                """"site":{"file":"repos/api/01.txt","side":"new","kind":"changed","from":1,"to":1}}]}]}""",
            result("""{"stories":"walkthrough/stories.json"}"""),
        )
        claude.session("pr", result("""{"pullRequests":[{"repo":"api","url":"https://github.com/oyvij/api/pull/9"}]}"""))
        claude.session("update-knowledge", 1, "write vault/proposal.md One Note.\\n", result("""{"proposal":"vault/proposal.md","written":false}"""))
        claude.session("update-knowledge", 2, result("""{"written":true}"""))
    }

    private fun start(path: String): String {
        settings(claude, sourceMapLines(api, web))
        assertFalse("role=\"alert\"" in browser.import("shipped/processes/build-and-ship.bpmn").body)
        val status = browser.get("/processes/build_and_ship").body.substringAfter("""<section id="pre-run">""").substringBefore("</section>")
        assertContains(status, "Ready")
        return browser.post("/processes/build_and_ship/runs", mapOf("prompt" to "Say hello in api", "path" to path, "branch" to "")).path.removePrefix("/runs/")
    }

    /** Approves the Spec when [spec], then the changes, Pushes and approves the Notes, and waits for the Run to complete. */
    private fun finish(run: String, spec: Boolean = true) {
        if (spec) browser.post(entry(run, "<i>✓</i>[approve]"), mapOf("action" to "approve", "comment" to ""))
        browser.post(entry(run, "<i>±</i>[review]", seconds = 30), mapOf("action" to "approve", "comment" to ""))
        browser.post(entry(run, "<i>↑</i>[push]"), mapOf("action" to "push", "instruction" to ""))
        browser.post(entry(run, "<i>✓</i>[approve]"), mapOf("action" to "approve", "comment" to ""))
        eventually(20) { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        assertEquals("01", git(api.remote, "show", "add-hello:01.txt"))
    }

    @Test
    fun `Answered as me has an answer-round Agent answer the Rounds, and Clone new repos clones a repo a Round names`() {
        afterTheGrilling()
        claude.session("grill", 1, round)
        claude.session("answer-round", result("""{"answers":[{"id":"who","answer":"Only admins.","why":"The Answer History says so."}]}"""))
        claude.session("grill", 2, result("""{"questions":[],"repos":["web"]}"""))
        val run = start("as-me")
        finish(run)

        assertEquals(listOf("[approve]", "[review]", "[push]", "[approve]"), waited)
        val answerer = claude.launches("answer-round").single()
        assertContains(answerer.stdin, "The Answer History is ${home.resolve("answer-history")}.")
        assertContains(answerer.stdin, "Who exports?")
        assertEquals("Read,Grep,Glob" to "Read,Grep,Glob", answerer.flag("--tools")?.removeSuffix(",Skill") to answerer.flag("--allowedTools")?.removeSuffix(",Skill,mcp__ants__run"))
        assertContains(claude.launches("grill")[1].stdin, """\"source\":\"agent\"""")
        // Clone new repos cloned the repo the first Round named, so to-spec has it in its clones.
        assertContains(claude.launches("tospec").single().stdin, """\"repos\":[\"web\"]""")
        assertContains(claude.launches("tospec").single().stdin, "repos/web")
        val report = browser.report(run)
        assertContains(report.substringAfter("<h2>Answers you didn't give</h2>"), "Who exports: Only admins. (agent)")
    }

    @Test
    fun `Answered as me hands Round 10 to the user`() {
        claude.session("scout", "write scout/brief.md Hello.\\n", result("""{"repos":["api"],"branch":"add-hello","brief":"scout/brief.md"}"""))
        claude.session("grill", round)
        claude.session("answer-round", result("""{"answers":[{"id":"who","answer":"Only admins.","why":"Because."}]}"""))
        val run = start("as-me")

        val entry = entry(run, "<i>?</i>[questions]", seconds = 60)
        assertEquals(10, claude.launches("grill").size)
        assertEquals(9, claude.launches("answer-round").size)
        assertContains(browser.get(entry).body, "Round 10")
    }

    @Test
    fun `Take the recommendations accepts the recommendations, and Clone new repos clones a repo a Round names`() {
        afterTheGrilling()
        claude.session("grill", 1, round)
        claude.session("grill", 2, result("""{"questions":[],"repos":[]}"""))
        val run = start("recommendations")
        finish(run)

        assertEquals(listOf("[approve]", "[review]", "[push]", "[approve]"), waited)
        assertContains(claude.launches("grill")[1].stdin, """{\"id\":\"who\",\"answer\":\"Admins.\",\"source\":\"recommended\"}""")
        assertContains(claude.launches("tospec").single().stdin, "repos/web")
    }

    @Test
    fun `Hands-off until review answers every Round by proxy, ends the grilling at the cap, and waits on the user only at Review changes, Push and the Notes`() {
        afterTheGrilling()
        claude.session("grill", round)
        claude.session("answer-round", result("""{"answers":[{"id":"who","answer":"Only admins.","why":"Because."}]}"""))
        val run = start("hands-off")
        finish(run, spec = false)

        assertEquals(listOf("[review]", "[push]", "[approve]"), waited)
        assertEquals(9, claude.launches("grill").size) // no Round 10 is asked
        assertEquals(9, claude.launches("answer-round").size)
        assertEquals("refs/heads/main", git(web.remote, "for-each-ref", "--format=%(refname)"))
        assertContains(claude.launches("tospec").single().stdin, """\"repos\":[\"web\"]""")
    }
}
