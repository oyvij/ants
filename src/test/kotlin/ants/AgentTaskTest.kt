package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentTaskTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    @Autowired
    lateinit var catalogue: Catalogue

    private val env = listOf("PATH", "HOME", "ANTHROPIC_API_KEY", "CLAUDE_CODE_RETRY_WATCHDOG")

    private fun settings(agentTasks: Int = 3, extra: String = "") = settings(claude, "agent-tasks: $agentTasks\n$extra", env)

    /** The Agent Task's recipe for the Skill `draft` promising [schema]; [agent] and [metadata] go in the Agent's and the Skill's frontmatter. */
    private fun agentTask(schema: String? = null, agent: String = "", metadata: String = "", agentTasks: Int = 3, extra: String = "", retries: Int = 0) =
        agentTask(claude, mapOf("draft" to schema), agent, metadata, "agent-tasks: $agentTasks\n$extra", env, retries)

    /** The launch's skills folder: the one --add-dir in the Run workspace's `.skills`. */
    private fun skillsFolder(launch: FakeClaude.Launch): Path =
        launch.argv.zipWithNext().filter { it.first == "--add-dir" && "/.skills/" in it.second }.map { Path.of(it.second) }.single()

    /** The `--json-schema` the launch was given. */
    private fun FakeClaude.Launch.schema(): Map<*, *> = JsonMapper.shared().readValue(argv[argv.indexOf("--json-schema") + 1], Map::class.java)

    /** A result line whose Result is done, carrying [output]. */
    private fun done(output: String = """{"text":"Hello"}""") = """{"type":"result","subtype":"success","is_error":false,""" +
        """"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Drafted.","output":$output}}"""

    private val done = done()
    private val textSchema = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""
    private val fileSchema = """{"type":"object","properties":{"file":{"type":"string","format":"path"}},"required":["file"]}"""

    private fun start(): String {
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        return browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    /** Deploys [file] as the Process [key], its first Agent Task given [properties] too, and starts a Run of it. */
    private fun startWith(key: String, properties: String, file: String = "agent-task") : String {
        val bpmn = javaClass.getResource("/$file.bpmn")!!.readText().replace("\"$file\"", "\"$key\"")
            .replaceFirst("""<operaton:property name="ants.skills" value="draft" />""", """<operaton:property name="ants.skills" value="draft" />$properties""")
        browser.get("/processes")
        assertFalse("role=\"alert\"" in browser.upload("/processes", "file", "$key.bpmn", bpmn.toByteArray()).body)
        browser.get("/processes/$key")
        return browser.post("/processes/$key/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    private fun property(name: String, value: String) = """<operaton:property name="$name" value="${value.replace("\"", "&quot;")}" />"""

    @Test
    fun `a test's Skill named like a shipped one fails at once, since the shipped Skill would hide it`() {
        val failure = kotlin.test.assertFailsWith<IllegalArgumentException> { agentTask(claude, mapOf("grilling" to null)) }
        assertEquals("grilling is hidden by the shipped Skill of that name; give the test's Skill another name.", failure.message)
    }

    @Test
    fun `an Agent Task launches the published Agent while the Agent has a Draft`() {
        agentTask()
        claude.session("draft", done)
        val draft = browser.autosave("/agents/writer", mapOf("name" to "writer", "cli" to "claude", "skills" to "draft", "model" to "opus", "instructions" to "Write a Draft."),
            draftVersion(browser.get("/agents/writer").body))
        assertEquals(200, draft.status, draft.body)
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val launch = claude.launches("draft").single()
        assertEquals("Write plainly.", launch.flag("--append-system-prompt"))
        assertEquals("sonnet" to "medium", launch.flag("--model") to launch.flag("--effort")) // the published writer's default level, not the Draft's opus
        browser.post("/agents/writer/discard", mapOf("confirm" to "on")) // the next test's writer.md, with no id, would leave this Draft as a Draft-only writer
    }

    @Test
    fun `an Agent Task gets the published Skill while the Skill has a Draft, and never a Draft-only one`() {
        agentTask()
        home.resolve("agents/writer.md").writeText("---\nskills: \"*\"\n---\nWrite plainly.\n") // so it would get a Draft-only Skill, were it given one
        claude.session("draft", done)
        val draft = browser.autosave("/skills/draft", mapOf("skill" to "---\nname: draft\ndescription: Does it worse.\n---\nDo it badly.\n"), draftVersion(browser.get("/skills/draft").body))
        assertEquals(200, draft.status, draft.body)
        val only = browser.post("/skills/new/draft", emptyMap()).path
        try {
            val run = start()
            eventually { assertContains(page(run), """id="state">Completed<""") }
            val launch = claude.launches("draft").single()
            assertEquals(home.resolve("skills/draft"), launch.skills["draft"])
            assertFalse(only.removePrefix("/skills/") in launch.skills)
            assertEquals("---\nname: draft\ndescription: Does draft.\n---\nDo it.\n", home.resolve("skills/draft/SKILL.md").readText())
        } finally {
            browser.post("$only/delete", mapOf("confirm" to "on"))
            browser.post("/skills/draft/discard", mapOf("confirm" to "on"))
        }
    }

    @Test
    fun `a result-schema override replaces the Skill's schema in --json-schema`() {
        agentTask(textSchema)
        claude.session("draft", done("""{"verdict":"ship"}"""))
        val verdict = """{"type":"object","properties":{"verdict":{"type":"string"}},"required":["verdict"]}"""
        val run = startWith("overridden", property("ants.result-schema", verdict))
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val output = (claude.launches("draft").single().schema()["properties"] as Map<*, *>)["output"]
        assertEquals(JsonMapper.shared().readValue(verdict, Map::class.java), output)
    }

    @Test
    fun `the Vault and Answer History toggles reach the hand-off line and --add-dir only when on, and are off unless the Task sets them`() {
        val map = Files.createTempFile("source-map", ".md")
        agentTask(extra = "source-maps:\n  mine: $map\n")
        claude.session("draft", done)
        val unset = startWith("toggles-unset", "")
        eventually { assertContains(page(unset), """id="state">Completed<""") }
        assertFalse(Regex("Vault|Answer History").containsMatchIn(claude.launches("draft").last().stdin))
        val byDefault = startWith("toggles-default", property("ants.vault", "true"))
        eventually { assertContains(page(byDefault), """id="state">Completed<""") }
        val defaults = claude.launches("draft").last().stdin
        assertContains(defaults, "The workspace is ${workspace(byDefault)}. The Vault is $vaultPath and the Source map is $map.\\n")
        assertFalse("Answer History" in defaults)
        assertContains(claude.launches("draft").last().argv.zipWithNext(), "--add-dir" to "$vaultPath")

        val flipped = startWith("toggles-flipped", property("ants.vault", "false") + property("ants.answer-history", "true"))
        eventually { assertContains(page(flipped), """id="state">Completed<""") }
        val toggled = claude.launches("draft").last().stdin
        assertContains(toggled, "The workspace is ${workspace(flipped)}. The Answer History is ${home.resolve("answer-history")}.\\n")
        assertFalse("Vault" in toggled)
        // Off, the Task learns the Vault and Source map from nowhere: not its prompt, environment or --add-dir.
        val off = claude.launches("draft").last()
        for (place in listOf("$vaultPath", "$map")) {
            assertFalse(place in off.stdin)
            assertFalse(off.argv.any { place in it })
            assertFalse(off.env.values.any { place in it })
        }
        // On, the Answer History is an --add-dir, so a Read reaches it outside the workspace.
        val history = home.resolve("answer-history").toString()
        assertContains(off.argv.zipWithNext(), "--add-dir" to history)
        assertFalse(history in claude.launches("draft").first().argv)
    }

    @Test
    fun `with resume on, a Task the flow comes back to resumes its session with its inputs`() {
        agentTask()
        claude.session("draft", done)
        val run = startWith("resumed", property("ants.resume", "true"), file = "agent-loop") // on Draft, which loops once; not on Polish
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val (first, again, polish) = claude.launches("draft")
        assertEquals(first.flag("--session-id"), again.flag("--resume"))
        assertEquals(null, again.flag("--session-id"))
        assertEquals(first.stdin, again.stdin) // the hand-off and the inputs, read afresh
        assertEquals(null, polish.flag("--resume"))
        assertNotEquals(first.flag("--session-id"), polish.flag("--session-id"))
    }

    @Test
    fun `with a Profile that has instructions, every launch excludes the CLAUDE md files above the Run workspace, a Task the flow comes back to too`() {
        agentTask(extra = "profiles:\n  work:\n    vault: notes\n")
        instruct("work", "Keep it short.\n")
        claude.session("draft", done)
        val run = startWith("instructed", property("ants.resume", "true"), file = "agent-loop")
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val launches = claude.launches("draft")
        assertEquals(3, launches.size)
        for (launch in launches) {
            assertEquals("user,project", launch.flag("--setting-sources"))
            assertEquals(excludingAbove(Path.of(launch.cwd)), launch.flag("--settings"))
        }
        val cwd = Path.of(launches.first().cwd)
        val settings = launches.first().flag("--settings")!!
        val above = listOf("${cwd.parent}/CLAUDE.md", "${home.toRealPath()}/CLAUDE.local.md", "${home.toRealPath()}/.claude/CLAUDE.md", "/CLAUDE.md")
        for (file in above) assertContains(settings, "\"$file\"")
        assertFalse("\"$cwd/CLAUDE.md\"" in settings)
    }

    @Test
    fun `with a Profile that has no instructions, or blank ones, --settings excludes no CLAUDE md files`() {
        agentTask(extra = "profiles:\n  work:\n    vault: notes\n")
        claude.session("draft", done)
        val none = start()
        eventually { assertContains(page(none), """id="state">Completed<""") }
        instruct("work", " \n")
        val blank = start()
        eventually { assertContains(page(blank), """id="state">Completed<""") }
        val settings = """{"disableAllHooks":true,"attribution":{"commit":"","pr":"🐜 Created with [Ants](https://github.com/oyvij/ants)"}}"""
        assertEquals(listOf(settings, settings), claude.launches("draft").map { it.flag("--settings") })
    }

    private fun page(run: String) = browser.get("/runs/$run").body.replace("&#34;", "\"")

    @Test
    fun `a Process with one Agent Task ends with the Task's Result stored under its Task id`() {
        agentTask(agent = "name: writer\ndescription: Writes.\nmodel: sonnet\n")
        claude.session("draft", """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}""", done)
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertContains(page(run), """<dt>draft</dt><dd><code>{"outcome":"done","summary":"Drafted.","output":{"text":"Hello"}}</code></dd>""")
    }

    private fun modelAndEffort(agent: String): Pair<String?, String?> {
        home.resolve("agents/writer.md").writeText("---\n${agent}skills: draft\n---\nWrite plainly.\n")
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val launch = claude.launches("draft").last()
        return launch.flag("--model") to launch.flag("--effort")
    }

    @Test
    fun `a claude Agent Task launches on its Agent's level's model and effort`() {
        agentTask()
        claude.session("draft", done)
        val ladder = mapOf(1 to ("haiku" to null), 2 to ("sonnet" to "medium"), 3 to ("opus" to "medium"), 4 to ("opus" to "high"), 5 to ("opus" to "max"))
        for ((level, expected) in ladder) assertEquals(expected, modelAndEffort("level: $level\n"))
    }

    @Test
    fun `an Agent with neither level nor model launches on level 2`() {
        agentTask()
        claude.session("draft", done)
        assertEquals("sonnet" to "medium", modelAndEffort(""))
    }

    @Test
    fun `a pinned model launches on its model ID with the effort of its family's level, and a level goes before it`() {
        agentTask()
        claude.session("draft", done)
        val pinned = mapOf("claude-haiku-4-5-20251001" to null, "claude-sonnet-5-5" to "medium", "claude-opus-5-5[1m]" to "medium")
        for ((model, effort) in pinned) assertEquals(model to effort, modelAndEffort("model: $model\n"))
        assertEquals("opus" to "high", modelAndEffort("level: 4\nmodel: claude-sonnet-5-5\n"))
    }

    private val failed = """{"type":"result","subtype":"error_max_turns","is_error":true,"session_id":"${'$'}SESSION"}"""

    private fun FakeClaude.Launch.level() = flag("--model") to flag("--effort")

    private fun failedOnce(agent: String): List<FakeClaude.Launch> {
        agentTask(agent = agent, retries = 3)
        claude.session("draft", 1, failed)
        claude.session("draft", done)
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        return claude.launches("draft")
    }

    private fun entry(run: String): String {
        var path = ""
        eventually {
            val row = assertNotNull(Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL).find(browser.get("/notifications").body))
            path = Regex("""href="(/notifications/[^"]+)"""").find(row.groupValues[1])!!.groupValues[1]
        }
        return path
    }

    @Test
    fun `a failed Task resumes its session one level up, told that the last attempt failed and why`() {
        val (first, retry) = failedOnce("level: 2\n")
        assertEquals("sonnet" to "medium", first.level())
        assertEquals("opus" to "medium", retry.level())
        assertEquals(first.flag("--session-id"), retry.flag("--resume"))
        assertEquals(null, retry.flag("--session-id"))
        assertContains(retry.stdin, "Your last attempt failed: claude ended without a Result: error_max_turns")
        assertFalse("From the user" in retry.stdin)
    }

    @Test
    fun `a failed Task on level 5 retries on level 5`() {
        val (first, retry) = failedOnce("level: 5\n")
        assertEquals("opus" to "max", first.level())
        assertEquals("opus" to "max", retry.level())
    }

    @Test
    fun `a failed Task with a pinned model retries one level above its family's`() {
        val (first, retry) = failedOnce("model: claude-sonnet-5-5\n")
        assertEquals("claude-sonnet-5-5" to "medium", first.level())
        assertEquals("opus" to "medium", retry.level())
    }

    @Test
    fun `a failed Task with its own level retries one level above it`() {
        agentTask(agent = "level: 1\n", retries = 3)
        claude.session("draft", 1, failed)
        claude.session("draft", done)
        val run = startWith("task-level-retry", property("ants.level", "3"))
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertEquals(listOf("opus" to "medium", "opus" to "high"), claude.launches("draft").map { it.level() })
    }

    @Test
    fun `a Task still failing after the cap's retries becomes an incident`() {
        agentTask(agent = "level: 3\n", retries = 2)
        claude.session("draft", failed)
        val run = start()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>[failed] claude ended without a Result: error_max_turns</dd>") }
        assertEquals(listOf("opus" to "medium", "opus" to "high", "opus" to "max"), claude.launches("draft").map { it.level() })
    }

    @Test
    fun `a stuck Task doesn't retry`() {
        agentTask(retries = 3)
        claude.session("draft", done.replace(""""outcome":"done","summary":"Drafted.","output":{"text":"Hello"}""", """"outcome":"stuck","summary":"No tone.","needs":"A tone.""""))
        val run = start()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>[stuck] No tone. A tone.</dd>") }
        Thread.sleep(500)
        assertEquals(1, claude.launches("draft").size)
    }

    @Test
    fun `Continue and Start fresh on an incident launch on the last level the retries reached`() {
        agentTask(agent = "level: 1\n", retries = 1)
        claude.session("draft", failed)
        val run = start()
        browser.post(entry(run), mapOf("action" to "continue", "instruction" to "Try again."))
        eventually { assertEquals(3, claude.launches("draft").size) }
        browser.post(entry(run), mapOf("action" to "fresh", "instruction" to ""))
        eventually { assertEquals(4, claude.launches("draft").size) }
        assertEquals(listOf("haiku" to null, "sonnet" to "medium", "sonnet" to "medium", "sonnet" to "medium"), claude.launches("draft").map { it.level() })
    }

    @Test
    fun `a Task's own level goes before its Agent's level and pinned model`() {
        agentTask()
        claude.session("draft", done)
        home.resolve("agents/writer.md").writeText("---\nlevel: 1\nmodel: claude-haiku-4-5-20251001\nskills: draft\n---\nWrite plainly.\n")
        val run = startWith("task-level", property("ants.level", "5"))
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val launch = claude.launches("draft").single()
        assertEquals("opus" to "max", launch.flag("--model") to launch.flag("--effort"))
    }

    /** Deploys agent-task.bpmn as [key], its Task on [agent] with [properties] in place of its Skill, and the page the upload answers with. */
    private fun deployOn(key: String, agent: String, properties: String): Browser.Page {
        val bpmn = javaClass.getResource("/agent-task.bpmn")!!.readText().replace("\"agent-task\"", "\"$key\"")
            .replace("""name="ants.agent" value="writer"""", """name="ants.agent" value="$agent"""")
            .replace("""<operaton:property name="ants.skills" value="draft" />""", properties)
        browser.get("/processes")
        return browser.upload("/processes", "file", "$key.bpmn", bpmn.toByteArray())
    }

    private fun runOf(key: String): String {
        browser.get("/processes/$key")
        val run = browser.post("/processes/$key/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
        eventually { assertContains(page(run), """id="state">Completed<""") }
        return run
    }

    @Test
    fun `a Task on a shipped Agent gets a Skill from its published Your Skills, and never one only in their Draft`() {
        agentTask()
        claude.session("draft", done)
        val assigned = property("ants.assignment", "Write it.") // no Skills of its own: it gets its Agent's
        browser.autosave("/agents/shipped-writer/your-skills", mapOf("skills" to "draft"), draftVersion(browser.get("/agents/shipped-writer").body))

        assertContains(deployOn("your-skills-listed", "a-shipped-writer", property("ants.skills", "draft")).body.replace("&#39;", "'"), "The Agent shipped-writer doesn't list the Skill draft.")
        assertFalse("role=\"alert\"" in deployOn("your-skills-assigned", "a-shipped-writer", assigned).body)
        runOf("your-skills-assigned")
        assertEquals(emptyMap(), claude.launches("draft").single().skills)

        assertEquals("/agents/shipped-writer", browser.post("/agents/shipped-writer/your-skills", mapOf("skills" to "draft")).path)
        runOf("your-skills-assigned")
        assertEquals(mapOf("draft" to home.resolve("skills/draft")), claude.launches("draft").last().skills)
        assertFalse("role=\"alert\"" in deployOn("your-skills-listed", "a-shipped-writer", property("ants.skills", "draft")).body)
        runOf("your-skills-listed")
        assertEquals(mapOf("draft" to home.resolve("skills/draft")), claude.launches("draft").last().skills)
    }

    @Test
    fun `a Task listing a Skill its shipped Agent no longer allows, removed from Your Skills since deploy, launches without it`() {
        agentTask()
        claude.session("draft", done)
        assertEquals("/agents/shipped-writer", browser.post("/agents/shipped-writer/your-skills", mapOf("skills" to "draft")).path)
        val listed = property("ants.assignment", "Write it.") + property("ants.skills", "draft")
        assertFalse("role=\"alert\"" in deployOn("your-skills-removed", "a-shipped-writer", listed).body)
        settings(agentTasks = 0) // the Run waits to launch until the Skill is gone from Your Skills
        browser.get("/processes/your-skills-removed")
        val run = browser.post("/processes/your-skills-removed/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")

        assertEquals("/agents/shipped-writer", browser.post("/agents/shipped-writer/your-skills", emptyMap()).path)
        settings()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertEquals(emptyMap(), claude.launches("draft").single().skills)
    }

    @Test
    fun `--json-schema holds the envelope around the Skill's promised output`() {
        agentTask(textSchema)
        claude.session("draft", done)
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val schema = claude.launches("draft").single().schema()
        val properties = schema["properties"] as Map<*, *>
        assertEquals(mapOf("type" to "object", "properties" to mapOf("text" to mapOf("type" to "string")), "required" to listOf("text")), properties["output"])
        assertEquals(mapOf("enum" to listOf("done", "stuck")), properties["outcome"])
        assertEquals(listOf("outcome", "summary"), schema["required"])
        assertEquals(mapOf("required" to listOf("output"), "not" to mapOf("required" to listOf("needs"))), schema["then"])
        assertEquals(mapOf("required" to listOf("needs"), "not" to mapOf("required" to listOf("output"))), schema["else"])
    }

    @Test
    fun `a stuck Result raises an incident holding what the Agent needs, and never completes the Task`() {
        agentTask()
        claude.session("draft", done.replace(""""outcome":"done","summary":"Drafted.","output":{"text":"Hello"}""",
            """"outcome":"stuck","summary":"No tone given.","needs":"Which tone, dry or warm?""""))
        val run = start()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>[stuck] No tone given. Which tone, dry or warm?</dd>") }
        assertContains(page(run), """id="state">Running<""")
    }

    @Test
    fun `an invalid Result resumes the same session once with the error, and a valid second Result completes the Task`() {
        agentTask(textSchema)
        claude.session("draft", done("""{"words":"Hello"}"""), "read", done)
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertContains(page(run), """<dt>draft</dt><dd><code>{"outcome":"done","summary":"Drafted.","output":{"text":"Hello"}}</code></dd>""")
        val retry = claude.launches("draft").single().stdin.lines()[1]
        assertContains(retry, "Your Result is invalid")
        assertContains(retry, "text")
    }

    @Test
    fun `a second invalid Result raises an incident naming the error`() {
        agentTask()
        val invalid = done.replace(""""summary":"Drafted.",""", "")
        claude.session("draft", invalid, "read", invalid)
        val run = start()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>[failed] The Result is invalid: ") }
        assertContains(page(run), "required property 'summary' not found")
        assertContains(page(run), """id="state">Running<""")
    }

    /** A Source map of the two repos si_client_plans and si_client_collateral_analysis, as `source-maps` settings. */
    private fun sourceMap(): String {
        val map = Files.createTempFile("source-map", ".md")
        map.writeText("- si_client_plans: ~/plans (git@example.com:plans.git)\n- si_client_collateral_analysis: ~/collateral (git@example.com:collateral.git)\n")
        return "source-maps:\n  stacc: $map\n"
    }

    private val reposSchema = """{"type":"object","properties":{"repos":{"type":"array","items":{"type":"string","format":"repo"}}},"required":["repos"]}"""

    @Test
    fun `a done Result naming a repo that is neither a Source map repo nor a git URL resumes the session once with the Source map's names`() {
        agentTask(reposSchema, extra = sourceMap())
        claude.session(
            "draft", done("""{"repos":["si_client_plans","si-client-collateral-analysis","git@example.com:other.git"]}"""),
            "read", done("""{"repos":["si_client_plans","si_client_collateral_analysis","git@example.com:other.git"]}"""),
        )
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val retry = claude.launches("draft").single().stdin.lines()[1]
        assertContains(retry, "Your Result is invalid")
        assertContains(retry, "si-client-collateral-analysis is neither a Source map name nor a git URL; the Source map's names are si_client_plans, si_client_collateral_analysis")
    }

    @Test
    fun `a Task whose result schema marks a repo, however deep, gets the Source map's names in its hand-off line`() {
        val nested = """{"type":"object","properties":{"plan":{"type":"object","properties":{"repo":{"type":"string","format":"repo"}}}},"required":["plan"]}"""
        agentTask(nested, extra = sourceMap())
        claude.session("draft", done("""{"plan":{"repo":"si_client_plans"}}"""))
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertContains(
            claude.launches("draft").single().stdin,
            " Name each repo in your Result by its Source map name or a git URL; the Source map's names are si_client_plans, si_client_collateral_analysis.\\n",
        )
    }

    @Test
    fun `a field named repos without format repo is neither checked nor given the Source map's names`() {
        agentTask("""{"type":"object","properties":{"repos":{"type":"array","items":{"type":"string"}}},"required":["repos"]}""", extra = sourceMap())
        claude.session("draft", done("""{"repos":["the quarter's plans"]}"""))
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val stdin = claude.launches("draft").single().stdin
        assertEquals(1, stdin.lines().filter(String::isNotEmpty).size) // the hand-off alone, no fix request
        assertFalse("Source map" in stdin)
    }

    @Test
    fun `a file reference in output that escapes the Run workspace is rejected as invalid`() {
        agentTask(fileSchema)
        claude.session("draft", done("""{"file":"../other-run/notes.md"}"""), "read", done("""{"file":"/etc/passwd"}"""))
        val run = start()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>[failed] The Result is invalid: ") }
        assertContains(page(run), "/etc/passwd")
        assertContains(claude.launches("draft").single().stdin.lines()[1], "/output/file: does not match the path pattern")
    }

    @Test
    fun `a file reference inside the Run workspace is valid`() {
        agentTask(fileSchema)
        claude.session("draft", done("""{"file":"tickets/../tickets/spec.md"}"""))
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
    }

    @Test
    fun `a file reference through a symlink out of the Run workspace is rejected as invalid`() {
        agentTask(fileSchema)
        val outside = Files.createTempDirectory("outside").also { it.resolve("secret.md").writeText("secret") }
        claude.session("draft", "wait release", done("""{"file":"link/secret.md"}"""), "read", done("""{"file":"."}"""))
        val run = start()
        Files.createSymbolicLink(workspace(run).resolve("link"), outside)
        claude.release("release")
        eventually { assertContains(page(run), "<dt>draft</dt><dd>[failed] The Result is invalid: ") }
        assertContains(claude.launches("draft").single().stdin.lines()[1], "/output/file: does not match the path pattern")
    }

    @Test
    fun `a result line without a Result is a failure, not an invalid Result to retry`() {
        agentTask()
        claude.session("draft", """{"type":"result","subtype":"error_max_turns","is_error":true,"session_id":"${'$'}SESSION"}""", "read", done)
        val run = start()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>[failed] claude ended without a Result: error_max_turns</dd>") }
        assertEquals(1, claude.launches("draft").single().stdin.lines().size)
    }

    @Test
    fun `the CLI gets Ants' headless flags, a narrowed environment and the hand-off prompt`() {
        agentTask(agent = "name: writer\ndescription: Writes.\ntools: Read, Grep, Skill, Bash(git log:*)\nmodel: sonnet\n")
        claude.session("draft", done)
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val launch = claude.launches("draft").single()
        val argv = launch.argv.joinToString(" ")
        for (flags in listOf(
            "-p", "--output-format stream-json --verbose --include-partial-messages", "--input-format stream-json --replay-user-messages",
            "--permission-mode dontAsk", "--setting-sources user,project", """--settings {"disableAllHooks":true,"attribution":{"commit":"","pr":"🐜 Created with [Ants](https://github.com/oyvij/ants)"}}""",
            "--strict-mcp-config", "--append-system-prompt Write plainly.", "--model sonnet",
            "--add-dir ${home.resolve("skills/draft")}",
        )) assertContains(argv, flags)
        assertFalse("--disable-slash-commands" in launch.argv)
        assertEquals("Read,Grep,Bash,Skill", launch.flag("--tools")) // the Agent's own Skill rule goes, Ants' Skill comes
        assertEquals("Read,Grep,Bash(git log:*),Skill,mcp__ants__run", launch.flag("--allowedTools"))
        assertContains(argv, Regex("--session-id [0-9a-f-]{36}"))
        assertEquals(workspace(run).toRealPath().toString(), launch.cwd)

        assertEquals(null, launch.env["ANTHROPIC_API_KEY"])
        assertEquals(null, launch.env["CLAUDE_CODE_RETRY_WATCHDOG"])
        assertEquals(home.resolve("claude").toString(), launch.env["CLAUDE_CONFIG_DIR"])
        assertEquals("1", launch.env["CLAUDE_CODE_DISABLE_BACKGROUND_TASKS"])
        assertEquals("1", launch.env["CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH"])
        assertEquals(null, launch.env["JAVA_HOME"]) // outside the allow-list

        // A Task no Answer the Round names gets the ranking and no Round protocol.
        val prompt = "/draft The workspace is ${workspace(run)}." +
            "\\n\\nWhere instructions disagree, the Task's Assignment wins, then your Agent's instructions, then the Skill's, " +
            "then a repo's own CLAUDE.md or AGENTS.md, then the user's Profile instructions in the workspace's CLAUDE.md or AGENTS.md." +
            """\n\n{\"prompt\":\"Say hello\",\"tone\":\"dry\"}"""
        assertEquals("""{"type":"user","message":{"role":"user","content":"$prompt"}}""", launch.stdin)
    }

    @Test
    fun `a relative Write or Edit rule goes out anchored at the folder the Agent starts in, so a cd doesn't move it`() {
        agentTask(agent = "name: writer\ndescription: Writes.\ntools: Read, Write(out/**), Edit(./notes/**), Write(//tmp/x/**), Write, Bash(git log *)\n")
        claude.session("draft", done)
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val argv = claude.launches("draft").single().argv
        val ws = workspace(run)
        assertEquals(
            "Read,Write(/$ws/out/**),Edit(/$ws/notes/**),Write(//tmp/x/**),Write,Bash(git log *),Edit(/$ws/out/**),Edit(//tmp/x/**),Skill,mcp__ants__run",
            argv[argv.indexOf("--allowedTools") + 1],
        )
        assertEquals("Read,Write,Edit,Bash,Skill", argv[argv.indexOf("--tools") + 1])
    }

    @Test
    fun `a resumed session gets the same skills folder and flags as its first launch`() {
        agentTask()
        claude.session("draft", done)
        val run = startWith("resumed-skills", property("ants.resume", "true"), file = "agent-loop")
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val (first, again) = claude.launches("draft")
        assertEquals(skillsFolder(first), skillsFolder(again))
        assertEquals(first.flag("--tools"), again.flag("--tools"))
        assertEquals(first.flag("--allowedTools"), again.flag("--allowedTools"))
        assertEquals(mapOf("draft" to home.resolve("skills/draft")), again.skills)
    }

    @Test
    fun `with the cap at 1 a second ready Task waits, and ready Tasks launch oldest first across Runs`() {
        agentTask(agentTasks = 1)
        claude.session("draft", "wait release", done)
        val runs = List(3) { start() }
        eventually { assertEquals(1, claude.launchLog().size) }
        assertContains(page(runs[0]), "<dt>draft</dt><dd>Running</dd>")
        assertContains(page(runs[1]), "<dt>draft</dt><dd>Queued: 1 of 1 Agent Tasks running</dd>")
        claude.release("release")
        for (run in runs) eventually { assertContains(page(run), """id="state">Completed<""") }
        assertEquals(runs.map { workspace(it).toRealPath().toString() }, claude.launchLog())
    }

    @Test
    fun `the Task fails loudly, before any session, once claude -p turns on --bare by default`() {
        agentTask()
        claude.session("draft", done)
        claude.help("Options:\n  --bare      Minimal mode, the default with -p: never reads OAuth\n  --verbose   Says more\n")
        val run = start()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>[failed] claude -p now turns on --bare by default") }
        assertEquals(emptyList(), claude.launches("draft"))
    }

    @Test
    fun `today's --bare, off by default, passes the guard`() {
        agentTask()
        claude.session("draft", done)
        claude.help("Options:\n  --bare      Minimal mode: skip hooks, LSP and keychain reads\n  --verbose   The default is quiet\n")
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
    }

    @Test
    fun `a file without frontmatter is an Agent with defaults, named after the file`() {
        agentTask() // listing draft, so the Process deploys
        claude.session("draft", done)
        settings(agentTasks = 0) // the pre-Run check needs the Agent to list draft; the launch, once allowed, reads the file without
        val run = start()
        home.resolve("agents/writer.md").writeText("Write plainly.\n")
        settings()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val argv = claude.launches("draft").single().argv.joinToString(" ")
        assertContains(argv, "--append-system-prompt Write plainly.")
        assertContains(argv, "--tools Bash,Edit,Glob,Grep,NotebookEdit,Read,TaskCreate,TaskGet,TaskList,TaskUpdate,WebFetch,WebSearch,Write")
        assertContains(argv, "--model sonnet --effort medium")
        assertEquals(emptyMap<String, Any>(), (claude.launches("draft").single().schema()["properties"] as Map<*, *>)["output"])
    }

    /** Puts the clock back and opens claude's gate, which a failed pause test could leave shut for every later test. */
    @AfterEach
    fun putTheClockBack() {
        clock.offset = Duration.ZERO
        browser.get("/processes") // for a CSRF token
        browser.post("/pauses/claude", emptyMap())
    }

    /** A Usage limit rejection, as claude reports it, resetting at [resetsAt]. */
    private fun rejected(resetsAt: Instant) = """{"type":"rate_limit_event","rate_limit_info":{"status":"rejected",""" +
        """"resetsAt":${resetsAt.epochSecond},"rateLimitType":"five_hour","isUsingOverage":false},"session_id":"${'$'}SESSION"}"""

    /** The nav's pill as [page] shows it: the nav fills its slot from the page's stream. */
    private fun pill(page: String): String {
        assertContains(browser.get(page).body, """<span id="paused"></span>""", message = "$page has no pill in its nav")
        return browser.event("", "pauses")
    }

    @Test
    fun `a rejection pauses the Task until resetsAt plus one minute, then resumes the same session with the reset message`() {
        agentTask(retries = 3)
        val resetsAt = clock.instant().plus(Duration.ofHours(1))
        claude.session("draft", 1, rejected(resetsAt), "wait never") // claude carries on; Ants kills it
        claude.session("draft", done)
        val run = start()
        val until = clock.local(Instant.ofEpochSecond(resetsAt.epochSecond).plusSeconds(60))
        eventually { assertContains(page(run), "<dt>draft</dt><dd>Paused until $until</dd>") }
        assertContains(pill("/runs/$run"), "claude paused until $until")
        assertContains(browser.events("run=$run", "run").poll(10, SECONDS).orEmpty(), "\"badge\":\"Paused until $until\"") // the diagram's badge
        assertFalse("/runs/$run\"" in browser.get("/notifications").body) // a pause waits on nobody

        clock.offset = Duration.ofHours(1) // past resetsAt, not yet the minute after
        Thread.sleep(600)
        assertEquals(1, claude.launches("draft").size)
        clock.offset = Duration.ofMinutes(62)
        eventually { assertContains(page(run), """id="state">Completed<""") }

        val (first, resumed) = claude.launches("draft")
        assertEquals(first.flag("--session-id"), resumed.flag("--resume"))
        assertEquals(null, resumed.flag("--session-id"))
        assertEquals("""{"type":"user","message":{"role":"user","content":"The usage limit has reset. Carry on where you stopped."}}""", resumed.stdin)
        assertFalse("paused" in pill("/runs/$run"))
    }

    @Test
    fun `while claude is paused other ready Tasks don't launch, and Try now opens the gate and launches them`() {
        agentTask()
        val resetsAt = clock.instant().plus(Duration.ofHours(1))
        claude.session("draft", 1, rejected(resetsAt), "wait never")
        claude.session("draft", done)
        val first = start()
        val paused = "<dt>draft</dt><dd>Paused until ${clock.local(Instant.ofEpochSecond(resetsAt.epochSecond).plusSeconds(60))}</dd>"
        eventually { assertContains(page(first), paused) }
        val second = start()
        eventually { assertContains(page(second), paused) }
        assertEquals(1, claude.launches("draft").size)

        val runs = pill("/runs")
        assertContains(runs, "claude paused until")
        assertContains(runs, """action="/pauses/claude"""")
        assertContains(runs, "Try now")
        browser.post("/pauses/claude", emptyMap())
        for (run in listOf(first, second)) eventually { assertContains(page(run), """id="state">Completed<""") }
        val (cut, after1, after2) = claude.launches("draft") // the two after Try now launch together
        assertEquals(setOf(cut.flag("--session-id"), null), setOf(after1.flag("--resume"), after2.flag("--resume")))
        assertFalse("Try now" in pill("/runs"))
    }

    /** Plays [limit] as the first launch and checks that claude pauses for 30 minutes and resumes after. */
    private fun pausesFor30Minutes(vararg limit: String) {
        agentTask()
        claude.session("draft", 1, *limit, "wait never")
        claude.session("draft", done)
        val before = clock.instant()
        val run = start()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>Paused until ") }
        val after = clock.instant()
        assertContains(setOf(clock.local(before.plusSeconds(31 * 60)), clock.local(after.plusSeconds(31 * 60))), page(run).substringAfter("Paused until ").substringBefore("<"))
        clock.offset = Duration.ofMinutes(32)
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertContains(claude.launches("draft").last().argv, "--resume")
    }

    @Test
    fun `a 429 with no rejection pauses claude for 30 minutes`() = pausesFor30Minutes(
        """{"type":"result","subtype":"success","is_error":true,"api_error_status":429,"result":"API Error: Request rejected (429)","session_id":"${'$'}SESSION"}""",
    )

    @Test
    fun `a Subagent retrying on the rate limit pauses claude for 30 minutes`() = pausesFor30Minutes(
        """{"type":"tool_progress","tool_use_id":"agent_msg_1","tool_name":"Agent","parent_tool_use_id":"toolu_1","elapsed_time_seconds":0,""" +
            """"session_id":"${'$'}SESSION","subagent_type":"general-purpose","subagent_retry":{"agent_id":"a1","attempt":1,"max_retries":10,""" +
            """"retry_delay_ms":1000,"error_status":429,"error_category":"rate_limit"}}""",
    )
}

/** The fake `claude`, copied into a folder of its own, where tests write its sessions and read what it recorded. */
class FakeClaude {
    val dir: Path = Files.createTempDirectory("fake-claude")
    val command: Path = dir.resolve("claude")

    init {
        Files.copy(javaClass.getResourceAsStream("/fake-claude/claude")!!, command)
        command.toFile().setExecutable(true)
        dir.resolve("sessions").createDirectories()
        // Logged in, as the pre-Run check needs; a test of the login writes its own status over this.
        AntsTest.home.resolve("claude").createDirectories().resolve("auth-status.json")
            .writeText("""{"loggedIn":true,"authMethod":"claude.ai","apiKeySource":"none"}""")
    }

    fun session(skill: String, vararg lines: String) = dir.resolve("sessions/$skill.jsonl").writeText(lines.joinToString("\n", postfix = "\n"))

    /** The session the [n]th launch of [skill] plays instead of its usual one. */
    fun session(skill: String, n: Int, vararg lines: String) = dir.resolve("sessions/$skill.$n.jsonl").writeText(lines.joinToString("\n", postfix = "\n"))

    fun release(file: String) = dir.resolve(file).createFile()

    fun help(text: String) = dir.resolve("help").writeText(text)

    /** The launches of [skill], in launch order, each once its record is written (its `stdin` comes last). */
    fun launches(skill: String): List<Launch> =
        if (dir.resolve("launches").isDirectory()) dir.resolve("launches").listDirectoryEntries("$skill.*")
            .filter { it.resolve("stdin").exists() }
            .sortedBy { it.fileName.toString().substringAfterLast('.').toInt() }.map(::Launch) else emptyList()

    /** The working directory of every launch, in launch order. */
    fun launchLog(): List<String> = dir.resolve("launches.log").let { if (Files.exists(it)) it.readLines() else emptyList() }

    /** One launch's record, read when asked: a launch just started may not have written it yet. */
    class Launch(private val dir: Path) {
        val argv get() = dir.resolve("argv").readLines()

        /** The value after [flag] in argv, or null. */
        fun flag(flag: String) = argv.let { argv -> argv.indexOf(flag).takeIf { it >= 0 }?.let { argv[it + 1] } }
        val env get() = dir.resolve("env").readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        val cwd get() = dir.resolve("cwd").readText().trim()
        val stdin get() = dir.resolve("stdin").readText().trim()

        /** The skills its --add-dirs' `.claude/skills` linked at launch, by name, to the folder each links. */
        val skills get() = dir.resolve("skills").readLines().associate { it.substringBefore(' ') to Path.of(it.substringAfter(' ')) }

        /** A file the launch's session wrote into its record (`$launch`). */
        fun file(name: String) = dir.resolve(name).readText()

        /** Whether Ants killed the launch. */
        val killed get() = dir.resolve("killed").exists()
    }
}

/** Waits for the live update, an SSE event's data, in which [check] passes. */
fun java.util.concurrent.BlockingQueue<String>.until(check: (String) -> Boolean): String {
    while (true) {
        val html = assertNotNull(poll(10, SECONDS), "no matching update within 10 seconds")
        if (check(html)) return html
    }
}

/** Retries [check] until it passes or [seconds] are up. */
fun eventually(seconds: Long = 10, check: () -> Unit) {
    val deadline = System.nanoTime() + seconds * 1_000_000_000
    while (true) {
        try {
            return check()
        } catch (e: Throwable) { // a launch's files, read through [FakeClaude.Launch], may not be written yet
            if (e !is AssertionError && e !is NoSuchFileException || System.nanoTime() > deadline) throw e
            Thread.sleep(100)
        }
    }
}
