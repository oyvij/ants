package ants

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** Gateways, timers and sub-processes (#50): their Checks, and how a Run takes them. */
class GatewaysTest : AntsTest() {
    private val browser by lazy { browser().also { it.login(); it.get("/processes") } }

    private fun diagram(key: String, body: String) = """
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                          id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="$key" name="$key" isExecutable="true" operaton:historyTimeToLive="180">
            <bpmn:startEvent id="start" />
            $body
          </bpmn:process>
        </bpmn:definitions>
    """.trimIndent()

    private fun checks(bpmn: String): List<Map<*, *>> =
        JsonMapper.shared().readValue(browser.post("/processes/checks", mapOf("bpmn" to bpmn)).body, List::class.java).map { it as Map<*, *> }

    @Test
    fun `an exclusive gateway with no default path is a Check error, and a merge needs none`() {
        val bpmn = diagram(
            "no-default",
            """
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="merge" />
            <bpmn:exclusiveGateway id="merge" />
            <bpmn:sequenceFlow id="f2" sourceRef="merge" targetRef="choose" />
            <bpmn:exclusiveGateway id="choose" name="Ship?" />
            <bpmn:sequenceFlow id="f3" sourceRef="choose" targetRef="end"><bpmn:conditionExpression>${'$'}{true}</bpmn:conditionExpression></bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="f4" sourceRef="choose" targetRef="merge" />
            <bpmn:endEvent id="end" />
            """,
        )
        assertEquals(listOf(mapOf("element" to "choose", "message" to "Ship? (choose) has no default path.", "warning" to false)), checks(bpmn))
    }

    @Test
    fun `a timer without a time is a Check error`() {
        val bpmn = diagram(
            "no-time",
            """
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait" />
            <bpmn:intermediateCatchEvent id="wait"><bpmn:timerEventDefinition /></bpmn:intermediateCatchEvent>
            <bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="end" />
            <bpmn:endEvent id="end" />
            """,
        )
        assertEquals(listOf(mapOf("element" to "wait", "message" to "wait is a timer without a time.", "warning" to false)), checks(bpmn))
    }

    private val claude = FakeClaude()

    /** The Agent Task [id], which runs the Skill `draft`. */
    private fun agentTask(id: String) = """
        <bpmn:serviceTask id="$id" operaton:type="external" operaton:topic="agent">
          <bpmn:extensionElements>
            <operaton:properties>
              <operaton:property name="ants.agent" value="writer" />
              <operaton:property name="ants.skill" value="draft" />
            </operaton:properties>
          </bpmn:extensionElements>
        </bpmn:serviceTask>
    """

    /** The Skill `draft` plays [lines], then hands back [output]. */
    private fun draft(output: String, vararg lines: String) {
        home.resolve("settings.yaml").writeText("claude:\n  command: [${claude.command}]\n")
        home.resolve("agents").createDirectories().resolve("writer.md").writeText("---\nname: writer\nskills: draft\n---\nWrite plainly.\n")
        val skill = home.resolve("skills/draft").createDirectories()
        skill.resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts a text.\n---\nDraft the text.\n")
        skill.resolve("result.schema.json").writeText("""{"type":"object","properties":{"verdict":{}}}""")
        claude.session(
            "draft", *lines,
            """{"type":"result","subtype":"success","is_error":false,"session_id":"s","structured_output":{"outcome":"done","summary":"Drafted.","output":$output}}""",
        )
    }

    /** Deploys the Process [key] drawn as [body] and starts a Run of it; the Run page's path. */
    private fun run(key: String, body: String): String {
        assertEquals("/processes/$key", browser.post("/processes/deploy", mapOf("bpmn" to diagram(key, body))).path)
        return browser.post("/processes/$key/runs", mapOf()).path
    }

    /** Each Task's run count in the Run, as the Run page shows `runs`. */
    private fun runs(run: String) = Regex("""<dt>runs</dt><dd><code>(.*?)</code>""").find(browser.get(run).body.replace("&#34;", "\""))!!.groupValues[1]

    @Test
    fun `the condition builder's expression routes a Run on a Result field`() {
        draft("""{"verdict":"ship"}""")
        val run = run(
            "route",
            """
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="review" />
            ${agentTask("review")}
            <bpmn:sequenceFlow id="f2" sourceRef="review" targetRef="choose" />
            <bpmn:exclusiveGateway id="choose" default="f4" />
            <bpmn:sequenceFlow id="f3" sourceRef="choose" targetRef="ship"><bpmn:conditionExpression>${'$'}{review.output.verdict == 'ship'}</bpmn:conditionExpression></bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="f4" sourceRef="choose" targetRef="rework" />
            <bpmn:userTask id="ship" />
            <bpmn:userTask id="rework" />
            """,
        )
        eventually { assertEquals("""{"review":1,"ship":1,"rework":0}""", runs(run)) }
    }

    @Test
    fun `an intermediate timer waits, then the Run goes on`() {
        val run = run(
            "timer-wait",
            """
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait" />
            <bpmn:intermediateCatchEvent id="wait"><bpmn:timerEventDefinition><bpmn:timeDuration>PT2S</bpmn:timeDuration></bpmn:timerEventDefinition></bpmn:intermediateCatchEvent>
            <bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="after" />
            <bpmn:userTask id="after" />
            """,
        )
        assertEquals("""{"after":0}""", runs(run))
        eventually { assertEquals("""{"after":1}""", runs(run)) }
    }

    @Test
    fun `a parallel gateway fans out to Agent Tasks that finish together, and joins`() {
        draft("{}", "wait go")
        val run = run(
            "fan-out",
            """
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="fork" />
            <bpmn:parallelGateway id="fork" />
            <bpmn:sequenceFlow id="f2" sourceRef="fork" targetRef="a" />
            <bpmn:sequenceFlow id="f3" sourceRef="fork" targetRef="b" />
            <bpmn:sequenceFlow id="f4" sourceRef="fork" targetRef="c" />
            ${agentTask("a")}
            ${agentTask("b")}
            ${agentTask("c")}
            <bpmn:sequenceFlow id="f5" sourceRef="a" targetRef="join" />
            <bpmn:sequenceFlow id="f6" sourceRef="b" targetRef="join" />
            <bpmn:sequenceFlow id="f7" sourceRef="c" targetRef="join" />
            <bpmn:parallelGateway id="join" />
            <bpmn:sequenceFlow id="f8" sourceRef="join" targetRef="after" />
            <bpmn:userTask id="after" />
            """,
        )
        eventually { assertEquals(3, claude.launches("draft").size) }
        claude.release("go")
        // A Task losing the race to the join without a retry would be [failed], and the Run would never get past it.
        eventually { assertEquals("""{"a":1,"b":1,"c":1,"after":1}""", runs(run)) }
    }

    @Test
    fun `a timer boundary on a User Task interrupts it when it fires`() {
        val run = run(
            "boundary",
            """
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="answer" />
            <bpmn:userTask id="answer" />
            <bpmn:boundaryEvent id="late" attachedToRef="answer"><bpmn:timerEventDefinition><bpmn:timeDuration>PT1S</bpmn:timeDuration></bpmn:timerEventDefinition></bpmn:boundaryEvent>
            <bpmn:sequenceFlow id="f2" sourceRef="late" targetRef="fallback" />
            <bpmn:userTask id="fallback" />
            <bpmn:sequenceFlow id="f3" sourceRef="answer" targetRef="end" />
            <bpmn:endEvent id="end" />
            """,
        )
        eventually { assertEquals("""{"answer":1,"fallback":1}""", runs(run)) }
        assertContains(browser.get(run).body, "Running")
    }
}
