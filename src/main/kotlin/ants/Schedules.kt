package ants

import jakarta.annotation.PreDestroy
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.repository.ProcessDefinition
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.operaton.bpm.model.bpmn.instance.Process
import org.operaton.bpm.model.bpmn.instance.StartEvent
import org.operaton.bpm.model.bpmn.instance.TimerEventDefinition
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * The Process [key]'s Schedule as the user reads it: in words, when it [next] starts a Run ([at] on the clock, and
 * [countdown], such as `in 1d 6h`), and what Ants noted.
 */
class Schedule(
    val key: String, val process: String, val cron: String, val paused: Boolean, val next: Instant?, val at: String?, val countdown: String?, val skipped: String?, val failed: FailedStart?,
    val id: String? = null, val label: String = process, val profile: String? = null, val values: Map<String, String> = emptyMap(),
) {
    val words get() = inWords(cron)
}

/** A scheduled Run that couldn't start, [why], at [at]. */
class FailedStart(val why: String, val at: Instant)

private data class State(
    val paused: Boolean = false, val last: Instant? = null, val run: String? = null, val skipped: String? = null, val failed: FailedStart? = null,
    val process: String? = null, val cron: String? = null, val values: Map<String, List<String>> = emptyMap(),
) {
    fun toYaml(): Map<String, Any> = listOfNotNull(
        "paused" to paused, last?.let { "last" to "$it" }, run?.let { "run" to it }, skipped?.let { "skipped" to it },
        failed?.let { "failed" to it.why }, failed?.let { "failedAt" to "${it.at}" },
        process?.let { "process" to it }, cron?.let { "cron" to it }, values.takeIf { it.isNotEmpty() }?.let { "values" to it },
    ).toMap()

    companion object {
        fun of(yaml: Map<*, *>) = State(
            yaml["paused"] == true, (yaml["last"] as? String)?.let(Instant::parse), yaml["run"] as? String, yaml["skipped"] as? String,
            (yaml["failed"] as? String)?.let { FailedStart(it, Instant.parse(yaml["failedAt"] as String)) },
            yaml["process"] as? String, yaml["cron"] as? String,
            (yaml["values"] as? Map<*, *>).orEmpty().entries.associate { (name, values) -> "$name" to (values as List<*>).map { "$it" } },
        )
    }
}

/**
 * Ants runs Schedules itself: Operaton never sees the timer start (RunLifecycle drops it), and this loop starts a Run
 * through [StartForm.start], as **Start** does. Each time one or more of its times
 * have come since the last start, it starts one Run, so times missed while Ants was off run once. A time that comes
 * while the Schedule's last Run is still going is skipped and noted until a Run starts again, and a Run that can't
 * start is kept as a [FailedStart] for the Notification list.
 */
