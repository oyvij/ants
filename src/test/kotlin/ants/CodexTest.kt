package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodexTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val codex = FakeCodex()
    private val env = listOf("PATH", "HOME", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "CODEX_API_KEY")

    /** The Agent `writer` on codex, with [agent] lines in its frontmatter, listing the Skill `draft`. */
    private fun codexTask(agent: String = "", claude: FakeClaude? = null, metadata: String = "", profiles: String = "") {
        agentTask(claude, agent = "cli: codex\n$agent", metadata = metadata)
        home.resolve("settings.yaml").writeAtomically(
            "codex:\n  command: [${codex.command}]\n  env: [${env.joinToString(", ")}]\n" +
                (if (claude == null) "" else "claude:\n  command: [${claude.command}]\n") + vault + profiles,
        )
    }

    private val started = """{"type":"thread.started","thread_id":"${'$'}SESSION"}"""
    private val turn = """{"type":"turn.started"}"""

    /** The Agent's final message, its Result as JSON text, as codex gives it under the strict `--output-schema`: every key, `needs` null. */
    private fun answer(summary: String = "Drafted.") = """{"type":"item.completed","item":{"id":"item_9","type":"agent_message",""" +
        """"text":"{\"outcome\":\"done\",\"summary\":\"$summary\",\"output\":{\"text\":\"Hello\"},\"needs\":null}"}}"""
    private val completed = """{"type":"turn.completed","usage":{"input_tokens":1200,"cached_input_tokens":200,"output_tokens":34}}"""

    private fun start(key: String = "agent-task", agent: String = "writer", prompt: String = "Say hello", properties: String = ""): String {
        val bpmn = javaClass.getResource("/agent-task.bpmn")!!.readText().replace("\"agent-task\"", "\"$key\"")
            .replace("""name="ants.agent" value="writer"""", """name="ants.agent" value="$agent"""")
            .replace("""<operaton:property name="ants.skills" value="draft" />""", """<operaton:property name="ants.skills" value="draft" />$properties""")
        browser.get("/processes")
        assertFalse("role=\"alert\"" in browser.upload("/processes", "file", "$key.bpmn", bpmn.toByteArray()).body)
        browser.get("/processes/$key")
        return browser.post("/processes/$key/runs", mapOf("prompt" to prompt)).path.removePrefix("/runs/")
    }

    private fun page(run: String) = browser.get("/runs/$run").body.replace("&#34;", "\"")

    @Test
    fun `a codex Agent Task runs codex exec on Ants' own codex home, with a sandbox instead of tools, and completes`() {
        codexTask("sandbox: read-only\nmodel: gpt-5-codex\n")
        codex.session(
            "draft", started, turn,
            """{"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"Writing a **short** draft."}}""",
            """{"type":"item.started","item":{"id":"item_2","type":"command_execution","command":"bash -lc ls","aggregated_output":"","exit_code":null,"status":"in_progress"}}""",
            answer(), completed,
        )
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertContains(page(run), "Drafted.")

        val launch = codex.launches("draft").single()
        val argv = launch.argv
        assertEquals(listOf("exec", "--json"), argv.take(2))
        assertEquals("-", argv.last()) // the prompt comes on stdin, read to its end
        assertEquals("read-only", launch.flag("--sandbox"))
        assertEquals("gpt-5-codex", launch.flag("-m"))
        assertContains(argv, "--skip-git-repo-check")
        assertContains(argv, "developer_instructions=\"Write plainly.\"")
        assertFalse(argv.any { it.startsWith("--tools") || it == "--allowedTools" })
        val schema = JsonMapper.shared().readValue(Path.of(launch.flag("--output-schema")!!).toFile(), Map::class.java)
        assertEquals(listOf("done", "stuck"), ((schema["properties"] as Map<*, *>)["outcome"] as Map<*, *>)["enum"])
        assertEquals(listOf("outcome", "summary", "output", "needs"), schema["required"]) // codex's strict mode: every key required
        assertEquals(home.resolve("codex").toString(), launch.env["CODEX_HOME"])
        for (key in listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "CODEX_API_KEY")) assertEquals(null, launch.env[key], key)
        assertContains(launch.stdin, "Follow the Skill in ${home.resolve("skills/draft/SKILL.md")} for the workspace at ${workspace(run)}.")

        val transcript = browser.get("/runs/$run/tasks/draft/transcript").body
        assertContains(transcript, "<strong>short</strong> draft.")
        assertContains(transcript, "<b>Shell</b> <code>bash -lc ls</code>")
        assertContains(transcript, "<b>Result: done</b> Drafted.")
        assertContains(transcript, "1.2k tokens")
    }

    private fun modelAndEffort(agent: String): Pair<String?, String?> {
        home.resolve("agents/writer.md").writeText("---\ncli: codex\n${agent}skills: draft\n---\nWrite plainly.\n")
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val launch = codex.launches("draft").last()
        val effort = launch.argv.zipWithNext().singleOrNull { it.first == "-c" && it.second.startsWith("model_reasoning_effort=") }
        return launch.flag("-m") to effort?.second?.removePrefix("model_reasoning_effort=")
    }

    @Test
    fun `a codex Agent Task launches on its Agent's level's model and effort`() {
        codexTask()
        codex.session("draft", started, turn, answer(), completed)
        val ladder = mapOf(
            1 to ("gpt-5.1-codex-mini" to "\"low\""),
            2 to ("gpt-5.2-codex" to "\"low\""),
            3 to ("gpt-5.2-codex" to "\"medium\""),
            4 to ("gpt-5.2-codex" to "\"high\""),
            5 to ("gpt-5.2-codex" to "\"xhigh\""),
        )
        for ((level, expected) in ladder) assertEquals(expected, modelAndEffort("level: $level\n"))
    }

    @Test
    fun `a codex Agent with neither level nor model launches on level 2`() {
        codexTask()
        codex.session("draft", started, turn, answer(), completed)
        assertEquals("gpt-5.2-codex" to "\"low\"", modelAndEffort(""))
    }

    @Test
    fun `a pinned model on a codex Agent launches on its model ID with level 2's effort, and a level goes before it`() {
        codexTask()
        codex.session("draft", started, turn, answer(), completed)
        assertEquals("gpt-5-codex" to "\"low\"", modelAndEffort("model: gpt-5-codex\n"))
        assertEquals("gpt-5.2-codex" to "\"high\"", modelAndEffort("level: 4\nmodel: gpt-5-codex\n"))
    }

    @Test
    fun `a codex Task launches with its Run's AGENTS md in its working folder`() {
        codexTask(profiles = "profiles:\n  work:\n    vault: notes\n")
        instruct("work", "Keep it short.\n")
        codex.session("draft", started, turn, "wait go", answer(), completed)
        val run = start()
        eventually { assertEquals(1, codex.launches("draft").size) }

        val cwd = Path.of(codex.launches("draft").single().cwd)
        assertEquals(workspace(run).toRealPath(), cwd)
        assertEquals("Keep it short.\n", cwd.resolve("AGENTS.md").readText())
        codex.release("go")
        eventually { assertContains(page(run), """id="state">Completed<""") }
    }

    @Test
    fun `a codex Agent with an unknown sandbox can't start a Run, and fails its Task if it changes after the start`() {
        codexTask("sandbox: yolo\n")
        start("refused")
        assertContains(browser.get("/processes/refused").body, "The Agent writer: codex takes the sandbox read-only, workspace-write or danger-full-access, not yolo.")

        codexTask()
        codex.session("draft", started, turn, "wait never")
        val run = start()
        eventually { assertEquals(1, codex.launches("draft").size) }
        codexTask("sandbox: yolo\n")
        eventually { assertContains(browser.get(transcript(run)).body, ">Stop</button>") }
        browser.post("${transcript(run)}/stop", emptyMap())
        var entry = ""
        eventually {
            val row = assertNotNull(Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
                .find(browser.get("/notifications").body)).groupValues[1]
            assertContains(row, "<i>■</i>[stopped]")
            entry = Regex("""href="(/notifications/[^"]+)"""").find(row)!!.groupValues[1]
        }
        browser.post(entry, mapOf("action" to "continue", "instruction" to "Go on."))
        eventually { assertContains(browser.get(entry).body, "codex takes the sandbox read-only, workspace-write or danger-full-access, not yolo.") }
        assertEquals(1, codex.launches("draft").size)
    }

    @Test
    fun `a codex Task with the Vault on may write it, and one without has no Vault`() {
        codexTask()
        codex.session("draft", started, turn, answer(), completed)
        val run = start(properties = """<operaton:property name="ants.vault" value="true" />""")
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertEquals(vaultPath.toString(), codex.launches("draft").single().flag("--add-dir"))

        codexTask()
        val without = start("no-vault")
        eventually { assertContains(page(without), """id="state">Completed<""") }
        assertFalse("--add-dir" in codex.launches("draft").last().argv)
    }

    @Test
    fun `a codex Task at brief gets the Verbosity sentence and no --add-dir for it`() {
        codexTask(profiles = "profiles:\n  work:\n    vault: notes\n    verbosity: brief\n")
        codex.session("draft", started, turn, answer(), completed)
        val run = start()
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val launch = codex.launches("draft").single()
        assertContains(launch.stdin, "Write what the user reads at the brief verbosity defined in ${home.resolve("shipped/verbosity/verbosity.md")}.")
        assertFalse("--add-dir" in launch.argv)
    }

    /** codex's Usage limit [message], as an `error` and the `turn.failed` it ends, and the exit that follows. */
    private fun limit(message: String) = arrayOf(
        """{"type":"error","message":"$message"}""", """{"type":"turn.failed","error":{"message":"$message"}}""", "exit 1",
    )

    private fun pill(page: String): String {
        assertContains(browser.get(page).body, """<span id="paused"></span>""")
        return browser.event("", "pauses")
    }

    @Test
    fun `a codex usage limit pauses codex Tasks only, until the local time it gives plus a minute, then resumes the session`() {
        val claude = FakeClaude()
        codexTask(claude = claude)
        home.resolve("agents/claude-writer.md").writeText("---\ncli: claude\nskills: draft\n---\nWrite plainly.\n")
        val tomorrow = LocalDate.now(clock).plusDays(1)
        val suffix = when (tomorrow.dayOfMonth) { 1, 21, 31 -> "st"; 2, 22 -> "nd"; 3, 23 -> "rd"; else -> "th" }
        val at = tomorrow.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)) + "$suffix, ${tomorrow.year} 3:45 AM"
        codex.session(
            "draft", 1, started, turn,
            *limit("You’ve hit your usage limit. Upgrade to Pro (https://chatgpt.com/explore/pro), visit https://chatgpt.com/settings/usage to purchase more credits or try again at $at."),
        )
        codex.session("draft", started, turn, answer(), completed)
        val first = start()
        val reset = tomorrow.atTime(3, 46).atZone(clock.zone).toInstant()
        val paused = "<dt>draft</dt><dd>Paused until ${clock.local(reset)}</dd>"
        eventually { assertContains(page(first), paused) }
        assertContains(pill("/runs"), "codex paused until ${clock.local(reset)}")

        val second = start("codex-again")
        eventually { assertContains(page(second), paused) }
        claude.session("draft", """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
            """"structured_output":{"outcome":"done","summary":"Drafted by claude.","output":{"text":"Hello"}}}""")
        val onClaude = start("on-claude", agent = "claude-writer")
        eventually { assertContains(page(onClaude), """id="state">Completed<""") } // claude isn't paused
        assertEquals(1, codex.launches("draft").size)

        clock.offset = Duration.between(clock.instant(), reset).plusMinutes(1)
        for (run in listOf(first, second)) eventually { assertContains(page(run), """id="state">Completed<""") }
        val cut = codex.launches("draft").first()
        val resumed = codex.launches("draft").drop(1).single { it.argv.contains("resume") }
        assertEquals(cut.thread, resumed.flag("resume"))
        assertEquals("The usage limit has reset. Carry on where you stopped.", resumed.stdin)
    }

    @Test
    fun `a codex usage limit without a time pauses codex for 30 minutes`() {
        codexTask()
        codex.session("draft", 1, started, turn, *limit("You’ve hit your usage limit. Try again later."))
        codex.session("draft", started, turn, answer(), completed)
        val before = clock.instant()
        val run = start()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>Paused until ") }
        val after = clock.instant()
        assertContains(setOf(clock.local(before.plusSeconds(31 * 60)), clock.local(after.plusSeconds(31 * 60))), page(run).substringAfter("Paused until ").substringBefore("<"))
        clock.offset = Duration.ofMinutes(32)
        eventually { assertContains(page(run), """id="state">Completed<""") }
    }

    @Test
    fun `a codex usage limit resetting later today gives the time alone`() {
        assumeTrue(LocalTime.now(clock).isBefore(LocalTime.of(22, 0)))
        codexTask()
        codex.session("draft", started, turn, *limit("You’ve hit your usage limit. Try again at 11:15 PM."))
        val run = start()
        eventually { assertContains(page(run), "<dt>draft</dt><dd>Paused until 23:16</dd>") }
    }

    private fun transcript(run: String) = "/runs/$run/tasks/draft/transcript"

    @Test
    fun `Send to a codex Task stops its turn and resumes its session with the message, and the page says so`() {
        codexTask()
        codex.session("draft", 1, started, turn, "wait never")
        codex.session("draft", started, turn, "wait release", answer("Kept short."), completed)
        val run = start()
        var page = ""
        eventually { page = browser.get(transcript(run)).body.also { assertContains(it, """<button class="primary">Send</button>""") } }
        assertContains(page, "codex takes no message while it works: Send stops it and resumes its session with your message.")
        eventually { assertEquals(1, codex.launches("draft").size) }
        // Ants has read codex's thread.started, so a Send has a session to resume.
        eventually { assertTrue(workspace(run).resolve("tasks/draft/session").exists()) }

        assertEquals(transcript(run), browser.post("${transcript(run)}/send", mapOf("message" to "Keep it short.")).path)
        assertContains(workspace(run).resolve("run-log.md").readText(), "Draft · Send\n\nKeep it short.\n")
        codex.release("release")
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertContains(page(run), "Kept short.")
        val (cut, resumed) = codex.launches("draft")
        assertTrue(cut.interrupted)
        assertEquals(listOf("resume", cut.thread, "-"), resumed.argv.takeLast(3))
        assertEquals("Keep it short.", resumed.stdin)
        assertContains(browser.get(transcript(run)).body, """<p class="user" id="e2">› Keep it short.</p>""")
    }

    @Test
    fun `an image in a codex Task's prompt, and in a message Sent to it, goes to codex as -i`() {
        codexTask()
        codex.session("draft", 1, started, turn, "wait never")
        codex.session("draft", started, turn, answer(), completed)
        browser.get("/processes")
        browser.postBytes("/images", ImagesTest.RED_PNG)
        val image = home.resolve(ImagesTest.RED_PATH.removePrefix("/")).toString()
        val run = start(prompt = "Like this: ![image](${ImagesTest.RED_PATH})")
        eventually { assertEquals(1, codex.launches("draft").size) }
        eventually { assertTrue(workspace(run).resolve("tasks/draft/session").exists()) }
        val first = codex.launches("draft").single()
        assertEquals(image, first.flag("-i"))
        assertContains(first.stdin, "Like this: ![image](${ImagesTest.RED_PATH})")

        browser.get(transcript(run))
        browser.post("${transcript(run)}/send", mapOf("message" to "And this: ![image](${ImagesTest.RED_PATH})"))
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val resumed = codex.launches("draft")[1]
        assertEquals(listOf("resume", first.thread, "-"), resumed.argv.takeLast(3))
        assertEquals(image, resumed.flag("-i"))
        assertEquals("And this: ![image](${ImagesTest.RED_PATH})", resumed.stdin)
    }

    @Test
    fun `Stop on a codex Task leaves it stopped, and Continue resumes its session`() {
        codexTask()
        codex.session("draft", 1, started, turn, "wait never")
        codex.session("draft", started, turn, answer(), completed)
        val run = start()
        eventually { assertContains(browser.get(transcript(run)).body, ">Stop</button>") }
        eventually { assertEquals(1, codex.launches("draft").size) }
        // Ants has read codex's thread.started, so Continue has a session to resume.
        eventually { assertTrue(workspace(run).resolve("tasks/draft/session").exists()) }
        browser.post("${transcript(run)}/stop", emptyMap())
        var entry = ""
        eventually {
            val row = assertNotNull(Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
                .find(browser.get("/notifications").body)).groupValues[1]
            assertContains(row, "<i>■</i>[stopped]")
            entry = Regex("""href="(/notifications/[^"]+)"""").find(row)!!.groupValues[1]
        }
        assertContains(browser.get(entry).body, "Stopped by the user.")
        browser.post(entry, mapOf("action" to "continue", "instruction" to "Go on."))
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val (cut, resumed) = codex.launches("draft")
        assertTrue(cut.interrupted)
        assertEquals(cut.thread, resumed.flag("resume"))
        assertEquals("Go on.", resumed.stdin)
    }

    @Test
    fun `a codex launch that resumes its session shows the session's tokens so far, as codex gives no context per call`() {
        codexTask()
        val stuck = """{"type":"item.completed","item":{"id":"item_9","type":"agent_message",""" +
            """"text":"{\"outcome\":\"stuck\",\"summary\":\"Blocked.\",\"output\":null,\"needs\":\"A topic.\"}"}}"""
        codex.session("draft", 1, started, turn, stuck, """{"type":"turn.completed","usage":{"input_tokens":900,"cached_input_tokens":0,"output_tokens":100}}""")
        codex.session("draft", started, turn, answer(), """{"type":"turn.completed","usage":{"input_tokens":1350,"cached_input_tokens":0,"output_tokens":150}}""")
        val run = start()
        var entry = ""
        eventually {
            val row = assertNotNull(Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
                .find(browser.get("/notifications").body)).groupValues[1]
            entry = Regex("""href="(/notifications/[^"]+)"""").find(row)!!.groupValues[1]
        }
        browser.post(entry, mapOf("action" to "continue", "instruction" to "Pick billing."))
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val (first, resumed) = codex.launches("draft")
        assertEquals(first.thread, resumed.flag("resume"))
        val transcript = browser.get(transcript(run)).body
        assertTrue(Regex("""Launch 1 · [^<]* · 1k tokens""").containsMatchIn(transcript), transcript)
        assertTrue(Regex("""Launch 2 · [^<]* · 1.5k tokens""").containsMatchIn(transcript), transcript)
        assertContains(transcript, """<span id="tokens">1.5k tokens</span>""")
    }

    @AfterEach
    fun openTheGates() {
        clock.offset = Duration.ZERO
        browser.get("/processes") // for a CSRF token
        browser.post("/pauses/codex", emptyMap())
    }

    @Autowired
    lateinit var scripts: Scripts

    @Test
    fun `a codex Agent that lists Scripts gets no Script tools, and its hand-off names none`() {
        scripts.save(Script("s-post01", "post-message", "Posts a message."), "console.log('hi')\n", emptyMap())
        codexTask("scripts: [s-post01]\n")
        codex.session("draft", started, turn, answer(), completed)
        val run = start(properties = """<operaton:property name="ants.scripts" value="post-message" />""")
        eventually { assertContains(page(run), """id="state">Completed<""") }

        val launch = codex.launches("draft").single()
        assertFalse((launch.argv + launch.stdin).any { "script_" in it || "post-message" in it || "mcp" in it })
    }
}

/** The fake `codex`, copied into a folder of its own, where tests write its sessions and read what it recorded. */
class FakeCodex {
    val dir: Path = Files.createTempDirectory("fake-codex")
    val command: Path = dir.resolve("codex")

    init {
        Files.copy(javaClass.getResourceAsStream("/fake-codex/codex")!!, command)
        command.toFile().setExecutable(true)
        dir.resolve("sessions").createDirectories()
    }

    fun session(skill: String, vararg lines: String) = dir.resolve("sessions/$skill.jsonl").writeText(lines.joinToString("\n", postfix = "\n"))

    /** The session the [n]th launch of [skill] plays instead of its usual one. */
    fun session(skill: String, n: Int, vararg lines: String) = dir.resolve("sessions/$skill.$n.jsonl").writeText(lines.joinToString("\n", postfix = "\n"))

    fun release(file: String) = dir.resolve(file).createFile()

    /** The launches of [skill], in launch order, each once its record is written (its `stdin` comes last). */
    fun launches(skill: String): List<Launch> =
        if (dir.resolve("launches").isDirectory()) dir.resolve("launches").listDirectoryEntries("$skill.*")
            .filter { it.resolve("stdin").exists() }
            .sortedBy { it.fileName.toString().substringAfterLast('.').toInt() }.map(::Launch) else emptyList()

    /** One launch's record, read when asked. */
    class Launch(private val dir: Path) {
        val argv get() = dir.resolve("argv").readLines()

        /** The value after [flag] in argv, or null. */
        fun flag(flag: String) = argv.let { argv -> argv.indexOf(flag).takeIf { it >= 0 }?.let { argv[it + 1] } }
        val env get() = dir.resolve("env").readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        val stdin get() = dir.resolve("stdin").readText().trim()
        val cwd get() = dir.resolve("cwd").readText().trim()

        /** The session (codex's thread) it ran: a new one's own id, or the one it resumed. */
        val thread get() = dir.resolve("thread").readText()

        /** Whether Ants interrupted the launch's turn, as Ctrl-C does. */
        val interrupted get() = dir.resolve("interrupted").exists()
    }
}
