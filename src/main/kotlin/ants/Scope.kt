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

// Which Vault and Source map a Run uses, decided here alone: none for a Run scoped to its repos ([scope] set), else
// the default ones. ponytail: the defaults, until a Run picks its group (#90).

/** The Vault's folder a Run with [scope] uses; none when scoped, or with no Vault configured. */
fun Settings.runVault(scope: List<String>?): Path? = if (scope != null) null else vaults()[defaultVault()]?.let(Settings::expand)

/** The Source map's file a Run with [scope] uses; none when scoped. */
fun Settings.runSourceMap(scope: List<String>?): Path? = if (scope != null) null else sourceMap()

/** The Source map's repos a Run with [scope] uses, and the Repos field offers; none when scoped. */
fun Settings.runRepos(scope: List<String>?): List<Repo> = if (scope != null) emptyList() else repos()

/** The scope of a Run, as it started ([SCOPE]). */
@Component
class Scopes(private val history: HistoryService) {
    /** The repos [run] is scoped to, or null when it isn't. */
    fun of(run: String): List<String>? =
        (history.createHistoricVariableInstanceQuery().processInstanceId(run).variableName(SCOPE).singleResult()?.value as? List<*>)?.map(Any?::toString)
}
