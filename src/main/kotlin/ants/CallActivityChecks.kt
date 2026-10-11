package ants

import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.operaton.bpm.model.bpmn.instance.Activity
import org.operaton.bpm.model.bpmn.instance.CallActivity
import org.operaton.bpm.model.bpmn.instance.Process
import org.operaton.bpm.model.bpmn.instance.StartEvent
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonIn

// The Checks on call activities, a kind of [Checks].

private val BpmnModelInstance.calls get() = getModelElementsByType(CallActivity::class.java)

/**
 * What each call activity in [model] needs of the Process it calls, which is bound `latest` and so is the deployed
 * one, or [model] itself: that it is deployed, that its required start-form fields are filled, that the Tasks read from
 * the call's Result are Tasks of it, and that no call leads back to [model]. Saving a Process others call runs those
 * callers' Checks on their calls of it, with [model] as what they call.
 */
internal fun calls(repository: RepositoryService, model: BpmnModelInstance): List<Check> {
    val process = model.getModelElementsByType(Process::class.java).firstOrNull() ?: return emptyList()
    val deployed = repository.createProcessDefinitionQuery().latestVersion().list().filter { it.key != process.id }
        .associate { it.key to repository.getBpmnModelInstance(it.id) }
    val processes = deployed + (process.id to model)
    val callers = deployed.values.flatMap { caller ->
        val name = caller.getModelElementsByType(Process::class.java).first().label
        needs(caller, processes) { it == process.id }.map { Check(null, "$name, which calls this Process: ${it.message}") }
    }
    return needs(model, processes) { true } + cycles(process.id, model, processes) + callers
}

/** What [model]'s calls of the Processes [calling] picks need of them, each called Process read from [processes]. */
private fun needs(model: BpmnModelInstance, processes: Map<String, BpmnModelInstance>, calling: (String) -> Boolean): List<Check> {
    val calls = model.calls.filter { calling(it.calledElement.orEmpty()) }
    val unfilled = calls.flatMap { call ->
        val key = call.calledElement?.takeIf(String::isNotBlank) ?: return@flatMap listOf(Check(call.id, "${call.label} calls no Process."))
        val called = processes[key] ?: return@flatMap listOf(Check(call.id, "${call.label} calls $key, which isn't deployed."))
        val filled = call.extensions<OperatonIn>()
            .filter { !(it.operatonSourceExpression ?: it.operatonSource).isNullOrBlank() }.map { it.operatonTarget }.toSet()
        called.getModelElementsByType(StartEvent::class.java).filter { it.parentElement is Process }
            .flatMap { it.formFields() }
            .filter { it.operatonId !in filled && it.required }
            .map { Check(call.id, "${call.label} leaves ${it.operatonLabel ?: it.operatonId} (${it.operatonId}), which $key requires, empty.") }
    }
    val byId = calls.associateBy { it.id }
    val reads = expressions(model).flatMap { (owner, text) ->
        reads(text).mapNotNull { path ->
            val call = byId[path[0]] ?: return@mapNotNull null
            val task = path.getOrNull(1) ?: return@mapNotNull null
            val called = processes[call.calledElement] ?: return@mapNotNull null
            if (called.getModelElementById<Activity>(task) != null) null
            else Check(owner.id, "${owner.label} reads ${call.id}.$task, which is no Task in ${call.calledElement}.")
        }
    }
    return unfilled + reads.distinct()
}

/** Each call in [model], the Process [key], that leads back to it, through the calls of [processes]. */
private fun cycles(key: String, model: BpmnModelInstance, processes: Map<String, BpmnModelInstance>): List<Check> {
    fun back(from: String, path: List<String>): List<String>? = when (from) {
        key -> path + from
        in path -> null // a cycle that doesn't pass through this Process is the Checks' of a Process in it
        else -> processes[from]?.calls.orEmpty().mapNotNull { it.calledElement }.firstNotNullOfOrNull { back(it, path + from) }
    }
    return model.calls.mapNotNull { call ->
        call.calledElement?.let { back(it, listOf(key)) }?.let { Check(call.id, "${call.label} makes a call cycle: ${it.joinToString(" → ")}.") }
    }
}
