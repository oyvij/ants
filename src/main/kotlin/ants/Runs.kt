package ants

import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.ExecutionListener
import org.operaton.bpm.engine.externaltask.ExternalTask
import org.operaton.bpm.engine.history.HistoricProcessInstance
import org.operaton.bpm.engine.impl.bpmn.behavior.MultiInstanceActivityBehavior
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl
import org.operaton.bpm.engine.impl.cfg.TransactionState
import org.operaton.bpm.engine.impl.context.Context
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl
import org.operaton.bpm.engine.impl.util.xml.Element
import org.operaton.bpm.engine.variable.value.ObjectValue
import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import org.operaton.bpm.model.bpmn.instance.FlowElement
import org.springframework.security.web.csrf.CsrfToken
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.json.JsonMapper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit.SECONDS
import java.util.Date
import kotlin.io.path.createDirectories

/** What the live diagram shows of one element: its state (done, running, queued or waiting), a badge and its Round. */
class Shown(val state: String, val badge: String?, val round: Int?)

@Controller
class Runs(
    private val history: HistoryService,
    private val runtime: RuntimeService,
    private val repository: RepositoryService,
    private val externalTasks: ExternalTaskService,
    private val notifications: Notifications,
    private val launcher: AgentLauncher,
    private val settings: Settings,
    private val clock: Clock,
    private val templates: TemplateEngine,
    private val streams: Streams,
    @Value("\${ants.home}") private val home: Path,
) {
    @GetMapping("/runs")
    fun list(model: Model): String {
        model.addAttribute("runs", history.createHistoricProcessInstanceQuery().rootProcessInstances().orderByProcessInstanceStartTime().desc().list())
        return "runs"
    }

    @GetMapping("/runs/{id}")
    fun run(@PathVariable id: String, model: Model): String {
        val run = run(id)
        model.addAttribute("run", run)
        model.addAttribute("kept", keptWorkspace(run)?.toString())
        model.addAttribute(
            "variables",
            history.createHistoricVariableInstanceQuery().processInstanceId(id).disableCustomObjectDeserialization()
                .orderByVariableName().asc().list()
                .filter { it.executionId == id } // the Run's own, not an Agent Task's inputs or a multi-instance's own Results
                .associate { it.name to ((it.typedValue as? ObjectValue)?.valueSerialized ?: "${it.value}") },
        )
        model.addAttribute("transcripts", Transcript.all(home.runWorkspace(id)).mapValues { it.value.tokens })
        model.addAttribute(
            "agentTasks",
            externalTasks.createExternalTaskQuery().processInstanceId(id).topicName(AgentLauncher.TOPIC).list().associate { it.activityId to state(it) },
        )
        return "run"
    }

    /** The diagram of the Process version the Run runs. */
    @GetMapping("/runs/{id}/diagram.bpmn", produces = ["application/xml"])
    @ResponseBody
    fun diagram(@PathVariable id: String): ByteArray = repository.getProcessModel(run(id).processDefinitionId).readAllBytes()

    /** The Run's state and each element's [Shown], as JSON, sent on connect and whenever it changes. */
    @GetMapping("/runs/{id}/live", produces = [TEXT_EVENT_STREAM_VALUE])
    @ResponseBody
    fun live(@PathVariable id: String): SseEmitter {
        run(id)
        return streams.changes { json.writeValueAsString(mapOf("state" to run(id).stateName, "elements" to shown(id))) }
    }

    /**
     * The drawer of the Run's Task [task] as HTML, sent on connect and whenever it changes: its state, So far, the
     * Agent's checklist, latest output, and the comment box and action when it waits on the user.
     */
    @GetMapping("/runs/{id}/tasks/{task}/drawer", produces = [TEXT_EVENT_STREAM_VALUE])
    @ResponseBody
    fun drawer(@PathVariable id: String, @PathVariable task: String, csrf: CsrfToken): SseEmitter {
        val element = repository.getBpmnModelInstance(run(id).processDefinitionId).getModelElementById<FlowElement>(task)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Task $task")
        val file = Transcript.file(home.runWorkspace(id), task)
        val transcript = Transcript()
        var offset = 0L
        csrf.token // loads the token while the request is still here
        return streams.changes {
            val (lines, end) = Transcript.read(file, offset)
            offset = end
            lines.forEach(transcript::add)
            val entry = notifications.entries().firstOrNull { it.run == id && it.taskId == task }
            val model = mapOf(
                "run" to id, "task" to task, "name" to (element.name ?: task), "shown" to shown(id)[task], "transcript" to transcript,
                "entry" to entry, "details" to entry?.let(notifications::details), "live" to launcher.live(id, task), "_csrf" to csrf,
            )
            StringOutput().also { templates.render("drawer.kte", model, it) }.toString()
        }
    }

    /**
     * Each element the Run has reached: done once every instance of it ended, else waiting when it has a Notification
     * list entry, else queued with a badge while a usage limit pauses its Agent Task, else running, or queued for an
     * Agent Task no worker holds yet. Its Round is its count in `runs`.
     */
    private fun shown(id: String): Map<String, Shown> {
        val entries = notifications.entries().filter { it.run == id }.associateBy { it.taskId }
        val agentTasks = externalTasks.createExternalTaskQuery().processInstanceId(id).topicName(AgentLauncher.TOPIC).list().groupBy { it.activityId }
        val rounds = history.createHistoricVariableInstanceQuery().processInstanceId(id).variableName("runs").singleResult()?.value as? Map<*, *>
        return history.createHistoricActivityInstanceQuery().processInstanceId(id).list().groupBy { it.activityId }.mapValues { (activity, instances) ->
            val round = (rounds?.get(activity) as? Number)?.toInt()
            val entry = entries[activity]
            val tasks = agentTasks[activity].orEmpty()
            val paused = tasks.firstNotNullOfOrNull { launcher.pausedUntil(it.id) }
            when {
                instances.all { it.endTime != null } -> Shown("done", null, round)
                entry != null -> Shown("waiting", "${entry.icon} ${entry.tag}", round)
                paused != null -> Shown("queued", "Paused until ${clock.local(paused)}", round)
                tasks.isNotEmpty() && tasks.none { it.lockExpirationTime?.after(Date()) == true } -> Shown("queued", "Queued", round)
                else -> Shown("running", null, round)
            }
        }
    }

    /** Dismiss: cleans up the Run workspace a cancelled Run kept, as if the Run had ended. */
    @PostMapping("/runs/{id}/dismiss")
    fun dismiss(@PathVariable id: String): String {
        keptWorkspace(run(id))?.let { cleanUp(it.toFile()) }
        return "redirect:/runs/$id"
    }

    private fun run(id: String) = history.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult()
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Run $id")

    /** The workspace of a cancelled Run, until it is dismissed. */
    private fun keptWorkspace(run: HistoricProcessInstance) = home.runWorkspace(run.id).takeIf { workspace ->
        run.state == HistoricProcessInstance.STATE_EXTERNALLY_TERMINATED && workspace.toFile().listFiles()?.any { it.name != "tasks" } == true
    }

    private fun state(task: ExternalTask, paused: Instant? = launcher.pausedUntil(task.id)) = when {
        task.retries == 0 -> listOfNotNull(task.errorMessage, externalTasks.getExternalTaskErrorDetails(task.id)).joinToString(" ")
        paused != null -> "Paused until ${clock.local(paused)}"
        task.lockExpirationTime?.after(Date()) == true -> "Running"
        else -> "Queued: ${launcher.running} of ${settings.agentTasks()} Agent Tasks running"
    }
}

