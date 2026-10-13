package ants

import org.junit.jupiter.api.Test
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class HomeTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    private fun setUp() {
        agentTask(claude)
        browser.import("agent-task.bpmn")
    }

    private fun result(result: String) = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":$result}"""
    private val stuck = result("""{"outcome":"stuck","summary":"No tone given.","needs":"Which tone, dry or warm?"}""")
    private val done = result("""{"outcome":"done","summary":"Drafted.","output":{"text":"Hello"}}""")

    private fun start(): String {
        browser.get("/processes/agent-task")
        return browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    /** A card of Home, by id, up to the next card. */
    private fun section(html: String, id: String) = html.also { assertContains(it, """id="$id">""") }
        .substringAfter("""id="$id">""").substringBefore("""<div class="card" id=""").replace("&#39;", "'")

    /** Home's stat cards: each one's label and number, and the Runs ended beside Live Runs. */
    private fun stats(html: String) = Regex("""<div class="muted small">([^<]+)</div><div class="stat">(\d+) <small>([^<]*)</small></div>""")
        .findAll(section(html, "stats")).flatMap {
            listOfNotNull(it.groupValues[1] to it.groupValues[2].toInt(), Regex("""(\d+) ended""").find(it.groupValues[3])?.let { ended -> "ended" to ended.groupValues[1].toInt() })
        }.toMap()

    /** Waits for the live update in which [check] passes. */
    private fun BlockingQueue<String>.until(check: (String) -> Boolean): String {
        while (true) {
            val html = assertNotNull(poll(10, SECONDS), "no matching update within 10 seconds")
            if (check(html)) return html
        }
    }

    @Test
    fun `Home lists waiting entries and running Agent Tasks with their live line, and its stats count waiting Tasks, running Agent Tasks and live and ended Runs, live`() {
        setUp()
        claude.session("draft", stuck)
        claude.session("draft", 2, "wait release", done)
        val before = stats(browser.get("/").body)
        fun stats(waiting: Int, running: Int, live: Int, ended: Int) = mapOf(
            "Waiting on you" to before.getValue("Waiting on you") + waiting, "Agent Tasks running" to before.getValue("Agent Tasks running") + running,
            "Live Runs" to before.getValue("Live Runs") + live, "ended" to before.getValue("ended") + ended,
        )
        assertContains(section(browser.get("/").body, "stats"), "<small>of 3</small>")
        val live = browser.events("home", "home")

        val waiting = start()
        var html = live.until { "/runs/$waiting" in section(it, "waiting-on-you") }
        assertContains(section(html, "waiting-on-you"), """<span class="tag t-stuck"><i>⚠</i>[stuck]</span>""")
        assertContains(section(html, "waiting-on-you"), "No tone given.")
        assertContains(section(html, "waiting-on-you"), Regex("""<a class="small" href="/notifications">All \d+</a>"""))

        val working = start()
        html = live.until { "/runs/$working" in section(it, "working-now") }
        assertContains(section(html, "working-now"), Regex("""<b class="small">Draft</b><div class="small"><span class="spin">✻</span> <span class="shimmer">\w+…</span>"""))
        assertFalse("/runs/$waiting" in section(html, "working-now"))
        assertEquals(stats(1, 1, 2, 0), stats(html))

        claude.release("release")
        html = live.until { "/runs/$working" !in section(it, "working-now") }
        assertEquals(stats(1, 0, 1, 1), stats(html))
        assertEquals(stats(html), stats(browser.get("/").body))
    }

    @Test
    fun `Start a Run leads to a Process's start form, and a Process that can't start says so`() {
        setUp()
        browser.import("start-end.bpmn")
        val ready = section(browser.get("/").body, "start-a-run")
        val link = assertNotNull(Regex("""href="([^"]*agent-task)"""").find(ready)).groupValues[1]
        assertContains(browser.get(link).body, """<form method="post" action="/processes/agent-task/runs">""")
        assertFalse("Can't start" in ready)

        home.resolve("skills/draft").toFile().deleteRecursively()
        val home = section(browser.get("/").body, "start-a-run")
        assertContains(home, """<a class="grow" href="/processes/agent-task">Agent Task</a><span class="pill p-failed">Can't start</span>""")
        assertContains(home, """<a class="grow" href="/processes/start-end">Start to end</a></div>""")
    }
}
