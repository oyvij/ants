package ants

import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.operaton.bpm.model.bpmn.instance.BaseElement
import org.operaton.bpm.model.bpmn.instance.ServiceTask

internal val BpmnModelInstance.scriptTasks get() = getModelElementsByType(ServiceTask::class.java).filter { it.operatonTopic == ScriptTask.TOPIC }

/** The Script this Script Task runs; null when it names none, or none the library has. */
internal fun ServiceTask.linkedScript(scripts: Scripts): Script? {
    val value = property(SCRIPT)?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return runCatching { scripts.linked(value, property(nameBeside(value))) }.getOrNull()
}

/** The inputs written as plain text, with no `${…}`, `#{…}` or nested value: name to text. A Script Task converts these to their type. */
internal fun BaseElement.literalInputs(): Map<String, String> = inputs()
    .filter { input -> input.domElement.childElements.isEmpty() && listOf("\${", "#{").none { it in input.textContent } }
    .associate { it.operatonName to it.textContent }

/** The dotted paths of every field the object schema [schema] declares, a nested object's fields after it. */
internal fun fieldPaths(schema: Map<*, *>): List<String> = (schema["properties"] as? Map<*, *>).orEmpty().flatMap { (name, value) ->
    listOf("$name") + (value as? Map<*, *>)?.takeIf { it["type"] == "object" }?.let(::fieldPaths).orEmpty().map { "$name.$it" }
}

internal fun scriptTasks(scripts: Scripts, catalogue: Catalogue, draftOnly: DraftOnly, model: BpmnModelInstance): List<Check> {
    val identified by lazy { catalogue.identified() + scripts.identified() }
    return model.scriptTasks.flatMap { task ->
        fun check(message: String) = Check(task.id, "${task.label}: $message")
        val value = task.property(SCRIPT)?.trim()?.takeIf(String::isNotEmpty) ?: return@flatMap listOf(Check(task.id, "${task.label} has no Script."))
        val kept = task.property(nameBeside(value))
        val script = task.linkedScript(scripts) ?: return@flatMap listOf(check(draftOnly.refusal(DraftKind.SCRIPT, value, kept) ?: "No Script named ${kept ?: value}."))
        val passed = task.inputs().map { it.operatonName }
        val undeclared = passed.filter { it !in script.inputNames }
            .map { check("The Script ${script.name} doesn't declare the input $it.") }
        val properties = (script.inputs["properties"] as? Map<*, *>).orEmpty()
        val mistyped = task.literalInputs().filterKeys { it in properties }.mapNotNull { (name, text) ->
            val property = properties[name] as? Map<*, *>
            ScriptSchema.literal(text, property).exceptionOrNull()?.let { check("The input $name is ${ScriptSchema.named(property)}, which $text isn't.") }
        }
        val missing = Settings.strings(script.inputs["required"]).orEmpty().filter { it !in passed }
            .map { check("The Script ${script.name} needs the input $it.") }
        val where = scripts.where(script)
        val twins = identified.filter { it.id == script.id && it.where != where }.map {
            val remedy = if (it.kind == "Script") "Delete one of them." else "Delete the id of the ${it.kind} and restart Ants to give it a new one."
            check("The Script ${script.name} has the same id as the ${it.kind} ${it.name}, in ${it.where}. $remedy")
        }
        undeclared + mistyped + missing + twins
    }
}
