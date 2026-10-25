package ants

import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.writeText

/**
 * What the validator finds in a Script: on a [line] of its code (from 1), or on a schema [row], named as the form posts
 * it (`inputs[0].rows[1]`). An [ERROR] blocks Save; a [WARNING] never does.
 */
data class Finding(val severity: String, val message: String, val line: Int? = null, val row: String? = null) {
    val error get() = severity == ERROR

    /** The finding as Save lists it. */
    val told get() = (line?.let { "Line $it: " } ?: "") + message

    companion object {
        const val ERROR = "error"
        const val WARNING = "warning"
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
        val inputs = rows(params, "inputs")
        val output = rows(params, "output")
        val schema = rowFindings(inputs, "input", "Input row") + rowFindings(output, "payload field", "Payload row")
        val code = params["code"]?.firstOrNull().orEmpty()
        val syntax = syntax(code)
        if (syntax != null) return listOf(syntax) + schema
        return codeFindings(code, inputs.filter { it.name.isNotEmpty() }, output.filter { it.name.isNotEmpty() }) + schema
    }

    private class Row(val at: String, val name: String, val type: String, val required: Boolean, val described: Boolean, val rows: List<Row>) {
        val nests get() = type in SchemaRow.NESTING
        val blank: Boolean get() = name.isEmpty() && !described && rows.all { it.blank }
    }

    private fun rows(params: Map<String, List<String>>, prefix: String): List<Row> {
        fun param(name: String) = params[name]?.firstOrNull().orEmpty()
        val pattern = Regex("^${Regex.escape(prefix)}\\[(\\d+)]\\.name$")
        return params.keys.mapNotNull { pattern.find(it)?.groupValues?.get(1)?.toInt() }.sorted().map { i ->
            val at = "$prefix[$i]"
            val type = param("$at.type").takeIf { it in ScriptSchema.TYPES } ?: "string"
            Row(at, param("$at.name").trim(), type, "$at.required" in params, param("$at.description").isNotBlank(),
                if (type in SchemaRow.NESTING) rows(params, "$at.rows") else emptyList())
        }
    }

    /** A missing or repeated name, or an object with no fields, among [rows], each a [kind] at the top level. */
    private fun rowFindings(rows: List<Row>, kind: String, top: String, parent: String? = null): List<Finding> {
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
            listOfNotNull(own?.let { Finding(Finding.ERROR, it, row = row.at) }) +
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
                return Finding(Finding.WARNING, "Ants can't run node to check the syntax: ${e.message}")
            }
            runCatching { process.outputStream.close() }
            val (finished, text) = process.within(Duration.ofSeconds(20), "")
            if (!finished || process.exitValue() == 0) return null
            val line = Regex("^.*script\\.mjs:(\\d+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.toInt()
            val message = text.lines().firstOrNull { Regex("^[A-Z]\\w*Error\\b").containsMatchIn(it) } ?: text.trim()
            return Finding(Finding.ERROR, message, line)
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    private fun codeFindings(code: String, inputs: List<Row>, output: List<Row>): List<Finding> {
        val read = JsRead(code)
        val declared = inputs.map { it.name }.toSet()
        val findings = mutableListOf<Finding>()
        read.reads.filter { it.first !in declared }.forEach { (name, line) ->
            findings += Finding(Finding.ERROR, "ants.inputs.$name reads an input the input schema doesn't declare.", line)
        }
        if (!read.inputsPassedOn) inputs.filter { input -> read.reads.none { it.first == input.name } }.forEach {
            findings += Finding(Finding.WARNING, "The input ${it.name} is never read.", row = it.at)
        }
        if (output.isEmpty()) return findings
        val fields = output.map { it.name }.toSet()
        read.writes.filter { it.first !in fields }.forEach { (name, line) ->
            findings += Finding(Finding.ERROR, "ants.output writes $name, a field the output schema doesn't declare.", line)
        }
        if (!read.outputPassedOn) output.filter { field -> field.required && read.writes.none { it.first == field.name } }.forEach {
            findings += Finding(Finding.WARNING, "The required payload field ${it.name} is never written.", row = it.at)
        }
        return findings.sortedBy { it.line ?: Int.MAX_VALUE }
    }
}

/**
 * What [code] writes out literally: the [reads] of `ants.inputs.<name>` and the [writes] of `ants.output({<name>: …})`,
 * each with its line. [inputsPassedOn] when `ants.inputs` is used any other way, [outputPassedOn] when `ants.output`
 * is, or is called with something other than an object literal of plain keys, so what is unread or unwritten isn't known.
 */
private class JsRead(private val code: String) {
    private val masked = mask(code)
    val reads = mutableListOf<Pair<String, Int>>()
    val writes = mutableListOf<Pair<String, Int>>()
    var inputsPassedOn = false
    var outputPassedOn = false

