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
 * are the ids of those that aren't the latest, and Skills. Ants never rewrites a Process.
 */
class Dependents(val processes: List<ProcessDefinition>, val older: Set<String> = emptySet(), val skills: List<Skill> = emptyList()) {
    val none get() = processes.isEmpty() && skills.isEmpty()
}

/** **Used by**: the deployed Processes whose Agent Tasks name an Agent or a Skill, and the Skills that use a Skill. */
@Component
class UsedBy(private val repository: RepositoryService, private val runtime: RuntimeService, private val catalogue: Catalogue) {
    fun agent(name: String) = dependents(processes(AGENT, name))

    fun skill(name: String) = dependents(processes(SKILL, name), catalogue.skills().filter { name in it.uses })

    fun process(key: String) = dependents(processes { process, model ->
        process.key != key && model.getModelElementsByType(CallActivity::class.java).any { it.calledElement == key }
    })

    private fun dependents(processes: List<ProcessDefinition>, skills: List<Skill> = emptyList()): Dependents {
        val latest = repository.createProcessDefinitionQuery().latestVersion().list().map { it.id }.toSet()
        return Dependents(processes, processes.map { it.id }.filter { it !in latest }.toSet(), skills)
    }

    /**
     * Each deployed Process version with an extension property [property] set to [name]: the latest, and any older
     * one that live Runs still run.
     */
    private fun processes(property: String, name: String) = processes { _, model ->
        model.getModelElementsByType(OperatonProperty::class.java).any { it.operatonName == property && it.operatonValue == name }
    }

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
