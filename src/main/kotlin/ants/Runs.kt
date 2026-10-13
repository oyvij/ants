package ants

import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.ExecutionListener
import org.operaton.bpm.engine.externaltask.ExternalTask
import org.operaton.bpm.engine.history.HistoricProcessInstance
import org.operaton.bpm.engine.impl.bpmn.behavior.MultiInstanceActivityBehavior
import org.operaton.bpm.engine.impl.bpmn.behavior.ParallelMultiInstanceActivityBehavior
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParse
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseUtil
import org.operaton.bpm.engine.impl.interceptor.CommandContext
import org.operaton.bpm.engine.impl.ServiceImpl
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
import org.springframework.web.bind.annotation.ResponseBody
import tools.jackson.databind.json.JsonMapper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
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
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively

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
    private val names: RunNames,
    private val scopes: Scopes,
) {
    @GetMapping("/runs")
    fun list(model: Model): String {
        val runs = history.createHistoricProcessInstanceQuery().rootProcessInstances().orderByProcessInstanceStartTime().desc().list()
        model.addAttribute("runs", runs)
        model.addAttribute("names", runs.associate { it.id to names.name(it.id) })
        return "runs"
    }

    @GetMapping("/runs/{id}")
    fun run(@PathVariable id: String, model: Model): String {
        val run = run(id)
        model.addAttribute("run", run)
        model.addAttribute("name", names.name(id))
        model.addAttribute("scope", scopes.of(id))
        model.addAttribute("profile", scopes.profile(id))
        model.addAttribute("kept",keptWorkspace(run)?.toString())
        // Where the work lives, in full, as long as it is on disk: the shared clones, then each instance's worktrees.
        val workspace = names.runWorkspace(id)
        model.addAttribute("workspace", workspace.takeIf { Files.isDirectory(it) }?.toString())
        model.addAttribute("repos", (listOf(workspace.resolve("repos")) + workspace.resolve("instances").folders().map { it.resolve("repos") })
            .flatMap { it.folders() }.map { it.toString() })
        model.addAttribute(
            "variables",
            history.createHistoricVariableInstanceQuery().processInstanceId(id).disableCustomObjectDeserialization()
                .orderByVariableName().asc().list()
                .filter { it.executionId == id } // the Run's own, not an Agent Task's inputs or a multi-instance's own Results
                .associate { it.name to ((it.typedValue as? ObjectValue)?.valueSerialized ?: "${it.value}") },
        )
        model.addAttribute("transcripts", Transcript.all(workspace).mapValues { it.value.tokens })
        model.addAttribute("report", Files.exists(workspace.resolve(REPORT)))
        model.addAttribute(
            "agentTasks",
            externalTasks.createExternalTaskQuery().processInstanceId(id).topicName(AgentLauncher.TOPIC).list()
                .associate { runCatching { runtime.taskKey(it.executionId, it.activityId) }.getOrDefault(it.activityId) to state(it) }.toSortedMap(),
        )
        return "run"
    }

    /** The Report the Run left as it ended, rendered. */
    @GetMapping("/runs/{id}/report")
    fun report(@PathVariable id: String, model: Model): String {
        val file = names.runWorkspace(run(id).id).resolve(REPORT)
        if (!Files.exists(file)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Run $id has no Report yet")
        model.addAttribute("run", id)
        model.addAttribute("name", names.name(id))
        model.addAttribute("html", Transcript.markdown(Files.readString(file)))
        return "report"
    }

    /** The diagram of the Process version the Run runs. */
    @GetMapping("/runs/{id}/diagram.bpmn", produces = ["application/xml"])
    @ResponseBody
    fun diagram(@PathVariable id: String): ByteArray = repository.getProcessModel(run(id).processDefinitionId).readAllBytes()

    /**
     * The Run's state, each element's [Shown] and the process instance each call activity called (its latest), as
     * JSON, sent on connect and whenever it changes. [id] may be a called Process's instance, for the breadcrumb.
     */
    fun live(id: String): Part {
        run(id)
        return streams.changes("run") {
            // ponytail: a multi-instance call activity opens its latest called instance; a picker if one is wanted.
            val called = history.createHistoricActivityInstanceQuery().processInstanceId(id).activityType("callActivity")
                .orderByHistoricActivityInstanceStartTime().asc().list().filter { it.calledProcessInstanceId != null }
                .associate { it.activityId to it.calledProcessInstanceId }
            json.writeValueAsString(mapOf("state" to run(id).stateName, "elements" to shown(id), "called" to called))
        }
    }

    /**
     * The drawer of the Run's Task [task] as HTML, sent on connect and whenever it changes: its state, So far, the
     * Agent's checklist, latest output, and the comment box and action when it waits on the user. [id] may be a
     * called Process's instance, whose Task is kept in the top Run under its key ([taskKey]).
     */
    fun drawer(id: String, task: String, csrf: CsrfToken): Part {
        val instance = run(id)
        val element = repository.getBpmnModelInstance(instance.processDefinitionId).getModelElementById<FlowElement>(task)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Task $task")
        val top = instance.rootProcessInstanceId
        val key = (history.createHistoricVariableInstanceQuery().processInstanceId(id).variableName(CALLED).singleResult()?.value as? String).orEmpty() + task
        val file = Transcript.file(names.runWorkspace(top), key)
        val transcript = Transcript()
        var offset = 0L
        csrf.token // loads the token while the request is still here
        return streams.changes("drawer") {
            val (lines, end) = Transcript.read(file, offset)
            offset = end
            lines.forEach(transcript::add)
            val entry = notifications.tasks().firstOrNull { it.instance == id && it.activity == task }
            val model = mapOf(
                "run" to top, "runName" to names.name(top), "task" to key, "name" to (element.name ?: task), "shown" to shown(id)[task], "transcript" to transcript,
                "entry" to entry, "details" to entry?.let(notifications::details), "push" to (entry?.let(notifications::isPush) == true), "sendHint" to launcher.sendHint(top, key), "_csrf" to csrf,
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
        val entries = notifications.tasks().filter { it.instance == id }.associateBy { it.activity }
        val agentTasks = externalTasks.createExternalTaskQuery().processInstanceId(id).topicName(AgentLauncher.TOPIC).list().groupBy { it.activityId }
        val rounds = history.createHistoricVariableInstanceQuery().processInstanceId(id).variableName("runs").singleResult()?.value as? Map<*, *>
        return history.createHistoricActivityInstanceQuery().processInstanceId(id).list().groupBy { it.activityId }.mapValues { (activity, instances) ->
            val round = (rounds?.get(activity) as? Number)?.toInt()
            val entry = entries[activity]
            val tasks = agentTasks[activity].orEmpty()
            val paused = tasks.firstNotNullOfOrNull { launcher.pausedUntil(it.id) }
            val held = tasks.map { launcher.heldBy(it.id) }
            when {
                instances.all { it.endTime != null } -> Shown("done", null, round)
                entry != null -> Shown("waiting", "${entry.icon} ${entry.tag}", round)
                paused != null -> Shown("queued", "Paused until ${clock.local(paused)}", round)
                held.isNotEmpty() && held.all { it != null } -> Shown("queued", held.first(), round)
                tasks.isNotEmpty() && tasks.none { it.lockExpirationTime?.after(Date()) == true } -> Shown("queued", "Queued", round)
                else -> Shown("running", null, round)
            }
        }
    }

    /** Cancel the Run, from its page: as the Notification list's Cancel the Run does, whatever the Run is doing. */
    @PostMapping("/runs/{id}/cancel")
    fun cancel(@PathVariable id: String): String {
        if (run(id).endTime == null) {
            // A cancelled Run keeps its workspace; externally terminated is what its pages read as Cancelled.
            runtime.deleteProcessInstance(id, "Cancelled by the user", false, true)
            launcher.cancel(id)
        }
        return "redirect:/runs/$id"
    }

    /** Rename: the Run's name changes, live or ended; its folder and work branch keep the one they started with. */
    @PostMapping("/runs/{id}/name")
    fun rename(@PathVariable id: String, @RequestParam name: String): String {
        names.rename(run(id).id, name)
        return "redirect:/runs/$id"
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
    /** The folders in this one, by name, leaving out hidden ones such as a clone still being made; none when it is missing. */
    private fun Path.folders(): List<Path> = if (!Files.isDirectory(this)) emptyList()
    else Files.list(this).use { all -> all.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }.sorted().toList() }

    private fun keptWorkspace(run: HistoricProcessInstance) = names.runWorkspace(run.id).takeIf { workspace ->
        run.state == HistoricProcessInstance.STATE_EXTERNALLY_TERMINATED && workspace.toFile().listFiles()?.any { it.name !in KEPT } == true
    }

    private fun state(task: ExternalTask, paused: Instant? = launcher.pausedUntil(task.id), held: String? = launcher.heldBy(task.id)) = when {
        task.retries == 0 -> listOfNotNull(task.errorMessage, externalTasks.getExternalTaskErrorDetails(task.id)).joinToString(" ")
        paused != null -> "Paused until ${clock.local(paused)}"
        held != null -> held
        task.lockExpirationTime?.after(Date()) == true -> "Running"
        else -> "Queued: ${launcher.running} of ${settings.agentTasks()} Agent Tasks running"
    }
}

private val json = JsonMapper.shared()

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
 * Gives each Run its name ([NAME]) and its Run workspace, `runs/<slug>-<run>/` in ANTS_HOME ([FOLDER]), for as long
 * as it lives, its Run log starting with the name. At Run start every
 * Task-id (and call activity id) variable is `null`, so forward references read `null`, `runs` counts each Task's
 * runs, `clones` is empty and `sourceMap` holds the repos of the Run's Profile's Source map ([PROFILE]), `[{name, remote}]`, none in a scoped Run ([SCOPE]). A multi-instance collects its instances' Results into one list ([collect]). A called Process runs inside its
 * caller's Run: it ends by giving its call activity every Result in it, by id.
 */
/** The variables [RunLifecycle] gives every Run besides its Results, which any expression may read. */
val RUN_VARIABLES = listOf("runs", "clones", "sourceMap")

@Component
class RunLifecycle(@Value("\${ants.home}") private val home: Path, private val settings: Settings, private val clock: Clock) : AbstractProcessEnginePlugin() {
    private fun started(tasks: List<String>, results: List<String>) = ExecutionListener { run ->
        if (run.isTopRun) {
            // The name given on the start form, else the Process and start time; the folder and Run log start with it.
            val process = (run as ExecutionEntity).processDefinition.let { it.name ?: it.key }
            val name = (run.getVariable(NAME) as? String)?.trim()?.take(200)?.ifEmpty { null } ?: clock.defaultName(process)
            val folder = folder(name, run.processInstanceId)
            run.setVariable(NAME, name)
            run.setVariable(FOLDER, folder)
            val workspace = home.runWorkspace(run.processInstanceId, folder).createDirectories()
            trackerDoc(workspace)
            Files.writeString(workspace.resolve("run-log.md"), "# $name\n\n$process\n\n")
        } else run.setVariable(CALLED, called(run.superExecution))
        for (id in results) run.setVariable(id, null)
        run.setVariable("runs", tasks.associateWith { 0 })
        run.setVariable("clones", ArrayList<Any>()) // Clone repos adds to it
        // A called Process reads its top Run's scope and Profile, as it runs in that Run.
        val top = run.topRun
        @Suppress("UNCHECKED_CAST")
        val repos = settings.runRepos(top.getVariable(SCOPE) as List<String>?, top.getVariable(PROFILE) as String?)
        run.setVariable("sourceMap", ArrayList(repos.map { linkedMapOf("name" to it.name, "remote" to it.remote) }))
    }

    // ponytail: parallel Tasks starting in separate transactions clash on `runs` (optimistic locking) and the losing
    // transaction rolls back; whoever drives it must retry, as the worker and the Approve page do ([onClash]).
    private val counted = ExecutionListener { task ->
        @Suppress("UNCHECKED_CAST")
        val counts = task.getVariable("runs") as Map<String, Int>
        task.setVariable("runs", counts + (task.currentActivityId to (counts[task.currentActivityId] ?: 0) + 1))
    }

    private fun ended(results: List<String>) = ExecutionListener { run ->
        val cancelled = run.isCanceled
        // A called Process's Result is every Result in it, by id, on its call activity, as a Task's is on the Task.
        run.superExecution?.let { call ->
            if (!cancelled) call.setVariable(call.currentActivityId, results.associateWith { run.getVariable(it) })
            return@ExecutionListener
        }
        val workspace = home.runWorkspace(run.processInstanceId, run.getVariable(FOLDER) as? String)
        val report = report(run, workspace)
        // Only once the end is committed: a rolled-back end leaves the Run alive and needing its workspace.
        Context.getCommandContext().transactionContext.addTransactionListener(TransactionState.COMMITTED) {
            closeSpec(workspace, cancelled)
            Files.writeString(workspace.resolve(REPORT), report)
            // A cancelled Run keeps its workspace, so no commits are lost.
            if (!cancelled) cleanUp(workspace.toFile())
        }
    }

    /**
     * Collects the Results of [body]'s instances into one list, in instance order, under the id of the activity it
     * repeats. Each instance keeps the Results it makes as its own local variables, so `${id}` inside it reads its own;
     * as it ends, they go into the list in the same transaction, so two instances ending at once clash (optimistic
     * locking) instead of losing one. A Task (or call activity) gives its Result, a sub-process `{taskId: Result}`.
     * An empty collection gives an empty list. Each instance reads its item as `${item}` unless the loop names its
     * element variable.
     */
    private fun collect(body: ActivityImpl) {
        val loop = body.activityBehavior as MultiInstanceActivityBehavior
        if (loop.collectionElementVariable == null) loop.collectionElementVariable = "item"
        val inner = body.activities.single()
        val subProcess = inner.getProperty("type") == "subProcess"
        val ids = if (subProcess) inner.results() else listOf(inner.id)
        inner.addBuiltInListener(ExecutionListener.EVENTNAME_START, ExecutionListener { instance -> ids.forEach { instance.setVariableLocal(it, null) } })
        inner.addBuiltInListener(ExecutionListener.EVENTNAME_END, ExecutionListener { instance ->
            // A cancelled instance, as when its Run is cancelled, has no Result and its list is never read; a list whose
            // first item is null can't even be stored, which would make the Run impossible to cancel.
            if (instance.isCanceled) return@ExecutionListener
            val result = if (subProcess) ids.associateWith { instance.getVariableLocal(it) } else instance.getVariableLocal(inner.id)
            // The body's own execution holds the loop's counts; a non-scope Task in a sequential loop runs on it too.
            val bodyExecution = generateSequence(instance as ExecutionEntity) { it.parent }.first { it.hasVariableLocal("nrOfInstances") }
            // Until the last instance ends, the Results so far wait on the body by instance number: a list with gaps can't
            // be stored, since the JSON serializer types a list by its first item. Always a LinkedHashMap, never the
            // SingletonMap `emptyMap() + pair` gives: the engine keeps a variable's type and its JSON in two rows, so an
            // instance ending beside another can read the old type with the new JSON, and only a type that never
            // changes still reads it.
            val results = LinkedHashMap(bodyExecution.getVariableLocal(RESULTS) as? Map<*, *> ?: emptyMap<Any, Any>())
                .apply { put("${instance.getVariable("loopCounter")}", result) }
            val count = bodyExecution.getVariableLocal("nrOfInstances") as Int
            if (results.size < count) bodyExecution.setVariableLocal(RESULTS, results)
            else bodyExecution.parent.setVariable(inner.id, (0 until count).map { results["$it"] })
            // With Items are Tickets, the instance's Ticket goes in review once it ends, after its last landing.
            if (body.getProperty(ITEMS_ARE_TICKETS) == true) {
                val item = instance.getVariable(loop.collectionElementVariable)
                val top = instance.topRun
                val run = top.id
                val workspace = home.runWorkspace(run, top.getVariable(FOLDER) as? String)
                Context.getCommandContext().transactionContext.addTransactionListener(TransactionState.COMMITTED) {
                    ticket(workspace, run, item)?.takeIf { !it.closed }?.status(Tracked.IN_REVIEW)
                }
            }
        })
        // With no instances the loop never sets its count, and no instance ends to give the list.
        body.addBuiltInListener(ExecutionListener.EVENTNAME_END, ExecutionListener { execution ->
            if (execution.getVariableLocal("nrOfInstances") == null) execution.setVariable(inner.id, ArrayList<Any>())
        })
    }

    override fun preInit(configuration: ProcessEngineConfigurationImpl) {
        configuration.customPostBPMNParseListeners = (configuration.customPostBPMNParseListeners ?: mutableListOf()) +
            object : BpmnParseListener {
                override fun parseProcess(element: Element, process: ProcessDefinitionEntity) {
                    // A timer start is the Process's Schedule, which Ants runs itself (Schedules), so Operaton gets no timer job.
                    process.setProperty(BpmnParse.PROPERTYNAME_START_TIMER, null)
                    val tasks = process.tasks()
                    tasks.forEach { it.addBuiltInListener(ExecutionListener.EVENTNAME_START, counted) }
                    process.multiInstanceBodies().forEach(::collect)
                    val results = process.results()
                    process.addBuiltInListener(ExecutionListener.EVENTNAME_START, started(tasks.map { it.id }, results))
                    process.addBuiltInListener(ExecutionListener.EVENTNAME_END, ended(results))
                }

                override fun parseMultiInstanceLoopCharacteristics(activity: Element, loop: Element, body: ActivityImpl) {
                    if (BpmnParseUtil.parseOperatonExtensionProperties(activity)?.get(ITEMS_ARE_TICKETS) == "true") body.setProperty(ITEMS_ARE_TICKETS, true)
                }
            }
    }
}

/** What outlives the Run in its workspace: the Transcripts (`tasks/`), the Spec and Tickets (`tickets/`) and the Report. */
private val KEPT = setOf("tasks", "tickets", REPORT)

/** Deletes a Run workspace once its Run is done with it, all but what is [KEPT]. */
@OptIn(ExperimentalPathApi::class)
fun cleanUp(workspace: File) {
    // Not following links: an instance's folder links to what is kept (Landing).
    workspace.listFiles()?.filter { it.name !in KEPT }?.forEach { it.toPath().deleteRecursively() }
    workspace.delete() // only if nothing was kept
}

/** Every Task in this scope and the scopes inside it, such as embedded sub-processes and multi-instance bodies. */
private fun ScopeImpl.tasks(): List<ActivityImpl> = activities.flatMap { activity ->
    val type = activity.getProperty("type") as? String
    listOfNotNull(activity.takeIf { type == "task" || type?.endsWith("Task") == true }) + activity.tasks()
}

/** The ids of the Results this scope gives, each `null` until given: its Tasks', call activities' and multi-instances'. */
private fun ScopeImpl.results() = (tasks() + calls() + multiInstanceBodies().map { it.activities.single() }).map { it.id }.distinct()

/** Every call activity in this scope and the scopes inside it. */
private fun ScopeImpl.calls(): List<ActivityImpl> =
    activities.flatMap { activity -> listOfNotNull(activity.takeIf { it.getProperty("type") == "callActivity" }) + activity.calls() }

/**
 * On a called Process's instance, what the keys of its Tasks start with in the Run (see [taskKey]): its call
 * activity's key and a dot, such as `build.`, or `build-2.` for the second instance of a multi-instance call.
 */
const val CALLED = "ants.called"

/** The key prefix of the Process [call] calls: its caller's own, then the call's key. */
private fun called(call: DelegateExecution) = key(call as ExecutionEntity, call.currentActivityId) + "."

/**
 * The key of [activity] on [execution] in its Run: its id, then `-<n>` for each multi-instance it is the nth instance
 * of, outermost first, as in `draft-2-1` for the first `draft` in the second instance of a multi-instance sub-process;
 * in a called Process, after its call's key ([CALLED]).
 */
private fun key(execution: ExecutionEntity, activity: String) = (execution.getVariable(CALLED) as? String).orEmpty() + activity +
    generateSequence(execution) { it.parent }.mapNotNull { it.getVariableLocal("loopCounter") as? Int }.toList().asReversed().joinToString("") { "-${it + 1}" }

/**
 * The key of the Task [activity] on [execution] in its Run, for its Transcript, live session and Notification entry,
 * since instances of a multi-instance run at once and a called Process's Task ids can be its caller's too: `draft`,
 * `draft-2`, `draft-2-1` or `build.draft`.
 */
fun RuntimeService.taskKey(execution: String, activity: String): String = (this as ServiceImpl).commandExecutor.execute { context ->
    key(checkNotNull(context.executionManager.findExecutionById(execution)) { "No execution $execution" }, activity)
}

/**
 * Whether the Task [activity] on [execution] runs in an instance of a parallel multi-instance, in its own Process or
 * in one calling it, and so lands ([Landing]).
 */
fun RuntimeService.inParallel(execution: String, activity: String): Boolean = (this as ServiceImpl).commandExecutor.execute { context ->
    context.scopes(execution, activity).any { (_, scope) -> (scope as? ActivityImpl)?.activityBehavior is ParallelMultiInstanceActivityBehavior }
}

/**
 * The item of the Items are Tickets multi-instance the Task [activity] on [execution] runs an instance of, in its own
 * Process or in one calling it: the innermost such; null when it runs in none.
 */
fun RuntimeService.ticketItem(execution: String, activity: String): Any? = (this as ServiceImpl).commandExecutor.execute { context ->
    context.scopes(execution, activity).firstNotNullOfOrNull { (level, scope) ->
        (scope as? ActivityImpl)?.takeIf { it.getProperty(ITEMS_ARE_TICKETS) == true }
            ?.let { body -> level.getVariable((body.activityBehavior as MultiInstanceActivityBehavior).collectionElementVariable) }
    }
}

/**
 * Every scope the Task [activity] on [execution] runs in, innermost first, in its own Process and then in those calling
 * it, each with the execution at that level: the Task's, then each call activity's.
 */
private fun CommandContext.scopes(execution: String, activity: String): Sequence<Pair<ExecutionEntity, ScopeImpl>> {
    val task = checkNotNull(executionManager.findExecutionById(execution)) { "No execution $execution" }
    return generateSequence(task to activity) { (it, _) -> it.processInstance.superExecution?.let { call -> call to call.currentActivityId } }.flatMap { (level, id) ->
        generateSequence(level.processDefinition.findActivity(id) as ScopeImpl?) { (it as? ActivityImpl)?.flowScope }.map { level to it }
    }
}

/** The extension property ticking **Items are Tickets** on a multi-instance, and the mark Ants leaves on its body. */
const val ITEMS_ARE_TICKETS = "ants.items-are-tickets"

/** The Results a multi-instance's instances have given so far, on its body's execution. */
private const val RESULTS = "ants.results"

/** Every multi-instance body in this scope and the scopes inside it. */
private fun ScopeImpl.multiInstanceBodies(): List<ActivityImpl> = activities.flatMap { activity ->
    listOfNotNull(activity.takeIf { it.activityBehavior is MultiInstanceActivityBehavior }) + activity.multiInstanceBodies()
}

/** A called Process runs inside its caller's Run. */
private val DelegateExecution.isTopRun get() = superExecution == null

/**
 * The top Run's process instance [this] runs in, at any depth of called Processes: the engine's root process instance
 * ([ExecutionEntity.getRootProcessInstanceId]), the one services find by that id.
 */
private val DelegateExecution.topRun: ExecutionEntity
    get() = generateSequence((this as ExecutionEntity).processInstance) { it.superExecution?.processInstance }.last()
