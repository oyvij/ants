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
        assertContains(side, """<span id="runs" class="m">1 live</span>""")
        assertContains(side, """<span class="m">v${build.version}</span>""")
        assertContains(side, """<form method="post" action="/logout">""")
    }

    @Test
    fun `the sidebar's Runs line follows the live Runs on the page's stream`() {
        waitingRun()
        val runs = browser.events("", "runs")
        assertEquals("1 live", runs.poll(10, SECONDS))
        browser.post("/processes/waits/runs", mapOf())
        assertEquals("2 live", runs.poll(10, SECONDS))
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
        assertContains(browser.get("/processes/start-form").body, """<textarea name="prompt" id="field-prompt" required data-markdown data-tall>""")
        assertContains(browser.get("/agents/new").body, """<textarea name="instructions" rows="14" data-markdown data-tall aria-label="Standing instructions">""")
        assertContains(browser.get("/skills/scout").body, """<textarea name="skill" rows="20" data-markdown readonly data-tall aria-label="SKILL.md">""")

        val preview = browser.post("/markdown", mapOf("text" to "**Bold** and <script>alert(1)</script>"))

        assertEquals(200, preview.status)
        assertContains(preview.body, "<strong>Bold</strong>")
        assertContains(preview.body, "&lt;script&gt;")
    }
}
