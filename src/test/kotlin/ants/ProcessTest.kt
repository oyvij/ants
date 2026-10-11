package ants

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProcessTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    @Test
    fun `an imported Process appears on the Processes list`() {
        val processes = browser.import("start-end.bpmn")
        assertEquals("/processes", processes.path)
        assertContains(processes.body, """<a href="/processes/start-end">Start to end</a>""")
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
