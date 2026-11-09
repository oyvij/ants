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
 * [files] by their path in the Setup, the [problem] that keeps it from landing, if one does, and its [setting], its
 * value in `setup.yaml` for an item kept there.
 */
data class SetupItem<T : Any>(val kind: SetupKind<T>, val key: String, val id: String, val name: String, val files: Map<String, ByteArray>, val problem: String? = null, val setting: T? = null) {
    fun uses() = kind.uses(this)

    fun warning(keptValues: Boolean) = kind.warning(this, keptValues)

    fun note() = kind.note(this)

    fun land(name: String) = kind.land(this, name)

    fun landKept(value: Any) = kind.landKept(this, value)
}

/** An item one item uses: the one of the kind in [folder] whose id or name is [value]. */
data class SetupUse(val folder: String, val value: String)

/**
 * One kind of item a Setup holds, such as the Agents, in its [folder], the same in a Setup as in ANTS_HOME, each item's
 * [SetupItem.setting] a [T]: how the user's own items go into a Setup, are read out of one, and land in this Ants, each
 * keeping its id (ADR 0034). The [Setup] asks every kind in turn, so a new kind is one more of these.
 */
interface SetupKind<T : Any> {
    /** What one item is called, such as "Agent". */
    val noun: String

    val folder: String

    /** Gives each of the user's own items without an id one, as Export writes them; never while Export only lists them. */
    fun giveIds() {}

    /** The user's own items' files, by their path in the Setup: none for a kind kept in `setup.yaml` only. */
    fun export(): Map<String, ByteArray> = emptyMap()

    /** What this kind keeps in `setup.yaml`, by key, beside the Ants version, of the user's own items whose ids are [ticked]. */
    fun exportSettings(ticked: (String) -> Boolean = { true }): Map<String, Any> = emptyMap()

    /** The items in a Setup's [files], which all lie in [folder], and in its `setup.yaml`'s keys, [settings]. */
    fun read(files: Map<String, ByteArray>, settings: Map<*, *>): List<SetupItem<T>>

    /**
     * What [item] uses that would go in a Setup beside it, each by the [SetupKind.folder] of its kind and its id or
     * name, such as a Process's Agent: none, unless the kind links others.
     */
    fun uses(item: SetupItem<T>): List<SetupUse> = emptyList()

    /**
     * Why [item] waits for the user's say before it lands: the Secrets of this Ants it would run with, which a shared
     * Setup mustn't reach unasked, its own Kept values coming with it when [keptValues]; null when it reaches none.
     */
    fun warning(item: SetupItem<T>, keptValues: Boolean): String? = null

    /** What the report says beside [item] once it landed, such as what it names that this Ants lacks. */
    fun note(item: SetupItem<T>): String? = null

    /** Whether [id] is an id this kind's items have, safe as a file's name. */
    fun isId(id: String) = Setup.ID.matches(id)

    /** Whether the user has an item of this kind with [id] of their own, which an item with it replaces. */
    fun has(id: String): Boolean

    /** Whether an item other than the one with [id], or a Draft, holds [name]: never, for a kind whose names don't clash. */
    fun taken(name: String, id: String): Boolean = false

    /** Writes [item] as the user's own under [name], keeping its id and replacing their own with that id; throws with why not. */
    fun land(item: SetupItem<T>, name: String)

    /** The Kept values of [items], by id, as `safe.yaml` seals them under [folder]; null for a kind that keeps none. */
    fun keptValues(items: List<SetupItem<T>>): Map<String, Any>? = null

    /** Keeps [value], the Kept value `safe.yaml` holds for [item], once [item] landed. */
    fun landKept(item: SetupItem<T>, value: Any) {}
}

/** The files among [files] right in a kind's folder whose names end in [extension], such as `agents/writer.md`. */
fun inFolder(files: Map<String, ByteArray>, extension: String) = files.filterKeys { it.count { c -> c == '/' } == 1 && it.endsWith(extension) }

/** The item [read] reads, or when it throws, the one at [key] holding [files], with [id] and named [fallback], refused saying [broken] and why. */
fun <T : Any> SetupKind<T>.readOr(key: String, files: Map<String, ByteArray>, fallback: String, broken: String?, id: String = "", read: () -> SetupItem<T>): SetupItem<T> =
    runCatching(read).getOrElse { SetupItem(this, key, id, fallback, files, listOfNotNull(broken, it.message).joinToString(": ")) }

/** The Kept values of those of [items] that are this kind's, as [SetupKind.keptValues] gives them. */
@Suppress("UNCHECKED_CAST")
private fun <T : Any> SetupKind<T>.keptValuesOf(items: List<SetupItem<*>>) = keptValues(items.filter { it.kind === this } as List<SetupItem<T>>)

