package ants

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RunPageTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    private fun setUp(settings: String = "") = agentTask(claude, extra = settings)

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
        val live = browser.events("run=$run", "run")

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
    fun `Cancel the Run on a live Run's page ends it and kills its live Agent Tasks, keeping its Run workspace`() {
        setUp()
        claude.session("draft", "wait never", done)
        val run = start("agent-task")
        eventually { assertEquals(1, claude.launches("draft").size) }
        assertContains(browser.get("/runs/$run").body, "Cancel the Run")

        val cancelled = browser.post("/runs/$run/cancel", mapOf())
        assertEquals("/runs/$run", cancelled.path)
        assertContains(cancelled.body, """id="state">Cancelled<""")
        assertFalse("Cancel the Run" in cancelled.body)
        assertContains(cancelled.body, "until you dismiss the Run")
        eventually { assertTrue(claude.launches("draft").single().killed) }
        // The Task the Run was in the middle of reads Cancelled, not Done, in the table, on its page and on the diagram.
        assertContains(row(cancelled.body, "Draft"), """<span class="pill p-quiet">Cancelled</span>""")
        assertContains(browser.get("/runs/$run/tasks/draft/transcript").body, """<span class="pill p-quiet">Cancelled</span>""")
        val diagram = diagram(assertNotNull(browser.events("run=$run", "run").poll(10, SECONDS)))
        assertEquals(mapOf("state" to "cancelled", "badge" to "Cancelled", "round" to 1), diagram.element("draft"))
    }

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
        val drawer = browser.events("run=$run&drawer=draft", "drawer")
        var html = drawer.until { "dry" in it }
        assertContains(part(html, "drawer-head"), "Draft")
        assertContains(part(html, "drawer-head"), """<span class="shimmer">""") // the live line of a running Task
        assertContains(part(html, "drawer-output"), "<p>The notes say <strong>dry</strong>.</p>")
        assertFalse("First a look" in part(html, "drawer-output"))
        val tasks = part(html, "drawer-so-far")
        assertContains(tasks, """<div class="todo done">☑ Read the notes</div>""")
        assertContains(tasks, """<div class="todo now">◐ Write the draft</div>""")
        assertContains(part(html, "drawer-head"), """<a class="small" href="/runs/$run/tasks/draft/transcript">Full Transcript →</a>""")

        // The comment box of a running Task sends into its live session, and the user stays on the Run page.
        val send = part(html, "drawer-send")
        assertContains(send, """<form method="post" action="/runs/$run/tasks/draft/transcript/send">""")
        assertContains(send, """formaction="/runs/$run/tasks/draft/transcript/stop"""")
        browser.get("/runs/$run")
        assertEquals("/runs/$run", browser.post("/runs/$run/tasks/draft/transcript/send", mapOf("message" to "Keep it dry.", "back" to "run")).path)
        html = drawer.until { "done" in part(it, "drawer-head") }
        assertContains(part(html, "drawer-output"), "dry")
        assertFalse("drawer-send" in html)
        assertContains(claude.launches("draft").single().stdin, "Keep it dry.")
    }

    @Test
    fun `So far rewrites a running Task's new Transcript lines with haiku, outside the Task's context, and can be switched off`() {
        setUp("so-far: 1s\n")
        val looked = """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"Looked at the notes."}]}}"""
        claude.session("draft", looked, "wait release", done, "wait linger")
        claude.session(
            "so-far",
            """{"type":"result","subtype":"success","is_error":false,"result":"It read the notes.",""" +
                """"modelUsage":{"claude-haiku-5-5":{"inputTokens":40,"outputTokens":2,"cacheReadInputTokens":0,"cacheCreationInputTokens":0}}}""",
        )
        val run = start("agent-task")
        val html = browser.events("run=$run&drawer=draft", "drawer").until { "It read the notes." in it }
        assertContains(part(html, "drawer-so-far"), "<p>It read the notes.</p>")
        assertContains(part(html, "drawer-so-far"), Regex("""<span class="muted">updated <span data-since="\d+">\d+s</span> ago</span>""")) // ticking (shell.js)
        assertContains(part(html, "drawer-head"), "· 0 tokens") // haiku's rewrite is a session of its own
        val rewrite = claude.launches("so-far").first()
        assertEquals("haiku", rewrite.flag("--model"))
        assertEquals("", rewrite.flag("--tools"))
        assertFalse("ANTHROPIC_API_KEY" in rewrite.env)
        claude.release("release")
        Thread.sleep(2500) // with the session lingering after its Result, no second rewrite
        claude.release("linger")
        eventually { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        assertContains(browser.get("/runs/$run/tasks/draft/transcript").body, """<span id="tokens">0 tokens</span>""")

        setUp("so-far: false\n")
        claude.session("draft", 2, looked, "wait again", done)
        val rewrites = claude.launches("so-far").size
        val off = start("agent-task")
        browser.events("run=$off&drawer=draft", "drawer").until { "Looked at the notes." in it }
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
        browser.events("run=$run&drawer=draft", "drawer").until { "Looked." in it }
        Thread.sleep(2500)
        assertEquals(rewrites, claude.launches("so-far").size)
        claude.release("full")
        eventually { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
    }

    @Test
    fun `a waiting Task's drawer offers its action, and acting from it stays on the Run page`() {
        setUp()
        claude.session("draft", stuck)
        claude.session("draft", 2, done)
        val run = start("agent-task")
        val html = browser.events("run=$run&drawer=draft", "drawer").until { "t-stuck" in it }
        assertContains(part(html, "drawer-head"), """<span class="pill p-stuck">Stuck</span> <span class="tag t-stuck"><i>⚠</i>[stuck]</span>""")
        assertContains(part(html, "drawer-needs"), "<p>Which tone, dry or warm?</p>")
        val action = part(html, "drawer-action")
        assertContains(action, """<button class="primary" name="action" value="continue">Continue the session</button>""")
        assertContains(action, """<input type="hidden" name="back" value="run">""")
        val form = Regex("""<form method="post" action="(/notifications/[^"]+)">""").find(action)!!.groupValues[1]

        browser.get("/runs/$run")
        val after = browser.post(form, mapOf("action" to "continue", "instruction" to "Dry.", "back" to "run"))
        assertEquals("/runs/$run", after.path)
        eventually { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        assertEquals("Dry.", claude.launches("draft")[1].stdin.substringAfter("\"content\":\"").substringBefore('"'))
    }

    @Test
    fun `a Task's page heads its Transcript with its state, Agent and Skill and So far, and Send and Stop sit at the bottom`() {
        setUp()
        claude.session("draft", """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"Looking."}]}}""", "wait never", done)
        val run = start("agent-task")
        val path = "/runs/$run/tasks/draft/transcript"
        var page = ""
        eventually { page = browser.get(path).body.also { assertContains(it, "Looking.") } }
        assertContains(page, """<h1 data-flash="running">Draft <span class="pill p-running">Running</span></h1>""")
        assertContains(page, """<a href="/agents/writer">writer</a>""")
        assertContains(page, """<div class="sofar" id="sofar">""")
        val composer = assertNotNull(Regex("""<div class="composer">.*?</form>""", RegexOption.DOT_MATCHES_ALL).find(page)).value
        assertContains(composer, """<form method="post" action="$path/send">""")
        assertContains(composer, """formaction="$path/stop"""")
        assertContains(composer, ">Send</button>")
        assertContains(row(browser.get("/runs/$run").body, "Draft"), """<span class="shimmer"""") // the live line of a running Task
    }

    @Test
    fun `a Task waiting on its Round says so on its page`() {
        agentTask(claude, mapOf("quiz" to """{"type":"object","properties":{"questions":{"type":"array"}}}"""))
        claude.session("quiz", result("""{"outcome":"done","summary":"Asked.","output":{"questions":[{"id":"who","title":"Who","ask":"Who?","context":"Who.","recommendation":"Me.","why":"Mine."}]}}"""))
        val run = start("round")
        var entry = ""
        eventually { entry = entry(run) }
        val page = browser.get("/runs/$run/tasks/grill/transcript").body
        assertContains(page, """<span class="pill p-quiet">Waiting on its Round</span>""")
        assertContains(page, """This Task asked Round 1. <a href="$entry">Answer the Round</a> and its session resumes.""")
        assertContains(row(browser.get("/runs/$run").body, "Grill the idea"), "Waiting on its Round")
    }

    @Test
    fun `the Run page's Tasks table follows its Tasks live, marking a changed state to flash`() {
        setUp()
        claude.session("draft", "wait release", done)
        val run = start("agent-task")
        val tasks = browser.events("run=$run&tasks=$run", "tasks")
        var html = tasks.until { "<b>Draft</b>" in it && "shimmer" in it }
        assertContains(row(html, "Draft"), """<td data-flash="running"><span class="small"><span class="spin">✻</span>""")
        claude.release("release")
        html = tasks.until { "<b>Draft</b>" in it && "shimmer" !in it }
        assertContains(row(html, "Draft"), """<td data-flash="done"><span class="pill p-done">Done</span>""")
    }

    @Test
    fun `a toast says when a Round arrives and a queued Task takes the slot it freed`() {
        agentTask(claude, mapOf("quiz" to """{"type":"object","properties":{"questions":{"type":"array"}}}""", "draft" to null), extra = "agent-tasks: 1\n")
        claude.session("quiz", "wait ask", result("""{"outcome":"done","summary":"Asked.","output":{"questions":[{"id":"who","title":"Who","ask":"Who?","context":"Who.","recommendation":"Me.","why":"Mine."}]}}"""))
        claude.session("draft", "wait never")
        val toasts = browser.events("", "toast")
        val asking = start("round")
        eventually { assertEquals(1, claude.launches("quiz").size) }
        val queued = start("agent-task")
        Thread.sleep(1500) // the stream sees it queued
        claude.release("ask")
        val seen = StringBuilder()
        eventually { generateSequence { toasts.poll() }.forEach { seen.appendLine(it) }; assertContains(seen, "Its slot went to") }
        val names = browser.get("/runs").body
        val (asker, taker) = listOf(asking, queued).map { run -> Regex("""/runs/$run">([^<]+)<""").find(names)!!.groupValues[1] }
        val round = toastMarkup(
            Toast.Kind.WARNING, "New: $asker asks Round 1", "1 question is waiting on you. Its slot went to $taker · Draft, which was queued.", numbered = true,
        )
        // A page that follows again, as the Run page does when its drawer opens or closes, gets the toasts after the latest it showed.
        val seq = assertNotNull(round.find(seen), "no Round toast in $seen").groupValues[1].toLong()
        assertContains(assertNotNull(browser.events("toast=${seq - 1}", "toast").poll(10, SECONDS)), "asks Round 1")
    }

    /** The Run page's row of the Task named [name] in its Tasks table. */
    private fun row(page: String, name: String) = assertNotNull(
        Regex("""<tr class="click"[^>]*>(?:(?!</tr>).)*?<b>$name</b>(?:(?!</tr>).)*</tr>""", RegexOption.DOT_MATCHES_ALL).find(page), "no row $name in $page",
    ).value

    @Test
    fun `the Run page lists the Tasks it reached, each with its Agent and Skill, its state and context, opening the Task`() {
        agentTask(claude, mapOf("draft" to """{"type":"object","properties":{"notes":{"type":"string"}}}""", "polish" to null))
        claude.session(
            "draft",
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"Drafting."}],"usage":{"input_tokens":1200,"output_tokens":300}}}""",
            result("""{"outcome":"done","summary":"Drafted.","output":{"notes":"Hello"}}"""),
        )
        val run = start("approve")
        var entry = ""
        eventually { entry = entry(run) }
        val page = browser.get("/runs/$run").body
        val draft = row(page, "Draft")
        assertContains(draft, """data-href="/runs/$run/tasks/draft/transcript"""")
        assertContains(draft, """<a href="/agents/writer">writer</a>""")
        assertContains(draft, """<a href="/skills/draft">draft</a>""")
        assertContains(draft, """<span class="pill p-done">Done</span>""")
        assertContains(draft, "1.2k") // its context, the input of its latest call
        val check = row(page, "Check the draft")
        assertContains(check, """data-href="$entry"""")
        assertContains(check, "User Task")
        assertContains(check, """<span class="pill p-waiting">Waiting on you</span> <span class="tag t-approve"><i>✓</i>[approve]</span>""")
        assertFalse("<b>Polish</b>" in page) // not reached
        assertContains(page, """id="state">Running</span>""")
        assertContains(page, "The Report appears here when the Run ends.")
    }
}
