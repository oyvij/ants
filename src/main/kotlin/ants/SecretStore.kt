package ants

import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
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

    /**
     * Keep open's file, beside ANTS_HOME, not in it, so a copy of ANTS_HOME carries no key: the Safe key in plain, mode
     * 0600, which unlocks the Safe at boot. claude Agents may neither read nor edit it.
     */
    val keyFile: Path = home.resolveSibling("${home.fileName}-safe.key")

    private var key: ByteArray? = null

    /** Settings Secrets' values by label under `settings`, each Script's own by its id under `scripts`. */
    private var values = mutableMapOf<String, MutableMap<String, String>>()

    init {
        if (!file.exists()) migrate(home, password)
        else if (keyFile.exists()) open(decode(keyFile.readText().trim()))
        else password?.let(::unlock)
    }

    /** Keep open is on: the Safe unlocks at boot from [keyFile]. */
    val keepOpen get() = keyFile.exists()

    /** On writes the Safe key to [keyFile] (the Safe must be unlocked); off deletes it. */
    @Synchronized
    fun keepOpen(on: Boolean) {
        if (on) keyFile.writePrivately(encode(checkNotNull(key) { "The Safe is locked: log in to unlock it" }))
        else keyFile.deleteIfExists()
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
        open(try {
            decrypt(derive(password, decode(stored["salt"])), decode(wrapped))
        } catch (_: AEADBadTagException) {
            return
        })
    }

    /** Unlocks with the Safe key [opened]; one that doesn't open the Safe leaves it locked. */
    private fun open(opened: ByteArray) {
        values = try {
            parse(decrypt(opened, decode(stored()["values"])).decodeToString())
        } catch (_: AEADBadTagException) {
            return
        }
        key = opened
    }

    /** Wraps the Safe key with [password], the new login password. */
    @Synchronized
    fun rewrap(password: String) {
        val key = key ?: return
        val salt = random(16)
        write { it["salt"] = encode(salt); it["wrapped"] = encode(encrypt(derive(password, salt), key)) }
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

    /** The values to mask in any output an Agent or the user reads: every Settings Secret's; none while locked. */
    @Synchronized
    fun masks(): Collection<String> = values["settings"].orEmpty().values.toList()

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
