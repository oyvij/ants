package ants

import tools.jackson.databind.json.JsonMapper
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
class FileDiff(val path: String, val lines: List<DiffLine>) {
    val added get() = lines.count { it.kind == '+' }
    val removed get() = lines.count { it.kind == '-' }
}

/** One file's line counts in a repo's changes, [path] relative to the Run workspace root; 0 and 0 for a binary file. */
class FileStat(val path: String, val added: Int, val removed: Int)

/** A repo's changes under review: its commits since the base, as Push reads them ([repo]), and each file's line counts. */
class RepoChanges(val repo: Outgoing, val files: List<FileStat>) {
    val added get() = files.sumOf { it.added }
    val removed get() = files.sumOf { it.removed }
    val lines get() = added + removed
}

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

        /**
         * A line comment from its [fields], as the page's form or [json] gives them: `to` is `from` when missing, the
         * two are put in order, and a story without a step (or a step without a story) is dropped. Null when it lacks a
         * repo, a file, a side (`old` or `new`), its first line or a type.
         */
        fun of(fields: Map<*, *>): LineComment? {
            fun text(key: String) = fields[key]?.toString()?.takeIf(String::isNotBlank)
            val from = text("from")?.toIntOrNull() ?: return null
            val to = text("to")?.toIntOrNull() ?: from
            val story = text("story")
            val step = text("step")?.toIntOrNull()
            return LineComment(
                story?.takeIf { step != null }, step?.takeIf { story != null }, text("repo") ?: return null, text("file") ?: return null,
                text("side")?.takeIf { it == "old" || it == "new" } ?: return null, minOf(from, to), maxOf(from, to),
                text("type")?.takeIf { it in TYPES } ?: return null, fields["body"]?.toString().orEmpty(),
            )
        }

        /** [comments] as JSON, as a Review changes keeps them. */
        fun json(comments: List<LineComment>): String = JsonMapper.shared().writeValueAsString(comments.map(LineComment::toMap))

        /** The comments the JSON [json] holds, none when null. */
        fun all(json: String?): List<LineComment> =
            json?.let { JsonMapper.shared().readValue(it, List::class.java) }.orEmpty().mapNotNull { of(it as Map<*, *>) }
    }
}

/** How many changed lines By repo shows on load, at most, before it leaves the remaining repos' diffs to load when opened. */
const val INLINE = 3000

/** `git diff` as Ants reads it: plain, whatever the user's git config says, and a renamed file as a delete and an add. */
val DIFF = listOf("diff", "--no-color", "--no-ext-diff", "--no-renames")

/** The base and head [repo]'s changes are read between; null without a base. */
private fun range(repo: Outgoing) = repo.base?.let { "${repo.baseHead ?: "origin/$it"}...${repo.head ?: "HEAD"}" }

/** [git] in [repo]'s clone, kept when the range names commits only, as git's output for them never changes. */
private fun read(repo: Outgoing, args: List<String>, git: (Path, List<String>) -> String) =
    if (repo.head == null || repo.baseHead == null) git(repo.path, args) else memo(repo.path, args, git)

/** Git's output for [args] in [dir], kept by both, for arguments that name commits only, whose output never changes. */
private val memo = object : LinkedHashMap<Pair<Path, List<String>>, String>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Path, List<String>>, String>) = size > 500
}

private fun memo(dir: Path, args: List<String>, git: (Path, List<String>) -> String): String {
    synchronized(memo) { memo[dir to args] }?.let { return it }
    return git(dir, args).also { synchronized(memo) { memo[dir to args] = it } }
}

/**
 * The changes in each of the Run's clones that has commits since the base, [outgoing] as Push sees them, and each
 * file's line counts, read side by side with [git]. A clone without commits is left out.
 */
fun changes(outgoing: List<Outgoing>, git: (Path, List<String>) -> String): List<RepoChanges> = outgoing.filter { it.commits.isNotEmpty() }.parallelStream().map { repo ->
    val stat = range(repo)?.let { read(repo, listOf("-c", "core.quotePath=false") + DIFF + "--numstat" + it, git) }
    RepoChanges(repo, stat.orEmpty().lines().filter(String::isNotBlank).map { line ->
        val (added, removed, file) = line.split('\t', limit = 3)
        FileStat("repos/${repo.repo}/$file", added.toIntOrNull() ?: 0, removed.toIntOrNull() ?: 0)
    })
}.toList()

/** [repo]'s diff since the base, read with [git], each file's path from the Run workspace root. */
fun diff(repo: Outgoing, git: (Path, List<String>) -> String): List<FileDiff> =
    files(range(repo)?.let { read(repo, listOf("-c", "core.quotePath=false") + DIFF + it, git) }.orEmpty(), "repos/${repo.repo}")

/** The files of the unified diff [diff], each path prefixed with the clone's [path] in the Run workspace. */
internal fun files(diff: String, path: String): List<FileDiff> {
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
