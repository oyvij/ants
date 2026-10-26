package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.ui.Model
import org.springframework.web.server.ResponseStatusException
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/** What keeps Drafts, each kind in its own folder of the Drafts store. */
enum class DraftKind(val folder: String, val noun: String) {
    AGENT("agents", "Agent"), SKILL("skills", "Skill"), PROCESS("processes", "Process"), SCRIPT("scripts", "Script");

    /** What a save against a newer Draft is answered with. */
    val conflict get() = "This $noun's Draft was changed in another tab or window."
}

/**
 * An item's state, as its marker shows it in the lists and in its editor's header: a Draft only (never published, so
 * nothing can use it), Published, or Published with a Draft.
 */
enum class DraftState(val label: String, val css: String) {
    DRAFT_ONLY("Draft", "p-draft"),
    PUBLISHED("Published", "p-published"),
    CHANGED("Draft · changes not published", "p-changed");

    companion object {
        fun of(published: Boolean, draft: Boolean) = when {
            !published -> DRAFT_ONLY
            draft -> CHANGED
            else -> PUBLISHED
        }
    }
}

/**
 * An item's editor page as its Draft keeps it: the whole page as [content], in the kind's own format (an Agent's is its
 * agent file), the [name] the page has now, and the [key], the name the item's page is at while it is a Draft only.
 */
class DraftPage(val id: String, val key: String, val name: String, val content: String) {
    /** A new item's page, which is at its name. */
    constructor(id: String, name: String, content: String) : this(id, name, name, content)
}

/**
 * Where a save to the Drafts store comes from: a page that last loaded or saved the Draft [version], 0 for none. An
 * autosave also says which open page, the [tab], sent it, and the save's number there, [seq]: a page may send its next
 * save before the one on its way is answered, as it does when it is left.
 */
class DraftFrom(val version: Long, val tab: String? = null, val seq: Long = 0) {
    companion object {
        /** A new item, which has no Draft yet. */
        val NEW = DraftFrom(0)
    }
}

/**
 * One item's Draft: its [page], the [version] a save from a page that loaded this Draft carries, and the [tab] that
 * saved it with its save's number there, [seq].
 */
class Draft(val page: DraftPage, val version: Long, val saved: Instant, val tab: String? = null, val seq: Long = 0) {
    val id get() = page.id
    val key get() = page.key
    val name get() = page.name
    val content get() = page.content

    /** What the editor's header says of it: "Draft saved 14:02", with the day when it isn't today. */
    val savedLine get() = "Draft saved " + shortTime(saved)
}

/** What a save to the Drafts store came to: [Kept], or a [Conflict] with the [newer] Draft the page didn't load. */
sealed interface DraftSave {
    class Kept(val draft: Draft) : DraftSave
    class Conflict(val newer: Draft) : DraftSave
}

/**
 * The Drafts store: each Draft of a Process, Agent, Skill or Script the user owns, as `drafts/<kind>/<id>.yaml` in
 * ANTS_HOME, apart from every folder Ants reads from when it runs something, so nothing that runs ever sees one. It
 * keeps nothing in memory, so a Draft survives a restart.
 */
@Component
class Drafts(@Value("\${ants.home}") home: Path) {
    private val root = home.resolve("drafts")

    private fun file(kind: DraftKind, id: String) = root.resolve(kind.folder).resolve("$id.yaml")

    fun get(kind: DraftKind, id: String): Draft? = file(kind, id).takeIf { it.exists() }?.let(::read)

    fun all(kind: DraftKind): List<Draft> = root.resolve(kind.folder).takeIf { it.exists() }?.listDirectoryEntries("*.yaml")?.sorted()?.mapNotNull(::read).orEmpty()

    /** Every id a Draft holds, of any kind, so a new item never takes one. */
    fun ids(): Set<String> = DraftKind.entries.flatMap { all(it) }.map { it.id }.toSet()

