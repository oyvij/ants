package ants

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.LocalDate
import kotlin.io.path.createDirectories
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
 * The Round's Result: each question's answer, stamped by Ants, never by the Agent: `accepted` when it is the
 * recommendation, else `written`.
 */
fun answers(questions: List<Question>) = mapOf(
    "answers" to questions.map { mapOf("id" to it.id, "answer" to it.answer, "source" to if (it.answer == it.recommendation) "accepted" else "written") },
)

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
