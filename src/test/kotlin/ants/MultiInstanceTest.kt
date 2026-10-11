package ants

import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MultiInstanceTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    private fun setUp(agentTasks: Int = 3) {
        home.resolve("settings.yaml").writeText("agent-tasks: $agentTasks\nclaude:\n  command: [${claude.command}]\n  env: [PATH, HOME]\n")
        home.resolve("agents").createDirectories().resolve("writer.md").writeText("Write plainly.\n")
        for (name in listOf("plan", "draft", "proofread")) {
            home.resolve("skills/$name").createDirectories().resolve("SKILL.md").writeText("---\nname: $name\ndescription: $name.\n---\nDo it.\n")
        }
    }

    private fun result(output: String) = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"done","summary":"Done.","output":$output}}"""

    private fun agentTask(id: String, skill: String, inputs: String = "", loop: String = "") = """
        <bpmn:serviceTask id="$id" operaton:type="external" operaton:topic="agent">
          <bpmn:extensionElements>
            <operaton:properties>
              <operaton:property name="ants.agent" value="writer" />
              <operaton:property name="ants.skill" value="$skill" />
            </operaton:properties>
            ${if (inputs.isEmpty()) "" else "<operaton:inputOutput>$inputs</operaton:inputOutput>"}
          </bpmn:extensionElements>
          $loop
        </bpmn:serviceTask>"""

    private fun loop(sequential: Boolean = false, element: String = "") =
        """<bpmn:multiInstanceLoopCharacteristics isSequential="$sequential" operaton:collection="${'$'}{plan.output.items}" $element/>"""

    /** Deploys Process [key]: the `plan` Task, then [fanOut], whose id is `fanout` or a Task's, then the end. */
    private fun start(key: String, fanOut: String, target: String): String {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                              id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="$key" isExecutable="true" operaton:historyTimeToLive="180">
                <bpmn:startEvent id="start" />
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="plan" />
                ${agentTask("plan", "plan")}
                <bpmn:sequenceFlow id="f2" sourceRef="plan" targetRef="$target" />
                $fanOut
                <bpmn:sequenceFlow id="f3" sourceRef="$target" targetRef="end" />
                <bpmn:endEvent id="end" />
              </bpmn:process>
            </bpmn:definitions>"""
        browser.get("/processes")
        browser.upload("/processes", "file", "$key.bpmn", xml.toByteArray())
        browser.get("/processes/$key")
        return browser.post("/processes/$key/runs", emptyMap()).path.removePrefix("/runs/")
    }

    private fun page(run: String) = browser.get("/runs/$run").body.replace("&#34;", "\"")

    /** The value of variable [name] on the Run page of [run]. */
    private fun variable(run: String, name: String) = Regex("<dt>$name</dt><dd><code>(.*?)</code></dd>").find(page(run))?.groupValues?.get(1)

    private fun drafted(text: String) = """{"outcome":"done","summary":"Done.","output":{"text":"$text"}}"""

    /** The launch of [skill] whose inputs hold [input]. */
    private fun launchOf(skill: String, input: String) = claude.launches(skill).indexOfFirst { input in it.stdin } + 1

    @Test
    fun `a parallel multi-instance over a three-item list runs three instances and gives their three Results in instance order`() {
        setUp()
        claude.session("plan", result("""{"items":["a","b","c"]}"""))
        for (n in 1..3) claude.session("draft", n, "wait go", result("""{"text":"$n"}"""))
        val run = start("fan-out", agentTask("draft", "draft", """<operaton:inputParameter name="item">${'$'}{item}</operaton:inputParameter>""", loop()), "draft")

        eventually { assertEquals(3, claude.launches("draft").size) } // all at once
        claude.release("go")
        eventually { assertContains(page(run), "State: Completed") }
        val order = listOf("a", "b", "c").map { launchOf("draft", """\"item\":\"$it\"""") }
        assertEquals(setOf(1, 2, 3), order.toSet())
        assertEquals(order.joinToString(",", "[", "]") { drafted("$it") }, variable(run, "draft"))
    }

    @Test
    fun `a sequential multi-instance runs its instances one at a time, each on its own item, and gives their Results in instance order`() {
        setUp()
        claude.session("plan", result("""{"items":["a","b","c"]}"""))
        for (n in 1..3) claude.session("draft", n, "wait go-$n", result("""{"text":"$n"}"""))
        val ticket = """<operaton:inputParameter name="ticket">${'$'}{ticket}</operaton:inputParameter>"""
        val run = start("one-by-one", agentTask("draft", "draft", ticket, loop(sequential = true, element = """operaton:elementVariable="ticket"""")), "draft")

        for (n in 1..3) {
            eventually { assertEquals(n, claude.launches("draft").size) }
            Thread.sleep(300)
            assertEquals(n, claude.launches("draft").size) // the next waits for this one
            assertContains(claude.launches("draft")[n - 1].stdin, """{\"ticket\":\"${"abc"[n - 1]}\"}""")
            claude.release("go-$n")
        }
        eventually { assertContains(page(run), "State: Completed") }
        assertEquals((1..3).joinToString(",", "[", "]") { drafted("$it") }, variable(run, "draft"))
    }

    @Test
    fun `a multi-instance sub-process gives a list of each instance's Results by Task id`() {
        setUp()
        claude.session("plan", result("""{"items":["a","b"]}"""))
        claude.session("draft", 1, result("""{"text":"1"}"""))
        claude.session("draft", 2, "wait drafted-1", result("""{"text":"2"}"""))
        claude.session("proofread", result("""{"text":"ok"}"""))
        val item = """<operaton:inputParameter name="item">${'$'}{item}</operaton:inputParameter>"""
        // Each instance's review reads its own instance's draft.
        val text = """<operaton:inputParameter name="text">${'$'}{draft.output.text}</operaton:inputParameter>"""
        val subProcess = """
            <bpmn:subProcess id="fanout">
              ${loop()}
              <bpmn:startEvent id="inner-start" />
              <bpmn:sequenceFlow id="s1" sourceRef="inner-start" targetRef="draft" />
              ${agentTask("draft", "draft", item)}
              <bpmn:sequenceFlow id="s2" sourceRef="draft" targetRef="review" />
              ${agentTask("review", "proofread", text)}
              <bpmn:sequenceFlow id="s3" sourceRef="review" targetRef="inner-end" />
              <bpmn:endEvent id="inner-end" />
            </bpmn:subProcess>"""
        val run = start("sub-process", subProcess, "fanout")

        eventually { assertEquals(1, claude.launches("proofread").size) }
        claude.release("drafted-1")
        eventually { assertContains(page(run), "State: Completed") }
        val first = launchOf("draft", """\"item\":\"a\"""") // which launch played which session
        assertEquals(listOf("1", "2"), claude.launches("proofread").map { Regex("""\\"text\\":\\"(\d)\\"""").find(it.stdin)!!.groupValues[1] })
        val reviewed = """{"outcome":"done","summary":"Done.","output":{"text":"ok"}}"""
        assertEquals(
            listOf(first, 3 - first).joinToString(",", "[", "]") { """{"draft":${drafted("$it")},"review":$reviewed}""" },
            variable(run, "fanout"),
        )
    }

    @Test
    fun `instances completing at once never lose a Result`() {
        val n = 8
        setUp(agentTasks = n)
        claude.session("plan", result((1..n).joinToString(",", """{"items":[""", "]}") { "\"$it\"" }))
        claude.session("draft", "wait go", result("""{"text":"same"}"""))
        val run = start("at-once", agentTask("draft", "draft", loop = loop()), "draft")

        eventually { assertEquals(n, claude.launches("draft").size) }
        claude.release("go")
        eventually { assertContains(page(run), "State: Completed") }
        assertEquals(List(n) { drafted("same") }.joinToString(",", "[", "]"), variable(run, "draft"))
    }
}
