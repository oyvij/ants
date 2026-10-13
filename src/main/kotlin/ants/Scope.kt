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

// Which Vault and Source map a Run uses, decided here alone: none for a Run scoped to its repos ([scope] set), else
// its [profile]'s (the default Profile's when null), and none for a Profile no longer in the settings.

/** The Vault's folder a Run with [scope] and [profile] uses; none when scoped, or with no Vault configured. */
fun Settings.runVault(scope: List<String>?, profile: String?): Path? =
    if (scope != null) null else profile(profile)?.vault?.let { vaults()[it] }?.let(Settings::expand)

/** The Source map's file a Run with [scope] and [profile] uses; none when scoped. */
fun Settings.runSourceMap(scope: List<String>?, profile: String?): Path? = if (scope != null) null else profile(profile)?.sourceMap?.let(::sourceMap)

/** The Source map's repos a Run with [scope] and [profile] uses, and its Repos field offers; none when scoped. */
fun Settings.runRepos(scope: List<String>?, profile: String?): List<Repo> = if (scope != null) emptyList() else profile(profile)?.sourceMap?.let(::repos).orEmpty()

/** The scope and Profile of a Run, as it started ([SCOPE], [PROFILE]). */
@Component
class Scopes(private val history: HistoryService) {
    /** The repos [run] is scoped to, or null when it isn't. */
    fun of(run: String): List<String>? = (variable(run, SCOPE) as? List<*>)?.map(Any?::toString)

    /** The name of the Profile [run] uses, or null for the default one. */
    fun profile(run: String): String? = variable(run, PROFILE) as? String

    private fun variable(run: String, name: String) = history.createHistoricVariableInstanceQuery().processInstanceId(run).variableName(name).singleResult()?.value
}
