package ants

import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.module.ModuleDescriptor.Version
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One item in a Setup as its [kind] reads it: its [key], the path in the Setup that holds it, its [id] and [name], its
 * [files] by their path in the Setup, and the [problem] that keeps it from landing, if one does.
 */
data class SetupItem(val kind: SetupKind, val key: String, val id: String, val name: String, val files: Map<String, ByteArray>, val problem: String? = null)

/**
 * One kind of item a Setup holds, such as the Agents, in its [folder], the same in a Setup as in ANTS_HOME: how the
 * user's own items go into a Setup, are read out of one, and land in this Ants, each keeping its id (ADR 0034). The
 * [Setup] asks every kind in turn, so a new kind is one more of these.
 */
interface SetupKind {
    /** What one item is called, such as "Agent". */
    val noun: String

    val folder: String

    /** The user's own items' files, by their path in the Setup. */
    fun export(): Map<String, ByteArray>

    /** The items in a Setup's [files], which all lie in [folder]. */
    fun read(files: Map<String, ByteArray>): List<SetupItem>

    /** Whether [id] is an id this kind's items have, safe as a file's name. */
    fun isId(id: String) = Setup.ID.matches(id)

    /** Whether the user has an item of this kind with [id] of their own, which an item with it replaces. */
    fun has(id: String): Boolean

    /** Whether an item other than the one with [id], or a Draft, holds [name]. */
    fun taken(name: String, id: String): Boolean

    /** Writes [item] as the user's own under [name], keeping its id and replacing their own with that id; throws with why not. */
    fun land(item: SetupItem, name: String)
}

/**
 * A Setup (ADR 0034): what the user made, without its history, as one zip laid out like ANTS_HOME with `setup.yaml`
 * holding the Ants version that wrote it. [export] writes one, [preview] reads one into what Import would do with each
 * item, and [import] lands the items the user confirmed, each on its own, into a report.
 */
@Component
class Setup(private val kinds: List<SetupKind>, build: BuildProperties) {
    private val version = build.version

    enum class Status(val label: String) { NEW("New"), REPLACES("Replaces"), CLASHES("Clashes") }

    enum class Outcome(val label: String) { ADDED("Added"), REPLACED("Replaced"), SKIPPED("Skipped"), REFUSED("Refused") }

    /** What Import would do with [item]. */
    data class Previewed(val item: SetupItem, val status: Status) {
        val key get() = item.key
        val noun get() = item.kind.noun
        val name get() = item.name
        val problem get() = item.problem
    }

    /** A Setup's items as Import would land them, written by Ants [version]. */
    data class Preview(val version: String, val items: List<Previewed>)

    /** A ticked item, under its own name, or [rename] when given. */
    data class Choice(val rename: String? = null)

    /** What Import did with one item, named [name] as it landed, and why for a skipped or refused one. */
    data class Landed(val noun: String, val name: String, val outcome: Outcome, val reason: String? = null)

    /** A file Import refuses whole, saying why. */
    class Refused(message: String) : RuntimeException(message)

    /** The user's own items of every kind as a Setup. */
    fun export(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            val files = mapOf(SETUP_FILE to yaml.dump(mapOf("ants" to version)).toByteArray()) + kinds.flatMap { it.export().entries.map { (path, data) -> path to data } }
            for ((path, data) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(data)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    /** Every item in [zip] as New, Replaces or Clashes; throws [Refused] for a file that isn't a Setup this Ants can read. */
    fun preview(zip: ByteArray): Preview {
        val files = unzip(zip)
        val meta = runCatching { yaml.load<Any?>(files[SETUP_FILE]?.decodeToString() ?: throw Refused("This file isn't a Setup: it has no $SETUP_FILE.")) as? Map<*, *> }
            .getOrElse { if (it is Refused) throw it else null } ?: throw Refused("This Setup's $SETUP_FILE is broken.")
        val from = meta["ants"]?.toString() ?: throw Refused("This Setup's $SETUP_FILE doesn't say which Ants wrote it.")
        val newer = runCatching { Version.parse(from) > Version.parse(version) }.getOrDefault(true)
        if (newer) throw Refused("This Setup is from Ants $from, newer than this Ants $version: update Ants first.")
        val items = kinds.flatMap { kind -> kind.read(files.filterKeys { it.startsWith("${kind.folder}/") }) }.map { item ->
            val checked = if (item.problem == null && !item.kind.isId(item.id)) item.copy(problem = "it has no id.") else item
            Previewed(checked, when {
                checked.kind.has(checked.id) -> Status.REPLACES
                checked.kind.taken(checked.name, checked.id) -> Status.CLASHES
                else -> Status.NEW
            })
        }
        return Preview(from, items)
    }

    /**
     * Lands each item of [zip] that [choices] ticks, by its key, on its own, and says what became of every item: an
     * unticked one and a Clash not renamed are skipped, and one that can't be read or can't land is refused, the rest
     * landing still.
     */
    fun import(zip: ByteArray, choices: Map<String, Choice>): List<Landed> = preview(zip).items.map { previewed ->
        val item = previewed.item
        val noun = item.kind.noun
        if (item.problem != null) return@map Landed(noun, item.name, Outcome.REFUSED, "${item.key}: ${item.problem}")
        val choice = choices[item.key] ?: return@map Landed(noun, item.name, Outcome.SKIPPED, "Unticked.")
        val name = choice.rename?.trim()?.ifEmpty { null } ?: item.name
        when {
            previewed.status == Status.CLASHES && name == item.name -> Landed(noun, name, Outcome.SKIPPED, "Another $noun is named $name: rename it to import it.")
            name != item.name && item.kind.taken(name, item.id) -> Landed(noun, name, Outcome.REFUSED, "Another $noun is named $name.")
            else -> runCatching { item.kind.land(item, name) }.fold(
                { Landed(noun, name, if (previewed.status == Status.REPLACES) Outcome.REPLACED else Outcome.ADDED) },
                { Landed(noun, name, Outcome.REFUSED, it.message ?: it.toString()) },
            )
        }
    }

    /** The files in [zip] by their path; throws [Refused] for one that isn't a zip or has a path outside itself. */
    private fun unzip(zip: ByteArray): Map<String, ByteArray> = try {
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            generateSequence { input.nextEntry }.filterNot { it.isDirectory }.associate { entry ->
                val path = entry.name
                if (path.startsWith("/") || '\\' in path || path.split('/').any { it == ".." || it == "." || it.isEmpty() }) throw Refused("This Setup holds a file outside itself: $path.")
                path to input.readBytes()
            }
        }
    } catch (e: ZipException) {
        throw Refused("This file isn't a Setup: ${e.message}.")
    }

    companion object {
        const val SETUP_FILE = "setup.yaml"

        /** An id as Ants gives them, which is safe as a folder's name. */
        internal val ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
    }
}
