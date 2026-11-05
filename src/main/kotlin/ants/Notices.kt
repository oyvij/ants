package ants

import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.externaltask.LockedExternalTask
import org.operaton.bpm.engine.impl.ServiceImpl
import org.operaton.bpm.model.bpmn.instance.FlowElement
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * A Notice: the one [line] the Notify Task [task] of the Run [run], named [runName], of the Process [process] left for
 * the user to read, first at [first] and last at [last], [count] times. Nothing waits on it; it stays until dismissed.
 */
class Notice(
    val id: String, val process: String, val run: String, val runName: String, val task: String, val line: String,
    val first: Instant, val last: Instant, val count: Int,
) {
    fun toYaml(): Map<String, Any> = mapOf(
        "process" to process, "run" to run, "runName" to runName, "task" to task, "line" to line,
        "first" to "$first", "last" to "$last", "count" to count,
    )

    companion object {
        fun of(id: String, yaml: Map<*, *>) = Notice(
            id, "${yaml["process"]}", "${yaml["run"]}", "${yaml["runName"]}", "${yaml["task"]}", "${yaml["line"]}",
            Instant.parse("${yaml["first"]}"), Instant.parse("${yaml["last"]}"), (yaml["count"] as? Int) ?: 1,
        )
    }
}

/**
 * A [Notice] on the Notification list: tagged `[notice]`, leading to its Run, or, once the Run is [gone], to a note
 * that it is. [repeats] reads `×3, last at 10:42` once it has counted up, as read on [clock].
 */
class NoticeEntry(val notice: Notice, val gone: Boolean, clock: Clock) : Entry(notice.id, "[notice]", notice.process, notice.task, notice.line, Date.from(notice.last)) {
    val run get() = notice.run
    override val runName get() = notice.runName
    val repeats = if (notice.count > 1) "×${notice.count}, last at ${clock.local(notice.last)}" else null
}

/** The open Notices, kept by Ants in `notices.yaml` under `ANTS_HOME`, not in the engine, so they outlive their Runs and a restart. */
@Component
class Notices(@Value("\${ants.home}") home: Path, private val clock: Clock) {
    private val file = home.resolve("notices.yaml")

    /** The open Notices, oldest first. */
    fun all(): List<Notice> = read().sortedBy { it.last }

    /** Adds a Notice, or counts up the open one with the same [process] and [line], which keeps its id, so it is no new entry. */
    fun add(process: String, run: String, runName: String, task: String, line: String): Notice = synchronized(this) {
        val now = clock.instant()
        val notices = read()
        val open = notices.firstOrNull { it.process == process && it.line == line }
        if (open == null) Notice("notice-${UUID.randomUUID()}", process, run, runName, task, line, now, now, 1).also { write(notices + it) }
        else Notice(open.id, process, open.run, open.runName, open.task, line, open.first, now, open.count + 1)
            .also { counted -> write(notices.map { if (it.id == open.id) counted else it }) }
    }

    /** Removes the Notice [id]; false when there was none. */
    fun dismiss(id: String): Boolean = synchronized(this) {
        val notices = read()
        notices.any { it.id == id }.also { if (it) write(notices.filter { notice -> notice.id != id }) }
    }

    private fun read(): List<Notice> = if (!file.exists()) emptyList()
    else yaml.load<Map<String, Map<*, *>>?>(file.readText()).orEmpty().map { (id, notice) -> Notice.of(id, notice) }

    private fun write(notices: List<Notice>) = file.writeAtomically(yaml.dump(notices.associate { it.id to it.toYaml() }))
}

/**
 * The Built-in Task Notify (topic `notify`): it fills in its line ([LINE]) with `${…}`, makes it one line of at most
 * [LONGEST] characters, adds it as a [Notice] and completes, so the Run goes on at once. The line is an extension
 * property, not an input, so the engine never evaluates it: a name that can't be filled in would fail the Task before
 * Notify runs. Here such a name is named in the Notice instead, and Notify never fails its Task.
 */
@Component
class Notify(
    private val externalTasks: ExternalTaskService,
    private val runtime: RuntimeService,
    private val history: HistoryService,
    private val repository: RepositoryService,
    private val names: RunNames,
    private val notices: Notices,
) : BuiltInWorker(externalTasks, "Notify", TOPIC, Duration.ofMinutes(1), { it.includeExtensionProperties() }) {
    override fun handle(task: LockedExternalTask) {
        val element = runCatching { repository.getBpmnModelInstance(task.processDefinitionId).getModelElementById<FlowElement>(task.activityId) }.getOrNull()
        val run = runCatching { runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult()!!.rootProcessInstanceId }
            .getOrDefault(task.processInstanceId)
        val top = runCatching { history.createHistoricProcessInstanceQuery().processInstanceId(run).singleResult()!! }.getOrNull()
        notices.add(
            process = top?.let { it.processDefinitionName ?: it.processDefinitionKey } ?: task.processDefinitionKey,
            run = run,
            runName = runCatching { names.name(run) }.getOrDefault(run),
            task = element?.name ?: task.activityId,
            line = line(task.extensionProperties[LINE].orEmpty()) { expression -> runtime.evaluate(task.executionId, expression) },
        )
        externalTasks.complete(task.id, WORKER)
    }

    companion object {
        const val TOPIC = "notify"

        /** Notify's line, an extension property. */
        const val LINE = "ants.line"

        const val LONGEST = 200

        /**
         * [raw] with each `${…}` filled in by [evaluate], on one line and cut to [LONGEST] characters with an ellipsis;
         * an expression that can't be filled in stays as written, and the line starts by naming it.
         */
        fun line(raw: String, evaluate: (String) -> Any?): String {
            val unfilled = mutableListOf<String>()
            val filled = Regex("""\$\{(.*?)}""", RegexOption.DOT_MATCHES_ALL).replace(raw) { match ->
                runCatching { evaluate(match.value)?.toString().orEmpty() }.getOrElse { unfilled += match.value; match.value }
            }
            val said = if (unfilled.isEmpty()) filled else "Couldn't fill in ${unfilled.distinct().joinToString(", ")}: $filled"
            return said.replace(Regex("""\s+"""), " ").trim().let { if (it.length > LONGEST) it.take(LONGEST - 1).trimEnd() + "…" else it }
        }
    }
}

/**
 * [expression], such as `${pipeline}`, evaluated on [execution] as the engine evaluates an input. Caught inside the
 * command, so one that can't be evaluated isn't logged as the engine's error.
 */
fun RuntimeService.evaluate(execution: String, expression: String): Any? = (this as ServiceImpl).commandExecutor.execute { context ->
    runCatching {
        val scope = checkNotNull(context.executionManager.findExecutionById(execution)) { "No execution $execution" }
        context.processEngineConfiguration.expressionManager.createExpression(expression).getValue(scope)
    }
}.getOrThrow()
