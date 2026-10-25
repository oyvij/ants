package ants

import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.operaton.bpm.model.bpmn.instance.ServiceTask

internal val BpmnModelInstance.scriptTasks get() = getModelElementsByType(ServiceTask::class.java).filter { it.operatonTopic == ScriptTask.TOPIC }

internal fun scriptTasks(scripts: Scripts, catalogue: Catalogue, model: BpmnModelInstance): List<Check> {
    val identified by lazy { catalogue.identified() + scripts.identified() }
    return model.scriptTasks.flatMap { task ->
        fun check(message: String) = Check(task.id, "${task.label}: $message")
        val value = task.property(SCRIPT)?.trim()?.takeIf(String::isNotEmpty) ?: return@flatMap listOf(Check(task.id, "${task.label} has no Script."))
        val kept = task.property(nameBeside(value))
        val script = runCatching { scripts.linked(value, kept) }.getOrNull() ?: return@flatMap listOf(check("No Script named ${kept ?: value}."))
        val undeclared = task.inputs().map { it.operatonName }.filter { it !in script.inputNames }
            .map { check("The Script ${script.name} doesn't declare the input $it.") }
        val where = scripts.where(script)
        val twins = identified.filter { it.id == script.id && it.where != where }.map {
            val remedy = if (it.kind == "Script") "Delete one of them." else "Delete the id of the ${it.kind} and restart Ants to give it a new one."
            check("The Script ${script.name} has the same id as the ${it.kind} ${it.name}, in ${it.where}. $remedy")
        }
        undeclared + twins
    }
}
