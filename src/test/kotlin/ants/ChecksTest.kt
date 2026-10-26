package ants

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChecksTest : AntsTest() {
    private val browser by lazy { browser().also { it.login(); it.get("/processes") } }

    @BeforeEach
    fun writer() {
        settings(null) // its own, so a hide-shipped left by an earlier test doesn't hide the shipped Agents and Skills
        writerAndDraft()
    }

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
              <operaton:property name="ants.skills" value="draft" />
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
        <bpmn:serviceTask id="x" operaton:class="a.B" />                                                  | is a service task that isn't an Agent, Script or Built-in Task
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
        // The Process the call activity calls.
        assertEquals("/processes/other", browser.post("/processes/deploy", mapOf("bpmn" to diagram("").replace("\"checked\"", "\"other\""))).path)
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
    fun `a timer start event is refused, since a Schedule is made from the Process page`() {
        val bpmn = diagram("").replace("""<bpmn:startEvent id="start" />""",
            """<bpmn:startEvent id="start"><bpmn:timerEventDefinition><bpmn:timeCycle>0 0 0 * * *</bpmn:timeCycle></bpmn:timerEventDefinition></bpmn:startEvent>""")
        assertEquals(listOf(mapOf("element" to "start", "message" to "A timer start isn't run by Ants: schedule the Process from its page.", "warning" to false)), checks(bpmn))
        val refused = browser.post("/processes/deploy", mapOf("bpmn" to bpmn))
        assertEquals(400, refused.status)
        assertContains(refused.body.replace("&#39;", "'"), "A timer start isn't run by Ants: schedule the Process from its page.")
    }

    @Test
    fun `an empty input is a warning, and the Process still deploys`() {
        val bpmn = diagram(agentTask("draft", inputs = """<operaton:inputParameter name="tone" />"""))
        assertEquals(listOf(mapOf("element" to "draft", "message" to "Draft (draft) has an empty input tone.", "warning" to true)), checks(bpmn))
        assertEquals("/processes/checked", browser.post("/processes/deploy", mapOf("bpmn" to bpmn)).path)
    }

    @Test
    fun `the modeler reads camunda as the deploy path does`() {
        val bpmn = diagram(agentTask("x").replace("operaton:", "camunda:"))
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

    /** The Agent `writer`, listing [skills], and the Skill `draft`, whose Tasks promise `{text, meta}` ([promising]). */
    private fun writerAndDraft(skills: String = "draft, other") {
        home.resolve("agents").createDirectories().resolve("writer.md").writeText("---\nskills: $skills\n---\nWrite plainly.\n")
        skill("draft")
        promises["draft"] = """{"type":"object","properties":{"text":{"type":"string"},"meta":{"type":"object","properties":{"words":{"type":"integer"}}}}}"""
    }

    private fun skill(name: String, folder: String = name) {
        home.resolve("skills/$folder").createDirectories().resolve("SKILL.md").writeText("---\nname: $name\ndescription: A Skill.\n---\nDo it.\n")
    }

    /** Draft, then Review reading [reads] from Draft, with the start-form field `prompt`. */
    private fun twoTasks(reads: String, review: String = agentTask("review", inputs = """<operaton:inputParameter name="tone">$reads</operaton:inputParameter>""")) = diagram(
        """
        <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="draft" />
        ${agentTask("draft", inputs = """<operaton:inputParameter name="tone">${'$'}{prompt}</operaton:inputParameter>""")}
        <bpmn:sequenceFlow id="f2" sourceRef="draft" targetRef="review" />
        $review
        """,
    ).replace(
        """<bpmn:startEvent id="start" />""",
        """<bpmn:startEvent id="start"><bpmn:extensionElements><operaton:formData><operaton:formField id="prompt" label="Prompt" type="string" /></operaton:formData></bpmn:extensionElements></bpmn:startEvent>""",
    )

    /** Save and deploy refuses [bpmn], naming [element] in a Check saying [message]. */
    private fun refuses(bpmn: String, element: String, message: String) {
        assertContains(checks(bpmn), mapOf("element" to element, "message" to message, "warning" to false))
        val refused = browser.post("/processes/deploy", mapOf("bpmn" to bpmn))
        assertEquals(400, refused.status)
        assertContains(refused.body, message)
    }

    private fun withAgent(agent: String, skill: String = "draft") = agentTask("draft").replace("\"writer\"", "\"$agent\"").replace("value=\"draft\"", "value=\"$skill\"")

    @Test
    fun `an unknown, ambiguous or missing Agent name is refused`() {
        writerAndDraft()
        refuses(diagram(withAgent("nobody")), "draft", "Draft (draft): No Agent named nobody.")
        refuses(diagram(withAgent("")), "draft", "Draft (draft) has no Agent.")
        val agents = home.resolve("agents")
        agents.resolve("twin-1.md").writeText("---\nname: twin\n---\nOne.\n")
        agents.resolve("twin-2.md").writeText("---\nname: twin\n---\nTwo.\n")
        try {
            refuses(diagram(withAgent("twin")), "draft", "Draft (draft): More than one Agent is named twin.")
        } finally {
            agents.resolve("twin-1.md").deleteIfExists()
            agents.resolve("twin-2.md").deleteIfExists()
        }
    }

    @Test
    fun `an unknown, ambiguous or missing Skill name is refused`() {
        writerAndDraft()
        refuses(diagram(withAgent("writer", "nothing")), "draft", "Draft (draft): No Skill named nothing.")
        refuses(diagram(withAgent("writer", "")), "draft", "Draft (draft) has no Assignment and doesn't list exactly one Skill.")
        skill("other", folder = "twin-1")
        skill("other", folder = "twin-2")
        try {
            refuses(diagram(withAgent("writer", "other")), "draft", "Draft (draft): More than one Skill is named other.")
        } finally {
            home.resolve("skills/twin-1").toFile().deleteRecursively()
            home.resolve("skills/twin-2").toFile().deleteRecursively()
        }
    }

    @Test
    fun `an Agent or a Skill sharing its id with another is refused, without showing the id`() {
        writerAndDraft()
        val agents = home.resolve("agents")
        agents.resolve("writer.md").writeText("---\nid: a-twin01\n" + agents.resolve("writer.md").readText().removePrefix("---\n"))
        agents.resolve("copied.md").writeText("---\nid: a-twin01\nname: copied\n---\nCopied by hand.\n")
        refuses(diagram(withAgent("writer")), "draft",
            "Draft (draft): The Agent writer has the same id as the Agent copied, in ${agents.resolve("copied.md")}. Delete the id of one of them and restart Ants to give it a new one.")
        assertFalse(checks(diagram(withAgent("writer"))).any { "a-twin01" in it["message"].toString() })
        agents.resolve("copied.md").deleteIfExists()

        home.resolve("skills/draft/ants.yaml").writeText("id: k-twin01\n")
        skill("echo", folder = "echo")
        home.resolve("skills/echo/ants.yaml").writeText("id: k-twin01\n")
        refuses(diagram(withAgent("writer")), "draft",
            "Draft (draft): The Skill draft has the same id as the Skill echo, in ${home.resolve("skills/echo")}. Delete the id of one of them and restart Ants to give it a new one.")
    }

    /** The Agent Task `draft` with [properties] in place of its `ants.skill`. */
    private fun job(vararg properties: Pair<String, String>) = agentTask("draft").replace("""<operaton:property name="ants.skills" value="draft" />""",
        properties.joinToString("") { (name, value) -> """<operaton:property name="$name" value="$value" />""" })

    @Test
    fun `an Agent Task with no Assignment must list exactly one Skill of its own, and each listed Skill must be the Agent's and known`() {
        writerAndDraft()
        skill("other")
        val one = "Draft (draft) has no Assignment and doesn't list exactly one Skill."
        refuses(diagram(job()), "draft", one) // the Agent's Skills don't count
        refuses(diagram(job("ants.skills" to "")), "draft", one)
        refuses(diagram(job("ants.skills" to "draft, other")), "draft", one)
        refuses(diagram(job("ants.assignment" to "Draft it.", "ants.skills" to "draft, nothing")), "draft", "Draft (draft): No Skill named nothing.")
        skill("extra")
        try {
            refuses(diagram(job("ants.assignment" to "Draft it.", "ants.skills" to "draft, extra")), "draft", "Draft (draft): The Agent writer doesn't list the Skill extra.")
            for (fine in listOf(job("ants.skills" to "other"), job("ants.assignment" to "Draft it."), job("ants.assignment" to "Draft it.", "ants.skills" to ""),
                job("ants.assignment" to "Draft it.", "ants.skills" to "draft other")))
                assertEquals(emptyList(), checks(diagram(fine)).filter { it["warning"] == false })
            home.resolve("agents/writer.md").writeText("---\nskills: \"*\"\n---\nWrite plainly.\n")
            assertEquals(emptyList(), checks(diagram(job("ants.assignment" to "Draft it.", "ants.skills" to "extra"))).filter { it["warning"] == false })
        } finally {
            home.resolve("skills/extra").toFile().deleteRecursively()
        }
    }

    @Autowired
    lateinit var scripts: Scripts

    @Test
    fun `an Agent Task's Scripts must each be known and listed by its Agent`() {
        scripts.save(Script("s-post01", "post-message", "Posts a message."), "", emptyMap())
        scripts.save(Script("s-other1", "other", "Does other."), "", emptyMap())
        home.resolve("agents/writer.md").writeText("---\nskills: draft\nscripts: [s-post01]\n---\nWrite plainly.\n")
        refuses(diagram(job("ants.skills" to "draft", "ants.scripts" to "post-message, other")), "draft", "Draft (draft): The Agent writer doesn't list the Script other.")
        refuses(diagram(job("ants.skills" to "draft", "ants.scripts" to "nothing")), "draft", "Draft (draft): No Script named nothing.")
        for (fine in listOf(job("ants.skills" to "draft", "ants.scripts" to "post-message"), job("ants.skills" to "draft", "ants.scripts" to "s-post01"), job("ants.skills" to "draft", "ants.scripts" to "")))
            assertEquals(emptyList(), checks(diagram(fine)).filter { it["warning"] == false })
    }

    @Test
    fun `a Skill the Agent doesn't list is refused`() {
        writerAndDraft(skills = "other")
        refuses(diagram(agentTask("draft")), "draft", "Draft (draft): The Agent writer doesn't list the Skill draft.")
    }

    @Test
    fun `an invalid result schema is refused`() {
        writerAndDraft()
        val invalid = agentTask("draft").replace("</operaton:properties>", """<operaton:property name="ants.result-schema" value="[1]" /></operaton:properties>""")
        refuses(diagram(invalid), "draft", "Draft (draft): Its result schema is invalid: A result schema is a JSON object.")
    }

    @Test
    fun `a reference to a Task or field that doesn't exist is refused`() {
        writerAndDraft()
        refuses(twoTasks("${'$'}{drafted.output.text}"), "review", "Draft (review) reads drafted, which is no Task, start-form field or Run variable.")
        refuses(twoTasks("${'$'}{draft.output.colour}"), "review", "Draft (review) reads draft.output.colour, which draft's output schema doesn't have.")
        refuses(twoTasks("${'$'}{draft.output.meta.lines}"), "review", "Draft (review) reads draft.output.meta.lines, which draft's output schema doesn't have.")
        refuses(twoTasks("${'$'}{draft.result}"), "review", "Draft (review) reads draft.result, which draft's Result doesn't have.")
        refuses(twoTasks("${'$'}{runs.drafted > 2}"), "review", "Draft (review) reads runs.drafted, which is no Task.")
        val condition = twoTasks("${'$'}{prompt}").replace(
            """<bpmn:sequenceFlow id="f2" sourceRef="draft" targetRef="review" />""",
            """<bpmn:sequenceFlow id="f2" sourceRef="draft" targetRef="review"><bpmn:conditionExpression>${'$'}{draft.output.tone == 'dry'}</bpmn:conditionExpression></bpmn:sequenceFlow>""",
        )
        refuses(condition, "f2", "f2 reads draft.output.tone, which draft's output schema doesn't have.")
    }

    @Test
    fun `reading the output of a Task that promises no result schema is refused`() {
        writerAndDraft()
        skill("other")
        refuses(twoTasks("${'$'}{draft.output.text}").replace(Regex("""(<bpmn:serviceTask id="draft"(?:(?!</bpmn:serviceTask>).)*?)value="draft""", RegexOption.DOT_MATCHES_ALL), "$1value=\"other"),
            "review", "Draft (review) reads draft.output.text, but draft promises no result schema.")
    }

    @Test
    fun `references to start-form fields, Run variables and Result fields deploy, as the Task's own result schema has them`() {
        writerAndDraft()
        val reads = "${'$'}{draft.output.text}${'$'}{draft.output.meta.words}${'$'}{draft.summary}${'$'}{runs.draft >= 2 and empty clones}" +
            "${'$'}{prompt.trim()}${'$'}{draft.output.text.length() > 0 ? 'a.b' : \"c.d\"}${'$'}{true}"
        assertEquals(emptyList(), checks(twoTasks(reads)))
        val override = twoTasks("${'$'}{draft.output.verdict}").replace(
            """<operaton:property name="ants.skills" value="draft" />""",
            """<operaton:property name="ants.skills" value="draft" /><operaton:property name="ants.result-schema" value="{&quot;type&quot;:&quot;object&quot;,&quot;properties&quot;:{&quot;verdict&quot;:{}}}" />""",
        )
        assertEquals(emptyList(), checks(override))
    }

    @Test
    fun `the Agent Task panel's catalogue lists each Agent's Skills, and each Skill's description, folder and whether it is the user's own`() {
        writerAndDraft()
        skill("other")
        val catalogue = JsonMapper.shared().readValue(browser.get("/catalogue.json").body, Map::class.java)
        val writer = (catalogue["agents"] as List<*>).single { (it as Map<*, *>)["name"] == "writer" }
        assertEquals(listOf("draft", "other"), (writer as Map<*, *>)["skills"])
        assertEquals(true, writer["own"]) // so New Skill can say it adds the Skill to this Agent
        val shipped = (catalogue["agents"] as List<*>).map { it as Map<*, *> }.filter { it["name"] != "writer" }
        assertTrue(shipped.isNotEmpty() && shipped.none { it["own"] == true })
        val skills = (catalogue["skills"] as List<*>).associateBy { (it as Map<*, *>)["name"] }
        val draft = skills["draft"] as Map<*, *>
        assertEquals(setOf("id", "name", "description", "own", "folder"), draft.keys)
        assertEquals(listOf(true, false), listOf(draft["own"], (skills["tdd"] as Map<*, *>)["own"]))
        assertEquals(home.resolve("skills").toString(), draft["folder"])
    }

    @Test
    fun `the catalogue gives an Agent with every Skill, skills star, each Skill by name`() {
        writerAndDraft()
        home.resolve("agents/everyone.md").writeText("---\nskills: \"*\"\n---\nAll.\n")
        val catalogue = JsonMapper.shared().readValue(browser.get("/catalogue.json").body, Map::class.java)
        val everyone = (catalogue["agents"] as List<*>).map { it as Map<*, *> }.single { it["name"] == "everyone" }
        val names = (catalogue["skills"] as List<*>).map { (it as Map<*, *>)["name"] }.distinct()
        assertEquals(names, everyone["skills"])
        assertTrue("draft" in names && "tdd" in names)
    }

    /** Review in a multi-instance over `${prompt}`, its element variable [element] (or the default), reading [reads]. */
    private fun inLoop(reads: String, element: String? = null) = twoTasks(reads, agentTask(
        "review",
        """<bpmn:multiInstanceLoopCharacteristics operaton:collection="${'$'}{prompt}" ${element?.let { "operaton:elementVariable=\"$it\"" }.orEmpty()} />""",
        """<operaton:inputParameter name="tone">$reads</operaton:inputParameter>""",
    ))

    @Test
    fun `a loop's own variables are read only inside it, its element variable under its own name`() {
        writerAndDraft()
        refuses(twoTasks("${'$'}{item}"), "review", "Draft (review) reads item, which is no Task, start-form field or Run variable.")
        refuses(twoTasks("${'$'}{loopCounter}"), "review", "Draft (review) reads loopCounter, which is no Task, start-form field or Run variable.")
        refuses(inLoop("${'$'}{item}", element = "ticket"), "review", "Draft (review) reads item, which is no Task, start-form field or Run variable.")
        assertEquals(emptyList(), checks(inLoop("${'$'}{ticket}${'$'}{loopCounter}${'$'}{nrOfInstances}", element = "ticket")))
        assertEquals(emptyList(), checks(inLoop("${'$'}{item}")))
    }

    /** What the input pickers of [element] in [bpmn] offer. */
    private fun offered(bpmn: String, element: String = "review") =
        JsonMapper.shared().readValue(browser.post("/processes/inputs", mapOf("bpmn" to bpmn, "element" to element)).body, List::class.java).toSet()

    @Test
    fun `input pickers offer a loop's element variable inside it, and the Results of an earlier call by Task id`() {
        writerAndDraft()
        assertContains(offered(inLoop("", element = "ticket")), "${'$'}{ticket}")
        assertContains(offered(inLoop("")), "${'$'}{item}")
        assertFalse("${'$'}{item}" in offered(twoTasks("")))

        assertEquals("/processes/called", browser.post("/processes/deploy", mapOf("bpmn" to diagram(agentTask("draft")).replace("\"checked\"", "\"called\""))).path)
        val caller = twoTasks("").replace("""<bpmn:serviceTask id="draft"""", """<bpmn:callActivity id="build" calledElement="called" /><bpmn:serviceTask id="unused"""")
            .replace("""sourceRef="draft"""", """sourceRef="build"""").replace("""targetRef="draft"""", """targetRef="build"""")
        assertEquals(
            setOf("${'$'}{prompt}", "${'$'}{runs}", "${'$'}{clones}", "${'$'}{sourceMap}", "${'$'}{build.draft.summary}", "${'$'}{build.draft.output}", "${'$'}{build.draft.output.text}", "${'$'}{build.draft.output.meta}"),
            offered(caller),
        )
    }

    @Test
    fun `input pickers offer only start-form fields, Run variables and the Results of earlier Tasks, a User Task's by field`() {
        writerAndDraft()
        val bpmn = twoTasks("").replace(
            """<bpmn:sequenceFlow id="f2" sourceRef="draft" targetRef="review" />""",
            """
            <bpmn:sequenceFlow id="f2" sourceRef="draft" targetRef="ask" />
            <bpmn:userTask id="ask" />
            <bpmn:sequenceFlow id="f3" sourceRef="ask" targetRef="review" />
            <bpmn:sequenceFlow id="f4" sourceRef="review" targetRef="after" />
            <bpmn:userTask id="after" />
            <bpmn:sequenceFlow id="f5" sourceRef="start" targetRef="aside" />
            <bpmn:userTask id="aside" />
            """,
        )
        val offered = JsonMapper.shared().readValue(browser.post("/processes/inputs", mapOf("bpmn" to bpmn, "element" to "review")).body, List::class.java)
        assertEquals(
            setOf("${'$'}{prompt}", "${'$'}{runs}", "${'$'}{clones}", "${'$'}{sourceMap}", "${'$'}{draft.summary}", "${'$'}{draft.output}", "${'$'}{draft.output.text}", "${'$'}{draft.output.meta}", "${'$'}{ask}", "${'$'}{ask.approved}", "${'$'}{ask.comment}"),
            offered.toSet(),
        )
    }

    @Test
    fun `later Tasks read an Agent Task's promised fields, questions too when it asks Rounds, else its summary, and a User Task's fields`() {
        promises.clear() // a test before may have had its Skill promise a schema
        val schema = """<operaton:property name="ants.result-schema" value="{&quot;type&quot;:&quot;object&quot;,&quot;properties&quot;:{&quot;note&quot;:{&quot;type&quot;:&quot;string&quot;}}}" />"""
        val bpmn = diagram(
            agentTask("asker").replace("</operaton:properties>", "$schema</operaton:properties>") + agentTask("plain") +
                """<bpmn:userTask id="answer"><bpmn:extensionElements><operaton:properties><operaton:property name="ants.asker" value="asker" /></operaton:properties></bpmn:extensionElements></bpmn:userTask>""",
        )
        val read = JsonMapper.shared().readValue(browser.post("/processes/outputs", mapOf("bpmn" to bpmn)).body, Map::class.java)
        assertEquals(listOf("${'$'}{asker.output.note}", "${'$'}{asker.output.questions}"), read["asker"])
        assertEquals(listOf("${'$'}{plain.summary}"), read["plain"])
        assertEquals(listOf("${'$'}{answer.approved}", "${'$'}{answer.comment}"), read["answer"])
    }

    private fun script(id: String, name: String, inputs: List<String> = emptyList()) = home.resolve("scripts/$id").createDirectories().run {
        resolve("script.yaml").writeText("id: $id\nname: $name\ndescription: Does $name.\ninputs: {type: object, properties: {${inputs.joinToString(", ") { "$it: {}" }}}}\nvars: {}\nsecrets: []\n")
        resolve("script.mjs").writeText("console.log('hi')\n")
    }

    private fun scriptTask(id: String, script: String, inputs: Map<String, String> = emptyMap(), kept: String? = null) = """
        <bpmn:serviceTask id="$id" name="Post" operaton:type="external" operaton:topic="script">
          <bpmn:extensionElements>
            <operaton:properties>
              <operaton:property name="ants.script" value="$script" />
              ${kept?.let { """<operaton:property name="ants.name.$script" value="$it" />""" }.orEmpty()}
            </operaton:properties>
            <operaton:inputOutput>${inputs.entries.joinToString("") { (name, value) -> """<operaton:inputParameter name="$name">$value</operaton:inputParameter>""" }}</operaton:inputOutput>
          </bpmn:extensionElements>
        </bpmn:serviceTask>
    """

    private fun withoutScripts(test: () -> Unit) = try {
        test()
    } finally {
        home.resolve("scripts").toFile().deleteRecursively()
    }

    @Test
    fun `a Script Task naming no Script, or one the library doesn't have by id or by name, is refused`() = withoutScripts {
        script("s-post01", "post-message", listOf("text"))
        refuses(diagram(scriptTask("post", "")), "post", "Post (post) has no Script.")
        refuses(diagram(scriptTask("post", "nothing")), "post", "Post (post): No Script named nothing.")
        refuses(diagram(scriptTask("post", "s-gone01", kept = "gone")), "post", "Post (post): No Script named gone.")
        for (fine in listOf(scriptTask("post", "post-message"), scriptTask("post", "s-post01"), scriptTask("post", "s-other1", kept = "post-message"))) {
            assertEquals(emptyList(), checks(diagram(fine)).filter { it["warning"] == false }, fine)
        }
    }

    @Test
    fun `a Script Task passing an input its Script doesn't declare is refused`() = withoutScripts {
        script("s-post01", "post-message", listOf("text", "channel"))
        refuses(diagram(scriptTask("post", "s-post01", mapOf("text" to "hi", "chanel" to "general"))), "post",
            "Post (post): The Script post-message doesn't declare the input chanel.")
        assertEquals(emptyList(), checks(diagram(scriptTask("post", "s-post01", mapOf("text" to "hi", "channel" to "general")))).filter { it["warning"] == false })
    }

    @Test
    fun `a Script Task's literal input that isn't its input schema's type is refused, naming the input and the type, and an expression is left to the run`() = withoutScripts {
        home.resolve("scripts/s-count1").createDirectories().run {
            resolve("script.yaml").writeText(
                "id: s-count1\nname: count-up\ndescription: Counts.\n" +
                    "inputs: {type: object, properties: {count: {type: number}, flag: {type: boolean}, tags: {type: array, items: {type: string}}, label: {type: string}}}\nvars: {}\nsecrets: []\n")
            resolve("script.mjs").writeText("console.log('hi')\n")
        }
        refuses(diagram(scriptTask("post", "s-count1", mapOf("count" to "five"))), "post", "Post (post): The input count is a number, which five isn't.")
        refuses(diagram(scriptTask("post", "s-count1", mapOf("flag" to "yes"))), "post", "Post (post): The input flag is a yes/no, which yes isn't.")
        refuses(diagram(scriptTask("post", "s-count1", mapOf("tags" to "a, b"))), "post", "Post (post): The input tags is a list of text, which a, b isn't.")
        val fine = mapOf("count" to "5", "flag" to "false", "tags" to """["a"]""", "label" to "anything")
        assertEquals(emptyList(), checks(diagram(scriptTask("post", "s-count1", fine))).filter { it["warning"] == false })
        assertEquals(emptyList(), checks(diagram(scriptTask("post", "s-count1", mapOf("count" to "\${count}")))).filter { "The input count" in it["message"].toString() })
    }

    @Test
    fun `a Script Task leaving out a required input is refused, naming the input, and one leaving out an optional input is not`() = withoutScripts {
        home.resolve("scripts/s-post01").createDirectories().run {
            resolve("script.yaml").writeText(
                "id: s-post01\nname: post-message\ndescription: Posts.\n" +
                    "inputs: {type: object, properties: {text: {type: string}, channel: {type: string}}, required: [text]}\nvars: {}\nsecrets: []\n")
            resolve("script.mjs").writeText("console.log('hi')\n")
        }
        refuses(diagram(scriptTask("post", "s-post01", mapOf("channel" to "general"))), "post",
            "Post (post): The Script post-message needs the input text.")
        assertEquals(emptyList(), checks(diagram(scriptTask("post", "s-post01", mapOf("text" to "hi")))).filter { it["warning"] == false })
    }

    @Test
    fun `a Script sharing its id with another Script, an Agent or a Skill is refused`() = withoutScripts {
        script("s-twin01", "post-message")
        home.resolve("scripts/copied").createDirectories().resolve("script.yaml").writeText("id: s-twin01\nname: copied\ndescription: Copied by hand.\n")
        refuses(diagram(scriptTask("post", "post-message")), "post",
            "Post (post): The Script post-message has the same id as the Script copied, in ${home.resolve("scripts/copied")}. Delete one of them.")
        home.resolve("scripts/copied").toFile().deleteRecursively()

        val agents = home.resolve("agents")
        agents.resolve("twin.md").writeText("---\nid: s-twin01\nname: twin\n---\nAn Agent with a Script's id.\n")
        try {
            refuses(diagram(scriptTask("post", "post-message")), "post",
                "Post (post): The Script post-message has the same id as the Agent twin, in ${agents.resolve("twin.md")}. Delete the id of the Agent and restart Ants to give it a new one.")
        } finally {
            agents.resolve("twin.md").deleteIfExists()
        }
    }

    @Test
    fun `later Tasks read a Script Task's exitCode, output and payload, and nothing else of it`() = withoutScripts {
        script("s-post01", "post-message")
        val bpmn = diagram(scriptTask("post", "s-post01") + agentTask("draft", inputs = """<operaton:inputParameter name="code">${'$'}{post.nothing}</operaton:inputParameter>""") +
            """<bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="post" /><bpmn:sequenceFlow id="f2" sourceRef="post" targetRef="draft" />""")
        val read = JsonMapper.shared().readValue(browser.post("/processes/outputs", mapOf("bpmn" to bpmn)).body, Map::class.java)
        assertEquals(listOf("${'$'}{post.exitCode}", "${'$'}{post.output}", "${'$'}{post.payload}"), read["post"])
        val offered = JsonMapper.shared().readValue(browser.post("/processes/inputs", mapOf("bpmn" to bpmn, "element" to "draft")).body, List::class.java)
        assertTrue(offered.containsAll(listOf("${'$'}{post.exitCode}", "${'$'}{post.output}", "${'$'}{post.payload}")), offered.toString())
        refuses(bpmn, "draft", "Draft (draft) reads post.nothing, which post's Result doesn't have.")
        assertEquals(emptyList(), checks(bpmn.replace("post.nothing", "post.payload.posted")).filter { it["warning"] == false })
    }

    @Test
    fun `reads of a Script Task's payload are checked against its Script's output schema, nested fields included, and offered`() = withoutScripts {
        home.resolve("scripts/s-post01").createDirectories().run {
            resolve("script.yaml").writeText(
                "id: s-post01\nname: post-message\ndescription: Posts.\ninputs: {type: object, properties: {}}\n" +
                    "output: {type: object, properties: {ts: {type: string}, meta: {type: object, properties: {channel: {type: string}}}, tags: {type: array, items: {type: string}}}}\n" +
                    "vars: {}\nsecrets: []\n")
            resolve("script.mjs").writeText("ants.output({ts: '1'})\n")
        }
        fun reading(read: String) = diagram(scriptTask("post", "s-post01") + agentTask("draft", inputs = """<operaton:inputParameter name="code">${'$'}{$read}</operaton:inputParameter>""") +
            """<bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="post" /><bpmn:sequenceFlow id="f2" sourceRef="post" targetRef="draft" />""")
        refuses(reading("post.payload.tss"), "draft", "Draft (draft) reads post.payload.tss, which post's output schema doesn't have.")
        refuses(reading("post.payload.meta.chanel"), "draft", "Draft (draft) reads post.payload.meta.chanel, which post's output schema doesn't have.")
        for (fine in listOf("post.payload", "post.payload.ts", "post.payload.meta.channel", "post.payload.tags")) {
            assertEquals(emptyList(), checks(reading(fine)).filter { it["warning"] == false }, fine)
        }
        val payload = listOf("post.payload.ts", "post.payload.meta", "post.payload.meta.channel", "post.payload.tags").map { "${'$'}{$it}" }
        assertTrue(offered(reading("post.payload"), "draft").containsAll(listOf("${'$'}{post.exitCode}", "${'$'}{post.output}") + payload))
        val read = JsonMapper.shared().readValue(browser.post("/processes/outputs", mapOf("bpmn" to reading("post.payload"))).body, Map::class.java)
        assertEquals(listOf("${'$'}{post.exitCode}", "${'$'}{post.output}", "${'$'}{post.payload}") + payload, read["post"])
    }
    @Test
    fun `a file that isn't BPMN is one Check on the whole file`() {
        val checks = checks("not xml")
        assertEquals(1, checks.size)
        assertEquals(null, checks[0]["element"])
    }
}
