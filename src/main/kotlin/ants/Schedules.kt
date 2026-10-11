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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * The Process [key]'s Schedule as the user reads it: in words, when it [next] starts a Run ([at] on the clock), and
 * what Ants noted.
 */
class Schedule(val key: String, val process: String, val cron: String, val paused: Boolean, val next: Instant?, val at: String?, val skipped: String?, val failed: Failed?) {
    val words get() = inWords(cron)
}

/** A scheduled Run that couldn't start, [why], at [at]. */
class Failed(val why: String, val at: Instant)

/**
 * Ants runs Schedules itself: Operaton never sees the timer start (RunLifecycle drops it), and this loop starts a Run
 * through [StartForm.start], as **Start** does, with each field's stored value. Each time it finds the next cron time
 * after the last start due, it starts one Run, so times missed while Ants was off run once. A time that comes while
 * the Schedule's last Run is still going is skipped and noted, and a Run that can't start is kept as [Failed] for
 * the Notification list. What isn't in the BPMN (paused, the last start and Run, the notes) is `schedules.yaml`.
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
        val state = state()[definition.key].orEmpty()
        val paused = state["paused"] == true
        val next = if (paused) null else runCatching { CronExpression.parse(cron).next(clock.instant().atZone(clock.zone))?.toInstant() }.getOrNull()
        return Schedule(
            definition.key, definition.name ?: definition.key, cron, paused, next, next?.let(clock::local),
            state["skipped"] as? String,
            (state["failed"] as? String)?.let { Failed(it, Instant.parse(state["failedAt"] as String)) },
        )
    }

    /** Pauses or resumes the Schedule of [key]; times that come while it is paused never run. */
    fun pause(key: String, paused: Boolean) = update(key) { it + mapOf("paused" to paused, "last" to clock.instant().toString()) }

    /** Forgets what Ants kept of [key]'s Schedule, once the Process has none. */
    fun forget(key: String) = synchronized(this) { file.writeAtomically(yaml.dump(state() - key)) }

    /** Dismisses the failed start of [key] from the Notification list. */
    fun dismiss(key: String) = update(key) { it - "failed" - "failedAt" }

    /** **Start now**: the scheduled Run of [definition], with the stored values, as the loop starts it. */
    fun startNow(definition: ProcessDefinition): ProcessInstance = synchronized(this) {
        try {
            startForm.start(definition, stored(definition)).also { run -> update(definition.key) { it - "failed" - "failedAt" + ("run" to run.id) } }
        } catch (e: Exception) {
            update(definition.key) { it + mapOf("failed" to (e.message ?: e.javaClass.simpleName), "failedAt" to clock.instant().toString()) }
            throw e
        }
    }

    private fun tick() = try {
        for (definition in repository.createProcessDefinitionQuery().latestVersion().list()) {
            val cron = repository.getBpmnModelInstance(definition.id).schedule() ?: continue
            runCatching { due(definition, CronExpression.parse(cron)) }.onFailure { log.warn("The Schedule of ${definition.key} failed", it) }
        }
    } catch (e: Exception) {
        log.warn("The Schedule loop failed", e) // and goes round again
    }

    /** Starts a Run of [definition] if a time of [cron] came since the last start, or notes why not. */
    private fun due(definition: ProcessDefinition, cron: CronExpression): Unit = synchronized(this) {
        val state = state()[definition.key].orEmpty()
        if (state["paused"] == true) return
        val now = clock.instant()
        val since = (state["last"] as? String)?.let(Instant::parse)
            ?: repository.createDeploymentQuery().deploymentId(definition.deploymentId).singleResult().deploymentTime.toInstant()
        val time = cron.next(since.atZone(clock.zone))?.toInstant()?.takeIf { it <= now } ?: return
        update(definition.key) { it + ("last" to now.toString()) }
        val last = (state["run"] as? String)?.let { history.createHistoricProcessInstanceQuery().processInstanceId(it).active().singleResult() }
        if (last != null) update(definition.key) { it + ("skipped" to "Skipped ${clock.local(time)}: the last Run from this Schedule was still going.") }
        else runCatching { startNow(definition) }
        Unit
    }

    /** Each field's stored value, as the start form would post it. */
    private fun stored(definition: ProcessDefinition) = startForm.fields(definition).associate { field ->
        field.name to if (field.type == FieldType.REPOS) field.storedRepos else listOfNotNull(field.stored)
    }

    @Suppress("UNCHECKED_CAST")
    private fun state(): Map<String, Map<String, Any>> = if (file.exists()) yaml.load<Map<String, Map<String, Any>>?>(file.readText()).orEmpty() else emptyMap()

    private fun update(key: String, change: (Map<String, Any>) -> Map<String, Any>) = synchronized(this) {
        val state = state()
        file.writeAtomically(yaml.dump(state + (key to change(state[key].orEmpty()))))
    }
}

/** The Process's Schedule: the cron on its timer start event, or null when it starts only by hand. */
fun BpmnModelInstance.schedule(): String? = scheduleTimer()?.timeCycle?.textContent?.trim()?.ifEmpty { null }

/** The timer of the Process's start event, if it has one; a sub-process's start event is not a Schedule. */
fun BpmnModelInstance.scheduleTimer(): TimerEventDefinition? = getModelElementsByType(Process::class.java)
    .flatMap { it.getChildElementsByType(StartEvent::class.java) }
    .firstNotNullOfOrNull { it.getChildElementsByType(TimerEventDefinition::class.java).firstOrNull() }

/** A Schedule's cron in words, as the modeler writes it: every hour, day, weekday, week or month at a time, else the cron itself. */
fun inWords(cron: String): String {
    val fields = cron.trim().split(Regex("\\s+"))
    if (fields.size != 6 || fields[0] != "0" || fields[4] != "*") return "cron $cron"
    val (minute, hour, day) = fields.subList(1, 4)
    val weekday = fields[5]
    if (minute == "0" && hour == "*" && day == "*" && weekday == "*") return "Every hour"
    val m = minute.toIntOrNull()
    val h = hour.toIntOrNull()
    if (m == null || h == null || m !in 0..59 || h !in 0..23) return "cron $cron"
    val at = "at %02d:%02d".format(h, m)
    val days = mapOf("MON" to "Monday", "TUE" to "Tuesday", "WED" to "Wednesday", "THU" to "Thursday", "FRI" to "Friday", "SAT" to "Saturday", "SUN" to "Sunday")
    return when {
        day == "*" && weekday == "*" -> "Every day $at"
        day == "*" && weekday == "MON-FRI" -> "Every weekday $at"
        day == "*" && weekday in days -> "Every ${days[weekday]} $at"
        weekday == "*" && day.toIntOrNull() in 1..31 -> "Every month on day $day $at"
        else -> "cron $cron"
    }
}
