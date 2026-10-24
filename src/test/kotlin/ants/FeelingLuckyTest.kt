package ants

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** The shipped Build and ship's Feeling lucky, run end to end against fake Agents, a bare remote and a temporary Vault. */
class FeelingLuckyTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()
    private val api = repo("api")

    private fun result(output: String) =
        """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Done.","output":$output}}"""

    private fun row(run: String) = Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
        .find(browser.get("/notifications").body)?.groupValues?.get(1)

    private fun entry(run: String): String {
        var path = ""
        eventually { path = Regex("""href="(/notifications/[^"]+)"""").find(assertNotNull(row(run)))!!.groupValues[1] }
        return path
    }

    /** Build and ship's Process page, set up with the fake claude, a Vault and a Source map holding [repos]. */
    private fun page(vararg repos: Remote = arrayOf(api)): String {
        settings(claude, sourceMapLines(*repos))
        assertFalse("role=\"alert\"" in browser.import("shipped/processes/build-and-ship.bpmn").body)
        return browser.get("/processes/build_and_ship").body
    }

    private fun start(prompt: String, vararg repos: Remote = arrayOf(api)): String {
        page(*repos)
        return browser.post("/processes/build_and_ship/runs", mapOf("prompt" to prompt, "path" to "lucky", "branch" to "")).path.removePrefix("/runs/")
    }

    /** Plays a whole Build and ship's Feeling lucky: the scout picks api and names the branch, the implementer commits, the user pushes. */
    private fun feelingLucky(): String {
        claude.session("scout", "write scout/brief.md Add a hello endpoint to api.\\n", result("""{"repos":["api"],"branch":"add-hello","brief":"scout/brief.md"}"""))
        claude.session("proposesolution", "write proposal/proposal.md # Hello\\n\\nOne endpoint.\\n", result("""{"proposal":"proposal/proposal.md","repos":[]}"""))
        claude.session("implementproposal", "sh git -C repos/api -c user.name=Ants -c user.email=ants@example.com -c commit.gpgsign=false commit -q --allow-empty -m 'Add hello'", result("""{"review":"No findings."}"""))
        claude.session("prproposal", result("""{"pullRequests":[{"repo":"api","url":"https://github.com/oyvij/api/pull/7"}]}"""))
        claude.session("updateknowledge", 1, "write vault/proposal.md One Note.\\n", result("""{"proposal":"vault/proposal.md","written":false}"""))
        claude.session("updateknowledge", 2, result("""{"written":true}"""))
        val run = start("Add a hello endpoint to api")
        val push = entry(run)
        assertContains(row(run)!!, "<i>↑</i>[push]")
        browser.post(push, mapOf("action" to "push", "instruction" to ""))
        eventually(20) { assertContains(assertNotNull(row(run)), "<i>✓</i>[approve]") } // the Notes
        browser.post(entry(run), mapOf("action" to "approve", "comment" to ""))
        eventually(20) { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        return run
    }

    @Test
    fun `Build and ship's Feeling lucky ends with one pushed branch on the bare remote, a pull request Result and a Report`() {
        val run = feelingLucky()

        assertEquals("Add hello", git(api.remote, "log", "-1", "--format=%s", "add-hello"))
        assertEquals(setOf("refs/heads/main", "refs/heads/add-hello"), git(api.remote, "for-each-ref", "--format=%(refname)").lines().toSet())
        val scout = claude.launches("scout").single()
        assertContains(scout.stdin, """\"sourceMap\":[{\"name\":\"api\",\"remote\":\"${api.remote}\"}]""")
        assertContains(claude.launches("implementproposal").single().stdin, """\"proposal\":\"proposal/proposal.md\"""")
        assertContains(claude.launches("prproposal").single().stdin, """\"pushed\":true""")
        val (proposed, written) = claude.launches("updateknowledge")
        for (text in listOf("""\"spec\":\"proposal/proposal.md\"""", """\"review\":\"No findings.\"""", """\"approval\":null""")) assertContains(proposed.stdin, text)
        assertContains(written.stdin, """\"approval\":{\"approved\":true""")
        val report = browser.report(run)
        assertContains(report, "<h2>Push</h2>")
        assertContains(report, "<h2>Pull requests</h2>")
        assertContains(report, """<li><strong>api</strong> · <a rel="nofollow" href="https://github.com/oyvij/api/pull/7">https://github.com/oyvij/api/pull/7</a></li>""")
    }

    @Test
    fun `Feeling lucky clones a repo the architect names and resumes it to propose again with the new clone`() {
        val web = repo("web")
        claude.session("scout", "write scout/brief.md Add a hello endpoint to api.\\n", result("""{"repos":["api"],"branch":"add-hello","brief":"scout/brief.md"}"""))
        claude.session("proposesolution", 1, "write proposal/proposal.md # Hello\\n\\nweb not checked.\\n", result("""{"proposal":"proposal/proposal.md","repos":["web"]}"""))
        claude.session("proposesolution", 2, "write proposal/proposal.md # Hello\\n\\nOne endpoint, shown in web.\\n", result("""{"proposal":"proposal/proposal.md","repos":["web"]}"""))
        claude.session("implementproposal", "sh git -C repos/web -c user.name=Ants -c user.email=ants@example.com -c commit.gpgsign=false commit -q --allow-empty -m 'Show hello'", result("""{"review":"No findings."}"""))
        claude.session("prproposal", result("""{"pullRequests":[]}"""))
        val run = start("Add a hello endpoint to api and show it in web", api, web)
        browser.post(entry(run), mapOf("action" to "push", "instruction" to ""))

        val (first, again) = claude.launches("proposesolution")
        assertFalse("repos/web" in first.stdin)
        assertContains(again.stdin, "repos/web")
        assertEquals(first.flag("--session-id"), again.flag("--resume"))
        assertEquals(1, claude.launches("implementproposal").size)
        // Clone new repos put web on the Run's work branch, so Push pushes it.
        eventually(20) { assertEquals("Show hello", git(web.remote, "log", "-1", "--format=%s", "add-hello")) }
    }

    @Test
    fun `the shipped Agents get the tool allow-lists the spec gives them`() {
        val ws = workspace(feelingLucky())
        fun tools(skill: String) = claude.launches(skill).first().let { it.flag("--tools")?.removeSuffix(",Skill") to it.flag("--allowedTools")?.removeSuffix(",mcp__ants__run")?.removeSuffix(",Skill") } // every claude Task has the run tool, and Skill when it has Skills

        // claude checks a Write against Edit rules and ignores a Write(path) rule, so the scout's limit goes out as both
        assertEquals("Read,Grep,Glob,Write,Agent" to "Read,Grep,Glob,Write(/$ws/scout/**),Agent(knowledge-searcher),Edit(/$ws/scout/**)", tools("scout"))
        assertEquals("Read,Grep,Glob,Write,Edit" to "Read,Grep,Glob,Write(/$ws/proposal/**),Edit(/$ws/proposal/**)", tools("proposesolution"))
        assertContains(tools("implementproposal").first!!.split(","), "Agent")
        assertFalse("Bash(" in tools("implementproposal").second!!) // Bash unlimited
        // claude refuses `gh pr create --body "…"` with a backtick in the body, so the body goes in a file under pr/
        assertEquals("Read,Write,Edit,Bash" to "Read,Write(/$ws/pr/**),Edit(/$ws/pr/**),Bash(gh pr create *),Bash(gh pr view *),Bash(gh repo view *),Bash(git log *),Bash(git diff *)", tools("prproposal"))
        val assignment = claude.launches("prproposal").single().stdin // the pr Task's Assignment says how
        assertContains(assignment, "--body-file pr/<repo>.md")
        // and the title, which stays on the command line, so it is written without backticks
        assertContains(assignment, "in plain words with no backticks")
        // claude refuses `git -C` and `cd … && git …` under Bash(git log *), but allows a lone cd into the clone
        assertContains(assignment, "first `cd repos/<repo>`, then a plain `git log …`")
        assertContains(assignment, "gh repo view <owner>/<name> --json parent")

        val scout = claude.launches("scout").single()
        val offered = JsonMapper.shared().readValue(scout.flag("--agents"), Map::class.java)
        assertEquals(listOf("Read", "Grep", "Glob"), (offered["knowledge-searcher"] as Map<*, *>)["tools"])
        assertFalse("Agent(knowledge-searcher)" in scout.flag("--disallowedTools")!!.split(","))
        assertContains(scout.flag("--disallowedTools")!!.split(","), "Agent(general-purpose)")
        assertFalse("Bash" in scout.flag("--tools")!!)
    }

    @Test
    fun `the scout's stuck on an unknown repo reaches the Notification list`() {
        claude.session(
            "scout",
            """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"stuck","summary":"No repo named billing.","needs":"The prompt names billing, which isn't in the Source map. Add it, or name its git URL."}}""",
        )
        val run = start("Fix billing")
        val entry = entry(run)
        assertContains(row(run)!!, "<i>⚠</i>[stuck]")
        assertContains(row(run)!!, "Scout")
        assertContains(browser.get(entry).body, "The prompt names billing")
    }

    @Test
    fun `the Skills adapted from Matt Pocock's keep his MIT notice`() {
        val skills = home.resolve("shipped/skills")
        for (skill in listOf("tdd", "code-review")) assertContains(skills.resolve("$skill/LICENSE").toFile().readText(), "MIT License\n\nCopyright (c) 2026 Matt Pocock")
        for (skill in listOf("tdd", "code-review", "implement", "pr")) {
            assertContains(skills.resolve("$skill/SKILL.md").toFile().readText(), "Copyright (c) 2026 Matt Pocock. MIT License", message = skill)
        }
    }

    @Test
    fun `the pre-Run check passes for Build and ship on a configured setup`() {
        val status = page().substringAfter("""<section id="pre-run">""").substringBefore("</section>")
        assertContains(status, "Ready")
        assertFalse("Can't start" in status)
    }
}
