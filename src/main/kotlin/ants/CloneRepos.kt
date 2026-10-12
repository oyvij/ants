package ants

import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.externaltask.LockedExternalTask
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** The Run's clones in [workspace], by name: each folder in `repos/` but one cut short, which [CloneRepos] keeps hidden. */
fun clones(workspace: Path): List<Path> =
    workspace.resolve("repos").takeIf { it.isDirectory() }?.listDirectoryEntries()?.filter { it.isDirectory() && !it.name.startsWith(".") }?.sorted().orEmpty()

/**
 * The Built-in Task Clone repos (topic `clone-repos`). It clones each of its `repos` (Source map names or git URLs)
 * into `<Run workspace>/repos/<name>/` at the remote's default branch, borrowing the Source map's local checkout's
 * objects (`--reference … --dissociate`), and puts it on the Run's work branch: the branch the Run's clones are
 * already on, else the `branch` input, else `ants/<slug>` of the Run's name, with a counter suffix (`-2`, `-3` …) while another live
 * Run holds it. The work branch is checked out when the remote has it, otherwise created. The push URL is dead, so no
 * Agent pushes; Push gives the real remote itself. A repo already cloned is left alone. The Result is the repos it
 * cloned, `[{name, path, commit}]`, `path` relative to the Run workspace, and they are added to the Run's `clones`.
 */
// ponytail: one thread for every Run, so choosing a work branch never races another Run's; a slow clone holds up
// the rest. A lock per branch name if Runs start together often.
@Component
class CloneRepos(
    private val externalTasks: ExternalTaskService,
    private val runtime: RuntimeService,
    private val settings: Settings,
    private val names: RunNames,
) : BuiltInWorker(externalTasks, "Clone repos", TOPIC, Duration.ofMinutes(30), { it.variables("repos", "branch", "clones").enableCustomObjectDeserialization() }) {
    override fun handle(task: LockedExternalTask) {
        val run = runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult().rootProcessInstanceId
        val repos = names.runWorkspace(run).resolve("repos").createDirectories()
        val known = settings.repos().associateBy { it.name }
        val wanted = Settings.strings(task.variables["repos"]).orEmpty().map { known[it] ?: url(it) ?: error("The Source map ${settings.defaultSourceMap()} has no repo $it") }.filter { !repos.resolve(it.name).exists() }
        val branch by lazy { workBranch(run, task.variables["branch"]?.toString()) }
        val cloned = wanted.map { repo -> cloneOne(repo, repos, branch) }
        @Suppress("UNCHECKED_CAST")
        val clones = (task.variables["clones"] as? List<Map<String, String>>).orEmpty() + cloned
        externalTasks.complete(task.id, WORKER, mapOf(task.activityId to ArrayList(cloned), "clones" to ArrayList(clones)))
    }

    /**
     * Clones [repo] into [repos] on [branch], first into a hidden folder and moved in once done, so a clone cut short
     * never counts as cloned. Gives its `{name, path, commit}`.
     */
    private fun cloneOne(repo: Repo, repos: Path, branch: String): Map<String, String> {
        val partial = repos.resolve(".${repo.name}")
        partial.toFile().deleteRecursively()
        val local = repo.path.takeIf(String::isNotEmpty)?.let(Settings::expand)?.takeIf { it.isDirectory() }
        git(repos, listOf("clone", "-q") + (if (local == null) emptyList() else listOf("--reference", "$local", "--dissociate")) + listOf(repo.remote, partial.fileName.toString()))
        val onRemote = runCatching { git(partial, "rev-parse", "--verify", "-q", "refs/remotes/origin/$branch") }.isSuccess
        git(partial, if (onRemote) listOf("switch", "-q", branch) else listOf("switch", "-q", "-c", branch))
        git(partial, "remote", "set-url", "--push", "origin", DEAD_PUSH_URL)
        git(partial, "config", WORK_BRANCH, branch)
        val commit = git(partial, "rev-parse", "HEAD")
        Files.move(partial, repos.resolve(repo.name))
        return mapOf("name" to repo.name, "path" to "repos/${repo.name}", "commit" to commit)
    }

    /**
     * The work branch of [run]: the one its clones were put on, else [asked], else `ants/<slug>` ([RunNames.defaultBranch]), suffixed `-2`, `-3` …
     * while another live Run's clones were put on it.
     */
    private fun workBranch(run: String, asked: String?): String {
        branches(run).firstOrNull()?.let { return it }
        val wanted = asked?.takeIf(String::isNotBlank)?.trim() ?: names.defaultBranch(run)
        val held = runtime.createProcessInstanceQuery().rootProcessInstances().list().map { it.id }.filter { it != run }.flatMap(::branches).toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) wanted else "$wanted-$it" }.first { it !in held }
    }

    /** The work branches the clones in [run]'s Run workspace were put on. */
    private fun branches(run: String): List<String> = clones(names.runWorkspace(run)).mapNotNull { runCatching { workBranch(it) }.getOrNull() }.distinct()

    /** The work branch Clone repos put [clone] on, which every landing and Push uses, wherever the clone is now. */
    fun workBranch(clone: Path) = git(clone, "config", WORK_BRANCH)

    /** The branch [clone] is on now, empty when its HEAD is detached. */
    fun currentBranch(clone: Path) = git(clone, "branch", "--show-current")

    /** Runs git in [dir] with [args], giving its output, [trim]med; fails with git's message when git does. */
    fun git(dir: Path, args: List<String>, trim: Boolean = true): String {
        val process = ProcessBuilder(listOf(settings.path("git") ?: "git") + args).directory(dir.toFile())
            .redirectInput(File("/dev/null")).redirectErrorStream(true)
            .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }.start()
        val output = process.inputReader().readText()
        check(process.waitFor() == 0) { "git ${args.first { !it.startsWith("-") && '=' !in it }} failed: ${output.trim()}" }
        return if (trim) output.trim() else output
    }

    fun git(dir: Path, vararg args: String) = git(dir, args.toList())

    /** [git] with its output as is, for file contents and diffs, whose leading spaces count. */
    val untrimmed: (Path, List<String>) -> String = { dir, args -> git(dir, args, trim = false) }

    companion object {
        const val TOPIC = "clone-repos"

        /** A push URL nothing answers at, so `git push` from a clone fails. */
        const val DEAD_PUSH_URL = "/dev/null/ants-pushes-only-from-push"

        /** Where a clone keeps the work branch Clone repos put it on, in its git config. */
        private const val WORK_BRANCH = "ants.workBranch"

        /**
         * A repo given by its git URL instead of a Source map name, named after the URL's last part, with no local
         * checkout; null when [url] isn't one.
         */
        private fun url(url: String) = if ('/' !in url && ':' !in url) null else Repo(url.trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git"), "", url)
    }
}
