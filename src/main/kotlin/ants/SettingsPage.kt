package ants

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import tools.jackson.dataformat.toml.TomlMapper
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.math.roundToInt

/**
 * The settings page: one form over `settings.yaml`. Detect and Fill from Varde fill the form; Check login and Check now
 * (the update check) ask again and say what they found in a toast, keeping what was typed; only Save writes.
 */
@Controller
class SettingsPage(
    private val settings: Settings,
    private val encoder: PasswordEncoder,
    private val launcher: AgentLauncher,
    @Value("\${ants.home}") private val home: Path,
    /** Where Ants listens, as it was started; empty is every address. */
    @Value("\${server.address:}") private val address: String,
    private val updates: Updates,
    private val server: WebServerApplicationContext,
) {
    private val port get() = server.webServer?.port

    @GetMapping("/settings")
    fun page(model: Model) = show(model, SettingsForm.of(settings))

    @PostMapping("/settings")
    fun press(@RequestParam all: MultiValueMap<String, String>, request: HttpServletRequest, model: Model): String {
        // A list's rows, such as the Agent folders, post one value each, which the form reads one per line.
        val params = all.mapValues { it.value.joinToString("\n") }
        val form = SettingsForm.of(params)
        return when (params["action"]) {
            "login" -> login().let { login ->
                Shell.toast(request, if (login.loggedIn) Toast("Logged in", login.method, Toast.Kind.SUCCESS) else Toast("Not logged in", "Run the command under Log in again, then press Check login.", Toast.Kind.ERROR))
                show(model, form)
            }
            "updates" -> Shell.toast(request, updates.checkNow()).let { show(model, form) }
            "detect" -> settings.detect().let { found ->
                val missing = Settings.CLIS - found.keys
                show(model, form.copy(paths = form.paths + found), if (missing.isEmpty()) null else "Your login shell doesn't find ${missing.joinToString(", ")}.")
            }
            "varde" -> runCatching { form.fromVarde() }.fold({ show(model, it) }, { show(model, form, it.message) })
            else -> form.error()?.let { show(model, form, it) } ?: run {
                form.save(settings, params["password"].orEmpty().takeUnless { settings.passwordFromEnv }.orEmpty(), encoder)
                "redirect:/settings"
            }
        }
    }

    /** Save in a Source map's editor: rewrites the file of the saved Source map [name], creating it if it isn't there. */
    @PostMapping("/settings/source-map")
    fun sourceMap(@RequestParam name: String, @RequestParam text: String): String {
        val file = settings.sourceMap(name) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Source map $name")
        file.writeAtomically(text.replace("\r\n", "\n"))
        return "redirect:/settings"
    }

    @PostMapping("/settings/instructions")
    fun instructions(@RequestParam name: String, @RequestParam text: String): String {
        val file = settings.instructions(name) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Profile $name")
        file.writeAtomically(text.replace("\r\n", "\n"))
        return "redirect:/settings"
    }

    private fun show(model: Model, form: SettingsForm, message: String? = null): String {
        model.addAttribute("form", form)
        // Each saved Source map's path and text, for its editor.
        model.addAttribute("maps", settings.sourceMaps().mapValues { (name, path) -> path to settings.sourceMap(name)?.takeIf { it.exists() }?.readText().orEmpty() })
        model.addAttribute("instructions", settings.profiles().keys.associateWith { name ->
            settings.hasInstructions(name) to settings.instructions(name)?.takeIf { it.exists() }?.readText().orEmpty()
        })
        // The Profile Set up Vault runs with for each saved Vault: the first that pairs it; with no Profiles, the default Vault's, blank.
        model.addAttribute("setUp", settings.profiles().ifEmpty { null }?.entries?.reversed()?.associate { (name, profile) -> profile.vault to name }
            ?: listOfNotNull(settings.defaultVault()).associateWith { "" })
        model.addAttribute("file", settings.file.toString())
        model.addAttribute("message", message)
        model.addAttribute("passwordFromEnv", settings.passwordFromEnv)
        model.addAttribute("login", login())
        model.addAttribute("home", home.toString())
        model.addAttribute("address", "${address.ifEmpty { "0.0.0.0" }}:$port")
        model.addAttribute("update", updates.current)
        model.addAttribute("docker", updates.docker)
        return "settings"
    }

    // Docker sets HOSTNAME to the container's ID, which `docker exec` takes.
    private fun login() = ClaudeLogin(launcher.login(), home.resolve("claude"), launcher.usage(), if (updates.docker) System.getenv("HOSTNAME") ?: "ants" else null)
}

