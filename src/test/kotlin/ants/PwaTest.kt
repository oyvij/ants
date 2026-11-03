package ants

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `the app's status bar and splash take the phone top bar's colour`() {
        val manifest = manifest()
        assertContains(manifest, """"theme_color": "#0e1016"""")
        assertContains(manifest, """"background_color": "#0e1016"""")
    }

    @Test
    fun `each icon the manifest lists is as large as its sizes say`() {
        icons().forEach { (src, size) -> assertEquals(size, icon(src).let { "${it.width}x${it.height}" }, src) }
    }

    @Test
    fun `each icon is the purple ant tile, not the green one`() {
        icons().forEach { (src) ->
            val icon = icon(src)
            val pixels = (0 until icon.height).flatMap { y -> (0 until icon.width).map { x -> Color(icon.getRGB(x, y)) } }
            assertTrue(Color(icon.getRGB(0, 0)).near("#f7f1e8"), "$src top left")
            assertTrue(Color(icon.getRGB(icon.width - 1, icon.height - 1)).near("#e9dcc8"), "$src bottom right")
            assertTrue(pixels.any { it.red in 40..140 && it.red > it.blue + 30 }, "$src has the brown ant")
            assertFalse(pixels.any { it.near("#2b6f4f") }, "$src has the old green")
        }
    }

    private fun icons() = Regex(""""src": "([^"]+)", "sizes": "([^"]+)"""")
        .findAll(manifest()).map { it.groupValues[1] to it.groupValues[2] }.toList()
        .ifEmpty { error("no icons") }

    private fun manifest() = browser().get("/manifest.webmanifest").body

    private fun icon(src: String): BufferedImage = ImageIO.read(javaClass.getResource("/static$src") ?: error("no $src"))

    private fun Color.near(hex: String) = Color.decode(hex).let {
        abs(red - it.red) <= 12 && abs(green - it.green) <= 12 && abs(blue - it.blue) <= 12
    }
}
