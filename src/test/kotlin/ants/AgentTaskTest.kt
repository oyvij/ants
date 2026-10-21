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
    private fun agentTask(schema: String? = null, agent: String = "", metadata: String = "", agentTasks: Int = 3, extra: String = "") =
        agentTask(claude, mapOf("draft" to schema), agent, metadata, "agent-tasks: $agentTasks\n$extra", env)

    /** A Skill [name] that only other Skills use, its frontmatter carrying [metadata]. */
    private fun usedSkill(name: String, metadata: String = ""): Path = home.resolve("skills/used/$name").createDirectories().resolve("SKILL.md")
        .also { it.writeText("---\nname: $name\ndescription: Used.\n$metadata---\nUse it.\n") }

    private fun uses(names: String) = "metadata:\n  ants-uses: $names\n"

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
            .replaceFirst("""<operaton:property name="ants.skill" value="draft" />""", """<operaton:property name="ants.skill" value="draft" />$properties""")
        browser.get("/processes")
        assertFalse("role=\"alert\"" in browser.upload("/processes", "file", "$key.bpmn", bpmn.toByteArray()).body)
        browser.get("/processes/$key")
        return browser.post("/processes/$key/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    private fun property(name: String, value: String) = """<operaton:property name="$name" value="${value.replace("\"", "&quot;")}" />"""

    @Test
    fun `a test's Skill named like a shipped one fails at once, since the shipped Skill would hide it`() {
        val failure = kotlin.test.assertFailsWith<IllegalArgumentException> { agentTask(claude, mapOf("grill" to null)) }
        assertEquals("grill is hidden by the shipped Skill of that name; give the test's Skill another name.", failure.message)
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
    fun `the Vault and Answer History toggles reach the hand-off line and --add-dir only when on, defaulting from the Skill`() {
        val map = Files.createTempFile("source-map", ".md")
        agentTask(metadata = "metadata:\n  varde-vault: true\n", extra = "source-maps:\n  mine: $map\n")
        claude.session("draft", done)
        val byDefault = startWith("toggles-default", "")
        eventually { assertContains(page(byDefault), """id="state">Completed<""") }
        val defaults = claude.launches("draft").last().stdin
        assertContains(defaults, "for the workspace at ${workspace(byDefault)}. The Vault is $vaultPath and the Source map is $map.\\n")
        assertFalse("Answer History" in defaults)
        assertContains(claude.launches("draft").last().argv.zipWithNext(), "--add-dir" to "$vaultPath")

        val flipped = startWith("toggles-flipped", property("ants.vault", "false") + property("ants.answer-history", "true"))
        eventually { assertContains(page(flipped), """id="state">Completed<""") }
        val toggled = claude.launches("draft").last().stdin
        assertContains(toggled, "for the workspace at ${workspace(flipped)}. The Answer History is ${home.resolve("answer-history")}.\\n")
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

    private fun page(run: String) = browser.get("/runs/$run").body.replace("&#34;", "\"")

    @Test
    fun `a Process with one Agent Task ends with the Task's Result stored under its Task id`() {
        agentTask(agent = "name: writer\ndescription: Writes.\nmodel: sonnet\n")
        claude.session("draft", """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}""", done)
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertContains(page(run), """<dt>draft</dt><dd><code>{"outcome":"done","summary":"Drafted.","output":{"text":"Hello"}}</code></dd>""")
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
            "--permission-mode dontAsk", "--setting-sources user,project", """--settings {"disableAllHooks":true}""",
            "--strict-mcp-config", "--disable-slash-commands", "--append-system-prompt Write plainly.", "--model sonnet",
            "--tools Read,Grep,Bash", "--allowedTools Read,Grep,Bash(git log:*)", "--add-dir ${home.resolve("skills/draft")}",
        )) assertContains(argv, flags)
        assertContains(argv, Regex("--session-id [0-9a-f-]{36}"))
        assertEquals(workspace(run).toRealPath().toString(), launch.cwd)

        assertEquals(null, launch.env["ANTHROPIC_API_KEY"])
        assertEquals(null, launch.env["CLAUDE_CODE_RETRY_WATCHDOG"])
        assertEquals(home.resolve("claude").toString(), launch.env["CLAUDE_CONFIG_DIR"])
        assertEquals("1", launch.env["CLAUDE_CODE_DISABLE_BACKGROUND_TASKS"])
        assertEquals("1", launch.env["CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH"])
        assertEquals(null, launch.env["JAVA_HOME"]) // outside the allow-list

        val prompt = "Follow the Skill in ${home.resolve("skills/draft/SKILL.md")} for the workspace at ${workspace(run)}." +
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
            "Read,Write(/$ws/out/**),Edit(/$ws/notes/**),Write(//tmp/x/**),Write,Bash(git log *),Edit(/$ws/out/**),Edit(//tmp/x/**),mcp__ants__run",
            argv[argv.indexOf("--allowedTools") + 1],
        )
        assertEquals("Read,Write,Edit,Bash", argv[argv.indexOf("--tools") + 1])
    }

    @Test
    fun `a two-level ants-uses chain puts every used Skill in the hand-off line and in --add-dir`() {
        agentTask(metadata = uses("implement-tdd"))
        val tdd = usedSkill("implement-tdd", uses("seams review"))
        val seams = usedSkill("seams")
        val review = usedSkill("review", uses("seams"))
        claude.session("draft", done)
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val launch = claude.launches("draft").single()
        assertContains(launch.stdin, "for the workspace at ${workspace(run)}. It uses the Skills implement-tdd ($tdd), seams ($seams), review ($review).")
        val argv = launch.argv.joinToString(" ")
        for (folder in listOf(home.resolve("skills/draft"), tdd.parent, seams.parent, review.parent)) assertContains(argv, "--add-dir $folder")
    }

    @Test
    fun `an ants-uses cycle fails before launch, naming the cycle`() {
        agentTask(metadata = uses("implement-tdd"), agentTasks = 0) // the pre-Run check refuses a cycle, so it closes after the Run starts
        usedSkill("implement-tdd", uses("seams"))
        usedSkill("seams")
        claude.session("draft", done)
        val run = start()
        usedSkill("seams", uses("draft"))
        settings()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>[failed] The Skills draft → implement-tdd → seams → draft use each other in a cycle</dd>") }
        assertEquals(emptyList(), claude.launches("draft"))
    }

    @Test
    fun `a used Skill's Vault opt-in turns the Task's Vault on`() {
        agentTask(metadata = uses("search"))
        usedSkill("search", "metadata:\n  varde-vault: true\n")
        claude.session("draft", done)
        assertTrue(catalogue.vault(catalogue.skill("draft")))
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertContains(claude.launches("draft").single().argv.zipWithNext(), "--add-dir" to "$vaultPath")
        usedSkill("search")
        assertFalse(catalogue.vault(catalogue.skill("draft")))
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
        assertFalse("--model" in argv)
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
        agentTask()
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
