package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SubagentTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()
    private val agents = home.resolve("agents")
    private val written = listOf("searcher", "reviewer", "private", "codex-helper", "lead").map { agents.resolve("$it.md") }

    @BeforeEach
    fun setUp() {
        settings()
        agents.createDirectories()
        home.resolve("skills/draft").createDirectories().resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts a text.\n---\nDraft the text.\n")
        home.resolve("skills/draft/result.schema.json").deleteIfExists()
    }

    @AfterEach
    fun tearDown() {
        written.forEach { it.deleteIfExists() }
        agents.resolve("writer.md").deleteIfExists()
    }

    private fun settings(extra: String = "") = home.resolve("settings.yaml").writeText("claude:\n  command: [${claude.command}]\n$vault$extra")

    private fun agent(name: String, frontmatter: String, body: String = "Help.") =
        agents.resolve("$name.md").writeText("---\nname: $name\nskills: draft\n$frontmatter---\n$body\n")

    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"done","summary":"Drafted.","output":{}}}"""

    private fun start(): String {
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        return browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    private fun launch(): FakeClaude.Launch {
        claude.session("draft", done)
        val run = start()
        eventually { assertContains(browser.get("/runs/$run").body, "State: Completed") }
        return claude.launches("draft").single()
    }

    @Test
    fun `only offered claude Agents are passed through --agents, and the depth comes from settings`() {
        agent("writer", "tools: Read, Agent\n", "Write plainly.")
        agent("searcher", "description: Searches notes.\ntools: Read, Grep, Bash(git log *)\nmodel: haiku\nants-subagent: true\n", "Search the notes.")
        agent("reviewer", "description: Reviews.\nants-subagent: true\n", "Review.")
        agent("private", "description: Not offered.\n")
        agent("codex-helper", "description: Runs codex.\ncli: codex\nants-subagent: true\n")
        settings("subagent-depth: 2\n")

        val launch = launch()
        val offered = JsonMapper.shared().readValue(launch.flag("--agents"), Map::class.java)
        assertEquals(mapOf("description" to "Searches notes.", "prompt" to "Search the notes.", "tools" to listOf("Read", "Grep", "Bash(git log *)"), "model" to "haiku"), offered["searcher"])
        assertEquals(mapOf("description" to "Reviews.", "prompt" to "Review."), offered["reviewer"])
        assertFalse("private" in offered)
        assertFalse("codex-helper" in offered)
        assertNull(launch.flag("--disallowedTools")) // the Agent tool without a Limit starts any type
        assertEquals("2", launch.env["CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH"])
    }

    @Test
    fun `an Agent without the Agent tool, such as one with the default tools, gets no Subagent types`() {
        agent("writer", "description: Writes.\n", "Write plainly.")
        agent("searcher", "description: Searches.\nants-subagent: true\n")
        val launch = launch()
        assertNull(launch.flag("--agents"))
        assertFalse("Agent" in launch.flag("--tools")!!.split(","))
    }

    @Test
    fun `the Agent tool's Limit denies every other Subagent type`() {
        agent("writer", "tools: Read, Agent(searcher)\n", "Write plainly.")
        agent("searcher", "description: Searches.\nants-subagent: true\n")
        agent("reviewer", "description: Reviews.\nants-subagent: true\n")

        val launch = launch()
        assertEquals("Read,Agent", launch.flag("--tools"))
        val denied = launch.flag("--disallowedTools")!!.split(",")
        for (type in listOf("Agent(reviewer)", "Agent(general-purpose)", "Agent(Explore)", "Agent(Plan)")) assertContains(denied, type)
        assertFalse("Agent(searcher)" in denied)
        assertEquals("1", launch.env["CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH"])
    }
}
