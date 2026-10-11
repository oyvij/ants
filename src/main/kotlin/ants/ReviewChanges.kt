package ants

import java.nio.file.Path

/**
 * One row of a unified diff: a hunk header (`@`), or a line kept (` `), removed (`-`) or added (`+`), with its numbers
 * in the [old] and [new] file. A comment on it is on the [side] it is in, at [line].
 */
class DiffLine(val kind: Char, val old: Int?, val new: Int?, val text: String) {
    val css get() = when (kind) { '+' -> "add"; '-' -> "del"; '@' -> "hunk"; else -> "ctx" }
    val side get() = if (kind == '-') "old" else "new"
    val line get() = if (kind == '-') old else new
}

/** One file's diff, [path] relative to the Run workspace root, as every comment's `file` is. */
class FileDiff(val path: String, val lines: List<DiffLine>)

/** A repo's changes under review: its commits since the base, as Push reads them ([repo]), and their diff. */
class RepoChanges(val repo: Outgoing, val files: List<FileDiff>)

/**
 * A line comment, in Varde's shape: [story] and [step] say which story step it is on, as Varde does, by the story's
 * name and the step's number, 1-based, else null; [from] and [to] are lines on the [side] (`old` or `new`) of [file],
 * relative to the Run workspace root.
 */
data class LineComment(
    val story: String?, val step: Int?, val repo: String, val file: String, val side: String, val from: Int, val to: Int, val type: String, val body: String,
) {
    /**
     * As the fix Task's message and the Run log list it, Varde's way: `- ISSUE repos/api/a.kt:3-5 (new): body`, with
     * `, <story> step <n>` before the colon when it is on a story step.
     */
    val summary get() = "- $type $file:$from-$to ($side)${if (story != null) ", $story step $step" else ""}: ${body.trim().replace("\n", "\n  ")}"

    fun toMap() = mapOf("story" to story, "step" to step, "repo" to repo, "file" to file, "side" to side, "from" to from, "to" to to, "type" to type, "body" to body)

    companion object {
        val TYPES = listOf("ISSUE", "NOTE", "SUGGESTION", "COMMENT")
    }
}

/** `git diff` as Ants reads it: plain, whatever the user's git config says, and a renamed file as a delete and an add. */
val DIFF = listOf("diff", "--no-color", "--no-ext-diff", "--no-renames")

/**
 * The changes in each of the Run's clones, [outgoing] as Push sees them: the commits on the work branch since the
 * base, the remote's default branch, and their diff, read with [git].
 */
fun changes(outgoing: List<Outgoing>, git: (Path, List<String>) -> String): List<RepoChanges> = outgoing.map { repo ->
    val diff = repo.base?.let { git(repo.path, listOf("-c", "core.quotePath=false") + DIFF + "origin/$it...HEAD") }
    RepoChanges(repo, files(diff.orEmpty(), "repos/${repo.repo}"))
}

/** The files of the unified diff [diff], each path prefixed with the clone's [path] in the Run workspace. */
private fun files(diff: String, path: String): List<FileDiff> {
    val files = mutableListOf<Pair<String, MutableList<DiffLine>>>()
    var old = 0
    var new = 0
    for (line in diff.lines()) {
        when {
            line.startsWith("diff --git ") -> files += "" to mutableListOf()
            files.isEmpty() -> {}
            // The file's header, before its first hunk: its name, from `+++ b/`, or `--- a/` when it was deleted.
            files.last().second.isEmpty() && !line.startsWith("@@") -> Regex("""^(?:--- a|\+\+\+ b)/(.+?)\t?$""").find(line)
                ?.let { files[files.lastIndex] = "$path/${it.groupValues[1]}" to files.last().second }
            line.startsWith("@@") -> {
                val (o, n) = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)""").find(line)!!.destructured
                old = o.toInt()
                new = n.toInt()
                files.last().second += DiffLine('@', null, null, line)
            }
            line.startsWith("+") -> files.last().second += DiffLine('+', null, new++, line)
            line.startsWith("-") -> files.last().second += DiffLine('-', old++, null, line)
            line.startsWith(" ") -> files.last().second += DiffLine(' ', old++, new++, line)
        }
    }
    return files.map { (file, lines) -> FileDiff(file, lines) }
}
