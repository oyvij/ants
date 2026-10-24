package ants

import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.repository.ProcessDefinition
import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.operaton.bpm.model.bpmn.instance.CallActivity
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonProperty
import org.springframework.stereotype.Component
import org.springframework.ui.Model

/**
 * What uses an Agent or a Skill, and so would break if it were renamed or deleted: Process versions, of which [older]
 * are the ids of those that aren't the latest. Ants never rewrites a Process.
 */
class Dependents(val processes: List<ProcessDefinition>, val older: Set<String> = emptySet()) {
    val none get() = processes.isEmpty()
}

/** **Used by**: the deployed Processes whose Agent Tasks name an Agent, or list a Skill. */
@Component
class UsedBy(private val repository: RepositoryService, private val runtime: RuntimeService) {
    fun agent(name: String) = dependents(processes { _, model -> model.properties(AGENT).any { it == name } })

    fun skill(name: String) = dependents(processes { _, model -> model.properties(SKILLS).any { name in words(it) } })

    fun process(key: String) = dependents(processes { process, model ->
        process.key != key && model.getModelElementsByType(CallActivity::class.java).any { it.calledElement == key }
    })

    private fun dependents(processes: List<ProcessDefinition>): Dependents {
        val latest = repository.createProcessDefinitionQuery().latestVersion().list().map { it.id }.toSet()
        return Dependents(processes, processes.map { it.id }.filter { it !in latest }.toSet())
    }

    /** The values of the extension properties [name] in [this]. */
    private fun BpmnModelInstance.properties(name: String) = getModelElementsByType(OperatonProperty::class.java).filter { it.operatonName == name }.map { it.operatonValue.orEmpty() }

    /** Each deployed Process version that [uses]: the latest, and any older one that live Runs still run. */
    private fun processes(uses: (ProcessDefinition, BpmnModelInstance) -> Boolean): List<ProcessDefinition> {
        val latest = repository.createProcessDefinitionQuery().latestVersion().list().map { it.id }.toSet()
        return repository.createProcessDefinitionQuery().orderByProcessDefinitionName().asc().orderByProcessDefinitionVersion().desc().list()
            .filter { it.id in latest || runtime.createProcessInstanceQuery().processDefinitionId(it.id).count() > 0 }
            .filter { uses(it, repository.getBpmnModelInstance(it.id)) }
    }
}

/** The page asking to confirm [question], listing the [dependents] that would break; confirming re-posts [fields] to [action]. */
fun confirm(model: Model, question: String, dependents: Dependents, action: String, fields: Map<String, String>, back: String): String {
    model.addAttribute("question", question)
    model.addAttribute("dependents", dependents)
    model.addAttribute("action", action)
    model.addAttribute("fields", fields)
    model.addAttribute("back", back)
    return "confirm"
}
