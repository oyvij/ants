package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.ui.Model
import org.springframework.web.server.ResponseStatusException
import java.net.URLEncoder
import java.nio.file.Path

/**
 * One item of a kind as its page shows it (ADR 0026): the [published] item, its [draft], or both, never neither. Its
 * page is at [name], the published item's name, else the Draft's key. A read-only item ([own] false) has no Draft.
 */
class DraftedItem<P : Any>(val kind: DraftKind, val published: P?, val draft: Draft?, val name: String, val own: Boolean) {
    val state get() = DraftState.of(published != null, draft != null)
    val path get() = "/${kind.folder}/$name"

    /** What the editor's header and its autosave need; null for a read-only item. */
    val head get() = if (own) DraftHead(state, draft?.version ?: 0, draft?.savedLine, "$path/draft") else null

    /** The key a Draft of the page is kept under: where its page is now. */
    val key get() = draft?.key ?: name
}

/** What publishing a page came to: [Published], on to its page, or [Refused], the page shown again with why. */
sealed interface PublishOutcome {
    /** The view the page's Publish answers with. */
    val view: String

    class Published(val path: String) : PublishOutcome {
        override val view get() = "redirect:$path"
    }

    class Refused(override val view: String) : PublishOutcome
}

/**
 * The Drafts of one kind beside its published items, as the kind's pages and the Checks use them: which items are a
 * Draft only, which name is taken, and the steps every page of the kind shares (New, autosave, Publish, Discard Draft).
 * [P] is the kind's published item, and the rest is what this needs to know of it: the published items the pages
 * show ([shown]), every published id, hidden ones too ([ids]), each one's [name] and [id], whether it is the user's
 * [own] (only those have a Draft), whether one answers to a value naming it ([links], by id or by the name kept beside
 * it), and the item a Draft-only Draft holds ([asItem]).
 */
