package ants

import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.ExecutionListener
import org.operaton.bpm.engine.externaltask.ExternalTask
import org.operaton.bpm.engine.history.HistoricProcessInstance
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl
import org.operaton.bpm.engine.impl.cfg.TransactionState
import org.operaton.bpm.engine.impl.context.Context
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl
import org.operaton.bpm.engine.impl.util.xml.Element
import org.operaton.bpm.engine.variable.value.ObjectValue
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.Date
import kotlin.io.path.createDirectories

@Controller
class Runs(
    private val history: HistoryService,
    private val externalTasks: ExternalTaskService,
    private val launcher: AgentLauncher,
    private val settings: Settings,
    private val clock: Clock,
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
                .associate { it.name to ((it.typedValue as? ObjectValue)?.valueSerialized ?: "${it.value}") },
        )
        model.addAttribute("transcripts", Transcript.all(home.resolve("runs").resolve(id)).mapValues { it.value.tokens })
        model.addAttribute(
            "agentTasks",
            externalTasks.createExternalTaskQuery().processInstanceId(id).topicName(AgentLauncher.TOPIC).list().associate { it.activityId to state(it) },
        )
        return "run"
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
    private fun keptWorkspace(run: HistoricProcessInstance) = home.resolve("runs").resolve(run.id).takeIf { workspace ->
        run.state == HistoricProcessInstance.STATE_EXTERNALLY_TERMINATED && workspace.toFile().listFiles()?.any { it.name != "tasks" } == true
    }

    private fun state(task: ExternalTask, paused: Instant? = launcher.pausedUntil(task.id)) = when {
        task.retries == 0 -> listOfNotNull(task.errorMessage, externalTasks.getExternalTaskErrorDetails(task.id)).joinToString(" ")
        paused != null -> "Paused until ${clock.local(paused)}"
        task.lockExpirationTime?.after(Date()) == true -> "Running"
        else -> "Queued: ${launcher.running} of ${settings.agentTasks()} Agent Tasks running"
    }
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
 * Task-id variable is `null`, so forward references read `null`, and `runs` counts each Task's runs.
 */
@Component
class RunLifecycle(@Value("\${ants.home}") home: Path) : AbstractProcessEnginePlugin() {
    private val workspaces = home.resolve("runs")

    private fun started(tasks: List<String>) = ExecutionListener { run ->
        if (run.isTopRun) workspaces.resolve(run.processInstanceId).createDirectories()
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
        val workspace = workspaces.resolve(run.processInstanceId).toFile()
        // Only once the end is committed: a rolled-back end leaves the Run alive and needing its workspace.
        Context.getCommandContext().transactionContext.addTransactionListener(TransactionState.COMMITTED) { cleanUp(workspace) }
    }

    override fun preInit(configuration: ProcessEngineConfigurationImpl) {
        configuration.customPostBPMNParseListeners = (configuration.customPostBPMNParseListeners ?: mutableListOf()) +
            object : BpmnParseListener {
                override fun parseProcess(element: Element, process: ProcessDefinitionEntity) {
                    val tasks = process.tasks()
                    tasks.forEach { it.addBuiltInListener(ExecutionListener.EVENTNAME_START, counted) }
                    process.addBuiltInListener(ExecutionListener.EVENTNAME_START, started(tasks.map { it.id }))
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

/** A called Process runs inside its caller's Run. */
private val DelegateExecution.isTopRun get() = superExecution == null
