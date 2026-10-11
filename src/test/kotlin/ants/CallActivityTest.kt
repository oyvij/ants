package ants

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CallActivityTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    private fun setUp() {
        home.resolve("settings.yaml").writeText("agent-tasks: 3\nclaude:\n  command: [${claude.command}]\n  env: [PATH, HOME]\n$vault")
        home.resolve("agents").createDirectories().resolve("writer.md").writeText("---\nskills: plan, draft, proofread\n---\nWrite plainly.\n")
        for (name in listOf("plan", "draft", "proofread")) {
            home.resolve("skills/$name").createDirectories().resolve("SKILL.md").writeText("---\nname: $name\ndescription: $name.\n---\nDo it.\n")
        }
        home.resolve("skills/plan/result.schema.json").writeText("""{"type":"object","properties":{"items":{"type":"array"}}}""")
        home.resolve("skills/draft/result.schema.json").writeText("""{"type":"object","properties":{"text":{}}}""")
        home.resolve("skills/proofread/result.schema.json").writeText("""{"type":"object","properties":{"text":{}}}""")
    }

    private fun result(output: String) = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"done","summary":"Done.","output":$output}}"""

    private val stuck = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"stuck","summary":"No tone given.","needs":"Which tone?"}}"""

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

    private fun input(name: String, expression: String) = """<operaton:inputParameter name="$name">${'$'}{$expression}</operaton:inputParameter>"""

    /** A call activity [id] calling [called], each `operaton:in` from [ins] (target to expression). */
    private fun call(id: String, called: String, ins: Map<String, String> = mapOf("prompt" to "prompt"), loop: String = "") = """
        <bpmn:callActivity id="$id" name="Call $called" calledElement="$called">
          <bpmn:extensionElements>
            ${ins.entries.joinToString("") { (target, source) -> """<operaton:in sourceExpression="${'$'}{$source}" target="$target" />""" }}
          </bpmn:extensionElements>
          $loop
        </bpmn:callActivity>"""

    /** Process [key] with a required start-form field `prompt` and [steps] one after another, by id. */
    private fun process(key: String, vararg steps: Pair<String, String>): String {
        val ids = listOf("start") + steps.map { it.first } + "end"
        return """<?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                              id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="$key" name="${key.replaceFirstChar(Char::uppercase)}" isExecutable="true" operaton:historyTimeToLive="180">
                <bpmn:startEvent id="start">
                  <bpmn:extensionElements><operaton:formData>
                    <operaton:formField id="prompt" label="Prompt" type="string"><operaton:validation><operaton:constraint name="required" /></operaton:validation></operaton:formField>
                  </operaton:formData></bpmn:extensionElements>
                </bpmn:startEvent>
                ${steps.joinToString("") { it.second }}
                ${ids.zipWithNext().withIndex().joinToString("") { (i, pair) -> """<bpmn:sequenceFlow id="f$i" sourceRef="${pair.first}" targetRef="${pair.second}" />""" }}
                <bpmn:endEvent id="end" />
              </bpmn:process>
            </bpmn:definitions>"""
    }

    /** Save and deploy [xml]: null once deployed, else the Checks it refused with. */
    private fun save(xml: String): String? {
        browser.get("/processes")
        val page = browser.post("/processes/deploy", mapOf("bpmn" to xml))
        return page.body.takeIf { page.status == 400 }
    }

    private fun deploy(xml: String) = assertEquals(null, save(xml))

    private fun start(key: String): String {
        browser.get("/processes/$key")
        return browser.post("/processes/$key/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    private fun page(run: String) = browser.get("/runs/$run").body.replace("&#34;", "\"")

    /** The value of variable [name] on the Run page of [run]. */
    private fun variable(run: String, name: String) = Regex("<dt>$name</dt><dd><code>(.*?)</code></dd>").find(page(run))?.groupValues?.get(1)

    private fun done(text: String) = """{"outcome":"done","summary":"Done.","output":{"text":"$text"}}"""

    /** The called Process `spec`: one Agent Task `draft` on [skill], reading its `prompt`. */
    private fun spec(skill: String = "draft") = process("spec", "draft" to agentTask("draft", skill, input("prompt", "prompt")))

    @Test
    fun `a Run with a call activity completes, with the called Process's Results keyed by Task id`() {
        setUp()
        claude.session("draft", result("""{"text":"called"}"""))
        claude.session("proofread", result("""{"text":"proofread"}"""))
        deploy(spec())
        // The caller's own Task shares the id draft with the called Process's.
        deploy(process("caller", "build" to call("build", "spec"), "draft" to agentTask("draft", "proofread", input("text", "build.draft.output.text"))))
        val run = start("caller")

        eventually { assertContains(page(run), "State: Completed") }
        assertEquals("""{"draft":${done("called")}}""", variable(run, "build"))
        assertContains(claude.launches("draft").single().stdin, """{\"prompt\":\"Say hello\"}""")
        assertContains(claude.launches("proofread").single().stdin, """{\"text\":\"called\"}""")
        // One Run workspace, the top Run's, with a Transcript of each, kept apart.
        assertEquals(listOf(home.resolve("runs/$run").toRealPath().toString()), claude.launchLog().distinct())
        assertTrue(home.resolve("runs/$run/tasks/build.draft/transcript.jsonl").exists())
        assertTrue(home.resolve("runs/$run/tasks/draft/transcript.jsonl").exists())
        assertFalse(browser.get("/runs").body.contains("Spec</a>"), "the called Process is no Run of its own")
    }

    @Test
    fun `a fix deployed to the called Process reaches the next Run of every caller`() {
        setUp()
        claude.session("draft", result("""{"text":"old"}"""))
        claude.session("proofread", result("""{"text":"fixed"}"""))
        deploy(spec())
        deploy(process("caller", "build" to call("build", "spec")))
        val before = start("caller")
        eventually { assertContains(page(before), "State: Completed") }

        deploy(spec(skill = "proofread"))
        val after = start("caller")
        eventually { assertContains(page(after), "State: Completed") }
        assertEquals("""{"draft":${done("fixed")}}""", variable(after, "build"))
    }

    @Test
    fun `a multi-instance call activity gives a list of each called Run's Results by Task id, in instance order`() {
        setUp()
        claude.session("plan", result("""{"items":["a","b"]}"""))
        claude.session("draft", 1, "wait go", result("""{"text":"1"}"""))
        claude.session("draft", 2, "wait go", result("""{"text":"2"}"""))
        deploy(spec())
        val loop = """<bpmn:multiInstanceLoopCharacteristics isSequential="false" operaton:collection="${'$'}{plan.output.items}" />"""
        deploy(process("fan", "plan" to agentTask("plan", "plan"), "build" to call("build", "spec", mapOf("prompt" to "item"), loop)))
        val run = start("fan")

        eventually { assertEquals(2, claude.launches("draft").size) }
        claude.release("go")
        eventually { assertContains(page(run), "State: Completed") }
        val first = claude.launches("draft").indexOfFirst { """{\"prompt\":\"a\"}""" in it.stdin } + 1
        assertEquals(listOf(first, 3 - first).joinToString(",", "[", "]") { """{"draft":${done("$it")}}""" }, variable(run, "build"))
        assertTrue(home.resolve("runs/$run/tasks/build-1.draft/transcript.jsonl").exists())
        assertTrue(home.resolve("runs/$run/tasks/build-2.draft/transcript.jsonl").exists())
    }

    @Test
    fun `a stuck Task in a called Process is one Notification entry, named after the top Run, and Continue goes on`() {
        setUp()
        claude.session("draft", 1, stuck)
        claude.session("draft", result("""{"text":"warm"}"""))
        deploy(spec())
        deploy(process("caller", "build" to call("build", "spec")))
        val run = start("caller")

        val rows = { Regex("""<tr>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL).findAll(browser.get("/notifications").body).toList() }
        eventually { assertEquals(1, rows().size) }
        assertContains(rows().single().value, "<td>Caller</td>")
        val entry = Regex("""href="(/notifications/[^"]+)"""").find(rows().single().value)!!.groupValues[1]
        assertContains(browser.get(entry).body, """href="/runs/$run/tasks/build.draft/transcript"""")
        browser.post(entry, mapOf("action" to "continue", "instruction" to "Warm."))
        eventually { assertContains(page(run), "State: Completed") }
        assertEquals("""{"draft":${done("warm")}}""", variable(run, "build"))
    }

    @Test
    fun `clicking a call activity shows the called Process's live diagram, whose Task drawer is the called Task's`() {
        setUp()
        claude.session("draft", "wait go", result("""{"text":"called"}"""))
        deploy(spec())
        deploy(process("caller", "build" to call("build", "spec")))
        val run = start("caller")

        // The breadcrumb leads back to the Run's own diagram, which the page holds; the called one is by its instance.
        val called = browser.events("/runs/$run/live").until { (it["called"] as Map<*, *>)["build"] != null }.let { (it["called"] as Map<*, *>)["build"] }
        assertContains(browser.get("/runs/$called/diagram.bpmn").body, """id="spec"""")
        browser.events("/runs/$called/live").until { ((it["elements"] as Map<*, *>)["draft"] as? Map<*, *>)?.get("state") == "running" }
        val drawer = browser.events("/runs/$called/tasks/draft/drawer")
        assertContains(assertNotNull(drawer.poll(10, SECONDS)), """href="/runs/$run/tasks/build.draft/transcript"""")
        claude.release("go")
        eventually { assertContains(page(run), "State: Completed") }
    }

    @Test
    fun `a call cycle is refused, a Process calling itself too`() {
        setUp()
        deploy(process("inner", "draft" to agentTask("draft", "draft", input("prompt", "prompt"))))
        deploy(process("outer", "build" to call("build", "inner")))
        assertEquals("Call outer (back) makes a call cycle: inner → outer → inner.", save(process("inner", "back" to call("back", "outer"))))
        assertEquals("Call loop (again) makes a call cycle: loop → loop.", save(process("loop", "again" to call("again", "loop"))))
    }

    @Test
    fun `a required called field left empty is refused, and a Process that isn't deployed can't be called`() {
        setUp()
        deploy(spec())
        assertEquals("Call spec (build) leaves Prompt (prompt), which spec requires, empty.", save(process("caller", "build" to call("build", "spec", emptyMap()))))
        assertEquals("Call nowhere (build) calls nowhere, which isn't deployed.", save(process("caller", "build" to call("build", "nowhere"))))
    }

    @Test
    fun `a Task a caller reads can't be renamed or removed, since saving a called Process runs its callers' Checks`() {
        setUp()
        deploy(spec())
        val reads = agentTask("review", "proofread", input("text", "build.draft.output.text"))
        assertEquals(
            "review reads build.drafted, which is no Task in spec.",
            save(process("caller", "build" to call("build", "spec"), "review" to agentTask("review", "proofread", input("text", "build.drafted.output.text")))),
        )
        deploy(process("caller", "build" to call("build", "spec"), "review" to reads))

        val renamed = process("spec", "write" to agentTask("write", "draft", input("prompt", "prompt")))
        assertContains(assertNotNull(save(renamed)), "Caller (caller), which calls this Process: review reads build.draft, which is no Task in spec.")
        // A new required field the caller doesn't fill is the caller's Check too.
        val asks = spec().replace("""<operaton:formField id="prompt"""", """<operaton:formField id="tone" label="Tone" type="string"><operaton:validation><operaton:constraint name="required" /></operaton:validation></operaton:formField><operaton:formField id="prompt"""")
        // Other tests' callers of spec are its callers too.
        assertContains(assertNotNull(save(asks)), "Caller (caller), which calls this Process: Call spec (build) leaves Tone (tone), which spec requires, empty.")
    }

    @Test
    fun `the call activity panel lists each deployed Process with its start-form fields`() {
        setUp()
        deploy(spec())
        val processes = JsonMapper.shared().readValue(browser.get("/processes.json").body, List::class.java)
        assertContains(processes, mapOf("key" to "spec", "name" to "Spec", "fields" to listOf(mapOf("name" to "prompt", "label" to "Prompt", "required" to true))))
    }

    /** Waits for the live diagram update in which [check] passes. */
    private fun BlockingQueue<String>.until(check: (Map<*, *>) -> Boolean): Map<*, *> {
        while (true) {
            val update = JsonMapper.shared().readValue(assertNotNull(poll(10, SECONDS), "no matching update within 10 seconds"), Map::class.java)
            if (check(update)) return update
        }
    }
}
