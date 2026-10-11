package ants

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class RunPageTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    private fun setUp(settings: String = "") {
        home.resolve("settings.yaml").writeText("claude:\n  command: [${claude.command}]\n$vault$settings")
        home.resolve("agents").createDirectories().resolve("writer.md").writeText("---\nskills: draft\n---\nWrite plainly.\n")
        val skill = home.resolve("skills/draft").createDirectories()
        skill.resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts a text.\n---\nDraft the text.\n")
        skill.resolve("result.schema.json").deleteIfExists()
    }

    private fun result(result: String) = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":$result}"""
    private val stuck = result("""{"outcome":"stuck","summary":"No tone given.","needs":"Which tone, dry or warm?"}""")
    private val done = result("""{"outcome":"done","summary":"Drafted.","output":{"text":"Hello"}}""")

    private fun start(process: String): String {
        browser.import("$process.bpmn")
        browser.get("/processes/$process")
        return browser.post("/processes/$process/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    /** The Run's entry on the Notification list. */
    private fun entry(run: String) = assertNotNull(
        Regex("""/runs/$run".*?href="(/notifications/[^"]+)"""").find(browser.get("/notifications").body), "no entry for $run",
    ).groupValues[1]

    /** Waits for the update in which [check] passes. */
    private fun <T> BlockingQueue<String>.until(read: (String) -> T, check: (T) -> Boolean): T {
        while (true) {
            val update = read(assertNotNull(poll(10, SECONDS), "no matching update within 10 seconds"))
            if (check(update)) return update
        }
    }

    private fun BlockingQueue<String>.until(check: (String) -> Boolean) = until({ it.replace("&#34;", "\"") }, check)

    /** One live diagram update: the Run's state, and each element's state, badge and Round. */
    private fun diagram(data: String): Map<*, *> = JsonMapper.shared().readValue(data, Map::class.java)

    private fun Map<*, *>.element(id: String) = (this["elements"] as Map<*, *>)[id] as? Map<*, *>

    @Test
    fun `the live diagram colours Tasks by state, with badges and Round numbers, over SSE`() {
        setUp()
        claude.session("draft", 1, "wait first", done)
        claude.session("draft", 2, stuck)
        claude.session("draft", done)
        val run = start("agent-loop")
        assertContains(browser.get("/runs/$run").body, """<div id="diagram"""")
        assertContains(browser.get("/runs/$run/diagram.bpmn").body, """<bpmn:serviceTask id="polish"""")
        val live = browser.events("/runs/$run/live")

        var update = live.until(::diagram) { it.element("draft")?.get("state") == "running" }
        assertEquals(mapOf("state" to "running", "badge" to null, "round" to 1), update.element("draft"))
        assertEquals("done", update.element("start")?.get("state"))
        assertEquals(null, update.element("polish"))
        assertEquals("Running", update["state"])

        claude.release("first")
        update = live.until(::diagram) { it.element("draft")?.get("state") == "waiting" }
        assertEquals(mapOf("state" to "waiting", "badge" to "⚠ [stuck]", "round" to 2), update.element("draft"))

        browser.post(entry(run), mapOf("action" to "continue", "instruction" to "Dry."))
        update = live.until(::diagram) { it["state"] == "Completed" }
        assertEquals(mapOf("state" to "done", "badge" to null, "round" to 2), update.element("draft"))
        assertEquals(mapOf("state" to "done", "badge" to null, "round" to 1), update.element("polish"))
    }

    /** The drawer's part [id], from one drawer update. */
    private fun part(html: String, id: String) =
        assertNotNull(Regex("""<(\w+) id="$id"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL).find(html), "no part $id in $html").groupValues[2]

    @Test
    fun `a Task's drawer shows the latest output and the Agent's checklist, live`() {
        setUp()
        fun tool(id: String, name: String, input: String) =
            """{"type":"assistant","message":{"id":"m$id","content":[{"type":"tool_use","id":"t$id","name":"$name","input":$input}]}}"""
        // The task-list tools as claude 2.1.295 plays them: TaskCreate's id arrives in the tool result's tool_use_result.
        fun created(id: String, task: String, subject: String) =
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t$id","content":"Task #$task created successfully: $subject"}]},""" +
                """"tool_use_result":{"task":{"id":"$task","subject":"$subject"}}}"""
        claude.session(
            "draft",
            tool("1", "TaskCreate", """{"subject":"Read the notes","description":"Read them."}"""), created("1", "1", "Read the notes"),
            tool("2", "TaskCreate", """{"subject":"Write the draft","description":"Write it."}"""), created("2", "2", "Write the draft"),
            tool("3", "TaskUpdate", """{"taskId":"1","status":"completed"}"""),
            tool("4", "TaskUpdate", """{"taskId":"2","status":"in_progress"}"""),
            """{"type":"assistant","message":{"id":"m5","content":[{"type":"text","text":"First a look."}]}}""",
            """{"type":"assistant","message":{"id":"m6","content":[{"type":"text","text":"The notes say **dry**."}]}}""",
            "read", done,
        )
        val run = start("agent-task")
        val drawer = browser.events("/runs/$run/tasks/draft/drawer")
        var html = drawer.until { "dry" in it }
        assertContains(part(html, "drawer-head"), "Draft")
        assertContains(part(html, "drawer-head"), "running")
        assertContains(part(html, "drawer-output"), "<p>The notes say <strong>dry</strong>.</p>")
        assertFalse("First a look" in part(html, "drawer-output"))
        val tasks = part(html, "drawer-checklist")
        assertContains(tasks, """<li class="completed">Read the notes</li>""")
        assertContains(tasks, """<li class="in_progress">Write the draft</li>""")
        assertContains(part(html, "drawer-links"), """href="/runs/$run/tasks/draft/transcript"""")

        // The comment box of a running Task sends into its live session, and the user stays on the Run page.
        val send = part(html, "drawer-send")
        assertContains(send, """<form method="post" action="/runs/$run/tasks/draft/transcript/send">""")
        assertContains(send, """<form method="post" action="/runs/$run/tasks/draft/transcript/stop">""")
        browser.get("/runs/$run")
        assertEquals("/runs/$run", browser.post("/runs/$run/tasks/draft/transcript/send", mapOf("message" to "Keep it dry.", "back" to "run")).path)
        html = drawer.until { "done" in part(it, "drawer-head") }
        assertContains(part(html, "drawer-output"), "dry")
        assertFalse("drawer-send" in html)
        assertContains(claude.launches("draft").single().stdin, "Keep it dry.")
    }

    @Test
    fun `So far rewrites a running Task's new Transcript lines with haiku, counts on the Task, and can be switched off`() {
        setUp("so-far: 1s\n")
        val looked = """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"Looked at the notes."}]}}"""
        claude.session("draft", looked, "wait release", done)
        claude.session(
            "so-far",
            """{"type":"result","subtype":"success","is_error":false,"result":"It read the notes.",""" +
                """"modelUsage":{"claude-haiku-5-5":{"inputTokens":40,"outputTokens":2,"cacheReadInputTokens":0,"cacheCreationInputTokens":0}}}""",
        )
        val run = start("agent-task")
        val html = browser.events("/runs/$run/tasks/draft/drawer").until { "It read the notes." in it }
        assertContains(part(html, "drawer-so-far"), "<p>It read the notes.</p>")
        assertContains(part(html, "drawer-head"), "42 tokens")
        val rewrite = claude.launches("so-far").first()
        assertEquals("haiku", rewrite.flag("--model"))
        assertEquals("", rewrite.flag("--tools"))
        assertFalse("ANTHROPIC_API_KEY" in rewrite.env)
        claude.release("release")
        eventually { assertContains(browser.get("/runs/$run").body, "State: Completed") }
        assertContains(browser.get("/runs/$run/tasks/draft/transcript").body, """<span id="tokens">42 tokens</span>""")

        setUp("so-far: false\n")
        claude.session("draft", 2, looked, "wait again", done)
        val rewrites = claude.launches("so-far").size
        val off = start("agent-task")
        browser.events("/runs/$off/tasks/draft/drawer").until { "Looked at the notes." in it }
        Thread.sleep(2500)
        assertEquals(rewrites, claude.launches("so-far").size)
        assertContains(browser.get("/settings").body, """<input type="checkbox" name="so-far">""") // unticked
        claude.release("again")
    }

    @Test
    fun `So far takes a launch slot, so with every slot taken it waits`() {
        setUp("agent-tasks: 1\nso-far: 1s\n")
        claude.session("draft", """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"Looked."}]}}""", "wait full", done)
        val rewrites = claude.launches("so-far").size
        val run = start("agent-task")
        browser.events("/runs/$run/tasks/draft/drawer").until { "Looked." in it }
        Thread.sleep(2500)
        assertEquals(rewrites, claude.launches("so-far").size)
        claude.release("full")
        eventually { assertContains(browser.get("/runs/$run").body, "State: Completed") }
    }

    @Test
    fun `a waiting Task's drawer offers its action, and acting from it stays on the Run page`() {
        setUp()
        claude.session("draft", stuck)
        claude.session("draft", 2, done)
        val run = start("agent-task")
        val html = browser.events("/runs/$run/tasks/draft/drawer").until { "[stuck]" in it }
        assertContains(part(html, "drawer-head"), "⚠ [stuck] No tone given.")
        val action = part(html, "drawer-action")
        assertContains(action, "Which tone, dry or warm?")
        assertContains(action, """<button name="action" value="continue">Continue the session</button>""")
        assertContains(action, """<input type="hidden" name="back" value="run">""")
        val form = Regex("""<form method="post" action="(/notifications/[^"]+)">""").find(action)!!.groupValues[1]

        browser.get("/runs/$run")
        val after = browser.post(form, mapOf("action" to "continue", "instruction" to "Dry.", "back" to "run"))
        assertEquals("/runs/$run", after.path)
        eventually { assertContains(browser.get("/runs/$run").body, "State: Completed") }
        assertEquals("Dry.", claude.launches("draft")[1].stdin.substringAfter("\"content\":\"").substringBefore('"'))
    }
}
