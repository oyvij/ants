package ants

import tools.jackson.core.JacksonException
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
 * `story-set.schema.json`), each step's Site with the lines it points at.
 */
class StorySet(val title: String, val stories: List<Story>)

class Story(val name: String, val premise: String, val steps: List<Step>)

/** A step: its claim about the lines [from] to [to] on the [side] of [file], in the clone [repo], which hold [lines]. */
class Step(
    val name: String, val claim: String, val why: String, val repo: String, val file: String, val side: String, val kind: String, val from: Int, val to: Int,
    val lines: List<Pair<Int, String>>, val flow: String?, val values: List<Value>, val nudge: String?,
) {
    val at get() = "$file:$from${if (to != from) "-$to" else ""}"
}

/** A concrete value a step names, and where it comes from: its provenance and the line it cites, or `invented`. */
class Value(val name: String, val value: String, val source: String)

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
    return StorySet(set["title"].toString(), set.list("stories").map { story ->
        Story(story["name"].toString(), story["premise"].toString(), story.list("steps").map { step ->
            val site = step["site"] as Map<*, *>
            val file = site["file"].toString()
            val from = (site["from"] as Number).toInt()
            val to = (site["to"] as Number).toInt()
            val text = ranges.text(file, site["side"].toString())?.lines().orEmpty()
            Step(
                step["name"].toString(), step["claim"].toString(), step["why"].toString(), locate(file)?.first.orEmpty(), file, site["side"].toString(),
                site["kind"].toString(), from, to, (from..to).mapNotNull { n -> text.getOrNull(n - 1)?.let { n to it } },
                (step["flow"] as? Map<*, *>)?.let { "In: ${it["in"]}. Out: ${it["out"]}." },
                step.list("values").map { value ->
                    val cite = value["cite"] as? Map<*, *>
                    Value(value["name"].toString(), value["value"].toString(), if (cite == null) "invented" else "${value["provenance"]}, ${cite["file"]}:${cite["line"]}")
                },
                step["nudge"] as? String,
            )
        })
    })
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
            placed == null && site["kind"] == "changed" && ranges.hunks(file).none { (oldStart, oldLines, newStart, newLines) ->
                // Varde's overlap: a Site claims a hunk, context lines included, by touching it.
                val (start, lines) = if (side == "old") oldStart to oldLines else newStart to newLines
                lines > 0 && from < start + lines && start <= to
            }
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
        return runCatching { git(workspace.resolve("repos/$repo"), listOf("show", "${range[if (side == "old") "base" else "head"]}:$path")) }.getOrNull()
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
