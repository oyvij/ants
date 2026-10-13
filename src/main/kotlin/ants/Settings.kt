package ants

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.InetAddress
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/** One repo in a Source map: its short name, its local checkout and its git remote. */
data class Repo(val name: String, val path: String, val remote: String)

/** A Profile: the names of the Vault and the Source map it pairs, null where it names none. */
data class Profile(val vault: String?, val sourceMap: String?)

/** `settings.yaml` in ANTS_HOME, created on first start ([start]) and read fresh each time, so hand edits count. */
@Component
class Settings(
    @Value("\${ants.home}") home: Path,
    @Value("\${server.address:}") private val address: String,
    @Value("\${ants.shell}") private val shell: String,
    private val encoder: PasswordEncoder,
    @Value("\${ants.password:}") password: String,
    @Value("\${ants.password-file:}") passwordFile: String,
    /** ANTS_DOCKER, which the image sets: Ants runs in its container. */
    @Value("\${ants.docker:false}") private val docker: Boolean,
) {
    /** ANTS_PASSWORD, else the file ANTS_PASSWORD_FILE names (a Docker secret); either wins over `settings.yaml`'s. */
    private val envPassword = when {
        password.isNotEmpty() -> password
        passwordFile.isNotEmpty() -> Path.of(passwordFile).readText().removeSuffix("\n")
        else -> ""
    }

    /** ANTS_PASSWORD or ANTS_PASSWORD_FILE sets the password, so neither the settings page nor `ants password` changes it. */
    val passwordFromEnv get() = envPassword.isNotEmpty()

    /** The file itself, as the settings page names it. */
    val file: Path = home.resolve("settings.yaml")

    /**
     * At start, Ants creates the file, with the CLIs Detect finds while none is in it yet (`ants password` may have
     * written only a password), and refuses to start in Docker without a password from the environment, or off
     * loopback with the default one. `ants password` runs none of this, so it never runs the login shell.
     */
    @PostConstruct
    fun start() {
        if (CLIS.none { it in read() }) {
            val found = detect()
            save { values -> found.forEach { (cli, path) -> values[cli] = mapOf("path" to path) } }
        }
        check(!docker || passwordFromEnv) { "Ants in Docker needs a password: set ANTS_PASSWORD or ANTS_PASSWORD_FILE." }
        val loopback = address.isNotEmpty() && InetAddress.getByName(address).isLoopbackAddress
        check(loopback || !encoder.matches("admin", password())) {
            "Ants won't listen on ${address.ifEmpty { "every address" }} with the default password. Change the password first."
        }
    }

    /** The login password, from the environment else the file, encoded; a plain one reads as `{noop}`. */
    fun password(): String {
        val password = envPassword.ifEmpty { read()["password"]?.toString() ?: "admin" }
        return if (password.startsWith("{")) password else "{noop}$password"
    }

    // ponytail: one global cap, no per-CLI caps or priorities
    fun agentTasks(): Int = (read()["agent-tasks"] as? Int) ?: 3

    /**
     * How often a running Task's So far is rewritten (default 2 minutes), or null when it is switched off. A value
     * that isn't a time, such as `90s` or `5m`, gives the default, with one warning per value.
     */
    fun soFar(): Duration? = when (val value = read()["so-far"]) {
        false -> null
        null, true -> SO_FAR
        else -> runCatching { Duration.parse("PT$value".uppercase()) }.getOrNull()?.takeIf { it.isPositive } ?: SO_FAR.also {
            if (badSoFar.getAndSet("$value") != "$value") log.warn("so-far: $value in $file isn't a time such as 90s or 5m; it is rewritten every 2m")
        }
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val badSoFar = AtomicReference<String>()

    /** How deep claude's Subagents may nest: 1 lets a Task start Subagents, and them none of their own. */
    fun subagentDepth(): Int = (read()["subagent-depth"] as? Int) ?: 1

    /** Where [cli] lives, as Detect found it or the user set it. */
    fun path(cli: String): String? = cli(cli)["path"]?.toString()

    /** The command prefix for [cli]: the command the user set, else the CLI's path, else its bare name. */
    fun command(cli: String): List<String> = strings(cli(cli)["command"]) ?: listOf(path(cli) ?: cli)

    /** The environment variables an Agent on [cli] may see. */
    fun env(cli: String): List<String> = strings(cli(cli)["env"]) ?: DEFAULT_ENV

    /** The named Vaults, name to path. */
    fun vaults(): Map<String, String> = named("vaults")

    /** The default Vault's name: the one the user picked, else the first. */
    fun defaultVault(): String? = default("vaults", "default-vault")

    /** The named Source maps, name to path. */
    fun sourceMaps(): Map<String, String> = named("source-maps")

    /** The default Source map's name: the one the user picked, else the first. */
    fun defaultSourceMap(): String? = default("source-maps", "default-source-map")

    /** The Source map [name]'s file. */
    fun sourceMap(name: String?): Path? = sourceMaps()[name]?.let(::expand)

    /**
     * The repos in the Source map [name], read from its file in Varde's format, one `- <name>: <local path> (<git
     * remote>)` line per repo. Other lines, and a file that isn't there, give none.
     */
    fun repos(name: String?): List<Repo> {
        val file = sourceMap(name)?.takeIf { it.exists() } ?: return emptyList()
        return file.readText().lines().mapNotNull { Regex("""^- ([^:]+): (.+?) \((.+)\)$""").find(it.trim())?.destructured }
            .map { (repo, path, remote) -> Repo(repo.trim(), path, remote) }
    }

    /** The named Profiles, each a Vault and a Source map by name. */
    fun profiles(): Map<String, Profile> = (read()["profiles"] as? Map<*, *>).orEmpty().entries.associate { (name, value) ->
        val profile = value as? Map<*, *> ?: emptyMap<String, Any?>()
        "$name" to Profile(profile["vault"]?.toString(), profile["source-map"]?.toString())
    }

    /** The default Profile's name: the one the user picked, else the first; null with none. */
    fun defaultProfile(): String? = profiles().keys.let { names -> read()["default-profile"]?.toString()?.takeIf { it in names } ?: names.firstOrNull() }

    /**
     * The Profile [name], by default the default one; null when no Profile has that name. With no Profiles at all
     * there is just one, whatever [name] says: the default Vault and the default Source map.
     */
    fun profile(name: String?): Profile? = profiles().ifEmpty { return Profile(defaultVault(), defaultSourceMap()) }[name ?: defaultProfile()]

    /** The extra Agent folders, read after Ants' own. */
    fun agentFolders(): List<Path> = folders("agent-folders")

    /** The extra Skill folders, read after Ants' own. */
    fun skillFolders(): List<Path> = folders("skill-folders")

    /** The pages' `color-scheme`: the system's until the user picks light or dark. */
    fun colorScheme(): String = read()["theme"]?.toString()?.takeIf { it in listOf("light", "dark") } ?: "light dark"

    /** Every key in the file, as YAML reads it. */
    fun read(): Map<String, Any?> = if (file.exists()) Yaml().load(file.readText()) ?: emptyMap() else emptyMap()

    /** Rewrites the file with [change] applied to its keys. Keys it doesn't touch are kept; comments are not. */
    @Synchronized
    fun save(change: (MutableMap<String, Any?>) -> Unit) {
        val values = LinkedHashMap(read()).also(change)
        file.writeAtomically(HEADER + if (values.isEmpty()) "" else yaml.dump(values))
    }

    /** Finds each CLI through the user's login shell, which sees the PATH their profile sets. A missing one is left out. */
    fun detect(): Map<String, String> {
        val script = CLIS.joinToString("; ") { "printf '$it=%s\\n' \"$(command -v $it)\"" }
        val process = ProcessBuilder(shell, "-l", "-c", script)
            .redirectInput(File("/dev/null"))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (!process.waitFor(10, SECONDS)) process.destroyForcibly() // a profile that waits on something must not hang Ants
        // A profile may print too, so only `<cli>=<absolute path>` lines count; an alias or a function isn't a path.
        return process.inputReader().readLines().mapNotNull { Regex("""^(\w+)=(/.+)$""").find(it)?.destructured }
            .associate { (cli, path) -> cli to path }.filterKeys { it in CLIS }
    }

    private fun cli(cli: String) = read()[cli] as? Map<*, *> ?: emptyMap<String, Any?>()

    private fun named(key: String) = (read()[key] as? Map<*, *>).orEmpty().entries.associate { (name, path) -> "$name" to "$path" }

    private fun default(list: String, key: String) =
        named(list).keys.let { names -> read()[key]?.toString()?.takeIf { it in names } ?: names.firstOrNull() }

    private fun folders(key: String) = strings(read()[key]).orEmpty().map(::expand)

    companion object {
        /** The CLIs Ants finds paths for. */
        val CLIS = listOf("claude", "codex", "git", "gh")

        /** The AI CLIs, which also have a command and an environment allow-list. */
        val AI_CLIS = listOf("claude", "codex")

        private val SO_FAR: Duration = Duration.ofMinutes(2)

        val DEFAULT_ENV = listOf("PATH", "HOME", "USER", "LOGNAME", "SHELL", "TMPDIR", "LANG", "LC_ALL", "LC_CTYPE", "TERM")

        /**
         * A YAML list, or a string read as space-separated words, so `command: claude` works too, as do a Skill's
         * `ants-uses` names.
         */
        // ponytail: a string splits on spaces, so a path with a space in it needs the YAML list form
        fun strings(value: Any?) = when (value) {
            null -> null
            is List<*> -> value.map(Any?::toString)
            else -> value.toString().split(Regex("\\s+")).filter(String::isNotEmpty)
        }

        /** [path] with a leading `~` read as the user's home. */
        fun expand(path: String): Path = Path.of(path.replaceFirst(Regex("^~(?=/|$)"), Regex.escapeReplacement(System.getProperty("user.home"))))

        private val HEADER = """
            # Ants settings. Ants reads this file as it goes, so hand edits count. Saving the settings page rewrites it.
            # password: the login password, plain or encoded ({bcrypt}…). Until it is set, the login is admin/admin.
            # agent-tasks: how many Agent Tasks run at once, across every Run (default 3).
            # subagent-depth: how deep claude's Subagents may nest (default 1: a Task's Subagents start none of their own).
            # claude, codex, git, gh:
            #   path: where the CLI lives, as Detect found it through the login shell
            #   command: [claude]  claude and codex only: the command Ants appends its flags to, e.g. [nono, run, --, claude]
            #   env: [PATH, HOME]  claude and codex only: the environment allow-list; Ants may narrow it, never widen it
            # varde-config: Varde's config.toml, whose [ai] command and env the settings page fills in
            # vaults, source-maps: name: path. default-vault, default-source-map: a name; without one, the first.
            # profiles: name: {vault: <a Vault's name>, source-map: <a Source map's name>}. default-profile: a name;
            #   without one, the first. With no Profiles, every Run uses the default Vault and Source map.
            # agent-folders, skill-folders: extra folders, read after Ants' own
            # theme: light or dark; without it, the pages follow the system
            # so-far: false switches off the So far paragraph on running Tasks; a time (90s, 5m) sets how often it's
            #   rewritten (default 2m)

            """.trimIndent() + "\n"
    }
}

/** `ants password`: asks for the new password twice and writes it, encoded, into `settings.yaml`. 0 when it changed. */
fun changePassword(environment: Environment): Int {
    val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
    val settings = Settings(
        home = Path.of(environment.getRequiredProperty("ants.home")).createDirectories(),
        address = environment.getProperty("server.address", ""),
        shell = environment.getRequiredProperty("ants.shell"),
        encoder = encoder,
        password = environment.getProperty("ants.password", ""),
        passwordFile = environment.getProperty("ants.password-file", ""),
        docker = environment.getProperty("ants.docker", Boolean::class.java, false),
    )
    fun refuse(why: String) = 1.also { System.err.println("$why; the password is unchanged") }
    if (settings.passwordFromEnv) return refuse("ANTS_PASSWORD or ANTS_PASSWORD_FILE sets the password")
    val console = System.console()?.takeIf { it.isTerminal }
    val stdin = System.`in`.bufferedReader()
    val (password, again) = listOf("New password: ", "Again: ").map { prompt ->
        if (console != null) console.readPassword(prompt)?.let(::String).orEmpty() else stdin.readLine().orEmpty()
    }
    if (password.isEmpty()) return refuse("The password is empty")
    if (password != again) return refuse("The passwords differ")
    settings.save { it["password"] = encoder.encode(password) }
    println("Password changed")
    return 0
}
