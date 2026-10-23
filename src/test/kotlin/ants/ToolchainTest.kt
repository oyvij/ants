package ants

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Toolchains: a repo's commands find their tools in its Toolchain's folders first, Protected Commands because Ants puts
 * them first on PATH, an Agent's own Bash because the hand-off line tells it to.
 */
class ToolchainTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()
    private val json = JsonMapper.shared()

    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"done","summary":"Drafted.","output":{}}}"""

    /** A Toolchain's `bin`, holding a fake `yarn` that prints its arguments and its PATH. */
    private val bin = Files.createTempDirectory("node22").resolve("bin").createDirectories().also {
        val yarn = it.resolve("yarn")
        yarn.writeText("#!/bin/sh\necho \"toolchain yarn $*\"\necho \"PATH=${'$'}PATH\"\n")
        yarn.toFile().setExecutable(true)
    }

    private val missing = bin.resolveSibling("gone")

    /** Settings with `web` on [bin] (a later Toolchain naming it too), `legacy` on a missing folder, and `yarn` and `sh` as Protected Commands. */
    private fun toolchains() = agentTask(
        claude,
        extra = """
            protected-commands:
              yarn: []
              sh: []
            toolchains:
              node22:
                bin: [$bin]
                repos: [web]
              old:
                bin: $missing
                repos: [legacy, web]

        """.trimIndent(),
        env = listOf("PATH", "HOME"),
    )

    private fun start(process: String): String {
        browser.import("$process.bpmn")
        browser.get("/processes/$process")
        return browser.post("/processes/$process/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    private fun completed(run: String) = eventually(20) { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }

    private fun run(command: String, folder: String) = "mcp run " + json.writeValueAsString(mapOf("command" to command, "folder" to folder))

    @Test
    fun `a Protected Command in a repo runs with its Toolchain first on PATH and its program found there, in a shared clone or an instance's worktree`() {
        toolchains()
        claude.session(
            "draft",
            "sh mkdir -p repos/web/sub repos/api repos/legacy instances/k/repos/web",
            run("yarn install", "repos/web/sub"),
            run("yarn install", "instances/k/repos/web"),
            run("sh -c 'echo \$PATH'", "repos/api"),
            run("yarn install", "repos/legacy"),
            done,
        )
        completed(start("agent-task"))

        val texts = claude.launches("draft").single().file("mcp").lines().filter(String::isNotEmpty).map { line ->
            val result = json.readValue(line, Map::class.java)["result"] as Map<*, *>
            ((result["content"] as List<*>).single() as Map<*, *>)["text"].toString()
        }
        val onPath = "PATH=$bin:${System.getenv("PATH")}"
        assertEquals("Exit code 0\n\ntoolchain yarn install\n$onPath\n", texts[0])
        assertEquals("Exit code 0\n\ntoolchain yarn install\n$onPath\n", texts[1])
        assertEquals("Exit code 0\n\n${System.getenv("PATH")}\n", texts[2]) // a repo with no Toolchain: Ants' PATH, as before
        assertEquals("The Toolchain folder $missing of legacy is missing, so Ants ran nothing. End stuck: the user fixes it in Settings.", texts[3])
    }

    @Autowired
    lateinit var settings: Settings

    @Test
    fun `the settings page's Save keeps the Toolchains it doesn't show`() {
        toolchains()
        browser.get("/settings")
        browser.post("/settings", mapOf("action" to "save"))
        assertEquals(listOf(bin), settings.toolchain("web"))
        assertEquals(emptyList(), settings.toolchain("api"))
    }

    @Test
    fun `the hand-off line tells the Agent the PATH to put first in Bash for each cloned repo with a Toolchain`() {
        toolchains()
        claude.session("draft", 1, "sh mkdir -p repos/web repos/api repos/legacy", done)
        claude.session("draft", done)
        completed(start("agent-loop"))

        val (first, second) = claude.launches("draft")
        assertFalse("PATH" in first.stdin) // nothing cloned yet
        assertContains(
            second.stdin,
            "Prefix each Bash command in these repos with its PATH: `PATH=$missing:\$PATH` in repos/legacy, `PATH=$bin:\$PATH` in repos/web; the run tool puts it there itself.",
        )
    }
}
