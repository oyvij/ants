package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.Yaml
import java.net.InetAddress
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** `settings.yaml` in ANTS_HOME, created on first start and read fresh each time, so hand edits count. */
@Component
class Settings(
    @Value("\${ants.home}") home: Path,
    @Value("\${server.address:}") address: String,
    private val encoder: PasswordEncoder,
) {
    private val file = home.resolve("settings.yaml")

    init {
        if (!file.exists()) {
            home.createDirectories()
            file.writeText(
                """
                # Ants settings. Ants reads this file as it goes, so hand edits count.
                # password: the login password, plain or encoded ({bcrypt}…). Until it is set, the login is admin/admin.
                # agent-tasks: how many Agent Tasks run at once, across every Run (default 3).
                # claude:
                #   command: [claude]  the command prefix Ants appends its flags to, e.g. [nono, run, --, claude]
                #   env: [PATH, HOME]  the environment allow-list; Ants may narrow it, never widen it
                
                """.trimIndent(),
            )
        }
        val loopback = address.isNotEmpty() && InetAddress.getByName(address).isLoopbackAddress
        check(loopback || !encoder.matches("admin", password())) {
            "Ants won't listen on ${address.ifEmpty { "every address" }} with the default password. Change the password first."
        }
    }

    /** The login password, encoded; a plain one in the file reads as `{noop}`. */
    fun password(): String {
        val password = read()["password"]?.toString() ?: "admin"
        return if (password.startsWith("{")) password else "{noop}$password"
    }

    // ponytail: one global cap, no per-CLI caps or priorities
    fun agentTasks(): Int = (read()["agent-tasks"] as? Int) ?: 3

    /** The command prefix for [cli]. */
    fun command(cli: String): List<String> = strings(cli(cli)["command"]) ?: listOf(cli)

    /** The environment variables an Agent on [cli] may see. */
    fun env(cli: String): List<String> = strings(cli(cli)["env"])
        ?: listOf("PATH", "HOME", "USER", "LOGNAME", "SHELL", "TMPDIR", "LANG", "LC_ALL", "LC_CTYPE", "TERM")

    private fun cli(cli: String) = read()[cli] as? Map<*, *> ?: emptyMap<String, Any?>()

    /** A YAML list, or a string read as space-separated words, so `command: claude` works too. */
    private fun strings(value: Any?) = when (value) {
        null -> null
        is List<*> -> value.map(Any?::toString)
        else -> value.toString().split(' ').filter(String::isNotEmpty)
    }

    private fun read(): Map<String, Any?> = Yaml().load(file.readText()) ?: emptyMap()
}
