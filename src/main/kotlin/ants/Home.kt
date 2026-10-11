package ants

import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.model.bpmn.instance.FlowElement
import org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.net.URI
import java.time.Clock

/** An Agent Task running now, in a Run of a Process. */
class Working(val process: String, val run: String, val task: String)

/** Home: stats, Waiting on you, Working now, Coming up, all live, and Start a Run. */
@Controller
class Home(
    private val notifications: Notifications,
    private val runtime: RuntimeService,
    private val history: HistoryService,
    private val repository: RepositoryService,
    private val externalTasks: ExternalTaskService,
    private val templates: TemplateEngine,
    private val launcher: AgentLauncher,
    private val clock: Clock,
    private val streams: Streams,
    private val schedules: Schedules,
) {
    @GetMapping("/")
    fun home(model: Model): String {
        model.addAllAttributes(dashboard())
        model.addAttribute("processes", repository.createProcessDefinitionQuery().latestVersion().orderByProcessDefinitionName().asc().list())
        return "home"
    }

    /** **Try now**: retries a CLI the Usage limit paused, before its reset, and goes back to the page it was on. */
    @PostMapping("/pauses/{cli}")
    fun tryNow(@PathVariable cli: String, @RequestHeader("Referer", required = false) from: String?): String {
        launcher.tryNow(cli)
        return "redirect:" + (from?.let { runCatching { URI(it).path }.getOrNull() }?.ifEmpty { null } ?: "/")
    }

    /** The live part of Home as HTML, sent on connect and whenever it changes. */
    @GetMapping("/home/live", produces = [TEXT_EVENT_STREAM_VALUE])
    @ResponseBody
    fun live(): SseEmitter = stream("dashboard.kte") { dashboard() }

    /** The nav's pill as HTML, "claude paused until 15:45" with **Try now** per paused CLI, or nothing. */
    @GetMapping("/pauses", produces = [TEXT_EVENT_STREAM_VALUE])
    @ResponseBody
    fun pauses(csrf: CsrfToken): SseEmitter {
        val token = DefaultCsrfToken(csrf.headerName, csrf.parameterName, csrf.token) // read now, while the request lives
        return stream("pill.kte") { mapOf("pauses" to launcher.pauses().mapValues { clock.local(it.value) }, "csrf" to token) }
    }

    /** Streams [template] rendered with [params], sent on connect and whenever it changes. */
    private fun stream(template: String, params: () -> Map<String, Any>) =
        streams.changes { StringOutput().also { templates.render(template, params(), it) }.toString().trim() }

    private fun dashboard(): Map<String, Any> {
        val entries = notifications.entries()
        return mapOf(
            "live" to history.createHistoricProcessInstanceQuery().rootProcessInstances().active().count(),
            "waiting" to entries.filter { it.run.isNotEmpty() }.distinctBy { it.run }.size, // a scheduled Run that didn't start isn't a Run
            "done" to history.createHistoricProcessInstanceQuery().rootProcessInstances().completed().count(),
            "entries" to entries,
            "working" to working(),
            "coming" to schedules.all().filter { it.next != null },
        )
    }

    /** A locked Agent Task with retries left is running, unless the Usage limit pause holds it locked until the reset. */
    private fun working() = externalTasks.createExternalTaskQuery().topicName(AgentLauncher.TOPIC).locked().withRetriesLeft().list().mapNotNull { task ->
        if (launcher.pausedUntil(task.id) != null) return@mapNotNull null
        // A Task that has just finished may have taken its Run with it.
        val run = runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult()?.rootProcessInstanceId ?: return@mapNotNull null
        val top = history.createHistoricProcessInstanceQuery().processInstanceId(run).singleResult() ?: return@mapNotNull null
        val element = repository.getBpmnModelInstance(task.processDefinitionId).getModelElementById<FlowElement>(task.activityId)
        Working(top.processDefinitionName ?: top.processDefinitionKey, run, element?.name ?: task.activityId)
    }
}
