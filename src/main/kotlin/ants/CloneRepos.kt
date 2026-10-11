package ants

import jakarta.annotation.PreDestroy
import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.externaltask.LockedExternalTask
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * The Built-in Task Clone repos (topic `clone-repos`). It clones each of its `repos` (Source map names or git URLs)
 * into `<Run workspace>/repos/<name>/` at the remote's default branch, borrowing the Source map's local checkout's
 * objects (`--reference … --dissociate`), and puts it on the Run's work branch: the branch the Run's clones are
 * already on, else the `branch` input, else `ants/<run-id>`, with a counter suffix (`-2`, `-3` …) while another live
 * Run holds it. The work branch is checked out when the remote has it, otherwise created. The push URL is dead, so no
 * Agent pushes; Push gives the real remote itself. A repo already cloned is left alone. The Result is the repos it
 * cloned, `[{name, path, commit}]`, `path` relative to the Run workspace, and they are added to the Run's `clones`.
 */
@Component
class CloneRepos(
    private val externalTasks: ExternalTaskService,
    private val runtime: RuntimeService,
    private val settings: Settings,
    @Value("\${ants.home}") private val home: Path,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ponytail: one thread for every Run, so choosing a work branch never races another Run's; a slow clone holds up
    // the rest. A lock per branch name if Runs start together often.
    private val poller = Executors.newSingleThreadScheduledExecutor()

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        poller.scheduleWithFixedDelay(::fetch, 0, 200, MILLISECONDS)
    }

    @PreDestroy
    fun shutdown() {
        poller.shutdownNow()
    }

    private fun fetch() {
        try {
            externalTasks.fetchAndLock(10, WORKER).topic(TOPIC, LOCK.toMillis())
                .variables("repos", "branch", "clones").enableCustomObjectDeserialization().execute().forEach(::clone)
        } catch (e: Exception) {
            log.warn("Fetching Clone repos Tasks failed", e) // a failed fetch must not end the polling
        }
    }

    private fun clone(task: LockedExternalTask) {
        try {
            val run = runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult().rootProcessInstanceId
            val repos = home.runWorkspace(run).resolve("repos").createDirectories()
            val known = settings.repos().associateBy { it.name }
            val wanted = Settings.strings(task.variables["repos"]).orEmpty().map { known[it] ?: url(it) ?: error("The Source map ${settings.defaultSourceMap()} has no repo $it") }.filter { !repos.resolve(it.name).exists() }
            val branch by lazy { workBranch(run, task.variables["branch"]?.toString()) }
            val cloned = wanted.map { repo -> cloneOne(repo, repos, branch) }
            @Suppress("UNCHECKED_CAST")
            val clones = (task.variables["clones"] as? List<Map<String, String>>).orEmpty() + cloned
            externalTasks.complete(task.id, WORKER, mapOf(task.activityId to ArrayList(cloned), "clones" to ArrayList(clones)))
        } catch (e: Exception) {
            if (poller.isShutdown) return // the lock runs out, and the Task is tried again
            log.warn("Clone repos ${task.activityId} failed", e)
            runCatching { externalTasks.handleFailure(task.id, WORKER, "[failed] ${e.message ?: e}", null, 0, 0) }
        }
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
        val onRemote = runCatching { git(partial, listOf("rev-parse", "--verify", "-q", "refs/remotes/origin/$branch")) }.isSuccess
        git(partial, if (onRemote) listOf("switch", "-q", branch) else listOf("switch", "-q", "-c", branch))
        git(partial, listOf("remote", "set-url", "--push", "origin", DEAD_PUSH_URL))
        val commit = git(partial, listOf("rev-parse", "HEAD"))
        Files.move(partial, repos.resolve(repo.name))
        return mapOf("name" to repo.name, "path" to "repos/${repo.name}", "commit" to commit)
    }

    /**
     * The work branch of [run]: the branch its clones are on, else [asked], else `ants/<run>`, suffixed `-2`, `-3` …
     * while another live Run's clones are on it.
     */
    private fun workBranch(run: String, asked: String?): String {
        branches(run).firstOrNull()?.let { return it }
        val wanted = asked?.takeIf(String::isNotBlank)?.trim() ?: "ants/$run"
        val held = runtime.createProcessInstanceQuery().rootProcessInstances().list().map { it.id }.filter { it != run }.flatMap(::branches).toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) wanted else "$wanted-$it" }.first { it !in held }
    }

    /** The branches the clones in [run]'s Run workspace are on. */
    private fun branches(run: String): List<String> =
        home.runWorkspace(run).resolve("repos").toFile().listFiles().orEmpty().filter { !it.name.startsWith(".") }
            .mapNotNull { File(it, ".git/HEAD").takeIf(File::exists)?.readText()?.let { head -> Regex("^ref: refs/heads/(.+)").find(head.trim())?.groupValues?.get(1) } }
            .distinct()

    /** Runs git in [dir] with [args], giving its output, trimmed; fails with git's message when git does. */
    fun git(dir: Path, args: List<String>): String {
        val process = ProcessBuilder(listOf(settings.path("git") ?: "git") + args).directory(dir.toFile())
            .redirectInput(File("/dev/null")).redirectErrorStream(true)
            .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }.start()
        val output = process.inputReader().readText().trim()
        check(process.waitFor() == 0) { "git ${args.first()} failed: $output" }
        return output
    }

    companion object {
        const val TOPIC = "clone-repos"
        private const val WORKER = "ants"
        private val LOCK = Duration.ofMinutes(30)

        /** A push URL nothing answers at, so `git push` from a clone fails. */
        const val DEAD_PUSH_URL = "/dev/null/ants-pushes-only-from-push"

        /**
         * A repo given by its git URL instead of a Source map name, named after the URL's last part, with no local
         * checkout; null when [url] isn't one.
         */
        private fun url(url: String) = if ('/' !in url && ':' !in url) null else Repo(url.trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git"), "", url)
    }
}