@Component
class Schedules(
    private val repository: RepositoryService,
    private val history: HistoryService,
    private val startForm: StartForm,
    private val clock: Clock,
    @Value("\${ants.home}") home: Path,
    @Value("\${ants.schedule-every:30s}") private val every: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val loop = Executors.newSingleThreadScheduledExecutor()
    private val file = home.resolve("schedules.yaml")

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        // Straight away, so a time missed while Ants was off runs now.
        loop.scheduleWithFixedDelay(::tick, 0, every.toMillis(), MILLISECONDS)
    }

    @PreDestroy
    fun shutdown() {
        loop.shutdownNow()
    }

    /** Every Process with a Schedule, by when it next starts a Run. */
    fun all(): List<Schedule> = repository.createProcessDefinitionQuery().latestVersion().list().mapNotNull(::of).sortedBy { it.next }

    /** The Schedule of [definition], the latest version of its Process, or null without one. */
    fun of(definition: ProcessDefinition): Schedule? {
        val cron = repository.getBpmnModelInstance(definition.id).schedule() ?: return null
        val state = state(definition.key)
        val next = next(cron, state.paused)
        return Schedule(definition.key, definition.name ?: definition.key, cron, state.paused, next, next?.let(clock::local), next?.let { countdown(Duration.between(clock.instant(), it)) }, state.skipped, state.failed)
    }

    fun of(processKey: String): List<Schedule> {
        val definition = latest(processKey) ?: return emptyList()
        val fields = startForm.fields(definition)
        val process = definition.name ?: processKey
        return states().filterValues { it.process == processKey }.map { (id, state) ->
            val cron = state.cron.orEmpty()
            val next = next(cron, state.paused)
            val repos = fields.filter { it.type == FieldType.REPOS }.flatMap { state.values[it.name].orEmpty() }.flatMap { it.split(Regex("[,\\s]+")) }.filter(String::isNotEmpty)
            val label = runName(state.values[NAME]?.firstOrNull()) ?: listOf(process, repos.joinToString(", ")).filter(String::isNotEmpty).joinToString(" · ")
            val values = fields.associate { field -> field.label to state.values[field.name].orEmpty().filter(String::isNotBlank).joinToString(", ") }.filterValues(String::isNotEmpty)
            Schedule(
                processKey, process, cron, state.paused, next, next?.let(clock::local), next?.let { countdown(Duration.between(clock.instant(), it)) }, state.skipped, state.failed,
                id, label, startForm.profile(definition, state.values[PROFILE]?.firstOrNull()), values,
            )
        }
    }

    fun add(processKey: String, values: Map<String, List<String>>, cron: String): String = synchronized(this) {
        val times = cron.trim()
        if (times.split(Regex("\\s+")).size != 6 || runCatching { CronExpression.parse(times) }.isFailure) {
            throw Unfilled("Ants can't read the cron $times: it takes six fields, seconds first, such as 0 0 8 * * MON-FRI.")
        }
        val definition = latest(processKey) ?: throw Unfilled("There is no Process $processKey.")
        val kept = values.filterKeys { it in startForm.fields(definition).map(Field::name) + NAME + PROFILE }
        startForm.check(definition, kept)
        UUID.randomUUID().toString().also { id -> update(id) { State(last = clock.instant(), process = processKey, cron = times, values = kept) } }
    }

    /** Pauses or resumes the Schedule of [key]; times that come while it is paused never run. */
    fun pause(key: String, paused: Boolean) = update(key) { it.copy(paused = paused, last = clock.instant()) }

    /** Forgets what Ants kept of [key]'s Schedule, once the Process has none. */
    fun forget(key: String) = synchronized(this) { write(states() - key) }

    /** Dismisses the failed start of [key] from the Notification list. */
    fun dismiss(key: String) = update(key) { it.copy(failed = null) }

    /** **Start now**: the scheduled Run of [definition], with the stored values, as the loop starts it. */
    fun startNow(definition: ProcessDefinition): ProcessInstance = started(definition.key) { startForm.start(definition, stored(definition)) }

    private fun started(id: String, start: () -> ProcessInstance): ProcessInstance = synchronized(this) {
        try {
            start().also { run -> update(id) { it.copy(run = run.id, skipped = null, failed = null) } }
        } catch (e: Exception) {
            update(id) { it.copy(failed = FailedStart(e.message ?: e.javaClass.simpleName, clock.instant())) }
            throw e
        }
    }

    private fun startPageSchedule(id: String, state: State) = started(id) {
        val definition = latest(state.process.orEmpty()) ?: throw Unfilled("There is no Process ${state.process} any more.")
        val name = runName(state.values[NAME]?.firstOrNull())?.let { clock.defaultName(it) }
        startForm.start(definition, state.values + (NAME to listOfNotNull(name)))
    }

    private fun tick() = try {
        for (definition in repository.createProcessDefinitionQuery().latestVersion().list()) {
            val cron = repository.getBpmnModelInstance(definition.id).schedule() ?: continue
            runCatching { due(definition, CronExpression.parse(cron)) }.onFailure { log.warn("The Schedule of ${definition.key} failed", it) }
        }
        for (id in states().filterValues { it.process != null }.keys) {
            runCatching { due(id) }.onFailure { log.warn("The Schedule $id failed", it) }
        }
    } catch (e: Exception) {
        log.warn("The Schedule loop failed", e) // and goes round again
    }

    /** Starts a Run of [definition] if a time of [cron] came since the last start, or notes the latest one skipped. */
    private fun due(definition: ProcessDefinition, cron: CronExpression): Unit = synchronized(this) {
        // A version the loop listed before a Save or Remove replaced it: its key's state is the new version's now.
        if (repository.createProcessDefinitionQuery().processDefinitionKey(definition.key).latestVersion().singleResult()?.id != definition.id) return
        val deployed = repository.createDeploymentQuery().deploymentId(definition.deploymentId).singleResult().deploymentTime.toInstant()
        due(definition.key, state(definition.key), cron, deployed) { startNow(definition) }
    }

    private fun due(id: String): Unit = synchronized(this) {
        val state = states()[id]?.takeIf { it.process != null } ?: return
        due(id, state, CronExpression.parse(state.cron.orEmpty()), clock.instant()) { startPageSchedule(id, state) }
    }

    private fun due(id: String, state: State, cron: CronExpression, since: Instant, start: () -> ProcessInstance) {
        if (state.paused) return
        val now = clock.instant()
        // ponytail: steps through every time missed, so a cron of every second missed for a month takes a while.
        val time = generateSequence(cron.next((state.last ?: since).atZone(clock.zone))) { cron.next(it) }.takeWhile { it.toInstant() <= now }.lastOrNull() ?: return
        update(id) { it.copy(last = now) }
        val last = state.run?.let { history.createHistoricProcessInstanceQuery().processInstanceId(it).active().singleResult() }
        if (last != null) update(id) { it.copy(skipped = "Skipped ${clock.local(time.toInstant())}: the last Run from this Schedule was still going.") }
        else runCatching { start() }
    }

    private fun next(cron: String, paused: Boolean) =
        if (paused) null else runCatching { CronExpression.parse(cron).next(clock.instant().atZone(clock.zone))?.toInstant() }.getOrNull()

    private fun latest(key: String): ProcessDefinition? = repository.createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult()

    /** Each field's stored value, as the start form would post it. */
    private fun stored(definition: ProcessDefinition) = startForm.fields(definition).associate { field ->
        field.name to if (field.type == FieldType.REPOS) field.storedRepos else listOfNotNull(field.stored)
    }

    private fun states(): Map<String, State> = if (!file.exists()) emptyMap()
    else yaml.load<Map<String, Map<*, *>>?>(file.readText()).orEmpty().mapValues { State.of(it.value) }

    private fun state(key: String) = states()[key] ?: State()

    private fun write(states: Map<String, State>) = file.writeAtomically(yaml.dump(states.mapValues { it.value.toYaml() }))

    private fun update(key: String, change: (State) -> State) = synchronized(this) {
        val states = states()
        write(states + (key to change(states[key] ?: State())))
    }
}

