package ants

import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path

/** The Report's file in a Run workspace, kept by cleanup. */
const val REPORT = "report.md"

/**
 * The Report of the Run [run] as it ends, as markdown, written mechanically: its name, its Process and how it ended, its start
 * form, each Agent Task's Result summary, its session's context and Transcript link, read from the Transcripts in [workspace], and
 * each Round's answers and their sources, read from its Run log, its Spec and Tickets, the Push Results and the pull requests.
 */
fun report(run: DelegateExecution, workspace: Path): String = buildString {
    val definition = (run as ExecutionEntity).processDefinition
    val process = definition.name ?: definition.key
    append("# ${run.getVariable(NAME) ?: process}\n\n$process · ${if (run.isCanceled) "Cancelled" else "Completed"}\n\n")
    (run.getVariable(SCOPE) as? List<*>)?.let { scope -> append("Repos picked: ${scope.joinToString { "`$it`" }}.\n\n") }
    append("## Start form\n\n")
    for (field in run.processEngineServices.formService.getStartFormData(run.processDefinitionId).formFields) {
        val value = run.getVariable(field.id).let { if (it is List<*>) it.joinToString(", ") else it?.toString().orEmpty() }
        append("**${field.label ?: field.id}**\n\n$value\n\n")
    }
    val transcripts = Transcript.all(workspace)
    append("## Tasks\n\n") // each Task is its own session, so their contexts don't add up
    for ((task, transcript) in transcripts) {
        val result = transcript.result?.let { " · ${it["outcome"]}: ${it["summary"]}" }.orEmpty()
        append("- [$task](/runs/${run.processInstanceId}/tasks/$task/transcript)$result · ${transcript.context}\n")
    }
    append(roundsReport(workspace))
    // The implementers' and fixers' own reviews: a Result's `output.review`, the last of each Task's launches.
    val reviews = transcripts.mapNotNull { (task, transcript) -> ((transcript.result?.get("output") as? Map<*, *>)?.get("review"))?.let { task to it } }
    if (reviews.isNotEmpty()) append("\n## Review\n\n")
    for ((task, review) in reviews) append("### $task\n\n${review as? String ?: JsonMapper.shared().writeValueAsString(review)}\n\n")
    append("\n" + ticketsReport(workspace, run.processInstanceId))
    append(pushReport(run))
    // What the pr Tasks opened: a Result's `output.pullRequests`, the last of each Task's launches.
    val pullRequests = transcripts.values.flatMap { ((it.result?.get("output") as? Map<*, *>)?.get("pullRequests") as? List<*>).orEmpty() }.map { it as Map<*, *> }
    if (pullRequests.isNotEmpty()) append("\n## Pull requests\n\n" + pullRequests.joinToString("") { "- **${it["repo"]}** · [${it["url"]}](${it["url"]})\n" })
}
