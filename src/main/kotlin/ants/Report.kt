package ants

import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity
import java.nio.file.Path

/** The Report's file in a Run workspace, kept by cleanup. */
const val REPORT = "report.md"

/**
 * The Report of the Run [run] as it ends, as markdown, written mechanically: its Process and how it ended, its start
 * form, each Agent Task's Result summary, tokens and Transcript link, read from the Transcripts in [workspace], and
 * each Round's answers and their sources, read from its Run log.
 */
fun report(run: DelegateExecution, workspace: Path): String = buildString {
    val definition = (run as ExecutionEntity).processDefinition
    append("# ${definition.name ?: definition.key}\n\n${if (run.isCanceled) "Cancelled" else "Completed"}\n\n## Start form\n\n")
    for (field in run.processEngineServices.formService.getStartFormData(run.processDefinitionId).formFields) {
        val value = run.getVariable(field.id).let { if (it is List<*>) it.joinToString(", ") else it?.toString().orEmpty() }
        append("**${field.label ?: field.id}**\n\n$value\n\n")
    }
    val transcripts = Transcript.all(workspace)
    append("## Tasks · ${tokens(transcripts.values.sumOf { it.tokens })}\n\n")
    for ((task, transcript) in transcripts) {
        val result = transcript.result?.let { " · ${it["outcome"]}: ${it["summary"]}" }.orEmpty()
        append("- [$task](/runs/${run.processInstanceId}/tasks/$task/transcript)$result · ${tokens(transcript.tokens)}\n")
    }
    // Each Round's answers ([roundLog]), split into the user's own and the ones they didn't give, by the source each ends on.
    val rounds = rounds(workspace).map { (heading, answers) ->
        heading to answers.split(Regex("""\n(?=- )""")).partition { Regex("""\((accepted|written)\)$""").containsMatchIn(it) }
    }
    for ((title, mine) in listOf("Rounds" to true, "Answers you didn't give" to false)) {
        val shown = rounds.map { (heading, split) -> heading to if (mine) split.first else split.second }.filter { it.second.isNotEmpty() }
        if (shown.isNotEmpty()) append("\n## $title\n\n")
        for ((heading, answers) in shown) append("### $heading\n\n${answers.joinToString("\n")}\n\n")
    }
}
