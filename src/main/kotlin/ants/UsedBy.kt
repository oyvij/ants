package ants

import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.repository.ProcessDefinition
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonProperty
import org.springframework.stereotype.Component
import org.springframework.ui.Model

/** What uses an Agent or a Skill, and so would break if it were renamed or deleted. Ants never rewrites a Process. */
class Users(val processes: List<ProcessDefinition>, val skills: List<Skill> = emptyList()) {
    val none get() = processes.isEmpty() && skills.isEmpty()
}

/** **Used by**: the deployed Processes whose Agent Tasks name an Agent or a Skill, and the Skills that use a Skill. */
@Component
class UsedBy(private val repository: RepositoryService, private val catalogue: Catalogue) {
    fun agent(name: String) = Users(processes("ants.agent", name))

    fun skill(name: String) = Users(processes("ants.skill", name), catalogue.skills().filter { name in it.uses })

    /** The latest version of each deployed Process with an extension property [property] set to [name]. */
    private fun processes(property: String, name: String) =
        repository.createProcessDefinitionQuery().latestVersion().orderByProcessDefinitionName().asc().list().filter { process ->
            repository.getBpmnModelInstance(process.id).getModelElementsByType(OperatonProperty::class.java)
                .any { it.operatonName == property && it.operatonValue == name }
        }
}

/** The page asking to confirm [question], listing the [users] that would break; confirming re-posts [fields] to [action]. */
fun confirm(model: Model, question: String, users: Users, action: String, fields: Map<String, String>, back: String): String {
    model.addAttribute("question", question)
    model.addAttribute("users", users)
    model.addAttribute("action", action)
    model.addAttribute("fields", fields)
    model.addAttribute("back", back)
    return "confirm"
}
