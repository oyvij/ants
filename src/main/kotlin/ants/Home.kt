package ants

import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.model.bpmn.instance.FlowElement
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/** An Agent Task running now, in a Run of a Process. */
class Working(val process: String, val run: String, val task: String)

/** Home: stats, Waiting on you, Working now, all live, and Start a Run. */
@Controller
class Home(
    private val notifications: Notifications,
    private val runtime: RuntimeService,
    private val history: HistoryService,
    private val repository: RepositoryService,
    private val externalTasks: ExternalTaskService,
    private val templates: TemplateEngine,
) {
    @GetMapping("/")
    fun home(model: Model): String {
        model.addAllAttributes(dashboard())
        model.addAttribute("processes", repository.createProcessDefinitionQuery().latestVersion().orderByProcessDefinitionName().asc().list())
        return "home"
    }

    /** The live part of Home as HTML, sent on connect and whenever it changes. */
    // ponytail: polls every second per open tab, like the Notification list count; the stream ends after a minute and
    // EventSource reconnects.
    @GetMapping("/home/live", produces = [TEXT_EVENT_STREAM_VALUE])
    @ResponseBody
    fun live(): SseEmitter = SseEmitter(60_000).also { emitter ->
        Thread.ofVirtual().start {
            var last = ""
            try {
                while (!closing) {
                    val html = StringOutput().also { templates.render("dashboard.kte", dashboard(), it) }.toString()
                        .replace('\n', ' ') // one SSE data line
                    if (html != last) emitter.send(html).also { last = html }
                    Thread.sleep(1000)
                }
                emitter.complete()
            } catch (_: Exception) {
                // the tab went away or the stream timed out
            }
        }
    }

    @Volatile
    private var closing = false

    /** Ends the open streams, or a graceful shutdown would wait for them. */
    @EventListener(ContextClosedEvent::class)
    fun close() {
        closing = true
    }

    private fun dashboard(): Map<String, Any> {
        val entries = notifications.entries()
        return mapOf(
            "live" to history.createHistoricProcessInstanceQuery().rootProcessInstances().active().count(),
            "waiting" to entries.distinctBy { it.run }.size,
            "done" to history.createHistoricProcessInstanceQuery().rootProcessInstances().completed().count(),
            "entries" to entries,
            "working" to working(),
        )
    }

    // ponytail: a locked Agent Task with retries left is running; once the usage-limit pause holds tasks locked until
    // the reset, it must tell paused ones apart.
    private fun working() = externalTasks.createExternalTaskQuery().topicName(AgentLauncher.TOPIC).locked().withRetriesLeft().list().mapNotNull { task ->
        // A Task that has just finished may have taken its Run with it.
        val run = runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult()?.rootProcessInstanceId ?: return@mapNotNull null
        val top = history.createHistoricProcessInstanceQuery().processInstanceId(run).singleResult() ?: return@mapNotNull null
        val element = repository.getBpmnModelInstance(task.processDefinitionId).getModelElementById<FlowElement>(task.activityId)
        Working(top.processDefinitionName ?: top.processDefinitionKey, run, element?.name ?: task.activityId)
    }
}
