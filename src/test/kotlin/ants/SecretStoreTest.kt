package ants

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Safe (ADR 0033): every Secret value Ants keeps, Settings Secrets by label and a Script's own by the Script's id,
 * encrypted in one file under a random Safe key that the login password wraps.
 */
class SecretStoreTest {
    private val home: Path = Files.createTempDirectory("ants-home")
    private val store = SecretStore(home, "pa55word")

    @Test
    fun `keeps Settings Secrets by label and each Script's own apart, and masks with the Settings values`() {
        assertEquals(emptyMap(), store.settingsValues())
        store.saveSettingsValues(mapOf("npm" to "s3cret-npm", "maven" to "s3cret-mvn"))
        store.saveScriptValues("s-post01", mapOf("SLACK_TOKEN" to "xoxb-s3cret"))

        assertEquals(mapOf("npm" to "s3cret-npm", "maven" to "s3cret-mvn"), store.settingsValues())
        assertEquals(mapOf("SLACK_TOKEN" to "xoxb-s3cret"), store.scriptValues("s-post01"))
        assertEquals(emptyMap(), store.scriptValues("s-other01"))
        assertEquals(setOf("s3cret-npm", "s3cret-mvn"), store.masks().toSet())

        store.saveSettingsValues(mapOf("npm" to "s3cret-npm"))
        assertEquals(mapOf("npm" to "s3cret-npm"), store.settingsValues())
        store.saveScriptValues("s-post01", emptyMap())
        assertEquals(emptyMap(), store.scriptValues("s-post01"))
        store.saveScriptValues("s-post01", mapOf("SLACK_TOKEN" to "xoxb-s3cret"))
        store.deleteScriptValues("s-post01")
        assertEquals(emptyMap(), store.scriptValues("s-post01"))
    }

    @Test
    fun `a new Safe shows its Safe key until the user confirms they saved it, then never again`() {
        val key = assertNotNull(store.newKey())
        assertEquals(key, SecretStore(home, "pa55word").newKey())
        store.keySaved()
        assertNull(store.newKey())
        assertNull(SecretStore(home, "pa55word").newKey())
    }

    @Test
    fun `the login password unlocks the Safe after a restart, and a wrong one leaves it locked`() {
        store.saveSettingsValues(mapOf("npm" to "s3cret-npm"))

        val restarted = SecretStore(home)
        assertTrue(restarted.locked)
        assertFailsWith<IllegalStateException> { restarted.settingsValues() }
        assertEquals(emptyList(), restarted.masks().toList())
        assertNull(restarted.newKey())

        restarted.unlock("wrong")
        assertTrue(restarted.locked)
        restarted.unlock("pa55word")
        assertFalse(restarted.locked)
        assertEquals(mapOf("npm" to "s3cret-npm"), restarted.settingsValues())

        assertEquals(mapOf("npm" to "s3cret-npm"), SecretStore(home, "pa55word").settingsValues())
        assertTrue(SecretStore(home, "wrong").locked)
    }

    @Test
    fun `a Safe made with no password yet is wrapped by the first login, and a changed password rewraps it`() {
        val fresh = Files.createTempDirectory("ants-home")
        val store = SecretStore(fresh)
        assertFalse(store.locked)
        store.saveSettingsValues(mapOf("npm" to "s3cret-npm"))
        store.unlock("first")
        assertEquals(mapOf("npm" to "s3cret-npm"), SecretStore(fresh, "first").settingsValues())

        store.rewrap("second")
        assertTrue(SecretStore(fresh, "first").locked)
        assertEquals(mapOf("npm" to "s3cret-npm"), SecretStore(fresh, "second").settingsValues())
    }

    @Test
    fun `the first start moves every plain secrets yaml into the Safe and deletes them`() {
        val old = Files.createTempDirectory("ants-home")
        old.resolve("secrets.yaml").writeText("npm: s3cret-npm\nmaven: s3cret-mvn\n")
        val script = old.resolve("scripts/s-post01").createDirectories()
        script.resolve("script.mjs").writeText("export default () => {}\n")
        script.resolve("secrets.yaml").writeText("SLACK_TOKEN: xoxb-s3cret\n")

        val store = SecretStore(old, "pa55word")

        assertEquals(mapOf("npm" to "s3cret-npm", "maven" to "s3cret-mvn"), store.settingsValues())
        assertEquals(mapOf("SLACK_TOKEN" to "xoxb-s3cret"), store.scriptValues("s-post01"))
        assertFalse(old.resolve("secrets.yaml").exists())
        assertFalse(script.resolve("secrets.yaml").exists())
        assertTrue(script.resolve("script.mjs").exists())
        val key = assertNotNull(store.newKey())
        for (file in old.walk()) for (plain in listOf("s3cret-npm", "s3cret-mvn", "xoxb-s3cret", key, "pa55word")) {
            assertFalse(plain in file.readText(), "$plain in $file")
        }
    }

    @Test
    fun `Reset Safe on a locked Safe empties every Kept value, shows a new Safe key once, and unlocks at login as before`() {
        store.saveSettingsValues(mapOf("npm" to "s3cret-npm"))
        store.saveScriptValues("s-post01", mapOf("SLACK_TOKEN" to "xoxb-s3cret"))
        val old = assertNotNull(store.newKey())
        store.keySaved()
        val lost = SecretStore(home)
        lost.unlock("reset-outside-ants")
        assertTrue(lost.locked)

        lost.reset()

        assertFalse(lost.locked)
        assertEquals(emptyMap(), lost.settingsValues())
        assertEquals(emptyMap(), lost.scriptValues("s-post01"))
        val key = assertNotNull(lost.newKey())
        assertTrue(key != old)
        lost.unlock("reset-outside-ants")
        lost.keySaved()
        assertNull(lost.newKey())
        val restarted = SecretStore(home)
        restarted.unlock("reset-outside-ants")
        assertFalse(restarted.locked)
        assertEquals(emptyMap(), restarted.settingsValues())
    }

    @Test
    fun `Reset Safe wraps the new Safe key with a password Ants knows in plain`() {
        store.saveSettingsValues(mapOf("npm" to "s3cret-npm"))
        store.reset("pa55word")
        assertEquals(emptyMap(), SecretStore(home, "pa55word").settingsValues())
    }
}
