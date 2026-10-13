package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        press("save", mapOf("agent-tasks" to "5", "subagent-depth" to "2", "claude.command" to "nono run -- claude", "claude.env" to "PATH HOME", "agent-folders" to "/tmp/a\n/tmp/b"))
        assertEquals(5, yaml()["agent-tasks"])
        assertEquals(2, yaml()["subagent-depth"])
        assertEquals(mapOf("command" to listOf("nono", "run", "--", "claude"), "env" to listOf("PATH", "HOME")), yaml()["claude"])
        assertEquals(listOf("/tmp/a", "/tmp/b"), yaml()["agent-folders"])

        file.writeText("agent-tasks: 7\nsubagent-depth: 3\nclaude:\n  command: [env, claude]\nskill-folders: [/tmp/skills]\n")
        val page = browser.get("/settings").body
        assertContains(page, """name="agent-tasks" value="7"""")
        assertContains(page, """name="subagent-depth" value="3"""")
        assertContains(page, """name="claude.command" value="env claude"""")
        assertContains(page, """name="skill-folders" value="/tmp/skills"""")
    }

    @Test
    fun `Agent and Skill folders are rows after Ants' own, each row one folder`() {
        browser.get("/settings")
        browser.post("/settings", listOf("action" to "save", "agent-folders" to "/tmp/a", "agent-folders" to "", "agent-folders" to "/tmp/b"))
        assertEquals(listOf("/tmp/a", "/tmp/b"), yaml()["agent-folders"])
        val page = browser.get("/settings").body
        assertContains(page, """value="${home.resolve("agents")}" disabled""")
        assertContains(page, """value="${home.resolve("skills")}" disabled""")
        assertContains(page, """name="agent-folders" value="/tmp/b"""")
    }

    @Test
    fun `the Server card shows the address Ants listens on, beside the password`() {
        val page = browser.get("/settings").body
        assertContains(page, """value="127.0.0.1:$port" readonly""")
        assertContains(page, """name="password"""")
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
        extra.resolve("agents").createDirectories().resolve("writer.md").writeText("---\nskills: draft\n---\nWrite plainly.\n")
        extra.resolve("skills/draft").createDirectories().resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts.\n---\nDraft.\n")
        claude.session("draft", """{"type":"result","subtype":"success","structured_output":{"outcome":"done","summary":"Drafted.","output":{}}}""")
        press("save", mapOf(
            "claude.command" to "/usr/bin/env ANTS_PREFIX=on ${claude.command}",
            "claude.env" to "PATH",
            "agent-folders" to "$extra/agents",
            "skill-folders" to "$extra/skills",
            "vaults[0].name" to "notes", "vaults[0].path" to "$extra",
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
    fun `Profiles pair a Vault with a Source map by name, one the default, and are renamed and removed on the page`() {
        val lists = mapOf(
            "vaults[0].name" to "work", "vaults[0].path" to "/tmp/work", "vaults[1].name" to "private", "vaults[1].path" to "/tmp/private",
            "source-maps[0].name" to "work", "source-maps[0].path" to "/tmp/work.md", "source-maps[1].name" to "private", "source-maps[1].path" to "/tmp/private.md",
        )
        press("save", lists + mapOf(
            "profiles[0].name" to "work", "profiles[0].vault" to "work", "profiles[0].source-map" to "work",
            "profiles[1].name" to "private", "profiles[1].vault" to "private", "profiles[1].source-map" to "private",
            "default-profile" to "1",
        ))
        assertEquals(mapOf("work" to mapOf("vault" to "work", "source-map" to "work"), "private" to mapOf("vault" to "private", "source-map" to "private")), yaml()["profiles"])
        assertEquals("private", yaml()["default-profile"])
        val page = browser.get("/settings").body
        assertContains(page, """name="profiles[1].name" value="private"""")
        assertContains(page, """name="default-profile" value="1" checked""")
        assertContains(page, """name="profiles[2].name" value=""""") // an empty row adds the next one

        // Renamed, and the other removed by emptying its row.
        press("save", lists + mapOf("profiles[0].name" to "", "profiles[1].name" to "home", "profiles[1].vault" to "private", "profiles[1].source-map" to "private", "default-profile" to "1"))
        assertEquals(mapOf("home" to mapOf("vault" to "private", "source-map" to "private")), yaml()["profiles"])
        assertEquals("home", yaml()["default-profile"])

        val refused = press("save", lists + mapOf("profiles[0].name" to "odd", "profiles[0].vault" to "nowhere", "profiles[0].source-map" to "work"))
        assertContains(refused, "The Profile odd needs a Vault and a Source map from the lists above.")
        assertEquals(mapOf("home" to mapOf("vault" to "private", "source-map" to "private")), yaml()["profiles"])
    }

    @Test
    fun `changing the password takes effect at the next login`() {
        press("save", mapOf("password" to "s3cret"))
        assertTrue(yaml()["password"].toString().startsWith("{bcrypt}"))
        assertEquals("/login", browser().login("admin").path)
        assertEquals("/", browser().login("s3cret").path)
    }

    @Autowired
    lateinit var settings: Settings

    @Test
    fun `so-far is off, a time, or the default 2 minutes, also for a value that isn't a time`() {
        for ((value, every) in listOf("false" to null, "90s" to Duration.ofSeconds(90), "5m" to Duration.ofMinutes(5), "often" to Duration.ofMinutes(2))) {
            file.writeText("so-far: $value\n")
            assertEquals(every, settings.soFar(), value)
        }
    }

    @Test
    fun `a plain password written by hand logs in`() {
        file.writeText("password: by-hand\n")
        assertEquals("/", browser().login("by-hand").path)
    }

    /** A fake claude as the command; its `auth status` answers [status] when it's in Ants' Claude config folder. */
    private fun fakeClaude(status: String? = null) = FakeClaude().also { claude ->
        file.writeText("claude:\n  command: [${claude.command}]\n$vault")
        home.resolve("claude").createDirectories().resolve("auth-status.json").run { if (status != null) writeText(status) else deleteIfExists() }
    }

    @Test
    fun `settings shows logged in or not, and the exact login command`() {
        fakeClaude()
        val command = "<code>CLAUDE_CONFIG_DIR=${home.resolve("claude")} claude auth login</code>"
        assertContains(browser.get("/settings").body, """<span class="pill p-failed">Not logged in</span>""")
        assertContains(browser.get("/settings").body, command)

        fakeClaude("""{"loggedIn":true,"authMethod":"claude.ai","subscriptionType":"max"}""")
        val page = browser.get("/settings").body
        assertContains(page, """<span class="pill p-ready">Logged in</span> claude.ai, max""")
        assertContains(page, command)
        assertFalse("API key" in page)
    }

    @Test
    fun `Check login asks claude again and says what it found, keeping what was typed`() {
        fakeClaude()
        val out = press("login", mapOf("agent-tasks" to "9"))
        assertContains(out, """<div class="toast" role="status"><b>Not logged in</b>""")
        assertContains(out, """name="agent-tasks" value="9"""")
        fakeClaude("""{"loggedIn":true,"authMethod":"claude.ai","subscriptionType":"max"}""")
        assertContains(press("login", emptyMap()), """<div class="toast" role="status"><b>Logged in</b> claude.ai, max""")
    }

    @Test
    fun `an apiKeySource other than none is flagged`() {
        fakeClaude("""{"loggedIn":true,"authMethod":"api_key","apiKeySource":"apiKeyHelper"}""")
        assertContains(browser.get("/settings").body, "An API key is in use (apiKeySource: apiKeyHelper)")
        fakeClaude("""{"loggedIn":true,"authMethod":"claude.ai","apiKeySource":"none"}""")
        assertFalse("API key" in browser.get("/settings").body)
    }

    @Test
    fun `the latest 5-hour and 7-day usage shows next to the login`() {
        val claude = fakeClaude("""{"loggedIn":true,"authMethod":"claude.ai"}""")
        home.resolve("agents").createDirectories().resolve("writer.md").writeText("---\nskills: draft\n---\nWrite plainly.\n")
        home.resolve("skills/draft").createDirectories().resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts.\n---\nDraft.\n")
        home.resolve("skills/draft/result.schema.json").deleteIfExists()
        fun limit(fiveHour: Double) = """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","rateLimitType":"five_hour",""" +
            """"unifiedWindows":{"five_hour":{"utilization":$fiveHour,"resetsAt":1791732000},"seven_day":{"utilization":0.07,"resetsAt":1792162800}}}}"""
        claude.session("draft", limit(0.01), limit(0.04),
            """{"type":"result","subtype":"success","structured_output":{"outcome":"done","summary":"Drafted.","output":{}}}""")
        browser.import("agent-task.bpmn")
        val run = browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path
        eventually { assertContains(browser.get(run).body, "State: Completed") }
        val page = browser.get("/settings").body
        assertContains(page, """<span>5-hour</span><div class="track"><div class="fill" style="width:4%"></div></div><b>4%</b>""")
        assertContains(page, """<span>7-day</span><div class="track"><div class="fill" style="width:7%"></div></div><b>7%</b>""")
        assertContains(page, "5-hour resets ${local(1791732000)}, 7-day resets ${local(1792162800)}.")
    }

    private fun local(epochSecond: Long) =
        DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH).format(Instant.ofEpochSecond(epochSecond).atZone(ZoneId.systemDefault()))

    @Test
    fun `dark mode follows the system until a choice is saved, through the color-scheme ants css reads`() {
        assertContains(browser.get("/").body, """<meta name="color-scheme" content="light dark">""")
        assertContains(browser.get("/").body, """<link rel="stylesheet" href="/ants.css">""")
        val css = browser.get("/ants.css")
        assertEquals(200, css.status)
        assertContains(css.body, "light-dark(")
        press("save", mapOf("theme" to "dark"))
        assertContains(browser.get("/").body, """<meta name="color-scheme" content="dark">""")
        assertContains(browser.get("/settings").body, """name="theme" value="dark" checked""")
    }
}
