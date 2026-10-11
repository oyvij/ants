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
                # Ants settings. Edit by hand or on the Settings page; Ants reads this file as it goes.
                # password: the login password, encoded. Until it is set, the login is admin/admin.
                
                """.trimIndent(),
            )
        }
        check(address.isNotEmpty() && InetAddress.getByName(address).isLoopbackAddress || !encoder.matches("admin", password())) {
            "Ants won't listen on ${address.ifEmpty { "every address" }} with the default password. Change the password first."
        }
    }

    /** The encoded login password. */
    fun password(): String = read()["password"] as? String ?: "{noop}admin"

    private fun read(): Map<String, Any?> = Yaml().load(file.readText()) ?: emptyMap()
}
