package ants

import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.repository.ProcessDefinition
import org.operaton.bpm.model.bpmn.instance.CallActivity
import org.operaton.bpm.model.bpmn.instance.ServiceTask
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.exists

/** Why a Run can't start: the pre-Run check's reasons. */
class CantStart(val reasons: List<String>) : Exception(reasons.joinToString("\n"))

/**
 * The pre-Run check: everything a Run of a Process needs that can be checked before it starts. The Process page shows
 * its reasons, and [StartForm.start] refuses while there are any, so **Start**, and Schedules through it, never start
 * a Run that would fail on something checkable.
 */
// ponytail: runs whole on every Process page load, the probes one after another (seconds with real remotes); render
// it async or cache it briefly if the page gets slow.
@Component
class PreRun(
    private val repository: RepositoryService,
    private val checks: Checks,
    private val catalogue: Catalogue,
    private val settings: Settings,
    private val launcher: AgentLauncher,
    private val models: Models,
    @Value("\${ants.home}") private val home: Path,
) {
    // ponytail: in memory, so a custom model ID is tried once per Ants start and claude command; a bad one stays bad until then.
    private val tried = ConcurrentHashMap<Pair<List<String>, String>, String>() // (claude command, model ID) -> why it failed, empty when it answered
    private val ghAsked = ConcurrentHashMap<String, Long>() // gh command -> when gh last said it is logged in (System.nanoTime)

    /**
     * Why a Run of [definition] with the Profile [profile] (null for the default) and the Repos [repos] (Source map
     * names, git URLs and new repos' `owner/name`) can't start; empty when it's Ready. A Profile that isn't in
     * the settings is the only reason, and so is [CONFIGURE_VAULT] without the Profile's Vault and with no Repos, since the
     * scout then picks the repos from the Vault; with Repos picked the Vault is optional (ADR 0014). Each repo
     * must answer `git ls-remote`, and each new repo's name must be free. The Processes it calls are checked too, since
     * they run inside its Run. A page checking many Processes at once asks the Claude login ([claudeLogin]) once, by [oneLogin].
     */
    /** The Claude login, asked once however many checks read it, for a page that checks many Processes. */
    fun oneLogin(): () -> Map<*, *> {
        val login by lazy { launcher.login() }
        return { login }
    }

    fun reasons(definition: ProcessDefinition, repos: List<String>, profile: String?, claudeLogin: () -> Map<*, *> = launcher::login): List<String> {
        val named = repos.distinct()
        val source = RunSource(profile, named)
        if (source.profile(settings) == null) return listOf(profileGone(profile))
        val vault = source.vault(settings)
        if (vault == null && named.isEmpty()) return listOf(CONFIGURE_VAULT) // the scout picks the repos from the Vault
        val processes = withCalled(definition)
        // Names, schemas and references: the deploy's Checks again, since files change after a deploy.
        val failed = processes.flatMap { process ->
            val prefix = if (process.id == definition.id) "" else "${process.name ?: process.key}: "
            checks.of(repository.getProcessModel(process.id).readAllBytes().decodeToString()).filterNot { it.warning }.map { prefix + it.message }
        }
        val agents = processes.flatMap { repository.getBpmnModelInstance(it.id).getModelElementsByType(ServiceTask::class.java) }
            .filter { it.operatonTopic == AgentLauncher.TOPIC }
            .mapNotNull { task -> runCatching { Links(task).agent(catalogue) }.getOrNull() }.distinctBy { it.name }
        // The Repos field's choices: the Profile's, which the picked names are looked up in.
        val sourceMap = source.profile(settings)?.sourceMap
        val known = source.repos(settings).associateBy { it.name }
        val created = named.filter { it !in known && newRepo(it) }
        // Each repo to clone, by its remote: a Source map name's, else the git URL itself.
        val remotes = named.filter { it !in created }.mapNotNull { known[it]?.let { repo -> repo.remote to "${repo.name}'s remote ${repo.remote}" } ?: CloneRepos.url(it)?.let { _ -> it to it } }
        // gh for every Process: any Agent with Bash, which the default tools have, can run it.
        val missing = (agents.map { it.cli } + listOfNotNull("git".takeIf { named.isNotEmpty() }) + "gh")
            .distinct().associateWith { cli -> command(cli) }.filterValues { !executable(it) }
        return buildList {
            vault?.takeIf { !it.exists() }?.let { add("The Vault ${source.profile(settings)?.vault} isn't there: $it.") }
            source.sourceMap(settings)?.takeIf { !it.exists() }?.let { add("The Source map $sourceMap isn't there: $it.") }
            addAll(failed)
            missing.forEach { (cli, command) -> add("$cli: $command isn't an executable.") }
            for (agent in agents) Agent.sandboxError(agent.cli, agent.sandbox)?.let { add("The Agent ${agent.name}: $it") }
            for (name in named) if (name !in known && name !in created && CloneRepos.url(name) == null) add(if (sourceMap == null) "$name: no Source map is configured (Settings)." else "The Source map $sourceMap has no repo $name.")
            if (agents.any { it.cli == "claude" } && "claude" !in missing) {
                val login = claudeLogin()
                val apiKey = login["apiKeySource"]?.toString()?.takeIf { it != "none" }
                if (login["loggedIn"] != true) add("${AgentLauncher.LOST_LOGIN}.")
                else if (apiKey != null) add("An API key is in use (apiKeySource: $apiKey).")
                else for (agent in agents) agent.launchLevel.model.takeIf { agent.cli == "claude" && custom(it) }
                    ?.let { model -> tryModel(model)?.let { add("The Agent ${agent.name}'s model $model doesn't work: $it") } }
            }
            val gh = "gh" !in missing && ghLoggedIn(command("gh"))
            if ("gh" !in missing && !gh) add("gh isn't logged in: run gh auth login.")
            if ("git" !in missing) for ((remote, what) in remotes) {
                val lsRemote = ProcessBuilder(command("git"), "ls-remote", "--heads", remote).apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }
                if (failure(lsRemote, 20) != null) add("git ls-remote can't reach $what.")
            }
            // ponytail: any failure of gh repo view reads as a free name, a network error too; Clone repos' gh repo create says otherwise.
            if (gh) for (repo in created) if (failure(ProcessBuilder(command("gh"), "repo", "view", repo, "--json", "name"), 20) == null) add("The repo $repo already exists on GitHub.")
        }
    }

    /**
     * Whether `gh auth status` says [gh] is logged in. It asks GitHub, about 0.4 s on every Process page, so a yes holds
     * for [GH_LOGIN] (a logout shows within it) and a no is asked again on the next check.
     */
    private fun ghLoggedIn(gh: String): Boolean {
        if (ghAsked[gh]?.let { System.nanoTime() - it < GH_LOGIN.toNanos() } == true) return true
        return (failure(ProcessBuilder(gh, "auth", "status"), 20) == null).also { if (it) ghAsked[gh] = System.nanoTime() else ghAsked -= gh }
    }

    /** [definition] and every Process it calls, at any depth, each the latest version, as a call binds it. */
    private fun withCalled(definition: ProcessDefinition): List<ProcessDefinition> {
        val found = linkedMapOf(definition.key to definition)
        val queue = ArrayDeque(listOf(definition))
        while (queue.isNotEmpty()) for (call in repository.getBpmnModelInstance(queue.removeFirst().id).getModelElementsByType(CallActivity::class.java)) {
            val key = call.calledElement?.takeIf { it !in found } ?: continue
            val called = repository.createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult() ?: continue // a Check says so
            found[key] = called
            queue += called
        }
        return found.values.toList()
    }

    /** What a launch of [cli] runs first: the command prefix's first word for an AI CLI, else the CLI's path. */
    private fun command(cli: String) = if (cli in Settings.AI_CLIS) settings.command(cli).first() else settings.path(cli) ?: cli

    /** Whether [command] runs: a path to an executable, or a name on Ants' PATH. */
    private fun executable(command: String) = if ('/' in command) Files.isExecutable(Settings.expand(command))
    else System.getenv("PATH").orEmpty().split(File.pathSeparator).any { it.isNotEmpty() && Files.isExecutable(Path.of(it, command)) }

    /** A model ID that is neither in Anthropic's table nor an alias; `[1m]` asks for the long context of a listed one. */
    private fun custom(model: String) = models.unlisted(model.substringBefore('['))

    /** Tries [model] once with a one-line prompt, the way a launch runs claude: null when it answers, else its last words. */
    private fun tryModel(model: String): String? = tried.computeIfAbsent(settings.command("claude") to model) {
        failure(launcher.cli("claude", home, listOf("-p", "--model", model, "Reply with OK.")), 60).orEmpty()
    }.ifEmpty { null }

    /** Runs [command] for at most [seconds]: null when it exits 0, else its last line of output. */
    private fun failure(command: ProcessBuilder, seconds: Long): String? = runCatching {
        val deadline = System.nanoTime() + SECONDS.toNanos(seconds)
        val process = command.redirectInput(File("/dev/null")).redirectErrorStream(true).start()
        val lines = mutableListOf<String>()
        val drain = Thread.ofVirtual().start { process.inputReader().forEachLine { synchronized(lines) { lines += it } } }
        val exited = process.waitFor(seconds, SECONDS)
        // Unlike Process.destroy, which closes stdout and so drops what the drain hasn't read yet, as on a busy machine.
        if (!exited) process.toHandle().destroyForcibly()
        val ok = exited && process.exitValue() == 0
        // The drain may lag the exit, so a failure waits for its last line until the deadline.
        if (!ok) drain.join(Duration.ofNanos(deadline - System.nanoTime()).coerceAtLeast(Duration.ofSeconds(1)))
        if (ok) null else synchronized(lines) { lines.lastOrNull(String::isNotBlank) } ?: "${command.command().first()} failed."
    }.getOrElse { it.message ?: "${command.command().first()} failed." }

    companion object {
        /** The one reason without a Vault; the Process page links it to the settings. */
        const val CONFIGURE_VAULT = "Configure a Vault"

        /** How long a logged-in gh is trusted. */
        val GH_LOGIN: Duration = Duration.ofMinutes(1)

        fun profileGone(profile: String?) = "The Profile $profile isn't in the settings."
    }
}
