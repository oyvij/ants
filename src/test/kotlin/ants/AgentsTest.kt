package ants

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val agents = home.resolve("agents").createDirectories()

    /** The page, with JTE's escaped quotes read back. */
    private fun get(path: String) = browser.get(path).body.replace("&#34;", "\"")

    private fun post(path: String, form: Map<String, String>): Browser.Page {
        browser.get(path)
        return browser.post(path, form)
    }

    private val reviewer = mapOf(
        "name" to "reviewer",
        "description" to "Reviews changes.",
        "cli" to "claude",
        "skills" to "code-review, tdd",
        "tool.Read" to "on",
        "tool.Grep" to "on",
        "tool.Bash" to "on",
        "limit.Bash" to "git log *, git diff *",
        "other-tools" to "mcp__github__get_issue",
        "model" to "claude-sonnet-5-5",
        "instructions" to "Review carefully.",
    )

    private val reviewerFile = """
        ---
        name: reviewer
        description: Reviews changes.
        cli: claude
        skills: code-review, tdd
        tools: Bash(git log *), Bash(git diff *), Grep, Read, mcp__github__get_issue
        model: claude-sonnet-5-5
        ---
        Review carefully.

        """.trimIndent()

    @Test
    fun `creating and editing an Agent writes the agent file, and the form round-trips it`() {
        agents.resolve("reviewer.md").deleteIfExists()
        val created = post("/agents/new", reviewer)
        assertEquals("/agents/reviewer", created.path)
        assertEquals(reviewerFile, agents.resolve("reviewer.md").readText())

        val page = get("/agents/reviewer")
        assertContains(page, """name="description" value="Reviews changes."""")
        assertContains(page, """name="tool.Bash" checked""")
        assertContains(page, """name="limit.Bash" value="git log *"""") // one field per rule, with × and ＋ Add
        assertContains(page, """name="limit.Bash" value="git diff *"""")
        assertContains(page, """<input type="checkbox" name="other-tools" value="mcp__github__get_issue" checked>""")
        assertContains(page, "An MCP tool. Ants starts Agents with MCP off")
        assertContains(page, """<input type="hidden" name="skills" value="code-review">""") // a chip with ×
        assertContains(page, """value="claude-sonnet-5-5" selected""")
        assertContains(page, ">Review carefully.</textarea>")

        post("/agents/reviewer", reviewer)
        assertEquals(reviewerFile, agents.resolve("reviewer.md").readText())

        // As the page posts it: a field per rule, chip and ticked tool, the empty ones left blank.
        browser.post("/agents/reviewer", reviewer.toList().filter { it.first !in setOf("skills", "limit.Bash", "other-tools") } + listOf(
            "skills" to "code-review", "skills" to "tdd", "skills" to "",
            "limit.Bash" to "git log *", "limit.Bash" to "", "limit.Bash" to "git diff *",
            "other-tools" to "mcp__github__get_issue", "other-tools" to "",
        ))
        assertEquals(reviewerFile, agents.resolve("reviewer.md").readText())

        post("/agents/reviewer", reviewer + ("model" to "other") + ("model-other" to "claude-opus-5-5[1m]") + ("instructions" to "Review harder."))
        val edited = agents.resolve("reviewer.md").readText()
        assertContains(edited, "model: claude-opus-5-5[1m]\n")
        assertContains(edited, "---\nReview harder.\n")
        assertContains(get("/agents/reviewer"), """name="model-other" value="claude-opus-5-5[1m]"""")
    }

    @Test
    fun `a hand-written agent file shows on the form, its limits under Limit and its other keys kept`() {
        agents.resolve("seeker.md").writeText("---\nname: seeker\ncolor: blue\ntools: Agent(knowledge-searcher, worker), Read\n---\nScout.\n")
        val page = get("/agents/seeker")
        assertContains(page, """name="tool.Agent" checked""")
        assertContains(page, """name="limit.Agent" value="knowledge-searcher"""")
        assertContains(page, """name="limit.Agent" value="worker"""")
        post("/agents/seeker", mapOf("name" to "seeker", "cli" to "claude", "tool.Read" to "on", "instructions" to "Scout."))
        assertEquals("---\nname: seeker\ncli: claude\ntools: Read\ncolor: blue\n---\nScout.\n", agents.resolve("seeker.md").readText())
    }

    /** Runs [check] with [folder] as an extra Agent folder in settings. */
    private fun withExtraFolder(folder: Path, check: () -> Unit) {
        val settings = home.resolve("settings.yaml")
        val before = settings.readText()
        settings.writeText("agent-folders: [$folder]\n")
        try {
            check()
        } finally {
            settings.writeText(before)
        }
    }

    @Test
    fun `shipped Agents and Agents from extra folders are read-only, and Copy makes an editable one`() {
        val extra = Files.createTempDirectory("extra-agents")
        extra.resolve("outsider.md").writeText("---\nname: outsider\ndescription: From Varde.\ncolor: red\n---\nLook around.\n")
        agents.resolve("outsider-copy.md").deleteIfExists()
        agents.resolve("shipped-writer-copy.md").deleteIfExists()
        withExtraFolder(extra) {
            val list = get("/agents")
            assertContains(list, """<a href="/agents/shipped-writer">shipped-writer</a>""")
            assertContains(list, """<a href="/agents/outsider">outsider</a>""")
            for (name in listOf("shipped-writer", "outsider")) {
                val page = get("/agents/$name")
                assertContains(page, "<fieldset disabled>")
                assertFalse("""<button class="primary" form="agent">Save</button>""" in page)
                assertEquals(403, post("/agents/$name", mapOf("name" to name, "instructions" to "Changed.")).status)
            }
            assertEquals("/agents/outsider-copy", post("/agents/outsider/copy", emptyMap()).path)
            assertEquals("---\nname: outsider-copy\ndescription: From Varde.\ncli: claude\ntools: ${AgentLauncher.DEFAULT_TOOLS.joinToString(", ")}\ncolor: red\n---\nLook around.\n",
                agents.resolve("outsider-copy.md").readText())
            assertFalse("<fieldset disabled>" in get("/agents/outsider-copy"))
            post("/agents/outsider-copy", mapOf("name" to "outsider-copy", "cli" to "claude", "instructions" to "Look closer."))
            assertContains(agents.resolve("outsider-copy.md").readText(), "Look closer.")
            assertEquals("Look around.\n", extra.resolve("outsider.md").readText().substringAfterLast("---\n"))
            assertEquals("/agents/shipped-writer-copy", post("/agents/shipped-writer/copy", emptyMap()).path)
        }
    }

    @Test
    fun `names are unique across all folders`() {
        val extra = Files.createTempDirectory("extra-agents")
        extra.resolve("outsider.md").writeText("Look around.\n")
        agents.resolve("mine.md").writeText("---\nname: mine\n---\nMine.\n")
        agents.resolve("other.md").deleteIfExists()
        withExtraFolder(extra) {
            for (taken in listOf("shipped-writer", "outsider", "mine")) {
                val refused = post("/agents/new", mapOf("name" to taken, "cli" to "claude"))
                assertContains(refused.body, "An Agent named $taken already exists.")
            }
            post("/agents/new", mapOf("name" to "other", "cli" to "claude"))
            assertContains(post("/agents/other", mapOf("name" to "mine", "cli" to "claude")).body, "An Agent named mine already exists.")
            assertTrue(agents.resolve("other.md").exists())
        }
        assertContains(post("/agents/new", mapOf("name" to "../escape", "cli" to "claude")).body, "An Agent's name is up to 64 lowercase letters, digits and hyphens.")
        assertContains(post("/agents/new", mapOf("name" to "a".repeat(65), "cli" to "claude")).body, "up to 64 lowercase letters")
        for (route in listOf("new", "import")) {
            assertContains(post("/agents/new", mapOf("name" to route, "cli" to "claude")).body, "The Agents page keeps the name $route for itself.")
            assertFalse(agents.resolve("$route.md").exists())
        }
    }

    @Test
    fun `renaming an Agent moves its file`() {
        agents.resolve("old-name.md").writeText("---\nname: old-name\n---\nHi.\n")
        agents.resolve("new-name.md").deleteIfExists()
        assertEquals("/agents/new-name", post("/agents/old-name", mapOf("name" to "new-name", "cli" to "claude", "instructions" to "Hi.", "confirm" to "on")).path)
        assertFalse(agents.resolve("old-name.md").exists())
        assertContains(agents.resolve("new-name.md").readText(), "name: new-name\n")
    }

    private fun import(name: String, text: String): String {
        browser.get("/agents")
        return browser.upload("/agents/import", "file", name, text.toByteArray()).body.replace("&#34;", "\"")
    }

    @Test
    fun `Import fills the create form and drops unknown keys with a notice, or refuses with a reason`() {
        val filled = import("helper.md", "---\nname: helper\ndescription: Helps.\ncolor: green\nmemory: user\ntools: Read, Grep\nmodel: haiku\n---\nHelp out.\n")
        assertContains(filled, "New Agent</span></h1>")
        assertContains(filled, """action="/agents/new"""")
        assertContains(filled, """name="name" value="helper"""")
        assertContains(filled, """name="tool.Grep" checked""")
        assertContains(filled, """value="haiku" selected""")
        assertContains(filled, ">Help out.</textarea>")
        assertContains(filled, "Dropped the keys Ants doesn't use: color, memory.")

        assertContains(import("broken.md", "---\nname: [broken\n---\nBody.\n"), "broken.md has broken YAML")
        assertContains(import("gemini.md", "---\nname: gemini\ncli: gemini\n---\nBody.\n"), "gemini.md runs cli gemini, and Ants runs only claude or codex.")
    }

    @Test
    fun `a codex Agent has a Sandbox select in place of the tools, kept on import, and an unknown sandbox is refused`() {
        agents.resolve("coder.md").deleteIfExists()
        assertContains(get("/agents/new"), """name="sandbox"""")
        post("/agents/new", mapOf("name" to "coder", "cli" to "codex", "sandbox" to "read-only", "tool.Read" to "on", "instructions" to "Code."))
        assertEquals("---\nname: coder\ncli: codex\nsandbox: read-only\n---\nCode.\n", agents.resolve("coder.md").readText())
        val coder = get("/agents/coder")
        assertContains(coder, """value="read-only" selected""")
        assertContains(coder, """<fieldset data-cli="claude" disabled>""") // Claude's tools and models, off for a codex Agent
        assertContains(coder, """<fieldset data-cli="codex">""")

        // codex's model is free text.
        post("/agents/coder", mapOf("name" to "coder", "cli" to "codex", "sandbox" to "read-only", "model" to "gpt-5-codex", "instructions" to "Code."))
        assertContains(agents.resolve("coder.md").readText(), "model: gpt-5-codex\n")
        assertContains(get("/agents/coder"), """<input class="mono" id="codex-model" name="model" value="gpt-5-codex"""")

        val refused = post("/agents/new", mapOf("name" to "yolo", "cli" to "codex", "sandbox" to "yolo"))
        assertContains(refused.body, "codex takes the sandbox read-only, workspace-write or danger-full-access, not yolo.")
        assertFalse(agents.resolve("yolo.md").exists())

        val imported = import("coder.md", "---\nname: coder2\ncli: codex\nsandbox: danger-full-access\n---\nCode.\n")
        assertContains(imported, """value="danger-full-access" selected""")
        assertFalse("Dropped the keys" in imported)
        assertContains(import("yolo.md", "---\nname: yolo\ncli: codex\nsandbox: yolo\n---\n"), "yolo.md: codex takes the sandbox read-only, workspace-write or danger-full-access, not yolo.")
    }

    @Test
    fun `ticking Offer as a Subagent on a codex Agent is refused`() {
        agents.resolve("searcher.md").deleteIfExists()
        val refused = post("/agents/new", mapOf("name" to "searcher", "cli" to "codex", "subagent" to "on"))
        assertContains(refused.body, "Only a claude Agent can be offered as a Subagent.")
        assertFalse(agents.resolve("searcher.md").exists())
        post("/agents/new", mapOf("name" to "searcher", "cli" to "claude", "subagent" to "on"))
        assertContains(agents.resolve("searcher.md").readText(), "ants-subagent: true\n")
        assertContains(get("/agents/searcher"), """name="subagent" checked""")
    }

    @Test
    fun `the model dropdown lists current models and aliases, and a deprecated model warns`() {
        val page = get("/agents/new")
        assertContains(page, """<optgroup label="Always the newest">""")
        assertContains(page, ">opus · newest Opus, now Opus 5.5</option>")
        assertContains(page, ">sonnet · newest Sonnet, now Sonnet 5.5</option>")
        assertContains(page, """<optgroup label="A fixed version">""")
        assertContains(page, ">Haiku 5.5 · claude-haiku-5-5</option>")
        assertContains(page, ">Haiku 4.5 · claude-haiku-4-5-20251001</option>")
        assertContains(page, ">Sonnet 4.5 · claude-sonnet-4-5-20250929 · retires November 30, 2026</option>")
        assertFalse("claude-opus-4-1-20250805" in page) // retired
        assertContains(page, ">Other model ID…</option>")
        assertContains(page, "From Anthropic's public model list, refreshed with Ants' update check (last ")

        agents.resolve("old.md").writeText("---\nname: old\nmodel: claude-sonnet-4-5-20250929\n---\n")
        assertContains(get("/agents/old"), "Deprecated: it retires November 30, 2026.")
    }

    @Test
    fun `Check now refreshes the model list and goes back to the page`() {
        val checked = post("/models/check", emptyMap()) // the page it was pressed on sends its Referer; without one, the Agents
        assertEquals("/agents", checked.path)
        assertContains(checked.body, toastMarkup(Toast.Kind.SUCCESS, "Models up to date", "No new models."))
    }

    @Test
    fun `Check now says why when Anthropic's model list can't be read`() {
        models.deleteIfExists()
        try {
            val failed = post("/models/check", emptyMap())
            assertEquals("/agents", failed.path)
            assertContains(failed.body, toastMarkup(Toast.Kind.ERROR, "Couldn't check models", "Anthropic's model list couldn't be read: $models (No such file or directory)"))
        } finally {
            models.writeText("")
        }
    }

    @Test
    fun `the tools come in groups, each saying what it does, and the others say why they are usually off`() {
        val page = get("/agents/new")
        for (group in listOf("Read the code", "Change files", "Run commands", "Web", "Helpers")) assertContains(page, """<div class="tgroup">$group</div>""")
        assertContains(page, """<b class="mono">Read</b></label>""")
        assertContains(page, "Open a file.")
        assertContains(page, """data-example="pnpm test"""") // Bash's Limit…
        // Write has its own Limit…: a ticked Write without one writes anywhere, whatever Edit's limit
        assertContains(page, """data-tool="Write" data-example="src/**"""")
        assertContains(page, "Claude Code's other ${AgentForm.OTHER_TOOLS.size} tools: usually off for Agents, and why")
        assertContains(page, """name="tool.AskUserQuestion"""")
        assertContains(page, "Nobody is at a terminal, so the question goes unanswered.")
        assertFalse("""name="tool.Skill"""" in page) // never in the allow-list

        agents.resolve("asker.md").writeText("---\nname: asker\ntools: Read, AskUserQuestion, Frobnicate\n---\n")
        val asker = get("/agents/asker")
        assertContains(asker, """name="tool.AskUserQuestion" checked""")
        assertContains(asker, "tools (1 ticked): usually off")
        assertContains(asker, """<input type="checkbox" name="other-tools" value="Frobnicate" checked>""")
        assertContains(asker, "Not a tool Ants knows.")
        post("/agents/asker", mapOf("name" to "asker", "cli" to "claude", "tool.Read" to "on", "tool.AskUserQuestion" to "on", "other-tools" to "Frobnicate"))
        assertContains(agents.resolve("asker.md").readText(), "tools: AskUserQuestion, Read, Frobnicate\n")
    }

    @Test
    fun `an edit reaches a live Run at its next Task launch`() {
        val claude = FakeClaude()
        val settings = home.resolve("settings.yaml")
        val before = settings.readText()
        settings.writeText("claude:\n  command: [${claude.command}]\n$vault")
        try {
            agents.resolve("writer.md").deleteIfExists()
            post("/agents/new", mapOf("name" to "writer", "cli" to "claude", "skills" to "draft", "tool.Read" to "on", "model" to "sonnet", "instructions" to "Write plainly."))
            val skill = home.resolve("skills/draft").createDirectories()
            skill.resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts a text.\n---\nDraft the text.\n")
            skill.resolve("result.schema.json").deleteIfExists()
            fun result(result: String) = """{"type":"result","subtype":"success","session_id":"${'$'}SESSION","structured_output":$result}"""
            claude.session("draft", result("""{"outcome":"stuck","summary":"No tone.","needs":"Which tone?"}"""))
            claude.session("draft", 2, result("""{"outcome":"done","summary":"Drafted.","output":{}}"""))
            browser.import("agent-task.bpmn")
            val run = browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path
            var entry = ""
            val row = Regex("""<tr[^>]*>(?:(?!</tr>).)*?"$run"(?:(?!</tr>).)*?href="(/notifications/[^"]+)"""",RegexOption.DOT_MATCHES_ALL)
            eventually { entry = assertNotNull(row.find(browser.get("/notifications").body)).groupValues[1] }

            post("/agents/writer", mapOf("name" to "writer", "cli" to "claude", "tool.Read" to "on", "tool.Grep" to "on", "model" to "opus", "instructions" to "Write warmly."))
            browser.post(entry, mapOf("action" to "fresh", "instruction" to ""))
            eventually { assertContains(browser.get(run).body, """id="state">Completed<""") }
            val (first, second) = claude.launches("draft")
            assertEquals(listOf("sonnet", "Write plainly.", "Read"), listOf(first.flag("--model"), first.flag("--append-system-prompt"), first.flag("--tools")))
            assertEquals(listOf("opus", "Write warmly.", "Grep,Read"), listOf(second.flag("--model"), second.flag("--append-system-prompt"), second.flag("--tools")))
        } finally {
            settings.writeText(before)
        }
    }

    @Test
    fun `New Agent from the modeler opens the create form in its modal, and Create and use lands on the new Agent`() {
        agents.resolve("helper.md").deleteIfExists()
        val form = browser.get("/agents/new?use")
        assertEquals("SAMEORIGIN", form.headers?.firstValue("X-Frame-Options")?.orElse(null)) // the modeler frames it
        assertContains(form.body, """<button class="primary" form="agent">Create and use</button>""")
        assertFalse("""<nav class="side">""" in form.body) // the modeler's own page is behind the modal
        assertContains(browser.get("/agents/new?use&skills=draft").body, Regex("""name="skills" value="draft"""")) // the Task's Skill

        val refused = browser.post("/agents/new", mapOf("name" to "Helper!", "cli" to "claude", "use" to ""))
        assertContains(refused.body, """<button class="primary" form="agent">Create and use</button>""") // a refusal stays in the modal
        assertEquals("/agents/helper", browser.post("/agents/new", mapOf("name" to "helper", "cli" to "claude", "use" to "")).path)
        assertTrue(agents.resolve("helper.md").exists())
    }
}
