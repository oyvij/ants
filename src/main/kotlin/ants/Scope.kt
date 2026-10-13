package ants

import org.operaton.bpm.engine.HistoryService
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * The Run variable holding the repos the user picked on the start form, the entries of its Repos fields with each
 * Source map name given as its remote: git URLs, and `owner/name` for a repo Ants creates. Unset when none was picked.
 */
const val SCOPE = "ants.scope"

/** Whether a Repos entry names a new repo for Ants to create on GitHub: `owner/name`, with no scheme, host or path. */
fun newRepo(entry: String) = Regex("[A-Za-z0-9-]+/[A-Za-z0-9._-]+").matches(entry) && !entry.endsWith(".git")

/** The Run variable holding the name of the Profile a Run uses; unset with no Profiles configured. */
const val PROFILE = "ants.profile"

/**
 * What a Run draws on: its Profile [name] ([PROFILE], the default one when null) and the repos the user [picked]
 * ([SCOPE]). Which Vault, Source map and repos a Run uses is decided here alone: its Profile's, none for a Profile no
 * longer in the settings. Picked repos never change that (ADR 0014).
 */
data class RunSource(val name: String?, val picked: List<String> = emptyList()) {
    /** The Profile the Run uses; none for a Profile no longer in [settings]. */
    fun profile(settings: Settings): Profile? = settings.profile(name)

    /** The Vault's folder the Run uses; none with no Vault configured. */
    fun vault(settings: Settings): Path? = profile(settings)?.vault?.let { settings.vaults()[it] }?.let(Settings::expand)

    /** The Source map's file the Run uses. */
    fun sourceMap(settings: Settings): Path? = profile(settings)?.sourceMap?.let(settings::sourceMap)

    /** The Source map's repos the Run uses, and its Repos field offers. */
    fun repos(settings: Settings): List<Repo> = profile(settings)?.sourceMap?.let(settings::repos).orEmpty()

    companion object {
        /**
         * The source a Run keeps as it started, from its [SCOPE] and [PROFILE] variables as [variable] reads them. A Run
         * started before ADR 0014, scoped with no Profile, reads as one with the default Profile.
         */
        fun read(variable: (String) -> Any?): RunSource =
            RunSource(variable(PROFILE) as? String, (variable(SCOPE) as? List<*>).orEmpty().map(Any?::toString))
    }
}

/** The source of each Run, as it started ([RunSource.read]). */
@Component
class Scopes(private val history: HistoryService) {
    fun of(run: String): RunSource = RunSource.read { history.variable(run, it) }
}
