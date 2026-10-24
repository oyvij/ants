package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

data class Script(
    val id: String,
    val name: String,
    val description: String,
    val inputs: List<String> = emptyList(),
    val vars: Map<String, String> = emptyMap(),
    val secrets: List<String> = emptyList(),
) {
    fun definition(): Map<String, Any> = linkedMapOf("id" to id, "name" to name, "description" to description, "inputs" to inputs, "vars" to vars, "secrets" to secrets)

    companion object {
        fun of(definition: Map<*, *>) = Script(
            id = definition["id"].toString(),
            name = definition["name"]?.toString().orEmpty(),
            description = definition["description"]?.toString().orEmpty(),
            inputs = Settings.strings(definition["inputs"]).orEmpty(),
            vars = (definition["vars"] as? Map<*, *>).orEmpty().entries.associate { (name, value) -> "$name" to "$value" },
            secrets = Settings.strings(definition["secrets"]).orEmpty(),
        )
    }
}

@Component
class Scripts(@Value("\${ants.home}") home: Path, private val settings: Settings) {
    val folder: Path = home.resolve("scripts")

    fun all(): List<Script> = if (!folder.isDirectory()) emptyList()
    else folder.listDirectoryEntries().filter { it.resolve("script.yaml").exists() }
        .map { Script.of(Yaml().load<Map<*, *>>(it.resolve("script.yaml").readText()) ?: emptyMap<String, Any>()) }
        .sortedBy { it.name }

    fun named(name: String): Script? = all().firstOrNull { it.name == name }

    fun code(script: Script): String = folder(script).resolve("script.mjs").takeIf { it.exists() }?.readText().orEmpty()

    fun ownSecrets(script: Script): Map<String, String> = folder(script).resolve("secrets.yaml").takeIf { it.exists() }
        ?.let { Yaml().load<Map<*, *>>(it.readText()) }.orEmpty().entries.associate { (name, value) -> "$name" to "$value" }

    fun newId(): String = newId('s') { all().any { script -> script.id == it } || folder.resolve(it).exists() }

    fun save(script: Script, code: String, own: Map<String, String>) {
        val dir = folder(script).createDirectories()
        dir.resolve("script.mjs").writeAtomically(code)
        val secrets = dir.resolve("secrets.yaml")
        if (own.isEmpty()) secrets.deleteIfExists()
        else secrets.writePrivately(yaml.dump(own))
        dir.resolve("script.yaml").writeAtomically(yaml.dump(script.definition()))
    }

    fun delete(script: Script) {
        folder(script).toFile().deleteRecursively()
    }

    fun secretEnvironment(script: Script, own: Map<String, String>): Map<String, String> {
        val names = settings.secrets()
        val values = settings.secretValues()
        return script.secrets.flatMap { secret ->
            own[secret]?.let { listOf(secret to it) }
                ?: values[secret]?.let { value -> names[secret].orEmpty().map { it to value } }
                ?: emptyList()
        }.toMap()
    }

    private fun folder(script: Script) = folder.resolve(script.id)
}
