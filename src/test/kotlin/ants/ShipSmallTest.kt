package ants

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** The shipped Ship small, run end to end against fake Agents, a bare remote and a temporary Vault. */
class ShipSmallTest : AntsTest() {
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

    /** Ship small's Process page, set up with the fake claude, a Vault and a Source map holding [api]. */
    private fun page(): String {
        settings(claude, sourceMapLines(api))
        assertFalse("role=\"alert\"" in browser.import("shipped/processes/ship-small.bpmn").body)
        return browser.get("/processes/ship_small").body
    }

    private fun start(prompt: String): String {
        page()
        return browser.post("/processes/ship_small/runs", mapOf("prompt" to prompt, "branch" to "")).path.removePrefix("/runs/")
    }

    /** Plays a whole Ship small: the scout picks api and names the branch, the implementer commits, the user pushes. */
    private fun shipSmall(): String {
        claude.session("scout", "write scout/brief.md Add a hello endpoint to api.\\n", result("""{"repos":["api"],"branch":"add-hello","brief":"scout/brief.md"}"""))
        claude.session("propose-solution", "write proposal/proposal.md # Hello\\n\\nOne endpoint.\\n", result("""{"proposal":"proposal/proposal.md"}"""))
        claude.session("implement", "sh git -C repos/api -c user.name=Ants -c user.email=ants@example.com -c commit.gpgsign=false commit -q --allow-empty -m 'Add hello'", result("""{"review":"No findings."}"""))
        claude.session("pr", result("""{"pullRequests":[{"repo":"api","url":"https://github.com/oyvij/api/pull/7"}]}"""))
        claude.session("update-knowledge", 1, "write vault/proposal.md One Note.\\n", result("""{"proposal":"vault/proposal.md","written":false}"""))
        claude.session("update-knowledge", 2, result("""{"written":true}"""))
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
    fun `Ship small ends with one pushed branch on the bare remote, a pull request Result and a Report`() {
        val run = shipSmall()

        assertEquals("Add hello", git(api.remote, "log", "-1", "--format=%s", "add-hello"))
        assertEquals(setOf("refs/heads/main", "refs/heads/add-hello"), git(api.remote, "for-each-ref", "--format=%(refname)").lines().toSet())
        val scout = claude.launches("scout").single()
        assertContains(scout.stdin, """\"sourceMap\":[{\"name\":\"api\",\"remote\":\"${api.remote}\"}]""")
        assertContains(claude.launches("implement").single().stdin, """\"proposal\":\"proposal/proposal.md\"""")
        assertContains(claude.launches("pr").single().stdin, """\"pushed\":true""")
        val (proposed, written) = claude.launches("update-knowledge")
        for (text in listOf("""\"spec\":\"proposal/proposal.md\"""", """\"review\":\"No findings.\"""", """\"approval\":null""")) assertContains(proposed.stdin, text)
        assertContains(written.stdin, """\"approval\":{\"approved\":true""")
        val report = browser.get("/runs/$run/report").body
        assertContains(report, "<h2>Push</h2>")
        assertContains(report, "<h2>Pull requests</h2>")
        assertContains(report, """<li><strong>api</strong> · <a rel="nofollow" href="https://github.com/oyvij/api/pull/7">https://github.com/oyvij/api/pull/7</a></li>""")
    }

    @Test
    fun `the shipped Agents get the tool allow-lists the spec gives them`() {
        shipSmall()
        fun tools(skill: String) = claude.launches(skill).first().let { it.flag("--tools") to it.flag("--allowedTools")?.removeSuffix(",mcp__ants__run") } // every claude Task has the run tool

        // claude checks a Write against Edit rules and ignores a Write(path) rule, so the scout's limit goes out as both
        assertEquals("Read,Grep,Glob,Write,Agent" to "Read,Grep,Glob,Write(scout/**),Agent(knowledge-searcher),Edit(scout/**)", tools("scout"))
        assertEquals("Read,Grep,Glob,Write,Edit" to "Read,Grep,Glob,Write(proposal/**),Edit(proposal/**)", tools("propose-solution"))
        assertContains(tools("implement").first!!.split(","), "Agent")
        assertFalse("Bash(" in tools("implement").second!!) // Bash unlimited
        assertEquals("Read,Bash" to "Read,Bash(gh pr create *),Bash(gh pr view *),Bash(git log *),Bash(git diff *)", tools("pr"))

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
    fun `the pre-Run check passes for Ship small on a configured setup`() {
        val status = page().substringAfter("""<section id="pre-run">""").substringBefore("</section>")
        assertContains(status, "Ready")
        assertFalse("Can't start" in status)
    }
}
