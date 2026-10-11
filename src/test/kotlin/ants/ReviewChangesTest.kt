package ants

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class ReviewChangesTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    private fun result(output: String) =
        """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Done.","output":$output}}"""

    /**
     * Starts a Run of `review.bpmn` over the repos `api` and `web`: Clone repos → Review the changes → Ship, or Fix and
     * back. Gives the Run once its clones are there.
     */
    private fun start(): String {
        agentTask(claude, mapOf("fix-review" to """{"type":"object","properties":{"review":{"type":"string"}}}""", "ship" to null), extra = reviewSourceMap())
        browser.import("review.bpmn")
        browser.get("/processes/review")
        val run = browser.post("/processes/review/runs", listOf("repos" to "api", "repos" to "web")).path.removePrefix("/runs/")
        eventually { assertNotNull(row(run)) }
        return run
    }

    private fun clone(run: String, name: String): Path = home.runWorkspace(run).resolve("repos/$name")

    /** The Run's row on the Notification list, or null. */
    private fun row(run: String) = Regex("""<tr>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
        .find(browser.get("/notifications").body)?.groupValues?.get(1)

    /** The path of the Run's entry, once it is on the Notification list. */
    private fun entry(run: String): String {
        var path = ""
        eventually { path = Regex("""href="(/notifications/[^"]+)"""").find(assertNotNull(row(run)))!!.groupValues[1] }
        return path
    }

    private fun page(run: String) = browser.get("/runs/$run").body

    private val issue = mapOf("repo" to "api", "file" to "repos/api/hello.txt", "side" to "new", "from" to "1", "to" to "2", "type" to "ISSUE", "body" to "Say hello, not hi.")

    @Test
    fun `By repo shows each repo's commits and diffs since the base`() {
        val run = start()
        commitFile(clone(run, "api"), "hello.txt", "hi\nworld\n", "Say hi")
        commitFile(clone(run, "web"), "new.txt", "brand new\n", "Add a file")

        assertContains(row(run)!!, "[review]")
        val body = browser.get(entry(run)).body
        assertContains(body, "<h2>api</h2>")
        assertContains(body, "<h2>web</h2>")
        assertContains(body, "</code> Say hi</li>")
        assertContains(body, "</code> Add a file</li>")
        assertFalse("</code> First</li>" in body) // the base is not under review
        assertContains(body, """data-file="repos/api/hello.txt"""")
        assertContains(body, """<tr class="del" data-side="old" data-line="1"><td>1</td><td></td><td>-hello</td></tr>""")
        assertContains(body, """<tr class="add" data-side="new" data-line="1"><td></td><td>1</td><td>+hi</td></tr>""")
        assertContains(body, """<tr class="ctx" data-side="new" data-line="2"><td>2</td><td>2</td><td> world</td></tr>""")
        assertContains(body, """data-file="repos/web/new.txt"""")
        assertContains(body, "+brand new")
    }

    @Test
    fun `a typed line comment reaches the fix Task's resume message, which starts it fresh, then resumes it`() {
        claude.session("fix-review", result("""{"review":"Put hello back."}"""))
        claude.session("ship", result("{}"))
        val run = start()
        val entry = entry(run)

        val refused = browser.post(entry, mapOf("action" to "send-back", "comment" to " "))
        assertContains(refused.body, "Send back needs a comment, on the changes or on a line.")

        val commented = browser.post("$entry/comments", issue)
        assertContains(commented.body, "ISSUE")
        assertContains(commented.body, "Say hello, not hi.")
        browser.post("$entry/comments", issue + ("body" to "A slip."))
        browser.post("$entry/comments", mapOf("remove" to "1"))
        assertFalse("A slip." in browser.get(entry).body)

        browser.post(entry, mapOf("action" to "send-back", "comment" to ""))
        val again = entry(run) // the fix is done, and the changes wait for review again
        assertNotEquals(entry, again)
        val first = claude.launches("fix-review").single()
        assertContains(first.stdin, "From the user:\\n- ISSUE repos/api/hello.txt:1-2 (new): Say hello, not hi.")
        assertContains(home.resolve("runs/$run/run-log.md").readText(), "Review the changes · Send back\n\n- ISSUE repos/api/hello.txt:1-2 (new): Say hello, not hi.\n")

        claude.session("fix-review", 2, result("""{"review":"Shortened it."}"""))
        browser.post(again, mapOf("action" to "send-back", "comment" to "Shorter."))
        browser.post(entry(run), mapOf("action" to "approve", "comment" to ""))
        eventually { assertContains(page(run), "State: Completed") }
        val second = claude.launches("fix-review")[1]
        assertEquals(first.flag("--session-id"), second.flag("--resume"))
        assertEquals("""{"type":"user","message":{"role":"user","content":"Shorter."}}""", second.stdin)

        val report = browser.get("/runs/$run/report").body
        assertContains(report, "<h2>Review</h2>")
        assertContains(report, "<h3>fix</h3>\n<p>Shortened it.</p>") // a Task's latest review
    }

    @Test
    fun `Approve completes with approved, comment and comments, and sets in-review Tickets done`() {
        claude.session("ship", result("{}"))
        val run = start()
        val issues = home.runWorkspace(run).resolve("tickets/issues").createDirectories()
        issues.resolve("01-a.md").writeText("# A\n\nStatus: in-review\n")
        issues.resolve("02-b.md").writeText("# B\n\nStatus: ready-for-human\n")
        val entry = entry(run)
        browser.post("$entry/comments", issue + ("type" to "NOTE"))

        browser.post(entry, mapOf("action" to "approve", "comment" to "Lovely."))
        eventually { assertContains(page(run), "State: Completed") }
        val prompt = JsonMapper.shared().readTree(claude.launches("ship").single().stdin)["message"]["content"].asString()
        val verdict = JsonMapper.shared().readValue(prompt.substringAfterLast("\n\n"), Map::class.java)["verdict"]
        assertEquals(
            mapOf(
                "approved" to true, "comment" to "Lovely.",
                "comments" to listOf(mapOf("story" to null, "step" to null, "repo" to "api", "file" to "repos/api/hello.txt", "side" to "new", "from" to 1, "to" to 2, "type" to "NOTE", "body" to "Say hello, not hi.")),
            ),
            verdict,
        )
        assertContains(issues.resolve("01-a.md").readText(), "Status: done")
        assertContains(issues.resolve("02-b.md").readText(), "Status: ready-for-human")
    }

    @Test
    fun `Send back naming a multi-instance Task is a Check error`() {
        agentTask(null, mapOf("fix-review" to null, "ship" to null))
        browser.get("/processes")
        val bpmn = javaClass.getResource("/review.bpmn")!!.readText()
            .replace("""<operaton:property name="ants.skill" value="fix-review" />
        </operaton:properties>
      </bpmn:extensionElements>""", """<operaton:property name="ants.skill" value="fix-review" />
        </operaton:properties>
      </bpmn:extensionElements>
      <bpmn:multiInstanceLoopCharacteristics operaton:collection="${'$'}{repos}" />""")
        val checks = browser.post("/processes/checks", mapOf("bpmn" to bpmn)).body
        assertContains(checks, "Review the changes (changes) sends back to Fix (fix), a multi-instance Task: Send back resumes one Agent Task.")
    }
}

/**
 * `settings.yaml` lines for a Source map of two repos, `api` and `web`: each a bare remote whose `main` holds
 * `hello.txt`, and a local checkout of it.
 */
internal fun reviewSourceMap(): String {
    val map = Files.createTempDirectory("source-map").resolve("work.md")
    map.writeText(listOf("api", "web").joinToString("") { name ->
        val remote = Files.createTempDirectory("remote").resolve("$name.git")
        git(remote.parent, "init", "-q", "--bare", "-b", "main", "$remote")
        val local = Files.createTempDirectory("local")
        git(local, "clone", "-q", "$remote", ".")
        commitFile(local, "hello.txt", "hello\nworld\n", "First")
        git(local, "push", "-q", "origin", "HEAD:main")
        "- $name: $local ($remote)\n"
    })
    return "source-maps:\n  work: $map\n"
}

/** Writes [text] to [file] in the repo [repo] and commits it as [message]. */
internal fun commitFile(repo: Path, file: String, text: String, message: String) {
    repo.resolve(file).writeText(text)
    git(repo, "add", ".")
    git(repo, "commit", "-q", "-m", message)
}
