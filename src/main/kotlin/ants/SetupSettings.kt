package ants

import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Duration
import kotlin.io.path.deleteIfExists

/**
 * The portable Settings in a Setup, kept in `setup.yaml` under the key [key] they have in `settings.yaml`, one item per
 * name, which is its id, so none Clashes, each read as a [T]: Import merges each into `settings.yaml` by name, adding
 * or replacing it and leaving every other Setting as it is. The machine-specific Settings (CLI paths and env, Vault and
 * Source map folders, outside Agent and Skill folders, the password) are no kind's, so a Setup never holds them.
 */
abstract class SettingsInSetup<T : Any>(protected val settings: Settings, private val key: String) : SetupKind<T> {
    override val folder get() = key

    /** A name, such as the Protected Command `mvn deploy`, is the id. */
    override fun isId(id: String) = true

    /** The user's own, each name to its value as `setup.yaml` holds it. */
    protected abstract fun own(): Map<String, Any>

    /** [value], as `setup.yaml` holds it, read; null when it is none, which [problem] then says. */
    protected abstract fun parse(value: Any?): T?

    /** Why [value] can't land under [name], if it can't. */
    protected abstract fun problem(name: String, value: Any?): String?

    /** Writes [value] under [name] into `settings.yaml`'s [values]; throws with why not. */
    protected open fun write(values: MutableMap<String, Any?>, name: String, value: T) = put(values, name, value)

    /** Puts [stored], as `settings.yaml` holds it, under [name] in its [values]' map at [key]. */
    protected fun put(values: MutableMap<String, Any?>, name: String, stored: Any) {
        values[key] = (values[key] as? Map<*, *>).orEmpty().mapKeys { "${it.key}" } + (name to stored)
    }

    override fun exportSettings(ticked: (String) -> Boolean) = own().filterKeys(ticked).takeIf { it.isNotEmpty() }?.let { mapOf(key to it) }.orEmpty()

    override fun read(files: Map<String, ByteArray>, settings: Map<*, *>) = (settings[key] as? Map<*, *>).orEmpty().map { (name, value) ->
        SetupItem(this, "$key/$name", "$name", "$name", emptyMap(), problem("$name", value), parse(value))
    }

    override fun has(id: String) = id in own()

    override fun land(item: SetupItem<T>, name: String) = settings.save { write(it, name, checkNotNull(item.setting)) }

    /** Throws when this Ants hasn't each of [labels] among those it [has], which a Protected Command or Secret Provider needs before it lands. */
    protected fun requireLabels(labels: List<String>, has: Set<String>, what: String) = labels.filterNot { it in has }.takeIf { it.isNotEmpty() }?.let {
        throw IllegalArgumentException("It runs with the $what ${it.joinToString(", ")}, which this Ants hasn't: import ${if (it.size == 1) "it" else "them"} too.")
    }
}

/**
 * The Secrets in a Setup: each label to its variable names under `secrets`, and a Lookup's pointer under `lookups`;
 * a Kept value only with the Kept values, sealed.
 */
@Component
@Order(10)
class SecretsInSetup(settings: Settings, private val store: SecretStore) : SettingsInSetup<SecretsInSetup.Secret>(settings, "secrets") {
    override val noun = "Secret"

    /** One Secret as a Setup holds it: its variable names, and its pointer when it is a Lookup. */
    data class Secret(val names: List<String>, val lookup: String?)

    override fun own(): Map<String, Any> = settings.secrets()

    override fun exportSettings(ticked: (String) -> Boolean) =
        super.exportSettings(ticked) + settings.lookups().filterKeys(ticked).takeIf { it.isNotEmpty() }?.let { mapOf(LOOKUPS to it) }.orEmpty()

    override fun parse(value: Any?) = Secret(Settings.strings(value).orEmpty(), null)

    override fun read(files: Map<String, ByteArray>, settings: Map<*, *>): List<SetupItem<Secret>> {
        val lookups = (settings[LOOKUPS] as? Map<*, *>).orEmpty()
        return super.read(files, settings).map { item ->
            val lookup = lookups[item.name]?.toString()
            item.copy(setting = item.setting?.copy(lookup = lookup), problem = item.problem ?: lookup?.takeUnless(SecretProviders.POINTER::matches)?.let {
                "its Lookup $it isn't a pointer with a scheme, such as op://Dev/npm/token."
            })
        }
    }

    override fun problem(name: String, value: Any?) = Settings.strings(value).orEmpty().let { names ->
        when {
            names.isEmpty() -> "it has no variable name."
            names.any { !SettingsForm.VARIABLE.matches(it) } -> "a variable name is capitals, digits and underscores, such as NPM_TOKEN."
            else -> null
        }
    }

    override fun write(values: MutableMap<String, Any?>, name: String, value: Secret) {
        put(values, name, value.names)
        val lookups = (values[LOOKUPS] as? Map<*, *>).orEmpty().mapKeys { "${it.key}" } - name
        values[LOOKUPS] = if (value.lookup == null) lookups else lookups + (name to value.lookup)
        if ((values[LOOKUPS] as Map<*, *>).isEmpty()) values.remove(LOOKUPS)
    }

