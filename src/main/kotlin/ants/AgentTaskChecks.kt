package ants

import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.operaton.bpm.model.bpmn.instance.Activity
import org.operaton.bpm.model.bpmn.instance.BaseElement
import org.operaton.bpm.model.bpmn.instance.BoundaryEvent
import org.operaton.bpm.model.bpmn.instance.CallActivity
import org.operaton.bpm.model.bpmn.instance.FlowElement
import org.operaton.bpm.model.bpmn.instance.FlowNode
import org.operaton.bpm.model.bpmn.instance.SubProcess
import org.operaton.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics
import org.operaton.bpm.model.bpmn.instance.SequenceFlow
import org.operaton.bpm.model.bpmn.instance.ServiceTask
import org.operaton.bpm.model.bpmn.instance.UserTask
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonFormField
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonIn
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonInputParameter
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonProperties
import org.operaton.bpm.model.xml.instance.ModelElementInstance
import tools.jackson.databind.json.JsonMapper

// The Checks on Agent Tasks and on what a Process's expressions read, two kinds of [Checks].

// An Agent Task's extension properties.
const val AGENT = "ants.agent"
/** The Skills a Task may use, comma or space separated, an empty list none; without it, its Agent's (ADR 0024). */
const val SKILLS = "ants.skills"
/** What a Task asks its Agent to do, the job, in a few lines (ADR 0024). */
const val ASSIGNMENT = "ants.assignment"
/** "true" when the flow coming back to the Task resumes its last session. */
const val RESUME = "ants.resume"
/** "true" when the Task gets the Vault and the Source map. */
const val VAULT = "ants.vault"
/** "true" when the Task gets the Answer History. */
const val ANSWER_HISTORY = "ants.answer-history"
/** JSON text: the JSON Schema of the `output` the Task's Result promises; without it, any output. */
const val RESULT_SCHEMA = "ants.result-schema"

/** The value of an element's extension property [name], such as an Agent Task's [AGENT]. */
fun BaseElement.property(name: String): String? = extensions<OperatonProperties>().flatMap { it.operatonProperties }.firstOrNull { it.operatonName == name }?.operatonValue

private val BpmnModelInstance.agentTasks get() = getModelElementsByType(ServiceTask::class.java).filter { it.operatonTopic == AgentLauncher.TOPIC }

/**
 * Each Agent Task's Agent and Skills: unknown or ambiguous names, no Assignment and not exactly one Skill listed on the
 * Task, a listed Skill the Agent doesn't list (ADR 0024), and an invalid result schema.
 */
internal fun agentTasks(catalogue: Catalogue, model: BpmnModelInstance) = model.agentTasks.flatMap { task ->
    fun check(message: String) = Check(task.id, "${task.label}: $message")
    val agentName = task.property(AGENT)?.takeIf(String::isNotBlank)
    val agent = agentName?.let { runCatching { catalogue.agent(it) } }
    val listed = listed(task).map { runCatching { catalogue.skill(it) } }
    val assignment = !task.property(ASSIGNMENT).isNullOrBlank()
    val schema = ResultContract.schemaErrors(task.property(RESULT_SCHEMA).orEmpty())
    val lacking = agent?.getOrNull()?.let { a -> listed.mapNotNull { it.getOrNull() }.filter { !a.allows(it.name) }.map { check("The Agent ${a.name} doesn't list the Skill ${it.name}.") } }
    listOfNotNull(
        if (agentName == null) Check(task.id, "${task.label} has no Agent.") else agent!!.exceptionOrNull()?.let { check("${it.message}.") },
        Check(task.id, "${task.label} has no Assignment and doesn't list exactly one Skill.").takeIf { !assignment && listed.size != 1 },
    ) + listed.mapNotNull { it.exceptionOrNull()?.let { e -> check("${e.message}.") } } + lacking.orEmpty() +
        listOfNotNull(schema.takeIf { it.isNotEmpty() }?.let { check("Its result schema is invalid: ${it.joinToString("; ")}") })
}

/** The Skills an Agent Task lists itself, its [SKILLS]. */
private fun listed(task: ServiceTask) = task.property(SKILLS)?.let(::words).orEmpty()

