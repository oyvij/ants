package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

data class Script(
    val id: String,
    val name: String,
    val description: String,
    /** The input schema: an object JSON Schema, each property an input (ADR 0025). */
    val inputs: Map<*, *> = ScriptSchema.EMPTY,
    /** The output schema: the payload's object JSON Schema, or null when any JSON payload is accepted. */
    val output: Map<*, *>? = null,
    val vars: Map<String, String> = emptyMap(),
    val secrets: List<String> = emptyList(),
    /** The addresses of the Widgets it Posts to, `<dashboard id>/<widget id>` (ADR 0032); `ants.post` refuses any other. */
    val widgets: List<String> = emptyList(),
) {
    val inputNames: List<String> get() = (inputs["properties"] as? Map<*, *>).orEmpty().keys.map { "$it" }

    fun definition(): Map<String, Any> = linkedMapOf<String, Any>("id" to id, "name" to name, "description" to description, "inputs" to inputs)
        .apply { output?.let { put("output", it) } } + mapOf("vars" to vars, "secrets" to secrets) + (if (widgets.isEmpty()) emptyMap() else mapOf("widgets" to widgets))

    companion object {
        fun of(definition: Map<*, *>) = Script(
            id = definition["id"].toString(),
            name = definition["name"]?.toString().orEmpty(),
            description = definition["description"]?.toString().orEmpty(),
            inputs = definition["inputs"] as? Map<*, *> ?: ScriptSchema.EMPTY,
            output = definition["output"] as? Map<*, *>,
            vars = (definition["vars"] as? Map<*, *>).orEmpty().entries.associate { (name, value) -> "$name" to "$value" },
            secrets = Settings.strings(definition["secrets"]).orEmpty(),
            widgets = Settings.strings(definition["widgets"]).orEmpty(),
        )
    }
}

@Component
class Scripts(@Value("\${ants.home}") home: Path, private val settings: Settings, private val drafts: Drafts, private val store: SecretStore, private val providers: SecretProviders) {
    val folder: Path = home.resolve("scripts")

    fun all(): List<Script> = inFolders().map { it.second }.sortedBy { it.name }

    fun identified(): List<Identified> = inFolders().map { (dir, script) -> Identified(script.id, "Script", script.name, dir) }

    private fun inFolders(): List<Pair<Path, Script>> = if (!folder.isDirectory()) emptyList()
    else folder.listDirectoryEntries().filter { it.resolve("script.yaml").exists() }
        .map { it to Script.of(Yaml().load<Map<*, *>>(it.resolve("script.yaml").readText()) ?: emptyMap<String, Any>()) }
        .sortedBy { (dir, script) -> dir.fileName.toString() != script.id }

    fun named(name: String): Script? = all().firstOrNull { it.name == name }

    fun withId(id: String): Script? = inFolders().firstOrNull { it.second.id == id }?.second

    fun where(script: Script): Path = inFolders().firstOrNull { it.second == script }?.first ?: folder(script)

    fun linked(value: String, kept: String? = null): Script = withId(value) ?: named(kept ?: value) ?: error("No Script named ${kept ?: value}")

    fun ids(listed: List<String>): List<String> = listed.map { runCatching { linked(it).id }.getOrDefault(it) }.distinct()

    fun names(listed: List<String>): List<String> = listed.map { runCatching { linked(it).name }.getOrDefault(it) }

    fun file(script: Script): Path = folder(script).resolve("script.mjs")

    fun code(script: Script): String = file(script).takeIf { it.exists() }?.readText().orEmpty()

    /** The Script with [id]'s own Secrets' values, kept apart from its Draft and its published version, so a Draft-only Script has them too. */
    fun ownSecrets(id: String): Map<String, String> = store.scriptValues(id)

    /**
     * The Script with [id]'s own Lookups, each Secret's name to its pointer: in `settings.yaml` under `script-lookups`,
     * as a pointer is no secret, and never in the Script's definition, which a shared Script carries.
     */
    fun ownLookups(id: String): Map<String, String> =
        ((settings.read()["script-lookups"] as? Map<*, *>)?.get(id) as? Map<*, *>).orEmpty().entries.associate { (name, pointer) -> "$name" to "$pointer" }

    /** The names of the Script with [id]'s own Secrets, Kept or Lookups. */
    fun ownNames(id: String): Set<String> = ownSecrets(id).keys + ownLookups(id).keys

    /**
     * Keeps [own] as the Script with [id]'s own Kept Secrets and [lookups] as its own Lookups, at once: a published
     * Script's definition then lists their names, beside the Secrets from Settings it lists, and nothing else of it changes.
     */
    fun saveSecrets(id: String, own: Map<String, String>, lookups: Map<String, String> = emptyMap()) {
        val old = ownNames(id)
        store.saveScriptValues(id, own)
        saveLookups(id, lookups)
        val script = withId(id) ?: return
        folder(script).resolve("script.yaml").writeAtomically(yaml.dump(script.copy(secrets = (own.keys + lookups.keys + script.secrets.filter { it !in old }).distinct()).definition()))
    }

    private fun saveLookups(id: String, lookups: Map<String, String>) {
        if (lookups == ownLookups(id)) return
        settings.save { values ->
            val all = (values["script-lookups"] as? Map<*, *>).orEmpty().filterKeys { it != id } + (if (lookups.isEmpty()) emptyMap() else mapOf(id to lookups))
            if (all.isEmpty()) values.remove("script-lookups") else values["script-lookups"] = all
        }
    }

    /** A new Script's id: none a Script or any Draft holds. */
    fun newId(): String = newId('s') { all().any { script -> script.id == it } || folder.resolve(it).exists() || it in drafts.ids() }

    /** Writes [script] and its [code], and [own] as its own Secrets unless null, which leaves the ones kept. */
    fun save(script: Script, code: String, own: Map<String, String>?) {
        val dir = folder(script).createDirectories()
        dir.resolve("script.mjs").writeAtomically(code)
        own?.let { store.saveScriptValues(script.id, it) }
        dir.resolve("script.yaml").writeAtomically(yaml.dump(script.definition()))
    }

    /** Deletes the Script with [id], its own Secrets with it. */
    fun delete(id: String) {
        store.deleteScriptValues(id)
        saveLookups(id, emptyMap())
        folder.resolve(id).toFile().deleteRecursively()
    }

    /**
     * The environment holding [script]'s Secrets' values, [own] being its own Kept ones and [lookups] its own Lookups,
     * each Lookup, its own or one in Settings, resolved now; throws [SecretProviders.Failed] when one can't be.
     */
    fun secretEnvironment(script: Script, own: Map<String, String> = ownSecrets(script.id), lookups: Map<String, String> = ownLookups(script.id)): Map<String, String> {
        val names = settings.secrets()
        return script.secrets.flatMap { secret ->
            own[secret]?.let { listOf(secret to it) }
                ?: lookups[secret]?.let { listOf(secret to providers.lookUp(secret, it)) }
                ?: providers.value(secret)?.let { value -> names[secret].orEmpty().map { it to value } }
                ?: emptyList()
        }.toMap()
    }

    private fun folder(script: Script) = folder.resolve(script.id)
}
