package ants

import jakarta.annotation.PreDestroy
import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.externaltask.LockedExternalTask
import org.operaton.bpm.model.bpmn.instance.FlowElement
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.LocalDate
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** One question of a Round, as its asker's `output.questions` holds it, with the [answer] the page shows for it. */
class Question(val id: String, val title: String, val ask: String, val context: String, val recommendation: String, val why: String, val answer: String) {
    companion object {
        /** The questions of the asker's Result [result], each answered by [answers] (by id), else by its recommendation. */
        fun of(result: Any?, answers: Map<String, String> = emptyMap()): List<Question> =
            (((result as? Map<*, *>)?.get("output") as? Map<*, *>)?.get("questions") as? List<*>).orEmpty().map {
                val q = it as Map<*, *>
                fun text(key: String) = q[key]?.toString().orEmpty()
                Question(text("id"), text("title"), text("ask"), text("context"), text("recommendation"), text("why"), answers[text("id")] ?: text("recommendation"))
            }
    }
}

/**
 * The Round's Result: each question's answer, stamped by Ants, never by the Agent, with its [source]; by default the
 * user's: `accepted` when it is the recommendation, else `written`.
 */
fun answers(questions: List<Question>, source: (Question) -> String = { if (it.answer == it.recommendation) "accepted" else "written" }) = mapOf(
    "answers" to questions.map { mapOf("id" to it.id, "answer" to it.answer, "source" to source(it)) },
)

/** The Agent Task an answerer names as its asker: the one whose `output.questions` it answers and whose session it resumes. */
const val ASKER = "ants.asker"

/** Which Round the asker [asker] is on, seen from the execution [execution]: its run count in the Run's `runs`. */
fun RuntimeService.round(execution: String, asker: String) = ((getVariable(execution, "runs") as? Map<*, *>)?.get(asker) as? Number)?.toInt() ?: 1

/**
 * A Round answered for the user, by an answer-round Agent or Accept recommendations, so never kept in the Answer
 * History: each question of the asker [asker] gets its answer in [given] (by id), stamped `agent`, or else its
 * recommendation, stamped `recommended`. The Round goes to the Run log in [workspace] under the answerer's [name], and
 * its answers are left for the asker, whose next launch resumes its session with them, before [complete] runs with
 * them; they are taken back if it fails.
 */
fun RuntimeService.answerFor(workspace: Path, execution: String, name: String, asker: String, given: Map<String, String>, complete: (Map<String, Any>) -> Unit) {
    val questions = Question.of(getVariable(execution, asker), given)
    val answers = answers(questions) { if (it.id in given) "agent" else "recommended" }
    runLog(workspace, name, "Round ${round(execution, asker)}", roundLog(questions, answers))
    val sent = AgentLauncher.sentBack(workspace, taskKey(execution, asker))
    sent.parent.createDirectories()
    sent.writeText(JsonMapper.shared().writeValueAsString(answers))
    try {
        complete(answers)
    } catch (e: Exception) {
        sent.deleteIfExists()
        throw e
    }
}

/** A Round as the Run log and the Report list it: one line per question, its title, answer and source. */
fun roundLog(questions: List<Question>, answers: Map<String, Any>) = questions.zip(answers["answers"] as List<*>).joinToString("\n") { (q, a) ->
    "- ${q.title}: ${q.answer.trim().replace("\n", "\n  ")} (${(a as Map<*, *>)["source"]})"
}

/** The Rounds in the Run log of [workspace], in order: each one's Task and Round, and its [roundLog]. */
// ponytail: read back from the Run log, so a user's instruction holding a line like a Run log heading splits it there.
fun rounds(workspace: Path): List<Pair<String, String>> {
    val log = workspace.resolve("run-log.md").takeIf { it.exists() }?.readText() ?: return emptyList()
    return log.split(Regex("""(?m)^(?=## \S+ · )""")).mapNotNull { entry ->
        val heading = entry.substringBefore('\n').substringAfter(" · ")
        if (Regex(""".+ · Round \d+""").matches(heading)) heading to entry.substringAfter("\n\n").trim() else null
    }
}

/**
 * Keeps the user's own [questions] and answers of one Round in the Answer History in [home]: one markdown file per
 * Round (each question whole, its answer and source, the Process, Run, Task and date) and one line per question in
 * `INDEX.md`, its title linking to its anchor, a short context, the Process and the date.
 */
// ponytail: the Answer History is never pruned (the spec's accepted ceiling).
fun answerHistory(home: Path, process: String, run: String, task: String, key: String, round: Int, questions: List<Question>, answers: Map<String, Any>) {
    val folder = home.resolve("answer-history").createDirectories()
    val date = LocalDate.now().toString()
    val file = folder.resolve("$date-$run-$key-$round.md")
    file.writeText(
        "# $process · Run $run · $task · Round $round\n\n$date\n\n" +
            questions.zip(answers["answers"] as List<*>).joinToString("") { (q, a) ->
                "<a id=\"${q.id}\"></a>\n\n## ${q.title}\n\n${q.ask}\n\n**What it settles:** ${q.context}\n\n" +
                    "**Recommended:** ${q.recommendation}\n\n**Why:** ${q.why}\n\n**Answer** (${(a as Map<*, *>)["source"]}):\n\n${q.answer.trim()}\n\n"
            },
    )
    val index = folder.resolve("INDEX.md")
    if (!index.exists()) index.writeText("# Answer History\n\n")
    Files.writeString(
        index,
        questions.joinToString("") { "- [${it.title}](${file.fileName}#${it.id}) · ${short(it.context)} · $process · $date\n" },
        CREATE, APPEND,
    )
}

/** [text] on one line, cut to about a hundred characters. */
private fun short(text: String) = text.replace(Regex("""\s+"""), " ").trim().let { if (it.length > 100) it.take(99) + "…" else it }

/**
 * The Built-in Task Accept recommendations (topic `accept-recommendations`): it answers the Round of the asker it
 * names ([ASKER]) with every recommendation, stamped `recommended`, and resumes the asker with them ([answerFor]).
 */
@Component
class AcceptRecommendations(
    private val externalTasks: ExternalTaskService,
    private val runtime: RuntimeService,
    private val repository: RepositoryService,
    @Value("\${ants.home}") private val home: Path,
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

    private fun fetch() {
        try {
            externalTasks.fetchAndLock(10, WORKER).topic(TOPIC, 60_000).includeExtensionProperties().execute().forEach(::accept)
        } catch (e: Exception) {
            log.warn("Fetching Accept recommendations Tasks failed", e) // a failed fetch must not end the polling
        }
    }

    private fun accept(task: LockedExternalTask) {
        try {
            val asker = task.extensionProperties[ASKER] ?: error("${task.activityId} names no asker ($ASKER)")
            val run = runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult().rootProcessInstanceId
            val name = repository.getBpmnModelInstance(task.processDefinitionId).getModelElementById<FlowElement>(task.activityId)?.name ?: task.activityId
            runtime.answerFor(home.runWorkspace(run), task.executionId, name, asker, emptyMap()) { answers ->
                onClash { externalTasks.complete(task.id, WORKER, mapOf(task.activityId to answers)) }
            }
        } catch (e: Exception) {
            if (poller.isShutdown) return // the lock runs out, and the Task is tried again
            log.warn("Accept recommendations ${task.activityId} failed", e)
            runCatching { externalTasks.handleFailure(task.id, WORKER, "[failed] ${e.message ?: e}", null, 0, 0) }
        }
    }

    private companion object {
        const val TOPIC = "accept-recommendations"
        const val WORKER = "ants"
    }
}
