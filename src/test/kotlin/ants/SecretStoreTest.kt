package ants

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

/** The Secret store: every Secret value Ants keeps, Settings Secrets by label and a Script's own by the Script's id. */
class SecretStoreTest {
    private val store = SecretStore(Files.createTempDirectory("ants-home"))

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
}
