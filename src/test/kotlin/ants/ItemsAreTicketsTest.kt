package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A multi-instance with Items are Tickets ticked honours its Tickets' Blocked by, and moves them to `in-review`. */
class ItemsAreTicketsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    @Autowired
    lateinit var runtime: RuntimeService

    private val init = """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}"""
    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"done","summary":"Done.","output":{}}}"""

    /**
     * The Agent `writer` with `plan` and `build`, and a Source map of [repo]. `plan` writes two Tickets, `01-one`
     * with [one]'s status and `02-two` blocked by it, and gives their paths.
     */
    private fun setUp(repo: Remote, one: String = "ready-for-agent") {
        agentTask(
            claude,
            mapOf("plan" to """{"type":"object","properties":{"tickets":{"type":"array"}}}""", "build" to null),
            extra = sourceMapLines(repo),
            env = listOf("PATH", "HOME"),
        )
        claude.session(
            "plan", init,
            """write tickets/issues/01-one.md # One\n\nStatus: $one\n\nBlocked by: none\n""",
            """write tickets/issues/02-two.md # Two\n\nStatus: ready-for-agent\n\nBlocked by: 01\n""",
            """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":""" +
                """{"outcome":"done","summary":"Done.","output":{"tickets":["tickets/issues/01-one.md","tickets/issues/02-two.md"]}}}""",
        )
    }

    /** Starts a Run that clones [repo], plans, implements each Ticket in a parallel multi-instance, then waits. */
    private fun start(repo: String): String {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                              id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="tickets" isExecutable="true" operaton:historyTimeToLive="180">
                <bpmn:startEvent id="start">
                  <bpmn:extensionElements>
                    <operaton:formData>
                      <operaton:formField id="repos" label="Repos" type="string">
                        <operaton:properties><operaton:property id="ants.type" value="repos" /></operaton:properties>
                      </operaton:formField>
                    </operaton:formData>
                  </bpmn:extensionElements>
                </bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="clone" />
                <bpmn:serviceTask id="clone" operaton:type="external" operaton:topic="clone-repos">
                  <bpmn:extensionElements>
                    <operaton:inputOutput><operaton:inputParameter name="repos">${'$'}{repos}</operaton:inputParameter></operaton:inputOutput>
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="f2" sourceRef="clone" targetRef="plan" />
                ${agentTask("plan", "plan")}
                <bpmn:sequenceFlow id="f3" sourceRef="plan" targetRef="implement" />
                ${agentTask("implement", "build", """<operaton:property name="ants.items-are-tickets" value="true" />""",
                    """<bpmn:multiInstanceLoopCharacteristics isSequential="false" operaton:collection="${'$'}{plan.output.tickets}" operaton:elementVariable="ticket" />""")}
                <bpmn:sequenceFlow id="f4" sourceRef="implement" targetRef="wait" />
                <bpmn:userTask id="wait" name="Wait" />
                <bpmn:sequenceFlow id="f5" sourceRef="wait" targetRef="end" />
                <bpmn:endEvent id="end" />
              </bpmn:process>
            </bpmn:definitions>"""
        browser.get("/processes")
        browser.upload("/processes", "file", "tickets.bpmn", xml.toByteArray())
        browser.get("/processes/tickets")
        return browser.post("/processes/tickets/runs", listOf("repos" to repo)).path.removePrefix("/runs/")
    }

    private fun agentTask(id: String, skill: String, property: String = "", loop: String = "") = """
        <bpmn:serviceTask id="$id" operaton:type="external" operaton:topic="agent">
          <bpmn:extensionElements>
            <operaton:properties>
              <operaton:property name="ants.agent" value="writer" />
              <operaton:property name="ants.skill" value="$skill" />
              $property
            </operaton:properties>
          </bpmn:extensionElements>
          $loop
        </bpmn:serviceTask>"""

    /** The Ticket [name]'s `Status:`. */
    private fun status(run: String, name: String) =
        Regex("""Status: (\S+)""").find(home.runWorkspace(run).resolve("tickets/issues/$name.md").readText())!!.groupValues[1]

    /** Waits until every instance of `implement` has ended and the Run waits after it. */
    private fun implemented(run: String) = eventually { assertNotNull(runtime.getVariable(run, "implement"), browser.get("/runs/$run").body) }

    @Test
    fun `a Ticket blocked by another launches only after its blocker lands, and landing sets each in-review`() {
        val api = repo("api")
        setUp(api)
        claude.session("build", 1, init, "wait go", "sh cd repos/api && echo one > one.txt && git add one.txt && git -c user.name=A -c user.email=a@b -c commit.gpgsign=false commit -qm one", done)
        claude.session("build", 2, init, """sh ls repos/api > "${'$'}launch/seen"""", done)
        val run = start("api")

        eventually { assertContains(browser.get("/runs/$run").body, "<dt>implement-2</dt><dd>Blocked by 01</dd>") }
        eventually { assertEquals(1, claude.launches("build").size) }
        assertEquals("ready-for-agent", status(run, "01-one"))

        claude.release("go")
        implemented(run)
        assertEquals(2, claude.launches("build").size)
        assertEquals("one.txt", claude.dir.resolve("launches/build.2/seen").readText().trim())
        assertEquals("in-review", status(run, "01-one"))
        assertEquals("in-review", status(run, "02-two"))
    }

    @Test
    fun `a ready-for-human Ticket is not implemented, and its dependents launch once the user marks it done`() {
        val api = repo("api")
        setUp(api, one = "ready-for-human")
        claude.session("build", init, done)
        val run = start("api")

        eventually {
            val page = browser.get("/runs/$run").body
            assertContains(page, "<dt>implement-1</dt><dd>Ready for human</dd>")
            assertContains(page, "<dt>implement-2</dt><dd>Blocked by 01</dd>")
        }
        assertEquals(0, claude.launches("build").size)

        browser.post("/runs/$run/tickets/01-one/done", emptyMap())
        implemented(run)
        assertEquals(1, claude.launches("build").size)
        assertEquals("done", status(run, "01-one"))
        assertEquals("in-review", status(run, "02-two"))
    }
}
