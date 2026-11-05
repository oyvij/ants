package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.history.HistoricProcessInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.web.server.context.WebServerApplicationContext
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class NotifyTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    @Autowired
    lateinit var history: HistoryService

    /** Starts a Run of `notify.bpmn`, its line reading [line], named [name] with [pipeline], and waits for its Notice. */
    private fun run(pipeline: String, name: String = "Nightly watch", line: String? = null, browser: Browser = this.browser): String {
        val before = notices(browser).size
        if (line == null) browser.import("notify.bpmn")
        else browser.upload("/processes", "file", "notify.bpmn", javaClass.getResource("/notify.bpmn")!!.readText().replace("Pipeline \${pipeline} failed on main", line).toByteArray())
        browser.get("/processes/watch")
        val run = browser.post("/processes/watch/runs", mapOf("pipeline" to pipeline, NAME to name)).path.removePrefix("/runs/")
        eventually { assertEquals(before + 1, notices(browser).size) }
        return run
    }

    /** The Notice rows on the Notification list. */
    private fun notices(browser: Browser = this.browser) = Regex("""<tr[^>]*>((?:(?!</tr>).)*?\[notice](?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(browser.get("/notifications").body).map { it.groupValues[1] }.toList()

    /** The path of the only Notice's entry. */
    private fun entry(browser: Browser = this.browser) = Regex("""href="(/notifications/notice-[^"]+)"""").find(notices(browser).single())!!.groupValues[1]

    @Test
    fun `a Run reaching Notify adds a Notice with its filled-in line, Process, Run name and time, and goes on to its end`() {
        settings(null)
        val run = run("api-build")
        eventually { assertEquals(HistoricProcessInstance.STATE_COMPLETED, history.createHistoricProcessInstanceQuery().processInstanceId(run).singleResult()?.state) }
        val notice = notices().single()
        assertContains(notice, "<i>i</i>[notice]")
        assertContains(notice, "Pipeline api-build failed on main")
        assertContains(notice, "Watch pipelines")
        assertContains(notice, """<a href="/runs/$run">Nightly watch</a>""")
        assertContains(notice, "0m")

        val page = browser.get(entry()).body
        assertContains(page, "Pipeline api-build failed on main")
        assertContains(page, """<a class="btn primary" href="/runs/$run">Open the Run</a>""")
        assertEquals("/runs/$run", browser.get("/runs/$run").path)
    }

    @Test
    fun `the line is one line of at most 200 characters, and a name it can't fill in is named in the Notice, which the Run still adds`() {
        settings(null)
        run("api\n\n   build " + "x".repeat(300))
        val line = Regex("""<span class="clip">([^<]*)</span>""").find(notices().single())!!.groupValues[1]
        assertEquals(200, line.length)
        assertEquals("Pipeline api build " + "x".repeat(180) + "…", line)

        browser.post("${entry()}/dismiss", mapOf())
        val run = run("api-build", line = "Pipeline \${pipeline} broke \${nosuch.name}")
        eventually { assertEquals(HistoricProcessInstance.STATE_COMPLETED, history.createHistoricProcessInstanceQuery().processInstanceId(run).singleResult()?.state) }
        assertContains(notices().single(), "Couldn't fill in \${nosuch.name}: Pipeline api-build broke \${nosuch.name}")
    }

    @Test
    fun `the count follows Notices live, and Dismiss removes a Notice for good, after its Run has ended`() {
        settings(null)
        val counts = browser.events("", "count")
        val before = assertNotNull(counts.poll(10, SECONDS)).toInt()
        run("api-build")
        assertEquals(before + 1, assertNotNull(counts.poll(10, SECONDS)).toInt())
        val entry = entry()
        assertEquals("/notifications", browser.post("$entry/dismiss", mapOf()).path)
        assertEquals(before, assertNotNull(counts.poll(10, SECONDS)).toInt())
        assertEquals(emptyList(), notices())
        assertEquals(404, browser.post("$entry/dismiss", mapOf()).status)
    }

    @Test
    fun `a Notice stays across a restart`() {
        val home = tempHome()
        fun url(ants: Any) = "http://127.0.0.1:${(ants as WebServerApplicationContext).webServer!!.port}"
        val run = startAnother(home).use { ants ->
            val browser = Browser(url(ants)).also { it.login() }
            home.resolve("settings.yaml").writeAtomically(vault)
            run("api-build", browser = browser)
        }
        startAnother(home).use { ants ->
            val browser = Browser(url(ants)).also { it.login() }
            val notice = notices(browser).single()
            assertContains(notice, "Pipeline api-build failed on main")
            assertContains(notice, """<a href="/runs/$run">Nightly watch</a>""")
            browser.post("${entry(browser)}/dismiss", mapOf())
            assertFalse("[notice]" in browser.get("/notifications").body)
        }
    }
}
