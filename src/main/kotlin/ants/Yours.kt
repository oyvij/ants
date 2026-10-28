package ants

import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class Yours(private val skillsFolder: Path, private val toolsFolder: Path, private val drafts: Drafts, private val shipped: (Agent) -> Boolean) {
    class Lists(val skills: List<String>, val tools: List<String>)

    private fun idOf(agent: Agent) = agent.id?.takeIf { shipped(agent) }

    private fun id(agent: Agent) = idOf(agent) ?: error("Only a shipped Agent has Your Skills and Your tools")

    fun has(agent: Agent) = idOf(agent) != null

    fun skills(agent: Agent): List<String> = published(skillsFolder, agent, "skills")

    fun tools(agent: Agent): List<String> = published(toolsFolder, agent, "tools")

    private fun published(folder: Path, agent: Agent, key: String): List<String> = idOf(agent)?.let { folder.resolve("$it.yaml") }?.takeIf { it.exists() }
        ?.let { runCatching { ((Yaml().load<Any?>(it.readText()) as? Map<*, *>)?.get(key) as? List<*>)?.map(Any?::toString) }.getOrNull() }.orEmpty()

    fun publishSkills(agent: Agent, skills: List<String>) = skillsFolder.resolve("${id(agent)}.yaml").writeAtomically(yaml.dump(mapOf("skills" to skills)))

    fun publishTools(agent: Agent, tools: List<String>) = toolsFolder.resolve("${id(agent)}.yaml").writeAtomically(yaml.dump(mapOf("tools" to tools)))

    fun draft(agent: Agent): Draft? = drafts.get(DraftKind.YOUR_SKILLS, id(agent))

    fun shown(agent: Agent): Lists {
        val content = draft(agent)?.content ?: return Lists(skills(agent), tools(agent))
        val drafted = runCatching { Yaml().load<Any?>(content) as? Map<*, *> }.getOrNull()
            ?: return Lists(words(content), tools(agent))
        fun list(key: String) = (drafted[key] as? List<*>)?.map(Any?::toString)
        return Lists(list("skills").orEmpty(), list("tools") ?: tools(agent))
    }

    fun saveDraft(agent: Agent, lists: Lists, from: DraftFrom): DraftSave =
        drafts.save(DraftKind.YOUR_SKILLS, DraftPage(id(agent), agent.name, yaml.dump(mapOf("skills" to lists.skills, "tools" to lists.tools))), from)

    fun removeDraft(agent: Agent) = drafts.remove(DraftKind.YOUR_SKILLS, id(agent))
}
