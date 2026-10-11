package ants

import jakarta.annotation.PreDestroy
import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.externaltask.LockedExternalTask
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicInteger

/**
 * The in-process worker. It fetches ready Agent Tasks (external tasks on topic `agent`) only for free slots under
 * the cap, oldest first across Runs, and runs each one's Skill through `claude -p`. A `done` Result completes the
 * task, stored as a process variable named after the Task's BPMN id; anything else is an incident.
 */
@Component
class AgentLauncher(
    private val externalTasks: ExternalTaskService,
    private val runtime: RuntimeService,
    private val settings: Settings,
    private val catalogue: Catalogue,
    @Value("\${ants.home}") private val home: Path,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val poller = Executors.newSingleThreadScheduledExecutor()
    private val processes = ConcurrentHashMap.newKeySet<Process>()
    private val live = AtomicInteger()

    /** How many Agent Tasks are running now. */
    val running get() = live.get()

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        // ponytail: one Ants per ANTS_HOME, so a lock left by the last one is a launch that died with it, and its Task
        // starts over; locks are long instead of extended, so this is also the only way a lock ever ends early.
        externalTasks.createExternalTaskQuery().topicName(TOPIC).locked().list().forEach { externalTasks.unlock(it.id) }
        poller.scheduleWithFixedDelay(::fetch, 0, 200, MILLISECONDS)
    }

    @PreDestroy
    fun stop() {
        poller.shutdownNow()
        processes.forEach(Process::destroy)
    }

    private fun fetch() {
        try {
            val free = settings.agentTasks() - live.get()
            if (free <= 0) return
            externalTasks.fetchAndLock().workerId(WORKER).maxTasks(free).orderByCreateTime().asc()
                .subscribe().topic(TOPIC, LOCK.toMillis()).localVariables().includeExtensionProperties().execute()
                .forEach { task ->
                    live.incrementAndGet()
                    Thread.ofVirtual().start {
                        try {
                            launch(task)
                        } finally {
                            live.decrementAndGet()
                        }
                    }
                }
        } catch (e: Exception) {
            log.warn("Fetching Agent Tasks failed", e) // a failed fetch must not end the polling
        }
    }

    private fun launch(task: LockedExternalTask) {
        val result = try {
            session(task)
        } catch (e: Exception) {
            log.warn("Agent Task ${task.activityId} failed", e)
            return externalTasks.handleFailure(task.id, WORKER, e.message ?: e.toString(), 0, 0)
        }
        externalTasks.complete(task.id, WORKER, mapOf(task.activityId to result))
    }

    /** Runs the Task's Skill in one CLI session and returns its Result. */
    private fun session(task: LockedExternalTask): Any {
        val agent = catalogue.agent(task.extensionProperties["ants.agent"] ?: error("${task.activityId} names no ants.agent"))
        val skill = catalogue.skill(task.extensionProperties["ants.skill"] ?: error("${task.activityId} names no ants.skill"))
        check(agent.cli == "claude") { "Agent ${agent.name} runs ${agent.cli}, and only claude runs for now" }
        val run = runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult().rootProcessInstanceId
        val workspace = home.resolve("runs").resolve(run)
        refuseBareByDefault(agent.cli, workspace)

        val prompt = "Follow the Skill in ${skill.file} for the workspace at $workspace.\n\n" + json.writeValueAsString(task.variables.toSortedMap())
        val process = cli(agent.cli, workspace, flags(agent, skill)).start()
        processes += process
        try {
            val stdin = process.outputWriter()
            stdin.write(json.writeValueAsString(mapOf("type" to "user", "message" to mapOf("role" to "user", "content" to prompt))))
            stdin.newLine()
            stdin.flush()
            var result: Map<*, *>? = null
            process.inputReader().forEachLine { line ->
                val event = runCatching { json.readValue(line, Map::class.java) }.getOrNull() ?: return@forEachLine
                if (event["type"] == "result") {
                    result = event
                    stdin.close() // the session ends once Ants holds its Result
                }
            }
            val exit = process.waitFor()
            check(exit == 0) { "${agent.cli} exited with code $exit" }
            return result?.get("structured_output") ?: error("${agent.cli} ended without a Result")
        } finally {
            processes -= process
        }
    }

    private fun flags(agent: Agent, skill: Skill): List<String> {
        val tools = (agent.tools ?: DEFAULT_TOOLS).filter { it.substringBefore('(') != "Skill" }
        return listOf(
            "-p",
            "--output-format", "stream-json", "--verbose", "--include-partial-messages",
            "--input-format", "stream-json", "--replay-user-messages",
            "--session-id", UUID.randomUUID().toString(),
            "--permission-mode", "dontAsk",
            "--setting-sources", "user,project",
            "--settings", """{"disableAllHooks":true}""",
            "--strict-mcp-config",
            "--disable-slash-commands",
            "--forward-subagent-text",
            "--tools", tools.map { it.substringBefore('(') }.distinct().joinToString(","),
            "--allowedTools", tools.joinToString(","),
            "--add-dir", skill.folder.toString(),
        ) + listOfNotNull(agent.model?.let { "--model" }, agent.model) +
            listOfNotNull(agent.instructions.ifEmpty { null }?.let { "--append-system-prompt" }, agent.instructions.ifEmpty { null })
    }

    /** The CLI's command prefix with [args], in [workspace], on the narrowed environment. */
    private fun cli(cli: String, workspace: Path, args: List<String>) = ProcessBuilder(settings.command(cli) + args)
        .directory(workspace.toFile())
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .apply {
            environment().keys.retainAll(settings.env(cli).toSet())
            environment() -= listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_RETRY_WATCHDOG")
            environment() += mapOf(
                "CLAUDE_CONFIG_DIR" to home.resolve("claude").toString(),
                "CLAUDE_CODE_DISABLE_BACKGROUND_TASKS" to "1",
                "CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH" to "1",
            )
        }

    /** `--bare` never reads Ants' login, so a `-p` that turns it on by default would fail every Task on auth. */
    private fun refuseBareByDefault(cli: String, workspace: Path) {
        val help = cli(cli, workspace, listOf("--help")).redirectErrorStream(true).start().run { inputReader().readText().also { waitFor() } }
        val bare = Regex("""^ *--bare\b.*?(?=^ {0,4}-|\z)""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)).find(help)?.value.orEmpty()
        check("default" !in bare.lowercase()) { "$cli -p now turns on --bare by default, which never reads Ants' login. Ants needs updating." }
    }

    companion object {
        const val TOPIC = "agent"
        private const val WORKER = "ants"
        private val LOCK = Duration.ofDays(365)
        private val DEFAULT_TOOLS = listOf("Bash", "Edit", "Glob", "Grep", "NotebookEdit", "Read", "TodoWrite", "WebFetch", "WebSearch", "Write")
        private val json = JsonMapper.shared()
    }
}
