package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.http.HttpStatus.UNAUTHORIZED
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import kotlin.io.path.isDirectory

/**
 * Protected Commands (ADR 0015): every claude Agent Task gets one MCP tool, `run(command, folder)`, served here over
 * HTTP with a bearer token of its own. Ants runs a command a Protected Command's prefix starts, with no shell, in a
 * folder of the Task's Run workspace, on the CLI's environment allow-list plus the Secrets linked to it, and hands back
 * its exit code and output with every Secret's value masked. The Agent never sees a Secret.
 */
@RestController
class ProtectedCommands(
    private val settings: Settings,
    private val server: WebServerApplicationContext,
    @Value("\${server.address:}") private val address: String,
) {
    /** The Run workspace of each live claude Task, by its token. */
    private val tasks = ConcurrentHashMap<String, Path>()

    /** How long a Protected Command may run before Ants kills it. */
    internal var timeLimit: Duration = Duration.ofMinutes(10)

    /** Gives the Task working in [workspace] a token, until [close], and returns it with the Task's `--mcp-config`. */
    fun open(workspace: Path): Pair<String, String> {
        val token = UUID.randomUUID().toString()
        tasks[token] = workspace
        val host = (address.takeUnless { it.isEmpty() || InetAddress.getByName(it).isAnyLocalAddress } ?: "127.0.0.1").let { if (':' in it) "[$it]" else it }
        val ants = mapOf("type" to "http", "url" to "http://$host:${server.webServer!!.port}/mcp", "headers" to mapOf(AUTHORIZATION to "Bearer $token"))
        return token to json.writeValueAsString(mapOf("mcpServers" to mapOf("ants" to ants)))
    }

    /** Ends [token], once its Task's session has ended. */
    fun close(token: String) {
        tasks -= token
    }

    /** The MCP endpoint: JSON-RPC over HTTP, one JSON response per request. */
    @PostMapping("/mcp")
    fun mcp(@RequestHeader(AUTHORIZATION, required = false) authorization: String?, @RequestBody request: Map<String, Any?>): ResponseEntity<Any> {
        val id = request["id"]
        val workspace = authorization?.removePrefix("Bearer ")?.let(tasks::get)
            ?: return ResponseEntity.status(UNAUTHORIZED).body(error(id, "No live Agent Task holds this token."))
        if (id == null) return ResponseEntity.accepted().build() // a notification, such as notifications/initialized
        val params = request["params"] as? Map<*, *> ?: emptyMap<String, Any>()
        val result = when (request["method"]) {
            "initialize" -> mapOf(
                "protocolVersion" to (params["protocolVersion"] ?: "2025-06-18"),
                "capabilities" to mapOf("tools" to emptyMap<String, Any>()),
                "serverInfo" to mapOf("name" to "ants", "version" to "1"),
            )
            "ping" -> emptyMap<String, Any>()
            "tools/list" -> mapOf("tools" to listOf(TOOL))
            "tools/call" -> {
                val arguments = params["arguments"] as? Map<*, *> ?: emptyMap<String, Any>()
                val (text, failed) = try {
                    run(workspace, arguments["command"]?.toString().orEmpty(), arguments["folder"]?.toString() ?: ".").let { (exit, output) -> "Exit code $exit\n\n$output" to (exit != 0) }
                } catch (refused: Refused) {
                    refused.message to true
                }
                mapOf("content" to listOf(mapOf("type" to "text", "text" to text)), "isError" to failed)
            }
            else -> return ResponseEntity.ok(error(id, "No method ${request["method"]}.", -32601))
        }
        return ResponseEntity.ok(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))
    }

    /**
     * Runs [command] in [folder] of [workspace] and returns its exit code and its output, stdout and stderr together,
     * with every Secret's value as [MASK]. Refuses a command no Protected Command starts, a folder outside the
     * workspace, and one that runs past [timeLimit], which Ants kills with every process under it.
     */
    internal fun run(workspace: Path, command: String, folder: String): Pair<Int, String> {
        // ponytail: quotes group words, but a word half quoted, as in a"b c", splits; a shell's rules if it matters
        val words = WORD.findAll(command).map { match -> match.groups.drop(1).firstNotNullOf { it?.value } }.toList()
        val prefixes = settings.protectedCommands().mapKeys { (prefix) -> Settings.strings(prefix).orEmpty() }
        val labels = prefixes.filterKeys { it.isNotEmpty() && words.take(it.size) == it }.maxByOrNull { it.key.size }?.value
            ?: throw Refused("`$command` is no Protected Command, so Ants ran nothing. End stuck, with `$command` in needs: the user adds it in Settings, then continues your session, and you ask again.")
        val root = workspace.toRealPath()
        val dir = runCatching { root.resolve(folder).toRealPath() }.getOrNull()?.takeIf { it.startsWith(root) && it.isDirectory() }
            ?: throw Refused("$folder is no folder in the Run workspace, so Ants ran nothing.")
        val names = settings.secrets()
        val values = settings.secretValues()
        val secrets = labels.flatMap { label ->
            val value = values[label] ?: throw Refused("The Secret $label has no value, so Ants ran nothing. End stuck: the user sets it in Settings.")
            names[label].orEmpty().map { it to value }
        }
        val process = try {
            ProcessBuilder(words).directory(dir.toFile()).redirectErrorStream(true).redirectInput(File("/dev/null"))
                .apply {
                    environment().keys.retainAll(settings.env("claude").toSet())
                    environment() += secrets
                }.start()
        } catch (e: IOException) {
            throw Refused("Ants can't run `$command`: ${e.message}")
        }
        val output = CompletableFuture.supplyAsync({ process.inputStream.readAllBytes().toString(Charsets.UTF_8) }) { Thread.ofVirtual().start(it) }
        if (!process.waitFor(timeLimit.toMillis(), MILLISECONDS)) {
            (process.descendants().toList() + process.toHandle()).forEach(ProcessHandle::destroyForcibly)
            throw Refused("`$command` ran past its time limit of ${timeLimit.toMinutes()} minutes, so Ants killed it.")
        }
        val text = try {
            output.get(5, SECONDS)
        } catch (_: TimeoutException) {
            "(Ants stopped reading: a process `$command` left running still holds its output.)"
        }
        return process.exitValue() to mask(text, values.values)
    }

    /** Refused: Ants ran nothing, or killed what it ran, and the Agent reads why. */
    class Refused(override val message: String) : Exception(message)

    companion object {
        const val MASK = "••••"

        /** [text] with each of [secrets] as [MASK], the longest first, so one holding another is masked whole. */
        fun mask(text: String, secrets: Collection<String>) =
            secrets.filter(String::isNotEmpty).sortedByDescending { it.length }.fold(text) { masked, secret -> masked.replace(secret, MASK) }

        /** A word: double-quoted, single-quoted, or bare. */
        private val WORD = Regex(""""([^"]*)"|'([^']*)'|(\S+)""")

        private val TOOL = mapOf(
            "name" to "run",
            "description" to "Runs a Protected Command, such as `yarn install`, in a folder of the Run workspace, with the Secrets the user " +
                "linked to it, which you never see. No shell: the command is split into words. Returns the exit code and the output, " +
                "every Secret masked as $MASK. A command that is no Protected Command is refused: then end stuck with it in needs.",
            "inputSchema" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "command" to mapOf("type" to "string", "description" to "The command and its arguments, such as `yarn install --immutable`."),
                    "folder" to mapOf("type" to "string", "description" to "The folder to run it in, relative to the Run workspace; by default the workspace."),
                ),
                "required" to listOf("command"),
            ),
        )

        private fun error(id: Any?, message: String, code: Int = -32001) = mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message))

        private val json = JsonMapper.shared()
    }
}
