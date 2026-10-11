package ants

import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity
import org.operaton.bpm.model.bpmn.instance.ServiceTask
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path

/** The Report's file in a Run workspace, kept by cleanup. */
const val REPORT = "report.md"

/**
 * The Report of the Run [run] as it ends, as markdown, written mechanically: its Process and how it ended, its start
 * form, each Agent Task's Result summary, tokens and Transcript link, read from the Transcripts in [workspace], and
 * each Round's answers and their sources, read from its Run log, its Spec and Tickets, and the Push Results.
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
    // The implementers' and fixers' own reviews: a Result's `output.review`, the last of each Task's launches.
    val reviews = transcripts.mapNotNull { (task, transcript) -> ((transcript.result?.get("output") as? Map<*, *>)?.get("review"))?.let { task to it } }
    if (reviews.isNotEmpty()) append("\n## Review\n\n")
    for ((task, review) in reviews) append("### $task\n\n${review as? String ?: JsonMapper.shared().writeValueAsString(review)}\n\n")
    append("\n" + ticketsReport(workspace, run.processInstanceId))
    append(pushes(run))
}

/** The Push Results of the Run [run], one line per repo, or nothing when no Push ran. */
private fun pushes(run: DelegateExecution): String {
    val results = run.bpmnModelInstance.getModelElementsByType(ServiceTask::class.java).filter { it.operatonTopic == Push.TOPIC }
        .flatMap { run.getVariable(it.id) as? List<*> ?: emptyList() }.map { it as Map<*, *> }
    if (results.isEmpty()) return ""
    return "\n## Push\n\n" + results.joinToString("") { repo ->
        if (repo["pushed"] != true) return@joinToString "- **${repo["repo"]}** · not pushed: ${"${repo["error"]}".replace('\n', ' ')}\n"
        val branch = (if (repo["isNew"] == true) "new branch" else "existing branch") + (repo["base"]?.let { " off `$it`" }.orEmpty())
        val commits = (repo["commits"] as List<*>).size.let { if (it == 1) "1 commit" else "$it commits" }
        val compare = repo["compareUrl"]?.let { " · [Compare]($it)" }.orEmpty()
        "- **${repo["repo"]}** · pushed `${repo["branch"]}` to `${repo["remote"]}` · $branch · $commits$compare\n"
    }
}
