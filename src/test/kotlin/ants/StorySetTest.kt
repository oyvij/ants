package ants

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper
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

    /** Starts a Run of `story-set.bpmn` over `api` and `web`, commits the change the walkthrough tells, and gives the Run. */
    private fun start(): String {
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
        commitFile(api, "long.txt", WORDS.joinToString("\n", postfix = "\n").replaceFirst("one", "ONE"), "Shout")
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
    fun `a valid Story set renders in the Story tab, and By repo is the other tab`() {
        walkthrough(valid)
        val run = start()
        val page = browser.get(entry(run)).body
        assertContains(page, """<nav id="tabs"><b>Story</b> <a href="?tab=repo">By repo</a></nav>""")
        assertContains(page, "<h2>Shout the first word</h2>")
        assertContains(page, "<h3>The first word is shouted</h3>")
        assertContains(page, "<p>Only the first line changes.</p>")
        assertContains(page, "<h4>1. Shout</h4>")
        assertContains(page, "<code>repos/api/long.txt:1</code> (new, changed)")
        assertContains(page, "<tr><td>1</td><td>ONE</td></tr>")
        assertContains(page, "<p>Line one is upper case.</p>")
        assertContains(page, "<li>word = <code>ONE</code> (literal, repos/api/long.txt:1)</li>")
        assertContains(page, "<h4>2. The rest</h4>")
        assertContains(page, "<code>repos/api/long.txt:9-10</code> (new, context)")
        assertContains(page, "<tr><td>10</td><td>ten</td></tr>")
        assertContains(page, "In: the heading. Out: the end.")
        assertFalse("<h2>web</h2>" in page) // By repo is the other tab

        val byRepo = browser.get(entry(run) + "?tab=repo").body
        assertContains(byRepo, """<nav id="tabs"><a href="?tab=story">Story</a> <b>By repo</b></nav>""")
        assertContains(byRepo, "<h2>web</h2>")
        assertEquals(1, claude.launches("narrate").single().stdin.lines().size) // nothing to fix
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
        assertContains(page, "<h4>1. Shout</h4>")
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
        assertContains(commented.body, "NOTE repos/api/long.txt:9-10 (new), The first word is shouted step 2: Fine.")

        browser.post(entry, mapOf("action" to "approve", "comment" to ""))
        eventually { assertContains(browser.get("/runs/$run").body, "State: Completed") }
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
