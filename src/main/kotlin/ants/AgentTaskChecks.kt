package ants

import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.operaton.bpm.model.bpmn.instance.Activity
import org.operaton.bpm.model.bpmn.instance.BaseElement
import org.operaton.bpm.model.bpmn.instance.BoundaryEvent
import org.operaton.bpm.model.bpmn.instance.FlowElement
import org.operaton.bpm.model.bpmn.instance.FlowNode
import org.operaton.bpm.model.bpmn.instance.SubProcess
import org.operaton.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics
import org.operaton.bpm.model.bpmn.instance.SequenceFlow
import org.operaton.bpm.model.bpmn.instance.ServiceTask
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonFormField
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonIn
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonInputOutput
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonInputParameter
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonProperties
import org.operaton.bpm.model.xml.instance.ModelElementInstance
import tools.jackson.databind.json.JsonMapper
import kotlin.io.path.exists
import kotlin.io.path.readText

// The Checks on Agent Tasks and on what a Process's expressions read, two rules of [Checks].

/** The value of an element's extension property [name], such as an Agent Task's `ants.skill`. */
fun BaseElement.property(name: String): String? = extensionElements?.elementsQuery?.filterByType(OperatonProperties::class.java)?.list().orEmpty()
    .flatMap { it.operatonProperties }.firstOrNull { it.operatonName == name }?.operatonValue

private val BaseElement.label get() = getAttributeValue("name")?.let { "$it ($id)" } ?: id

private val BpmnModelInstance.agentTasks get() = getModelElementsByType(ServiceTask::class.java).filter { it.operatonTopic == AgentLauncher.TOPIC }

private fun ServiceTask.inputs() = extensionElements?.elementsQuery?.filterByType(OperatonInputOutput::class.java)?.list().orEmpty().flatMap { it.operatonInputParameters }

/**
 * Each Agent Task's Agent and Skill: unknown or ambiguous names, a Skill the Agent doesn't list, a used Skill that
 * can't be found or a cycle of them, and an invalid result schema, the Skill's or the Task's override. A declared
 * input the Task doesn't fill is a warning.
 */
internal fun agentTasks(catalogue: Catalogue, model: BpmnModelInstance) = model.agentTasks.flatMap { task ->
    fun check(message: String) = Check(task.id, "${task.label}: $message")
    val agentName = task.property("ants.agent")?.takeIf(String::isNotBlank)
    val skillName = task.property("ants.skill")?.takeIf(String::isNotBlank)
    val agent = agentName?.let { runCatching { catalogue.agent(it) } }
    val skill = skillName?.let { runCatching { catalogue.skill(it) } }
    val found = skill?.getOrNull()
    val schema = found?.folder?.resolve("result.schema.json")?.takeIf { it.exists() }?.let { ResultContract.schemaErrors(it.readText()) }.orEmpty()
    val override = ResultContract.schemaErrors(task.property(RESULT_SCHEMA).orEmpty())
    val filled = task.inputs().map { it.operatonName }.toSet()
    listOfNotNull(
        if (agentName == null) Check(task.id, "${task.label} has no Agent.") else agent!!.exceptionOrNull()?.let { check("${it.message}.") },
        if (skillName == null) Check(task.id, "${task.label} has no Skill.") else skill!!.exceptionOrNull()?.let { check("${it.message}.") },
        agent?.getOrNull()?.takeIf { found != null && found.name !in it.skills }?.let { check("The Agent ${it.name} doesn't list the Skill ${found!!.name}.") },
        found?.let { runCatching { catalogue.uses(it) }.exceptionOrNull() }?.let { check("${it.message}.") },
        schema.takeIf { it.isNotEmpty() }?.let { check("The Skill ${found!!.name}'s result.schema.json is invalid: ${it.joinToString("; ")}") },
        override.takeIf { it.isNotEmpty() }?.let { check("Its result-schema override is invalid: ${it.joinToString("; ")}") },
    ) + found?.inputs.orEmpty().filter { it !in filled }.map { Check(task.id, "${task.label} has an empty input $it.", warning = true) }
}

/** The Task property holding a result-schema override, JSON text that replaces the Skill's `result.schema.json`. */
const val RESULT_SCHEMA = "ants.result-schema"

/** What an Agent Task promises in its Result's `output`: its override, else its Skill's schema; null when neither can be read. */
private fun promised(catalogue: Catalogue, task: ServiceTask): Map<*, *>? {
    task.property(RESULT_SCHEMA)?.takeIf(String::isNotBlank)?.let { return runCatching { JsonMapper.shared().readValue(it, Map::class.java) }.getOrNull() }
    return runCatching { catalogue.skill(task.property("ants.skill").orEmpty()).outputSchema }.getOrNull()
}

/** The names an expression may start with besides start-form fields and Task ids: Ants' Run variables and the engine's own. */
private val runVariables = setOf("runs", "clones", "item", "execution", "loopCounter", "nrOfInstances", "nrOfActiveInstances", "nrOfCompletedInstances")
private val juelWords = setOf("and", "or", "not", "eq", "ne", "lt", "gt", "le", "ge", "empty", "div", "mod", "true", "false", "null", "instanceof")

