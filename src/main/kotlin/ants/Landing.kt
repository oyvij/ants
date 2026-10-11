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
 * branch is rebased onto the work branch, then the shared clones fast-forward to it, so the work branch moves in every
 * repo or in none, with linear history. Then the folder and the instance branches go.
 */
// ponytail: one lock for every Run, so landings never interleave; a slow rebase holds up other Runs' landings. A lock
// per Run if that ever shows.
@Component
class Landing(private val settings: Settings) {
    /** The folder the instance [key] works in: made at its first launch, kept for Continue, and made anew when [fresh]. */
    @Synchronized
    fun folder(workspace: Path, key: String, fresh: Boolean): Path {
        val folder = folder(workspace, key)
        if (fresh) discard(workspace, key)
        if (folder.exists()) return folder
        folder.resolve("repos").createDirectories()
        for (clone in clones(workspace)) {
            git(clone, "worktree", "add", "-q", "-b", "${branch(clone)}--$key", "${folder.resolve("repos/${clone.name}")}")
        }
        workspace.listDirectoryEntries().filter { it.name != "repos" && it.name != "instances" }
            .forEach { folder.resolve(it.name).createSymbolicLinkPointingTo(it) }
        return folder
    }

    /**
     * Lands the instance [key] on the work branch of every clone, all or none, then removes its folder and branches.
     * A rebase that fails is aborted and leaves every work branch where it was, and the instance's folder for Continue.
     */
    @Synchronized
    fun land(workspace: Path, key: String) {
        val worktrees = clones(workspace).associateWith { folder(workspace, key).resolve("repos/${it.name}") }.filterValues { it.exists() }
        for ((clone, worktree) in worktrees) {
            // Rebasing writes commits; without an identity of the user's, they are Ants'.
            val identity = if (runCatching { git(clone, "config", "user.email") }.isSuccess) emptyArray() else arrayOf("-c", "user.name=Ants", "-c", "user.email=ants@localhost")
            try {
                git(worktree, *identity, "rebase", "-q", branch(clone))
            } catch (e: IllegalStateException) {
                runCatching { git(worktree, "rebase", "--abort") }
                throw IllegalStateException("$key didn't land: it doesn't rebase onto the work branch of ${clone.name}, so no repo moved. ${e.message}", e)
            }
        }
        for ((clone, worktree) in worktrees) git(clone, "merge", "-q", "--ff-only", branch(worktree))
        discard(workspace, key)
    }

    /** Removes the instance [key]'s folder, its worktrees and its branches, if it has them. */
    @OptIn(ExperimentalPathApi::class)
    private fun discard(workspace: Path, key: String) {
        val folder = folder(workspace, key)
        for (clone in clones(workspace)) {
            val worktree = folder.resolve("repos/${clone.name}")
            if (worktree.exists()) git(clone, "worktree", "remove", "--force", "$worktree")
            runCatching { git(clone, "branch", "-q", "-D", "${branch(clone)}--$key") }
        }
        folder.deleteRecursively() // leaves what the links lead to
    }

    private fun folder(workspace: Path, key: String) = workspace.resolve("instances").resolve(key)

    /** The shared clones of the Run workspace, not one cut short ([CloneRepos] clones into a hidden folder first). */
    private fun clones(workspace: Path) = workspace.resolve("repos").takeIf { it.exists() }?.listDirectoryEntries()?.filter { !it.name.startsWith(".") }.orEmpty().sorted()

    private fun branch(clone: Path) = git(clone, "branch", "--show-current")

    private fun git(dir: Path, vararg args: String) = settings.git(dir, args.toList())
}
