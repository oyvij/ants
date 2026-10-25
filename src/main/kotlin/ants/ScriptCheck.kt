package ants

import com.fasterxml.jackson.annotation.JsonValue
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.writeText

/**
 * What the validator finds in a Script: on a [line] of its code (from 1), or on a schema [row], named as the form posts
 * it (`inputs[0].rows[1]`). An [Severity.ERROR] blocks Save; a [Severity.WARNING] never does.
 */
data class Finding(val severity: Severity, val message: String, val line: Int? = null, val row: String? = null) {
    val error get() = severity == Severity.ERROR

    /** The finding as Save lists it. */
    val sentence get() = (line?.let { "Line $it: " } ?: "") + message

    enum class Severity {
        ERROR, WARNING;

        @JsonValue
        fun json() = name.lowercase()
    }
}

/**
 * The Script validator: Node's own syntax check of the code, the code's literal `ants.inputs.<name>` reads and
 * `ants.output({...})` fields against the schemas, and the schema rows themselves. It follows nothing further, so
 * code it can't read literally, such as `ants.inputs` passed on, gives no finding.
 */
@Component
class ScriptCheck(private val settings: Settings) {
    /** The findings for the Script page's form, unsaved, as [params] posts it: `code` and the rows under `inputs` and `output`. */
    fun findings(params: Map<String, List<String>>): List<Finding> {
        val inputs = ScriptSchema.posted(params, "inputs")
        val output = ScriptSchema.posted(params, "output")
        val schema = rowFindings(inputs, "input", "Input row") + rowFindings(output, "payload field", "Payload row")
        val code = params["code"]?.firstOrNull().orEmpty()
        val syntax = syntax(code)
        if (syntax != null) return listOf(syntax) + schema
        return codeFindings(code, inputs.filter { it.name.isNotEmpty() }, output.filter { it.name.isNotEmpty() }) + schema
    }

    /** A missing or repeated name, or an object with no fields, among [rows], each a [kind] at the top level. */
    private fun rowFindings(rows: List<PostedRow>, kind: String, top: String, parent: String? = null): List<Finding> {
        val seen = mutableSetOf<String>()
        return rows.withIndex().flatMap { (i, row) ->
            if (row.blank) return@flatMap emptyList()
            val own = when {
                row.name.isEmpty() -> "${if (parent == null) top else "Field"} ${i + 1}${parent?.let { " of $it" }.orEmpty()} has no name."
                !seen.add(row.name) -> if (parent == null) "Another ${kind} is named ${row.name} too." else "Another field of $parent is named ${row.name} too."
                row.nests && row.rows.all { it.blank } ->
                    "${row.name} is ${if (row.type == "object") "an object" else "a list of objects"} with no fields."
                else -> null
            }
            listOfNotNull(own?.let { Finding(Finding.Severity.ERROR, it, row = row.at) }) +
                rowFindings(row.rows, kind, top, row.name.ifEmpty { "row ${i + 1}" })
        }
    }

    /** Node's verdict on [code]'s syntax, as an error on its line, or null when Node accepts it. */
    private fun syntax(code: String): Finding? {
        val scratch = Files.createTempDirectory("ants-check")
        try {
            val file = scratch.resolve("script.mjs").apply { writeText(code) }
            val process = try {
                ProcessBuilder(settings.path("node") ?: "node", "--check", file.toString()).directory(scratch.toFile())
                    .redirectErrorStream(true).onAllowList(settings, emptyMap()).start()
            } catch (e: IOException) {
                return Finding(Finding.Severity.WARNING, "Ants can't run node to check the syntax: ${e.message}")
            }
            runCatching { process.outputStream.close() }
            val (finished, text) = process.within(Duration.ofSeconds(20), "")
            if (!finished || process.exitValue() == 0) return null
            val line = Regex("^.*script\\.mjs:(\\d+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.toInt()
            val message = text.lines().firstOrNull { Regex("^[A-Z]\\w*Error\\b").containsMatchIn(it) } ?: text.trim()
            return Finding(Finding.Severity.ERROR, message, line)
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    private fun codeFindings(code: String, inputs: List<PostedRow>, output: List<PostedRow>): List<Finding> {
        val read = LiteralUses(code)
        val declared = inputs.map { it.name }.toSet()
        val findings = mutableListOf<Finding>()
        read.reads.filter { it.first !in declared }.forEach { (name, line) ->
            findings += Finding(Finding.Severity.ERROR, "ants.inputs.$name reads an input the input schema doesn't declare.", line)
        }
        if (!read.inputsPassedOn) inputs.filter { input -> read.reads.none { it.first == input.name } }.forEach {
            findings += Finding(Finding.Severity.WARNING, "The input ${it.name} is never read.", row = it.at)
        }
        if (output.isEmpty()) return findings
        val fields = output.map { it.name }.toSet()
        read.writes.filter { it.first !in fields }.forEach { (name, line) ->
            findings += Finding(Finding.Severity.ERROR, "ants.output writes $name, a field the output schema doesn't declare.", line)
        }
        if (!read.outputPassedOn) output.filter { field -> field.required && read.writes.none { it.first == field.name } }.forEach {
            findings += Finding(Finding.Severity.WARNING, "The required payload field ${it.name} is never written.", row = it.at)
        }
        return findings.sortedBy { it.line ?: Int.MAX_VALUE }
    }
}
