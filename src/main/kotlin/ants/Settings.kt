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

    private fun read(): Map<String, Any?> = Yaml().load(file.readText()) ?: emptyMap()
}
