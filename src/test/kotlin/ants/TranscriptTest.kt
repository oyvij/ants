package ants

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.BlockingQueue
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    @BeforeEach
    fun setUp() {
        home.resolve("settings.yaml").writeText("claude:\n  command: [${claude.command}]\n")
        home.resolve("agents").createDirectories().resolve("writer.md").writeText("Write plainly.\n")
        home.resolve("skills/draft").createDirectories().resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts a text.\n---\nDraft the text.\n")
        home.resolve("skills/draft/result.schema.json").toFile().delete()
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
        eventually { assertContains(page("/runs/$run"), "State: Completed") }
        assertContains(page("/runs/$run"), """<a href="/runs/$run/tasks/draft/transcript">draft</a>""")

        val transcript = page("/runs/$run/tasks/draft/transcript") // the Run has ended, and its Transcript is still there
        val order = listOf(
            """<h3 class="launch" id="e0">Launch 1""",
            """<details class="prompt" id="e1"><summary>Prompt</summary><pre>Follow the Skill in ${home.resolve("skills/draft/SKILL.md")}""",
            """<p>Let me <strong>look</strong>.</p>""",
            """<details class="thinking" id="e3"><summary>✻ Thinking</summary>Which files?</details>""",
            """<div class="tool" id="e4">⏺ <b>Bash</b> <code>ls -1</code></div>""",
            """<div class="result" id="e5"><b>Result: done</b> Drafted.<pre>""",
            """<p class="ants" id="e6">Done.</p>""",
        )
        for (part in order) assertContains(transcript, part)
        assertEquals(order, order.sortedBy { transcript.indexOf(it) })
        assertContains(transcript, """"text" : "Hello"""")
        assertContains(transcript, """<div id="live"></div>""") // the streamed preview gives way to the whole message
    }

    @Test
    fun `a second launch of a Task appends after a divider, and tokens from modelUsage add up per launch, Task and Run`() {
        val usage = """{"m":{"inputTokens":1,"outputTokens":2,"cacheReadInputTokens":3,"cacheCreationInputTokens":4},""" +
            """"n":{"inputTokens":10,"outputTokens":20,"cacheReadInputTokens":30,"cacheCreationInputTokens":40}}"""
        claude.session(
            "draft", init,
            // modelUsage, which counts Subagents too, outranks the messages' own usage
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"Hello from ${'$'}SESSION"}],"usage":{"input_tokens":7}}}""",
            done.replace(""""structured_output"""", """"modelUsage":$usage,"structured_output""""),
        )
        val run = start("agent-loop")
        eventually { assertContains(page("/runs/$run"), "State: Completed") }
        val sessions = claude.launches("draft").map { it.argv[it.argv.indexOf("--session-id") + 1] }
        assertEquals(3, sessions.size)

        val draft = page("/runs/$run/tasks/draft/transcript")
        val first = draft.indexOf("Launch 1 · ")
        val second = draft.indexOf("Launch 2 · ")
        assertTrue(first in 0..<second)
        assertEquals(1, sessions.count { draft.indexOf("Hello from $it") in first..second })
        assertEquals(1, sessions.count { draft.indexOf("Hello from $it") > second })
        assertEquals(2, Regex("""Launch \d · [\d: -]+ · 110 tokens</h3>""").findAll(draft).count())
        assertContains(draft, """<span id="tokens">220 tokens</span>""")

        val runPage = page("/runs/$run")
        assertContains(runPage, "<h2>Transcripts · 330 tokens</h2>")
        assertContains(runPage, """<a href="/runs/$run/tasks/draft/transcript">draft</a> · 220 tokens""")
        assertContains(runPage, """<a href="/runs/$run/tasks/polish/transcript">polish</a> · 110 tokens""")
    }

    @Test
    fun `a running Task shows a spinner, an activity verb, elapsed time and rising tokens, followed live over SSE`() {
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
        eventually { assertContains(page(path), "Reading…") }
        val loaded = page(path)
        assertContains(loaded, Regex("""<div id="status"><span class="spinner">✻</span> Reading… <span data-since="\d+">\d+s</span> · 115 tokens</div>"""))
        assertContains(loaded, """<div class="tool" id="e2">⏺ <b>Read</b> <code>notes.md</code></div>""")

        val from = Regex("""data-from="(\d+)"""").find(loaded)!!.groupValues[1]
        val tab = Received(browser.events("$path/events?from=$from"))
        val secondTab = Received(browser.events("$path/events?from=0"))
        claude.release("one")
        eventually { assertContains(tab.toString(), """<div id="live"><p>Almost <strong>there</strong></p>""") }
        assertContains(tab.toString(), "Writing…")
        assertFalse("Read</b>" in tab.toString()) // this page had it already
        claude.release("two")
        eventually { assertContains(tab.toString(), Regex("""Writing… <span data-since="\d+">\d+s</span> · 330 tokens</div>""")) }
        claude.release("three")
        eventually { assertContains(tab.toString(), """<div id="status"></div>""") }
        assertContains(tab.toString(), """<div class="result" id="e""")

        eventually { assertContains(secondTab.toString(), "<b>Result: done</b>") }
        for (text in listOf(page(path), secondTab.toString())) {
            assertContains(text, """⏺ <b>Read</b> <code>notes.md</code>""")
            assertContains(text, """<p>Almost <strong>there</strong>.</p>""")
            assertContains(text, "<b>Result: done</b> Drafted.")
        }
        assertEquals(page(path), page(path))
    }
}

/** Every data line an SSE stream has sent so far, one per line. */
private class Received(private val events: BlockingQueue<String>) {
    private val text = StringBuilder()

    override fun toString() = text.apply { generateSequence { events.poll() }.forEach { appendLine(it) } }.toString()
}