/** What an Agent Task promises in its Result's `output`: its result schema, with `questions` when it [asksRounds]; null when it can't be read. */
private fun promised(task: ServiceTask): Map<*, *>? {
    val output = task.property(RESULT_SCHEMA)?.takeIf(String::isNotBlank)?.let { runCatching { JsonMapper.shared().readValue(it, Map::class.java) }.getOrNull() ?: return null }
        ?: emptyMap<String, Any?>()
    return if (asksRounds(task)) withQuestions(output) else output
}

/** The names an expression anywhere may start with besides start-form fields and Task ids: Ants' Run variables and the engine's own. */
private val runVariables = RUN_VARIABLES + "execution"

/** What an instance of a multi-instance reads besides its element variable. */
private val loopVariables = setOf("loopCounter", "nrOfInstances", "nrOfActiveInstances", "nrOfCompletedInstances")

/** The multi-instances [element] runs as an instance of, itself or a sub-process it is in. */
internal fun loops(element: ModelElementInstance) =
    generateSequence(element) { it.parentElement }.mapNotNull { (it as? Activity)?.loopCharacteristics as? MultiInstanceLoopCharacteristics }.toList()

/** The variable each instance reads its item from: the one the loop names, else `item`. */
private val MultiInstanceLoopCharacteristics.element get() = operatonElementVariable?.takeIf(String::isNotBlank) ?: "item"
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
internal fun references(model: BpmnModelInstance): List<Check> {
    val activities = model.getModelElementsByType(Activity::class.java).associateBy { it.id }
    val names = model.getModelElementsByType(OperatonFormField::class.java).map { it.operatonId }.toSet() + activities.keys + runVariables
    return expressions(model).flatMap { (owner, text) ->
        val inScope = names + loops(owner).flatMap { listOf(it.element) + loopVariables }
        reads(text).mapNotNull { path -> problem(path, inScope, activities)?.let { owner to it } }
    }
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
private fun problem(path: List<String>, names: Set<String>, activities: Map<String, Activity>): String? {
    val (root, field) = path[0] to path.getOrNull(1)
    val task = activities[root] as? ServiceTask
    return when {
        root !in names -> "$root, which is no Task, start-form field or Run variable."
        root == "runs" && field != null && field !in activities -> "runs.$field, which is no Task."
        task?.operatonTopic != AgentLauncher.TOPIC || field == null -> null
        field !in setOf("outcome", "summary", "output") -> "$root.$field, which $root's Result doesn't have."
        path.size < 3 -> null
        else -> {
            var schema = promised(task) ?: return null
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
 * What an input picker on the Task [id] offers: the start-form fields, the Run variables, the element variable of each
 * multi-instance it runs in, and the Results of the Tasks before it, an Agent Task's by field (its `summary`, its
 * `output` and each field its schema promises), a User Task's by its kind's Result fields, and a call activity's by the
 * [called] Process's Task. The modeler's condition builder offers those of a decision too. A Task is before
 * it when a path leads from it to [id], so a loop puts later Tasks before it too; they read `null` until run.
 */
internal fun offered(model: BpmnModelInstance, id: String, called: (String) -> BpmnModelInstance?): List<String> {
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
    fun results(task: Activity, key: String): List<String> = when {
        task is UserTask -> listOf(key) + UserTaskKind.of(task.property(USER_TASK)).result.map { "$key.$it" }
        task !is ServiceTask || task.operatonTopic != AgentLauncher.TOPIC -> listOf(key)
        else -> listOf("$key.summary", "$key.output") + (promised(task)?.get("properties") as? Map<*, *>).orEmpty().keys.map { "$key.output.$it" }
    }
    val results = model.getModelElementsByType(Activity::class.java).filter { it.id in before && it.givesResult }.flatMap { task ->
        val process = (task as? CallActivity)?.calledElement?.let(called)
        process?.getModelElementsByType(Activity::class.java)?.filter { it.givesResult }?.flatMap { results(it, "${task.id}.${it.id}") } ?: results(task, task.id)
    }
    return (fields + RUN_VARIABLES + loops(target).map { it.element } + results).distinct().map { "\${$it}" }
}

/** Whether a Result is kept under the activity's id: anything but a sub-process that runs once. */
private val Activity.givesResult get() = this !is SubProcess || loopCharacteristics != null
