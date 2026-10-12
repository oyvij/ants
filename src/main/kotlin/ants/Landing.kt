package ants

import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Landing, which no one draws: each instance of a parallel multi-instance works in its own folder,
 * `instances/<key>/` of the Run workspace, holding a `git worktree` of each clone on `<work-branch>--<key>`, branched
 * from the work branch head, and a link to every other top-level entry. When it is done, it lands: every instance
 * branch is rebased onto the work branch, then the shared clones fast-forward to it, once every one of them can, so the
 * work branch moves in every repo or in none, with linear history. Then the folder and the instance branches go.
 */
// ponytail: one lock for every Run, so landings never interleave; a slow rebase holds up other Runs' landings. A lock
// per Run if that ever shows.
@Component
class Landing(private val cloneRepos: CloneRepos) {
    /** The folder the instance [key] works in: made at its first launch, kept for Continue, and made anew when [fresh]. */
    @Synchronized
    fun folder(workspace: Path, key: String, fresh: Boolean): Path {
        val folder = folder(workspace, key)
        if (fresh) discard(workspace, key)
        if (folder.exists()) return folder
        folder.resolve("repos").createDirectories()
        for (clone in clones(workspace)) {
            cloneRepos.git(clone, "worktree", "add", "-q", "-b", branch(clone, key), "${folder.resolve("repos/${clone.name}")}")
        }
        workspace.listDirectoryEntries().filter { it.name != "repos" && it.name != "instances" }
            .forEach { folder.resolve(it.name).createSymbolicLinkPointingTo(it) }
        return folder
    }

    /**
     * Lands the instance [key] on the work branch of every clone, all or none, then removes its folder and branches,
     * and returns null. A rebase that conflicts is aborted, leaving every work branch where it was and the instance's
     * folder for its session to resolve, and Ants gets back why it didn't land, naming every repo that conflicts. A
     * rebase that fails otherwise, or a shared clone that can't fast-forward, fails the landing ([AgentLauncher.Failed])
     * with nothing moved and the folder kept.
     */
    @Synchronized
    fun land(workspace: Path, key: String): String? {
        // A repo the Agent made itself, say with no clone to work in, has nowhere to land: removing the folder would
        // lose it.
        val names = clones(workspace).map { it.name }.toSet()
        val strangers = folder(workspace, key).resolve("repos").takeIf { it.exists() }?.listDirectoryEntries()
            ?.map { "repos/${it.name}" }?.filter { it.substringAfter('/') !in names }.orEmpty()
        if (strangers.isNotEmpty()) throw AgentLauncher.Failed("$key didn't land: ${strangers.joinToString(" and ")} " +
            "${if (strangers.size == 1) "isn't a clone" else "aren't clones"} of this Run, so nothing moved and its folder is kept.")
        val worktrees = clones(workspace).associateWith { folder(workspace, key).resolve("repos/${it.name}") }.filterValues { it.exists() }
        val conflicts = worktrees.mapNotNull { (clone, worktree) ->
            val work = cloneRepos.workBranch(clone)
            // Rebasing writes commits; without an identity of the user's, they are Ants'.
            val identity = if (runCatching { cloneRepos.git(clone, "config", "user.email") }.isSuccess) emptyArray() else arrayOf("-c", "user.name=Ants", "-c", "user.email=ants@localhost")
            try {
                cloneRepos.git(worktree, *identity, "rebase", "-q", work)
                null
            } catch (e: IllegalStateException) {
                val conflict = runCatching { cloneRepos.git(worktree, "ls-files", "--unmerged") }.getOrDefault("").isNotEmpty()
                runCatching { cloneRepos.git(worktree, "rebase", "--abort") }
                if (!conflict) throw AgentLauncher.Failed("$key didn't land: repos/${clone.name} doesn't rebase onto the work branch $work, so no repo moved.", e.message)
                "repos/${clone.name}" to "repos/${clone.name}, on $work: ${e.message}"
            }
        }
        if (conflicts.isNotEmpty()) {
            return "$key didn't land: it doesn't rebase onto the work branch of ${conflicts.joinToString(" and ") { it.first }}, so no repo moved.\n\n" +
                conflicts.joinToString("\n\n") { it.second }
        }
        // Every shared clone must be able to fast-forward before any moves.
        val stuck = worktrees.keys.mapNotNull { clone ->
            val work = cloneRepos.workBranch(clone)
            val on = cloneRepos.currentBranch(clone)
            val dirty = cloneRepos.git(clone, "status", "--porcelain")
            when {
                on != work -> "repos/${clone.name} is on ${on.ifEmpty { "no branch" }}, not the work branch $work."
                dirty.isNotEmpty() -> "repos/${clone.name} isn't clean:\n$dirty"
                runCatching { cloneRepos.git(clone, "merge-base", "--is-ancestor", work, branch(clone, key)) }.isFailure -> "repos/${clone.name}'s work branch $work moved during the landing."
                else -> null
            }
        }
        if (stuck.isNotEmpty()) throw AgentLauncher.Failed("$key didn't land: a shared clone can't fast-forward, so no repo moved. ${stuck.joinToString(" ") { it.substringBefore('\n') }}", stuck.joinToString("\n\n"))
        for (clone in worktrees.keys) cloneRepos.git(clone, "merge", "-q", "--ff-only", branch(clone, key))
        discard(workspace, key)
        return null
    }

    /** Removes the instance [key]'s folder, its worktrees and its branches, if it has them. */
    @OptIn(ExperimentalPathApi::class)
    private fun discard(workspace: Path, key: String) {
        val folder = folder(workspace, key)
        for (clone in clones(workspace)) {
            val worktree = folder.resolve("repos/${clone.name}")
            if (worktree.exists()) cloneRepos.git(clone, "worktree", "remove", "--force", "$worktree")
            runCatching { cloneRepos.git(clone, "branch", "-q", "-D", branch(clone, key)) }
        }
        folder.deleteRecursively() // leaves what the links lead to
    }

    private fun folder(workspace: Path, key: String) = workspace.resolve("instances").resolve(key)

    /** The instance [key]'s branch in [clone]: `<work-branch>--<key>`. */
    private fun branch(clone: Path, key: String) = "${cloneRepos.workBranch(clone)}--$key"
}
