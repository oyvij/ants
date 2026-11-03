package ants

import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.repository.ProcessDefinition
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.security.web.csrf.CsrfToken
import java.net.URI
import java.time.Clock
import java.time.LocalTime
import java.util.concurrent.CompletableFuture

/** Redirects back to the page a nav button was pressed on, as its Referer names it, else Home. */
fun back(from: String?) = "redirect:" + (from?.let { runCatching { URI(it).path }.getOrNull() }?.ifEmpty { null } ?: "/")

/**
 * Home: a greeting, the stats, Waiting on you, Working now and Coming up, all live, and Start a Run, where a Process
 * that can't start says so once its pre-Run check, streamed in by [ready], is done.
 */
@Controller
class Home(
    private val notifications: Notifications,
    private val history: HistoryService,
    private val repository: RepositoryService,
    private val launcher: AgentLauncher,
    private val clock: Clock,
    private val streams: Streams,
    private val schedules: Schedules,
    private val runs: Runs,
    private val settings: Settings,
    private val startForm: StartForm,
) {
    @GetMapping("/")
    fun home(model: Model): String {
        model.addAllAttributes(dashboard())
        val hour = LocalTime.now(clock).hour
        model.addAttribute("greeting", if (hour < 12) "Good morning" else if (hour < 18) "Good afternoon" else "Good evening")
        model.addAttribute("processes", listed())
        return "home"
    }

    /** **Try now**: retries a CLI the Usage limit paused, before its reset, and goes back to the page it was on. */
    @PostMapping("/pauses/{cli}")
    fun tryNow(@PathVariable cli: String, @RequestHeader("Referer", required = false) from: String?): String {
        launcher.tryNow(cli)
        return back(from)
    }

    /** The live part of Home as HTML, sent on connect and whenever it changes. */
    fun live(): Part = streams.rendered("home", "dashboard.kte") { dashboard() }

    /**
     * The Ready mark of each listed Process as HTML, Home's, or the Processes list's for [list], sent once their pre-Run
     * checks are all done: each can ask git, gh and claude, so the page shows first with a spinner in each mark.
     */
    // ponytail: checked again on each stream reconnect (a minute); cache the marks if the remotes mind.
    fun ready(list: Boolean): Part {
        val marks = CompletableFuture.supplyAsync({ startForm.ready(listed()) }) { Thread.ofVirtual().start(it) }
        val rendered = streams.rendered("ready", "readymarks.kte") { mapOf("marks" to marks.get(), "list" to list) }
        return Part("ready") { if (marks.isDone) rendered.changed() else emptyList() }
    }

    /** The Processes Home and the Processes list show, by name. */
    fun listed(): List<ProcessDefinition> =
        repository.createProcessDefinitionQuery().latestVersion().orderByProcessDefinitionName().asc().list().filterNot(settings::hides)

    /** The nav's pill as HTML, "claude paused until 15:45" with **Try now** per paused CLI, or nothing. */
    fun pauses(csrf: CsrfToken): Part =
        streams.rendered("pauses", "pill.kte", csrf) { mapOf("pauses" to launcher.pauses().mapValues { clock.local(it.value) }) }

    private fun dashboard(): Map<String, Any> = mapOf(
        "entries" to notifications.entries(),
        "running" to launcher.running,
        "cap" to settings.agentTasks(),
        "live" to history.liveRuns(),
        "ended" to history.createHistoricProcessInstanceQuery().rootProcessInstances().finished().count(),
        "working" to runs.now().filter { it.running.isNotEmpty() || it.quiet.isNotEmpty() },
        "coming" to schedules.all().filter { it.next != null },
    )
}