class Drafted<P : Any>(
    val kind: DraftKind,
    private val drafts: Drafts,
    private val shown: () -> List<P>,
    private val ids: () -> Set<String>,
    private val name: (P) -> String,
    private val id: (P) -> String?,
    private val own: (P) -> Boolean,
    private val links: (value: String, kept: String?) -> Boolean,
    val asItem: (Draft) -> P,
) {
    /** The Drafts of items never published. */
    fun draftsOnly(): List<Draft> = ids().let { published -> drafts.all(kind).filter { it.id !in published } }

    /** Each Draft-only item as its Draft holds it, with that Draft. */
    fun draftOnlyItems(): List<Pair<Draft, P>> = draftsOnly().map { it to asItem(it) }

    /** The published [item]'s Draft, when it has one. */
    fun draftOf(item: P): Draft? = id(item)?.takeIf { own(item) }?.let { drafts.get(kind, it) }

    /**
     * The item whose page is at [name]: a published one, by [find] (by its name among those shown, else a 404), else a
     * Draft-only one by its key; else what [find] threw.
     */
    fun item(name: String, find: (String) -> P = { wanted -> shown().firstOrNull { this.name(it) == wanted } ?: throw notFound(wanted) }): DraftedItem<P> {
        val found = runCatching { find(name) }
        found.getOrNull()?.let { return DraftedItem(kind, it, draftOf(it), this.name(it), own(it)) }
        return draftsOnly().firstOrNull { it.key == name }?.let { DraftedItem(kind, null, it, it.key, true) } ?: throw found.exceptionOrNull()!!
    }

    /** The published [item] alone, as the Process Builder publishes over it, leaving its Draft as it is. */
    fun publishedOnly(item: P) = DraftedItem(kind, item, null, name(item), own(item))

    private fun notFound(name: String) = ResponseStatusException(HttpStatus.NOT_FOUND, "No ${kind.noun} named $name")

    /** Whether another item than the one with [id] has [name]: a published one, or a Draft-only one by its name or the page it is at. */
    fun taken(name: String, id: String?) = shown().any { this.name(it) == name && this.id(it) != id } || draftOnlyNamed(name, id)

    /** Whether a Draft-only item other than the one with [id] has [name], or its page is at it. */
    fun draftOnlyNamed(name: String, id: String?) = draftsOnly().any { it.id != id && (it.name == name || it.key == name) }

    /**
     * The Draft-only item that [value] names, by its id, or by its name ([kept], the name kept beside the id, when there
     * is one); null when a published item answers to it, or no Draft does.
     */
    fun named(value: String, kept: String? = null): Draft? {
        if (links(value, kept)) return null
        val wanted = kept ?: value
        return draftsOnly().let { all -> all.firstOrNull { it.id == value } ?: all.firstOrNull { it.name == wanted || it.key == wanted } }
    }

    /** Why [value] can't be named yet: the sentence Publish refuses with when it names a Draft-only item, else null. */
    fun refusal(value: String, kept: String? = null): String? = named(value, kept)?.let { "The ${kind.noun} ${it.name} is a Draft: publish it first." }

    /**
     * **New** or **Copy**: [content] as a Draft-only item with the [id] and [name] given, and on to its page; with
     * [use], as the modeler's modal opens it (`?use`, or `?use=<use>` when it says more).
     */
    fun create(id: String, name: String, content: String, use: String? = null): String {
        drafts.save(kind, DraftPage(id, name, content), DraftFrom.NEW)
        return "redirect:/${kind.folder}/$name" + use?.let { if (it.isEmpty()) "?use" else "?use=" + URLEncoder.encode(it, Charsets.UTF_8) }.orEmpty()
    }

    /**
     * The page's autosave: [content] as [item]'s Draft, kept under [id], with the [named] name (null for none it can
     * read), unless the Draft changed since [from]. A Draft-only item's page follows its name while it is [free].
     */
    fun autosave(item: DraftedItem<P>, id: String, named: String?, content: String, from: DraftFrom, free: (String) -> Boolean): ResponseEntity<Map<String, Any>> {
        val key = item.published?.let(name) ?: named?.takeIf { it == item.draft!!.key || free(it) } ?: item.draft!!.key
        val save = drafts.save(kind, DraftPage(id, key, named ?: key, content), from)
        return Drafts.answer(kind, save, DraftState.of(item.published != null, true), "/${kind.folder}/$key")
    }

    /**
     * **Publish**: keeps [content], named [named], as [item]'s Draft under [id] first, so a refusal keeps it, then
     * [publish]es it as the page's old Save did; once published, the Draft is removed.
     */
    fun publish(item: DraftedItem<P>, id: String, named: String, content: String, publish: () -> PublishOutcome): PublishOutcome {
        drafts.save(kind, DraftPage(id, item.key, named, content), DraftFrom(drafts.get(kind, id)?.version ?: 0))
        return publish().also { if (it is PublishOutcome.Published) drafts.remove(kind, id) }
    }

    /** [content], named [named], as [item]'s Draft under [id], over the Draft [item] was read with, from something other than its page. */
    fun edit(item: DraftedItem<P>, id: String, named: String, content: String): DraftSave =
        drafts.save(kind, DraftPage(id, item.key, named, content), DraftFrom(item.draft?.version ?: 0))

    /** Removes [item]'s Draft, as deleting the item does. */
    fun removeDraft(item: DraftedItem<P>) {
        (item.draft?.id ?: item.published?.let(id))?.let { drafts.remove(kind, it) }
    }

    /** **Discard Draft**: once [confirmed], removes [item]'s Draft and reopens the published item. */
    fun discard(item: DraftedItem<P>, confirmed: Boolean, model: Model): String =
        drafts.discard(kind, item.draft, item.published?.let { item.name }, item.name, item.path, item.path, confirmed, model)
}

/**
 * The Agents, Skills and Scripts each with their Drafts: what their pages share, and what Publish refuses a Process or
 * an Agent naming, a Draft-only item, which nothing can use.
 */
@Component
class DraftedItems(drafts: Drafts, catalogue: Catalogue, scripts: Scripts, @Value("\${ants.home}") home: Path) {
    private val agentFolder = home.resolve("agents")

    val agents = Drafted(
        DraftKind.AGENT, drafts, { catalogue.agents() }, { catalogue.agents(withHidden = true).mapNotNull { it.id }.toSet() }, Agent::name, Agent::id,
        { it.file.parent == agentFolder }, { value, kept -> runCatching { catalogue.linkedAgent(value, kept) }.isSuccess },
        { Agent(agentFolder.resolve("${it.key}.md"), it.content) },
    )

    val skills = Drafted(
        DraftKind.SKILL, drafts, { catalogue.skills() }, { catalogue.skills(withHidden = true).mapNotNull { it.id }.toSet() }, Skill::name, Skill::id,
        { it.file.startsWith(catalogue.yourSkills) }, { value, kept -> runCatching { catalogue.linkedSkill(value, kept) }.isSuccess },
        { draft ->
            val description = runCatching { frontmatter(draft.content).first["description"]?.toString() }.getOrNull().orEmpty()
            Skill(draft.key, description, catalogue.yourSkills.resolve(draft.key).resolve("SKILL.md"), id = draft.id)
        },
    )

    val scripts = Drafted(
        DraftKind.SCRIPT, drafts, { scripts.all() }, { scripts.all().map { it.id }.toSet() }, Script::name, Script::id,
        { true }, { value, kept -> runCatching { scripts.linked(value, kept) }.isSuccess },
        { ScriptForm.of(scriptFields(it)).script(it.id) },
    )
}
