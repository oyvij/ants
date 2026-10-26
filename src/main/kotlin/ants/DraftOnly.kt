package ants

import org.springframework.stereotype.Component

/**
 * The Agents, Skills and Scripts that are a Draft only, never published, which nothing can use: what Publish refuses a
 * Process or an Agent naming, and what the modeler's pickers offer marked.
 */
@Component
class DraftOnly(private val drafts: Drafts, private val catalogue: Catalogue, private val scripts: Scripts) {
    /** The Drafts of [kind] never published. */
    fun all(kind: DraftKind): List<Draft> {
        val published = when (kind) {
            DraftKind.AGENT -> catalogue.agents(withHidden = true).mapNotNull { it.id }
            DraftKind.SKILL -> catalogue.skills(withHidden = true).mapNotNull { it.id }
            DraftKind.SCRIPT -> scripts.all().map { it.id }
            DraftKind.PROCESS -> error("A Process isn't named by id")
        }.toSet()
        return drafts.all(kind).filter { it.id !in published }
    }

    /**
     * The Draft-only item of [kind] that [value] names, by its id, or by its name ([kept], the name kept beside the id,
     * when there is one); null when a published item answers to it, or no Draft does.
     */
    fun named(kind: DraftKind, value: String, kept: String? = null): Draft? {
        if (published(kind, value, kept)) return null
        val name = kept ?: value
        return all(kind).let { drafts -> drafts.firstOrNull { it.id == value } ?: drafts.firstOrNull { it.name == name || it.key == name } }
    }

    /** Why [value] can't be named yet: the sentence Publish refuses with when it names a Draft-only item of [kind], else null. */
    fun refusal(kind: DraftKind, value: String, kept: String? = null): String? = named(kind, value, kept)?.let { "The ${kind.noun} ${it.name} is a Draft: publish it first." }

    private fun published(kind: DraftKind, value: String, kept: String?) = when (kind) {
        DraftKind.AGENT -> runCatching { catalogue.linkedAgent(value, kept) }.isSuccess
        DraftKind.SKILL -> runCatching { catalogue.linkedSkill(value, kept) }.isSuccess
        DraftKind.SCRIPT -> runCatching { scripts.linked(value, kept) }.isSuccess
        DraftKind.PROCESS -> false
    }
}
