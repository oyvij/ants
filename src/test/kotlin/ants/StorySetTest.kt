package ants

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class StorySetTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    private fun result(output: String) =
        """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Done.","output":$output}}"""

    private val walked = result("""{"stories":"walkthrough/stories.json"}""")

    /** The walkthrough's session: once the test has committed, it writes each of [sets] in turn, a Result after each. */
    private fun walkthrough(vararg sets: String) = claude.session(
        "narrate", "wait commits",
        *sets.mapIndexed { i, set -> listOfNotNull("read".takeIf { i > 0 }, "write walkthrough/stories.json $set", walked) }.flatten().toTypedArray(),
    )

    /**
     * A Story set over `repos/api/long.txt`, ten lines whose first the last commit changed: the step `first` is on that
     * change and cites its value, the step `last` passes through lines 9 and 10, untouched.
     */
    private val valid = """{"protocolVersion":1,"title":"Shout the first word","ranges":[{"repo":"api","base":"HEAD~1","head":"HEAD"}],""" +
        """"stories":[{"id":"shout","name":"The first word is shouted","premise":"Only the first line changes.","steps":[""" +
        """{"id":"first","name":"Shout","claim":"Line one is upper case.","why":"It is a heading.",""" +
        """"site":{"file":"repos/api/long.txt","side":"new","kind":"changed","from":1,"to":1},""" +
        """"values":[{"name":"word","value":"ONE","provenance":"literal","cite":{"file":"repos/api/long.txt","line":1}}]},""" +
        """{"id":"last","name":"The rest","claim":"The end stays.","why":"Nothing else moved.","flow":{"in":"the heading","out":"the end"},""" +
        """"site":{"file":"repos/api/long.txt","side":"new","kind":"context","from":9,"to":10}}]}]}"""

    /**
     * Starts a Run of `story-set.bpmn` over `api` and `web`, commits the change the walkthrough tells, with `other.txt`
     * beside it, which no step covers, then does [then] in the api clone, and gives the Run.
     */
    private fun start(then: (Path) -> Unit = {}): String {
        agentTask(
            claude,
            mapOf("narrate" to """{"type":"object","properties":{"stories":{"type":"string","format":"path"}}}""", "mend" to null, "ship" to null),
            extra = reviewSourceMap(),
        )
        browser.import("story-set.bpmn")
        browser.get("/processes/story-set")
        val run = browser.post("/processes/story-set/runs", listOf("repos" to "api", "repos" to "web")).path.removePrefix("/runs/")
        eventually { assertEquals(1, claude.launches("narrate").size) } // the clones are there
        val api = workspace(run).resolve("repos/api")
        commitFile(api, "long.txt", WORDS.joinToString("\n", postfix = "\n"), "Count to ten")
        api.resolve("other.txt").writeText("aside\n")
        commitFile(api, "long.txt", WORDS.joinToString("\n", postfix = "\n").replaceFirst("one", "ONE"), "Shout")
        then(api)
        claude.release("commits")
        return run
    }

    private fun row(run: String) = Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
        .find(browser.get("/notifications").body)?.groupValues?.get(1)

    private fun entry(run: String): String {
        var path = ""
        eventually { path = Regex("""href="(/notifications/[^"]+)"""").find(assertNotNull(row(run)).takeIf { "[review]" in it } ?: throw AssertionError("no review yet"))!!.groupValues[1] }
        return path
    }

    @Test
    fun `a valid Story set renders in the Story tab, one step at a time, and By repo is the other tab`() {
        walkthrough(valid)
        val run = start()
        val page = browser.get(entry(run)).body
        assertContains(page, """<a class="on" href="?tab=story">Story</a><a href="?tab=repo">By repo</a>""")
        // A summary card per repo: files, +/−, commits on the work branch.
        assertContains(page, """<b class="mono">api</b>""")
        assertContains(page, """2 files · <span class="plus">+11</span> <span class="minus">−0</span> · 2 commits""")
        // The story list, with its step counts, and the Remainder.
        assertContains(page, """<div class="stitle">Shout the first word</div>""")
        assertContains(page, """<span>The first word is shouted</span><span class="cnt">2 steps</span>""")
        assertContains(page, """<b>Remainder</b> · 1 change no step covers""")
        assertContains(page, """<div class="rf"><span class="mono"><b>api</b> · other.txt</span><span class="muted">1 hunk</span></div>""")
        assertContains(page, """<div class="small">Only the first line changes.</div>""")
        assertContains(page, "Shout → The rest")
        // Each step: its code with its site highlighted (the diff's removed lines shown on demand), the claim and the values.
        assertContains(page, """<span class="mono"><b>api</b> · long.txt</span>""")
        assertContains(page, """<div class="cl site changed add"><span class="ln">1</span><span class="bar"></span><span class="tx">ONE</span></div>""")
        assertContains(page, """<div class="cl del extra"><span class="ln">−</span><span class="bar"></span><span class="tx">one</span></div>""")
        assertContains(page, """<div class="cl dim"><span class="ln">2</span><span class="bar"></span><span class="tx">two</span></div>""")
        assertContains(page, """<div class="cl site context"><span class="ln">10</span><span class="bar"></span><span class="tx">ten</span></div>""")
        assertContains(page, """<div class="claim">Line one is upper case.</div>""")
        assertContains(page, """<span class="cited" title="from repos/api/long.txt:1">word: <b>ONE</b> <span class="small">[literal]</span></span>""")
        assertContains(page, """<span class="k">Why</span><span>It is a heading.</span>""")
        assertContains(page, """<span class="k">Flow</span><span>in: the heading<br>out: the end</span>""")
        assertFalse("STALE" in page)
        assertFalse("data-repo=\"web\"" in page) // By repo is the other tab

        val byRepo = browser.get(entry(run) + "?tab=repo").body
        assertContains(byRepo, """<a href="?tab=story">Story</a><a class="on" href="?tab=repo">By repo</a>""")
        assertContains(byRepo, "data-repo=\"web\"")
        assertEquals(1, claude.launches("narrate").single().stdin.lines().size) // nothing to fix
    }

    @Test
    fun `a step whose site changed since the Story set was written is STALE, with its lines then and now`() {
        walkthrough(valid.replace(""""base":"HEAD~1","head":"HEAD"""", """"base":"HEAD~2","head":"HEAD~1""""))
        val run = start { api -> commitFile(api, "long.txt", WORDS.joinToString("\n", postfix = "\n").replace("one", "ONE").replace("nine", "NINE"), "Shout nine") }
        val page = browser.get(entry(run)).body
        assertContains(page, """<div class="claim"><span class="stale">STALE</span> The end stays.</div>""")
        assertContains(page, """<span class="k">Stale</span><span>Written against<br><code>nine""" + "\n" + """ten</code><br>Now<br><code>NINE""" + "\n" + """ten</code></span>""")
        assertContains(page, """<a href="#s1-2">⚠ The rest</a>""")
        assertContains(page, """<span class="cnt">2 steps · <span class="stalecnt">1 stale</span></span>""")
        assertContains(page, """<div class="claim">Line one is upper case.</div>""") // its line is as it was
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|', quoteCharacter = '\'',
        textBlock = """file-missing        | first | repos/api/long.txt","side":"new","kind":"changed" | repos/api/gone.txt","side":"new","kind":"changed"
        range-out-of-bounds | first | "from":1,"to":1                                    | "from":1,"to":11
        claims-no-change    | last  | "kind":"context"                                   | "kind":"changed"
        citation-absent     | first | "line":1                                           | "line":2""",
    )
    fun `a check that fails resumes the walkthrough naming the step`(fault: String, step: String, from: String, to: String) {
        walkthrough(valid.replace(from, to), valid)
        val run = start()
        val page = browser.get(entry(run)).body
        assertContains(page, """<div class="claim">Line one is upper case.</div>""")
        val fix = JsonMapper.shared().readTree(claude.launches("narrate").single().stdin.lines()[1])["message"]["content"].asString()
        assertContains(fix, "Ants checked the Story set in walkthrough/stories.json")
        assertContains(fix, "- `$step`: $fault: ")
        assertEquals(1, Regex("- `").findAll(fix).count()) // only the failing step
    }

    @Test
    fun `two failed rounds give failed, naming the steps`() {
        val bad = valid.replace("\"line\":1", "\"line\":2")
        walkthrough(bad, bad)
        val run = start()
        eventually { assertContains(assertNotNull(row(run)), "[failed]") }
        assertContains(row(run)!!, "The Story set in walkthrough/stories.json fails its checks: first: citation-absent")
    }

    @Test
    fun `an unparseable Story set is refused whole, with no round to fix it`() {
        walkthrough(valid.replace(""""name":"Shout",""", ""))
        val run = start()
        eventually { assertContains(assertNotNull(row(run)), "[failed]") }
        assertContains(row(run)!!, "The Story set in walkthrough/stories.json is refused:")
        assertEquals(1, claude.launches("narrate").single().stdin.lines().size)
    }

    @Test
    fun `a stuck walkthrough is stuck, its Story set unchecked`() {
        claude.session(
            "narrate", "wait commits",
            """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"stuck","summary":"Which repo?","needs":"Name the repo."}}""",
        )
        val run = start()
        eventually { assertContains(assertNotNull(row(run)), "[stuck]") }
        assertContains(row(run)!!, "Which repo?")
    }

    @Test
    fun `a comment on a story step carries story and step`() {
        walkthrough(valid)
        claude.session("ship", result("{}"))
        val run = start()
        val entry = entry(run)
        assertContains(browser.get(entry).body, """<input type="hidden" name="story" value="The first word is shouted">""")
        val commented = browser.post(
            "$entry/comments",
            mapOf("story" to "The first word is shouted", "step" to "2", "repo" to "api", "file" to "repos/api/long.txt", "side" to "new", "from" to "9", "to" to "10", "type" to "NOTE", "body" to "Fine.", "tab" to "story"),
        )
        assertContains(commented.body, """<div class="scomment"><b>NOTE</b> Fine.</div>""") // under its step
        assertContains(commented.body, """<span class="tag t-NOTE">Note</span>""") // and listed above the overall comment
        assertContains(commented.body, """<a href="#s1-2">The first word is shouted › The rest</a> <span class="mono">repos/api/long.txt:9-10 (new)</span>: Fine.""")

        browser.post(entry, mapOf("action" to "approve", "comment" to ""))
        eventually { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        val prompt = JsonMapper.shared().readTree(claude.launches("ship").single().stdin)["message"]["content"].asString()
        val verdict = JsonMapper.shared().readValue(prompt.substringAfterLast("\n\n"), Map::class.java)["verdict"] as Map<*, *>
        assertEquals(
            listOf(mapOf("story" to "The first word is shouted", "step" to 2, "repo" to "api", "file" to "repos/api/long.txt", "side" to "new", "from" to 9, "to" to 10, "type" to "NOTE", "body" to "Fine.")),
            verdict["comments"],
        )
    }

    private companion object {
        val WORDS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    }
}
