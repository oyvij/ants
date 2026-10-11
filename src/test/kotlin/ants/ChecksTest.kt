package ants

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChecksTest : AntsTest() {
    private val browser by lazy { browser().also { it.login(); it.get("/processes") } }

    /** A Process `checked` with one start event and [body], and [outside] after the Process (such as a pool). */
    private fun diagram(body: String, outside: String = "") = """
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                          id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="checked" name="Checked" isExecutable="true" operaton:historyTimeToLive="180">
            <bpmn:startEvent id="start" />
            $body
          </bpmn:process>
          $outside
        </bpmn:definitions>
    """.trimIndent()

    private fun agentTask(id: String, inside: String = "", inputs: String = "") = """
        <bpmn:serviceTask id="$id" name="Draft" operaton:type="external" operaton:topic="agent">
          <bpmn:extensionElements>
            <operaton:properties>
              <operaton:property name="ants.agent" value="writer" />
              <operaton:property name="ants.skill" value="draft" />
            </operaton:properties>
            <operaton:inputOutput>$inputs</operaton:inputOutput>
          </bpmn:extensionElements>
          $inside
        </bpmn:serviceTask>
    """

    /** The live Checks the modeler shows for [bpmn]. */
    private fun checks(bpmn: String): List<Map<*, *>> =
        JsonMapper.shared().readValue(browser.post("/processes/checks", mapOf("bpmn" to bpmn)).body, List::class.java).map { it as Map<*, *> }

    @Test
    fun `Save and deploy refuses a terminate end event and names it`() {
        val refused = browser.post("/processes/deploy", mapOf("bpmn" to diagram("""<bpmn:endEvent id="stop"><bpmn:terminateEventDefinition /></bpmn:endEvent>""")))
        assertEquals(400, refused.status)
        assertContains(refused.body, "stop is a terminate end event, which Ants doesn't run.")
        assertEquals(404, browser.get("/processes/checked").status)
    }

    /** Each body draws `x`, which Ants doesn't run, and the Check's message says what it is. */
    @ParameterizedTest(name = "{1}")
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '`', // the messages hold ' and the XML "
        textBlock = """
        <bpmn:task id="x" />                                                                              | is a plain Task
        <bpmn:manualTask id="x" />                                                                        | is a manual task
        <bpmn:scriptTask id="x" />                                                                        | is a script task
        <bpmn:sendTask id="x" />                                                                          | is a send task
        <bpmn:receiveTask id="x" />                                                                       | is a receive task
        <bpmn:businessRuleTask id="x" />                                                                  | is a business rule task (DMN)
        <bpmn:serviceTask id="x" operaton:class="a.B" />                                                  | is a service task that isn't an Agent or Built-in Task
        <bpmn:serviceTask id="x" operaton:type="external" operaton:topic="mail" />                        | is on topic mail, which no Built-in Task has.
        <bpmn:endEvent id="x"><bpmn:errorEventDefinition /></bpmn:endEvent>                               | is an error end event
        <bpmn:endEvent id="x"><bpmn:messageEventDefinition /></bpmn:endEvent>                             | is a message end event
        <bpmn:endEvent id="x"><bpmn:escalationEventDefinition /></bpmn:endEvent>                          | is an escalation end event
        <bpmn:endEvent id="x"><bpmn:signalEventDefinition /></bpmn:endEvent>                              | is a signal end event
        <bpmn:endEvent id="x"><bpmn:compensateEventDefinition /></bpmn:endEvent>                          | is a compensate end event
        <bpmn:intermediateCatchEvent id="x"><bpmn:messageEventDefinition /></bpmn:intermediateCatchEvent> | is a message intermediate catch event
        <bpmn:intermediateCatchEvent id="x"><bpmn:conditionalEventDefinition><bpmn:condition>c</bpmn:condition></bpmn:conditionalEventDefinition></bpmn:intermediateCatchEvent> | is a conditional intermediate catch event
        <bpmn:intermediateCatchEvent id="x"><bpmn:linkEventDefinition name="l" /></bpmn:intermediateCatchEvent> | is a link intermediate catch event
        <bpmn:intermediateCatchEvent id="x" />                                                            | is an intermediate catch event without a timer
        <bpmn:intermediateThrowEvent id="x" />                                                            | is an intermediate throw event
        <bpmn:startEvent id="x" />                                                                        | is a second start event
        <bpmn:userTask id="u" /><bpmn:boundaryEvent id="x" attachedToRef="u" cancelActivity="false"><bpmn:timerEventDefinition /></bpmn:boundaryEvent> | is a non-interrupting timer boundary event
        <bpmn:userTask id="u" /><bpmn:boundaryEvent id="x" attachedToRef="u"><bpmn:errorEventDefinition /></bpmn:boundaryEvent> | is an error boundary event
        <bpmn:subProcess id="s" /><bpmn:boundaryEvent id="x" attachedToRef="s"><bpmn:timerEventDefinition /></bpmn:boundaryEvent> | is a timer boundary event on something other than a User Task
        <bpmn:inclusiveGateway id="x" />                                                                  | is an inclusive gateway
        <bpmn:complexGateway id="x" />                                                                    | is a complex gateway
        <bpmn:eventBasedGateway id="x" />                                                                 | is an event based gateway
        <bpmn:subProcess id="x" triggeredByEvent="true" />                                                | is an event sub-process
        <bpmn:transaction id="x" />                                                                       | is a transaction
        <bpmn:adHocSubProcess id="x" />                                                                   | is an ad hoc sub process
        <bpmn:dataObjectReference id="x" dataObjectRef="d" /><bpmn:dataObject id="d" />                   | is a data object reference
        <bpmn:dataStoreReference id="x" />                                                                | is a data store reference
        <bpmn:userTask id="x"><bpmn:standardLoopCharacteristics /></bpmn:userTask>                         | is a standard loop
        <bpmn:userTask id="x"><bpmn:multiInstanceLoopCharacteristics /></bpmn:userTask>                    | is a multi-instance user task
        <bpmn:serviceTask id="x" operaton:type="external" operaton:topic="clone-repos"><bpmn:multiInstanceLoopCharacteristics /></bpmn:serviceTask> | is a multi-instance Built-in Task
        <bpmn:callActivity id="x" calledElement="c"><bpmn:multiInstanceLoopCharacteristics><bpmn:completionCondition>c</bpmn:completionCondition></bpmn:multiInstanceLoopCharacteristics></bpmn:callActivity> | has a multi-instance completion condition
""",
    )
    fun `a refused element is a Check naming it`(body: String, what: String) {
        val checks = checks(diagram(body))
        assertTrue(checks.any { it["element"] == "x" && "x $what" in it["message"] as String && it["warning"] == false }, "no Check on x in $checks")
    }

    @Test
    fun `lanes are refused`() {
        val checks = checks(diagram("").replace("<bpmn:startEvent", """<bpmn:laneSet id="ls"><bpmn:lane id="x" /></bpmn:laneSet><bpmn:startEvent"""))
        assertEquals(listOf(mapOf("element" to "x", "message" to "x is a lane, which Ants doesn't run.", "warning" to false)), checks)
    }

    @Test
    fun `start-form fields need a variable name, each its own`() {
        val bpmn = diagram("").replace(
            """<bpmn:startEvent id="start" />""",
            """
            <bpmn:startEvent id="start" name="Begin"><bpmn:extensionElements><operaton:formData>
              <operaton:formField id="prompt" label="Prompt" type="string" />
              <operaton:formField id="prompt" label="Prompt again" type="string" />
              <operaton:formField id=" " label="Nameless" type="string" />
            </operaton:formData></bpmn:extensionElements></bpmn:startEvent>
            """,
        )
        assertEquals(
            listOf(
                mapOf("element" to "start", "message" to "Begin (start) has more than one start-form field named prompt.", "warning" to false),
                mapOf("element" to "start", "message" to "Begin (start) has a start-form field, Nameless, without a variable name.", "warning" to false),
            ),
            checks(bpmn),
        )
    }

    @Test
    fun `a pool is refused`() {
        val checks = checks(diagram("", """<bpmn:collaboration id="c"><bpmn:participant id="pool" processRef="checked" /></bpmn:collaboration>"""))
        assertEquals(listOf(mapOf("element" to "pool", "message" to "pool is a participant, which Ants doesn't run.", "warning" to false)), checks)
    }

    @Test
    fun `a Process without a start event is refused`() {
        val checks = checks(diagram("").replace("""<bpmn:startEvent id="start" />""", ""))
        assertEquals(listOf(mapOf("element" to "checked", "message" to "Checked (checked) has no start event.", "warning" to false)), checks)
    }

    @Test
    fun `every allowed element passes and deploys`() {
        val bpmn = diagram(
            """
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="draft" />
            ${agentTask("draft", "<bpmn:multiInstanceLoopCharacteristics isSequential=\"true\" operaton:collection=\"\${'$'}{list}\" />")}
            <bpmn:sequenceFlow id="f2" sourceRef="draft" targetRef="fork" />
            <bpmn:parallelGateway id="fork" />
            <bpmn:sequenceFlow id="f3" sourceRef="fork" targetRef="wait" />
            <bpmn:intermediateCatchEvent id="wait"><bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition></bpmn:intermediateCatchEvent>
            <bpmn:sequenceFlow id="f4" sourceRef="wait" targetRef="choose" />
            <bpmn:exclusiveGateway id="choose" default="f6" />
            <bpmn:sequenceFlow id="f5" sourceRef="choose" targetRef="review"><bpmn:conditionExpression>${'$'}{true}</bpmn:conditionExpression></bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="f6" sourceRef="choose" targetRef="clone" />
            <bpmn:userTask id="review" />
            <bpmn:boundaryEvent id="late" attachedToRef="review"><bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition></bpmn:boundaryEvent>
            <bpmn:serviceTask id="clone" operaton:type="external" operaton:topic="clone-repos" />
            <bpmn:sequenceFlow id="f7" sourceRef="fork" targetRef="group" />
            <bpmn:subProcess id="group">
              <bpmn:startEvent id="inner-start" />
              <bpmn:sequenceFlow id="f8" sourceRef="inner-start" targetRef="inner-end" />
              <bpmn:endEvent id="inner-end" />
            </bpmn:subProcess>
            <bpmn:callActivity id="call" calledElement="other" />
            <bpmn:endEvent id="end" />
            <bpmn:textAnnotation id="note"><bpmn:text>A note</bpmn:text></bpmn:textAnnotation>
            <bpmn:association id="a1" sourceRef="note" targetRef="draft" />
            <bpmn:group id="frame" />
            """,
        )
        assertEquals(emptyList(), checks(bpmn))
        assertEquals("/processes/checked", browser.post("/processes/deploy", mapOf("bpmn" to bpmn)).path)
    }

    @Test
    fun `a timer start event is allowed`() {
        val bpmn = diagram("").replace("""<bpmn:startEvent id="start" />""",
            """<bpmn:startEvent id="start"><bpmn:timerEventDefinition><bpmn:timeCycle>0 0 0 * * ?</bpmn:timeCycle></bpmn:timerEventDefinition></bpmn:startEvent>""")
        assertEquals(emptyList(), checks(bpmn))
    }

    @Test
    fun `an empty input is a warning, and the Process still deploys`() {
        val bpmn = diagram(agentTask("draft", inputs = """<operaton:inputParameter name="tone" />"""))
        assertEquals(listOf(mapOf("element" to "draft", "message" to "Draft (draft) has an empty input tone.", "warning" to true)), checks(bpmn))
        assertEquals("/processes/checked", browser.post("/processes/deploy", mapOf("bpmn" to bpmn)).path)
    }

    @Test
    fun `the modeler reads camunda as the deploy path does`() {
        val bpmn = diagram("""<bpmn:serviceTask id="x" camunda:type="external" camunda:topic="agent" />""")
            .replace("<bpmn:definitions ", """<bpmn:definitions xmlns:camunda="http://camunda.org/schema/1.0/bpmn" """)
        assertEquals(emptyList(), checks(bpmn))
    }

    @Test
    fun `importing a file with a refused element shows the same Checks and doesn't deploy`() {
        val page = browser.upload("/processes", "file", "terminate.bpmn",
            diagram("""<bpmn:endEvent id="stop"><bpmn:terminateEventDefinition /></bpmn:endEvent>""").toByteArray())
        assertContains(page.body, "terminate.bpmn: stop is a terminate end event, which Ants doesn't run.")
        assertEquals(404, browser.get("/processes/checked").status)
    }

    @Test
    fun `a file that isn't BPMN is one Check on the whole file`() {
        val checks = checks("not xml")
        assertEquals(1, checks.size)
        assertEquals(null, checks[0]["element"])
    }
}
