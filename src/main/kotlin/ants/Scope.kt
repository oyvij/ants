package ants

import org.operaton.bpm.engine.HistoryService
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * The Run variable holding the repos a scoped Run is scoped to, the entries of its start form's Repos fields with each
 * Source map name given as its remote: git URLs, and `owner/name` for a repo Ants creates. Unset on an unscoped Run.
 */
const val SCOPE = "ants.scope"

/** Whether a Repos entry names a new repo for Ants to create on GitHub: `owner/name`, with no scheme, host or path. */
fun newRepo(entry: String) = Regex("[A-Za-z0-9-]+/[A-Za-z0-9._-]+").matches(entry) && !entry.endsWith(".git")

/** The Run variable holding the name of the Profile a Run uses; unset with no Profiles configured, or in a scoped Run. */
const val PROFILE = "ants.profile"

/**
 * What a Run draws on: the repos it is [Scoped] to, or its [Profiled] Profile. Which Vault, Source map and repos a Run
 * uses is decided here alone: none for a scoped Run, else its Profile's, and none for a Profile no longer in the settings.
 */
sealed interface RunSource {
    /** A Run scoped to [scope] ([SCOPE]): no Profile, Vault or Source map. */
    data class Scoped(val scope: List<String>) : RunSource

    /** A Run using the Profile [name] ([PROFILE]), the default one when null. */
    data class Profiled(val name: String?) : RunSource

    /** The Profile the Run uses; none when scoped, or for a Profile no longer in [settings]. */
    fun profile(settings: Settings): Profile? = (this as? Profiled)?.let { settings.profile(it.name) }

    /** The Vault's folder the Run uses; none when scoped, or with no Vault configured. */
    fun vault(settings: Settings): Path? = profile(settings)?.vault?.let { settings.vaults()[it] }?.let(Settings::expand)

    /** The Source map's file the Run uses; none when scoped. */
    fun sourceMap(settings: Settings): Path? = profile(settings)?.sourceMap?.let(settings::sourceMap)

    /** The Source map's repos the Run uses, and its Repos field offers; none when scoped. */
    fun repos(settings: Settings): List<Repo> = profile(settings)?.sourceMap?.let(settings::repos).orEmpty()

    companion object {
        /** The source of a Run started with the Repos entries [repos] and the Profile [profile]: scoped with any entry. */
        fun of(repos: List<String>, profile: String?): RunSource = if (repos.isEmpty()) Profiled(profile) else Scoped(repos)

        /** The source a Run keeps as it started, from its [SCOPE] and [PROFILE] variables as [variable] reads them. */
        fun read(variable: (String) -> Any?): RunSource =
            (variable(SCOPE) as? List<*>)?.let { scope -> Scoped(scope.map(Any?::toString)) } ?: Profiled(variable(PROFILE) as? String)
    }
}

/** The source of each Run, as it started ([RunSource.read]). */
@Component
class Scopes(private val history: HistoryService) {
    fun of(run: String): RunSource = RunSource.read { history.variable(run, it) }
}
