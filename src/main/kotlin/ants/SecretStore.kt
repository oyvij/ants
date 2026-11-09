package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Every Secret value Ants keeps, and the only code that reads or writes one: Settings Secrets by label, in
 * `secrets.yaml` in ANTS_HOME, and each Script's own by the Script's fixed id, in `scripts/<id>/secrets.yaml`. Files of
 * mode 0600, read fresh each time. The Safe (ADR 0033) replaces the storage behind this.
 */
@Component
class SecretStore(@Value("\${ants.home}") home: Path) {
    /** The Settings Secrets' file, which claude Agents may neither read nor edit (ADR 0015). */
    val file: Path = home.resolve("secrets.yaml")

    private val scripts = home.resolve("scripts")

    /** Each Settings Secret's value, by label. */
    fun settingsValues(): Map<String, String> = read(file)

    /** Rewrites the Settings Secrets' values with [values]. */
    @Synchronized
    fun saveSettingsValues(values: Map<String, String>) {
        file.writePrivately(if (values.isEmpty()) "" else yaml.dump(values))
    }

    /** The Script with [id]'s own Secrets' values, by name, kept apart from its Draft and its published version. */
    fun scriptValues(id: String): Map<String, String> = read(scriptFile(id))

    /** Keeps [values] as the Script with [id]'s own Secrets; none deletes them. */
    @Synchronized
    fun saveScriptValues(id: String, values: Map<String, String>) {
        if (values.isEmpty()) deleteScriptValues(id) else scriptFile(id).writePrivately(yaml.dump(values))
    }

    fun deleteScriptValues(id: String) {
        scriptFile(id).deleteIfExists()
    }

    /** The values to mask in any output an Agent or the user reads: every Settings Secret's. */
    fun masks(): Collection<String> = settingsValues().values

    private fun scriptFile(id: String) = scripts.resolve(id).resolve("secrets.yaml")

    private fun read(file: Path): Map<String, String> = file.takeIf { it.exists() }?.let { Yaml().load<Map<*, *>>(it.readText()) }
        .orEmpty().entries.associate { (key, value) -> "$key" to "$value" }
}