private val json = JsonMapper.shared()

/** The Run workspace of [run] in this ANTS_HOME: `runs/<run>/`. */
fun Path.runWorkspace(run: String): Path = resolve("runs").resolve(run)

/** Back to the Run page of [run] when the user acted from its Task drawer ([back] is `run`), else to [otherwise]. */
fun back(back: String?, run: String, otherwise: String) = "redirect:" + if (back == "run") "/runs/$run" else otherwise

/** Appends one entry to the Run log, `run-log.md` in the Run workspace [workspace]: [what] the user did on [task], and [text]. */
fun runLog(workspace: Path, task: String, what: String, text: String) {
    Files.writeString(workspace.resolve("run-log.md"), "## ${Instant.now().truncatedTo(SECONDS)} · $task · $what\n\n${text.trim()}\n\n", CREATE, APPEND)
}

/** How a Run's state reads on its pages. */
val HistoricProcessInstance.stateName
    get() = when (state) {
        HistoricProcessInstance.STATE_ACTIVE -> "Running"
        HistoricProcessInstance.STATE_EXTERNALLY_TERMINATED -> "Cancelled"
        else -> state.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
    }

/**
 * Gives each Run its Run workspace, `runs/<run>/` in ANTS_HOME, for as long as it lives. At Run start every
 * Task-id variable is `null`, so forward references read `null`, and `runs` counts each Task's runs. A multi-instance
 * collects its instances' Results into one list ([collect]).
 */
@Component
class RunLifecycle(@Value("\${ants.home}") private val home: Path) : AbstractProcessEnginePlugin() {
    private fun started(tasks: List<String>) = ExecutionListener { run ->
        if (run.isTopRun) home.runWorkspace(run.processInstanceId).createDirectories()
        for (task in tasks) run.setVariable(task, null)
        run.setVariable("runs", tasks.associateWith { 0 })
    }

    // ponytail: parallel Tasks starting in separate transactions clash on `runs` (optimistic locking) and the losing
    // transaction rolls back; whoever drives it (the worker, a User Task's form) must retry once Tasks run in parallel.
    private val counted = ExecutionListener { task ->
        @Suppress("UNCHECKED_CAST")
        val counts = task.getVariable("runs") as Map<String, Int>
        task.setVariable("runs", counts + (task.currentActivityId to (counts[task.currentActivityId] ?: 0) + 1))
    }