/** The names [expression] reads, such as `draft.output.text`: dotted names outside quotes, less a method called on them. */
internal fun reads(expression: String): List<List<String>> =
    Regex("""\$\{(.*?)}""", RegexOption.DOT_MATCHES_ALL).findAll(expression).flatMap { inside ->
        val code = inside.groupValues[1].replace(Regex("""'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*""""), "''")
        Regex("""(?<![\w.])[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*(\s*\()?""").findAll(code).map { match ->
            val names = match.value.substringBefore('(').trim().split('.')
            if (match.groupValues[1].isEmpty()) names else names.dropLast(1)
        }
    }.filter { it.isNotEmpty() && it[0] !in juelWords }.toList()

/**
 * Each `${…}` reference, in an input, a condition, a multi-instance collection or a call activity's input, naming a
 * Task, start-form field or Run variable that doesn't exist, or a field an Agent Task's Result doesn't have: its
 * `outcome`, its `summary` and the `output` its schema promises. More complex JUEL fails at runtime, as an incident.
 */
internal fun references(catalogue: Catalogue, model: BpmnModelInstance): List<Check> {
    val activities = model.getModelElementsByType(Activity::class.java).associateBy { it.id }
    val names = model.getModelElementsByType(OperatonFormField::class.java).map { it.operatonId }.toSet() + activities.keys + runVariables +
        model.getModelElementsByType(MultiInstanceLoopCharacteristics::class.java).mapNotNull { it.operatonElementVariable }
    return expressions(model).flatMap { (owner, text) -> reads(text).mapNotNull { path -> problem(catalogue, path, names, activities)?.let { owner to it } } }
        .distinct().map { (owner, problem) -> Check(owner.id, "${owner.label} reads $problem") }
}

/** Each expression in [model], in an input, a condition, a multi-instance collection or a call activity's input, and the element it is on. */
internal fun expressions(model: BpmnModelInstance): List<Pair<FlowElement, String>> {
    fun owner(element: ModelElementInstance) = generateSequence(element) { it.parentElement }.first { it is FlowElement } as FlowElement
    return model.getModelElementsByType(OperatonInputParameter::class.java).map { owner(it) to it.textContent } +
        model.getModelElementsByType(SequenceFlow::class.java).mapNotNull { flow -> flow.conditionExpression?.let { flow to it.textContent } } +
        model.getModelElementsByType(MultiInstanceLoopCharacteristics::class.java).mapNotNull { mi -> mi.operatonCollection?.let { owner(mi) to it } } +
        model.getModelElementsByType(OperatonIn::class.java).mapNotNull { binding -> binding.operatonSourceExpression?.let { owner(binding) to it } }
}

/** What's wrong with reading [path], or null when it names something that exists. */
private fun problem(catalogue: Catalogue, path: List<String>, names: Set<String>, activities: Map<String, Activity>): String? {
    val (root, field) = path[0] to path.getOrNull(1)
    val task = activities[root] as? ServiceTask
    return when {
        root !in names -> "$root, which is no Task, start-form field or Run variable."
        root == "runs" && field != null && field !in activities -> "runs.$field, which is no Task."
        task?.operatonTopic != AgentLauncher.TOPIC || field == null -> null
        field !in setOf("outcome", "summary", "output") -> "$root.$field, which $root's Result doesn't have."
        path.size < 3 -> null
        else -> {
            var schema = promised(catalogue, task) ?: return null
            if (schema.isEmpty()) return "${path.joinToString(".")}, but $root promises no result schema."
            for ((i, name) in path.drop(2).withIndex()) {
                val properties = schema["properties"] as? Map<*, *> ?: return null
                schema = properties[name] as? Map<*, *> ?: return "${path.take(3 + i).joinToString(".")}, which $root's output schema doesn't have."
            }
            null
        }
    }
}

/**
 * What an input picker on the Task [id] offers: the start-form fields, the Run variables, and the Results of the Tasks
 * before it, an Agent Task's by field (its `summary`, its `output` and each field its schema promises). A Task is
 * before it when a path leads from it to [id], so a loop puts later Tasks before it too; they read `null` until run.
 */
internal fun offered(catalogue: Catalogue, model: BpmnModelInstance, id: String): List<String> {
    val target = model.getModelElementById<FlowNode>(id) ?: return emptyList()
    val before = mutableSetOf<String>() // ids: the model hands out a new object for an element each time
    // From the sequence flows, since a file need not list a node's `incoming`.
    val incoming = model.getModelElementsByType(SequenceFlow::class.java).groupBy({ it.target.id }, { it.source })
    fun walk(node: FlowNode) {
        val sources = incoming[node.id].orEmpty() + listOfNotNull((node as? BoundaryEvent)?.attachedTo, node.parentElement as? SubProcess)
        for (source in sources) if (before.add(source.id)) walk(source)
    }
    walk(target)
    before -= generateSequence(target.parentElement) { it.parentElement }.filterIsInstance<SubProcess>().map { it.id }.toSet() // still running, no Result yet
    val fields = model.getModelElementsByType(OperatonFormField::class.java).map { it.operatonId }
    val results = model.getModelElementsByType(Activity::class.java).filter { it.id in before }
        .flatMap { task ->
            if (task !is ServiceTask || task.operatonTopic != AgentLauncher.TOPIC) listOf(task.id)
            else listOf("${task.id}.summary", "${task.id}.output") +
                (promised(catalogue, task)?.get("properties") as? Map<*, *>).orEmpty().keys.map { "${task.id}.output.$it" }
        }
    return (fields + listOf("runs", "clones") + results).distinct().map { "\${$it}" }
}
