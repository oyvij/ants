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
        val run = start(pipeline, name, line, browser)
        eventually { assertEquals(before + 1, notices(browser).size) }
        return run
    }

    /** Starts a Run of `notify.bpmn`, its line reading [line], named [name] with [pipeline]. */
    private fun start(pipeline: String, name: String = "Nightly watch", line: String? = null, browser: Browser = this.browser): String {
        if (line == null) browser.import("notify.bpmn")
        else browser.upload("/processes", "file", "notify.bpmn", javaClass.getResource("/notify.bpmn")!!.readText().replace("Pipeline \${pipeline} failed on main", line).toByteArray())
        browser.get("/processes/watch")
        return browser.post("/processes/watch/runs", mapOf("pipeline" to pipeline, NAME to name)).path.removePrefix("/runs/")
    }

    /** Waits for [run] to end. */
    private fun ended(run: String) =
        eventually { assertEquals(HistoricProcessInstance.STATE_COMPLETED, history.createHistoricProcessInstanceQuery().processInstanceId(run).singleResult()?.state) }

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
    fun `a closing brace inside a quoted string of an expression stays inside it, as the engine reads it`() {
        settings(null)
        run("api-build", line = "Pipeline \${pipeline.concat('}')} failed, \\\${not filled}")
        assertContains(notices().single(), "<span class=\"clip\">Pipeline api-build} failed, \${not filled}</span>")
    }

    /** A Process [key], named [name], that calls `watch` with its own `pipeline`. */
    private fun caller(key: String, name: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                          id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="$key" name="$name" isExecutable="true" operaton:historyTimeToLive="180">
            <bpmn:startEvent id="start">
              <bpmn:extensionElements><operaton:formData><operaton:formField id="pipeline" label="Pipeline" type="string" /></operaton:formData></bpmn:extensionElements>
            </bpmn:startEvent>
            <bpmn:callActivity id="call" name="Watch" calledElement="watch">
              <bpmn:extensionElements><operaton:in sourceExpression="${'$'}{pipeline}" target="pipeline" /></bpmn:extensionElements>
            </bpmn:callActivity>
            <bpmn:endEvent id="end" />
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="call" />
            <bpmn:sequenceFlow id="f2" sourceRef="call" targetRef="end" />
          </bpmn:process>
        </bpmn:definitions>""".trimIndent()

    @Test
    fun `a Notify in a called Process records that Process, so two callers with the same line count up on one Notice`() {
        settings(null)
        browser.import("notify.bpmn")
        for ((key, name) in listOf("nightly" to "Nightly", "weekly" to "Weekly")) {
            browser.upload("/processes", "file", "$key.bpmn", caller(key, name).toByteArray())
        }
        browser.get("/processes/nightly")
        val first = browser.post("/processes/nightly/runs", mapOf("pipeline" to "api-build", NAME to "Nightly run")).path.removePrefix("/runs/")
        eventually { assertEquals(1, notices().size) }
        val notice = notices().single()
        assertContains(notice, "Watch pipelines")
        assertFalse("Nightly</div>" in notice)
        assertContains(notice, """<a href="/runs/$first">Nightly run</a>""")
        ended(first)

        browser.get("/processes/weekly")
        ended(browser.post("/processes/weekly/runs", mapOf("pipeline" to "api-build", NAME to "Weekly run")).path.removePrefix("/runs/"))
        eventually { assertContains(notices().single(), Regex("""×2, last at \d\d:\d\d""")) }
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

    @Test
    fun `a Notice with the same Process and line as an open one counts up on it, showing its count and last time`() {
        settings(null)
        run("api-build")
        val entry = entry()
        assertFalse("×" in notices().single())
        ended(start("api-build", name = "Later watch"))
        ended(start("api-build", name = "Last watch"))
        eventually { assertContains(notices().single(), Regex("""×3, last at \d\d:\d\d""")) }
        assertEquals(entry, entry())
        assertContains(browser.get(entry).body, Regex("""×3, last at \d\d:\d\d"""))

        run("web-build")
        assertEquals(2, notices().size)
        browser.post("$entry/dismiss", mapOf())
        browser.post("${entry()}/dismiss", mapOf())
        run("api-build")
        assertFalse("×" in notices().single())
    }

    @Test
    fun `a Notice whose Run was deleted still shows, and leads to a note that the Run is gone`() {
        settings(null)
        val run = run("api-build")
        ended(run)
        history.deleteHistoricProcessInstance(run)
        val notice = notices().single()
        assertContains(notice, "Pipeline api-build failed on main")
        assertContains(notice, "Nightly watch")
        assertFalse("/runs/$run" in notice)
        val page = browser.get(entry()).body
        assertContains(page, "Pipeline api-build failed on main")
        assertContains(page, "The Run Nightly watch is gone.")
        assertFalse("/runs/$run" in page)
        assertFalse("Open the Run" in page)
    }
}
