package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
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
 * One item's Draft: its editor's whole page as [content], in the kind's own format (an Agent's is its agent file).
 * [name] is the name the page has now, [key] the name the item's page is at while it is a Draft only, and [version]
 * what a save from a page that loaded this Draft carries.
 */
class Draft(val id: String, val key: String, val name: String, val content: String, val version: Long, val saved: Instant) {
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
     * Keeps [content] as the Draft of [id], unless a Draft newer than [loaded], the version the page last loaded or
     * saved, is there: then the page didn't see it, and the save is a [DraftSave.Conflict]. With no Draft there is
     * nothing to overwrite, so any save is kept.
     */
    @Synchronized
    fun save(kind: DraftKind, id: String, key: String, name: String, content: String, loaded: Long): DraftSave {
        val before = get(kind, id)
        if (before != null && before.version != loaded) return DraftSave.Conflict(before)
        val now = Instant.now()
        val draft = Draft(id, key, name, content, maxOf(now.toEpochMilli(), (before?.version ?: 0) + 1), now)
        file(kind, id).writeAtomically(yaml.dump(linkedMapOf("key" to key, "name" to name, "version" to draft.version, "saved" to now.toString(), "content" to content)))
        return DraftSave.Kept(draft)
    }

    @Synchronized
    fun remove(kind: DraftKind, id: String) {
        file(kind, id).deleteIfExists()
    }

    private fun read(file: Path): Draft? = runCatching {
        val map = Yaml().load<Map<*, *>>(file.readText())
        Draft(file.nameWithoutExtension, map["key"].toString(), map["name"].toString(), map["content"].toString(), map["version"].toString().toLong(), Instant.parse(map["saved"].toString()))
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