    init {
        Regex("""(?<![\w$.])ants\b(?:\s*\.\s*(inputs|output)\b)?""").findAll(masked).forEach { ants ->
            when (ants.groupValues[1]) {
                "inputs" -> {
                    val name = Regex("""\G\s*\??\.\s*([A-Za-z_$][\w$]*)""").find(masked.substring(ants.range.last + 1))
                    if (name != null && name.range.first == 0) reads += name.groupValues[1] to line(ants.range.first) else inputsPassedOn = true
                }
                "output" -> {
                    val call = Regex("""^\s*\(\s*\{""").find(masked.substring(ants.range.last + 1))
                    if (call == null) outputPassedOn = true else fields(ants.range.last + call.range.last + 1)
                }
                else -> {
                    inputsPassedOn = true
                    outputPassedOn = true
                }
            }
        }
    }

    private fun line(offset: Int) = code.substring(0, offset).count { it == '\n' } + 1

    /** Reads the top-level keys of the object literal whose `{` is at [open]. */
    private fun fields(open: Int) {
        var depth = 0
        var start = open + 1
        for (i in open until masked.length) {
            when (masked[i]) {
                '{', '(', '[' -> depth++
                '}', ')', ']' -> depth--
                ',' -> if (depth == 1) {
                    entry(start, i)
                    start = i + 1
                }
            }
            if (depth == 0) return entry(start, i)
        }
    }

    private fun entry(from: Int, to: Int) {
        val text = masked.substring(from, to)
        val at = from + (text.length - text.trimStart().length)
        val entry = text.trim()
        if (entry.isEmpty()) return
        val plain = Regex("""^([A-Za-z_$][\w$]*)\s*(?::|\(|$)""").find(entry)
        val quoted = Regex("""^(['"])\s*\1\s*:""").find(entry)
        when {
            entry.startsWith("...") -> outputPassedOn = true
            plain != null -> writes += plain.groupValues[1] to line(at)
            quoted != null -> writes += code.substring(at + 1, at + entry.indexOf(quoted.groupValues[1], 1)) to line(at)
            else -> outputPassedOn = true
        }
    }

    companion object {
        /** [code] with its comments and the insides of its strings, regular expressions and template text blanked, newlines kept. */
        fun mask(code: String): String {
            val out = StringBuilder(code)
            fun blank(from: Int, to: Int) {
                for (k in from until minOf(to, code.length)) if (out[k] != '\n') out[k] = ' '
            }
            val templates = ArrayDeque<Int>()
            var depth = 0
            var i = 0
            fun template(from: Int): Int {
                var j = from
                while (j < code.length && code[j] != '`') {
                    if (code[j] == '\\') j++
                    else if (code[j] == '$' && code.getOrNull(j + 1) == '{') {
                        blank(from, j)
                        templates.addLast(depth)
                        return j + 2
                    }
                    j++
                }
                blank(from, j)
                return j + 1
            }
            fun regexMayStart(at: Int): Boolean {
                var k = at - 1
                while (k >= 0 && code[k].isWhitespace()) k--
                return k < 0 || code[k] in "(,=:[!&|?{};+-*%<>~^"
            }
            while (i < code.length) {
                val c = code[i]
                val next = code.getOrNull(i + 1)
                when {
                    c == '/' && next == '/' -> code.indexOf('\n', i).let { if (it < 0) code.length else it }.also { blank(i, it); i = it }
                    c == '/' && next == '*' -> code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }.also { blank(i, it); i = it }
                    c == '\'' || c == '"' -> {
                        var j = i + 1
                        while (j < code.length && code[j] != c && code[j] != '\n') j += if (code[j] == '\\') 2 else 1
                        blank(i + 1, j)
                        i = j + 1
                    }
                    c == '`' -> i = template(i + 1)
                    c == '/' && regexMayStart(i) -> {
                        var j = i + 1
                        var inClass = false
                        while (j < code.length && code[j] != '\n' && (code[j] != '/' || inClass)) {
                            if (code[j] == '\\') j++
                            else if (code[j] == '[') inClass = true
                            else if (code[j] == ']') inClass = false
                            j++
                        }
                        blank(i + 1, j)
                        i = j + 1
                    }
                    c == '{' -> { depth++; i++ }
                    c == '}' && templates.lastOrNull() == depth -> { templates.removeLast(); i = template(i + 1) }
                    c == '}' -> { depth--; i++ }
                    else -> i++
                }
            }
            return out.toString()
        }
    }
}
