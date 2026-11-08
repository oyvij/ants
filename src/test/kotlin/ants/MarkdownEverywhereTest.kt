package ants

import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CODING_STANDARDS' "Text shows as Markdown, everywhere": no template prints text someone wrote (a name, a title, a
 * summary, a message) raw, where its `**` would show. It goes through `Transcript.inline` in a page's content, and
 * through `Transcript.plain` in a `<title>` or an `<option>`, which hold no HTML.
 */
class MarkdownEverywhereTest {
    @Test
    fun `no template prints a name, title, summary or message without its Markdown`() {
        val raw = Path.of("src/main/jte").listDirectoryEntries("*.kte").sortedBy { it.name }.flatMap { template ->
            val text = template.readText()
            val kept = Regex("""<(script|textarea|pre|code|title|option|style)\b[^>]*>.*?</\1>""", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.range }.toList()
            Regex("""(?<![!$\w])\$\{((?:[^{}]|\{[^{}]*\})*)\}""").findAll(text)
                .filter { print -> kept.none { print.range.first in it } && text.lastIndexOf('<', print.range.first) < text.lastIndexOf('>', print.range.first) }
                .map { it.groupValues[1].trim() }
                .filter { PROSE.containsMatchIn(it) && !it.startsWith("ants.Transcript.plain") && it !in NOT_PROSE && "${template.name}: $it" !in NOT_PROSE }
                .map { "${template.name}: \${$it}" }
                .toList()
        }
        assertEquals(emptyList(), raw, "Print these as \$unsafe{ants.Transcript.inline(…)}, or as \${ants.Transcript.plain(…)} where no HTML goes")
    }

    private companion object {
        /** The words that name text someone wrote. */
        val PROSE = Regex("""(name|title|summary|description|question|note|message|error|label|body|why|help|subject|comment|reason|task|process|words|greeting|intro|refused|does|warn|limits|provenance|text|story)\b""", RegexOption.IGNORE_CASE)

        /** What reads like text but is Ants' own fixed wording, a number, code or an id, which shows as is. */
        val NOT_PROSE = setOf(
            "view.widget.type.label", "type.label", "kind.title", "state.label", "level.label", "run.stateName", // Ants' own labels
            "story.steps.size", "story.stale", "process.version", "process.value.version", "task.round", "task.subagents", "task.transcript.runningSubagents", "task.pill",
            "transcript.context", "context", "task.context.replace(\" tokens\", \"\")", "if (does.isEmpty()) \"\" else \" \"", // counts and spacing
            "line.text", "comment.file", "comment.from", "comment.to", "comment.side", "comment.step", // code and where in it
            "comment.type", "comment.type.lowercase().replaceFirstChar(Char::uppercase)", // a Review comment's fixed type
            "run.kte: name", "toolrow.kte: name", // a variable's name and a tool's name: ids, in a mono font
        )
    }
}