/** The Process's Schedule: the cron on its timer start event, or null when it starts only by hand. */
fun BpmnModelInstance.schedule(): String? = scheduleTimer()?.timeCycle?.textContent?.trim()?.ifEmpty { null }

/** The timer of the Process's start event, if it has one; a sub-process's start event is not a Schedule. */
fun BpmnModelInstance.scheduleTimer(): TimerEventDefinition? = getModelElementsByType(Process::class.java)
    .flatMap { it.getChildElementsByType(StartEvent::class.java) }
    .firstNotNullOfOrNull { it.getChildElementsByType(TimerEventDefinition::class.java).firstOrNull() }

/** How long until a time [ahead], to the nearest minute: `in 6h 12m`, or `in 1d 6h`. */
fun countdown(ahead: Duration) = "in " + duration(Duration.ofMinutes((ahead.toSeconds() + 30) / 60), seconds = false)

/** A Schedule in words, as the modeler writes it: every hour, day, weekday, week or month at a time, else its six fields. */
fun inWords(cron: String): String {
    val fields = cron.trim().split(Regex("\\s+"))
    if (fields.size != 6 || fields[0] != "0" || fields[4] != "*") return "At times $cron"
    val (minute, hour, day) = fields.subList(1, 4)
    val weekday = fields[5]
    if (minute == "0" && hour == "*" && day == "*" && weekday == "*") return "Every hour"
    val m = minute.toIntOrNull()
    val h = hour.toIntOrNull()
    if (m == null || h == null || m !in 0..59 || h !in 0..23) return "At times $cron"
    val at = "at %02d:%02d".format(h, m)
    val days = mapOf("MON" to "Monday", "TUE" to "Tuesday", "WED" to "Wednesday", "THU" to "Thursday", "FRI" to "Friday", "SAT" to "Saturday", "SUN" to "Sunday")
    return when {
        day == "*" && weekday == "*" -> "Every day $at"
        day == "*" && weekday == "MON-FRI" -> "Every weekday $at"
        day == "*" && weekday in days -> "Every ${days[weekday]} $at"
        weekday == "*" && day.toIntOrNull() in 1..31 -> "Every month on day $day $at"
        else -> "At times $cron"
    }
}
