package ants

import tools.jackson.core.JacksonException
import org.springframework.web.util.HtmlUtils
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** Where the walkthrough writes the Run's Story set, relative to the Run workspace root. */
const val STORIES = "walkthrough/stories.json"

/** A Review changes' Walkthrough field: the Task whose Story set its Story tab shows, checked when that Task is done. */
const val WALKTHROUGH = "ants.walkthrough"

/** The Story set's JSON Schema, a classpath resource in the walkthrough Skill's folder, which the storyteller reads too. */
const val STORY_SET_SCHEMA = "/shipped/skills/walkthrough/story-set.schema.json"

/**
 * A Story set as the Story tab shows it: Varde's story format widened to a Run (the walkthrough Skill's
 * `story-set.schema.json`), each step's Site in its code, and the [remainder]: per changed file, by its repo and its
 * path in the clone, how many of its hunks no step's Site touches.
 */
class StorySet(val title: String, val stories: List<Story>, val remainder: Map<Pair<String, String>, Int>)

class Story(val name: String, val premise: String, val steps: List<Step>) {
    val stale get() = steps.count { it.stale != null }
}

/**
 * A step: its claim about the lines [from] to [to] on the [side] of [file], in the clone [repo], shown in its [code].
 * [stale] is the Site's lines as the Story set was written against them, at its range's head, when the clone's HEAD
 * has them otherwise, [now]; null when they are the same.
 */
class Step(
    val name: String, val claim: String, val why: String, val repo: String, val file: String, val side: String, val kind: String, val from: Int, val to: Int,
    val code: List<CodeLine>, val flow: Pair<String, String>?, val values: List<Value>, val nudge: String?, val stale: String?, val now: String,
) {
    /** [file]'s path in its clone. */
    val path get() = file.removePrefix("repos/$repo/")
}

/**
 * A line of a step's code: its [number] on the step's side, its [text], and its classes: `site` and the Site's kind,
 * or `dim`; `add` or `del` for a line the diff changed; and `extra` for a line of the other side, shown with the diff.
 */
class CodeLine(val number: String, val text: String, val css: String)

/** A concrete value a step names, its provenance, and the line it [cite]s, `file:line`, if any. */
class Value(val name: String, val value: String, val provenance: String, val cite: String?)

/** [text] as HTML, each `code span` in backticks a `<code>`, as the Story set writes them. */
fun inlineCode(text: String): String = HtmlUtils.htmlEscape(text).replace(Regex("`([^`]+)`"), "<code>\$1</code>")

/** A Story set that is missing or doesn't parse, refused whole, [because]. */
class StorySetRefused(val because: String) : Exception("The Story set in $STORIES is refused: $because")

/** A step that fails one of Varde's four mechanical checks, by its id. */
class Problem(val step: String, val fault: Fault) {
    override fun toString() = "$step: ${fault.label}"
}

/** Varde's four checks, with what Varde tells the walkthrough about each. */
enum class Fault(val label: String, val complaint: String) {
    FILE_MISSING("file-missing", "there is no file at the path this step's `site.file` names. Paths are relative to the Run workspace root, such as `repos/api/src/a.kt`."),
    RANGE_OUT_OF_BOUNDS("range-out-of-bounds", "this step's `site.from`/`site.to` run past the end of that file at the side it names. Line numbers are 1-based, at the revision `side` names."),
    CLAIMS_NO_CHANGE(
        "claims-no-change",
        "this step says `kind: \"changed\"` but those lines are not in its repo's range's diff. Point at lines the change touched, or say `kind: \"context\"` " +
            "if the step deliberately walks into untouched code. A removal is `side: \"old\"` with `kind: \"changed\"`.",
    ),
    CITATION_ABSENT("citation-absent", "a value on this step cites a line that does not contain it. Fix the line number, or mark the value `\"invented\"` and drop its `cite`."),
}

/** The Story set in [workspace], each Site read with [git] (its output untrimmed) from its repo's range; [StorySetRefused] when it doesn't parse. */
fun storySet(workspace: Path, git: (Path, List<String>) -> String): StorySet {
    val set = read(workspace)
    val ranges = Ranges(workspace, set, git)
    val stories = set.list("stories").map { story ->
        Story(story["name"].toString(), story["premise"].toString(), story.list("steps").map { step ->
            val site = step["site"] as Map<*, *>
            val file = site["file"].toString()
            val side = site["side"].toString()
            val kind = site["kind"].toString()
            val from = (site["from"] as Number).toInt()
            val to = (site["to"] as Number).toInt()
            val written = ranges.text(file, side)?.let { lines(it, from, to) }.orEmpty()
            val now = if (side == "old") written else ranges.now(file)?.let { lines(it, from, to) }.orEmpty()
            Step(
                step["name"].toString(), step["claim"].toString(), step["why"].toString(), locate(file)?.first.orEmpty(), file, side, kind, from, to,
                code(ranges.code(file), side, kind, from, to), (step["flow"] as? Map<*, *>)?.let { "${it["in"]}" to "${it["out"]}" },
                step.list("values").map { value ->
                    val cite = value["cite"] as? Map<*, *>
                    Value(value["name"].toString(), value["value"].toString(), if (cite == null) "invented" else "${value["provenance"]}", cite?.let { "${it["file"]}:${it["line"]}" })
                },
                step["nudge"] as? String, written.takeIf { it != now }, now,
            )
        })
    }
    val steps = stories.flatMap { it.steps }
    val remainder = ranges.changed().associate { file ->
        locate(file)!! to ranges.hunks(file).count { hunk -> steps.none { it.file == file && touches(hunk, it.side, it.from, it.to) } }
    }.filterValues { it > 0 }
    return StorySet(set["title"].toString(), stories, remainder)
}

