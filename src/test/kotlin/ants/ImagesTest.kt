package ants

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** An image pasted into the start form's prompt or a Send: stored under `images/`, referenced from the text, given to claude as an image block. */
class ImagesTest : AntsTest() {
    private val browser by lazy { browser().also { it.login(); it.get("/processes") } }
    private val claude = FakeClaude()
    private val json = JsonMapper.shared()

    @BeforeEach
    fun setUp() {
        agentTask(claude)
    }

    private val init = """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}"""
    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"done","summary":"Drafted.","output":{"text":"Hello"}}}"""

    private fun stored() = home.resolve("images").takeIf { it.isDirectory() }?.listDirectoryEntries()?.size ?: 0

    private fun start(prompt: String): String {
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        return browser.post("/processes/agent-task/runs", mapOf("prompt" to prompt)).path.removePrefix("/runs/")
    }

    /** The content blocks of the [n]th user message a launch read on stdin. */
    private fun blocks(stdin: String, n: Int = 0): List<Map<*, *>> =
        ((json.readValue(stdin.lines()[n], Map::class.java)["message"] as Map<*, *>)["content"] as List<*>).map { it as Map<*, *> }

    @Test
    fun `an uploaded image is stored under its hash and served back, and the reply is the path to reference`() {
        val upload = browser.postBytes("/images", RED_PNG)
        assertEquals(200, upload.status)
        assertEquals(RED_PATH, upload.body)
        val served = browser.get(RED_PATH)
        assertEquals(200, served.status)
        assertEquals("image/png", served.headers!!.firstValue("Content-Type").get())
    }

    @Test
    fun `anything but an image, or an image over the cap, is refused with a message and nothing is stored`() {
        val before = stored()
        val text = browser.postBytes("/images", "just text".toByteArray())
        assertEquals(400, text.status)
        assertEquals("Only an image can be pasted: PNG, JPEG, GIF or WebP.", text.body)
        val big = browser.postBytes("/images", RED_PNG + ByteArray(4_000_000))
        assertEquals(400, big.status)
        assertEquals("The image is over the 3.75 MB an Agent takes.", big.body)
        assertEquals(before, stored())
        assertEquals(404, browser.get("/images/nope.txt").status)
    }

    @Test
    fun `a Run whose prompt references an image gives it to claude as an image block beside the text`() {
        browser.postBytes("/images", RED_PNG)
        claude.session("draft", init, done)
        val run = start("Make it look like this: ![image]($RED_PATH)")
        eventually { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        val (image, text) = blocks(claude.launches("draft").single().stdin)
        assertEquals(mapOf("type" to "base64", "media_type" to "image/png", "data" to Base64.getEncoder().encodeToString(RED_PNG)), image["source"])
        assertEquals("image", image["type"])
        assertContains(text["text"].toString(), "/draft The workspace is ")
        assertContains(text["text"].toString(), "Make it look like this: ![image]($RED_PATH)")
    }

    @Test
    fun `an image in a Sent message reaches the live session with its text, and the Transcript shows it`() {
        browser.postBytes("/images", RED_PNG)
        claude.session("draft", init, "read", "wait release", done)
        val run = start("Say hello")
        val transcript = "/runs/$run/tasks/draft/transcript"
        eventually { assertContains(browser.get(transcript).body, """<button class="primary" data-pending="Sending…">Send</button>""") }
        browser.post("$transcript/send", mapOf("message" to "Like this: ![image]($RED_PATH)"))
        eventually { assertContains(browser.get(transcript).body, """› Like this: <img src="$RED_PATH" alt="image"></p>""") }
        assertContains(workspace(run).resolve("run-log.md").readText(), "Like this: ![image]($RED_PATH)\n")
        claude.release("release")
        eventually { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        val (image, text) = blocks(claude.launches("draft").single().stdin, 1)
        assertEquals("image", image["type"])
        assertEquals("Like this: ![image]($RED_PATH)", text["text"])
    }

    @Test
    fun `a reference to an image that isn't stored stays text in the Transcript`() {
        claude.session("draft", init, done)
        val missing = "/images/${"0".repeat(64)}.png"
        val run = start("Like this: ![image]($missing)")
        eventually { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        val transcript = browser.get("/runs/$run/tasks/draft/transcript").body
        assertContains(transcript, "![image]($missing)")
        assertEquals(false, "<img" in transcript)
    }

    @Test
    fun `a message without an image stays plain text`() {
        claude.session("draft", init, done)
        val run = start("Say hello")
        eventually { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        assertContains(claude.launches("draft").single().stdin, """"content":"/draft The workspace is """)
    }

    companion object {
        /** A 32×32 red PNG. */
        val RED_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAKElEQVR4nO3NsQ0AAAzCMP5/un0CNkuZ41wybXsHAAAAAAAAAAAAxR4yw/wuPL6QkAAAAABJRU5ErkJggg==",
        )
        const val RED_PATH = "/images/becf0cf30d6978325d9ccdea315923e2dbbf7e591c2857b92b5f82e41957bb98.png"
    }
}
