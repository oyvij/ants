package ants

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The web UI as an installable app. Installing it and its badge are seen only in a real browser (BrowserTest). */
class PwaTest : AntsTest() {
    @Test
    fun `the manifest and its icons are served before login, and every page links them`() {
        val anonymous = browser()
        val manifest = anonymous.get("/manifest.webmanifest")
        assertEquals(200, manifest.status)
        assertContains(manifest.body, """"display": "standalone"""")
        Regex(""""src": "([^"]+)"""").findAll(manifest.body).map { it.groupValues[1] }.toList().ifEmpty { error("no icons") }.forEach {
            val icon = anonymous.get(it)
            assertEquals(200, icon.status, it)
            assertEquals("image/png", icon.headers?.firstValue("Content-Type")?.orElse(null), it)
        }

        val page = browser().also { it.login() }.get("/notifications").body
        assertContains(page, """<link rel="manifest" href="/manifest.webmanifest">""")
    }
}
