package ants

import org.springframework.stereotype.Component
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Resolves Settings Secrets (ADR 0033): a Kept one's value is in the Safe; a Lookup's, such as `op://Dev/npm/token`,
 * comes from the Secret Provider of its scheme at each use, its command run with no shell, the pointer as one argument
 * in place of `{ref}`, on the allow-list environment plus the Provider's linked Secrets, which are Kept.
 */
@Component
class SecretProviders(private val settings: Settings, private val store: SecretStore) {
    /** How long a Secret Provider may run before Ants kills it. */
    internal var timeLimit: Duration = Duration.ofSeconds(30)

    /** The value of the Secret [label], null for a Kept one with none; throws [Failed] when its Lookup can't be resolved. */
    fun value(label: String): String? {
        val pointer = settings.lookups()[label] ?: return store.settingsValues()[label]
        val scheme = pointer.substringBefore("://", "")
        val provider = settings.secretProviders()[scheme] ?: throw Failed("Ants couldn't look up the Secret $label: no Secret Provider resolves $scheme://.")
        val kept = store.settingsValues()
        val names = settings.secrets()
        val linked = provider.secrets.flatMap { linked ->
            val value = kept[linked] ?: throw Failed("Ants couldn't look up the Secret $label: its Secret Provider's Secret $linked has no Kept value.")
            names[linked].orEmpty().map { it to value }
        }.toMap()
        val process = try {
            ProcessBuilder(provider.command.map { if (it == "{ref}") pointer else it }).redirectInput(File("/dev/null")).onAllowList(settings, linked).start()
        } catch (e: IOException) {
            throw Failed("Ants couldn't look up the Secret $label: ${e.message}")
        }
        val errors = CompletableFuture.supplyAsync({ process.errorStream.readAllBytes().toString(Charsets.UTF_8) }) { Thread.ofVirtual().start(it) }
        val (finished, out) = process.within(timeLimit, "")
        if (!finished) throw Failed("Ants couldn't look up the Secret $label: its Secret Provider ran past its time limit of ${timeLimit.short()}.")
        if (process.exitValue() != 0) {
            val output = ProtectedCommands.mask((out + errors.get()).trimEnd(), store.masks())
            throw Failed("Ants couldn't look up the Secret $label: its Secret Provider exited ${process.exitValue()}:\n$output")
        }
        return out.removeSuffix("\n").also(store::lookedUp)
    }

    class Failed(override val message: String) : Exception(message)
}

/** A Secret Provider: the command resolving its scheme's Lookups, `{ref}` standing for the pointer, and the labels of the Kept Secrets it runs with. */
data class SecretProvider(val command: List<String>, val secrets: List<String>)
