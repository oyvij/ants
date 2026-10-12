package ants

import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.event.EventListener
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Clock
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.system.exitProcess

@SpringBootApplication
class AntsApplication {
    /** Where Ants reads the time, such as pause-gate deadlines; tests move it forward instead of waiting. */
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}

/** `ants.lock` in ANTS_HOME: Ants' pid and the URL it listens on, one a line, so `ants start` and `ants status` find it. */
@Component
class LockFile(@Value("\${ants.home}") home: Path) {
    private val file = home.resolve("ants.lock")
    private val pid = "${ProcessHandle.current().pid()}"

    @EventListener
    fun write(event: WebServerInitializedEvent) {
        val address = event.applicationContext.environment.getProperty("server.address").orEmpty().ifEmpty { "127.0.0.1" }
        file.writeAtomically("$pid\nhttp://$address:${event.webServer.port}\n")
    }

    @PreDestroy
    fun remove() {
        if (file.exists() && file.readLines().firstOrNull() == pid) file.deleteIfExists()
    }
}

/** `ants password`: asks for the new password twice and writes it, encoded, into `settings.yaml`. 0 when it changed. */
fun changePassword(): Int {
    val console = System.console()?.takeIf { it.isTerminal }
    val stdin = System.`in`.bufferedReader()
    val (password, again) = listOf("New password: ", "Again: ").map { prompt ->
        if (console != null) console.readPassword(prompt)?.let(::String).orEmpty() else stdin.readLine().orEmpty()
    }
    val refused = when {
        password.isEmpty() -> "The password is empty"
        password != again -> "The passwords differ"
        else -> null
    }
    if (refused != null) return 1.also { System.err.println("$refused; the password is unchanged") }
    val home = Path.of(System.getenv("ANTS_HOME") ?: "${System.getProperty("user.home")}/.ants").createDirectories()
    val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
    Settings(home, "127.0.0.1", System.getenv("SHELL") ?: "/bin/zsh", encoder).save { it["password"] = encoder.encode(password) }
    println("Password changed")
    return 0
}

fun main(args: Array<String>) {
    if (args.toList() == listOf("password")) exitProcess(changePassword())
    runApplication<AntsApplication>(*args)
}
