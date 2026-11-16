package ants

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MultiInstanceTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    @Autowired
    private lateinit var runs: Runs

    private fun setUp(agentTasks: Int = 3) = agentTask(
        claude,
        mapOf("plan" to """{"type":"object","properties":{"items":{"type":"array"}}}""", "draft" to """{"type":"object","properties":{"text":{}}}""", "proofread" to null),
        extra = "agent-tasks: $agentTasks\n",
        env = listOf("PATH", "HOME"),
    )

    private fun result(output: String) = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"done","summary":"Done.","output":$output}}"""

    private fun agentTask(id: String, skill: String, inputs: String = "", loop: String = "") = """
        <bpmn:serviceTask id="$id" operaton:type="external" operaton:topic="agent">
          <bpmn:extensionElements>
            <operaton:properties>
              <operaton:property name="ants.agent" value="writer" />
              <operaton:property name="ants.skills" value="$skill" />
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
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val order = listOf("a", "b", "c").map { launchOf("draft", """\"item\":\"$it\"""") }
        assertEquals(setOf(1, 2, 3), order.toSet())
        assertEquals(order.joinToString(",", "[", "]") { drafted("$it") }, variable(run, "draft"))
    }

    private fun read(id: String, tokens: Int) =
        """{"type":"assistant","message":{"id":"$id","content":[{"type":"text","text":"Reading."}],"usage":{"input_tokens":$tokens}}}"""

    private val itemInput = """<operaton:inputParameter name="item">${'$'}{item}</operaton:inputParameter>"""

    private fun row(run: String, task: String) = Regex("""<tr class="click" data-href="/runs/$run/tasks/$task[-\d]*/transcript">.*?</tr>""", RegexOption.DOT_MATCHES_ALL)
        .find(page(run))?.value.orEmpty()

    @Test
    fun `a parallel multi-instance's row sums its instances' contexts, counts them, and opens the first running one`() {
        setUp()
        claude.session("plan", result("""{"items":["a","b","c"]}"""))
        claude.session("draft", 1, read("m1", 30_000), "wait go-1", result("""{"text":"1"}"""))
        claude.session("draft", 2, read("m2", 20_000), "wait go-2", result("""{"text":"2"}"""))
        claude.session("draft", 3, "wait go-3", result("""{"text":"3"}"""))
        val run = start("summed", agentTask("draft", "draft", itemInput, loop()), "draft")

        eventually { assertEquals(3, claude.launches("draft").size) }
        eventually { assertContains(row(run, "draft"), "50k · 3 agents") }
        assertContains(row(run, "draft"), """data-href="/runs/$run/tasks/draft-1/transcript"""")
        assertContains(browser.get("/runs/$run/tasks/draft-2/transcript").body, "50k · 3 agents")

        claude.release("go-${launchOf("draft", """\"item\":\"a\"""")}")
        eventually { assertContains(row(run, "draft"), """data-href="/runs/$run/tasks/draft-2/transcript"""") }
        listOf("b", "c").forEach { claude.release("go-${launchOf("draft", """\"item\":\"$it\"""")}") }
        eventually { assertContains(page(run), """id="state">Completed<""") }
    }

    private fun now(run: String) = Regex("""<tr class="click" data-href="/runs/$run">.*?</tr>""", RegexOption.DOT_MATCHES_ALL)
        .find(browser.get("/runs").body)?.value.orEmpty()

    private fun nowLine(run: String, key: String, text: String) = """<b class="small"><a href="/runs/$run/tasks/$key/transcript">$text</a></b>"""

    @Test
    fun `each running instance of a parallel multi-instance has its own Now line, labelled and linking to its Transcript`() {
        setUp()
        claude.session("plan", result("""{"items":["a",{"name":"Beta"},{"title":"Gamma"}]}"""))
        for (n in 1..3) claude.session("draft", n, "wait go", result("""{"text":"$n"}"""))
        val run = start("now-lines", agentTask("draft", "draft", itemInput, loop()), "draft")

        eventually { assertEquals(3, claude.launches("draft").size) }
        eventually {
            val now = now(run)
            listOf("draft-1" to "draft #1 a", "draft-2" to "draft #2 Beta", "draft-3" to "draft #3 Gamma").forEach { (key, text) -> assertContains(now, nowLine(run, key, text)) }
        }
        claude.release("go")
        eventually { assertContains(page(run), """id="state">Completed<""") }
    }

    @Test
    fun `a sequential multi-instance's Now line names and links the running instance, and a single Task's links without a number`() {
        setUp()
        claude.session("plan", "wait planned", result("""{"items":["a","b"]}"""))
        for (n in 1..2) claude.session("draft", n, "wait go-$n", result("""{"text":"$n"}"""))
        val run = start("now-sequential", agentTask("draft", "draft", itemInput, loop(sequential = true)), "draft")

        eventually { assertContains(now(run), nowLine(run, "plan", "plan")) }
        claude.release("planned")
        eventually { assertContains(now(run), nowLine(run, "draft-1", "draft #1 a")) }
        claude.release("go-1")
        eventually { assertContains(now(run), nowLine(run, "draft-2", "draft #2 b")) }
        assertFalse("draft-1/transcript" in now(run))
        claude.release("go-2")
        eventually { assertContains(page(run), """id="state">Completed<""") }
    }

    @Test
    fun `a multi-instance Task in a multi-instance sub-process has Now lines numbered by both instances`() {
        setUp(agentTasks = 4)
        claude.session("plan", result("""{"items":["a","b"]}"""))
        claude.session("draft", "wait go", result("""{"text":"same"}"""))
        val subProcess = """
            <bpmn:subProcess id="fanout">
              ${loop()}
              <bpmn:startEvent id="inner-start" />
              <bpmn:sequenceFlow id="s1" sourceRef="inner-start" targetRef="draft" />
              ${agentTask("draft", "draft", loop = loop())}
              <bpmn:sequenceFlow id="s2" sourceRef="draft" targetRef="inner-end" />
              <bpmn:endEvent id="inner-end" />
            </bpmn:subProcess>"""
        val run = start("now-nested", subProcess, "fanout")

        eventually { assertEquals(4, claude.launches("draft").size) }
        eventually {
            val now = now(run)
            listOf("1-1" to "#1.1 a", "1-2" to "#1.2 b", "2-1" to "#2.1 a", "2-2" to "#2.2 b").forEach { (n, label) -> assertContains(now, nowLine(run, "draft-$n", "draft $label")) }
        }
        claude.release("go")
        eventually { assertContains(page(run), """id="state">Completed<""") }
    }

    private fun bar(html: String) = Regex("""<nav class="instances".*?</nav>""", RegexOption.DOT_MATCHES_ALL).find(html)?.value

    private fun chipsOn(html: String) = bar(html)?.substringAfter(">")?.substringBeforeLast("</nav>")?.trim()

    private fun chip(run: String, key: String, label: String, pill: String, context: String, current: Boolean = false) =
        """<a href="/runs/$run/tasks/$key/transcript"${if (current) """ aria-current="page"""" else ""}><b>$label</b> $pill <span class="muted num">$context</span></a>"""

    private fun chips(run: String, instances: List<Pair<String, String>>, pill: String, context: String, shown: String) =
        instances.joinToString("") { (key, label) -> chip(run, "draft-$key", label, pill, context, current = key == shown) }

    private fun numbered(labels: List<String>) = labels.mapIndexed { i, label -> "${i + 1}" to label }

    private val running = """<span class="pill p-running">Running</span>"""
    private val done = """<span class="pill p-done">Done</span>"""

    @Test
    fun `each instance's Transcript page of a parallel multi-instance has a bar with a chip per instance, its own marked, its pills changing live`() {
        setUp()
        claude.session("plan", result("""{"items":["a",{"name":"Beta"},{"title":"Gamma"}]}"""))
        claude.session("draft", read("m", 1_000), "wait go", result("""{"text":"same"}"""))
        val run = start("bar", agentTask("draft", "draft", itemInput, loop()), "draft")

        eventually { assertEquals(3, claude.launches("draft").size) }
        val labels = listOf("#1 a", "#2 Beta", "#3 Gamma")
        eventually {
            for (n in 1..3) assertEquals(
                chips(run, numbered(labels), running, "1k tokens", shown = "$n"),
                chipsOn(browser.get("/runs/$run/tasks/draft-$n/transcript").body),
            )
        }
        val head = browser.events("transcript=$run/draft-2&from=0", "taskhead")
        claude.release("go")
        val html = head.until { bar(it)?.split("p-done")?.size == 4 }
        assertContains(html, chip(run, "draft-2", "#2 Beta", done, "1k tokens", current = true))
        eventually { assertContains(page(run), """id="state">Completed<""") }
    }

    @Test
    fun `Send and Stop on an instance's Transcript page reach that instance's session only, and Send to an ended one is refused`() {
        setUp()
        val init = """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}"""
        claude.session("plan", result("""{"items":["a","b","c"]}"""))
        for (n in 1..3) claude.session("draft", n, init, "wait sent-$n", "read", result("""{"text":"$n"}"""))
        val run = start("send-each", agentTask("draft", "draft", itemInput, loop()), "draft")
        val transcript = { key: String -> "/runs/$run/tasks/$key/transcript" }

        eventually { assertEquals(3, claude.launches("draft").size) }
        val (a, b, c) = listOf("a", "b", "c").map { launchOf("draft", """\"item\":\"$it\"""") }
        for (key in listOf("draft-1", "draft-2", "draft-3")) eventually { assertContains(browser.get(transcript(key)).body, """data-pending="Sending…">Send</button>""") }
        assertEquals(transcript("draft-1"), browser.post("${transcript("draft-1")}/send", mapOf("message" to "Only for a.")).path)
        assertEquals(transcript("draft-2"), browser.post("${transcript("draft-2")}/send", mapOf("message" to "Only for b.")).path)
        assertEquals(transcript("draft-3"), browser.post("${transcript("draft-3")}/stop", emptyMap()).path)
        listOf(a, b, c).forEach { claude.release("sent-$it") }

        eventually { assertEquals(3, claude.launches("draft").count { it.stdin.lines().size == 2 }) }
        val received = claude.launches("draft").map { it.stdin.lines()[1] }
        assertContains(received[a - 1], "Only for a.")
        assertContains(received[b - 1], "Only for b.")
        assertContains(received[c - 1], """"request":{"subtype":"interrupt"}}""")
        eventually { assertContains(browser.get(transcript("draft-1")).body, "Result: done") }
        val refused = browser.post("${transcript("draft-1")}/send", mapOf("message" to "One more thing."))
        assertContains(refused.body, "Not sent: the Agent's session has ended")
        assertEquals(0, claude.launches("draft").count { "One more thing." in it.stdin })
    }

    @Test
    fun `a sequential multi-instance's row and head sum its instances so far, labelled by number and item`() {
        setUp()
        val long = "x".repeat(60)
        claude.session("plan", result("""{"items":["a",{"name":"Beta"},{"title":"Gamma"},{"size":1},"$long"]}"""))
        claude.session("draft", read("m", 1_000), "wait go", result("""{"text":"same"}"""))
        val ticket = """<operaton:inputParameter name="ticket">${'$'}{ticket}</operaton:inputParameter>"""
        val run = start("labelled", agentTask("draft", "draft", ticket, loop(sequential = true, element = """operaton:elementVariable="ticket"""")), "draft")

        eventually { assertEquals(1, claude.launches("draft").size) }
        eventually { assertContains(row(run, "draft"), "1k · 1 agent<") }
        val head = browser.events("transcript=$run/draft-1&from=0", "taskhead")
        claude.release("go")
        assertContains(bar(head.until { "#5" in bar(it).orEmpty() }).orEmpty(), chip(run, "draft-1", "#1 a", done, "1k tokens", current = true))
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertContains(row(run, "draft"), "5k · 5 agents")
        assertContains(row(run, "draft"), """data-href="/runs/$run/tasks/draft-1/transcript"""")
        assertContains(browser.get("/runs/$run/tasks/draft-4/transcript").body, """<span id="tokens">5k · 5 agents</span>""")
        val labels = listOf("#1 a", "#2 Beta", "#3 Gamma", "#4", "#5 ${"x".repeat(39)}…")
        assertEquals(labels, runs.tasks(run).single { it.key == "draft" }.instances.map { it.label })
        assertEquals(
            chips(run, numbered(labels), done, "1k tokens", shown = "4"),
            chipsOn(browser.get("/runs/$run/tasks/draft-4/transcript").body),
        )
    }

    @Test
    fun `a multi-instance Task in a multi-instance sub-process sums all its instances, labelled by both numbers`() {
        setUp()
        claude.session("plan", result("""{"items":["a","b"]}"""))
        claude.session("draft", read("m", 2_000), result("""{"text":"same"}"""))
        val subProcess = """
            <bpmn:subProcess id="fanout">
              ${loop()}
              <bpmn:startEvent id="inner-start" />
              <bpmn:sequenceFlow id="s1" sourceRef="inner-start" targetRef="draft" />
              ${agentTask("draft", "draft", loop = loop())}
              <bpmn:sequenceFlow id="s2" sourceRef="draft" targetRef="inner-end" />
              <bpmn:endEvent id="inner-end" />
            </bpmn:subProcess>"""
        val run = start("nested-sum", subProcess, "fanout")

        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertContains(row(run, "draft"), "8k · 4 agents")
        assertContains(browser.get("/runs/$run/tasks/draft-2-1/transcript").body, """<span id="tokens">8k · 4 agents</span>""")
        assertEquals(listOf("#1.1 a", "#1.2 b", "#2.1 a", "#2.2 b"), runs.tasks(run).single { it.key == "draft" }.instances.map { it.label })
        assertEquals(
            chips(run, listOf("1-1" to "#1.1 a", "1-2" to "#1.2 b", "2-1" to "#2.1 a", "2-2" to "#2.2 b"), done, "2k tokens", shown = "2-1"),
            chipsOn(browser.get("/runs/$run/tasks/draft-2-1/transcript").body),
        )
    }

    @Test
    fun `a single-instance Task beside a multi-instance keeps its own context and window share`() {
        setUp()
        claude.session(
            "plan", read("m", 62_000),
            """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","modelUsage":{"m":{"contextWindow":1000000}},""" +
                """"structured_output":{"outcome":"done","summary":"Done.","output":{"items":["a"]}}}""",
        )
        claude.session("draft", result("""{"text":"1"}"""))
        val run = start("single", agentTask("draft", "draft", loop = loop()), "draft")

        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertContains(row(run, "plan"), "62k (6.2%)")
        assertContains(row(run, "plan"), """data-href="/runs/$run/tasks/plan/transcript"""")
        assertContains(browser.get("/runs/$run/tasks/plan/transcript").body, """<span id="tokens">62k tokens (6.2%)</span>""")
        assertContains(row(run, "draft"), "0 · 1 agent<")
        assertNull(bar(browser.get("/runs/$run/tasks/plan/transcript").body))
    }

    private fun reportLine(run: String, key: String, text: String, context: String) =
        """<li><a rel="nofollow" href="/runs/$run/tasks/$key/transcript">$text</a> · done: Done. · $context</li>"""

    @Test
    fun `the Report lists a multi-instance Task as one line with its summed figure, its instances nested under it`() {
        setUp()
        claude.session("plan", read("p", 700), result("""{"items":["a",{"name":"Beta"},{"size":1}]}"""))
        claude.session("draft", read("m", 2_000), result("""{"text":"same"}"""))
        val run = start("report", agentTask("draft", "draft", itemInput, loop()), "draft")

        eventually { assertContains(page(run), """id="state">Completed<""") }
        val report = browser.report(run)
        assertContains(report, reportLine(run, "plan", "plan", "700 tokens"))
        assertContains(
            report,
            "<li>draft · 6k · 3 agents\n<ul>\n" +
                reportLine(run, "draft-1", "#1 a", "2k tokens") + "\n" +
                reportLine(run, "draft-2", "#2 Beta", "2k tokens") + "\n" +
                reportLine(run, "draft-3", "#3", "2k tokens") + "\n</ul>\n</li>",
        )
    }

    @Test
    fun `the Report keeps a single-instance Task whose id ends in a number on its own line`() {
        setUp()
        claude.session("plan", result("""{"items":[]}"""))
        claude.session("draft", read("m", 2_000), result("""{"text":"1"}"""))
        val run = start("report-numbered", agentTask("review-2", "draft"), "review-2")

        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertContains(browser.report(run), reportLine(run, "review-2", "review-2", "2k tokens"))
    }

    @Test
    fun `the Report nests a multi-instance sub-process's Task instances under one line, labelled by both numbers`() {
        setUp()
        claude.session("plan", result("""{"items":["a","b"]}"""))
        claude.session("draft", read("m", 2_000), result("""{"text":"same"}"""))
        val subProcess = """
            <bpmn:subProcess id="fanout">
              ${loop()}
              <bpmn:startEvent id="inner-start" />
              <bpmn:sequenceFlow id="s1" sourceRef="inner-start" targetRef="draft" />
              ${agentTask("draft", "draft", loop = loop())}
              <bpmn:sequenceFlow id="s2" sourceRef="draft" targetRef="inner-end" />
              <bpmn:endEvent id="inner-end" />
            </bpmn:subProcess>"""
        val run = start("report-nested", subProcess, "fanout")

        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertContains(
            browser.report(run),
            "<li>draft · 8k · 4 agents\n<ul>\n" +
                listOf("1-1" to "#1.1 a", "1-2" to "#1.2 b", "2-1" to "#2.1 a", "2-2" to "#2.2 b")
                    .joinToString("") { (n, label) -> reportLine(run, "draft-$n", label, "2k tokens") + "\n" } + "</ul>\n</li>",
        )
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
            // The Tasks table's live line follows the running instance, not the first, which is done.
            if (n == 2) eventually { assertContains(page(run), """class="shimmer"""") }
            claude.release("go-$n")
        }
        eventually { assertContains(page(run), """id="state">Completed<""") }
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
        eventually { assertContains(page(run), """id="state">Completed<""") }
        val first = launchOf("draft", """\"item\":\"a\"""") // which launch played which session
        assertEquals(listOf("1", "2"), claude.launches("proofread").map { Regex("""\\"text\\":\\"(\d)\\"""").find(it.stdin)!!.groupValues[1] })
        val reviewed = """{"outcome":"done","summary":"Done.","output":{"text":"ok"}}"""
        assertEquals(
            listOf(first, 3 - first).joinToString(",", "[", "]") { """{"draft":${drafted("$it")},"review":$reviewed}""" },
            variable(run, "fanout"),
        )
    }

    @Test
    fun `a multi-instance Task in a multi-instance sub-process keeps each instance's Transcript apart`() {
        setUp()
        claude.session("plan", result("""{"items":["a","b"]}"""))
        claude.session("draft", result("""{"text":"same"}"""))
        val subProcess = """
            <bpmn:subProcess id="fanout">
              ${loop()}
              <bpmn:startEvent id="inner-start" />
              <bpmn:sequenceFlow id="s1" sourceRef="inner-start" targetRef="draft" />
              ${agentTask("draft", "draft", loop = loop())}
              <bpmn:sequenceFlow id="s2" sourceRef="draft" targetRef="inner-end" />
              <bpmn:endEvent id="inner-end" />
            </bpmn:subProcess>"""
        val run = start("nested", subProcess, "fanout")

        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertEquals(4, claude.launches("draft").size)
        // Outer instance first, then the Task's own: draft-2-1 is the first draft of the second sub-process.
        assertEquals(setOf("plan", "draft-1-1", "draft-1-2", "draft-2-1", "draft-2-2"), workspace(run).resolve("tasks").listDirectoryEntries().map { it.name }.toSet())
        val drafts = List(2) { drafted("same") }.joinToString(",", "[", "]")
        assertEquals("""[{"draft":$drafts},{"draft":$drafts}]""", variable(run, "fanout"))
    }

    @Test
    fun `a multi-instance over an empty list gives an empty list`() {
        setUp()
        claude.session("plan", result("""{"items":[]}"""))
        val run = start("none", agentTask("draft", "draft", loop = loop()), "draft")

        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertEquals("[]", variable(run, "draft"))
        assertEquals(0, claude.launches("draft").size)
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
        eventually { assertContains(page(run), """id="state">Completed<""") }
        assertEquals(List(n) { drafted("same") }.joinToString(",", "[", "]"), variable(run, "draft"))
    }
}
