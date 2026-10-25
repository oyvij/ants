package ants

import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

data class ScriptRun(val exitCode: Int, val output: String, val payload: Any?)

@Component
class ScriptRunner(private val settings: Settings) {
    internal var timeLimit: Duration = Duration.ofMinutes(10)

    private val json = JsonMapper.shared()

    /**
     * Runs [code] with Node, the global `ants` defined before it: `ants.inputs` is [inputs], `ants.output(value)` sets
     * the payload. Inputs that break [schema], the Script's input schema, are refused before Node starts.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun run(code: Path, workspace: Path, runId: String, inputs: Map<String, Any?>, schema: Map<*, *>, vars: Map<String, String>, secrets: Map<String, String>): ScriptRun {
        val broken = ScriptSchema.errors(inputs, schema)
        if (broken.isNotEmpty()) return ScriptRun(-1, "The inputs break the Script's input schema, so it didn't run:\n" + broken.joinToString("") { "- $it\n" }, null)
        val scratch = Files.createTempDirectory("ants-script")
        try {
            val payload = scratch.resolve("payload.json")
            val refused = scratch.resolve("refused.txt")
            scratch.resolve("inputs.json").writeBytes(json.writeValueAsBytes(inputs))
            val module = scratch.resolve("ants.mjs")
            module.writeText(antsModule(scratch.resolve("inputs.json"), payload, refused))
            val masked = secrets.values + settings.secretValues().values
            val ants = mapOf("ANTS_RUN_WORKSPACE" to workspace.toString(), "ANTS_RUN_ID" to runId)
            val process = try {
                ProcessBuilder(settings.path("node") ?: "node", "--import", module.toUri().toString(), code.toAbsolutePath().toString())
                    .directory(workspace.toFile()).redirectErrorStream(true)
                    .onAllowList(settings, vars + secrets + ants).start()
            } catch (e: IOException) {
                return ScriptRun(-1, "Ants can't run node: ${e.message}", null)
            }
            runCatching { process.outputStream.close() }
            val (finished, text) = process.within(timeLimit, "(Ants stopped reading: a process the Script left running still holds its output.)\n")
            val printed = ProtectedCommands.mask(text, masked)
            if (!finished) return ScriptRun(-1, printed + "The Script ran past its time limit of ${timeLimit.short()}, so Ants killed it.\n", null)
            if (refused.exists()) {
                val reason = ProtectedCommands.mask(refused.readText(), masked)
                return ScriptRun(process.exitValue().takeIf { it != 0 } ?: 1, printed + "ants.output got a value JSON can't hold, so the run failed: $reason\n", null)
            }
            if (!payload.exists()) return ScriptRun(process.exitValue(), printed, null)
            return ScriptRun(process.exitValue(), printed, json.readValue(ProtectedCommands.mask(payload.readText(), masked), Any::class.java))
        } finally {
            scratch.deleteRecursively()
        }
    }

    /** The module Node loads before the Script. It serialises the payload when the process ends, so the last `ants.output` wins. */
    private fun antsModule(inputs: Path, payload: Path, refused: Path): String {
        fun literal(path: Path) = json.writeValueAsString(path.toString())
        return """
            |import {readFileSync, writeFileSync} from 'node:fs'
            |let payload, set = false
            |globalThis.ants = Object.freeze({
            |  inputs: JSON.parse(readFileSync(${literal(inputs)}, 'utf8')),
            |  output(value) { payload = value; set = true },
            |})
            |process.on('exit', () => {
            |  if (!set) return
            |  let text
            |  try { text = JSON.stringify(payload) } catch (e) { text = e }
            |  if (typeof text === 'string') return writeFileSync(${literal(payload)}, text)
            |  writeFileSync(${literal(refused)}, text === undefined ? 'JSON.stringify gives nothing for ' + (payload === undefined ? 'undefined' : 'a ' + typeof payload) : String(text.message))
            |  process.exitCode = process.exitCode || 1
            |})
            |""".trimMargin()
    }
}
