package ants

import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.repository.ProcessDefinition
import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.operaton.bpm.model.bpmn.instance.CallActivity
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonProperty
import org.springframework.stereotype.Component
import org.springframework.ui.Model

class Dependents(val processes: List<ProcessDefinition>, val older: Set<String> = emptySet(), val agents: List<String> = emptyList()) {
    val none get() = processes.isEmpty() && agents.isEmpty()
}

@Component
class UsedBy(private val repository: RepositoryService, private val runtime: RuntimeService, private val catalogue: Catalogue, private val scripts: Scripts) {
    fun agent(name: String): Dependents {
        val agent = runCatching { catalogue.agent(name) }.getOrNull()
        return dependents(processes { _, model ->
            model.agentTasks.any { task -> task.property(AGENT) == name || agent != null && runCatching { Links(task).agent(catalogue).file == agent.file }.getOrDefault(false) }
        })
    }

    /** What uses the Skill [name]: the Processes whose Tasks list it, and the Agents that list it, in their own Skills or in Your Skills (not those with every Skill, `*`). */
    fun skill(name: String): Dependents {
        val skill = runCatching { catalogue.skill(name) }.getOrNull()
        val agents = skill?.let { catalogue.agents().filter { agent -> !agent.allSkills && catalogue.allows(agent, it) }.map { it.name } }.orEmpty()
        val processes = dependents(processes { _, model ->
            model.agentTasks.any { task -> name in Job(task).listed.orEmpty() || skill != null && Links(task).skills(catalogue).orEmpty().any { it.getOrNull()?.file == skill.file } }
        })
        return Dependents(processes.processes, processes.older, agents)
    }

    fun script(script: Script): Dependents {
        val agents = catalogue.agents().filter { it.allows(script) }.map { it.name }
        val processes = processes { _, model -> model.properties(SCRIPT).any { it == script.id } || model.agentTasks.any { task -> Links(task).scripts(scripts).orEmpty().any { it.getOrNull()?.id == script.id } } }
        return dependents(processes).let { Dependents(it.processes, it.older, agents) }
    }

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
