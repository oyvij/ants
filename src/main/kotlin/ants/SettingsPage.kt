package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import tools.jackson.dataformat.toml.TomlMapper
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The settings page: one form over `settings.yaml`. Detect and Fill from Varde fill the form; only Save writes. */
@Controller
class SettingsPage(
    private val settings: Settings,
    private val encoder: PasswordEncoder,
    private val launcher: AgentLauncher,
    @Value("\${ants.home}") private val home: Path,
) {
    @GetMapping("/settings")
    fun page(model: Model) = show(model, SettingsForm.of(settings))

    @PostMapping("/settings")
    fun press(@RequestParam params: Map<String, String>, model: Model): String {
        val form = SettingsForm.of(params)
        return when (params["action"]) {
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

    private fun show(model: Model, form: SettingsForm, message: String? = null): String {
        model.addAttribute("form", form)
        model.addAttribute("file", settings.file.toString())
        model.addAttribute("message", message)
        model.addAttribute("passwordFromEnv", settings.passwordFromEnv)
        model.addAttribute("login", ClaudeLogin(launcher.login(), home.resolve("claude"), launcher.usage()))
        return "settings"
    }
}

/** Ants' own Claude login as `claude auth status` reports it, the command that logs it in, and the latest usage. */
class ClaudeLogin(status: Map<*, *>, folder: Path, usage: Map<*, *>) {
    val loggedIn = status["loggedIn"] == true

    /** How it's logged in, such as `claude.ai, max`. */
    val method = listOfNotNull(status["authMethod"], status["subscriptionType"]).joinToString(", ")

    /** Where an API key comes from, when one is in use instead of the subscription login. */
    val apiKeySource = status["apiKeySource"]?.toString()?.takeIf { it != "none" }

    /** Run in a terminal; Ants never handles the credentials. */
    val command = "CLAUDE_CONFIG_DIR=${shellQuoted(folder.toString())} claude auth login"

    /** Such as `5-hour usage: 4%, resets Sun 11 Oct 19:00`, for each window claude last reported. */
    val usage = listOf("five_hour" to "5-hour", "seven_day" to "7-day").mapNotNull { (key, name) ->
        val window = usage[key] as? Map<*, *> ?: return@mapNotNull null
        val used = (window["utilization"] as? Number ?: return@mapNotNull null).toDouble() * 100
        val resets = (window["resetsAt"] as? Number)?.let { ", resets " + TIME.format(Instant.ofEpochSecond(it.toLong()).atZone(ZoneId.systemDefault())) }
        "$name usage: ${used.roundToInt()}%${resets.orEmpty()}"
    }

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
    val agentFolders: String,
    val skillFolders: String,
    val theme: String,
    val soFar: Boolean,
) {
    fun error() = when {
        agentTasks.isNotEmpty() && (agentTasks.toIntOrNull() ?: 0) < 1 -> "Agent Tasks at once must be a whole number, 1 or more."
        subagentDepth.isNotEmpty() && (subagentDepth.toIntOrNull() ?: 0) < 1 -> "Subagent depth must be a whole number, 1 or more."
        else -> vaults.error("Vault") ?: sourceMaps.error("Source map")
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

    /** Writes the form into `settings.yaml`, with [password] as the new password unless it's empty. */
    fun save(settings: Settings, password: String, encoder: PasswordEncoder) = settings.save { values ->
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
            return SettingsForm(
                paths = Settings.CLIS.associateWith { param("$it.path") },
                commands = Settings.AI_CLIS.associateWith { param("$it.command") },
                envs = Settings.AI_CLIS.associateWith { param("$it.env") },
                vardeConfig = param("varde-config"),
                agentTasks = param("agent-tasks"),
                subagentDepth = param("subagent-depth"),
                vaults = named("vaults", "default-vault"),
                sourceMaps = named("source-maps", "default-source-map"),
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