/**
 * A Setup (ADR 0034): what the user made, without its history, as one zip laid out like ANTS_HOME with `setup.yaml`
 * holding the Ants version that wrote it. [export] writes one, [preview] reads one into what Import would do with each
 * item, and [import] lands the items the user confirmed, each on its own, into a report. A Process or Template from an
 * older Ants lands through the [Migration] ANTS_HOME gets.
 *
 * The Kept values its kinds keep, such as those of its Secret labels and Scripts, travel only when asked for, in
 * `safe.yaml`, encrypted with the exporting Safe key (ADR 0033), which Import then needs.
 */
@Component
class Setup(private val kinds: List<SetupKind<*>>, build: BuildProperties, private val store: SecretStore) {
    private val version = build.version

    enum class Status(val label: String) { NEW("New"), REPLACES("Replaces"), CLASHES("Clashes") }

    enum class Outcome(val label: String) { ADDED("Added"), REPLACED("Replaced"), SKIPPED("Skipped"), REFUSED("Refused") }

    /** What Import would do with [item], and the [warning] that keeps it unticked until the user ticks it. */
    data class Previewed(val item: SetupItem<*>, val status: Status, val warning: String? = null) {
        val key get() = item.key
        val noun get() = item.kind.noun
        val name get() = item.name
        val problem get() = item.problem

        /** Whether the preview starts with it ticked: neither refused nor reaching the user's Secrets. */
        val ticked get() = problem == null && warning == null
    }

    /** A Setup's items as Import would land them, written by Ants [version], and whether it carries [keptValues], which need its Safe key. */
    data class Preview(val version: String, val items: List<Previewed>, val keptValues: Boolean = false)

    /** A ticked item, under its own name, or [rename] when given. */
    data class Choice(val rename: String? = null)

    /** What Import did with one item, named [name] as it landed, and why for a skipped or refused one. */
    data class Landed(val noun: String, val name: String, val outcome: Outcome, val reason: String? = null)

    /** A file Import refuses whole, saying why. */
    class Refused(message: String) : RuntimeException(message)

    /** One of the user's own items Export offers, by the [key] it has in a Setup, with the keys of every item it [uses], through those it uses. */
    data class Exportable(val key: String, val noun: String, val name: String, val uses: List<String>)

    /** The user's own items of every kind, as Export lists them to tick. */
    fun exportable(): List<Exportable> {
        val items = own()
        val used = items.associateWith { item ->
            item.uses().mapNotNull { use -> items.firstOrNull { it !== item && it.kind.folder == use.folder && (it.id == use.value || it.name == use.value) } }
        }
        return items.map { item ->
            // Through what each used one uses, so ticking a Process ticks what its called Processes and its Agent use too.
            val reached = linkedSetOf<SetupItem<*>>()
            val next = ArrayDeque(used.getValue(item))
            while (next.isNotEmpty()) next.removeFirst().let { if (it !== item && reached.add(it)) next += used.getValue(it) }
            Exportable(item.key, item.kind.noun, item.name, reached.map { it.key })
        }
    }

    /** The user's own items as each kind reads them back. */
    private fun own() = kinds.flatMap { kind -> kind.read(kind.export(), kind.exportSettings()) }

