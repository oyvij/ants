package ants

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AgentTaskTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    private fun settings(agentTasks: Int = 3) = home.resolve("settings.yaml").writeText(
        """
        agent-tasks: $agentTasks
        claude:
          command: [${claude.command}]
          env: [PATH, HOME, ANTHROPIC_API_KEY, CLAUDE_CODE_RETRY_WATCHDOG]
        """.trimIndent(),
    )

    private fun agent(text: String) = home.resolve("agents").createDirectories().resolve("writer.md").writeText(text)

    private fun skill(schema: String? = null) {
        val folder = home.resolve("skills/draft").createDirectories()
        folder.resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts a text.\n---\nDraft the text.\n")
        folder.resolve("result.schema.json").run { if (schema != null) writeText(schema) else deleteIfExists() }
    }

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

    private fun page(run: String) = browser.get("/runs/$run").body.replace("&#34;", "\"")

    @Test
    fun `a Process with one Agent Task ends with the Task's Result stored under its Task id`() {
        settings()
        agent("---\nname: writer\ndescription: Writes.\nmodel: sonnet\n---\nWrite plainly.\n")
        skill()
        claude.session("draft", """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}""", done)
        val run = start()
        eventually { assertContains(page(run), "State: Completed") }
        assertContains(page(run), """<dt>draft</dt><dd><code>{"outcome":"done","summary":"Drafted.","output":{"text":"Hello"}}</code></dd>""")
    }

    @Test
    fun `--json-schema holds the envelope around the Skill's promised output`() {
        settings()
        agent("Write plainly.\n")
        skill(textSchema)
        claude.session("draft", done)
        val run = start()
        eventually { assertContains(page(run), "State: Completed") }
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
        settings()
        agent("Write plainly.\n")
        skill()
        claude.session("draft", done.replace(""""outcome":"done","summary":"Drafted.","output":{"text":"Hello"}""",
            """"outcome":"stuck","summary":"No tone given.","needs":"Which tone, dry or warm?""""))
        val run = start()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>[stuck] No tone given. Which tone, dry or warm?</dd>") }
        assertContains(page(run), "State: Running")
    }

    @Test
    fun `an invalid Result resumes the same session once with the error, and a valid second Result completes the Task`() {
        settings()
        agent("Write plainly.\n")
        skill(textSchema)
        claude.session("draft", done("""{"words":"Hello"}"""), "read", done)
        val run = start()
        eventually { assertContains(page(run), "State: Completed") }
        assertContains(page(run), """<dt>draft</dt><dd><code>{"outcome":"done","summary":"Drafted.","output":{"text":"Hello"}}</code></dd>""")
        val retry = claude.launches("draft").single().stdin.lines()[1]
        assertContains(retry, "Your Result is invalid")
        assertContains(retry, "text")
    }

    @Test
    fun `a second invalid Result raises an incident naming the error`() {
        settings()
        agent("Write plainly.\n")
        skill()
        val invalid = done.replace(""""summary":"Drafted.",""", "")
        claude.session("draft", invalid, "read", invalid)
        val run = start()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>[failed] The Result is invalid: ") }
        assertContains(page(run), "required property 'summary' not found")
        assertContains(page(run), "State: Running")
    }

    @Test
    fun `a file reference in output that escapes the Run workspace is rejected as invalid`() {
        settings()
        agent("Write plainly.\n")
        skill(fileSchema)
        claude.session("draft", done("""{"file":"../other-run/notes.md"}"""), "read", done("""{"file":"/etc/passwd"}"""))
        val run = start()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>[failed] The Result is invalid: ") }
        assertContains(page(run), "/etc/passwd")
        assertContains(claude.launches("draft").single().stdin.lines()[1], "/output/file: does not match the path pattern")
    }

    @Test
    fun `a file reference inside the Run workspace is valid`() {
        settings()
        agent("Write plainly.\n")
        skill(fileSchema)
        claude.session("draft", done("""{"file":"tickets/../tickets/spec.md"}"""))
        val run = start()
        eventually { assertContains(page(run), "State: Completed") }
    }

    @Test
    fun `a file reference through a symlink out of the Run workspace is rejected as invalid`() {
        settings()
        agent("Write plainly.\n")
        skill(fileSchema)
        val outside = Files.createTempDirectory("outside").also { it.resolve("secret.md").writeText("secret") }
        claude.session("draft", "wait release", done("""{"file":"link/secret.md"}"""), "read", done("""{"file":"."}"""))
        val run = start()
        Files.createSymbolicLink(home.resolve("runs/$run/link"), outside)
        claude.release("release")
        eventually { assertContains(page(run), "<dt>draft</dt><dd>[failed] The Result is invalid: ") }
        assertContains(claude.launches("draft").single().stdin.lines()[1], "/output/file: does not match the path pattern")
    }

    @Test
    fun `a result line without a Result is a failure, not an invalid Result to retry`() {
        settings()
        agent("Write plainly.\n")
        skill()
        claude.session("draft", """{"type":"result","subtype":"error_max_turns","is_error":true,"session_id":"${'$'}SESSION"}""", "read", done)
        val run = start()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>[failed] claude ended without a Result: error_max_turns</dd>") }
        assertEquals(1, claude.launches("draft").single().stdin.lines().size)
    }

    @Test
    fun `the CLI gets Ants' headless flags, a narrowed environment and the hand-off prompt`() {
        settings()
        agent("---\nname: writer\ndescription: Writes.\ntools: Read, Grep, Skill, Bash(git log:*)\nmodel: sonnet\n---\nWrite plainly.\n")
        skill()
        claude.session("draft", done)
        val run = start()
        eventually { assertContains(page(run), "State: Completed") }
        val launch = claude.launches("draft").single()
        val argv = launch.argv.joinToString(" ")
        for (flags in listOf(
            "-p", "--output-format stream-json --verbose --include-partial-messages", "--input-format stream-json --replay-user-messages",
            "--permission-mode dontAsk", "--setting-sources user,project", """--settings {"disableAllHooks":true}""",
            "--strict-mcp-config", "--disable-slash-commands", "--append-system-prompt Write plainly.", "--model sonnet",
            "--tools Read,Grep,Bash", "--allowedTools Read,Grep,Bash(git log:*)", "--add-dir ${home.resolve("skills/draft")}",
        )) assertContains(argv, flags)
        assertContains(argv, Regex("--session-id [0-9a-f-]{36}"))
        assertEquals(home.resolve("runs").toRealPath().resolve(run).toString(), launch.cwd)

        assertEquals(null, launch.env["ANTHROPIC_API_KEY"])
        assertEquals(null, launch.env["CLAUDE_CODE_RETRY_WATCHDOG"])
        assertEquals(home.resolve("claude").toString(), launch.env["CLAUDE_CONFIG_DIR"])
        assertEquals("1", launch.env["CLAUDE_CODE_DISABLE_BACKGROUND_TASKS"])
        assertEquals("1", launch.env["CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH"])
        assertEquals(null, launch.env["JAVA_HOME"]) // outside the allow-list

        val prompt = "Follow the Skill in ${home.resolve("skills/draft/SKILL.md")} for the workspace at ${home.resolve("runs/$run")}." +
            """\n\n{\"prompt\":\"Say hello\",\"tone\":\"dry\"}"""
        assertEquals("""{"type":"user","message":{"role":"user","content":"$prompt"}}""", launch.stdin)
    }

    @Test
    fun `with the cap at 1 a second ready Task waits, and ready Tasks launch oldest first across Runs`() {
        settings(agentTasks = 1)
        agent("Write plainly.\n")
        skill()
        claude.session("draft", "wait release", done)
        val runs = List(3) { start() }
        eventually { assertEquals(1, claude.launchLog().size) }
        assertContains(page(runs[0]), "<dt>draft</dt><dd>Running</dd>")
        assertContains(page(runs[1]), "<dt>draft</dt><dd>Queued: 1 of 1 Agent Tasks running</dd>")
        claude.release("release")
        for (run in runs) eventually { assertContains(page(run), "State: Completed") }
        assertEquals(runs.map { home.resolve("runs").toRealPath().resolve(it).toString() }, claude.launchLog())
    }

    @Test
    fun `the Task fails loudly, before any session, once claude -p turns on --bare by default`() {
        settings()
        agent("Write plainly.\n")
        skill()
        claude.session("draft", done)
        claude.help("Options:\n  --bare      Minimal mode, the default with -p: never reads OAuth\n  --verbose   Says more\n")
        val run = start()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>[failed] claude -p now turns on --bare by default") }
        assertEquals(emptyList(), claude.launches("draft"))
    }

    @Test
    fun `today's --bare, off by default, passes the guard`() {
        settings()
        agent("Write plainly.\n")
        skill()
        claude.session("draft", done)
        claude.help("Options:\n  --bare      Minimal mode: skip hooks, LSP and keychain reads\n  --verbose   The default is quiet\n")
        val run = start()
        eventually { assertContains(page(run), "State: Completed") }
    }

    @Test
    fun `a file without frontmatter is an Agent with defaults, named after the file`() {
        settings()
        agent("Write plainly.\n")
        skill()
        claude.session("draft", done)
        val run = start()
        eventually { assertContains(page(run), "State: Completed") }
        val argv = claude.launches("draft").single().argv.joinToString(" ")
        assertContains(argv, "--append-system-prompt Write plainly.")
        assertContains(argv, "--tools Bash,Edit,Glob,Grep,NotebookEdit,Read,TodoWrite,WebFetch,WebSearch,Write")
        assertFalse("--model" in argv)
        assertEquals(emptyMap<String, Any>(), (claude.launches("draft").single().schema()["properties"] as Map<*, *>)["output"])
    }
}

/** The fake `claude`, copied into a folder of its own, where tests write its sessions and read what it recorded. */
class FakeClaude {
    val dir: Path = Files.createTempDirectory("fake-claude")
    val command: Path = dir.resolve("claude")

    init {
        Files.copy(javaClass.getResourceAsStream("/fake-claude/claude")!!, command)
        command.toFile().setExecutable(true)
        dir.resolve("sessions").createDirectories()
    }

    fun session(skill: String, vararg lines: String) = dir.resolve("sessions/$skill.jsonl").writeText(lines.joinToString("\n", postfix = "\n"))

    fun release(file: String) = dir.resolve(file).createFile()

    fun help(text: String) = dir.resolve("help").writeText(text)

    fun launches(skill: String): List<Launch> =
        if (dir.resolve("launches").isDirectory()) dir.resolve("launches").listDirectoryEntries("$skill.*").map(::Launch) else emptyList()

    /** The working directory of every launch, in launch order. */
    fun launchLog(): List<String> = dir.resolve("launches.log").let { if (Files.exists(it)) it.readLines() else emptyList() }

    class Launch(dir: Path) {
        val argv = dir.resolve("argv").readLines()
        val env = dir.resolve("env").readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        val cwd = dir.resolve("cwd").readText().trim()
        val stdin = dir.resolve("stdin").readText().trim()
    }
}

/** Retries [check] until it passes or ten seconds are up. */
fun eventually(check: () -> Unit) {
    val deadline = System.nanoTime() + 10_000_000_000
    while (true) {
        try {
            return check()
        } catch (e: AssertionError) {
            if (System.nanoTime() > deadline) throw e
            Thread.sleep(100)
        }
    }
}
