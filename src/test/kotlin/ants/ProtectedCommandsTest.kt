package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Protected Commands: a claude Agent Task asks Ants, through its MCP tool `run`, to run a command with Secrets it never
 * sees. The fake claude waits on `go` while a test calls the endpoint with the token its `--mcp-config` carries.
 */
class ProtectedCommandsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()
    private val json = JsonMapper.shared()

    private val secrets = home.resolve("secrets.yaml")

    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"done","summary":"Drafted.","output":{}}}"""

    @AfterEach
    fun noSecrets() {
        secrets.deleteIfExists()
    }

    /** A Task whose settings link the Secret `npm` to `./yarn` and keep `maven` for `mvn deploy`. */
    private fun protected(vararg session: String) {
        agentTask(
            claude,
            extra = "secrets:\n  npm: [YARN_NPM_AUTH_TOKEN, NPM_TOKEN]\n  maven: [MAVEN_TOKEN]\n" +
                "protected-commands:\n  ./yarn: [npm]\n  mvn deploy: [maven]\n",
            env = listOf("PATH", "HOME"),
        )
        secrets.writeText("npm: s3cret-npm\nmaven: s3cret-mvn\n")
        claude.session("draft", *session)
    }

    private fun start(): String {
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        return browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    /** The launch's Ants server from `--mcp-config`: its URL and its Authorization header. */
    private fun server(launch: FakeClaude.Launch): Pair<String, String> {
        val ants = (json.readValue(launch.flag("--mcp-config"), Map::class.java)["mcpServers"] as Map<*, *>)["ants"] as Map<*, *>
        return ants["url"].toString() to (ants["headers"] as Map<*, *>)["Authorization"].toString()
    }

    @Test
    fun `a claude Agent Task gets Ants' run tool with a bearer token, is denied Ants' own files, and sees no Secret`() {
        protected("wait go", done)
        start()
        eventually { assertEquals(1, claude.launches("draft").size) }
        val launch = claude.launches("draft").single()

        val (url, authorization) = server(launch)
        assertEquals("http://127.0.0.1:$port/mcp", url)
        assert(Regex("Bearer [\\w-]{20,}").matches(authorization)) { authorization }
        assertContains(launch.argv, "--strict-mcp-config")
        assertContains(launch.flag("--allowedTools")!!.split(","), "mcp__ants__run")
        val denied = launch.flag("--disallowedTools")!!.split(",")
        for (file in listOf("secrets.yaml", "settings.yaml")) {
            assertContains(denied, "Read(/$home/$file)")
            assertContains(denied, "Edit(/$home/$file)")
        }
        assertContains(denied, "Read(/$home/scripts/**)")
        assertContains(denied, "Edit(/$home/scripts/**)")
        assertContains(denied, "Read(/$home/drafts/scripts/**)") // a Script's Draft is as private as the Script
        assertContains(denied, "Edit(/$home/drafts/scripts/**)")
        for (prefix in listOf("./yarn", "mvn deploy")) {
            assertContains(denied, "Bash($prefix)")
            assertContains(denied, "Bash($prefix *)")
        }
        assertFalse(launch.env.values.any { "s3cret" in it })
        assertFalse(launch.env.keys.any { it in listOf("YARN_NPM_AUTH_TOKEN", "NPM_TOKEN", "MAVEN_TOKEN") })
        claude.release("go")
    }

    @Test
    fun `with no Protected Command, a claude Agent Task's Bash is denied no prefix`() {
        agentTask(claude, env = listOf("PATH", "HOME"))
        claude.session("draft", done)
        start()
        eventually { assertEquals(1, claude.launches("draft").size) }

        val denied = claude.launches("draft").single().flag("--disallowedTools")!!.split(",")
        assertEquals(emptyList(), denied.filter { it.startsWith("Bash(") })
    }

    @Test
    fun `a GET on the MCP endpoint is a plain 405, not a redirect to the login, so claude stops asking for a stream`() {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp")).GET().build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())
        assertEquals(405, response.statusCode())
        assertEquals("POST", response.headers().firstValue("Allow").orElse(null))
    }

    /**
     * A Run whose Task has written `sub/yarn`, a fake yarn that prints its folder, its arguments, a Secret it wasn't
     * given and a line on stderr, writes its environment to `env.out` and exits 3; given `slow`, it first waits on a
     * `sleep 30` whose pid it writes to `sleep.pid`. The Task waits on `go`. Returns the Run and its launch.
     */
    private fun yarn(): Pair<String, FakeClaude.Launch> {
        val script = """#!/bin/sh\nif [ "$1" = slow ]; then sleep 30 & echo $! > sleep.pid; wait; fi\npwd\nprintf '[%s]\\n' "$@"\n""" +
            """env > env.out\necho s3cret-mvn\necho on stderr >&2\nexit 3\n"""
        protected("write sub/yarn $script", "sh chmod +x sub/yarn", "write ready ok", "wait go", done)
        val run = start()
        eventually { assertTrue(workspace(run).resolve("ready").exists()) }
        return run to claude.launches("draft").single()
    }

    @Test
    fun `the run tool runs a Protected Command in the folder asked, with its Secrets under each name, and masks every Secret in its output`() {
        val (run, launch) = yarn()
        val (url, authorization) = server(launch)

        val (status, response) = call(url, authorization, """./yarn install "a b"; env""", folder = "sub")

        assertEquals(200, status)
        val result = response["result"] as Map<*, *>
        assertEquals(true, result["isError"]) // it exited 3
        val text = ((result["content"] as List<*>).single() as Map<*, *>)["text"].toString()
        val sub = workspace(run).resolve("sub").toRealPath()
        assertEquals("Exit code 3\n\n$sub\n[install]\n[a b]\n[;]\n[env]\n••••\non stderr\n", text)
        // The Secrets went to the command, under every name, and only the linked one.
        val env = sub.resolve("env.out").readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("s3cret-npm", env["YARN_NPM_AUTH_TOKEN"])
        assertEquals("s3cret-npm", env["NPM_TOKEN"])
        assertFalse("MAVEN_TOKEN" in env)
        assertEquals(System.getenv("PATH"), env["PATH"]) // the claude allow-list, PATH and HOME
        claude.release("go")
        eventually { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        // Nothing of a Secret reaches the Transcript or the Run workspace's Ants files.
        assertFalse(Files.walk(workspace(run)).filter { it.isRegularFile() && it.fileName.toString() !in listOf("env.out", "yarn") }.anyMatch { "s3cret" in it.readText() })
    }

    @Autowired
    lateinit var protectedCommands: ProtectedCommands

    @Test
    fun `the run tool refuses a command no Protected Command starts, a folder outside the workspace, and a token no live Task holds, and kills one past its time limit`() {
        val (run, launch) = yarn()
        val (url, authorization) = server(launch)
        fun text(response: Pair<Int, Map<*, *>>): String {
            assertEquals(200, response.first)
            val result = response.second["result"] as Map<*, *>
            assertEquals(true, result["isError"])
            return ((result["content"] as List<*>).single() as Map<*, *>)["text"].toString()
        }

        assertEquals(
            "`touch ran` is no Protected Command: it gets no Secrets, so Ants ran nothing. Run it with Bash. If Bash refuses it, your Agent isn't allowed to run it: end stuck, saying which Bash rule your Agent needs, such as `Bash(touch *)`.",
            text(call(url, authorization, "touch ran")),
        )
        assertFalse(workspace(run).resolve("ran").exists())
        // A clone made outside Clone repos has no work branch, so the Agent names the repo instead of asking for the command.
        assertEquals(
            "`git clone git@example.com:o/web.git` is no Protected Command, so Ants ran nothing. Ants clones the Run's repos itself, on the work branch: " +
                "name the repo where your Skill says to name repos, else end stuck with the repo in needs. Never ask for `git clone` as a Protected Command.",
            text(call(url, authorization, "git clone git@example.com:o/web.git", folder = "sub")),
        )
        assertEquals("../.. is no folder in the Run workspace, so Ants ran nothing.", text(call(url, authorization, "./yarn", folder = "../..")))
        workspace(run).resolve("out").createSymbolicLinkPointingTo(Files.createTempDirectory("outside"))
        assertEquals("out is no folder in the Run workspace, so Ants ran nothing.", text(call(url, authorization, "./yarn", folder = "out")))
        for (wrong in listOf(null, "Bearer nobody's", authorization.dropLast(1))) assertEquals(401, call(url, wrong, "./yarn", folder = "sub").first)

        protectedCommands.timeLimit = Duration.ofSeconds(1)
        try {
            assertEquals("`./yarn slow` ran past its time limit of 1s, so Ants killed it.", text(call(url, authorization, "./yarn slow", folder = "sub")))
        } finally {
            protectedCommands.timeLimit = Duration.ofMinutes(10)
        }
        val sleep = workspace(run).resolve("sub/sleep.pid").readText().trim().toLong()
        eventually { assertFalse(ProcessHandle.of(sleep).isPresent) }

        claude.release("go")
        eventually { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        assertEquals(401, call(url, authorization, "./yarn", folder = "sub").first) // the session has ended
    }

    /** Calls the tool `run` at [url] with [authorization] and returns the HTTP status and the JSON-RPC response. */
    private fun call(url: String, authorization: String?, command: String, folder: String = "."): Pair<Int, Map<*, *>> {
        val body = json.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
            "params" to mapOf("name" to "run", "arguments" to mapOf("command" to command, "folder" to folder))))
        val request = HttpRequest.newBuilder(URI(url)).POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
            .apply { if (authorization != null) header("Authorization", authorization) }
        val response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to json.readValue(response.body(), Map::class.java)
    }
}
