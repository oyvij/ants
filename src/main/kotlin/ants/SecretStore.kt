package ants

import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * The Safe (ADR 0033): every Secret value Ants keeps, and the only code that reads or writes one, Settings Secrets by
 * label and each Script's own by the Script's fixed id. All of them sit in one file, `safe.yaml` in ANTS_HOME, mode
 * 0600, encrypted with AES-256-GCM under a random Safe key, which the file holds only wrapped by a key derived from the
 * login password. Ants holds the Safe key in memory while the Safe is unlocked: [password] (one Ants knows in plain, from
 * the environment) unlocks it here, and a login ([unlock]) after.
 *
 * The first start with no Safe creates one, moves every plain `secrets.yaml` (ADR 0015's) into it and deletes them, and
 * offers the Safe key ([newKey]) until the user says they saved it. With no [password] yet the first login wraps it.
 */
class SecretStore(home: Path, password: String? = null) {
    /** The Safe's file, which claude Agents may neither read nor edit (ADR 0015). */
    val file: Path = home.resolve("safe.yaml")

    private var key: ByteArray? = null

    /** Settings Secrets' values by label under `settings`, each Script's own by its id under `scripts`. */
    private var values = mutableMapOf<String, MutableMap<String, String>>()

    /** Since when a Task has waited on the locked Safe ([whenUnlocked]); null while none waits. */
    @Volatile
    var waitingSince: Instant? = null
        private set

    /** What each waiting Task does once the Safe is unlocked. */
    private val held = mutableListOf<() -> Unit>()

    init {
        if (file.exists()) password?.let(::unlock)
        else migrate(home, password)
    }

    /** No Safe key in memory: no value can be read or saved until a login or the Safe key unlocks it. */
    val locked get() = key == null

    /** The Safe key to show once, encoded for copy and paste, while the user hasn't said they saved it. */
    @Synchronized
    fun newKey(): String? = key?.takeIf { stored()["key-saved"] != true }?.let(Base64.getEncoder()::encodeToString)

    /** The user saved the Safe key: it is never shown again. */
    @Synchronized
    fun keySaved() {
        if (!locked) write { it["key-saved"] = true }
    }

    /**
     * A login with [password] unlocks a locked Safe, unless the password doesn't open its wrapping (a wrong one, or one
     * reset outside Ants), and wraps an unlocked Safe that no password wraps yet.
     */
    @Synchronized
    fun unlock(password: String) {
        val stored = stored()
        val wrapped = stored["wrapped"] as? String
        if (!locked) {
            if (wrapped == null) rewrap(password)
            return
        }
        if (wrapped == null) return
        val opened = try {
            decrypt(derive(password, decode(stored["salt"])), decode(wrapped))
        } catch (_: AEADBadTagException) {
            return
        }
        open(opened)
    }

    /** The Safe key, as [newKey] showed it, unlocks a locked Safe; false, and the Safe stays locked, if it doesn't open it. */
    @Synchronized
    fun unlockWithKey(key: String): Boolean {
        if (!locked) return true
        val opened = runCatching { Base64.getDecoder().decode(key.trim()) }.getOrNull()?.takeIf { it.size == 32 } ?: return false
        return runCatching { open(opened) }.isSuccess
    }

    private fun open(opened: ByteArray) {
        values = parse(decrypt(opened, decode(stored()["values"])).decodeToString())
        key = opened
        release()
    }

    /** Lets every Task waiting on the Safe go on, now it is unlocked. */
    private fun release() {
        waitingSince = null
        held.toList().also { held.clear() }.forEach { runCatching(it) }
    }

    /** Forgets the Safe key, every value and every Lookup value resolved since the unlock, until a login or the Safe key unlocks the Safe again. */
    @Synchronized
    fun lock() {
        key = null
        values = mutableMapOf()
        looked.clear()
    }

    /** Runs [go] once the Safe is unlocked, now if it is: a Task that needs a Secret waits so, as on a Permission request. */
    @Synchronized
    fun whenUnlocked(go: () -> Unit) {
        if (!locked) return go()
        if (held.isEmpty()) waitingSince = Instant.now()
        held += go
    }

    /** Waits on this thread until the Safe is unlocked. */
    fun awaitUnlocked() = CountDownLatch(1).also { whenUnlocked(it::countDown) }.await()

    /** Wraps the Safe key with [password], the new login password. */
    @Synchronized
    fun rewrap(password: String) {
        val key = key ?: return
        val salt = random(16)
        write { it["salt"] = encode(salt); it["wrapped"] = encode(encrypt(derive(password, salt), key)) }
    }

    /**
     * Reset Safe, for a lost Safe key and password: a new Safe key with no Kept value, shown once as at creation. Ants
     * wraps it with [password] when it knows one in plain, else the next login does.
     */
    @Synchronized
    fun reset(password: String? = null) {
        val fresh = random(32)
        key = fresh
        values = mutableMapOf()
        file.writePrivately(yaml.dump(mapOf("values" to encode(encrypt(fresh, yaml.dump(values).encodeToByteArray())))))
        password?.let(::rewrap)
        release() // the Tasks waiting go on, and fail on a Secret with no value until it is retyped
    }

    /** Each Settings Secret's value, by label. */
    fun settingsValues(): Map<String, String> = section("settings")

    /** Rewrites the Settings Secrets' values with [values]. */
    fun saveSettingsValues(values: Map<String, String>) = save("settings", values)

    /** The Script with [id]'s own Secrets' values, by name, kept apart from its Draft and its published version. */
    fun scriptValues(id: String): Map<String, String> = section(id)

    /** Keeps [values] as the Script with [id]'s own Secrets; none deletes them. */
    fun saveScriptValues(id: String, values: Map<String, String>) = save(id, values)

    fun deleteScriptValues(id: String) = save(id, emptyMap())

    /** Every Lookup value resolved since the unlock, in memory only, masked with the Kept ones; locking clears them. */
    private val looked = mutableSetOf<String>()

    @Synchronized
    fun lookedUp(value: String) {
        looked += value
    }

    /** The values to mask in any output an Agent or the user reads: every Settings Secret's and every Lookup's resolved; none while locked. */
    @Synchronized
    fun masks(): Collection<String> = values["settings"].orEmpty().values.toList() + looked

    @Synchronized
    private fun section(name: String): Map<String, String> {
        check(!locked) { "The Safe is locked: log in to unlock it" }
        return values[name].orEmpty().toMap()
    }

    @Synchronized
    private fun save(name: String, section: Map<String, String>) {
        val key = checkNotNull(key) { "The Safe is locked: log in to unlock it" }
        if (section.isEmpty()) values.remove(name) else values[name] = section.toMutableMap()
        write { it["values"] = encode(encrypt(key, yaml.dump(values).encodeToByteArray())) }
    }

    /** Creates the Safe, holding the values of every plain `secrets.yaml`, which it then deletes. */
    private fun migrate(home: Path, password: String?) {
        key = random(32)
        val scripts = home.resolve("scripts").takeIf { it.isDirectory() }?.listDirectoryEntries().orEmpty()
        val plain = listOf("settings" to home) + scripts.map { it.fileName.toString() to it }
        for ((name, folder) in plain) read(folder.resolve("secrets.yaml")).takeIf { it.isNotEmpty() }?.let { values[name] = it.toMutableMap() }
        save("settings", values["settings"].orEmpty())
        password?.let(::rewrap)
        plain.forEach { (_, folder) -> folder.resolve("secrets.yaml").deleteIfExists() }
    }

    private fun stored(): Map<String, Any?> = file.takeIf { it.exists() }?.let { Yaml().load<Map<String, Any?>>(it.readText()) }.orEmpty()

    private fun write(change: (MutableMap<String, Any?>) -> Unit) = file.writePrivately(yaml.dump(LinkedHashMap(stored()).also(change)))

    private fun read(file: Path): MutableMap<String, String> = file.takeIf { it.exists() }?.let { Yaml().load<Map<*, *>>(it.readText()) }
        .orEmpty().entries.associateTo(mutableMapOf()) { (key, value) -> "$key" to "$value" }

    private fun parse(text: String) = (Yaml().load<Map<String, Map<String, String>>>(text) ?: emptyMap())
        .mapValuesTo(mutableMapOf()) { (_, section) -> section.toMutableMap() }

    private companion object {
        const val ITERATIONS = 600_000
        val random = SecureRandom()

        fun random(size: Int) = ByteArray(size).also(random::nextBytes)
        fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun decode(text: Any?): ByteArray = Base64.getDecoder().decode(text as String)

        fun derive(password: String, salt: ByteArray): ByteArray = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)).encoded

        /** A random 12-byte nonce, then the ciphertext and its tag. */
        fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
            val nonce = random(12)
            return nonce + cipher(Cipher.ENCRYPT_MODE, key, nonce).doFinal(plain)
        }

        fun decrypt(key: ByteArray, sealed: ByteArray): ByteArray = cipher(Cipher.DECRYPT_MODE, key, sealed.copyOf(12)).doFinal(sealed, 12, sealed.size - 12)

        fun cipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
    }
}