/**
 * Ants' own Claude login as `claude auth status` reports it, the command that logs it in, and the latest usage.
 * In Docker, [container] names Ants' container, which the command runs in: the folder is on its volume, not the host.
 */
class ClaudeLogin(status: Map<*, *>, val folder: Path, usage: Map<*, *>, container: String? = null) {
    val loggedIn = status["loggedIn"] == true

    /** How it's logged in, such as `claude.ai, max`. */
    val method = listOfNotNull(status["authMethod"], status["subscriptionType"]).joinToString(", ")

    /** Where an API key comes from, when one is in use instead of the subscription login. */
    val apiKeySource = status["apiKeySource"]?.toString()?.takeIf { it != "none" }

    /** Run in a terminal; Ants never handles the credentials. */
    val command = "CLAUDE_CONFIG_DIR=${shellQuoted(folder.toString())} claude auth login"
        .let { if (container == null) it else "docker exec -it ${shellQuoted(container)} env $it" }

    /** Each window claude last reported, such as the 5-hour one at 4% that resets Sun 11 Oct 19:00. */
    val usage = listOf("five_hour" to "5-hour", "seven_day" to "7-day").mapNotNull { (key, name) ->
        val window = usage[key] as? Map<*, *> ?: return@mapNotNull null
        val used = (window["utilization"] as? Number ?: return@mapNotNull null).toDouble() * 100
        val resets = (window["resetsAt"] as? Number)?.let { TIME.format(Instant.ofEpochSecond(it.toLong()).atZone(ZoneId.systemDefault())) }
        Usage(name, used.roundToInt(), resets)
    }

    /** Such as `5-hour resets Sun 11 Oct 19:00, 7-day resets Fri 16 Oct 15:00.`, or empty. */
    val resets = this.usage.filter { it.resets != null }.joinToString(", ", postfix = ".") { "${it.window} resets ${it.resets}" }.takeIf { it != "." }.orEmpty()

    /** One usage window: its name, how much of it is used, in percent, and when it resets. */
    data class Usage(val window: String, val percent: Int, val resets: String?)

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)

        fun shellQuoted(word: String) = if (Regex("""^[\w/.,:@%+=-]+$""").matches(word)) word else "'" + word.replace("'", "'\\''") + "'"
    }
}

/** Every page's `color-scheme`, from the theme in settings. */
@ControllerAdvice
class ColorScheme(private val settings: Settings) {
    @ModelAttribute("colorScheme")
    fun colorScheme() = settings.colorScheme()
}

/** Named paths, such as the Vaults, and which one is the default (-1 for none picked). */
data class Named(val rows: List<Pair<String, String>>, val default: Int) {
    /** The rows plus an empty one, which adds the next. */
    val shown get() = rows + ("" to "")

    fun error(kind: String) = when {
        rows.any { (name, path) -> name.isEmpty() || path.isEmpty() } -> "Every $kind needs a name and a path."
        rows.map { it.first }.toSet().size < rows.size -> "Two ${kind}s have the same name."
        else -> null
    }
}

/** One Profile on the settings page: its name and the names of its Vault and Source map. */
data class ProfileRow(val name: String, val vault: String, val sourceMap: String)

/**
 * One Secret on the settings page: its label, its variable names, the value typed in, which the page never shows, the
 * label it was saved under, whose value a blank one keeps, and whether it has a value [set].
 */
