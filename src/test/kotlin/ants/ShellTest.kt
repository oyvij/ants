package ants

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The shell every page is built from: the sidebar, the login page and Log out. */
class ShellTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    @Autowired
    private lateinit var build: BuildProperties

    /** The sidebar of the page at [path]. */
    private fun sidebar(path: String) = Regex("""<nav class="side">.*?</nav>""", RegexOption.DOT_MATCHES_ALL).find(browser.get(path).body)!!.value

    /** Starts a Run that waits on a User Task, so it stays live and is on the Notification list. */
    private fun waitingRun() {
        settings(null)
        browser.get("/processes")
        browser.post("/processes/deploy", mapOf("bpmn" to """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="waits" name="Waits" isExecutable="true" camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start" /><bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait" />
                <bpmn:userTask id="wait" name="Wait" /><bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="end" />
                <bpmn:endEvent id="end" />
              </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()))
        browser.post("/processes/waits/runs", mapOf())
    }

    @Test
    fun `the sidebar groups Work and Library, counts the Notification list and the live Runs, and has Log out with the running version`() {
        waitingRun()
        val side = sidebar("/runs")
        for (link in listOf("Home", "Notification list", "Work", "Runs", "Processes", "Tickets", "Library", "Agents", "Skills", "Templates", "Settings", "Log out")) {
            assertContains(side, link)
        }
        assertContains(side, """<span id="waiting" class="n">1</span>""")
        assertContains(side, """<span id="runs" class="m"><span data-flash="1">1 live</span></span>""")
        assertContains(side, """<span class="m">v${build.version}</span>""")
        assertContains(side, """<form method="post" action="/logout">""")
    }

    @Test
    fun `every page, the login page too, renders at the device's width`() {
        val viewport = """<meta name="viewport" content="width=device-width, initial-scale=1">"""
        assertContains(browser().get("/login").body, viewport)
        for (path in listOf("/", "/notifications", "/runs", "/processes", "/tickets", "/agents", "/skills", "/templates", "/settings")) {
            assertContains(browser.get(path).body, viewport, message = path)
        }
    }

    @Test
    fun `the top bar shows Ants, the Notification list count and the ☰ that opens the sidebar`() {
        waitingRun()
        val bar = Regex("""<header class="bar">.*?</header>""", RegexOption.DOT_MATCHES_ALL).find(browser.get("/runs").body)!!.value
        assertContains(bar, """<b>🐜</b>Ants""")
        assertContains(bar, """<span id="bar-waiting" class="n">1</span>""")
        assertContains(bar, """<label for="menu" title="Menu">☰</label>""")
    }

    @Test
    fun `the sidebar's Runs line follows the live Runs on the page's stream`() {
        waitingRun()
        val runs = browser.events("", "runs")
        assertEquals("""<span data-flash="1">1 live</span>""", runs.poll(10, SECONDS))
        browser.post("/processes/waits/runs", mapOf())
        assertEquals("""<span data-flash="2">2 live</span>""", runs.poll(10, SECONDS))
    }

    @Test
    fun `Dark mode and Light mode in the sidebar write the theme setting and go back to the page`() {
        settings(null, "theme: light\n")
        val side = sidebar("/agents")
        assertContains(side, "Dark mode")
        assertContains(side, "Light mode") // the stylesheet shows the one that isn't the page's scheme

        val back = browser.post("/theme", mapOf("theme" to "dark"))

        assertEquals("/", back.path) // no Referer here; a browser goes back to the page it was on
        assertContains(home.resolve("settings.yaml").readText(), "theme: dark")
        assertContains(browser.get("/agents").body, """<meta name="color-scheme" content="dark">""")
        browser.post("/theme", mapOf("theme" to "light"))
        assertContains(browser.get("/agents").body, """<meta name="color-scheme" content="light">""")
    }

    @Test
    fun `the login page is Ants' own, and Log out ends the session on it`() {
        val login = browser().get("/login")
        assertContains(login.body, """<div class="logo"><b>🐜</b>Ants</div>""")
        assertContains(login.body, """<link rel="stylesheet" href="/ants.css">""")
        assertFalse("""<nav class="side">""" in login.body)
        assertEquals(200, browser().get("/ants.css").status) // the login page's look needs no login

        val out = browser.post("/logout", mapOf())

        assertEquals("/login", out.path)
        assertContains(out.body, "You are logged out.")
        assertEquals("/login", browser.get("/").path)
        assertContains(browser().login("nope").body, "Wrong password.")
    }

    @Test
    fun `the markdown fields are marked for the editor, whose Preview the server renders as it renders the Approve page`() {
        browser.import("start-form.bpmn")
        assertContains(browser.get("/processes/start-form").body, """<textarea name="prompt" id="field-prompt" placeholder="e.g. Add a dark mode toggle to the web app" required data-markdown data-tall>""")
        assertContains(browser.get("/agents/new").body, """<textarea id="instructions" name="instructions" rows="14" placeholder="e.g. You implement one Ticket at a time, test first, and commit once the suite passes." data-markdown data-tall>""")
        assertContains(browser.get("/skills/scout").body, """<textarea id="skill-md" name="skill" rows="20" placeholder="e.g. ---&#10;name: release-notes&#10;description: Writes the release notes for a version.&#10;---&#10;Read the commits since the last tag and sum them up for users." data-markdown readonly data-tall>""")

        val preview = browser.post("/markdown", mapOf("text" to "**Bold** and <script>alert(1)</script>"))

        assertEquals(200, preview.status)
        assertContains(preview.body, "<strong>Bold</strong>")
        assertContains(preview.body, "&lt;script&gt;")
    }
}
