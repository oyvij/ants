package ants

import jakarta.annotation.PreDestroy
import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.externaltask.ExternalTaskQueryTopicBuilder
import org.operaton.bpm.engine.externaltask.LockedExternalTask
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * The worker of a Built-in Task, [name]: on one thread, every 200 ms, it fetches the external tasks on [topic], locked
 * for [lock], with what [query] asks for too, and [handle]s each. A Task [handle] throws on is `[failed]`, with the
 * message, unless Ants is stopping, when the lock runs out and the Task is tried again.
 */
abstract class BuiltInWorker(
    private val externalTasks: ExternalTaskService,
    private val name: String,
    private val topic: String,
    private val lock: Duration,
    private val query: (ExternalTaskQueryTopicBuilder) -> ExternalTaskQueryTopicBuilder = { it },
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val poller = Executors.newSingleThreadScheduledExecutor()

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        poller.scheduleWithFixedDelay(::fetch, 0, 200, MILLISECONDS)
    }

    @PreDestroy
    fun shutdown() {
        poller.shutdownNow()
    }

    protected abstract fun handle(task: LockedExternalTask)

    private fun fetch() {
        try {
            query(externalTasks.fetchAndLock(10, WORKER).topic(topic, lock.toMillis())).execute().forEach(::run)
        } catch (e: Exception) {
            log.warn("Fetching $name Tasks failed", e) // a failed fetch must not end the polling
        }
    }

    private fun run(task: LockedExternalTask) {
        try {
            handle(task)
        } catch (e: Exception) {
            if (poller.isShutdown) return
            log.warn("$name ${task.activityId} failed", e)
            runCatching { externalTasks.handleFailure(task.id, WORKER, "[failed] ${e.message ?: e}", null, 0, 0) }
        }
    }

    companion object {
        const val WORKER = "ants"
    }
}
