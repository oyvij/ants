package ants

import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.TaskService
import org.operaton.bpm.engine.history.HistoricProcessInstance
import org.operaton.bpm.engine.runtime.Incident
import org.operaton.bpm.engine.task.Task
import org.operaton.bpm.model.bpmn.instance.BaseElement
import org.operaton.bpm.model.bpmn.instance.FlowElement
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
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
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** One entry on the Notification list, waiting on the user since [since]: a [TaskEntry] or a [StartEntry]. */
sealed class Entry(val id: String, val tag: String, val process: String, val task: String, val summary: String, val since: Date) {
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
        val ICONS = mapOf("[failed]" to "✕", "[stuck]" to "⚠", "[stopped]" to "■", "[questions]" to "?", "[approve]" to "✓", "[review]" to "±", "[push]" to "↑")
    }
}

/**
 * A Task of the Run [run] waiting on the user: held by an incident, or a User Task. [taskId] is the Task's key in the
 * Run ([taskKey]); the Task is the element [activity] of the process instance [instance], which is the Run's own or,
 * in a called Process, the called one's.
 */
class TaskEntry(
    id: String, tag: String, process: String, val run: String, task: String, val taskId: String, summary: String, since: Date,
    val instance: String, val activity: String,
) : Entry(id, tag, process, task, summary, since)

/** A scheduled Run of the Process [key] that couldn't start, until started now or dismissed. */
class StartEntry(val key: String, process: String, failed: FailedStart) :
    Entry("schedule-$key", "[failed]", process, "Scheduled Run", "The scheduled Run didn't start: ${failed.why}", Date.from(failed.at))

/**
 * The Notification list: one query over what is waiting on the user, with no table of its own, so it can't drift.
 * For now that is every Agent Task held by an incident, its tag and summary read from the incident message, and every
 * open User Task: an Answer a Round, or an Approve; plus failed scheduled starts and the `ready-for-human` Tickets of
 * live Runs.
 */
