package ants

import org.junit.jupiter.api.Test
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class NotificationListTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    private fun setUp() {
        home.resolve("settings.yaml").writeText("claude:\n  command: [${claude.command}]\n")
        home.resolve("agents").createDirectories().resolve("writer.md").writeText("Write plainly.\n")
        val skill = home.resolve("skills/draft").createDirectories()
        skill.resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts a text.\n---\nDraft the text.\n")
        skill.resolve("result.schema.json").deleteIfExists()
    }

    private fun result(result: String) = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":$result}"""
    private val stuck = result("""{"outcome":"stuck","summary":"No tone given.","needs":"Which tone, dry or warm?"}""")
    private val done = result("""{"outcome":"done","summary":"Drafted.","output":{"text":"Hello"}}""")

    private fun start(): String {
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        return browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

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

    private fun runLog(run: String) = home.resolve("runs/$run/run-log.md").readText()

    @Test
    fun `a stuck Agent Task appears on the Notification list as ⚠ stuck with its summary`() {
        setUp()
        claude.session("draft", stuck)
        val run = start()
        entry(run)
        val row = row(run)!!
        for (cell in listOf("⚠ [stuck]", "Agent Task", "Draft", "No tone given.", "<td>0 min</td>")) assertContains(row, cell)
        val page = browser.get(entry(run)).body
        assertContains(page, "Which tone, dry or warm?")
        assertContains(page, """href="/runs/$run/tasks/draft/transcript"""")
    }

    @Test
    fun `Continue resumes the same session id with the instruction, and can't be sent empty`() {
        setUp()
        claude.session("draft", stuck)
        claude.session("draft", 2, "wait release", done)
        val run = start()
        val entry = entry(run)

        val refused = browser.post(entry, mapOf("action" to "continue", "instruction" to " "))
        assertContains(refused.body, "Continue the session needs an instruction")
        assertEquals(entry, entry(run))

        val acted = browser.post(entry, mapOf("action" to "continue", "instruction" to "Dry, please."))
        assertEquals(null, row(run)) // gone the moment the user acts
        assertEquals("/notifications", acted.path) // nothing else of this Run is waiting
        assertContains(runLog(run), "Draft · Continue the session\n\nDry, please.\n")
        claude.release("release")
        eventually { assertContains(page(run), "State: Completed") }
        val (first, second) = claude.launches("draft")
        assertEquals(first.flag("--session-id"), second.flag("--resume"))
        assertEquals(null, second.flag("--session-id"))
        assertEquals("""{"type":"user","message":{"role":"user","content":"Dry, please."}}""", second.stdin)
    }

    @Test
    fun `Start fresh launches a new session id with the instruction under From the user`() {
        setUp()
        claude.session("draft", stuck)
        claude.session("draft", 2, "wait release", done)
        val run = start()
        browser.post(entry(run), mapOf("action" to "fresh", "instruction" to "Warm, please."))
        assertContains(runLog(run), "Draft · Start fresh\n\nWarm, please.\n")
        claude.release("release")
        eventually { assertContains(page(run), "State: Completed") }
        val (first, second) = claude.launches("draft")
        assertNotEquals(first.flag("--session-id"), assertNotNull(second.flag("--session-id")))
        assertEquals(null, second.flag("--resume"))
        assertContains(second.stdin, """Follow the Skill in ${home.resolve("skills/draft/SKILL.md")}""")
        assertContains(second.stdin, """\n\nFrom the user:\nWarm, please."}}""")
    }

    @Test
    fun `Start fresh needs no instruction`() {
        setUp()
        claude.session("draft", stuck)
        claude.session("draft", 2, done)
        val run = start()
        browser.post(entry(run), mapOf("action" to "fresh", "instruction" to ""))
        eventually { assertContains(page(run), "State: Completed") }
        assertFalse("From the user" in claude.launches("draft")[1].stdin)
    }

    @Test
    fun `Next up lands on a new entry from the same Run`() {
        setUp()
        claude.session("draft", stuck)
        claude.session("draft", 2, result("""{"outcome":"stuck","summary":"Still no tone.","needs":"Dry or warm?"}"""))
        val run = start()
        val first = entry(run)
        val next = browser.post(first, mapOf("action" to "continue", "instruction" to "Pick one."))
        assertNotEquals(first, next.path)
        assertContains(next.body, "Dry or warm?")
    }

    @Test
    fun `the nav and tab-title count follow the Notification list live`() {
        setUp()
        claude.session("draft", stuck)
        claude.session("draft", 2, done)
        assertContains(browser.get("/notifications").body, """<script src="/notifications.js"""")
        val counts = browser.events("/notifications/count")
        val before = counts.next().toInt()
        val run = start()
        assertEquals(before + 1, counts.next().toInt())
        browser.post(entry(run), mapOf("action" to "continue", "instruction" to "Dry."))
        assertEquals(before, counts.next().toInt())
    }

    private fun BlockingQueue<String>.next() = assertNotNull(poll(10, SECONDS), "no count within 10 seconds")
}
