package ants

import java.io.File
import java.nio.file.Path

/** A commit under review, by its short hash. */
class Commit(val hash: String, val subject: String)

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

/** A repo's changes under review: the commits on its work branch since [base] and their diff. */
class RepoChanges(val name: String, val branch: String, val base: String, val commits: List<Commit>, val files: List<FileDiff>)

/**
 * A line comment, in Varde's shape: [story] and [step] say which story step it is on, else null; [from] and [to] are
 * lines on the [side] (`old` or `new`) of [file], relative to the Run workspace root.
 */
data class LineComment(
    val story: Int?, val step: Int?, val repo: String, val file: String, val side: String, val from: Int, val to: Int, val type: String, val body: String,
) {
    /** As the fix Task's message and the Run log list it, Varde's way: `- ISSUE repos/api/a.kt:3-5 (new): body`. */
    val summary get() = "- $type $file:$from${if (to != from) "-$to" else ""} ($side): ${body.trim().replace("\n", "\n  ")}"

    fun toMap() = mapOf("story" to story, "step" to step, "repo" to repo, "file" to file, "side" to side, "from" to from, "to" to to, "type" to type, "body" to body)

    companion object {
        val TYPES = listOf("ISSUE", "NOTE", "SUGGESTION", "COMMENT")
    }
}

/**
 * The changes of each of [clones] (the Run's `clones`) in [workspace], read with [git]: the commits since the base,
 * where the work branch left the remote's default branch, and their diff.
 */
fun changes(workspace: Path, clones: Any?, git: String): List<RepoChanges> = (clones as? List<*>).orEmpty().map { it as Map<*, *> }.map { clone ->
    val name = clone["name"].toString()
    val path = clone["path"].toString()
    fun git(vararg args: String) = git(git, workspace.resolve(path), *args)
    val base = runCatching { git("merge-base", "origin/HEAD", "HEAD") }.getOrElse { "" }
    val commits = if (base.isEmpty()) emptyList() else git("log", "--reverse", "--format=%h%x09%s", "$base..HEAD").lines().filter(String::isNotEmpty)
        .map { Commit(it.substringBefore('\t'), it.substringAfter('\t')) }
    val diff = if (base.isEmpty()) "" else git("-c", "core.quotePath=false", "diff", "--no-color", "--no-ext-diff", "--no-renames", base, "HEAD")
    RepoChanges(name, git("branch", "--show-current"), base.take(7), commits, files(diff, path))
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

/** Runs [git] in [dir] with [args], giving its output less its last newline; fails with git's message when git does. */
// ponytail: a second copy of CloneRepos' git runner; one shared one once Push needs it too.
private fun git(git: String, dir: Path, vararg args: String): String {
    val process = ProcessBuilder(listOf(git) + args).directory(dir.toFile()).redirectInput(File("/dev/null")).redirectErrorStream(true).start()
    val output = process.inputReader().readText().trimEnd('\n')
    check(process.waitFor() == 0) { "git ${args.first()} failed: $output" }
    return output
}
