package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
     * with [one]'s status and `02-two`, with [two]'s, blocked by it, and gives their paths.
     */
    private fun setUp(repo: Remote, one: String = "ready-for-agent", two: String = "ready-for-agent") {
        agentTask(
            claude,
            mapOf("plan" to """{"type":"object","properties":{"tickets":{"type":"array"}}}""", "build" to null),
            extra = sourceMapLines(repo),
            env = listOf("PATH", "HOME"),
        )
        claude.session(
            "plan", init,
            """write tickets/issues/01-one.md # One\n\nStatus: $one\n\nBlocked by: none\n""",
            """write tickets/issues/02-two.md # Two\n\nStatus: $two\n\nBlocked by: 01\n\n## Acceptance criteria\n\n- [x] Builds\n- [ ] Shows one\n""",
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
              <operaton:property name="ants.skills" value="$skill" />
              $property
            </operaton:properties>
          </bpmn:extensionElements>
          $loop
        </bpmn:serviceTask>"""

    /** The Ticket [name]'s `Status:`. */
    private fun status(run: String, name: String) =
        Regex("""Status: (\S+)""").find(workspace(run).resolve("tickets/issues/$name.md").readText())!!.groupValues[1]

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

    @Test
    fun `a ready-for-human Ticket reaches the Notification list only once its Blocked by have landed`() {
        val api = repo("api")
        setUp(api, two = "ready-for-human")
        claude.session("build", init, "wait go", commit("one"), done)
        val run = start("api")
        eventually { assertEquals(1, claude.launches("build").size) }

        assertFalse("ticket-$run-02-two" in browser.get("/notifications").body)
        val page = browser.get("/runs/$run/tickets/02-two").body
        assertContains(page, "Waits for 01")
        assertFalse("Mark done" in page)
        assertFalse("Send back" in page)
        assertContains(browser.post("/runs/$run/tickets/02-two/done", emptyMap()).body, "Waits for 01")
        assertEquals("ready-for-human", status(run, "02-two"))

        claude.release("go")
        eventually { assertEquals("in-review", status(run, "01-one")) }
        assertContains(browser.get("/notifications").body, "ticket-$run-02-two")
        assertContains(browser.get("/runs/$run/tickets/02-two").body, "Mark done")
    }

    /** A session line committing [file] in the api repo. */
    private fun commit(file: String) =
        "sh cd repos/api && echo $file > $file.txt && git add $file.txt && git -c user.name=A -c user.email=a@b -c commit.gpgsign=false commit -qm $file"

    /** Send back on `02-two`, with [comment]. */
    private fun sendBack(run: String, comment: String) = browser.post("/runs/$run/tickets/02-two/send-back", mapOf("comment" to comment))

    @Test
    fun `Send back on a ready-for-human Ticket resumes its blocker's implementer, lands the fix and keeps the Ticket held`() {
        val api = repo("api")
        setUp(api, two = "ready-for-human")
        val fixed = done.replace("\"Done.\"", "\"Fixed.\"")
        claude.session("build", 1, init, commit("one"), done)
        claude.session("build", 2, init, "wait fix", commit("fix"), fixed)
        val run = start("api")
        eventually { assertEquals("in-review", status(run, "01-one")) }

        val page = browser.get("/runs/$run/tickets/02-two").body
        assertContains(page, "Send back</button>")
        assertContains(page, ">- Shows one\n</textarea>") // the unticked criteria
        assertContains(sendBack(run, " ").body, "Send back needs a comment.")
        assertEquals(1, claude.launches("build").size)

        sendBack(run, "one.txt says one")
        assertContains(workspace(run).resolve("tickets/issues/02-two.md").readText().substringAfter("## Comments"), "one.txt says one")
        eventually { assertEquals(2, claude.launches("build").size) }
        val (first, fix) = claude.launches("build")
        assertEquals(first.flag("--session-id"), fix.flag("--resume"))
        assertContains(fix.stdin, "one.txt says one")
        assertContains(fix.stdin, "tickets/issues/02-two.md")
        assertTrue(fix.cwd.endsWith("instances/implement-1"))
        eventually { assertEquals("in-progress", status(run, "01-one")) }
        assertContains(browser.get("/notifications").body, "Sent back")

        claude.release("fix")
        eventually { assertEquals("in-review", status(run, "01-one")) }
        eventually { assertContains(browser.get("/notifications").body, "Yours to do") }
        assertEquals("fix", workspace(run).resolve("repos/api/fix.txt").readText().trim())
        assertEquals("ready-for-human", status(run, "02-two"))
        assertContains(browser.get("/runs/$run").body, "<dt>implement-2</dt><dd>Ready for human</dd>")

        browser.post("/runs/$run/tickets/02-two/done", emptyMap())
        implemented(run)
        assertEquals(2, claude.launches("build").size)
        assertEquals(listOf("Fixed.", "Done by the user."), (runtime.getVariable(run, "implement") as List<*>).map { (it as Map<*, *>)["summary"] })
    }

    @Test
    fun `a sent back fix that fails is failed on the Ticket's entry, and the Ticket stays held`() {
        val api = repo("api")
        setUp(api, two = "ready-for-human")
        claude.session("build", 1, init, commit("one"), done)
        claude.session("build", 2, init, "exit 3")
        val run = start("api")
        eventually { assertEquals("in-review", status(run, "01-one")) }

        sendBack(run, "one.txt says one")
        eventually { assertContains(browser.get("/notifications").body, "[failed]") }
        assertContains(browser.get("/runs/$run/tickets/02-two").body, "claude exited with code 3")
        assertEquals("in-review", status(run, "01-one")) // as its last landing left it, to Send back again
        assertEquals("ready-for-human", status(run, "02-two"))
        assertContains(browser.get("/runs/$run").body, "<dt>implement-2</dt><dd>Ready for human</dd>")
    }

    @Test
    fun `a ready-for-human Ticket blocked by nothing has no Send back`() {
        val api = repo("api")
        setUp(api, one = "ready-for-human")
        val run = start("api")
        eventually { assertContains(browser.get("/runs/$run").body, "<dt>implement-1</dt><dd>Ready for human</dd>") }
        val page = browser.get("/runs/$run/tickets/01-one").body
        assertContains(page, "Mark done")
        assertFalse("Send back" in page)
    }
}
