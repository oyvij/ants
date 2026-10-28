package ants

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.BlockingQueue
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    @BeforeEach
    fun setUp() {
        agentTask(claude)
    }

    private val init = """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}"""
    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"done","summary":"Drafted.","output":{"text":"Hello"}}}"""

    private fun start(process: String): String {
        browser.import("$process.bpmn")
        browser.get("/processes/$process")
        return browser.post("/processes/$process/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    private fun page(path: String) = browser.get(path).body.replace("&#34;", "\"").replace("&quot;", "\"")

    @Test
    fun `token counts read short, as they are under a thousand, then in k, then in M`() {
        assertEquals("999 tokens", tokens(999))
        assertEquals("1k tokens", tokens(1_000))
        assertEquals("1.5k tokens", tokens(1_537))
        assertEquals("134k tokens", tokens(133_998))
        assertEquals("1.3M tokens", tokens(1_333_998))
    }

    @Test
    fun `durations read as their two largest units, to the minute where seconds don't matter`() {
        assertEquals("0s", duration(java.time.Duration.ZERO))
        assertEquals("1m 5s", duration(java.time.Duration.ofSeconds(65)))
        assertEquals("1d 0h", duration(java.time.Duration.ofHours(24).plusMinutes(3)))
        assertEquals("3m", duration(java.time.Duration.ofSeconds(200), seconds = false))
        assertEquals("in 2h 1m", countdown(java.time.Duration.ofSeconds(7_250)))
    }

    @Test
    fun `the Transcript page shows the prompt collapsed, streamed markdown, one line per tool call, thinking collapsed and the Result`() {
        claude.session(
            "draft", init,
            """{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Let me **look**"}}}""",
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"Let me **look**."}]}}""",
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"thinking","thinking":"Which files?"}]}}""",
            """{"type":"assistant","message":{"id":"m2","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls -1","description":"List"}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"a.md"}]}}""",
            done,
        )
        val run = start("agent-task")
        eventually { assertContains(page("/runs/$run"), """id="state">Completed<""") }
        assertContains(page("/runs/$run"), """<a href="/runs/$run/tasks/draft/transcript">draft</a>""")

        val transcript = page("/runs/$run/tasks/draft/transcript") // the Run has ended, and its Transcript is still there
        val order = listOf(
            """<h3 class="launch" id="e0">Launch 1""",
            """<details class="prompt" id="e1"><summary>Opening prompt</summary><pre>/draft The workspace is """,
            """<p>Let me <strong>look</strong>.</p>""",
            """<details class="thinking" id="e3"><summary>✻ Thinking</summary>Which files?</details>""",
            """<div class="tool" id="e4"><b>Bash</b> <code>ls -1</code></div>""",
            """<div class="result" id="e5"><b>Result: done</b> Drafted.<pre>""",
            """<p class="ants" id="e6">Done.</p>""",
        )
        for (part in order) assertContains(transcript, part)
        assertEquals(order, order.sortedBy { transcript.indexOf(it) })
        assertContains(transcript, """"text" : "Hello"""")
        assertContains(transcript, """<div id="live"></div>""") // the streamed preview gives way to the whole message
    }

    @Test
    fun `the Transcript says which Skill the Agent invoked`() {
        claude.session(
            "draft", init,
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Skill","input":{"skill":"tdd","args":"red first"}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"Launching skill: tdd"}]}}""",
            done,
        )
        val run = start("agent-task")
        eventually { assertContains(page("/runs/$run"), """id="state">Completed<""") }
        assertContains(page("/runs/$run/tasks/draft/transcript"), """<div class="tool" id="e2">Used the Skill <b>tdd</b></div>""")
    }

    @Test
    fun `the Transcript shows a Protected Command's args joined with spaces`() {
        claude.session(
            "draft", init,
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"mcp__ants__run","input":{"folder":"repos/web","args":["gh","api","--jq",".files[]"]}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"Exit code 0"}]}}""",
            done,
        )
        val run = start("agent-task")
        eventually { assertContains(page("/runs/$run"), """id="state">Completed<""") }
        assertContains(page("/runs/$run/tasks/draft/transcript"), """<div class="tool" id="e2"><b>mcp__ants__run</b> <code>gh api --jq .files[]</code></div>""")
    }

    @Test
    fun `a second launch of a Task appends after a divider, and each launch and Task shows its session's context out of the model's window`() {
        // modelUsage adds up every call of the launch, cache re-reads included; it gives only the window.
        val usage = """{"m":{"inputTokens":1,"outputTokens":2,"cacheReadInputTokens":3000000,"cacheCreationInputTokens":4,"contextWindow":200000}}"""
        claude.session(
            "draft", init,
            """{"type":"assistant","message":{"id":"m0","content":[{"type":"text","text":"Reading."}],"usage":{"input_tokens":5,"cache_read_input_tokens":9000}}}""",
            // the context is the latest call's: its input, read from the cache or written to it; its output comes in the next
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"Hello from ${'$'}SESSION"}],""" +
                """"usage":{"input_tokens":7,"cache_read_input_tokens":30000,"cache_creation_input_tokens":3000,"output_tokens":900}}}""",
            done.replace(""""structured_output"""", """"modelUsage":$usage,"structured_output""""),
        )
        val run = start("agent-loop")
        eventually { assertContains(page("/runs/$run"), """id="state">Completed<""") }
        val sessions = claude.launches("draft").map { it.argv[it.argv.indexOf("--session-id") + 1] }
        assertEquals(3, sessions.size)

        val draft = page("/runs/$run/tasks/draft/transcript")
        val first = draft.indexOf("Launch 1 · ")
        val second = draft.indexOf("Launch 2 · ")
        assertTrue(first in 0..<second)
        assertEquals(1, sessions.count { draft.indexOf("Hello from $it") in first..second })
        assertEquals(1, sessions.count { draft.indexOf("Hello from $it") > second })
        assertEquals(2, Regex("""Launch \d · [\d: -]+ · 33k tokens</h3>""").findAll(draft).count())
        // The Task is its latest session: a fresh launch doesn't add to the one before.
        assertContains(draft, """<span id="tokens">33k tokens (16.5%)</span>""")

        val runPage = page("/runs/$run")
        assertContains(runPage, "<h2>Transcripts</h2>") // sessions' contexts don't add up
        assertContains(runPage, """<a href="/runs/$run/tasks/draft/transcript">draft</a> · 33k tokens (16.5%)""")
        assertContains(runPage, """<a href="/runs/$run/tasks/polish/transcript">polish</a> · 33k tokens (16.5%)""")
    }

    @Test
    fun `a running Task shows a spinner, an activity verb, elapsed time and its rising context, followed live over SSE`() {
        fun usage(input: Int) = """"usage":{"input_tokens":$input,"output_tokens":5,"cache_read_input_tokens":100,"cache_creation_input_tokens":0}"""
        claude.session(
            "draft", init,
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"notes.md"}}],${usage(10)}}}""",
            "wait one",
            """{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Almost **there**"}}}""",
            "wait two",
            """{"type":"assistant","message":{"id":"m2","content":[{"type":"text","text":"Almost **there**."}],${usage(110)}}}""",
            "wait three",
            done,
        )
        val run = start("agent-task")
        val path = "/runs/$run/tasks/draft/transcript"
        eventually { assertContains(page(path), "Reading notes.md…") }
        val loaded = page(path)
        assertContains(loaded, Regex("""<div id="status"><span class="spin">✻</span> <span class="shimmer">Reading notes.md…</span> <span class="muted num">\(<span data-since="\d+">\d+s</span> · 110 tokens\)</span></div>"""))
        assertContains(loaded, """<div class="tool" id="e2"><b>Read</b> <code>notes.md</code></div>""")

        val from = Regex("""data-from="(\d+)"""").find(loaded)!!.groupValues[1]
        val tab = Received(browser.events("transcript=$run/draft&from=$from", "transcript"))
        val secondTab = Received(browser.events("transcript=$run/draft&from=0", "transcript"))
        claude.release("one")
        eventually { assertContains(tab.toString(), """<div id="live" class="caret"><p>Almost <strong>there</strong></p>""") } // a caret while the block streams
        assertContains(tab.toString(), "Writing…")
        assertFalse("Read</b>" in tab.toString()) // this page had it already
        claude.release("two")
        eventually { assertContains(tab.toString(), Regex("""Writing…</span> <span class="muted num">\(<span data-since="\d+">\d+s</span> · 210 tokens\)</span></div>""")) }
        claude.release("three")
        eventually { assertContains(tab.toString(), """<div id="status"></div>""") }
        assertContains(tab.toString(), """<div class="result" id="e""")

        eventually { assertContains(secondTab.toString(), "<b>Result: done</b>") }
        for (text in listOf(page(path), secondTab.toString())) {
            assertContains(text, """<b>Read</b> <code>notes.md</code>""")
            assertContains(text, """<p>Almost <strong>there</strong>.</p>""")
            assertContains(text, "<b>Result: done</b> Drafted.")
        }
        fun unmasked() = page(path).replace(Regex("""name="_csrf" value="[^"]*""""), "") // the sidebar's token is masked anew each time
        assertEquals(unmasked(), unmasked())
    }

    @Test
    fun `Subagents show as a count, live chips with type, description and time, and nested blocks, their context apart from the Task's`() {
        fun spawn(id: String, type: String, description: String) =
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"$id","name":"Agent","input":""" +
                """{"description":"$description","prompt":"Look it up","subagent_type":"$type"}}],"usage":{"input_tokens":10}},""" +
                """"parent_tool_use_id":null,"timestamp":"2026-10-11T12:00:00Z"}"""
        fun started(task: String, id: String, type: String) =
            """{"type":"system","subtype":"task_started","task_id":"$task","tool_use_id":"$id","description":"x","subagent_type":"$type"}"""
        claude.session(
            "draft", init,
            spawn("tA", "searcher", "Search the notes"),
            started("a1", "tA", "searcher"),
            spawn("tB", "general-purpose", "Check facts"),
            started("b1", "tB", "general-purpose"),
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Find the tone"}]},"parent_tool_use_id":"tA","agent_id":"a1"}""",
            """{"type":"assistant","message":{"id":"s1","content":[{"type":"tool_use","id":"tR","name":"Read","input":{"file_path":"tone.md"}}],"usage":{"input_tokens":100}},"parent_tool_use_id":"tA","agent_id":"a1"}""",
            // without an agent_id, the parent_tool_use_id still finds the block
            """{"type":"assistant","message":{"id":"s2","content":[{"type":"text","text":"Facts **hold**."}],"usage":{"input_tokens":1000}},"parent_tool_use_id":"tB"}""",
            "wait one",
            """{"type":"system","subtype":"task_updated","task_id":"a1","patch":{"status":"completed","end_time":1791720007000}}""",
            """{"type":"system","subtype":"task_notification","task_id":"a1","tool_use_id":"tA","status":"completed","summary":"Warm."}""",
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"tA","type":"tool_result","content":"Warm."}]},"parent_tool_use_id":null}""",
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"tB","type":"tool_result","content":"Denied","is_error":true}]},"parent_tool_use_id":null,"timestamp":"2026-10-11T12:01:05Z"}""",
            done,
        )
        val run = start("agent-task")
        val path = "/runs/$run/tasks/draft/transcript"
        eventually { assertContains(page(path), "Facts <strong>hold</strong>.") }
        val running = page(path)
        assertContains(running, """<div id="subagents" class="chips"><span class="chip">""")
        assertContains(running, Regex("""<span class="chip"><span class="spin">✻</span> searcher · Search the notes · <span data-since="\d+">[\ddhms ]+</span></span>"""))
        assertContains(running, Regex("""<span class="chip"><span class="spin">✻</span> general-purpose · Check facts · <span data-since="\d+">[\ddhms ]+</span></span>"""))
        val searcher = Regex("""(?s)<details class="subagent" id="(e\d+)" open><summary id="\1s">⑂ <b>searcher</b> Search the notes · <span data-since""" +
            """.*?<div>(.*?)</div></details>""").find(running)!!.groupValues[2]
        assertContains(searcher, """<summary>Prompt</summary><pre>Find the tone</pre>""")
        assertContains(searcher, """<b>Read</b> <code>tone.md</code>""")
        assertFalse("Facts" in searcher)
        assertContains(running, Regex("""(?s)<details class="subagent" id="e\d+" open><summary id="e\d+s">⑂ <b>general-purpose</b> Check facts.*?Facts <strong>hold</strong>.*?</details>"""))
        assertContains(running, Regex("""<div id="status">.*· 10 tokens\)</span></div>""")) // each Subagent has a context of its own

        val tab = Received(browser.events("transcript=$run/draft&from=${Regex("""data-from="(\d+)"""").find(running)!!.groupValues[1]}", "transcript"))
        claude.release("one")
        eventually { assertContains(page(path), "<b>Result: done</b>") }
        val ended = page(path)
        assertContains(ended, """<div id="subagents" class="chips"></div>""") // no running Subagents, no chips
        assertContains(ended, """⑂ <b>searcher</b> Search the notes · 7s</summary>""")
        assertContains(ended, """⑂ <b>general-purpose</b> Check facts · 1m 5s · failed</summary>""")
        // The tab's events come as the stream sees the file grow: the searcher's end can come before the other's.
        eventually {
            val seen = tab.toString()
            assertContains(seen, """⑂ <b>searcher</b> Search the notes · 7s</summary>""")
            assertContains(seen, """<div id="subagents" class="chips"></div>""")
        }
    }

    @Test
    fun `the Transcript page's header follows the Task's state live, marking it to flash`() {
        claude.session("draft", init, "wait release", done)
        val run = start("agent-task")
        val head = browser.events("transcript=$run/draft&from=0", "taskhead")
        var html = head.until { "p-running" in it }
        assertContains(html, """<h1 data-flash="running">Draft <span class="pill p-running">Running</span>""")
        claude.release("release")
        html = head.until { "p-done" in it }
        assertContains(html, """<h1 data-flash="done">Draft <span class="pill p-done">Done</span>""")
        assertContains(html, """<div class="crumbs"><a href="/runs">Runs</a> / <a href="/runs/$run">""") // the whole header comes
    }

    @Test
    fun `the live line's verb says what the latest event does and to what, else it rotates`() {
        claude.session(
            "draft", init, "wait one",
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"pnpm test -- <b>theme</b>\nmore"}}]}}""",
            "wait two",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}""",
            "wait three",
            """{"type":"assistant","message":{"id":"m2","content":[{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"/w/repos/web/src/Header.tsx"}}]}}""",
            "wait four", done,
        )
        val run = start("agent-task")
        val path = "/runs/$run/tasks/draft/transcript"
        val rotating = Regex("""<div id="status"><span class="spin">✻</span> <span class="shimmer" data-rotate>Pondering…</span> """)
        eventually { assertContains(page(path), rotating) } // nothing done yet: a rotating verb
        claude.release("one")
        eventually { assertContains(page(path), """<span class="shimmer">Running pnpm test -- &lt;b&gt;theme&lt;/b&gt;…</span>""") }
        claude.release("two")
        eventually { assertContains(page(path), rotating) } // its result is in: rotating again
        claude.release("three")
        eventually { assertContains(page(path), """<span class="shimmer">Reading Header.tsx…</span>""") }
        claude.release("four")
    }

    @Test
    fun `a Subagent's own Subagent nests inside its block, and Subagents stay out of the Task's context once the launch ends`() {
        claude.session(
            "draft", init,
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"tA","name":"Agent","input":{"description":"Search","subagent_type":"searcher"}}],""" +
                """"usage":{"input_tokens":10}},"parent_tool_use_id":null}""",
            """{"type":"system","subtype":"task_started","task_id":"a1","tool_use_id":"tA","description":"Search","subagent_type":"searcher"}""",
            // with subagent-depth 2, the searcher starts a Subagent of its own
            """{"type":"assistant","message":{"id":"s1","content":[{"type":"tool_use","id":"tN","name":"Agent","input":{"description":"Dig","subagent_type":"Explore"}}],""" +
                """"usage":{"input_tokens":100}},"parent_tool_use_id":"tA","agent_id":"a1"}""",
            """{"type":"system","subtype":"task_started","task_id":"n1","tool_use_id":"tN","description":"Dig","subagent_type":"Explore"}""",
            """{"type":"assistant","message":{"id":"n1m","content":[{"type":"text","text":"Deep **find**."}],"usage":{"input_tokens":1000}},"parent_tool_use_id":"tN","agent_id":"n1"}""",
            "wait one",
            """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Drafted.","output":{}},""" +
                """"modelUsage":{"claude-haiku-5-5":{"inputTokens":1200,"outputTokens":30},"claude-sonnet-5-5":{"inputTokens":300,"outputTokens":7}}}""",
        )
        val run = start("agent-task")
        val path = "/runs/$run/tasks/draft/transcript"
        eventually { assertContains(page(path), "Deep <strong>find</strong>.") }
        val running = page(path)
        assertContains(
            running,
            Regex("""(?s)<details class="subagent" id="(e\d+)" open><summary id="\1s">⑂ <b>searcher</b>.*?</summary><div><details class="subagent" id="(e\d+)" data-in="\1" open>""" +
                """<summary id="\2s">⑂ <b>Explore</b> Dig.*?</summary><div><div class="text" id="e\d+" data-in="\2"><p>Deep <strong>find</strong>.</p>"""),
        )
        assertContains(running, Regex("""<div id="status">.*· 10 tokens\)</span></div>"""))

        claude.release("one")
        eventually { assertContains(page(path), "<b>Result: done</b>") }
        assertContains(page(path), """<span id="tokens">10 tokens</span>""") // no contextWindow in modelUsage, so no share of it
        assertContains(page("/runs/$run"), """<a href="/runs/$run/tasks/draft/transcript">draft</a> · 10 tokens<""")
    }
}

/** Every data line an SSE stream has sent so far, one per line. */
private class Received(private val events: BlockingQueue<String>) {
    private val text = StringBuilder()

    override fun toString() = text.apply { generateSequence { events.poll() }.forEach { appendLine(it) } }.toString()
}
