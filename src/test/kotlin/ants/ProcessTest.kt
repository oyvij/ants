package ants

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProcessTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    @Autowired
    private lateinit var processes: Processes

    private fun waits(cron: String? = null) {
        browser.get("/processes")
        browser.post("/processes/deploy", mapOf("bpmn" to """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="waits" name="Waits" isExecutable="true" camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start" /><bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait" />
                <bpmn:userTask id="wait" name="Wait" /><bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="end" /><bpmn:endEvent id="end" />
              </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()))
        if (cron != null) browser.post("/processes/waits/schedule", mapOf("cron" to cron))
    }

    /** The Processes list's row for the Process [name]. */
    private fun row(name: String): String {
        val list = browser.get("/processes").body
        return Regex("""<tr\b(?:(?!</tr>).)*?<b>$name</b>(?:(?!</tr>).)*</tr>""", RegexOption.DOT_MATCHES_ALL).find(list)?.value
            ?: throw AssertionError("No row for $name in $list")
    }

    @Test
    fun `the Processes list shows where each Process is from, its Vault and Source map, Schedule, check, live Runs and Edit diagram`() {
        home.resolve("settings.yaml").writeAtomically(vault)
        processes.deployShipped()
        browser.import("start-end.bpmn")
        waits(cron = "0 0 0 * * *")
        browser.post("/processes/waits/runs", mapOf())

        val waits = row("Waits")
        assertContains(waits, """data-href="/processes/waits"""")
        assertContains(waits, ">you<")
        assertContains(waits, "notes · —")
        assertContains(waits, """<td class="small">⏰ 1</td>""")
        assertContains(waits, """<span class="pill p-ready">Ready</span>""")
        assertContains(waits, """<td class="muted">1</td>""")
        assertContains(waits, """<a href="/processes/waits/edit">Edit diagram</a>""")
        assertContains(row("Start to end"), """<td class="small"></td>""")
        assertContains(row("Start to end"), """<td class="muted">0</td>""")
        assertContains(row("Set up Vault"), ">shipped<")

        home.resolve("settings.yaml").writeAtomically("") // no Vault
        assertContains(row("Waits"), """<span class="pill p-failed">Can't start</span>""")
    }

    @Test
    fun `an imported Process appears on the Processes list`() {
        val processes = browser.import("start-end.bpmn")
        assertEquals("/processes", processes.path)
        assertContains(processes.body, """<a href="/processes/start-end"><b>Start to end</b></a>""")
    }

    @Test
    fun `the Process page has its Vault and Source map, the check, the start form with optional fields marked, the Schedule card and its Runs`() {
        home.resolve("settings.yaml").writeAtomically(vault)
        browser.import("start-form.bpmn")
        val page = browser.get("/processes/start-form").body
        assertContains(page, """<div class="sub">Vault <b>notes</b> · Source map <b>—</b>""")
        assertContains(page, """<span class="pill p-ready">Ready</span>""")
        assertContains(page, """<label class="f" for="field-prompt">Prompt</label>""")
        assertContains(page, """<label class="f" for="field-workBranch">Work branch <span class="muted small" style="font-weight: 500">(optional)</span></label>""")
        assertContains(page, "No schedule. Runs start when someone presses Start.")
        assertContains(page, """<a class="btn" href="#start">＋ Add a schedule</a>""")

        waits(cron = "0 0 0 * * *")
        val run = browser.post("/processes/waits/runs", mapOf(NAME to "Tiny wait")).path
        val scheduled = browser.get("/processes/waits").body
        assertContains(scheduled, "<h3>⏰ Schedules</h3>")
        assertContains(scheduled, "Every day at 00:00")
        assertContains(scheduled, "<code>0 0 0 * * *</code>")
        assertContains(scheduled, Regex("""00:00 · in (\d+h )?\d+m"""))
        assertContains(scheduled, "It runs once when Ants is back, not once per missed time.")
        assertContains(scheduled, """<tr class="click" data-href="$run"><td><a href="$run"><b>Tiny wait</b></a></td>""")

        val id = Regex("<tr id=\"schedule-([^\"]+)\">").find(scheduled)!!.groupValues[1]
        browser.post("/processes/waits/schedules/$id/pause", mapOf())
        assertContains(browser.get("/processes/waits").body, """<span class="muted">paused</span>""")
    }

    @Test
    fun `camunda attributes are deployed as operaton`() {
        browser.import("start-end.bpmn")
        val diagram = browser.get("/processes/start-end/diagram.bpmn").body
        assertContains(diagram, """operaton:historyTimeToLive="180"""")
        assertFalse("camunda" in diagram)
    }

    @Test
    fun `the Process page shows the diagram with bpmn-js from the static resources`() {
        browser.import("start-end.bpmn")
        val page = browser.get("/processes/start-end")
        assertContains(page.body, "<h1>Start to end</h1>")
        val script = Regex("""<script src="(/vendor/camunda-bpmn-js/[^"]+\.js)"""").find(page.body)!!.groupValues[1]
        assertContains(browser.get(script).body, "BpmnNavigatedViewer")
        assertContains(page.body, "/processes/start-end/diagram.bpmn")
    }

    @Test
    fun `a file that isn't BPMN is refused with the reason`() {
        browser.get("/processes")
        val page = browser.upload("/processes", "file", "notes.bpmn", "not xml".toByteArray())
        assertContains(page.body, """<p role="alert">notes.bpmn: """)
    }
}