data class SecretRow(val label: String, val names: String, val value: String = "", val was: String = "", val set: Boolean = false)

/** One Protected Command on the settings page: its prefix and the labels of the Secrets it runs with. */
data class ProtectedCommandRow(val prefix: String, val secrets: List<String>)

/** The settings page's form, filled from `settings.yaml` or from what the user posted. Empty means unset. */
data class SettingsForm(
    val paths: Map<String, String>,
    val commands: Map<String, String>,
    val envs: Map<String, String>,
    val vardeConfig: String,
    val agentTasks: String,
    val subagentDepth: String,
    val vaults: Named,
    val sourceMaps: Named,
    val profiles: List<ProfileRow>,
    /** Which of [profiles] is the default, -1 for none picked. */
    val defaultProfile: Int,
    val secrets: List<SecretRow>,
    val protectedCommands: List<ProtectedCommandRow>,
    val agentFolders: String,
    val skillFolders: String,
    val theme: String,
    val soFar: Boolean,
) {
    fun error() = when {
        agentTasks.isNotEmpty() && (agentTasks.toIntOrNull() ?: 0) < 1 -> "Agent Tasks at once must be a whole number, 1 or more."
        subagentDepth.isNotEmpty() && (subagentDepth.toIntOrNull() ?: 0) < 1 -> "Subagent depth must be a whole number, 1 or more."
        else -> vaults.error("Vault") ?: sourceMaps.error("Source map") ?: profileError() ?: secretError() ?: protectedCommandError()
    }

    /** The Profile rows plus an empty one, which adds the next. */
    val shownProfiles get() = profiles + ProfileRow("", "", "")

    /** The Secret rows plus an empty one, which adds the next. */
    val shownSecrets get() = secrets + SecretRow("", "")

    /** The Protected Command rows plus an empty one, which adds the next. */
    val shownProtectedCommands get() = protectedCommands + ProtectedCommandRow("", emptyList())

    private fun secretError() = when {
        secrets.any { it.label.isEmpty() || words(it.names).isEmpty() || (it.value.isEmpty() && it.was.isEmpty()) } -> "Every Secret needs a label, a value and a variable name."
        secrets.flatMap { words(it.names) }.any { !Regex("[A-Z_][A-Z0-9_]*").matches(it) } ->
            "A variable name is capitals, digits and underscores, such as NPM_TOKEN: the variable the command reads the Secret from."
        secrets.map { it.label }.toSet().size < secrets.size -> "Two Secrets have the same label."
        else -> null
    }

    private fun protectedCommandError() = protectedCommands.firstOrNull { row -> row.secrets.any { it !in secrets.map(SecretRow::label) } }
        ?.let { "The Protected Command ${it.prefix} needs Secrets from the list above." }
        ?: "Two Protected Commands have the same prefix.".takeIf { protectedCommands.map { it.prefix }.toSet().size < protectedCommands.size }

    private fun profileError(): String? {
        val vaultNames = vaults.rows.map { it.first }
        val mapNames = sourceMaps.rows.map { it.first }
        if (profiles.any { '/' in it.name || it.name in listOf(".", "..") }) return "A Profile's name is a folder name, so it can't hold a / or be . or .."
        return profiles.firstOrNull { it.vault !in vaultNames || it.sourceMap !in mapNames }?.let { "The Profile ${it.name} needs a Vault and a Source map from the lists above." }
            ?: "Two Profiles have the same name.".takeIf { profiles.map { it.name }.toSet().size < profiles.size }
    }

    /** This form with the AI CLI's command and env from Varde's `[ai]`, filled in for the CLI that command runs. */
    fun fromVarde(): SettingsForm {
        val file = Settings.expand(vardeConfig).toFile()
        val ai = runCatching { TomlMapper().readValue(file, Map::class.java)["ai"] as? Map<*, *> }
            .getOrElse { throw IllegalArgumentException("Can't read $vardeConfig as Varde's config.toml: ${it.message}") }
            ?: throw IllegalArgumentException("$vardeConfig has no [ai] table.")
        val command = Settings.strings(ai["command"])
        val cli = command?.lastOrNull { it.substringAfterLast('/') in Settings.AI_CLIS }?.substringAfterLast('/') ?: "claude"
        return copy(
            commands = commands + listOfNotNull(command?.let { cli to it.joinToString(" ") }),
            envs = envs + listOfNotNull(Settings.strings(ai["env"])?.let { cli to it.joinToString(" ") }),
        )
    }

    /**
     * Writes the form into `settings.yaml`, with [password] as the new password unless it's empty, and the Secrets'
     * values into `secrets.yaml`, a blank one keeping the value saved under the label it was.
     */
    fun save(settings: Settings, password: String, encoder: PasswordEncoder) {
        val saved = settings.secretValues()
        settings.saveSecretValues(secrets.mapNotNull { row -> row.value.ifEmpty { saved[row.was] }?.let { row.label to it } }.toMap())
        settings.save { values -> write(values, password, encoder) }
    }

    private fun write(values: MutableMap<String, Any?>, password: String, encoder: PasswordEncoder) {
        for (cli in Settings.CLIS) {
            val entry = LinkedHashMap<Any?, Any?>(values[cli] as? Map<*, *> ?: emptyMap<Any?, Any?>())
            entry.keep("path", paths[cli])
            if (cli in Settings.AI_CLIS) {
                entry.keep("command", words(commands[cli]))
                entry.keep("env", words(envs[cli]))
            }
            values.keep(cli, entry)
        }
        values.keep("varde-config", vardeConfig)
        values.keep("agent-tasks", agentTasks.toIntOrNull())
        values.keep("subagent-depth", subagentDepth.toIntOrNull())
        values.keep("vaults", vaults.rows.toMap())
        values.keep("default-vault", vaults.rows.getOrNull(vaults.default)?.first)
        values.keep("source-maps", sourceMaps.rows.toMap())
        values.keep("default-source-map", sourceMaps.rows.getOrNull(sourceMaps.default)?.first)
        values.keep("profiles", profiles.associate { it.name to mapOf("vault" to it.vault, "source-map" to it.sourceMap) })
        values.keep("default-profile", profiles.getOrNull(defaultProfile)?.name)
        values.keep("secrets", secrets.associate { it.label to words(it.names) })
        values.keep("protected-commands", protectedCommands.associate { it.prefix to it.secrets })
        values.keep("agent-folders", agentFolders.lines().map(String::trim).filter(String::isNotEmpty))
        values.keep("skill-folders", skillFolders.lines().map(String::trim).filter(String::isNotEmpty))
        values.keep("theme", theme.takeIf { it in listOf("light", "dark") })
        // Ticked keeps a hand-set time; unticked is `false`.
        values.keep("so-far", if (soFar) values["so-far"].takeIf { it != false && it != true } else false)
        if (password.isNotEmpty()) values["password"] = encoder.encode(password)
    }

    companion object {
        fun of(settings: Settings): SettingsForm {
            val values = settings.read()
            fun cli(cli: String) = values[cli] as? Map<*, *> ?: emptyMap<String, Any?>()
            fun words(value: Any?) = Settings.strings(value)?.joinToString(" ").orEmpty()
            fun named(names: Map<String, String>, default: String?) = Named(names.toList(), names.keys.indexOf(default))
            fun lines(value: Any?) = Settings.strings(value).orEmpty().joinToString("\n")
            return SettingsForm(
                paths = Settings.CLIS.associateWith { cli(it)["path"]?.toString().orEmpty() },
                commands = Settings.AI_CLIS.associateWith { words(cli(it)["command"]) },
                envs = Settings.AI_CLIS.associateWith { words(cli(it)["env"]) },
                vardeConfig = values["varde-config"]?.toString().orEmpty(),
                agentTasks = values["agent-tasks"]?.toString().orEmpty(),
                subagentDepth = values["subagent-depth"]?.toString().orEmpty(),
                vaults = named(settings.vaults(), settings.defaultVault()),
                sourceMaps = named(settings.sourceMaps(), settings.defaultSourceMap()),
                profiles = settings.profiles().map { (name, profile) -> ProfileRow(name, profile.vault.orEmpty(), profile.sourceMap.orEmpty()) },
                defaultProfile = settings.profiles().keys.indexOf(settings.defaultProfile()),
                secrets = settings.secretValues().keys.let { set ->
                    settings.secrets().map { (label, names) -> SecretRow(label, names.joinToString(", "), was = label, set = label in set) }
                },
                protectedCommands = settings.protectedCommands().map { (prefix, labels) -> ProtectedCommandRow(prefix, labels) },
                agentFolders = lines(values["agent-folders"]),
                skillFolders = lines(values["skill-folders"]),
                theme = values["theme"]?.toString() ?: "system",
                soFar = values["so-far"] != false,
            )
        }

        fun of(params: Map<String, String>): SettingsForm {
            fun param(name: String) = params[name].orEmpty().trim()
            fun named(list: String, default: String): Named {
                val all = generateSequence(0, Int::inc).takeWhile { "$list[$it].name" in params }
                    .map { param("$list[$it].name") to param("$list[$it].path") }.toList()
                val picked = param(default).toIntOrNull()?.let(all::getOrNull)
                val rows = all.filter { it != "" to "" }
                return Named(rows, rows.indexOf(picked))
            }
            // A row whose name is emptied is removed.
            val allProfiles = generateSequence(0, Int::inc).takeWhile { "profiles[$it].name" in params }
                .map { ProfileRow(param("profiles[$it].name"), param("profiles[$it].vault"), param("profiles[$it].source-map")) }.toList()
            val profiles = allProfiles.filter { it.name.isNotEmpty() }
            // The value is posted as typed, never trimmed.
            val secrets = generateSequence(0, Int::inc).takeWhile { "secrets[$it].label" in params }
                .map { SecretRow(param("secrets[$it].label"), param("secrets[$it].names"), params["secrets[$it].value"].orEmpty(), param("secrets[$it].was"), set = param("secrets[$it].was").isNotEmpty()) }
                .filter { it.label.isNotEmpty() }.toList()
            val commands = generateSequence(0, Int::inc).takeWhile { "protected-commands[$it].prefix" in params }
                .map { ProtectedCommandRow(Settings.strings(param("protected-commands[$it].prefix")).orEmpty().joinToString(" "), param("protected-commands[$it].secrets").lines().filter(String::isNotEmpty)) }
                .filter { it.prefix.isNotEmpty() }.toList()
            return SettingsForm(
                paths = Settings.CLIS.associateWith { param("$it.path") },
                commands = Settings.AI_CLIS.associateWith { param("$it.command") },
                envs = Settings.AI_CLIS.associateWith { param("$it.env") },
                vardeConfig = param("varde-config"),
                agentTasks = param("agent-tasks"),
                subagentDepth = param("subagent-depth"),
                vaults = named("vaults", "default-vault"),
                sourceMaps = named("source-maps", "default-source-map"),
                profiles = profiles,
                defaultProfile = profiles.indexOf(param("default-profile").toIntOrNull()?.let(allProfiles::getOrNull)),
                secrets = secrets,
                protectedCommands = commands,
                agentFolders = param("agent-folders"),
                skillFolders = param("skill-folders"),
                theme = param("theme").ifEmpty { "system" },
                soFar = "so-far" in params,
            )
        }

        /** Sets [key] to [value], or removes it when the value is empty, so an empty field leaves the default. */
        private fun <K> MutableMap<K, Any?>.keep(key: K, value: Any?) {
            val empty = value == null || value == "" || (value is Collection<*> && value.isEmpty()) || (value is Map<*, *> && value.isEmpty())
            if (empty) remove(key) else put(key, value)
        }
    }
}