/** How many lines of code a step shows around its Site, on its side. */
private const val AROUND = 10

/**
 * A step's code: the lines of [file] (each in the range's diff) around its Site, [from] to [to] on [side], each with its
 * number on that side; a line of the other side, which only the diff shows, is numbered `−` or `+`.
 */
// ponytail: a window of AROUND lines either side of the Site, not the whole file; a "show all" if reviewers need it.
private fun code(file: List<DiffLine>, side: String, kind: String, from: Int, to: Int): List<CodeLine> {
    val other = if (side == "old") '+' else '-'
    fun number(line: DiffLine) = if (side == "old") line.old else line.new
    var first = file.indexOfFirst { line -> number(line)?.let { it >= from - AROUND } == true }.coerceAtLeast(0)
    var last = file.indexOfLast { line -> number(line)?.let { it <= to + AROUND } == true }
    while (first > 0 && number(file[first - 1]) == null) first-- // the other side's lines at either end
    while (last in 0..<file.lastIndex && number(file[last + 1]) == null) last++
    return file.subList(first, last + 1).map { line ->
        val n = number(line)
        val css = listOfNotNull(
            "cl", if (line.kind == other) null else if (n in from..to) "site $kind" else "dim",
            when (line.kind) { '+' -> "add"; '-' -> "del"; else -> null }, "extra".takeIf { line.kind == other },
        )
        CodeLine(n?.toString() ?: if (line.kind == '-') "−" else "+", line.text.drop(1), css.joinToString(" "))
    }
}

/** Varde's overlap: a Site, [from] to [to] on [side], touches a hunk, `[oldStart, oldLines, newStart, newLines]`, context lines included. */
private fun touches(hunk: List<Int>, side: String, from: Int, to: Int): Boolean {
    val (oldStart, oldLines, newStart, newLines) = hunk
    val (start, lines) = if (side == "old") oldStart to oldLines else newStart to newLines
    return lines > 0 && from < start + lines && start <= to
}

/**
 * Varde's four mechanical checks on the Story set in [workspace], read with [git]: a Site's file missing at its side,
 * its lines out of the file's bounds, a changed Site touching no hunk of its repo's diff, and a cited value absent from
 * the line it cites. Empty when the set holds up; [StorySetRefused] when it doesn't parse.
 */
fun storyProblems(workspace: Path, git: (Path, List<String>) -> String): List<Problem> {
    val set = read(workspace)
    val ranges = Ranges(workspace, set, git)
    return set.list("stories").flatMap { it.list("steps") }.flatMap { step ->
        val id = step["id"].toString()
        val site = step["site"] as Map<*, *>
        val file = site["file"].toString()
        val side = site["side"].toString()
        val from = (site["from"] as Number).toInt()
        val to = (site["to"] as Number).toInt()
        val text = ranges.text(file, side)
        val placed = when {
            text == null -> Fault.FILE_MISSING
            lines(text, from, to) == null -> Fault.RANGE_OUT_OF_BOUNDS
            else -> null
        }
        val unclaimed = Fault.CLAIMS_NO_CHANGE.takeIf {
            placed == null && site["kind"] == "changed" && ranges.hunks(file).none { touches(it, side, from, to) }
        }
        val uncited = step.list("values").filter { value ->
            val cite = value["cite"] as? Map<*, *> ?: return@filter false
            val line = (cite["line"] as Number).toInt()
            ranges.text(cite["file"].toString(), "new")?.let { lines(it, line, line) }?.contains(value["value"].toString()) != true
        }.map { Fault.CITATION_ABSENT }
        (listOfNotNull(placed, unclaimed) + uncited).map { Problem(id, it) }
    }
}

/** What Ants sends the walkthrough when its Story set fails the checks: Varde's fix request, naming only the failing steps. */
fun fixRequest(problems: List<Problem>) = "Ants checked the Story set in $STORIES and can't accept it yet. These steps don't hold up:\n\n" +
    problems.joinToString("\n") { "- `${it.step}`: ${it.fault.label}: ${it.fault.complaint}" } +
    "\n\nRewrite only those steps. Keep the set's `title`, its `ranges`, its stories and every other step as they are, write the whole set back " +
    "to $STORIES, and answer with your Result again."

