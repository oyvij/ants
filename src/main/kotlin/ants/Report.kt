package ants

import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path

/** The Report's file in a Run workspace, kept by cleanup. */
const val REPORT = "report.md"

/**
 * The Report of the Run [run] as it ends, as markdown, written mechanically: its Process and how it ended, its start
 * form, each Agent Task's Result summary, tokens and Transcript link, read from the Transcripts in [workspace], and
 * each Round's answers and their sources, read from its Run log, and its Spec and Tickets.
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
    val rounds = rounds(workspace)
    if (rounds.isNotEmpty()) append("\n## Rounds\n\n")
    for ((heading, answers) in rounds) append("### $heading\n\n$answers\n\n")
    // The implementers' and fixers' own reviews: a Result's `output.review`, the last of each Task's launches.
    val reviews = transcripts.mapNotNull { (task, transcript) -> ((transcript.result?.get("output") as? Map<*, *>)?.get("review"))?.let { task to it } }
    if (reviews.isNotEmpty()) append("\n## Review\n\n")
    for ((task, review) in reviews) append("### $task\n\n${review as? String ?: JsonMapper.shared().writeValueAsString(review)}\n\n")
    append("\n" + ticketsReport(workspace, run.processInstanceId))
}
