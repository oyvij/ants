package ants

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.yaml.snakeyaml.Yaml
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
    @Autowired
    lateinit var catalogue: Catalogue

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
        skills: k-code-review, k-tdd
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
        assertEquals(reviewerFile, withoutId(agents.resolve("reviewer.md")))

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
        assertEquals(reviewerFile, withoutId(agents.resolve("reviewer.md")))

        // As the page posts it: a field per rule, chip and ticked tool, the empty ones left blank.
        browser.post("/agents/reviewer", reviewer.toList().filter { it.first !in setOf("skills", "limit.Bash", "other-tools") } + listOf(
            "skills" to "code-review", "skills" to "tdd", "skills" to "",
            "limit.Bash" to "git log *", "limit.Bash" to "", "limit.Bash" to "git diff *",
            "other-tools" to "mcp__github__get_issue", "other-tools" to "",
        ))
        assertEquals(reviewerFile, withoutId(agents.resolve("reviewer.md")))

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
        assertEquals("---\nname: seeker\ncli: claude\ntools: Read\ncolor: blue\n---\nScout.\n", withoutId(agents.resolve("seeker.md")))
    }

    @Test
    fun `an Agent with every Skill, skills star, shows every Skill on its page and keeps the star on Save`() {
        agents.resolve("all.md").writeText("---\nname: all\nskills: \"*\"\n---\nDo it.\n")
        val page = get("/agents/all")
        assertContains(page, """every Skill<input type="hidden" name="skills" value="*">""")
        assertContains(page, "The Skills this Agent may use")
        assertFalse("A Task picks one of them" in page)
        post("/agents/all", mapOf("name" to "all", "cli" to "claude", "skills" to "*", "instructions" to "Do it."))
        assertContains(agents.resolve("all.md").readText(), "skills: '*'")
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
        extra.resolve("outsider.md").writeText("---\nname: outsider\ndescription: From Varde.\nskills: tdd\ncolor: red\n---\nLook around.\n")
        agents.resolve("outsider-copy.md").deleteIfExists()
        agents.resolve("shipped-writer-copy.md").deleteIfExists()
        withExtraFolder(extra) {
            val list = get("/agents")
            assertContains(list, """<a href="/agents/shipped-writer">shipped-writer</a>""")
            assertContains(list, """<a href="/agents/outsider">outsider</a>""")
            for (name in listOf("shipped-writer", "outsider")) {
                val page = get("/agents/$name")
                assertContains(page, "<fieldset disabled>")
                assertFalse("""<button class="primary" form="agent">Publish</button>""" in page)
                assertEquals(403, post("/agents/$name", mapOf("name" to name, "instructions" to "Changed.")).status)
            }
            val copy = post("/agents/outsider/copy", emptyMap())
            assertEquals("/agents/outsider-copy", copy.path)
            for (shown in listOf("""name="description" value="From Varde."""", """<input type="hidden" name="skills" value="tdd">""", ">Look around.</textarea>"))
                assertContains(copy.body.replace("&#34;", "\""), shown)
            assertFalse("<fieldset disabled>" in copy.body)
            post("/agents/outsider-copy", mapOf("name" to "outsider-copy", "cli" to "claude", "description" to "From Varde.", "skills" to "tdd", "instructions" to "Look closer."))
            assertEquals("---\nname: outsider-copy\ndescription: From Varde.\ncli: claude\nskills: k-tdd\ntools: ''\ncolor: red\n---\nLook closer.\n", withoutId(agents.resolve("outsider-copy.md")))
            assertEquals("Look around.\n", extra.resolve("outsider.md").readText().substringAfterLast("---\n"))
            val shipped = post("/agents/shipped-writer/copy", emptyMap()).path
            assertTrue(shipped.startsWith("/agents/shipped-writer-copy"), shipped)
            deleteDraft(shipped)
        }
    }

    private fun idOf(file: Path) = Regex("(?m)^id: (a-[a-z0-9]{6})$").find(file.readText())?.groupValues?.get(1)

    private fun withoutId(file: Path) = file.readText().also { assertNotNull(idOf(file), it) }.replaceFirst(Regex("(?m)^id: a-[a-z0-9]{6}\n"), "")

    private fun assertNoPageShows(id: String, vararg paths: String) {
        for (path in paths) assertFalse(id in get(path), "$path shows the id $id")
    }

    @Test
    fun `creating, importing or copying an Agent gives it a new id in its file, which no page shows and the Catalogue finds it by`() {
        agents.resolve("reviewer.md").deleteIfExists()
        post("/agents/new", reviewer)
        val created = assertNotNull(idOf(agents.resolve("reviewer.md")))
        post("/agents/reviewer", reviewer + ("description" to "Reviews more."))
        assertEquals(created, idOf(agents.resolve("reviewer.md")))
        assertEquals("reviewer", catalogue.agentWithId(created).name)
        assertNoPageShows(created, "/agents", "/agents/reviewer")

        agents.resolve("theirs.md").deleteIfExists()
        assertFalse("a-theirs" in import("theirs.md", "---\nname: theirs\nid: a-theirs\n---\nTheirs.\n"))
        val imported = assertNotNull(idOf(agents.resolve("theirs.md")))
        assertTrue(imported !in setOf(created, "a-theirs"))

        val copy = post("/agents/shipped-writer/copy", emptyMap()).path.removePrefix("/agents/")
        agents.resolve("$copy.md").deleteIfExists()
        post("/agents/$copy", mapOf("name" to copy, "cli" to "claude"))
        val copied = assertNotNull(idOf(agents.resolve("$copy.md")))
        assertTrue(copied !in setOf(created, imported, "a-shipped-writer"))
        assertEquals("shipped-writer", catalogue.agentWithId("a-shipped-writer").name)
    }

    @Test
    fun `an Agent from an extra folder gets its id in agent-ids yaml, and its folder is untouched`() {
        val extra = Files.createTempDirectory("extra-agents")
        val text = "---\nname: outsider\ndescription: From Varde.\n---\nLook around.\n"
        extra.resolve("outsider.md").writeText(text)
        withExtraFolder(extra) {
            get("/agents")
            val ids = Yaml().load<Map<String, String>>(home.resolve("agent-ids.yaml").readText())
            val id = ids.entries.single { it.value == extra.resolve("outsider.md").toString() }.key
            assertTrue(Regex("a-[a-z0-9]{6}").matches(id), id)
            assertEquals("outsider", catalogue.agentWithId(id).name)
            get("/agents")
            assertEquals(ids, Yaml().load<Map<String, String>>(home.resolve("agent-ids.yaml").readText()))
            assertNoPageShows(id, "/agents", "/agents/outsider")
        }
        assertEquals(listOf("outsider.md"), extra.toFile().list()!!.toList())
        assertEquals(text, extra.resolve("outsider.md").readText())
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
    fun `renaming an Agent saves at once with no confirm, moves its file and keeps its id`() {
        agents.resolve("old-name.md").writeText("---\nid: a-oldname\nname: old-name\n---\nHi.\n")
        agents.resolve("new-name.md").deleteIfExists()
        assertEquals("/agents/new-name", post("/agents/old-name", mapOf("name" to "new-name", "cli" to "claude", "instructions" to "Hi.")).path)
        assertFalse(agents.resolve("old-name.md").exists())
        assertContains(agents.resolve("new-name.md").readText(), "id: a-oldname\nname: new-name\n")
        assertEquals("new-name", catalogue.agentWithId("a-oldname").name)
    }

    @Test
    fun `an Agent saved with Skill names holds their ids, and the Agents and Skills pages show their names`() {
        agents.resolve("linker.md").deleteIfExists()
        post("/agents/new", mapOf("name" to "linker", "cli" to "claude", "skills" to "shipped-draft, code-review"))
        assertContains(agents.resolve("linker.md").readText(), "skills: k-shipped-draft, k-code-review\n")

        val page = get("/agents/linker")
        assertContains(page, """<input type="hidden" name="skills" value="shipped-draft">""")
        assertContains(page, """<input type="hidden" name="skills" value="code-review">""")
        val list = get("/agents")
        assertContains(list, """<a href="/skills/shipped-draft">shipped-draft</a> <a href="/skills/code-review">code-review</a>""")
        val skills = get("/skills")
        assertContains(skills, """<a href="/agents/linker">linker</a>""")
        val skill = get("/skills/shipped-draft")
        assertContains(skill, """<a href="/agents/linker">linker</a>""")
        for (shown in listOf(page, list, skills, skill)) assertFalse(Regex("k-shipped-draft|k-code-review").containsMatchIn(shown))

        post("/agents/linker", mapOf("name" to "linker", "cli" to "claude", "skills" to "shipped-draft, code-review"))
        assertContains(agents.resolve("linker.md").readText(), "skills: k-shipped-draft, k-code-review\n")
        agents.resolve("linker.md").deleteIfExists()
    }

    @Autowired
    lateinit var scripts: Scripts

    @Test
    fun `an Agent saved with Script names holds their ids, and its page shows their names`() {
        scripts.save(Script("s-post01", "post-message", "Posts a message."), "", emptyMap())
        scripts.save(Script("s-tidy01", "tidy", "Tidies."), "", emptyMap())
        scripts.save(Script("s-other1", "other", "Does other."), "", emptyMap())
        browser.get("/agents/new")
        browser.post("/agents/new", listOf("name" to "caller", "cli" to "claude", "scripts" to "post-message", "scripts" to "tidy"))
        assertContains(agents.resolve("caller.md").readText(), "scripts: s-post01, s-tidy01\n")

        val page = get("/agents/caller")
        assertContains(page, """name="scripts" value="post-message" checked""")
        assertContains(page, """name="scripts" value="tidy" checked""")
        assertContains(page, """name="scripts" value="other">""")
        assertFalse(Regex("s-post01|s-tidy01|s-other1").containsMatchIn(page))

        agents.resolve("by-hand.md").writeText("---\nname: by-hand\nscripts: [tidy]\n---\nDo it.\n")
        assertContains(get("/agents/by-hand"), """name="scripts" value="tidy" checked""")
        browser.post("/agents/by-hand", listOf("name" to "by-hand", "cli" to "claude", "scripts" to "tidy"))
        assertContains(agents.resolve("by-hand.md").readText(), "scripts: s-tidy01\n")
    }

    private fun import(name: String, text: String): String {
        browser.get("/agents")
        return browser.upload("/agents/import", "file", name, text.toByteArray()).body.replace("&#34;", "\"")
    }

    @Test
    fun `Import publishes the Agent and drops unknown keys with a notice, or refuses with a reason`() {
        agents.resolve("helper.md").deleteIfExists()
        val imported = import("helper.md", "---\nname: helper\ndescription: Helps.\ncolor: green\nmemory: user\ntools: Read, Grep\nmodel: haiku\n---\nHelp out.\n")
        assertContains(imported, """action="/agents/helper"""")
        assertContains(imported, """name="tool.Grep" checked""")
        assertContains(imported, """value="haiku" selected""")
        assertContains(imported, ">Help out.</textarea>")
        assertContains(imported, "Dropped the keys Ants doesn't use: color, memory.")
        assertEquals("---\nname: helper\ndescription: Helps.\ncli: claude\ntools: Grep, Read\nmodel: haiku\n---\nHelp out.\n", withoutId(agents.resolve("helper.md")))
        assertContains(import("again.md", "---\nname: helper\n---\nAgain.\n"), "An Agent named helper already exists.")

        assertContains(import("broken.md", "---\nname: [broken\n---\nBody.\n"), "broken.md has broken YAML")
        assertContains(import("gemini.md", "---\nname: gemini\ncli: gemini\n---\nBody.\n"), "gemini.md runs cli gemini, and Ants runs only claude or codex.")
    }

    @Test
    fun `a codex Agent has a Sandbox select in place of the tools, kept on import, and an unknown sandbox is refused`() {
        agents.resolve("coder.md").deleteIfExists()
        assertContains(get("/agents/new"), """name="sandbox"""")
        post("/agents/new", mapOf("name" to "coder", "cli" to "codex", "sandbox" to "read-only", "tool.Read" to "on", "instructions" to "Code."))
        assertEquals("---\nname: coder\ncli: codex\nsandbox: read-only\n---\nCode.\n", withoutId(agents.resolve("coder.md")))
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
            assertEquals(listOf("sonnet", "Write plainly.", "Read,Skill"), listOf(first.flag("--model"), first.flag("--append-system-prompt"), first.flag("--tools")))
            assertEquals(listOf("opus", "Write warmly.", "Grep,Read,Skill"), listOf(second.flag("--model"), second.flag("--append-system-prompt"), second.flag("--tools")))
        } finally {
            settings.writeText(before)
        }
    }

    @Test
    fun `Edit from the modeler opens the Agent's page in its modal, and Publish lands on it published`() {
        listOf("editor", "renamed-editor").forEach { agents.resolve("$it.md").deleteIfExists() }
        post("/agents/new", mapOf("name" to "editor", "cli" to "claude", "description" to "Edits."))
        val form = browser.get("/agents/editor?use").body
        assertContains(form, """<button class="primary" form="agent">Publish</button>""")
        assertContains(form, """<input type="hidden" name="use">""")
        assertFalse("""<nav class="side">""" in form)

        val refused = browser.post("/agents/editor", mapOf("name" to "Editor!", "cli" to "claude", "use" to ""))
        assertContains(refused.body, """<button class="primary" form="agent">Publish</button>""")
        assertFalse("""<nav class="side">""" in refused.body)

        val saved = browser.post("/agents/editor", mapOf("name" to "editor", "cli" to "claude", "description" to "Edits warmly.", "use" to ""))
        assertEquals("/agents/editor" to "saved", saved.path to saved.query)
        assertContains(agents.resolve("editor.md").readText(), "Edits warmly.")

        val renamed = browser.post("/agents/editor", mapOf("name" to "renamed-editor", "cli" to "claude", "use" to ""))
        assertEquals("/agents/renamed-editor" to "saved", renamed.path to renamed.query)

        val page = post("/agents/renamed-editor", mapOf("name" to "renamed-editor", "cli" to "claude", "description" to "Edits on its page."))
        assertEquals("/agents/renamed-editor" to null, page.path to page.query)
    }

    /** The markers a page shows: Draft, Published, or Draft · changes not published. */
    private fun markers(page: String) = Regex("""class="pill draft-mark[^"]*">([^<]+)<""").findAll(page).map { it.groupValues[1] }.toList()

    /** Deletes the Draft-only Agent at [page], as its Delete does once confirmed, so the next test's New or Copy gets the same name. */
    private fun deleteDraft(page: String) = assertEquals("/agents", post("$page/delete", mapOf("confirm" to "on")).path)

    private fun drafter(name: String) = mapOf("name" to name, "cli" to "claude", "description" to "Published.", "tool.Read" to "on", "instructions" to "Publish me.")

    @Test
    fun `an edit is autosaved to the Draft, which the page reopens after a new login and a restart, and the published Agent stays as it was`() {
        agents.resolve("drafter.md").deleteIfExists()
        post("/agents/new", drafter("drafter"))
        val published = agents.resolve("drafter.md").readText()
        val page = get("/agents/drafter")
        assertEquals(listOf("Published"), markers(page))
        assertEquals("0", draftVersion(page))

        val saved = browser.autosave("/agents/drafter", drafter("drafter") + ("description" to "Half done."), draftVersion(page))
        assertEquals(200, saved.status, saved.body)
        val answer = answer(saved)
        assertTrue(Regex("""Draft saved \d\d:\d\d""").matches(answer["saved"].toString()), answer.toString())
        assertEquals("Draft · changes not published", answer["label"])
        assertEquals("/agents/drafter", answer["path"])
        assertEquals(published, agents.resolve("drafter.md").readText())
        assertEquals("Published.", catalogue.agent("drafter").description)

        val again = browser().also { it.login() }.get("/agents/drafter").body.replace("&#34;", "\"")
        assertContains(again, """name="description" value="Half done."""")
        assertEquals(listOf("Draft · changes not published"), markers(again))
        assertContains(again, answer["saved"].toString())
        assertEquals(answer["version"].toString(), draftVersion(again))
        assertContains(Drafts(home).get(DraftKind.AGENT, catalogue.agent("drafter").id!!)!!.content, "description: Half done.") // as a restarted Ants reads it
        assertContains(get("/agents"), """href="/agents/drafter">drafter</a></b><span class="pill draft-mark p-changed">Draft · changes not published</span>""")
        browser.post("/agents/drafter/discard", mapOf("confirm" to "on"))
    }

    @Test
    fun `a save from a tab with an older version gets a conflict, and one with the newest is kept`() {
        agents.resolve("tabbed.md").deleteIfExists()
        post("/agents/new", drafter("tabbed"))
        val loaded = draftVersion(get("/agents/tabbed"))
        val first = answer(browser.autosave("/agents/tabbed", drafter("tabbed") + ("description" to "First tab."), loaded))
        val second = browser.autosave("/agents/tabbed", drafter("tabbed") + ("description" to "Second tab."), loaded)
        assertEquals(409, second.status)
        assertEquals("This Agent's Draft was changed in another tab or window.", answer(second)["conflict"])
        assertContains(get("/agents/tabbed"), """value="First tab."""")

        val next = browser.autosave("/agents/tabbed", drafter("tabbed") + ("description" to "First tab, again."), first["version"].toString())
        assertEquals(200, next.status)
        assertContains(get("/agents/tabbed"), """value="First tab, again."""")
        browser.post("/agents/tabbed/discard", mapOf("confirm" to "on"))
    }

    @Test
    fun `Publish publishes the page and removes the Draft, and a refused Publish keeps the Draft and says why`() {
        listOf("publisher", "publisher-taken").forEach { agents.resolve("$it.md").deleteIfExists() }
        post("/agents/new", drafter("publisher"))
        post("/agents/new", drafter("publisher-taken"))
        browser.autosave("/agents/publisher", drafter("publisher") + ("description" to "Changed."), draftVersion(get("/agents/publisher")))

        val refused = post("/agents/publisher", drafter("publisher-taken") + ("description" to "Renamed."))
        assertContains(refused.body, "An Agent named publisher-taken already exists.")
        assertContains(get("/agents/publisher"), """value="Renamed."""") // the Draft is the page as Publish was pressed
        assertContains(agents.resolve("publisher.md").readText(), "description: Published.")

        assertEquals("/agents/publisher", post("/agents/publisher", drafter("publisher") + ("description" to "Changed.")).path)
        assertContains(agents.resolve("publisher.md").readText(), "description: Changed.")
        val page = get("/agents/publisher")
        assertEquals(listOf("Published"), markers(page))
        assertEquals("0", draftVersion(page))
        assertFalse("Draft saved" in page)
    }

    @Test
    fun `New and Copy make a Draft-only Agent with a placeholder name, used by nothing, which Publish puts to use`() {
        val first = post("/agents/new/draft", emptyMap())
        val name = first.path.removePrefix("/agents/")
        assertTrue(Regex("new-agent(-\\d+)?").matches(name), name)
        assertEquals(listOf("Draft"), markers(first.body))
        assertContains(first.body, """name="name" value="$name"""")
        assertFalse(agents.resolve("$name.md").exists())
        assertTrue(catalogue.agents().none { it.name == name })
        assertContains(get("/agents"), """href="/agents/$name">$name</a></b><span class="pill draft-mark p-draft">Draft</span>""")
        val second = post("/agents/new/draft", emptyMap()).path.removePrefix("/agents/")
        assertTrue(second != name && second.startsWith("new-agent-"), second)

        val copy = post("/agents/shipped-writer/copy", emptyMap())
        val copied = copy.path.removePrefix("/agents/")
        assertTrue(copied.startsWith("shipped-writer-copy"), copied)
        assertEquals(listOf("Draft"), markers(copy.body))
        assertContains(copy.body, """name="name" value="$copied"""")
        assertFalse(agents.resolve("$copied.md").exists())
        for (draft in listOf(second, copied)) deleteDraft("/agents/$draft")

        agents.resolve("drafted-first.md").deleteIfExists()
        val renamed = answer(browser.autosave("/agents/$name", drafter("drafted-first"), draftVersion(first.body)))
        assertEquals("/agents/drafted-first", renamed["path"]) // a Draft-only Agent's page follows its name
        assertEquals("Draft", renamed["label"])
        assertEquals(listOf("Draft"), markers(get("/agents/drafted-first")))
        assertEquals("/agents/drafted-first", post("/agents/drafted-first", drafter("drafted-first")).path)
        assertEquals("Publish me.", catalogue.agent("drafted-first").instructions)
        assertNotNull(catalogue.agent("drafted-first").id)
        assertEquals(listOf("Published"), markers(get("/agents/drafted-first")))
    }

    @Test
    fun `Discard Draft confirms and reopens the published Agent, and Delete on a Draft-only Agent confirms and leaves the list`() {
        agents.resolve("discarder.md").deleteIfExists()
        post("/agents/new", drafter("discarder"))
        browser.autosave("/agents/discarder", drafter("discarder") + ("description" to "Unwanted."), draftVersion(get("/agents/discarder")))
        assertContains(get("/agents/discarder"), """<form method="post" action="/agents/discarder/discard"""")
        val asked = post("/agents/discarder/discard", emptyMap())
        assertContains(asked.body, "Discard the Draft of discarder?")
        assertContains(get("/agents/discarder"), """value="Unwanted."""")
        val discarded = browser.post("/agents/discarder/discard", mapOf("confirm" to "on"))
        assertEquals("/agents/discarder", discarded.path)
        assertContains(discarded.body, """value="Published."""")
        assertEquals(listOf("Published"), markers(discarded.body))

        val draft = post("/agents/new/draft", emptyMap()).path
        assertFalse("/discard" in get(draft))
        assertContains(post("$draft/delete", emptyMap()).body, "Delete the Draft ${draft.removePrefix("/agents/")}?")
        assertEquals("/agents", browser.post("$draft/delete", mapOf("confirm" to "on")).path)
        assertEquals(404, browser.get(draft).status)
        assertFalse("href=\"$draft\"" in get("/agents"))
    }

    @Test
    fun `New Agent in the modeler's modal makes a Draft-only Agent with the Task's Skills and opens its page in the modal`() {
        val made = post("/agents/new/draft", mapOf("use" to "", "skills" to "tdd"))
        val name = made.path.removePrefix("/agents/")
        assertTrue(Regex("new-agent(-\\d+)?").matches(name), name)
        assertEquals("use", made.query)
        assertEquals(listOf("Draft"), markers(made.body))
        assertContains(made.body, """<input type="hidden" name="use">""")
        assertContains(made.body, """<input type="hidden" name="skills" value="tdd">""")
        assertFalse("""<nav class="side">""" in made.body)
        assertFalse(agents.resolve("$name.md").exists())
        deleteDraft(made.path)
    }

    @Test
    fun `New Skill's Use in the modeler lists the Skill in the Draft of the Task's Agent, and the published Agent stays as it was`() {
        agents.resolve("drafted-picker.md").writeText("---\nid: a-dpick01\nname: drafted-picker\ndescription: Picks.\ncli: claude\nskills: tdd\n---\nPick.\n")
        val published = agents.resolve("drafted-picker.md").readText()
        get("/agents/drafted-picker")
        assertEquals(204, browser.post("/agents/drafted-picker/draft/skills", mapOf("skill" to "new-skill-x")).status)
        assertEquals(published, agents.resolve("drafted-picker.md").readText())
        val draft = get("/agents/drafted-picker")
        assertContains(draft, """<input type="hidden" name="skills" value="tdd">""")
        assertContains(draft, """<input type="hidden" name="skills" value="new-skill-x">""")
        assertEquals(listOf("Draft · changes not published"), markers(draft))
        assertEquals(403, browser.post("/agents/shipped-writer/draft/skills", mapOf("skill" to "new-skill-x")).status)
        browser.post("/agents/drafted-picker/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `Publish refuses an Agent listing a Draft-only Skill or Script, naming each, and keeps its Draft`() {
        agents.resolve("lister.md").deleteIfExists()
        val skill = post("/skills/new/draft", emptyMap()).path
        val script = post("/scripts/new/draft", emptyMap()).path
        val skillName = skill.removePrefix("/skills/")
        val scriptName = script.removePrefix("/scripts/")
        try {
            val page = post("/agents/new/draft", emptyMap()).path
            val refused = post(page, drafter("lister") + ("skills" to "tdd, $skillName") + ("scripts" to scriptName))
            assertContains(refused.body, "The Skill $skillName is a Draft: publish it first.")
            assertContains(refused.body, "The Script $scriptName is a Draft: publish it first.")
            assertFalse(agents.resolve("lister.md").exists())
            assertContains(get(page), """value="$skillName"""") // the Draft is the page as Publish was pressed
            deleteDraft(page)
        } finally {
            post("$skill/delete", mapOf("confirm" to "on"))
            post("$script/delete", mapOf("confirm" to "on"))
        }
    }

    @Test
    fun `Publish passes an Agent listing a Skill and a Script that are published and have a Draft`() {
        listOf("lister-ok.md").forEach { agents.resolve(it).deleteIfExists() }
        home.resolve("skills/listed").createDirectories().resolve("SKILL.md").writeText("---\nname: listed\ndescription: Listed.\n---\nDo it.\n")
        scripts.save(Script("s-listed1", "listed-script", "Listed."), "", emptyMap())
        try {
            browser.autosave("/skills/listed", mapOf("skill" to "---\nname: listed\ndescription: Half done.\n---\n"), draftVersion(get("/skills/listed")))
            browser.autosave("/scripts/listed-script", mapOf("name" to "listed-script", "description" to "Half done."), draftVersion(get("/scripts/listed-script")))
            val published = post("/agents/new", drafter("lister-ok") + ("skills" to "listed") + ("scripts" to "listed-script"))
            assertEquals("/agents/lister-ok", published.path, published.body)
        } finally {
            post("/skills/listed/delete", mapOf("confirm" to "on"))
            post("/scripts/listed-script/delete", mapOf("confirm" to "on"))
            agents.resolve("lister-ok.md").deleteIfExists()
        }
    }

    @Test
    fun `shipped Agents have no Draft and no autosave`() {
        val page = get("/agents/shipped-writer")
        assertFalse("data-autosave" in page)
        assertTrue(markers(page).isEmpty())
        assertEquals(403, browser.autosave("/agents/shipped-writer", mapOf("name" to "shipped-writer", "cli" to "claude"), "0").status)
    }
}
