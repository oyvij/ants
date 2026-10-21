package ants

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DeleteProcessTest : AntsTest() {
    @Autowired
    private lateinit var processes: Processes

    private val browser by lazy { browser().also { it.login() } }

    private fun deploy(bpmn: String) {
        browser.get("/processes")
        assertEquals("/processes", browser.upload("/processes", "file", "process.bpmn", bpmn.toByteArray()).path)
    }

    private val waits = """
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                          id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="waits" name="Waits" isExecutable="true" operaton:historyTimeToLive="180">
            <bpmn:startEvent id="start" /><bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait" />
            <bpmn:userTask id="wait" name="Wait" /><bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="end" /><bpmn:endEvent id="end" />
          </bpmn:process>
        </bpmn:definitions>
    """.trimIndent()

    private fun caller(key: String, name: String, called: String) = deploy("""
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                          id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="$key" name="$name" isExecutable="true" operaton:historyTimeToLive="180">
            <bpmn:startEvent id="start" /><bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="call" />
            <bpmn:callActivity id="call" name="Call it" calledElement="$called" />
            <bpmn:sequenceFlow id="f2" sourceRef="call" targetRef="end" /><bpmn:endEvent id="end" />
          </bpmn:process>
        </bpmn:definitions>
    """.trimIndent())

    private fun confirmation(key: String): Browser.Page {
        browser.get("/processes/$key")
        return browser.post("/processes/$key/delete", emptyMap())
    }

    private fun delete(key: String) {
        confirmation(key)
        assertEquals("/processes", browser.post("/processes/$key/delete", mapOf("confirm" to "on")).path)
    }

    @Test
    fun `Delete asks for confirmation, listing the Processes that call it`() {
        browser.import("start-end.bpmn")
        caller("calls-it", "Calls it", "start-end")

        val asked = confirmation("start-end")
        assertContains(asked.body, "Delete the Process Start to end?")
        assertContains(asked.body, """<a href="/processes/calls-it">Calls it</a>""")
        assertContains(asked.body, """action="/processes/start-end/delete"""")
        assertEquals(200, browser.get("/processes/start-end").status)
    }

    @Test
    fun `once confirmed, every version of the Process is gone from the Processes list and its page`() {
        browser.import("start-end.bpmn")
        deploy(javaClass.getResource("/start-end.bpmn")!!.readText().replace("""<bpmn:endEvent id="end" />""", """<bpmn:endEvent id="end" name="Done" />"""))

        delete("start-end")
        assertFalse("/processes/start-end\"" in browser.get("/processes").body)
        assertEquals(404, browser.get("/processes/start-end").status)
    }

    @Test
    fun `an ended Run of the deleted Process still shows in the Runs list and its page opens, with its Tasks`() {
        settings(null)
        deploy(waits)
        browser.get("/processes/waits")
        val run = browser.post("/processes/waits/runs", mapOf(NAME to "Gone but kept")).path
        browser.post("$run/cancel", emptyMap())

        delete("waits")
        assertContains(browser.get("/runs").body, "Gone but kept")
        val page = browser.get(run)
        assertEquals(200, page.status)
        assertContains(page.body, """id="state">Cancelled<""")
        assertContains(page.body, "<td><b>Wait</b>")
        assertContains(page.body, "User Task")
    }

    @Test
    fun `Delete is refused while a Run of the Process is live, naming the Run`() {
        settings(null)
        deploy(waits)
        browser.get("/processes/waits")
        val run = browser.post("/processes/waits/runs", mapOf(NAME to "Still waiting")).path

        for (form in listOf(emptyMap(), mapOf("confirm" to "on"))) {
            browser.get("/processes/waits")
            val refused = browser.post("/processes/waits/delete", form)
            assertEquals(409, refused.status)
            assertContains(refused.body, """<a href="$run">Still waiting</a>""")
            assertContains(refused.body, "Cancel them first.")
        }
        assertEquals(200, browser.get("/processes/waits").status)
    }

    @Test
    fun `a new Process with the deleted one's name starts with no Schedules, no Build log and no Runs started on a Schedule`() {
        settings(null)
        browser.import("start-end.bpmn")
        browser.get("/processes/start-end")
        assertEquals("/processes/start-end", browser.post("/processes/start-end/schedule", mapOf("cron" to "0 * * * * *")).path)
        val folder = home.resolve("processes/start-end").createDirectories()
        folder.resolve("build-log.md").writeText("# Built\n")
        folder.resolve("draft.bpmn").writeText("<definitions />")

        delete("start-end")
        assertFalse(folder.exists())
        browser.import("start-end.bpmn")
        clock.offset = Duration.ofMinutes(2)
        Thread.sleep(1000)

        val page = browser.get("/processes/start-end").body
        assertContains(page, "No schedule. Runs start when someone presses Start.")
        assertFalse("data-href=\"/runs/" in page)
        assertEquals(404, browser.get("/processes/start-end/build-log").status)
    }

    @Test
    fun `Delete shows only on the user's own Processes, never on a shipped one`() {
        settings(null)
        processes.deployShipped()
        browser.import("start-end.bpmn")

        assertContains(browser.get("/processes/start-end").body, """<form method="post" action="/processes/start-end/delete">""")
        assertFalse("/processes/set_up_vault/delete" in browser.get("/processes/set_up_vault").body)
        assertEquals(403, browser.post("/processes/set_up_vault/delete", mapOf("confirm" to "on")).status)
        assertEquals(200, browser.get("/processes/set_up_vault").status)
    }
}
