package ants

import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.info.BuildProperties
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus.FORBIDDEN
import org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.lang.module.ModuleDescriptor.Version
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.HOURS

/**
 * The update check, at start and every six hours: the newest release in ANTS_RELEASES (GitHub's latest by default),
 * which the nav offers with **Update** when it is newer than this Ants. Update runs the release's install.sh, which puts
 * it beside this version and links it as `current`, so Runs go on and it runs from the next `ants restart`; the version
 * it replaces stays for `ants rollback`. In Docker the nav only says it is out: the image is updated by pulling the new one.
 * The check also refreshes the model list from Anthropic's model status table (ANTS_MODELS).
 */
@Controller
class Updates(
    @Value("\${ants.home}") private val home: Path,
    @Value("\${ants.releases:https://github.com/oyvij/ants/releases/latest/download}") releases: String,
    @Value("\${ants.models:https://docs.claude.com/en/docs/about-claude/model-deprecations.md}") private val models: String,
    /** ANTS_DOCKER, which the image sets. */
    @Value("\${ants.docker:false}") private val docker: Boolean,
    build: BuildProperties,
    private val templates: TemplateEngine,
    private val streams: Streams,
) {
    private val releases = releases.removeSuffix("/")
    private val version = Version.parse(build.version)
    private val log = LoggerFactory.getLogger(javaClass)
    private val loop = Executors.newSingleThreadScheduledExecutor()

    /** The newest release's version, when it is newer than this Ants. */
    @Volatile
    private var newer: String? = null

    @Volatile
    private var downloading = false

    /** The release Update installed, which runs from the next restart. */
    @Volatile
    private var installed: String? = null

    /** Why the last Update failed. */
    @Volatile
    private var failed: String? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        loop.scheduleWithFixedDelay(::check, 0, 6, HOURS)
    }

    @PreDestroy
    fun shutdown() {
        loop.shutdownNow()
    }

    /**
     * Reads the newest release's version from its SHA256SUMS, which names its tarball, and refreshes the model list from
     * the model status table. Offline, the last answers stand.
     */
    fun check() {
        try {
            val sums = URI("$releases/SHA256SUMS").toURL().readText()
            val release = Regex("""ants-(\S+)-macos-arm64\.tar\.gz""").find(sums)?.groupValues?.get(1)
            newer = release?.takeIf { Version.parse(it) > version }
        } catch (e: Exception) {
            log.info("No update check: $e")
        }
        try {
            Models.refresh(URI(models).toURL().readText())
        } catch (e: Exception) {
            log.info("The model list wasn't refreshed: $e")
        }
    }

    /** The nav's update notice as HTML: a newer release with **Update**, how Update is going, or nothing. */
    @GetMapping("/update", produces = [TEXT_EVENT_STREAM_VALUE])
    @ResponseBody
    fun notice(csrf: CsrfToken): SseEmitter {
        val token = DefaultCsrfToken(csrf.headerName, csrf.parameterName, csrf.token) // read now, while the request lives
        return streams.changes {
            val params = mapOf("newer" to newer, "downloading" to downloading, "installed" to installed, "failed" to failed, "docker" to docker, "csrf" to token)
            StringOutput().also { templates.render("update.kte", params, it) }.toString().trim()
        }
    }

    /** **Update**: installs the newer release in the background and goes back to the page it was on. Docker refuses it. */
    @PostMapping("/update")
    fun update(@RequestHeader("Referer", required = false) from: String?): String {
        if (docker) throw ResponseStatusException(FORBIDDEN, "Ants in Docker is updated by pulling the new image")
        synchronized(this) {
            val release = newer
            if (release != null && !downloading) {
                downloading = true
                Thread.ofVirtual().start { install(release) }
            }
        }
        return "redirect:" + (from?.let { runCatching { URI(it).path }.getOrNull() }?.ifEmpty { null } ?: "/")
    }

    /** Runs [release]'s install.sh as `curl … | bash -s -- --update`, the way the user installed Ants. */
    private fun install(release: String) {
        try {
            val install = ProcessBuilder("/bin/bash", "-o", "pipefail", "-c", "curl -fsSL \"\$ANTS_RELEASES/install.sh\" | bash -s -- --update")
                .redirectErrorStream(true)
                .apply { environment() += mapOf("ANTS_HOME" to "$home", "ANTS_RELEASES" to releases) }
                .start()
            val output = install.inputReader().readText()
            if (install.waitFor() == 0) {
                installed = release
                failed = null
            } else {
                failed = output.lines().lastOrNull { it.isNotBlank() } ?: "install.sh exited ${install.exitValue()}"
            }
        } catch (e: Exception) {
            failed = "$e"
        } finally {
            downloading = false
        }
        failed?.let { log.warn("Update to Ants $release failed: $it") }
    }
}
