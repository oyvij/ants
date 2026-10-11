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
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.io.path.name

/**
 * One of the Run's clones as Push sees it: its [remote] (the fetch URL, since the push URL is dead), its work
 * [branch], its [base] (the remote's default branch, null when the remote had none) and the [commits] on the work
 * branch since the base, newest first, each `{sha, subject}`.
 */
class Outgoing(val repo: String, val path: Path, val remote: String, val branch: String, val base: String?, val commits: List<Map<String, String>>)

/**
 * The Built-in Task Push (topic `push`). It waits on the user as a ↑ `[push]` entry, whose page shows each clone
 * ([Outgoing]). **Push** dry-runs every repo, then pushes each work branch to the same branch on its remote: a
 * fast-forward only, never forced, never the base, and nothing else. A rejection is `[failed]`, naming the repos that
 * pushed and those that didn't, with git's message; the user can push by hand from the kept clone and Continue. The
 * Result has one entry per repo: `{repo, remote, branch, base, isNew, commits, pushed, error, compareUrl}`.
 */
@Component
class Push(
    private val externalTasks: ExternalTaskService,
    private val runtime: RuntimeService,
    private val cloneRepos: CloneRepos,
    @Value("\${ants.home}") private val home: Path,
) {
    private val log = LoggerFactory.getLogger(javaClass)
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
            externalTasks.fetchAndLock(10, WORKER).topic(TOPIC, LOCK.toMillis()).variables(AgentLauncher.ACTION).execute().forEach(::handle)
        } catch (e: Exception) {
            log.warn("Fetching Push Tasks failed", e) // a failed fetch must not end the polling
        }
    }

    /** Waits on the user at first; then pushes, or after a push by hand (Continue) completes with what the remotes hold. */
    private fun handle(task: LockedExternalTask) {
        try {
            val repos = outgoing(runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult().rootProcessInstanceId)
            val action = task.variables[AgentLauncher.ACTION] ?: return wait(task, repos)
            runtime.removeVariablesLocal(task.executionId, listOf(AgentLauncher.ACTION, AgentLauncher.INSTRUCTION)) // the choice is used once
            val errors = if (action == PUSH) push(repos) else repos.filter { !onRemote(it) }.associate { it.repo to "The remote's ${it.branch} isn't this clone's HEAD." }
            if (action == PUSH && errors.isNotEmpty()) {
                val pushed = repos.map { it.repo }.filter { it !in errors }.ifEmpty { listOf("none") }
                externalTasks.handleFailure(
                    task.id, WORKER, "[failed] Pushed: ${pushed.joinToString()}. Not pushed: ${errors.keys.joinToString()}.",
                    errors.entries.joinToString("\n\n") { "${it.key}: ${it.value}" }, 0, 0,
                )
                return
            }
            externalTasks.complete(task.id, WORKER, mapOf(task.activityId to ArrayList(repos.map { result(it, errors[it.repo]) })))
        } catch (e: Exception) {
            if (poller.isShutdown) return // the lock runs out, and the Task is tried again
            log.warn("Push ${task.activityId} failed", e)
            runCatching { externalTasks.handleFailure(task.id, WORKER, "[failed] ${e.message ?: e}", null, 0, 0) }
        }
    }

    private fun wait(task: LockedExternalTask, repos: List<Outgoing>) =
        externalTasks.handleFailure(task.id, WORKER, "[push] Push ${repos.map { it.branch }.distinct().joinToString()} to ${repos.joinToString { it.repo }}", null, 0, 0)

    /**
     * Pushes every repo's work branch, once every dry run passes, giving git's message for each repo that didn't push;
     * when a dry run fails, none pushes.
     */
    private fun push(repos: List<Outgoing>): Map<String, String> {
        fun push(repo: Outgoing, vararg dryRun: String): String? =
            if (repo.branch.isEmpty() || repo.branch == repo.base) "The work branch ${repo.branch} is the base, which Push never pushes to."
            else cloneRepos.currentBranch(repo.path).takeIf { it != repo.branch }?.let { "The clone is on ${it.ifEmpty { "no branch" }}, not the work branch ${repo.branch}." }
            ?: runCatching {
                // The refspec names the work branch alone, without `+`, so it is a fast-forward or nothing.
                cloneRepos.git(repo.path, "push", "--no-follow-tags", "--recurse-submodules=no", *dryRun, repo.remote, "refs/heads/${repo.branch}:refs/heads/${repo.branch}")
            }.exceptionOrNull()?.let { it.message ?: "$it" }
        val refused = repos.mapNotNull { repo -> push(repo, "--dry-run")?.let { repo.repo to it } }.toMap()
        if (refused.isNotEmpty()) return repos.associate { it.repo to (refused[it.repo] ?: "Not pushed, since another repo's dry run failed.") }
        return repos.mapNotNull { repo -> push(repo)?.let { repo.repo to it } }.toMap()
    }

    /** Whether [repo]'s remote has its work branch where the clone has it. */
    private fun onRemote(repo: Outgoing) =
        cloneRepos.git(repo.path, "ls-remote", repo.remote, "refs/heads/${repo.branch}").substringBefore('\t') == cloneRepos.git(repo.path, "rev-parse", "refs/heads/${repo.branch}")

    private fun result(repo: Outgoing, error: String?) = mapOf(
        "repo" to repo.repo, "remote" to repo.remote, "branch" to repo.branch, "base" to repo.base,
        // Clone repos tracks the work branch when the remote had it, and Push pushes to the URL, so no tracking branch is ever added.
        "isNew" to runCatching { cloneRepos.git(repo.path, "rev-parse", "--verify", "-q", "refs/remotes/origin/${repo.branch}") }.isFailure,
        "commits" to repo.commits, "pushed" to (error == null), "error" to error, "compareUrl" to compareUrl(repo),
    )

    /** The Run's clones, by name, each on the work branch Clone repos put it on. */
    fun outgoing(run: String): List<Outgoing> = clones(home.runWorkspace(run)).map { path ->
        val branch = cloneRepos.workBranch(path)
        val base = runCatching { cloneRepos.git(path, "symbolic-ref", "--short", "refs/remotes/origin/HEAD").removePrefix("origin/") }.getOrNull()
        val commits = cloneRepos.git(path, "log", "--format=%H %s", if (base == null) "refs/heads/$branch" else "origin/$base..refs/heads/$branch").lines().filter(String::isNotBlank)
            .map { mapOf("sha" to it.substringBefore(' '), "subject" to it.substringAfter(' ')) }
        Outgoing(path.name, path, cloneRepos.git(path, "config", "remote.origin.url"), branch, base, commits)
    }


    companion object {
        const val TOPIC = "push"

        /** The action of Push's own button, as `ants.action`. */
        const val PUSH = "push"

        private const val WORKER = "ants"
        private val LOCK = Duration.ofMinutes(30)

        /**
         * The forge page comparing the work branch with the base, for an https or ssh remote: GitLab's `/-/compare/`
         * on a host named gitlab, else GitHub's (and Gitea's) `/compare/`. Null for a local remote or no base.
         */
        // ponytail: two URL shapes guessed from the host name; a per-remote setting if Bitbucket or a renamed GitLab turns up.
        fun compareUrl(repo: Outgoing): String? {
            val (host, path) = Regex("""^(?:[a-z+]+://)?(?:[^@/]+@)?([^/:]+\.[^/:]+)(?::\d+)?[:/](.+?)(?:\.git)?/?$""").find(repo.remote)?.destructured ?: return null
            if (repo.base == null) return null
            return "https://$host/$path/${if ("gitlab" in host) "-/" else ""}compare/${repo.base}...${repo.branch}"
        }
    }
}