@Controller
class Notifications(
    private val runtime: RuntimeService,
    private val history: HistoryService,
    private val repository: RepositoryService,
    private val externalTasks: ExternalTaskService,
    private val userTasks: TaskService,
    private val launcher: AgentLauncher,
    private val push: Push,
    private val streams: Streams,
    private val schedules: Schedules,
    private val tickets: Tickets,
    private val cloneRepos: CloneRepos,
    @Value("\${ants.home}") private val home: Path,
) {
    /** Everything waiting on the user, oldest first. */
    fun entries(): List<Entry> = (tasks() + failedStarts() + tickets.waiting()).sortedBy { it.since }

    /** The Tasks waiting on the user, oldest first. */
    fun tasks(): List<TaskEntry> = (rootCauses().map(::entry) + userTasks.createTaskQuery().active().list().map(::entry)).sortedBy { it.since }

    /** The scheduled Runs that couldn't start, until started or dismissed. */
    private fun failedStarts() = schedules.all().mapNotNull { schedule -> schedule.failed?.let { StartEntry(schedule.key, schedule.process, it) } }

    @GetMapping("/notifications")
    fun list(model: Model): String {
        model.addAttribute("entries", entries())
        return "notifications"
    }

    /**
     * An entry's page. A Review changes' has two tabs ([tab]): Story, the Story set of the Task its Walkthrough field
     * names, first, and By repo; without a Walkthrough, By repo only.
     */
    @GetMapping("/notifications/{id}")
    fun entry(@PathVariable id: String, model: Model, response: HttpServletResponse, @RequestParam(required = false) tab: String? = null): String {
        tickets.waiting().firstOrNull { it.id == id }?.let { return "redirect:${it.href}" }
        val failed = failedStarts().firstOrNull { it.id == id }
        if (failed != null) {
            model.addAttribute("entry", failed)
            return "failed-start"
        }
        userTask(id)?.let { task ->
            val entry = entry(task)
            model.addAttribute("entry", entry)
            if (kind(task) == ANSWER_ROUND) {
                if (!model.containsAttribute("questions")) model.addAttribute("questions", Question.of(asked(task)))
                model.addAttribute("round", round(task))
                return "answer"
            }
            if (kind(task) == REVIEW_CHANGES) {
                val shown = when {
                    property(task, WALKTHROUGH) == null -> null // By repo, with no tabs
                    tab == "repo" -> "repo"
                    else -> "story"
                }
                val story = shown == "story"
                model.addAttribute("tab", shown)
                if (story) {
                    try {
                        model.addAttribute("stories", storySet(home.runWorkspace(entry.run), cloneRepos.untrimmed))
                    } catch (refused: StorySetRefused) {
                        model.addAttribute("refused", refused.message)
                    }
                }
                model.addAttribute("changes", if (story) emptyList() else changes(push.outgoing(entry.run), cloneRepos::git))
                model.addAttribute("comments", comments(task))
                return "review"
            }
            model.addAttribute("shown", shown(home.runWorkspace(entry.run), runtime.getVariableLocal(task.executionId, SHOW)))
            return "approve"
        }
        val incident = incidents().incidentId(id).singleResult() ?: return gone(model, response)
        val entry = entry(incident)
        model.addAttribute("entry", entry)
        model.addAttribute("details", details(entry))
        if (isPush(incident)) {
            model.addAttribute("repos", push.outgoing(entry.run))
            return "push"
        }
        return "notification"
    }

    /** What [entry]'s page shows beyond its summary, such as what a stuck Agent needs. */
    fun details(entry: TaskEntry): String? = runtime.createIncidentQuery().incidentId(entry.id).singleResult()
        ?.let { externalTasks.getExternalTaskErrorDetails(it.configuration) }

    /** Whether [entry] is a Push waiting or failed, whose page is Push's own. */
    fun isPush(entry: TaskEntry) = runtime.createIncidentQuery().incidentId(entry.id).singleResult()?.let(::isPush) == true

    private fun isPush(incident: Incident) = externalTasks.createExternalTaskQuery().externalTaskId(incident.configuration).topicName(Push.TOPIC).count() > 0

    /**
     * Continue the session (instruction required) or Start fresh (instruction optional), then Next up, or back to
     * the Run page when the user acted from its Task drawer ([back] is `run`); or Cancel the Run, which ends it and
     * keeps its Run workspace until the user dismisses it on the Run page. A Push offers Push, and Continue (no
     * instruction) after the user pushed by hand, instead.
     */
    @PostMapping("/notifications/{id}")
    fun act(
        @PathVariable id: String, @RequestParam action: String, @RequestParam instruction: String, @RequestParam(required = false) back: String?, model: Model,
        response: HttpServletResponse,
    ): String {
        val incident = incidents().incidentId(id).singleResult() ?: return gone(model, response)
        val entry = entry(incident)
        val pushing = isPush(incident)
        val name = when {
            // Continue on a Push completes it with what the remotes hold, so only after a failed Push: there is no Skip.
            action == AgentLauncher.CONTINUE && pushing && entry.tag != "[failed]" -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A waiting Push has no Continue")
            action == AgentLauncher.CONTINUE -> if (pushing) "Continue" else "Continue the session"
            action == AgentLauncher.FRESH && !pushing -> "Start fresh"
            action == Push.PUSH && pushing -> "Push"
            action == CANCEL -> "Cancel the Run"
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No action $action")
        }
        if (action == AgentLauncher.CONTINUE && instruction.isBlank() && !pushing) {
            model.addAttribute("error", "Continue the session needs an instruction.")
            return entry(id, model, response)
        }
        runLog(home.runWorkspace(entry.run), entry.task, name, instruction)
        if (action == CANCEL) {
            // A cancelled Run keeps its workspace; externally terminated is what its pages read as Cancelled.
            runtime.deleteProcessInstance(entry.run, "Cancelled by the user", false, true)
            launcher.cancel(entry.run)
            return "redirect:/runs/${entry.run}"
        }
        // The choice first: the worker can fetch the Task again the moment its retries are back.
        runtime.setVariablesLocal(incident.executionId, mapOf(AgentLauncher.ACTION to action, AgentLauncher.INSTRUCTION to instruction))
        externalTasks.setRetries(incident.configuration, 1)
        return back(back, entry.run, "/notifications/next?run=${entry.run}")
    }

    /**
     * Approve, which completes the Approve with `{approved: true, comment}`, or Send back (comment required), which
     * completes it with `{approved: false, comment}` and leaves the comment for the Task its `ants.send-back` names,
     * whose next launch resumes its session with it; then Next up, as for any entry. A Review changes completes the
     * same way with its line comments too, `{approved, comment, comments[]}`; its Send back needs a comment or a line
     * comment and sends both, and its Approve sets every `in-review` Ticket `done`.
     */
    @PostMapping("/notifications/{id}", params = ["comment"])
    fun approve(@PathVariable id: String, @RequestParam action: String, @RequestParam comment: String, @RequestParam(required = false) back: String?, model: Model, response: HttpServletResponse): String {
        val task = userTask(id)?.takeIf { kind(it) == null || kind(it) == REVIEW_CHANGES } ?: return gone(model, response)
        val comments = if (kind(task) == REVIEW_CHANGES) comments(task) else null
        val entry = entry(task)
        val approved = when (action) {
            APPROVE -> true
            SEND_BACK -> false
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No action $action")
        }
        if (!approved && comment.isBlank() && comments.isNullOrEmpty()) {
            model.addAttribute("error", if (comments == null) "Send back needs a comment." else "Send back needs a comment, on the changes or on a line.")
            return entry(id, model, response)
        }
        val workspace = home.runWorkspace(entry.run)
        val message = (listOf(comment.trim()) + comments.orEmpty().joinToString("\n") { it.summary }).filter(String::isNotEmpty).joinToString("\n\n")
        runLog(workspace, entry.task, if (approved) "Approve" else "Send back", message)
        val result = mapOf("approved" to approved, "comment" to comment) + (comments?.let { mapOf("comments" to ArrayList(it.map(LineComment::toMap))) } ?: emptyMap())
        complete(task, workspace, result, property(task, SEND_BACK_PROPERTY)?.takeIf { !approved }, message)
        if (approved && comments != null) ants.tickets(workspace, entry.run).filter { it.status == "in-review" }.forEach { it.status("done") }
        return back(back, entry.run, "/notifications/next?run=${entry.run}")
    }

    /**
     * Adds a line comment to a Review changes, kept on its User Task until it completes, or takes out the [remove]th; one
     * on a story step names its [story] and [step], 1-based. Back to the [tab] it was made on.
     */
    @PostMapping("/notifications/{id}/comments")
    fun comment(
        @PathVariable id: String, @RequestParam(required = false) remove: Int?, @RequestParam(required = false) story: String?,
        @RequestParam(required = false) step: Int?, @RequestParam(required = false) tab: String?, @RequestParam(required = false) repo: String?,
        @RequestParam(required = false) file: String?, @RequestParam(required = false) side: String?, @RequestParam(required = false) from: Int?,
        @RequestParam(required = false) to: Int?, @RequestParam(required = false) type: String?, @RequestParam(required = false) body: String?,
        model: Model, response: HttpServletResponse,
    ): String {
        val task = userTask(id)?.takeIf { kind(it) == REVIEW_CHANGES } ?: return gone(model, response)
        val comments = comments(task).toMutableList()
        if (remove != null) comments.removeAt(remove)
        else {
            if (repo.isNullOrBlank() || file.isNullOrBlank() || side !in setOf("old", "new") || from == null || type !in LineComment.TYPES) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A line comment needs a repo, a file, a side, its lines and a type")
            }
            if (body.isNullOrBlank()) {
                model.addAttribute("error", "A line comment needs a body.")
                return entry(id, model, response, tab)
            }
            val last = to ?: from
            comments += LineComment(story?.takeIf { step != null }, step?.takeIf { story != null }, repo, file, side!!, minOf(from, last), maxOf(from, last), type!!, body)
        }
        userTasks.setVariableLocal(task.id, COMMENTS, JsonMapper.shared().writeValueAsString(comments.map(LineComment::toMap)))
        return "redirect:/notifications/$id" + if (tab in setOf("story", "repo")) "?tab=$tab" else ""
    }

    /** A Review changes' line comments so far. */
    private fun comments(task: Task): List<LineComment> =
        (userTasks.getVariableLocal(task.id, COMMENTS) as? String)?.let { JsonMapper.shared().readValue(it, List::class.java) }.orEmpty().map {
            val c = it as Map<*, *>
            LineComment(
                c["story"] as? String, (c["step"] as? Number)?.toInt(), c["repo"].toString(), c["file"].toString(), c["side"].toString(),
                (c["from"] as Number).toInt(), (c["to"] as Number).toInt(), c["type"].toString(), c["body"].toString(),
            )
        }

    /**
     * Sends a Round: each question's answer (`answer.<id>`, every one required), stamped by Ants ([answers]), completes
     * the Answer a Round and resumes its asker's session with them; the Round goes to the Run log and the user's
     * answers to the Answer History. Then Next up, as for any entry. A Round already gone, as when its timer boundary
     * fired, says so.
     */
    @PostMapping("/notifications/{id}/answers")
    fun answer(@PathVariable id: String, @RequestParam form: Map<String, String>, model: Model, response: HttpServletResponse): String {
        val task = userTask(id)?.takeIf { kind(it) == ANSWER_ROUND } ?: return gone(model, response)
        val entry = entry(task)
        val questions = Question.of(asked(task), form.filterKeys { it.startsWith("answer.") }.mapKeys { it.key.removePrefix("answer.") })
        if (questions.any { it.answer.isBlank() }) {
            model.addAttribute("error", "Every question needs an answer.")
            model.addAttribute("questions", questions)
            return entry(id, model, response)
        }
        val answers = answers(questions)
        val workspace = home.runWorkspace(entry.run)
        val round = round(task)
        runLog(workspace, entry.task, "Round $round", roundLog(questions, answers))
        answerHistory(home, entry.process, entry.run, entry.task, entry.taskId, round, questions, answers)
        complete(task, workspace, answers, property(task, ASKER), JsonMapper.shared().writeValueAsString(answers))
        return "redirect:/notifications/next?run=${entry.run}"
    }

    /**
     * Completes the User Task [task] of the Run with the workspace [workspace] with its [result], first leaving
     * [message] for the Task [resumes] names, if any, whose next launch resumes its session with it.
     */
    private fun complete(task: Task, workspace: Path, result: Map<String, Any>, resumes: String?, message: String) {
        // Before completing: the named Task can launch the moment the completion commits.
        val sent = resumes?.let { AgentLauncher.sentBack(workspace, runtime.taskKey(task.executionId, it)) }
            ?.also { it.parent.createDirectories(); it.writeText(message) }
        try {
            onClash { userTasks.complete(task.id, mapOf(task.taskDefinitionKey to result)) }
        } catch (e: Exception) {
            sent?.deleteIfExists()
            throw e
        }
    }

    /** The Notification list, saying the entry the user acted on is no longer waiting, as when a timer boundary took it. */
    private fun gone(model: Model, response: HttpServletResponse): String {
        response.status = HttpStatus.NOT_FOUND.value()
        model.addAttribute("error", "Nothing is waiting on you here any more: it was done, or its timer took it away. What you sent wasn't used.")
        return list(model)
    }

    private fun property(task: Task, name: String) =
        repository.getBpmnModelInstance(task.processDefinitionId).getModelElementById<BaseElement>(task.taskDefinitionKey).property(name)

    /** A User Task's kind: [ANSWER_ROUND] or [REVIEW_CHANGES], or without [USER_TASK] an Approve. */
    private fun kind(task: Task) = property(task, USER_TASK)

    /** The Result of the Agent Task an Answer a Round names as its asker, holding the Round's questions. */
    private fun asked(task: Task) = property(task, ASKER)?.let { runtime.getVariable(task.executionId, it) }

    /** Which Round [task] answers: its asker's, whoever answered the earlier ones. */
    private fun round(task: Task) = runtime.round(task.executionId, property(task, ASKER) ?: task.taskDefinitionKey)

    /** Next up: the Run's next entry, if one turns up within a few seconds; else the Notification list. */
    // ponytail: holds the request, polling, for up to 5 s; push it over SSE if the wait ever needs to be longer.
    @GetMapping("/notifications/next")
    fun next(@RequestParam run: String): String {
        val deadline = Instant.now().plusSeconds(5)
        while (Instant.now() < deadline) {
            tasks().firstOrNull { it.run == run }?.let { return "redirect:/notifications/${it.id}" }
            if (history.createHistoricProcessInstanceQuery().processInstanceId(run).singleResult()?.state != HistoricProcessInstance.STATE_ACTIVE) break
            Thread.sleep(200)
        }
        return "redirect:/notifications"
    }

    /** The count for the nav and the tab title, sent on connect and whenever it changes. */
    @GetMapping("/notifications/count", produces = [TEXT_EVENT_STREAM_VALUE])
    @ResponseBody
    fun count(): SseEmitter = streams.changes { entries().size.toString() }

    private fun incidents() = runtime.createIncidentQuery().incidentType(Incident.EXTERNAL_TASK_HANDLER_TYPE)

    /**
     * The incidents on Tasks, oldest first. An incident in a called Process also puts one on each call activity above
     * it, the same Task waiting, so only the root cause counts.
     */
    private fun rootCauses() = incidents().orderByIncidentTimestamp().asc().list().filter { it.id == it.rootCauseIncidentId }


    private fun userTask(id: String): Task? = userTasks.createTaskQuery().taskId(id).active().singleResult()

    private fun entry(incident: Incident): TaskEntry {
        val tag = Regex("""^\[[\w-]+]""").find(incident.incidentMessage.orEmpty())?.value
        val summary = incident.incidentMessage.orEmpty().removePrefix(tag.orEmpty()).trim()
        return entry(incident.id, tag ?: "[failed]", summary, incident.incidentTimestamp, incident.processInstanceId, incident.processDefinitionId, incident.executionId, incident.activityId)
    }

    /** A User Task's entry: an Answer a Round's summary is its asker's, an Approve's is its Title. */
    private fun entry(task: Task): TaskEntry {
        val (tag, summary) = when (kind(task)) {
            ANSWER_ROUND -> "[questions]" to (asked(task) as? Map<*, *>)?.get("summary")
            REVIEW_CHANGES -> "[review]" to "Review the changes on the work branch."
            else -> "[approve]" to runtime.getVariableLocal(task.executionId, TITLE)
        }
        return entry(task.id, tag, summary?.toString().orEmpty(), task.createTime, task.processInstanceId, task.processDefinitionId, task.executionId, task.taskDefinitionKey)
    }

    private fun entry(id: String, tag: String, summary: String, since: Date, instance: String, definition: String, execution: String, activity: String): TaskEntry {
        val run = runtime.createProcessInstanceQuery().processInstanceId(instance).singleResult().rootProcessInstanceId
        val top = history.createHistoricProcessInstanceQuery().processInstanceId(run).singleResult()
        val task = repository.getBpmnModelInstance(definition).getModelElementById<FlowElement>(activity)
        return TaskEntry(
            id = id,
            tag = tag,
            process = top.processDefinitionName ?: top.processDefinitionKey,
            run = run,
            task = task?.name ?: activity,
            taskId = runtime.taskKey(execution, activity),
            summary = summary,
            since = since,
            instance = instance,
            activity = activity,
        )
    }

    private companion object {
        const val CANCEL = "cancel"
        const val APPROVE = "approve"
        const val SEND_BACK = "send-back"

        /** An Approve's fields: its Title and Show are input parameters, so `${…}` in them works and Checks read them. */
        const val TITLE = "title"
        const val SHOW = "show"
        const val SEND_BACK_PROPERTY = "ants.send-back"

        /** A User Task's kind ([ANSWER_ROUND]; without it, an Approve). */
        const val USER_TASK = "ants.user-task"
        const val ANSWER_ROUND = "answer-round"
        const val REVIEW_CHANGES = "review-changes"

        /** A Review changes' line comments so far, as JSON, local to its User Task. */
        const val COMMENTS = "comments"
    }
}

/**
 * What an Approve shows, as HTML: the file in [workspace] that [show] names, else [show] itself, rendered as markdown;
 * a Result that isn't text shows as JSON.
 */
fun shown(workspace: Path, show: Any?): String {
    val text = show as? String ?: show?.let { "```json\n" + JsonMapper.shared().writerWithDefaultPrettyPrinter().writeValueAsString(it) + "\n```" }.orEmpty()
    return Transcript.markdown(inWorkspace(workspace, text)?.readText() ?: text)
}

/** The regular file at the relative [path] inside [workspace], symlinks followed, or null when there is none. */
private fun inWorkspace(workspace: Path, path: String): Path? = runCatching {
    val root = workspace.toRealPath()
    root.resolve(Path.of(path)).takeIf { path.isNotBlank() && !Path.of(path).isAbsolute && it.isRegularFile() }?.toRealPath()?.takeIf { it.startsWith(root) }
}.getOrNull()
