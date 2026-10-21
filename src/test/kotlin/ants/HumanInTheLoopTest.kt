package ants

import org.junit.jupiter.api.Test
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** The shipped Build and ship's Human in the loop, run end to end against fake Agents, bare remotes and a temporary Vault. */
class HumanInTheLoopTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()
    private val api = repo("api")
    private val web = repo("web")

    private fun result(output: String) =
        """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Done.","output":$output}}"""

    private fun question(id: String) =
        """{"id":"$id","title":"Title $id","ask":"Ask $id?","context":"Settles $id.","recommendation":"Recommend $id.","why":"Because $id."}"""

    private fun row(run: String) = Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
        .find(browser.get("/notifications").body)?.groupValues?.get(1)

    /** The Run's entry on the Notification list, once it is the one tagged [tag]. */
    private fun entry(run: String, tag: String): String {
        var path = ""
        eventually(20) {
            val row = assertNotNull(row(run))
            assertContains(row, tag)
            path = Regex("""href="(/notifications/[^"]+)"""").find(row)!!.groupValues[1]
        }
        return path
    }

    /** A commit of the file [name] in the clone `repos/api` of the launch's working directory. */
    private fun commit(name: String, text: String = name) =
        "sh cd repos/api && echo $text > $name && git add $name && git -c user.name=Ants -c user.email=ants@example.com -c commit.gpgsign=false commit -qm 'Add $name'"

    /** A Story set over the change to `repos/api/01.txt`, which [text] is the first line of. */
    private fun stories(text: String) =
        """write walkthrough/stories.json {"protocolVersion":1,"title":"Three Tickets","ranges":[{"repo":"api","base":"origin/main","head":"HEAD"}],""" +
            """"stories":[{"id":"one","name":"One","premise":"The first Ticket.","steps":[{"id":"file","name":"The file","claim":"It holds $text.","why":"Ticket 01.",""" +
            """"site":{"file":"repos/api/01.txt","side":"new","kind":"changed","from":1,"to":1},""" +
            """"values":[{"name":"text","value":"$text","provenance":"literal","cite":{"file":"repos/api/01.txt","line":1}}]}]}]}"""

    /**
     * Plays a whole Build and ship's Human in the loop: the scout picks api, the first Round names web too, two Rounds, the Spec approved,
     * three Tickets (03 blocked by 01) implemented, a Review changes Send back and fix, then Approve, Push and the pull
     * request.
     */
    private fun humanInTheLoop(): String {
        claude.session("scout", "write scout/brief.md Hello.\\n", result("""{"repos":["api"],"branch":"add-hello","brief":"scout/brief.md"}"""))
        claude.session("grill", 1, result("""{"questions":[${question("who")},${question("scope")}],"repos":["web"]}"""))
        claude.session("grill", 2, result("""{"questions":[${question("format")}],"repos":[]}"""))
        claude.session("grill", 3, result("""{"questions":[],"repos":[]}"""))
        claude.session("to-spec", "write tickets/spec.md # Hello\\n\\nSay hello.\\n", result("""{"spec":"tickets/spec.md"}"""))
        claude.session(
            "to-tickets",
            "write tickets/issues/01-one.md # One\\n\\nStatus: ready-for-agent\\n\\nBlocked by: none\\n",
            "write tickets/issues/02-two.md # Two\\n\\nStatus: ready-for-agent\\n\\nBlocked by: none\\n",
            "write tickets/issues/03-three.md # Three\\n\\nStatus: ready-for-agent\\n\\nBlocked by: 01\\n",
            result("""{"tickets":["tickets/issues/01-one.md","tickets/issues/02-two.md","tickets/issues/03-three.md"]}"""),
        )
        // Each implementer commits a file named after its Ticket, and notes which files it found.
        claude.session(
            "implement",
            """sh t=${'$'}(grep -o 'issues/0[0-9]' "${'$'}launch/stdin" | head -1 | cut -c8-) && echo ${'$'}t > "${'$'}launch/ticket" && ls repos/api > "${'$'}launch/seen"""",
            """sh cd repos/api && t=${'$'}(cat "${'$'}launch/ticket") && echo ${'$'}t > ${'$'}t.txt && git add ${'$'}t.txt && git -c user.name=Ants -c user.email=ants@example.com -c commit.gpgsign=false commit -qm "Add ${'$'}t.txt"""",
            result("""{"review":"Reviewed the Ticket."}"""),
        )
        claude.session("walkthrough", 1, stories("01"), result("""{"stories":"walkthrough/stories.json"}"""))
        claude.session("fix-review", commit("01.txt", "ONE"), result("""{"review":"Shouted one."}"""))
        claude.session("walkthrough", 2, stories("ONE"), result("""{"stories":"walkthrough/stories.json"}"""))
        claude.session("pr", result("""{"pullRequests":[{"repo":"api","url":"https://github.com/oyvij/api/pull/8"}]}"""))
        claude.session("update-knowledge", result("""{"written":true}"""))

        settings(claude, sourceMapLines(api, web))
        assertFalse("role=\"alert\"" in browser.import("shipped/processes/build-and-ship.bpmn").body)
        browser.get("/processes/build_and_ship")
        val run = browser.post("/processes/build_and_ship/runs", mapOf("prompt" to "Say hello in api", "path" to "human", "branch" to "")).path.removePrefix("/runs/")

        browser.post("${entry(run, "<i>?</i>[questions]")}/answers", mapOf("answer.who" to "Recommend who.", "answer.scope" to "Only admins."))
        eventually { assertEquals(2, claude.launches("grill").size) }
        browser.post("${entry(run, "<i>?</i>[questions]")}/answers", mapOf("answer.format" to "Recommend format."))
        browser.post(entry(run, "<i>✓</i>[approve]"), mapOf("action" to "approve", "comment" to ""))
        val review = entry(run, "<i>±</i>[review]")
        browser.post(review, mapOf("action" to "send-back", "comment" to "Shout one."))
        eventually(20) { assertEquals(2, claude.launches("walkthrough").size) }
        browser.post(entry(run, "<i>±</i>[review]"), mapOf("action" to "approve", "comment" to ""))
        browser.post(entry(run, "<i>↑</i>[push]"), mapOf("action" to "push", "instruction" to ""))
        eventually(20) { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        return run
    }

    @Test
    fun `Build and ship's Human in the loop runs end to end, with two Rounds, an approved Spec, three Tickets, a Send back and fix, then Push and the pull request`() {
        val run = humanInTheLoop()

        // Review changes Approve set every Ticket done.
        for (ticket in listOf("01-one", "02-two", "03-three")) {
            assertContains(workspace(run).resolve("tickets/issues/$ticket.md").readText(), "Status: done", message = ticket)
        }

        assertEquals(3, claude.launches("grill").size)
        assertContains(claude.launches("grill")[1].stdin, """\"source\":\"written\"""")
        // Clone new repos cloned the repo the first Round named; Push pushed api's work branch, not web's, which no Ticket changed.
        assertEquals("ONE", git(api.remote, "show", "add-hello:01.txt"))
        for (file in listOf("02.txt", "03.txt")) git(api.remote, "show", "add-hello:$file")
        assertEquals("", git(web.remote, "branch", "--list", "add-hello"))
        // Ticket 03 launched only once its blocker 01 had landed.
        val three = claude.launches("implement").single { "issues/03" in it.stdin }
        assertContains(three.file("seen"), "01.txt")
        assertContains(claude.launches("fix-review").single().stdin, "From the user:\\nShout one.")
        // pr reads the implementers' reviews and the fixer's; update-knowledge reads the implementers'.
        for (text in listOf("Reviewed the Ticket.", "Shouted one.")) assertContains(claude.launches("pr").single().stdin, text)
        assertContains(claude.launches("update-knowledge").single().stdin, """\"review\":[{""")
        assertContains(claude.launches("update-knowledge").single().stdin, "Reviewed the Ticket.")

        val report = browser.report(run)
        for (text in listOf("<h2>Rounds</h2>", "Title who: Recommend who. (accepted)", "Title format: Recommend format. (accepted)", "<h2>Tickets</h2>", "<h2>Review</h2>", "Shouted one.", "Reviewed the Ticket.", "<h2>Push</h2>", "<h2>Pull requests</h2>", "https://github.com/oyvij/api/pull/8")) {
            assertContains(report, text)
        }
        for (ticket in listOf("01-one", "02-two", "03-three")) assertContains(report, Regex("""$ticket[^>]*>[^<]*</a> · done"""), ticket)
        assertContains(report, Regex("""<strong>api</strong> · pushed <code>add-hello</code>"""))
        assertFalse("<strong>web</strong> · pushed" in report)
    }

    @Test
    fun `the griller, planner and storyteller get the tool allow-lists the spec gives them, and the implementer fixes the review`() {
        val ws = workspace(humanInTheLoop())
        fun tools(skill: String) = claude.launches(skill).first().let { it.flag("--tools") to it.flag("--allowedTools")?.removeSuffix(",mcp__ants__run") } // every claude Task has the run tool

        assertEquals("Read,Grep,Glob" to "Read,Grep,Glob", tools("grill"))
        assertEquals("Read,Grep,Glob,Write,Edit" to "Read,Grep,Glob,Write(/$ws/tickets/**),Edit(/$ws/tickets/**)", tools("to-spec"))
        assertEquals(tools("to-spec"), tools("to-tickets"))
        assertEquals(
            "Read,Grep,Glob,Write,Edit,Bash" to
                "Read,Grep,Glob,Write(/$ws/walkthrough/**),Edit(/$ws/walkthrough/**),Bash(git log *),Bash(git diff *),Bash(git show *),Bash(git merge-base *)",
            tools("walkthrough"),
        )
        assertContains(claude.launches("walkthrough").first().flag("--disallowedTools")!!.split(","), "Bash(git *--output*)") // --output makes git log, diff and show write a file
        assertEquals(tools("implement"), tools("fix-review"))
        // Every implementer builds with tdd and reviews with code-review.
        for (skill in listOf("implement", "fix-review")) {
            for (used in listOf("tdd", "code-review")) assertContains(claude.launches(skill).first().stdin, "/$used/SKILL.md", message = skill)
        }
    }

    @Test
    fun `the Skills adapted from Matt Pocock's and Varde's keep their MIT notices`() {
        val skills = home.resolve("shipped/skills")
        for (skill in listOf("grill", "to-spec", "to-tickets")) {
            assertContains(skills.resolve("$skill/SKILL.md").toFile().readText(), "Copyright (c) 2026 Matt Pocock. MIT License", message = skill)
        }
        assertContains(skills.resolve("walkthrough/SKILL.md").toFile().readText(), "Copyright (c) 2026 Øyvind Johannessen. MIT License")
    }

    @Test
    fun `the pre-Run check passes for Build and ship on a configured setup`() {
        settings(claude, sourceMapLines(api))
        browser.import("shipped/processes/build-and-ship.bpmn")
        val status = browser.get("/processes/build_and_ship").body.substringAfter("""<section id="pre-run">""").substringBefore("</section>")
        assertContains(status, "Ready")
        assertFalse("Can't start" in status)
    }
}