/** The Story set in [workspace], valid against its schema; [StorySetRefused] when it is missing or doesn't parse. */
private fun read(workspace: Path): Map<*, *> {
    val file = workspace.resolve(STORIES)
    if (!file.isRegularFile()) throw StorySetRefused("there is no such file")
    val set = try {
        json.readValue(file.readText(), Any::class.java)
    } catch (e: JacksonException) {
        throw StorySetRefused(e.originalMessage)
    }
    ResultContract.errors(set, schema, workspace).takeIf { it.isNotEmpty() }?.let { throw StorySetRefused(it.joinToString("; ")) }
    return set as Map<*, *>
}

/** Lines [from] to [to] of [text], 1-based, or null when they don't fit it, as Varde's `lines_in`. */
private fun lines(text: String, from: Int, to: Int): String? {
    val lines = text.removeSuffix("\n").split("\n")
    return if (from == 0 || from > to || to > lines.size) null else lines.subList(from - 1, to).joinToString("\n")
}

/** The Story set's ranges, one per repo, read with [git] in the clones of [workspace]. */
private class Ranges(private val workspace: Path, set: Map<*, *>, private val git: (Path, List<String>) -> String) {
    private val ranges = set.list("ranges").associateBy { it["repo"].toString() }

    /** [file]'s text on [side] of its repo's range: at its base (`old`) or head (`new`); null when it isn't there. */
    fun text(file: String, side: String): String? {
        val (repo, path) = locate(file) ?: return null
        val range = ranges[repo] ?: return null
        return show(repo, "${range[if (side == "old") "base" else "head"]}", path)
    }

    /** [file]'s text in its clone's HEAD now, which may have moved past its range's head; null when it isn't there. */
    fun now(file: String): String? {
        val (repo, path) = locate(file) ?: return null
        return show(repo, "HEAD", path)
    }

    private fun show(repo: String, rev: String, path: String) = runCatching { git(workspace.resolve("repos/$repo"), listOf("show", "$rev:$path")) }.getOrNull()

    /** Every file its range changed, by its path from the Run workspace root. */
    fun changed(): List<String> = ranges.flatMap { (repo, range) ->
        runCatching { git(workspace.resolve("repos/$repo"), listOf("-c", "core.quotePath=false") + DIFF + listOf("--name-only", "${range["base"]}", "${range["head"]}")) }
            .getOrDefault("").lines().filter(String::isNotBlank).map { "repos/$repo/$it" }
    }

    private val code = mutableMapOf<String, List<DiffLine>>()

    /** [file] whole, with its range's diff in it: each line kept, removed or added; all kept when the range didn't change it. */
    fun code(file: String): List<DiffLine> = code.getOrPut(file) {
        val (repo, path) = locate(file) ?: return@getOrPut emptyList()
        val range = ranges[repo] ?: return@getOrPut emptyList()
        val diff = runCatching {
            git(workspace.resolve("repos/$repo"), DIFF + listOf("--unified=1000000", "${range["base"]}", "${range["head"]}", "--", path))
        }.getOrDefault("")
        files(diff, "repos/$repo").firstOrNull()?.lines?.filter { it.kind != '@' }
            ?: text(file, "new")?.removeSuffix("\n")?.split("\n")?.mapIndexed { i, line -> DiffLine(' ', i + 1, i + 1, " $line") }.orEmpty()
    }

    /** [file]'s hunks in its repo's range, each `[oldStart, oldLines, newStart, newLines]`. */
    fun hunks(file: String): List<List<Int>> {
        val (repo, path) = locate(file) ?: return emptyList()
        val range = ranges[repo] ?: return emptyList()
        val diff = runCatching {
            git(workspace.resolve("repos/$repo"), DIFF + listOf("${range["base"]}", "${range["head"]}", "--", path))
        }.getOrDefault("")
        return Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""", RegexOption.MULTILINE).findAll(diff).map { match ->
            val (oldStart, oldLines, newStart, newLines) = match.destructured
            listOf(oldStart.toInt(), oldLines.ifEmpty { "1" }.toInt(), newStart.toInt(), newLines.ifEmpty { "1" }.toInt())
        }.toList()
    }
}

/** The clone and the path inside it of [file], relative to the Run workspace root: `repos/<repo>/<path>`. */
private fun locate(file: String): Pair<String, String>? = file.split('/', limit = 3).takeIf { it.size == 3 && it[0] == "repos" }?.let { it[1] to it[2] }

private fun Map<*, *>.list(key: String) = (this[key] as? List<*>).orEmpty().map { it as Map<*, *> }

private val schema by lazy { json.readValue(StorySet::class.java.getResource(STORY_SET_SCHEMA)!!.readText(), Map::class.java) }
private val json = JsonMapper.shared()