    /** The Kept values of those of [items] that aren't Lookups. */
    override fun keptValues(items: List<SetupItem<Secret>>) = store.settingsValues().filterKeys { label -> items.any { it.id == label && it.setting?.lookup == null } }

    override fun landKept(item: SetupItem<Secret>, value: Any) = store.saveSettingsValues(store.settingsValues() + (item.id to "$value"))

    private companion object {
        const val LOOKUPS = "lookups"
    }
}

/** The Secret Providers in a Setup, by scheme, each its command and the labels of the Kept Secrets it runs with. */
@Component
@Order(11)
class SecretProvidersInSetup(settings: Settings) : SettingsInSetup<SecretProvider>(settings, "secret-providers") {
    override val noun = "Secret Provider"

    override fun own() = settings.secretProviders().mapValues { (_, provider) -> mapOf("command" to provider.command, "secrets" to provider.secrets) }

    override fun parse(value: Any?) = (value as? Map<*, *>)?.let { SecretProvider(Settings.strings(it["command"]).orEmpty(), Settings.strings(it["secrets"]).orEmpty()) }

    override fun problem(name: String, value: Any?) = when {
        "{ref}" !in Settings.strings((value as? Map<*, *>)?.get("command")).orEmpty() -> "its command needs {ref}, where the pointer goes, such as op read {ref}."
        else -> null
    }

    override fun write(values: MutableMap<String, Any?>, name: String, value: SecretProvider) {
        requireLabels(value.secrets, settings.secrets().keys - settings.lookups().keys, "Kept Secret")
        put(values, name, mapOf("command" to value.command, "secrets" to value.secrets))
    }

    override fun warning(item: SetupItem<SecretProvider>, keptValues: Boolean) = item.setting?.let { provider ->
        Setup.reaches(settings, provider.secrets, "Kept Secret")?.let { "Its command `${provider.command.joinToString(" ")}` ${it.replaceFirstChar(Char::lowercase)}" }
    }
}

/** The Protected Commands in a Setup, each command prefix to the labels of the Secrets it runs with. */
@Component
@Order(12)
class ProtectedCommandsInSetup(settings: Settings) : SettingsInSetup<List<String>>(settings, "protected-commands") {
    override val noun = "Protected Command"

    override fun own() = settings.protectedCommands()

    override fun parse(value: Any?) = Settings.strings(value).orEmpty()

    override fun problem(name: String, value: Any?) = null

    override fun write(values: MutableMap<String, Any?>, name: String, value: List<String>) {
        requireLabels(value, settings.secrets().keys, "Secret")
        super.write(values, name, value)
    }

    override fun warning(item: SetupItem<List<String>>, keptValues: Boolean) = Setup.reaches(settings, item.setting.orEmpty())
}

/**
 * The Profiles in a Setup, each the names of its Vault and Source map and its Verbosity, with its instructions at
 * `profiles/<name>/AGENTS.md`. A Vault or Source map is the machine's own, so a Profile naming one this Ants lacks
 * lands, with a note.
 */
@Component
@Order(13)
class ProfilesInSetup(settings: Settings) : SettingsInSetup<Map<*, *>>(settings, FOLDER) {
    override val noun = "Profile"

    override fun own() = settings.profiles().mapValues { (_, profile) ->
        listOfNotNull(profile.vault?.let { "vault" to it }, profile.sourceMap?.let { "source-map" to it }).toMap() + profile.verbosity.setting
    }

    override fun export() = settings.profiles().keys.filter(settings::hasInstructions).associate { "$folder/$it/AGENTS.md" to settings.instructionsText(it).toByteArray() }

    override fun read(files: Map<String, ByteArray>, settings: Map<*, *>) = super.read(files, settings).map { item ->
        item.copy(files = files.filterKeys { it == "$folder/${item.name}/AGENTS.md" })
    }

    override fun parse(value: Any?) = value as? Map<*, *>

    override fun problem(name: String, value: Any?) = when {
        '/' in name || '\\' in name || name in listOf(".", "..") -> "a Profile's name is a folder name, so it can't hold a / or be . or .."
        value !is Map<*, *> -> "it names no Vault or Source map."
        else -> null
    }

    override fun land(item: SetupItem<Map<*, *>>, name: String) {
        super.land(item, name)
        val instructions = settings.instructions(name)!!
        item.files.values.singleOrNull()?.let { instructions.writeAtomically(it.decodeToString()) } ?: instructions.deleteIfExists()
    }

    override fun write(values: MutableMap<String, Any?>, name: String, value: Map<*, *>) =
        put(values, name, listOf("vault", "source-map").mapNotNull { key -> value[key]?.let { key to "$it" } }.toMap() + Verbosity.of(value["verbosity"]).setting)

