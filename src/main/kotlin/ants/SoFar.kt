package ants

import jakarta.annotation.PreDestroy
import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.RuntimeService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.TimeUnit.SECONDS

/**
 * So far: every couple of minutes (settings' `so-far`), haiku rewrites each running Agent Task's new Transcript lines
 * into a short paragraph for its drawer. The paragraph goes into the Transcript as one of Ants' own lines, with its
 * tokens, so they count on the Task and the next rewrite starts from there.
 */
@Component
class SoFar(
    private val externalTasks: ExternalTaskService,
    private val runtime: RuntimeService,
    private val settings: Settings,
    private val launcher: AgentLauncher,
    @Value("\${ants.home}") private val home: Path,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val poller = Executors.newSingleThreadScheduledExecutor()
    private val tried = ConcurrentHashMap<Path, Instant>() // a failed rewrite waits its turn like a written one

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        poller.scheduleWithFixedDelay(::tick, 1, 1, SECONDS)
    }

    @PreDestroy
    fun shutdown() {
        poller.shutdownNow()
    }

    // ponytail: one rewrite at a time across all Runs, re-reading each running Task's whole Transcript every second;
    // it doesn't wait out a usage-limit pause yet.
    private fun tick() {
        try {
            val every = settings.soFar() ?: return
            for (task in externalTasks.createExternalTaskQuery().topicName(AgentLauncher.TOPIC).locked().withRetriesLeft().list()) {
                val run = runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult()?.rootProcessInstanceId ?: continue
                rewrite(Transcript.file(home.runWorkspace(run), runtime.taskKey(task.executionId, task.activityId)), every)
            }
        } catch (e: Exception) {
            log.warn("So far failed", e) // a failed tick must not end the polling
        }
    }

    /** Rewrites the So far of the Transcript [file] if [every] has passed since the last one, or since the launch. */
    private fun rewrite(file: Path, every: Duration) {
        val lines = Transcript.read(file).first.map { runCatching { json.readValue(it, Map::class.java) }.getOrNull() ?: emptyMap<Any, Any>() }
        val since = lines.lastOrNull { it.ants("so-far") || it.ants("launch") }?.let { Instant.parse(it["at"].toString()) } ?: return
        if (Duration.between(maxOf(since, tried[file] ?: since), Instant.now()) < every) return
        val last = lines.indexOfLast { it.ants("so-far") }
        val new = lines.drop(last + 1).mapNotNull(::said).joinToString("\n").takeLast(MAX)
        if (new.isBlank()) return

        val previous = lines.getOrNull(last)?.get("text") ?: "(none yet)"
        val prompt = "Rewrite the So far paragraph of an AI Agent's Task: what it has done and found so far, for the user who " +
            "waits on it, in at most four plain sentences. Answer with the paragraph alone.\n\nSo far until now:\n$previous\n\n" +
            "What the Agent did since, from its Transcript:\n$new\n"
        // With every slot taken, the rewrite waits for a later tick.
        val output = launcher.inSlot {
            tried[file] = Instant.now()
            val process = launcher.cli(
                "claude", file.parent,
                listOf(
                    "-p", "--model", "haiku", "--output-format", "json", "--tools", "", "--no-session-persistence", "--strict-mcp-config",
                    "--disable-slash-commands", "--settings", """{"disableAllHooks":true}""",
                ),
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process.outputWriter().use { it.write(prompt) }
            if (!process.waitFor(2, MINUTES)) process.destroyForcibly()
            process.inputReader().readLines()
        } ?: return
        // claude 2.1.295 prints one `result` line, its text in `result` and its tokens in `modelUsage`.
        val result = output.firstNotNullOfOrNull { line ->
            runCatching { json.readValue(line, Map::class.java) }.getOrNull()?.takeIf { it["type"] == "result" }
        }
        val paragraph = result?.get("result")?.toString()?.trim()
        if (result == null || result["is_error"] == true || paragraph.isNullOrEmpty()) return log.warn("So far for $file got no paragraph: $result")
        val tokens = (result["modelUsage"] as? Map<*, *>).orEmpty().values.filterIsInstance<Map<*, *>>()
            .sumOf { usage -> listOf("inputTokens", "outputTokens", "cacheReadInputTokens", "cacheCreationInputTokens").sumOf { (usage[it] as? Number)?.toLong() ?: 0 } }
        Transcript.Writer(file).ants("so-far", paragraph, tokens)
    }

    private fun Map<*, *>.ants(subtype: String) = this["type"] == "ants" && this["subtype"] == subtype

    /** What a Transcript line says, in a line or a few, or null for a line that says nothing to a reader. */
    private fun said(event: Map<*, *>): String? {
        val message = event["message"] as? Map<*, *>
        return when (event["type"]) {
            "user" -> (message?.get("content") as? String)?.let { "User: $it" }
            "assistant" -> (message?.get("content") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { block ->
                when (block["type"]) {
                    "text" -> block["text"]?.toString()
                    "tool_use" -> "Tool ${block["name"]}: " + (block["input"] as? Map<*, *>)?.values?.firstOrNull { it is String }?.toString().orEmpty().take(200)
                    else -> null
                }
            }.joinToString("\n")
            "result" -> (event["structured_output"] as? Map<*, *>)?.let { "Result: ${it["outcome"]}. ${it["summary"]}" }
            else -> null
        }?.takeIf(String::isNotBlank)
    }

    private companion object {
        const val MAX = 30_000 // characters of new Transcript text, the latest kept
        val json = JsonMapper.shared()
    }
}
