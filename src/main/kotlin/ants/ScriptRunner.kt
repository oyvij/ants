package ants

import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText

data class ScriptRun(val exitCode: Int, val output: String, val payload: Any?) {
    val failed get() = exitCode != 0
}

@Component
class ScriptRunner(private val settings: Settings) {
    internal var timeLimit: Duration = Duration.ofMinutes(10)

    private val json = JsonMapper.shared()

    fun run(code: Path, workspace: Path, runId: String, inputs: Map<String, Any?>, vars: Map<String, String>, secrets: Map<String, String>): ScriptRun {
        val output = Files.createTempFile("ants-output", ".json")
        try {
            val masked = secrets.values + settings.secretValues().values
            val process = try {
                ProcessBuilder(settings.path("node") ?: "node", code.toAbsolutePath().toString()).directory(workspace.toFile()).redirectErrorStream(true)
                    .apply {
                        environment().keys.retainAll(settings.env("claude").toSet())
                        environment() -= AgentLauncher.PAID
                        environment() += vars
                        environment() += secrets
                        environment() += mapOf("ANTS_RUN_WORKSPACE" to workspace.toString(), "ANTS_RUN_ID" to runId, "ANTS_OUTPUT" to output.toString())
                    }.start()
            } catch (e: IOException) {
                return ScriptRun(-1, "Ants can't run node: ${e.message}", null)
            }
            Thread.ofVirtual().start {
                runCatching { process.outputStream.use { it.write(json.writeValueAsBytes(inputs)) } }
            }
            val text = CompletableFuture.supplyAsync({ process.inputStream.readAllBytes().toString(Charsets.UTF_8) }) { Thread.ofVirtual().start(it) }
            if (!process.waitFor(timeLimit.toMillis(), MILLISECONDS)) {
                (process.descendants().toList() + process.toHandle()).forEach(ProcessHandle::destroyForcibly)
                process.waitFor()
                val limit = timeLimit.toString().removePrefix("PT").lowercase()
                return ScriptRun(process.exitValue().takeIf { it != 0 } ?: -1, ProtectedCommands.mask(read(text) + "The Script ran past its time limit of $limit, so Ants killed it.\n", masked), null)
            }
            val printed = ProtectedCommands.mask(read(text), masked)
            val written = ProtectedCommands.mask(output.readText(), masked)
            if (written.isBlank()) return ScriptRun(process.exitValue(), printed, null)
            return try {
                ScriptRun(process.exitValue(), printed, json.readValue(written, Any::class.java))
            } catch (e: JacksonException) {
                ScriptRun(process.exitValue().takeIf { it != 0 } ?: 1, printed + "The file ANTS_OUTPUT holds no JSON, so the run failed: ${e.originalMessage}\n", null)
            }
        } finally {
            output.deleteIfExists()
        }
    }

    private fun read(text: CompletableFuture<String>) = try {
        text.get(5, SECONDS)
    } catch (_: TimeoutException) {
        "(Ants stopped reading: a process the Script left running still holds its output.)\n"
    }
}