    override fun note(item: SetupItem<Map<*, *>>): String? {
        val profile = item.setting ?: return null
        val lacks = listOfNotNull(
            profile["vault"]?.toString()?.takeIf { it !in settings.vaults() }?.let { "the Vault $it" },
            profile["source-map"]?.toString()?.takeIf { it !in settings.sourceMaps() }?.let { "the Source map $it" },
        )
        return if (lacks.isEmpty()) null else "This Ants hasn't ${lacks.joinToString(" or ")}: add it on the Settings page before a Run uses this Profile."
    }

    companion object {
        const val FOLDER = "profiles"
    }
}

/** The Settings that are one value each, at the top of `setup.yaml` as in `settings.yaml`: the Agent Task defaults and the theme. */
@Component
@Order(14)
class SingleValueSettingsInSetup(private val settings: Settings) : SetupKind<Any> {
    override val noun = "Setting"
    override val folder = "settings"

    override fun isId(id: String) = true

    private fun own() = settings.read().filterKeys { it in KEYS }.filterValues { it != null }.mapValues { it.value!! }

    override fun exportSettings(ticked: (String) -> Boolean) = own().filterKeys(ticked)

    override fun read(files: Map<String, ByteArray>, settings: Map<*, *>) = KEYS.filter { settings[it] != null }.map { key ->
        SetupItem(this, "$folder/$key", key, key, emptyMap(), problem(key, settings[key]), settings[key])
    }

    private fun problem(key: String, value: Any?): String? = when (key) {
        "agent-tasks", "subagent-depth" -> "it isn't a whole number, 1 or more.".takeUnless { value is Int && value >= 1 }
        "retries" -> "it isn't a whole number, 0 or more.".takeUnless { value is Int && value >= 0 }
        "so-far" -> "it isn't true, false or a time such as 5m.".takeUnless {
            value is Boolean || runCatching { Duration.parse("PT$value".uppercase()).isPositive }.getOrDefault(false)
        }
        else -> "it isn't light or dark.".takeUnless { value in listOf("light", "dark") }
    }

    override fun has(id: String) = id in own()

    override fun land(item: SetupItem<Any>, name: String) = settings.save { it[item.id] = item.setting }

    private companion object {
        /** The Agent Task defaults and the theme. */
        val KEYS = listOf("agent-tasks", "subagent-depth", "retries", "so-far", "theme")
    }
}

/**
 * Your Skills and Your tools of the shipped Agents in a Setup: `your-skills` and `your-tools` in `setup.yaml`, each a
 * shipped Agent's id to its list, as ANTS_HOME keeps them in `your-skills/<id>.yaml` and `your-tools/<id>.yaml`. A
 * shipped Agent's id is the same in every Ants, so they land on the same Agent; a Draft of them stays a Draft.
 */
@Component
@Order(15)
class YoursInSetup(private val catalogue: Catalogue) : SetupKind<Pair<List<String>, List<String>>> {
    override val noun = "Your Skills and tools"
    override val folder = "your-skills"

    private fun shipped() = catalogue.agents(withHidden = true).filter(catalogue.yours::has)

    private fun own() = shipped().associateWith { catalogue.yours.skills(it) to catalogue.yours.tools(it) }.filterValues { (skills, tools) -> skills.isNotEmpty() || tools.isNotEmpty() }

    override fun exportSettings(ticked: (String) -> Boolean) = own().filterKeys { ticked(it.id!!) }.let { own ->
        listOf(SKILLS to { lists: Pair<List<String>, List<String>> -> lists.first }, TOOLS to { lists -> lists.second }).mapNotNull { (key, list) ->
            own.entries.associate { (agent, lists) -> agent.id!! to list(lists) }.filterValues { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { key to it }
        }.toMap()
    }

    override fun read(files: Map<String, ByteArray>, settings: Map<*, *>): List<SetupItem<Pair<List<String>, List<String>>>> {
        fun lists(key: String) = (settings[key] as? Map<*, *>).orEmpty().entries.associate { (id, list) -> "$id" to Settings.strings(list).orEmpty() }
        val skills = lists(SKILLS)
        val tools = lists(TOOLS)
        return (skills.keys + tools.keys).map { id ->
            val agent = shipped().firstOrNull { it.id == id }
            SetupItem(this, "$folder/$id", id, agent?.name ?: id, emptyMap(), if (agent == null) "no shipped Agent of this Ants has the id $id." else null, skills[id].orEmpty() to tools[id].orEmpty())
        }
    }

    override fun has(id: String) = own().keys.any { it.id == id }

    override fun land(item: SetupItem<Pair<List<String>, List<String>>>, name: String) {
        val agent = shipped().first { it.id == item.id }
        val (skills, tools) = checkNotNull(item.setting)
        catalogue.yours.publishSkills(agent, skills)
        catalogue.yours.publishTools(agent, tools)
    }

    private companion object {
        const val SKILLS = "your-skills"
        const val TOOLS = "your-tools"
    }
}
