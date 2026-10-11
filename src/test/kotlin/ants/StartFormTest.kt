package ants

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartFormTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    /** The start form on the Process page of [key]. */
    private fun form(key: String) =
        browser.get("/processes/$key").body.substringAfter("""action="/processes/$key/runs"""").substringBefore("</form>")

    @Test
    fun `declared fields appear on the start form in order, with help text`() {
        browser.import("start-form.bpmn")
        val form = form("start-form")
        val order = listOf("Prompt", "What the Run should build", "Work branch", "Leave it empty for the scout to name one", "Repos")
            .map { form.indexOf(it) }
        assertTrue(order.all { it >= 0 } && order == order.sorted(), form)
        assertContains(form, """<textarea name="prompt" required>""")
        assertContains(form, """<input name="workBranch" value="">""")
        assertContains(form, """<select name="repos" multiple>""")
    }

    @Test
    fun `a required field left empty blocks Start`() {
        browser.import("start-form.bpmn")
        browser.get("/processes/start-form")
        val refused = browser.post("/processes/start-form/runs", mapOf("prompt" to " ", "workBranch" to "fix"))
        assertEquals(400, refused.status)
        assertContains(refused.body, "Prompt is required")
    }

    @Test
    fun `a Repos field offers the default Source map's names`() {
        val maps = Files.createTempDirectory("source-maps")
        maps.resolve("stacc.md").writeText("# Stacc\n- api-in: /code/api-in (git@example.com:stacc/api-in.git)\n- web: /code/web (/remotes/web.git)\n")
        maps.resolve("home.md").writeText("- dotfiles: /code/dotfiles (/remotes/dotfiles.git)\n")
        home.resolve("settings.yaml").writeText("source-maps:\n  home: $maps/home.md\n  stacc: $maps/stacc.md\ndefault-source-map: stacc\n")
        try {
            browser.import("start-form.bpmn")
            val repos = form("start-form").substringAfter("""<select name="repos" multiple>""").substringBefore("</select>")
            assertEquals(listOf("api-in", "web"), Regex("<option>([^<]+)</option>").findAll(repos).map { it.groupValues[1] }.toList())
        } finally {
            home.resolve("settings.yaml").writeText("")
        }
    }

    @Test
    fun `field values reach the Run as variables under their names`() {
        browser.import("start-form.bpmn")
        browser.get("/processes/start-form")
        val run = browser.post(
            "/processes/start-form/runs",
            listOf("prompt" to "Add *dark mode*", "workBranch" to "", "repos" to "api-in", "repos" to "web", "ignored" to "x"),
        )
        val variables = run.body.replace("&#34;", "\"").substringAfter("<h2>Variables</h2>")
        assertContains(variables, "<dt>prompt</dt><dd><code>Add *dark mode*</code></dd>")
        assertContains(variables, "<dt>workBranch</dt><dd><code></code></dd>")
        assertContains(variables, """<dt>repos</dt><dd><code>["api-in","web"]</code></dd>""")
        assertFalse("<dt>ignored</dt>" in variables)
    }
}
