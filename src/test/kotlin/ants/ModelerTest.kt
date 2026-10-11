package ants

import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ModelerTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    /** Opens the modeler at [path] and fetches the diagram it loads, as its script does. */
    private fun open(path: String): Pair<Browser.Page, String> {
        val modeler = browser.get(path)
        assertEquals(200, modeler.status)
        val src = Regex("""id="canvas" data-src="([^"]+)"""").find(modeler.body)!!.groupValues[1].replace("&amp;", "&")
        return modeler to browser.get(src).body
    }

    @Test
    fun `a Process drawn from scratch is saved, deployed and started`() {
        val (modeler, blank) = open("/processes/new?name=Drawn+from+scratch&from=")
        val script = Regex("""<script src="(/vendor/camunda-bpmn-js/[^"]+\.js)"""").find(modeler.body)!!.groupValues[1]
        assertContains(browser.get(script).body, "BpmnModeler")
        assertContains(blank, """id="drawn-from-scratch"""")
        assertContains(blank, """name="Drawn from scratch"""")

        // What the user draws: the start event flows into a User Task.
        val drawn = blank.replace(
            "</bpmn:process>",
            """<bpmn:userTask id="review" /><bpmn:sequenceFlow id="f" sourceRef="start" targetRef="review" /></bpmn:process>""",
        )
        val process = browser.post("/processes/deploy", mapOf("bpmn" to drawn))
        assertEquals("/processes/drawn-from-scratch", process.path)
        assertContains(process.body, "<h1>Drawn from scratch</h1>")

        home.resolve("settings.yaml").writeText(vault)
        val run = browser.post("/processes/drawn-from-scratch/runs", mapOf("prompt" to "Draw"))
        assertContains(run.body, "Running")
    }

    @Test
    fun `Save and deploy writes operaton only`() {
        home.resolve("agents").createDirectories().resolve("writer.md").writeText("---\nskills: draft\n---\nWrite plainly.\n")
        home.resolve("skills/draft").createDirectories().resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts a text.\n---\nDraft the text.\n")
        val (_, blank) = open("/processes/new?name=Topic+in+the+modeler")
        // camunda-bpmn-js writes camunda:, such as an Agent Task's topic.
        val drawn = blank.replace("<bpmn:definitions ", """<bpmn:definitions xmlns:camunda="http://camunda.org/schema/1.0/bpmn" """)
            .replace("</bpmn:process>", """<bpmn:serviceTask id="draft" camunda:type="external" camunda:topic="agent"><bpmn:extensionElements><camunda:properties>""" +
                """<camunda:property name="ants.agent" value="writer" /><camunda:property name="ants.skill" value="draft" /></camunda:properties></bpmn:extensionElements></bpmn:serviceTask></bpmn:process>""")
        browser.post("/processes/deploy", mapOf("bpmn" to drawn))
        val diagram = browser.get("/processes/topic-in-the-modeler/diagram.bpmn").body
        assertContains(diagram, """operaton:topic="agent"""")
        assertEquals(false, "camunda" in diagram)
    }

    @Test
    fun `Save and deploy refuses a diagram the engine can't parse, with the reason`() {
        val (_, blank) = open("/processes/new?name=Broken")
        val broken = blank.replace("</bpmn:process>", """<bpmn:sequenceFlow id="f" sourceRef="start" targetRef="nowhere" /></bpmn:process>""")
        val refused = browser.post("/processes/deploy", mapOf("bpmn" to broken))
        assertEquals(400, refused.status)
        assertContains(refused.body, "nowhere")
    }

    @Test
    fun `New Process as a copy opens the copy in the modeler, under a key of its own`() {
        browser.import("start-end.bpmn")
        assertContains(browser.get("/processes").body, """<option value="start-end">Start to end</option>""")
        val (modeler, copy) = open("/processes/new?from=start-end")
        assertContains(modeler.body, "<h1>Start to end copy</h1>")
        assertContains(copy, """id="start-to-end-copy"""")
        assertContains(copy, """name="Start to end copy"""")
        assertContains(copy, """<bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="end"/>""")

        browser.post("/processes/deploy", mapOf("bpmn" to copy))
        val (_, again) = open("/processes/new?from=start-end")
        assertContains(again, """id="start-to-end-copy-2"""")
    }

    @Test
    fun `a deployed Process opens in the modeler`() {
        browser.import("start-end.bpmn")
        val (_, diagram) = open("/processes/start-end/edit")
        assertContains(diagram, """id="start-end"""")
        assertContains(browser.get("/processes/start-end").body, """href="/processes/start-end/edit"""")
    }
}
