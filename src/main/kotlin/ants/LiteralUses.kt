package ants

/**
 * What [code] writes out literally: the [reads] of `ants.inputs.<name>` and the [writes] of `ants.output({<name>: …})`,
 * each with its line. [inputsPassedOn] when `ants.inputs` is used any other way, [outputPassedOn] when `ants.output`
 * is, or is called with something other than an object literal of plain keys, so what is unread or unwritten isn't known.
 */
internal class LiteralUses(private val code: String) {
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
