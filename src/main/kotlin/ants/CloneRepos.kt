package ants

import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.externaltask.LockedExternalTask
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
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
 * Run holds it, or, for `ants/<slug>`, while the remote has it. The work branch is checked out when the remote has it, otherwise created. The push URL is dead, so no
 * Agent pushes; Push gives the real remote itself. A repo already cloned is left alone. The Result is the repos it
 * cloned, `[{name, path, commit}]`, `path` relative to the Run workspace; the Run's `clones` becomes every clone in `repos/`, so one an earlier, failed attempt made is there too.
 * A Run with repos picked ([SCOPE]) clones exactly them instead, whatever `repos` names, first creating each new repo on GitHub.
 */
// ponytail: one thread for every Run, so choosing a work branch never races another Run's; a slow clone holds up
// the rest. A lock per branch name if Runs start together often.
@Component
class CloneRepos(
    private val externalTasks: ExternalTaskService,
    private val runtime: RuntimeService,
    private val settings: Settings,
    private val names: RunNames,
    private val scopes: Scopes,
) : BuiltInWorker(externalTasks, "Clone repos", TOPIC, Duration.ofMinutes(30), { it.variables("repos", "branch", "clones").enableCustomObjectDeserialization() }) {
    override fun handle(task: LockedExternalTask) {
        val run = runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult().rootProcessInstanceId
        val repos = names.runWorkspace(run).resolve("repos").createDirectories()
        // A Run with repos picked clones exactly them, whatever the Task names; its new repos are created only now.
        val source = scopes.of(run)
        val known = source.repos(settings).associateBy { it.name }
        val wanted = source.picked.ifEmpty { Settings.strings(task.variables["repos"]).orEmpty() }
            .map { known[it] ?: url(it) ?: error("The Source map ${source.profile(settings)?.sourceMap} has no repo $it") }
            .filter { !repos.resolve(it.name).exists() }
            .map { if (source.picked.isNotEmpty() && newRepo(it.remote)) it.copy(remote = create(it.remote)) else it }
        val branch by lazy { workBranch(run, task.variables["branch"]?.toString(), wanted.map(Repo::remote), repos) }
        val cloned = wanted.map { repo -> cloneOne(repo, repos, branch) }
        // Every clone on disk, even one an earlier, failed attempt made and so never added.
        @Suppress("UNCHECKED_CAST")
        val kept = (task.variables["clones"] as? List<Map<String, String>>).orEmpty().filter { repos.resolve(it["name"]!!).exists() } + cloned
        val clones = kept + clones(repos.parent).filter { dir -> kept.none { it["name"] == dir.name } }
            .map { mapOf("name" to it.name, "path" to "repos/${it.name}", "commit" to git(it, "rev-parse", "HEAD")) }
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
        fun clone(vararg reference: String) = git(repos, listOf("clone", "-q", *reference, repo.remote, partial.fileName.toString()))
        // A local checkout whose objects are broken fails the borrowing clone; the remote alone still has them all.
        if (local == null || runCatching { clone("--reference", "$local", "--dissociate") }.isFailure) {
            partial.toFile().deleteRecursively()
            clone()
        }
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
     * while another live Run's clones were put on it, or, for the default, while any of its [remotes] has it (asked from [dir]).
     */
    private fun workBranch(run: String, asked: String?, remotes: List<String>, dir: Path): String {
        branches(run).firstOrNull()?.let { return it }
        val given = asked?.takeIf(String::isNotBlank)?.trim()
        val wanted = given ?: names.defaultBranch(run)
        val held = runtime.createProcessInstanceQuery().rootProcessInstances().list().map { it.id }.filter { it != run }.flatMap(::branches).toSet()
        // A given branch the remote has is built on; a default one there is an earlier Run's, as one of the same name pushed it.
        fun taken(branch: String) = branch in held || given == null && remotes.any { git(dir, "ls-remote", "--heads", it, "refs/heads/$branch").isNotEmpty() }
        return generateSequence(1) { it + 1 }.map { if (it == 1) wanted else "$wanted-$it" }.first { !taken(it) }
    }

    /** The work branches the clones in [run]'s Run workspace were put on. */
    private fun branches(run: String): List<String> = clones(names.runWorkspace(run)).mapNotNull(::putOn).distinct()

    /** The work branch Clone repos put [clone] on, or null for a clone it didn't make. */
    private fun putOn(clone: Path) = runCatching { git(clone, "config", WORK_BRANCH) }.getOrNull()

    /**
     * The work branch Clone repos put [clone] on, which every landing and Push uses, wherever the clone is now; for a
     * clone an Agent made by hand, the branch it is on.
     */
    fun workBranch(clone: Path) = putOn(clone) ?: currentBranch(clone)

    /** The branch [clone] is on now, empty when its HEAD is detached. */
    fun currentBranch(clone: Path) = git(clone, "branch", "--show-current")

    /** Runs git in [dir] with [args], giving its output, [trim]med; fails with git's message when git does. */
    fun git(dir: Path, args: List<String>, trim: Boolean = true) = command("git", dir, args, trim)

    /** Runs [cli] (git or gh) in [dir] with [args], giving its output, [trim]med; fails with its message when it does. */
    private fun command(cli: String, dir: Path, args: List<String>, trim: Boolean = true): String {
        val process = ProcessBuilder(listOf(settings.path(cli) ?: cli) + args).directory(dir.toFile())
            .redirectInput(File("/dev/null")).redirectErrorStream(true)
            .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }.start()
        val output = process.inputReader().readText()
        check(process.waitFor() == 0) { "$cli ${args.first { !it.startsWith("-") && '=' !in it }} failed: ${output.trim()}" }
        return if (trim) output.trim() else output
    }

    /**
     * Creates the new repo [name] (`owner/name`) on GitHub on the user's gh login, private and with a README so it has
     * a default branch, and gives its remote in gh's git protocol. A repo of that name already there is taken as made
     * by an earlier try of this Task.
     */
    // ponytail: the pre-Run check found the name free, so one made since by anyone else is cloned too; push still needs its rights.
    private fun create(name: String): String {
        val home = Path.of(System.getProperty("user.home"))
        fun view() = runCatching { command("gh", home, listOf("repo", "view", name, "--json", "url,sshUrl")) }.getOrNull()
        val urls = JsonMapper.shared().readValue(view() ?: command("gh", home, listOf("repo", "create", name, "--private", "--add-readme")).let { view() ?: error("gh can't find the repo $name it created") }, Map::class.java)
        val ssh = runCatching { command("gh", home, listOf("config", "get", "git_protocol")) }.getOrNull() == "ssh"
        return urls[if (ssh) "sshUrl" else "url"].toString()
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
        fun url(url: String) = if ('/' !in url && ':' !in url) null else Repo(url.trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git"), "", url)
    }
}
