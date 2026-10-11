package ants

import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.history.HistoricProcessInstance
import org.operaton.bpm.engine.runtime.Incident
import org.operaton.bpm.model.bpmn.instance.FlowElement
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit.SECONDS
import java.util.Date

/** One entry on the Notification list: a Task of a Run waiting on the user since [since]. */
class Entry(
    val id: String,
    val tag: String,
    val process: String,
    val run: String,
    val task: String,
    val taskId: String,
    val summary: String,
    val since: Date,
) {
    val icon get() = ICONS[tag] ?: "•"

    val waited: String
        get() = Duration.between(since.toInstant(), Instant.now()).let {
            when {
                it.toDays() > 0 -> "${it.toDays()} d ${it.toHoursPart()} h"
                it.toHours() > 0 -> "${it.toHours()} h ${it.toMinutesPart()} min"
                else -> "${it.toMinutes()} min"
            }
        }

    private companion object {
        val ICONS = mapOf("[failed]" to "✕", "[stuck]" to "⚠", "[stopped]" to "■", "[questions]" to "?", "[approve]" to "✓")
    }
}

/**
 * The Notification list: one query over what is waiting on the user, with no table of its own, so it can't drift.
 * For now that is every Agent Task held by an incident, its tag and summary read from the incident message.
 */
@Controller
class Notifications(
    private val runtime: RuntimeService,
    private val history: HistoryService,
    private val repository: RepositoryService,
    private val externalTasks: ExternalTaskService,
    private val launcher: AgentLauncher,
    @Value("\${ants.home}") private val home: Path,
) {
    /** Everything waiting on the user, oldest first. */
    fun entries(): List<Entry> = incidents().orderByIncidentTimestamp().asc().list().map(::entry)

    @GetMapping("/notifications")
    fun list(model: Model): String {
        model.addAttribute("entries", entries())
        return "notifications"
    }

    @GetMapping("/notifications/{id}")
    fun entry(@PathVariable id: String, model: Model): String {
        val entry = entry(incident(id))
        model.addAttribute("entry", entry)
        model.addAttribute("details", details(entry))
        return "notification"
    }

    /** What [entry]'s page shows beyond its summary, such as what a stuck Agent needs. */
    fun details(entry: Entry): String? = runtime.createIncidentQuery().incidentId(entry.id).singleResult()
        ?.let { externalTasks.getExternalTaskErrorDetails(it.configuration) }

    /**
     * Continue the session (instruction required) or Start fresh (instruction optional), then Next up, or back to
     * the Run page when the user acted from its Task drawer ([back] is `run`); or Cancel the Run, which ends it and
     * keeps its Run workspace until the user dismisses it on the Run page.
     */
    @PostMapping("/notifications/{id}")
    fun act(
        @PathVariable id: String, @RequestParam action: String, @RequestParam instruction: String, @RequestParam(required = false) back: String?, model: Model,
    ): String {
        val incident = incident(id)
        val entry = entry(incident)
        val name = when (action) {
            AgentLauncher.CONTINUE -> "Continue the session"
            AgentLauncher.FRESH -> "Start fresh"
            CANCEL -> "Cancel the Run"
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No action $action")
        }
        if (action == AgentLauncher.CONTINUE && instruction.isBlank()) {
            model.addAttribute("error", "Continue the session needs an instruction.")
            return entry(id, model)
        }
        Files.writeString(
            home.resolve("runs").resolve(entry.run).resolve("run-log.md"),
            "## ${Instant.now().truncatedTo(SECONDS)} · ${entry.task} · $name\n\n${instruction.trim()}\n\n",
            CREATE, APPEND,
        )
        if (action == CANCEL) {
            // A cancelled Run keeps its workspace; externally terminated is what its pages read as Cancelled.
            runtime.deleteProcessInstance(entry.run, "Cancelled by the user", false, true)
            launcher.cancel(entry.run)
            return "redirect:/runs/${entry.run}"
        }
        // The choice first: the worker can fetch the Task again the moment its retries are back.
        runtime.setVariablesLocal(incident.executionId, mapOf(AgentLauncher.ACTION to action, AgentLauncher.INSTRUCTION to instruction))
        externalTasks.setRetries(incident.configuration, 1)
        return if (back == "run") "redirect:/runs/${entry.run}" else "redirect:/notifications/next?run=${entry.run}"
    }

    /** Next up: the Run's next entry, if one turns up within a few seconds; else the Notification list. */
    // ponytail: holds the request, polling, for up to 5 s; push it over SSE if the wait ever needs to be longer.
    @GetMapping("/notifications/next")
    fun next(@RequestParam run: String): String {
        val deadline = Instant.now().plusSeconds(5)
        while (Instant.now() < deadline) {
            entries().firstOrNull { it.run == run }?.let { return "redirect:/notifications/${it.id}" }
            if (history.createHistoricProcessInstanceQuery().processInstanceId(run).singleResult()?.state != HistoricProcessInstance.STATE_ACTIVE) break
            Thread.sleep(200)
        }
        return "redirect:/notifications"
    }

    /** The count for the nav and the tab title, sent on connect and whenever it changes. */
    // ponytail: a virtual thread per open tab polls the count every second; the stream ends after a minute and
    // EventSource reconnects, so a closed tab's poller stops by then.
    @GetMapping("/notifications/count", produces = [TEXT_EVENT_STREAM_VALUE])
    @ResponseBody
    fun count(): SseEmitter = SseEmitter(60_000).also { emitter ->
        Thread.ofVirtual().start {
            var last = -1L
            try {
                while (!closing) {
                    val count = incidents().count()
                    if (count != last) emitter.send(count).also { last = count }
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

    /** Ends the open count streams, or a graceful shutdown would wait for them. */
    @EventListener(ContextClosedEvent::class)
    fun close() {
        closing = true
    }

    private fun incidents() = runtime.createIncidentQuery().incidentType(Incident.EXTERNAL_TASK_HANDLER_TYPE)

    private fun incident(id: String): Incident = incidents().incidentId(id).singleResult()
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Nothing is waiting on you here any more")

    private fun entry(incident: Incident): Entry {
        val run = runtime.createProcessInstanceQuery().processInstanceId(incident.processInstanceId).singleResult().rootProcessInstanceId
        val top = history.createHistoricProcessInstanceQuery().processInstanceId(run).singleResult()
        val task = repository.getBpmnModelInstance(incident.processDefinitionId).getModelElementById<FlowElement>(incident.activityId)
        val tag = Regex("""^\[[\w-]+]""").find(incident.incidentMessage.orEmpty())?.value
        return Entry(
            id = incident.id,
            tag = tag ?: "[failed]",
            process = top.processDefinitionName ?: top.processDefinitionKey,
            run = run,
            task = task?.name ?: incident.activityId,
            taskId = incident.activityId,
            summary = incident.incidentMessage.orEmpty().removePrefix(tag.orEmpty()).trim(),
            since = incident.incidentTimestamp,
        )
    }

    private companion object {
        const val CANCEL = "cancel"
    }
}
