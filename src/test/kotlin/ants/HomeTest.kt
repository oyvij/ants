package ants

import org.junit.jupiter.api.Test
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class HomeTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    private fun setUp() {
        home.resolve("settings.yaml").writeText("claude:\n  command: [${claude.command}]\n$vault")
        home.resolve("agents").createDirectories().resolve("writer.md").writeText("---\nskills: draft\n---\nWrite plainly.\n")
        val skill = home.resolve("skills/draft").createDirectories()
        skill.resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts a text.\n---\nDraft the text.\n")
        skill.resolve("result.schema.json").deleteIfExists()
        browser.import("agent-task.bpmn")
    }

    private fun result(result: String) = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":$result}"""
    private val stuck = result("""{"outcome":"stuck","summary":"No tone given.","needs":"Which tone, dry or warm?"}""")
    private val done = result("""{"outcome":"done","summary":"Drafted.","output":{"text":"Hello"}}""")

    private fun start(): String {
        browser.get("/processes/agent-task")
        return browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    /** A section of Home, by id. */
    private fun section(html: String, id: String) =
        assertNotNull(Regex("""<section id="$id">(.*?)</section>""", RegexOption.DOT_MATCHES_ALL).find(html), "no section $id").groupValues[1]

    /** Home's stats: Runs live, waiting and done. */
    private fun stats(html: String) = Regex("""<dt>([^<]+)</dt>\s*<dd>(\d+)</dd>""").findAll(section(html, "stats"))
        .associate { it.groupValues[1] to it.groupValues[2].toInt() }

    /** Waits for the live update in which [check] passes. */
    private fun BlockingQueue<String>.until(check: (String) -> Boolean): String {
        while (true) {
            val html = assertNotNull(poll(10, SECONDS), "no matching update within 10 seconds")
            if (check(html)) return html
        }
    }

    @Test
    fun `Home lists waiting entries and running Agent Tasks, and its stats count Runs live, waiting and done, live`() {
        setUp()
        claude.session("draft", stuck)
        claude.session("draft", 2, "wait release", done)
        val before = stats(browser.get("/").body)
        fun stats(live: Int, waiting: Int, done: Int) =
            mapOf("Live" to before.getValue("Live") + live, "Waiting" to before.getValue("Waiting") + waiting, "Done" to before.getValue("Done") + done)
        val live = browser.events("/home/live")

        val waiting = start()
        var html = live.until { "/runs/$waiting" in section(it, "waiting-on-you") }
        assertContains(section(html, "waiting-on-you"), "⚠ [stuck]")
        assertContains(section(html, "waiting-on-you"), "No tone given.")

        val working = start()
        html = live.until { "/runs/$working" in section(it, "working-now") }
        assertContains(section(html, "working-now"), "Draft")
        assertFalse("/runs/$waiting" in section(html, "working-now"))
        assertEquals(stats(2, 1, 0), stats(html))

        claude.release("release")
        html = live.until { "/runs/$working" !in section(it, "working-now") }
        assertEquals(stats(1, 1, 1), stats(html))
        assertEquals(stats(1, 1, 1), stats(browser.get("/").body))
    }

    @Test
    fun `Start a Run leads to a Process's start form`() {
        browser.import("agent-task.bpmn")
        val home = browser.get("/").body
        val link = assertNotNull(Regex("""<section id="start-a-run">.*?href="([^"]*agent-task)"""", RegexOption.DOT_MATCHES_ALL).find(home)).groupValues[1]
        assertContains(browser.get(link).body, """<form method="post" action="/processes/agent-task/runs">""")
    }
}
