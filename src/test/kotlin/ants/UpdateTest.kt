package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.web.server.context.WebServerApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The update check against a release folder on disk ([AntsTest.releases]), never GitHub, and Update in the nav. The
 * test Ants checks every 200 ms, so a test changes the folder and waits for the nav to follow, up to a minute: under
 * CI load the check and install.sh have taken longer than [eventually]'s default ten seconds.
 */
class UpdateTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    @Autowired
    lateinit var build: BuildProperties

    /** The version this Ants runs, which Update hands install.sh as the one to roll back to. */
    private val running get() = build.version

    /** A release of [version] in the release folder, as the check reads it: its SHA256SUMS names the tarball. */
    private fun release(version: String) = releases.resolve("SHA256SUMS").writeText(sums(version))

    /**
     * A release of [version] as CI publishes one: a tarball (here holding only `bin/ants`), its SHA256SUMS and the
     * install.sh Update runs.
     */
    private fun published(version: String) {
        val tarball = releases.resolve(tarball(version))
        val image = Files.createTempDirectory("image")
        image.resolve("bin").createDirectories().resolve("ants").writeText("#!/bin/bash\n")
        assertEquals(0, ProcessBuilder("tar", "-czf", "$tarball", "-C", "$image", "bin").start().waitFor())
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tarball.readBytes()))
        Path.of("install.sh").copyTo(releases.resolve("install.sh"), overwrite = true)
        releases.resolve("SHA256SUMS").writeText("$sha  ${tarball.fileName}\n")
    }

    /** Puts the release folder back as other tests expect it: no release newer than this Ants, and the bundled models. */
    @AfterEach
    fun noNewerRelease() {
        release("0.0.1")
        models.writeText(javaClass.getResource("/models.md")!!.readText())
        eventually(60) {
            assertEquals("", notice())
            assertFalse("claude-opus-9" in browser.get("/agents/new").body)
        }
        models.writeText("")
        for (name in listOf("current", "previous")) home.resolve(name).deleteIfExists()
        home.resolve("versions").toFile().deleteRecursively()
    }

    /** The nav's update notice as [page] shows it: the nav fills its slot from the page's stream. */
    private fun notice(page: String = "/", browser: Browser = this.browser): String {
        assertContains(browser.get(page).body, """<span id="update"></span>""", message = "$page has no update notice in its nav")
        return browser.events("", "update").poll(10, SECONDS).orEmpty()
    }

    /**
     * Presses the nav's **Update** on Home, as [browser] sees it, posting its form's fields.
     */
    private fun update(browser: Browser = this.browser) {
        val form = Regex("""<form method="post" action="([^"]+)".*name="_csrf" value="([^"]+)"""").find(notice(browser = browser))
        val (action, csrf) = assertNotNull(form, "no Update in the nav").destructured
        browser.post(action, mapOf("_csrf" to csrf))
    }

    @Test
    fun `a newer release shows in the nav with Update`() {
        release("99.0.0")

        eventually(60) {
            val notice = notice("/runs")
            assertContains(notice, "Ants 99.0.0 is available")
            assertContains(notice, """<form method="post" action="/updates/99.0.0"""")
            assertContains(notice, "<button>Update</button>")
        }
    }

    @Test
    fun `Settings' Updates card shows the running version, and Check now checks at once`() {
        assertContains(browser.get("/settings").body, "Running v$running. Up to date.")
        release("99.0.0")
        val page = browser.post("/settings", mapOf("action" to "updates")).body
        assertContains(page, """<div class="toast" role="status"><b>Checked</b> Ants 99.0.0 is available.""")
        assertContains(page, """Running v$running. Ants 99.0.0 is available. <button formaction="/updates/99.0.0">Update</button>""")
    }

    @Test
    fun `a scheduled check that can't reach the release list says so on the Updates card`() {
        releases.resolve("SHA256SUMS").deleteIfExists()
        eventually(60) { assertContains(browser.get("/settings").body, "Running v$running. Couldn't check for a newer Ants: the release list didn't answer.") }
    }

    @Test
    fun `an older release shows nothing`() {
        release("0.0.1")

        assertEquals("", notice())
    }

    @Test
    fun `in Docker the update notice shows without an Update button, and Update is refused`() {
        release("99.0.0")
        startAnother(tempHome(), "--ants.docker=true", "--ants.password=s3cret").use { ants ->
            val docker = Browser("http://127.0.0.1:${(ants as WebServerApplicationContext).webServer!!.port}").also { it.login("s3cret") }

            val events = docker.events("", "update")
            val notice = generateSequence { events.poll(10, SECONDS) }.first { it.isNotEmpty() } // once its first check is done

            assertContains(notice, "Ants 99.0.0 is available")
            assertFalse("<button>" in notice, notice)
            assertEquals(403, docker.post("/updates/99.0.0", emptyMap()).status)
        }
    }

    @Test
    fun `Update installs the newer release beside this one without stopping a running Run, and it runs from the next restart`() {
        val claude = FakeClaude()
        agentTask(claude)
        claude.session("draft", "wait release", DONE)
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        val run = browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
        eventually(60) { assertEquals(1, claude.launches("draft").size) }
        home.resolve("versions/$running").createDirectories()
        home.resolve("current").createSymbolicLinkPointingTo(Path.of("versions/$running"))
        published("96.0.0")
        eventually(60) { assertContains(notice(), "Ants 96.0.0 is available") }

        update()

        eventually(60) { assertContains(notice(), "Ants 96.0.0 runs from the next ants restart") }
        assertTrue(home.resolve("versions/96.0.0/bin/ants").exists())
        assertEquals(Path.of("versions/96.0.0"), home.resolve("current").readSymbolicLink())
        assertEquals(Path.of("versions/$running"), home.resolve("previous").readSymbolicLink())
        assertContains(browser.get("/runs/$run").body, """id="state">Running<""")
        claude.release("release")
        eventually(60) { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
    }

    @Test
    fun `a second Update before a restart keeps the running version as the one to roll back to`() {
        home.resolve("versions/$running").createDirectories()
        home.resolve("current").createSymbolicLinkPointingTo(Path.of("versions/$running"))
        published("97.0.0")
        eventually(60) { assertContains(notice(), "Ants 97.0.0 is available") }
        update()
        eventually(60) { assertContains(notice(), "Ants 97.0.0 runs from the next ants restart") }
        published("98.0.0")
        eventually(60) { assertContains(notice(), "Ants 98.0.0 is available") }

        update()

        eventually(60) { assertContains(notice(), "Ants 98.0.0 runs from the next ants restart") }
        assertEquals(Path.of("versions/98.0.0"), home.resolve("current").readSymbolicLink())
        assertEquals(Path.of("versions/$running"), home.resolve("previous").readSymbolicLink())
    }

    @Test
    fun `Update says which release it installed when a newer one came out after the check`() {
        published("94.0.0")
        startAnother(tempHome()).use { ants -> // it checks at start, and then not for six hours
            val another = Browser("http://127.0.0.1:${(ants as WebServerApplicationContext).webServer!!.port}").also { it.login() }
            eventually(60) { assertContains(notice(browser = another), "Ants 94.0.0 is available") }
            published("95.0.0")

            update(another)

            eventually(60) { assertContains(notice(browser = another), "Ants 95.0.0 runs from the next ants restart") }
        }
    }

    @Test
    fun `the model list refreshes with the update check`() {
        models.writeText(
            """
            | API model name     | Current state | Deprecated     | Tentative retirement date |
            | :----------------- | :------------ | :------------- | :------------------------ |
            | claude-opus-9      | Active        | N/A            | Not sooner than 2030      |
            | claude-sonnet-8    | Deprecated    | March 1, 2029  | June 1, 2029              |
            | claude-opus-5-5    | Retired       | March 1, 2028  | June 1, 2028              |
            """.trimIndent(),
        )

        eventually(60) {
            val form = browser.get("/agents/new").body
            assertContains(form, ">Opus 9 · claude-opus-9</option>")
            assertContains(form, "Sonnet 8 · claude-sonnet-8 · retires June 1, 2029")
            assertContains(form, "opus · newest Opus, now Opus 9")
            assertFalse("claude-opus-5-5" in form, "a retired model is off the list")
        }
    }

    private companion object {
        const val DONE = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
            """"structured_output":{"outcome":"done","summary":"Drafted.","output":{"text":"Hello"}}}"""
    }
}
