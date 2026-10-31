package ants

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals

class TemplatesTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    /** A blank Process named [name], as **New Process** opens it. */
    private fun blank(name: String) = browser.get("/processes/new.bpmn?name=" + name.replace(" ", "+")).body

    /** Drops the Template [template] into [bpmn], as the palette's Templates section does: the new diagram and the sub-process's id. */
    private fun drop(template: String, bpmn: String): Pair<String, String> {
        browser.get("/processes")
        val dropped = JsonMapper.shared().readValue(browser.post("/templates/$template/drop", mapOf("bpmn" to bpmn, "x" to "400", "y" to "300")).body, Map::class.java)
        return dropped["bpmn"] as String to dropped["element"] as String
    }

    @Test
    fun `dropping a Template inserts a collapsed sub-process with the Template's ids`() {
        val (dropped, element) = drop("review-loop", blank("Dropped once"))
        assertEquals("review_loop", element)
        assertContains(dropped, """<bpmn:subProcess id="review_loop" name="Review loop">""")
        assertContains(dropped, """<bpmn:userTask id="approve" name="Approve">""")
        assertContains(dropped, """<bpmn:sequenceFlow id="f2" sourceRef="approve" targetRef="enough"/>""")
        // The Process already has a `start`, so the Template's is suffixed, and its flow follows.
        assertContains(dropped, """<bpmn:startEvent id="start_2">""")
        assertContains(dropped, """<bpmn:sequenceFlow id="f1" sourceRef="start_2" targetRef="approve"/>""")
        assertContains(dropped, """<bpmndi:BPMNShape bpmnElement="review_loop" id="review_loop_di" isExpanded="false">""")
        assertContains(dropped, """<bpmndi:BPMNPlane bpmnElement="review_loop" id="plane_2">""")

        assertEquals("/processes/dropped-once", browser.post("/processes/deploy", mapOf("bpmn" to dropped)).path)
    }

    @Test
    fun `dropping a Template twice suffixes the second copy's ids and rewrites its references`() {
        val (once, _) = drop("review-loop", blank("Dropped twice"))
        val (twice, element) = drop("review-loop", once)
        assertEquals("review_loop_2", element)
        assertContains(twice, """<bpmn:subProcess id="review_loop_2" name="Review loop">""")
        assertContains(twice, """<bpmn:userTask id="approve_2" name="Approve">""")
        assertContains(twice, """<bpmn:startEvent id="start_3">""")
        assertContains(twice, """<bpmn:exclusiveGateway default="again_2" id="enough_2" name="Enough?">""")
        assertContains(twice, """<bpmn:sequenceFlow id="f1_2" sourceRef="start_3" targetRef="approve_2"/>""")
        assertContains(twice, """<bpmn:incoming>again_2</bpmn:incoming>""")
        assertContains(twice, "\${runs.approve_2 &gt;= 3}")
        assertContains(twice, """<bpmndi:BPMNPlane bpmnElement="review_loop_2" id="plane_3">""")
        assertContains(twice, """<bpmndi:BPMNShape bpmnElement="approve_2" id="approve_di_2">""")
        // The first copy is untouched.
        assertContains(twice, """<bpmn:sequenceFlow id="f1" sourceRef="start_2" targetRef="approve"/>""")
        assertContains(twice, "\${runs.approve &gt;= 3}")

        assertEquals("/processes/dropped-twice", browser.post("/processes/deploy", mapOf("bpmn" to twice)).path)
    }

    @Test
    fun `a second copy's Asker, Send back resumes and Walkthrough name its own renamed Tasks`() {
        val (grills, _) = drop("grill-you-answer", drop("grill-you-answer", blank("Grilled twice")).first)
        assertContains(grills, """<operaton:property name="ants.asker" value="grill_2"/>""")
        assertContains(grills, """<operaton:property name="ants.asker" value="grill"/>""")
        val (reviews, _) = drop("review-and-push", drop("review-and-push", blank("Reviewed twice")).first)
        assertContains(reviews, """<operaton:property name="ants.walkthrough" value="walkthrough_2"/>""")
        assertContains(reviews, """<operaton:property name="ants.send-back" value="fix_2"/>""")
        assertContains(reviews, """<operaton:property name="ants.agent" value="a-lead"/>""")
    }

    @Test
    fun `a renamed Task keeps the Agent and Skill it names, even when the Skill's name is its id`() {
        home.resolve("templates").createDirectories().resolve("grilling.bpmn").writeText("""<?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="grilling" isExecutable="true">
                <bpmn:serviceTask id="grill" operaton:type="external" operaton:topic="agent">
                  <bpmn:extensionElements><operaton:properties>
                    <operaton:property name="ants.agent" value="grill" /><operaton:property name="ants.skills" value="grill" />
                  </operaton:properties></bpmn:extensionElements>
                </bpmn:serviceTask>
              </bpmn:process>
              <bpmndi:BPMNDiagram id="diagram"><bpmndi:BPMNPlane id="grill_plane" bpmnElement="grilling" /></bpmndi:BPMNDiagram>
            </bpmn:definitions>""")
        val (once, _) = drop("grilling", blank("Grilled twice"))
        val (twice, _) = drop("grilling", once)
        assertContains(twice, """<bpmn:serviceTask id="grill_2" operaton:topic="agent" operaton:type="external">""")
        assertEquals(4, Regex("""value="grill"""").findAll(twice).count(), twice)
    }

    @Test
    fun `shipped Templates are read-only, with Copy`() {
        val list = browser.get("/templates").body
        assertContains(list, """<a href="/templates/review-loop">review-loop</a> Review loop <small>shipped</small>""")
        assertContains(browser.get("/templates.json").body, """{"name":"review-loop","title":"Review loop","shipped":true}""")

        val shipped = browser.get("/templates/review-loop").body
        assertContains(shipped, """<button class="primary" id="save" data-action="/templates/review-loop" hidden>Save</button>""")
        assertContains(shipped, """<form method="post" action="/templates/review-loop/copy">""")
        assertEquals(403, browser.post("/templates/review-loop", mapOf("bpmn" to browser.get("/templates/review-loop/diagram.bpmn").body)).status)

        home.resolve("templates/review-loop-copy.bpmn").toFile().delete()
        browser.get("/templates/review-loop")
        val copy = browser.post("/templates/review-loop/copy", emptyMap())
        assertEquals("/templates/review-loop-copy", copy.path)
        assertContains(copy.body, """<button class="primary" id="save" data-action="/templates/review-loop-copy">Save</button>""")
        assertContains(browser.get("/templates/review-loop-copy/diagram.bpmn").body, """<bpmn:userTask id="approve" name="Approve">""")
    }

    @Test
    fun `Save writes a Template whose Checks fail, as the Checks are advice on a Template`() {
        home.resolve("templates/unfinished.bpmn").toFile().delete()
        browser.get("/templates")
        browser.post("/templates/new", mapOf("name" to "unfinished"))
        val failing = browser.get("/templates/unfinished/diagram.bpmn").body.replace("</bpmn:process>", """<bpmn:serviceTask id="loose" name="Loose" operaton:type="external" operaton:topic="agent" /></bpmn:process>""")
        assertContains(browser.post("/processes/checks", mapOf("bpmn" to failing)).body, "Loose (loose) has no Agent.")

        val saved = browser.post("/templates/unfinished", mapOf("bpmn" to failing))
        assertEquals("/templates/unfinished", saved.path)
        assertContains(browser.get("/templates/unfinished/diagram.bpmn").body, """<bpmn:serviceTask id="loose" name="Loose" operaton:topic="agent" operaton:type="external"/>""")
    }

    @Test
    fun `editing a Template afterwards doesn't change the Process it was dropped into`() {
        home.resolve("templates/edited.bpmn").toFile().delete()
        browser.get("/templates")
        assertEquals("/templates/edited", browser.post("/templates/new", mapOf("name" to "edited")).path)
        val template = browser.get("/templates/edited/diagram.bpmn").body
        assertContains(template, """<bpmn:process id="edited" isExecutable="true" name="edited"""")

        val (dropped, element) = drop("edited", blank("Edited afterwards"))
        assertEquals("edited", element)
        browser.post("/processes/deploy", mapOf("bpmn" to dropped))

        browser.get("/templates/edited")
        val saved = browser.post("/templates/edited", mapOf("bpmn" to template.replace("</bpmn:process>", """<bpmn:userTask id="added" /></bpmn:process>""")))
        assertEquals("/templates/edited", saved.path)
        assertContains(browser.get("/templates/edited/diagram.bpmn").body, """<bpmn:userTask id="added"/>""")
        assertEquals(false, "added" in browser.get("/processes/edited-afterwards/diagram.bpmn").body)
    }
}
