package ants

import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Your Skills: the Skills the user added to each shipped Agent, each listed by id, kept in [folder] one file per Agent
 * by its fixed id, apart from the shipped agent file and every folder Ants runs from, so an update replacing the Agent
 * keeps them; and their Draft, in the Drafts store like any Agent's (ADR 0026). Only a shipped Agent with an id, as
 * [shipped] says, has them.
 */
class YourSkills(private val folder: Path, private val drafts: Drafts, private val shipped: (Agent) -> Boolean) {
    /** The id [agent]'s Your Skills are kept under: a shipped Agent's own; null for an Agent that has none. */
    private fun idOf(agent: Agent) = agent.id?.takeIf { shipped(agent) }

    private fun id(agent: Agent) = idOf(agent) ?: error("Only a shipped Agent has Your Skills")

    fun has(agent: Agent) = idOf(agent) != null

    /** The published Your Skills on [agent], each as it is listed (an id); empty for an Agent that has none. */
    fun published(agent: Agent): List<String> = idOf(agent)?.let { folder.resolve("$it.yaml") }?.takeIf { it.exists() }
        ?.let { runCatching { ((Yaml().load<Any?>(it.readText()) as? Map<*, *>)?.get("skills") as? List<*>)?.map(Any?::toString) }.getOrNull() }.orEmpty()

    /** Publishes [skills], each listed by id, as Your Skills on [agent]. */
    fun publish(agent: Agent, skills: List<String>) = folder.resolve("${id(agent)}.yaml").writeAtomically(yaml.dump(mapOf("skills" to skills)))

    fun draft(agent: Agent): Draft? = drafts.get(DraftKind.YOUR_SKILLS, id(agent))

    /** Your Skills on [agent] as its page shows them: their Draft's when they have one, else the published ones. */
    fun shown(agent: Agent): List<String> = draft(agent)?.let { words(it.content) } ?: published(agent)

    /** Keeps [skills], each listed by id, as the Draft of Your Skills on [agent], unless it changed since [from]. */
    fun saveDraft(agent: Agent, skills: List<String>, from: DraftFrom): DraftSave =
        drafts.save(DraftKind.YOUR_SKILLS, DraftPage(id(agent), agent.name, skills.joinToString(", ")), from)

    fun removeDraft(agent: Agent) = drafts.remove(DraftKind.YOUR_SKILLS, id(agent))
}
