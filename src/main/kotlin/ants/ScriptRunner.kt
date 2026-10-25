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

/** A run of a Script. [mismatches] say how a payload breaks the output schema; a run with any failed, though Node exited 0. */
data class ScriptRun(val exitCode: Int, val output: String, val payload: Any?, val mismatches: List<String> = emptyList()) {
    val failed get() = exitCode != 0 || mismatches.isNotEmpty()

    /** [output], and after it why the payload breaks the output schema, for a Script Task's output and a Script tool's answer. */
    val reported get() = output + if (mismatches.isEmpty()) "" else "The payload breaks the Script's output schema:\n" + listed(mismatches)
}

/** [problems] as a list, one `- ` line each. */
private fun listed(problems: List<String>) = problems.joinToString("") { "- $it\n" }

@Component
class ScriptRunner(private val settings: Settings) {
    internal var timeLimit: Duration = Duration.ofMinutes(10)

    private val json = JsonMapper.shared()

    /**
     * Runs [code], the [script]'s, with Node, the global `ants` defined before it: `ants.inputs` is [given],
     * `ants.output(value)` sets the payload. The environment holds the Script's vars and [secrets], its Secrets' values.
     * Inputs that break the input schema are refused before Node starts; once Node exits 0, the payload is checked
     * against the output schema, if the Script has one.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun run(script: Script, secrets: Map<String, String>, code: Path, workspace: Path, runId: String, given: Map<*, *>): ScriptRun {
        val inputs = given.entries.associate { (key, value) -> "$key" to value }
        val broken = ScriptSchema.errors(inputs, script.inputs)
        if (broken.isNotEmpty()) return ScriptRun(-1, "The inputs break the Script's input schema, so it didn't run:\n" + listed(broken), null)
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
                    .onAllowList(settings, script.vars + secrets + ants).start()
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
            val value = if (payload.exists()) json.readValue(ProtectedCommands.mask(payload.readText(), masked), Any::class.java) else null
            val mismatches = if (script.output == null || process.exitValue() != 0) emptyList() else ScriptSchema.errors(value, script.output)
            return ScriptRun(process.exitValue(), printed, value, mismatches)
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
