package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val file = home.resolve("settings.yaml")
    private lateinit var before: String

    @BeforeEach
    fun keep() {
        before = file.readText()
    }

    @AfterEach
    fun restore() = file.writeText(before)

    private fun yaml(): Map<*, *> = Yaml().load(file.readText())

    /** Presses [action] on the settings page with [form] as every field's value; a field left out is empty. */
    private fun press(action: String, form: Map<String, String>): String {
        browser.get("/settings")
        return browser.post("/settings", form + ("action" to action)).body.replace("&#34;", "\"")
    }

    private fun executable(name: String) = loginBin.resolve(name).apply { writeText("#!/bin/sh\n"); toFile().setExecutable(true) }

    @Test
    fun `saving the page writes settings yaml, and a hand edit shows on the page`() {
        press("save", mapOf("agent-tasks" to "5", "claude.command" to "nono run -- claude", "claude.env" to "PATH HOME", "agent-folders" to "/tmp/a\n/tmp/b"))
        assertEquals(5, yaml()["agent-tasks"])
        assertEquals(mapOf("command" to listOf("nono", "run", "--", "claude"), "env" to listOf("PATH", "HOME")), yaml()["claude"])
        assertEquals(listOf("/tmp/a", "/tmp/b"), yaml()["agent-folders"])

        file.writeText("agent-tasks: 7\nclaude:\n  command: [env, claude]\nskill-folders: [/tmp/skills]\n")
        val page = browser.get("/settings").body
        assertContains(page, """name="agent-tasks" value="7"""")
        assertContains(page, """name="claude.command" value="env claude"""")
        assertContains(page, "/tmp/skills</textarea>")
    }

    @Test
    fun `Detect finds the CLIs through the login shell`() {
        val claude = executable("claude")
        val gh = executable("gh")
        val page = press("detect", emptyMap())
        assertContains(page, """name="claude.path" value="$claude"""")
        assertContains(page, """name="gh.path" value="$gh"""")
        press("save", mapOf("claude.path" to "$claude"))
        assertEquals(mapOf("path" to "$claude"), yaml()["claude"])
    }

    @Test
    fun `a first start detects the CLIs into the new settings yaml`() {
        val codex = executable("codex")
        val other = tempHome()
        bootAnother(other)
        assertEquals(mapOf("path" to "$codex"), Yaml().load<Map<*, *>>(other.resolve("settings.yaml").readText())["codex"])
    }

    @Test
    fun `the command prefix and env allow-list saved on the page reach the launched CLI`() {
        val claude = FakeClaude()
        val extra = Files.createTempDirectory("extra")
        // The Agent and Skill come from extra folders; drop any same-named ones another test left in Ants' own.
        home.resolve("agents/writer.md").toFile().delete()
        home.resolve("skills/draft").toFile().deleteRecursively()
        extra.resolve("agents").createDirectories().resolve("writer.md").writeText("Write plainly.\n")
        extra.resolve("skills/draft").createDirectories().resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts.\n---\nDraft.\n")
        claude.session("draft", """{"type":"result","subtype":"success","structured_output":{"outcome":"done","summary":"Drafted.","output":{}}}""")
        press("save", mapOf(
            "claude.command" to "/usr/bin/env ANTS_PREFIX=on ${claude.command}",
            "claude.env" to "PATH",
            "agent-folders" to "$extra/agents",
            "skill-folders" to "$extra/skills",
        ))
        browser.import("agent-task.bpmn")
        val run = browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path
        eventually { assertContains(browser.get(run).body, "State: Completed") }
        val launch = claude.launches("draft").single()
        assertEquals("on", launch.env["ANTS_PREFIX"])
        assertEquals(null, launch.env["HOME"]) // outside the allow-list
    }

    @Test
    fun `pointing at a Varde config toml fills the command and env from it`() {
        val config = Files.createTempFile("config", ".toml")
        config.writeText("[editor]\ntab_width = 2\n\n[ai]\ncommand = \"nono run --profile claude -- claude\"\nenv = [\n  \"HOME\", # mine\n  \"PATH\",\n]\n")
        val page = press("varde", mapOf("varde-config" to "$config"))
        assertContains(page, """name="claude.command" value="nono run --profile claude -- claude"""")
        assertContains(page, """name="claude.env" value="HOME PATH"""")
    }

    @Test
    fun `named Vaults and Source maps can be added, and one of each made the default`() {
        press("save", mapOf(
            "vaults[0].name" to "Work", "vaults[0].path" to "~/vault",
            "vaults[1].name" to "Side", "vaults[1].path" to "/tmp/side",
            "default-vault" to "1",
            "source-maps[0].name" to "Stacc", "source-maps[0].path" to "/tmp/source-map.md",
            "default-source-map" to "0",
        ))
        assertEquals(mapOf("Work" to "~/vault", "Side" to "/tmp/side"), yaml()["vaults"])
        assertEquals("Side", yaml()["default-vault"])
        assertEquals(mapOf("Stacc" to "/tmp/source-map.md"), yaml()["source-maps"])
        assertEquals("Stacc", yaml()["default-source-map"])
        val page = browser.get("/settings").body
        assertContains(page, """name="default-vault" value="1" checked""")
        assertContains(page, """name="vaults[2].name" value=""""") // an empty row adds the next one
    }

    @Test
    fun `changing the password takes effect at the next login`() {
        press("save", mapOf("password" to "s3cret"))
        assertTrue(yaml()["password"].toString().startsWith("{bcrypt}"))
        assertEquals("/login", browser().login("admin").path)
        assertEquals("/", browser().login("s3cret").path)
    }

    @Test
    fun `a plain password written by hand logs in`() {
        file.writeText("password: by-hand\n")
        assertEquals("/", browser().login("by-hand").path)
    }

    @Test
    fun `dark mode follows the system until a choice is saved`() {
        assertContains(browser.get("/").body, """<meta name="color-scheme" content="light dark">""")
        press("save", mapOf("theme" to "dark"))
        assertContains(browser.get("/").body, """<meta name="color-scheme" content="dark">""")
        assertContains(browser.get("/settings").body, """name="theme" value="dark" checked""")
    }
}