    /**
     * The user's own items of every kind as a Setup, or only those whose keys are [ticked], each given an id if it had
     * none, with the Kept values its kinds keep, encrypted with the Safe key, when [keptValues]; throws while the Safe is locked.
     */
    fun export(ticked: Set<String>? = null, keptValues: Boolean = false): ByteArray = ByteArrayOutputStream().also { bytes ->
        kinds.forEach { it.giveIds() }
        ZipOutputStream(bytes).use { zip ->
            val items = own().filter { ticked == null || it.key in ticked }
            val ids = items.groupBy({ it.kind }, { it.id })
            val meta = mapOf("ants" to version) + kinds.flatMap { kind -> kind.exportSettings { it in ids[kind].orEmpty() }.entries.map { (key, value) -> key to value } }
            val paths = items.flatMap { it.files.entries.map { (path, data) -> path to data } }.toMap()
            val files = mapOf(SETUP_FILE to yaml.dump(meta).toByteArray()) + paths + (if (keptValues) mapOf(SAFE_FILE to kept(items)) else emptyMap())
            for ((path, data) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(data)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    /** The Kept values of [items], by the folder of each kind that keeps some, sealed with the Safe key, as `safe.yaml` holds them. */
    private fun kept(items: List<SetupItem<*>>): ByteArray {
        val values = kinds.mapNotNull { kind -> kind.keptValuesOf(items)?.let { kind.folder to it } }.toMap()
        return yaml.dump(mapOf("sealed" to store.seal(values))).toByteArray()
    }

    /** Every item in [zip] as New, Replaces or Clashes; throws [Refused] for a file that isn't a Setup this Ants can read. */
    fun preview(zip: ByteArray): Preview = preview(unzip(zip))

    private fun preview(files: Map<String, ByteArray>): Preview {
        val meta = runCatching { yaml.load<Any?>(files[SETUP_FILE]?.decodeToString() ?: throw Refused("This file isn't a Setup: it has no $SETUP_FILE.")) as? Map<*, *> }
            .getOrElse { if (it is Refused) throw it else null } ?: throw Refused("This Setup's $SETUP_FILE is broken.")
        val from = meta["ants"]?.toString() ?: throw Refused("This Setup's $SETUP_FILE doesn't say which Ants wrote it.")
        val newer = runCatching { Version.parse(from) > Version.parse(version) }.getOrDefault(true)
        if (newer) throw Refused("This Setup is from Ants $from, newer than this Ants $version: update Ants first.")
        val keptValues = SAFE_FILE in files
        val items = kinds.flatMap { kind -> kind.read(files.filterKeys { it.startsWith("${kind.folder}/") }, meta) }.map { item ->
            val checked = if (item.problem == null && !item.kind.isId(item.id)) item.copy(problem = "it has no id.") else item
            Previewed(checked, when {
                checked.kind.has(checked.id) -> Status.REPLACES
                checked.kind.taken(checked.name, checked.id) -> Status.CLASHES
                else -> Status.NEW
            }, checked.warning(keptValues))
        }
        return Preview(from, items, keptValues)
    }

    /**
     * Lands each item of [zip] that [choices] ticks, by its key, on its own, and says what became of every item: an
     * unticked one and a Clash not renamed are skipped, and one that can't be read or can't land is refused, the rest
     * landing still. A landed item gets its Kept values from the Setup, which [safeKey] must open, or nothing lands.
     */
    fun import(zip: ByteArray, choices: Map<String, Choice>, safeKey: String? = null): List<Landed> {
        val files = unzip(zip)
        val preview = preview(files)
        val kept = files[SAFE_FILE]?.let { opened(it, safeKey) }
        return preview.items.map { previewed ->
            land(previewed, choices).also { landed ->
                val item = previewed.item
                if (landed.outcome == Outcome.ADDED || landed.outcome == Outcome.REPLACED) (kept?.get(item.kind.folder) as? Map<*, *>)?.get(item.id)?.let(item::landKept)
            }
        }
    }

    /** The Kept values a Setup's `safe.yaml` [file] seals, opened with [safeKey]; throws [Refused] when it can't be, before anything lands. */
    private fun opened(file: ByteArray, safeKey: String?): Map<*, *> {
        if (safeKey.isNullOrBlank()) throw Refused("This Setup holds Kept values: give the Safe key of the Ants that exported it.")
        val sealed = runCatching { (yaml.load<Any?>(file.decodeToString()) as Map<*, *>)["sealed"] as String }.getOrNull() ?: throw Refused("This Setup's $SAFE_FILE is broken.")
        val values = SecretStore.open(safeKey, sealed) ?: throw Refused("That Safe key doesn't open this Setup's Kept values: give the Safe key of the Ants that exported it.")
        if (store.locked) throw Refused("${SecretStore.LOCKED}, then import again.")
        return values
    }

    /** What becomes of [previewed] as [choices] say. */
    private fun land(previewed: Previewed, choices: Map<String, Choice>): Landed {
        val item = previewed.item
        val noun = item.kind.noun
        if (item.problem != null) return Landed(noun, item.name, Outcome.REFUSED, "${item.key}: ${item.problem}")
        val choice = choices[item.key] ?: return Landed(noun, item.name, Outcome.SKIPPED, "Unticked.")
        val name = choice.rename?.trim()?.ifEmpty { null } ?: item.name
        return when {
            previewed.status == Status.CLASHES && name == item.name -> Landed(noun, name, Outcome.SKIPPED, "Another $noun is named $name: rename it to import it.")
            name != item.name && item.kind.taken(name, item.id) -> Landed(noun, name, Outcome.REFUSED, "Another $noun is named $name.")
            else -> runCatching { item.land(name) }.fold(
                { Landed(noun, name, if (previewed.status == Status.REPLACES) Outcome.REPLACED else Outcome.ADDED, item.note()) },
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

        /** Where a Setup carries Kept values, sealed with the exporting Safe key. */
        const val SAFE_FILE = "safe.yaml"

        /** An id as Ants gives them, which is safe as a folder's name. */
        internal val ID = Regex("[a-z0-9][a-z0-9-]{0,63}")

        /** The warning for an item that would run with [labels], naming those of them that are Secret labels of [what] kind in [settings]; null for none. */
        fun reaches(settings: Settings, labels: Collection<String>, what: String = "Secret") = labels.filter { it in settings.secrets() }.takeIf { it.isNotEmpty() }?.let {
            "It would run with your $what${if (it.size == 1) "" else "s"} ${it.joinToString(", ")}."
        }
    }
}
