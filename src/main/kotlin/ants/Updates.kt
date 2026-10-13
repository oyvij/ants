package ants

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.info.BuildProperties
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus.FORBIDDEN
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.server.ResponseStatusException
import java.lang.module.ModuleDescriptor.Version
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS

/** The release tarball's name, as build.gradle.kts names it and SHA256SUMS lists it. */
fun tarball(version: String) = "ants-$version-macos-arm64.tar.gz"

/** What the nav says about updates: the newest release, when it is newer than this Ants, and how Update went. */
data class UpdateNotice(val newer: String? = null, val downloading: Boolean = false, val installed: String? = null, val failed: String? = null)

/**
 * The update check, at start and every six hours (`ants.update-every`): the newest release in ANTS_RELEASES (GitHub's
 * latest by default), which the nav offers with **Update** when it is newer than this Ants. Update runs the release's
 * install.sh, which puts it beside this version and links it as `current`, so Runs go on and it runs from the next
 * `ants restart`; the version it replaces stays for `ants rollback`. In Docker the nav only says it is out: the image is
 * updated by pulling the new one. The check also refreshes the model list from Anthropic's model status table (ANTS_MODELS).
 */
@Controller
class Updates(
    @Value("\${ants.home}") private val home: Path,
    @Value("\${ants.releases:https://github.com/oyvij/ants/releases/latest/download}") releases: String,
    @Value("\${ants.update-every:6h}") private val every: Duration,
    /** ANTS_DOCKER, which the image sets. */
    @Value("\${ants.docker:false}") val docker: Boolean,
    build: BuildProperties,
    private val models: Models,
    private val streams: Streams,
) {
    private val releases = releases.removeSuffix("/")
    private val version = Version.parse(build.version)
    private val log = LoggerFactory.getLogger(javaClass)
    private val loop = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var notice = UpdateNotice()

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        loop.scheduleWithFixedDelay({ check() }, 0, every.toMillis(), MILLISECONDS)
    }

    /** What the last check found, and how Update went, for the settings page's Updates card. */
    val current get() = notice

    /** Settings' **Check now**: checks at once, and says what it found. */
    fun checkNow(): Toast = when {
        !check() -> Toast("Couldn't check", "the release list didn't answer; the log says why.")
        else -> notice.newer?.let { Toast("Checked", "Ants $it is available.") } ?: Toast("Checked", "nothing newer.")
    }

    @PreDestroy
    fun shutdown() {
        loop.shutdownNow()
    }

    /**
     * Reads the newest release's version from its SHA256SUMS, which names its tarball, and refreshes the model list from
     * the model status table. Offline, the last answers stand. False when the release couldn't be read.
     */
    private fun check(): Boolean {
        val read = try {
            val sums = URI("$releases/SHA256SUMS").toURL().readText()
            val release = Regex(tarball("""(\S+)""")).find(sums)?.groupValues?.get(1) // its dots match any character too
            synchronized(this) { notice = notice.copy(newer = release?.takeIf { Version.parse(it) > version }) }
            true
        } catch (e: Exception) {
            log.info("No update check: $e")
            false
        }
        try {
            models.check()
        } catch (e: Exception) {
            log.info("The model list wasn't refreshed: $e")
        }
        return read
    }

    /** The nav's update notice as HTML: a newer release with **Update**, how Update is going, or nothing. */
    fun notice(csrf: CsrfToken): Part = streams.rendered("update", "update.kte", csrf) { mapOf("notice" to notice, "docker" to docker) }

    /** **Update** to [release], the newer one the nav offers: installs it in the background and goes back. Docker refuses it. */
    @PostMapping("/updates/{release}")
    fun update(@PathVariable release: String, @RequestHeader("Referer", required = false) from: String?): String {
        if (docker) throw ResponseStatusException(FORBIDDEN, "Ants in Docker is updated by pulling the new image")
        synchronized(this) {
            if (notice.newer == release && !notice.downloading) {
                notice = notice.copy(downloading = true)
                Thread.ofVirtual().start { install(release) }
            }
        }
        return back(from)
    }

    /**
     * Runs the newest release's install.sh as `curl … | bash -s -- --update <this version>`, the way the user installed
     * Ants. The release it installed is the one install.sh names, which is newer than [release] if one came out since the check.
     */
    private fun install(release: String) {
        val failed = try {
            val install = ProcessBuilder("/bin/bash", "-o", "pipefail", "-c", "curl -fsSL \"\$ANTS_RELEASES/install.sh\" | bash -s -- --update $version")
                .redirectErrorStream(true)
                .apply { environment() += mapOf("ANTS_HOME" to "$home", "ANTS_RELEASES" to releases) }
                .start()
            val output = install.inputReader().readText()
            val installed = Regex("""^Installed Ants (\S+)""", RegexOption.MULTILINE).find(output)?.groupValues?.get(1)
            if (install.waitFor() == 0 && installed != null) {
                synchronized(this) { notice = UpdateNotice(newer = installed, installed = installed) }
                return
            }
            output.lines().lastOrNull { it.isNotBlank() } ?: "install.sh exited ${install.exitValue()}"
        } catch (e: Exception) {
            "$e"
        }
        log.warn("Update to Ants $release failed: $failed")
        synchronized(this) { notice = notice.copy(downloading = false, failed = failed) }
    }
}