    private val ended = ExecutionListener { run ->
        // A cancelled Run keeps its workspace, so no commits are lost.
        if (!run.isTopRun || run.isCanceled) return@ExecutionListener
        val workspace = home.runWorkspace(run.processInstanceId).toFile()
        // Only once the end is committed: a rolled-back end leaves the Run alive and needing its workspace.
        Context.getCommandContext().transactionContext.addTransactionListener(TransactionState.COMMITTED) { cleanUp(workspace) }
    }

    /**
     * Collects the Results of [body]'s instances into one list, in instance order, under the id of the activity it
     * repeats. Each instance keeps the Results it makes as its own local variables, so `${id}` inside it reads its own;
     * as it ends, they go into the list in the same transaction, so two instances ending at once clash (optimistic
     * locking) instead of losing one. A Task (or call activity) gives its Result, a sub-process `{taskId: Result}`.
     * Each instance reads its item as `${item}` unless the loop names its element variable. Returns the activity's id.
     */
    private fun collect(body: ActivityImpl): String {
        val loop = body.activityBehavior as MultiInstanceActivityBehavior
        if (loop.collectionElementVariable == null) loop.collectionElementVariable = "item"
        val inner = body.activities.single()
        val subProcess = inner.getProperty("type") == "subProcess"
        val ids = if (subProcess) inner.tasks().map { it.id } else listOf(inner.id)
        inner.addBuiltInListener(ExecutionListener.EVENTNAME_START, ExecutionListener { instance -> ids.forEach { instance.setVariableLocal(it, null) } })
        inner.addBuiltInListener(ExecutionListener.EVENTNAME_END, ExecutionListener { instance ->
            val result = if (subProcess) ids.associateWith { instance.getVariableLocal(it) } else instance.getVariableLocal(inner.id)
            // The body's own execution holds the loop's counts; a non-scope Task in a sequential loop runs on it too.
            val bodyExecution = generateSequence(instance as ExecutionEntity) { it.parent }.first { it.hasVariableLocal("nrOfInstances") }
            // Until the last instance ends, the Results so far wait on the body by instance number: a list with gaps can't
            // be stored, since the JSON serializer types a list by its first item.
            val results = (bodyExecution.getVariableLocal(RESULTS) as? Map<*, *>).orEmpty() + ("${instance.getVariable("loopCounter")}" to result)
            val count = bodyExecution.getVariableLocal("nrOfInstances") as Int
            if (results.size < count) bodyExecution.setVariableLocal(RESULTS, results)
            else bodyExecution.parent.setVariable(inner.id, (0 until count).map { results["$it"] })
        })
        return inner.id
    }

    override fun preInit(configuration: ProcessEngineConfigurationImpl) {
        configuration.customPostBPMNParseListeners = (configuration.customPostBPMNParseListeners ?: mutableListOf()) +
            object : BpmnParseListener {
                override fun parseProcess(element: Element, process: ProcessDefinitionEntity) {
                    val tasks = process.tasks()
                    tasks.forEach { it.addBuiltInListener(ExecutionListener.EVENTNAME_START, counted) }
                    val fannedOut = process.multiInstanceBodies().map(::collect)
                    process.addBuiltInListener(ExecutionListener.EVENTNAME_START, started((tasks.map { it.id } + fannedOut).distinct()))
                    process.addBuiltInListener(ExecutionListener.EVENTNAME_END, ended)
                }
            }
    }
}

/** Deletes a Run workspace once its Run is done with it, all but the Transcripts (`tasks/`), which outlive the Run. */
fun cleanUp(workspace: File) {
    workspace.listFiles()?.filter { it.name != "tasks" }?.forEach { it.deleteRecursively() }
    workspace.delete() // only if nothing was kept
}

/** Every Task in this scope and the scopes inside it, such as embedded sub-processes and multi-instance bodies. */
private fun ScopeImpl.tasks(): List<ActivityImpl> = activities.flatMap { activity ->
    val type = activity.getProperty("type") as? String
    listOfNotNull(activity.takeIf { type == "task" || type?.endsWith("Task") == true }) + activity.tasks()
}

/** The Results a multi-instance's instances have given so far, on its body's execution. */
private const val RESULTS = "ants.results"

/** Every multi-instance body in this scope and the scopes inside it. */
private fun ScopeImpl.multiInstanceBodies(): List<ActivityImpl> = activities.flatMap { activity ->
    listOfNotNull(activity.takeIf { it.activityBehavior is MultiInstanceActivityBehavior }) + activity.multiInstanceBodies()
}

/** A called Process runs inside its caller's Run. */
private val DelegateExecution.isTopRun get() = superExecution == null
