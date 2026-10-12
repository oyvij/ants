package ants

import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * `ants.lock` in ANTS_HOME: Ants' pid, written first thing at start ([take]), and the URL it listens on, added once it
 * listens, one a line, so a second Ants refuses to start and `ants start` and `ants status` find the first.
 */
@Component
class LockFile(@Value("\${ants.home}") home: Path) {
    private val file = home.resolve(NAME)

    @EventListener
    fun write(event: WebServerInitializedEvent) {
        // An empty server.address listens on every address, the machine's own included.
        val address = event.applicationContext.environment.getProperty("server.address").orEmpty().ifEmpty { "localhost" }
        file.writeAtomically("$pid\nhttp://$address:${event.webServer.port}\n")
    }

    @PreDestroy
    fun remove() {
        if (file.exists() && file.readLines().firstOrNull() == "$pid") file.deleteIfExists()
    }

    companion object {
        private const val NAME = "ants.lock"
        private val pid = ProcessHandle.current().pid()

        /** Takes the lock in [home] for this Ants, or says which live Ants holds it and where it runs. */
        // ponytail: read, then write, so two Ants started in the same instant may both take it; a file lock if that bites
        fun take(home: Path): String? {
            val file = home.resolve(NAME)
            val lines = if (file.exists()) file.readLines() else emptyList()
            val holder = lines.firstOrNull()?.toLongOrNull()?.takeIf { it != pid && ProcessHandle.of(it).isPresent }
            if (holder != null) return lines.getOrNull(1)?.let { "Ants is already running at $it (pid $holder)" } ?: "Ants is already starting (pid $holder)"
            file.writeAtomically("$pid\n")
            return null
        }
    }
}