    /**
     * Keeps [page] as its item's Draft, unless a Draft other than the one [from] last loaded or saved is there: then the
     * page didn't see it, and the save is a [DraftSave.Conflict]. A Draft the same tab saved is one it saw, so its
     * later save is kept and an earlier one, arriving late, changes nothing. With no Draft there is nothing to
     * overwrite, so any save is kept.
     */
    @Synchronized
    fun save(kind: DraftKind, page: DraftPage, from: DraftFrom): DraftSave {
        val before = get(kind, page.id)
        val sameTab = from.tab != null && before?.tab == from.tab
        if (before != null && sameTab && from.seq <= before.seq) return DraftSave.Kept(before)
        if (before != null && !sameTab && before.version != from.version) return DraftSave.Conflict(before)
        val now = Instant.now()
        val draft = Draft(page, maxOf(now.toEpochMilli(), (before?.version ?: 0) + 1), now, from.tab, from.seq)
        val kept = linkedMapOf<String, Any?>("key" to page.key, "name" to page.name, "version" to draft.version, "saved" to now.toString())
        if (from.tab != null) kept += mapOf("tab" to from.tab, "seq" to from.seq)
        file(kind, page.id).writeAtomically(yaml.dump(kept + ("content" to page.content)))
        return DraftSave.Kept(draft)
    }

    /**
     * **Discard Draft** of an item: asks first, at [page]`/discard`, then removes its [draft] and goes [back] to the
     * published item, [published] (its name as the question says it). [name]d by its page, an item never published
     * has its Delete instead.
     */
    fun discard(kind: DraftKind, draft: Draft?, published: String?, name: String, page: String, back: String, confirmed: Boolean, model: Model): String {
        draft ?: return "redirect:$back"
        if (published == null) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$name was never published: Delete it instead.")
        if (!confirmed) return confirm(model, "Discard the Draft of $published?", Dependents(emptyList()), "$page/discard", emptyMap(), back)
        remove(kind, draft.id)
        return "redirect:$back"
    }

    /** The folder of the files a Draft keeps beside its content, such as those beside a copied Skill's `SKILL.md`. */
    fun files(kind: DraftKind, id: String): Path = root.resolve(kind.folder).resolve(id)

    @Synchronized
    fun remove(kind: DraftKind, id: String) {
        file(kind, id).deleteIfExists()
        files(kind, id).toFile().deleteRecursively()
    }

    private fun read(file: Path): Draft? = runCatching {
        val map = Yaml().load<Map<*, *>>(file.readText())
        val page = DraftPage(file.nameWithoutExtension, map["key"].toString(), map["name"].toString(), map["content"].toString())
        Draft(page, map["version"].toString().toLong(), Instant.parse(map["saved"].toString()), map["tab"]?.toString(), map["seq"]?.toString()?.toLong() ?: 0)
    }.getOrNull()

    companion object {
        /**
         * What an autosave answers, as `draft.js` reads it: the [draft]'s version, what the header says, the item's
         * page [path] and its marker, from [state]; or a 409 with why when the save met a newer Draft.
         */
        fun answer(kind: DraftKind, save: DraftSave, state: DraftState, path: String): ResponseEntity<Map<String, Any>> = when (save) {
            is DraftSave.Kept -> ResponseEntity.ok(
                mapOf("version" to save.draft.version.toString(), "saved" to save.draft.savedLine, "path" to path, "marker" to state.css, "label" to state.label),
            )
            is DraftSave.Conflict -> ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("conflict" to kind.conflict, "saved" to save.newer.savedLine))
        }
    }
}

/** [time] as a page says when something happened: 14:02 today, else 3 Nov 14:02. */
fun shortTime(time: Instant): String = time.atZone(ZoneId.systemDefault()).let { if (it.toLocalDate() == LocalDate.now()) "%tR".format(it) else "%1\$te %1\$tb %1\$tR".format(it) }

/**
 * What an editor's header shows of its item's Draft (`drafthead.kte`) and what its autosave (`draft.js`) needs: the
 * marker's [state], the [version] the page loaded (0 for no Draft), when the Draft was [saved], and the [endpoint] the
 * page saves to.
 */
class DraftHead(val state: DraftState, val version: Long, val saved: String?, val endpoint: String)
