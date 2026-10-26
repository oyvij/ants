package ants

import com.microsoft.playwright.Browser.NewContextOptions
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.KeyboardModifier
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import java.util.regex.Pattern
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What only the pages' scripts do, in a real browser: Chromium through Playwright, on the running test Ants, logged in.
 * CI installs Chromium (ci.yml); where it can't launch, such as in nono's sandbox, these tests are skipped
 * ([launchChromium]), never passed. A new test is a method that is
 * `browse { page.navigate(url("/…")); … }`, and [screenshot] saves what the page looks like to build/reports/browser.
 */
@Tag("browser")
class BrowserTest : AntsTest() {
    private val http by lazy { browser().also { it.login(); it.get("/processes") } }
    private lateinit var playwright: Playwright
    private lateinit var page: Page
    private lateinit var test: String
    private val errors = mutableListOf<String>()

    @BeforeEach
    fun launch(info: TestInfo) {
        test = info.testMethod.get().name
        playwright = Playwright.create()
        page = launchChromium(playwright).newPage().also(::watch).also(::logIn)
    }

    private fun logIn(page: Page) {
        page.navigate(url("/login"))
        page.fill("input[name=username]", "admin")
        page.fill("input[name=password]", "admin")
        page.click("button[type=submit]")
        page.waitForURL { !it.contains("/login") }
    }

    /** A second tab, logged in of its own. */
    private fun otherTab(): Page = page.context().browser().newPage().also(::watch).also(::logIn)

    @AfterEach
    fun close() {
        if (::playwright.isInitialized) playwright.close()
    }

    private fun watch(page: Page) {
        page.onConsoleMessage { if (it.type() == "error") errors += "console: ${it.text()}" }
        page.onPageError { errors += "page error: $it" }
    }

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    /**
     * Runs [steps]; a failure says where the page was, its console errors, and where its screenshot is (CI keeps
     * build/reports).
     */
    private fun browse(steps: () -> Unit) = try {
        steps()
    } catch (e: Throwable) {
        val shot = runCatching { page.screenshot("failed/$test") }.getOrNull()
        throw AssertionError("${e.message}\nAt ${page.url()}, screenshot $shot. The page's console errors:\n" + errors.joinToString("\n").ifEmpty { "(none)" }, e)
    }

    /** An element's shape on the diagram, by its id. */
    private fun shape(id: String) = page.locator("#canvas .djs-element[data-element-id='$id'], #diagram .djs-element[data-element-id='$id']")

    private val pad get() = page.locator(".djs-context-pad")
    private val heading get() = page.locator("#explain .kind")
    private val panel get() = page.locator("#explain")
    private fun button(text: String) = page.locator("#explain button", Page.LocatorOptions().setHasText(text))
    private fun label(text: String) = page.getByLabel(text, Page.GetByLabelOptions().setExact(true))
    private val selected = Pattern.compile("\\bselected\\b")

    /** The Draft marker beside the panel's picker [label], as the lists show it. */
    private fun mark(text: String) = label(text).locator("xpath=following-sibling::span[contains(@class, 'draft-mark')]")

    /** **New Process** from the list's modal: the modeler on a Draft-only Process named [name]. */
    private fun newProcess(name: String) {
        page.navigate(url("/processes"))
        page.click(".ph button.primary")
        page.getByLabel("Name").fill(name)
        page.click("#new-process button.primary")
        page.waitForURL(Pattern.compile(".*/processes/[^/]+/edit$"))
    }

    /** Opens the modeler on a blank Process and appends an Agent Task to its start event; the Task's id. */
    private fun appendAgentTask(): String {
        newProcess("Drawn here")
        shape("start").click()
        pad.locator("[data-action='append.agent-task']").click()
        assertThat(heading).hasText("Agent Task")
        return page.locator("#canvas .djs-shape.selected").getAttribute("data-element-id")!!
    }

    @Test
    fun `the hover menu appends an Agent, User or Built-in Task and offers nothing Ants doesn't run`() = browse {
        newProcess("Hover menu")
        for ((action, name) in listOf("append.agent-task" to "Agent Task", "append.user-task" to "User Task", "append.built-in-task" to "Built-in Task")) {
            shape("start").click()
            assertThat(heading).hasText("Start event")
            assertThat(pad.locator("[data-action='$action']")).isVisible()
            for (gone in listOf("append.append-task", "append.intermediate-event", "append")) assertThat(pad.locator("[data-action='$gone']")).hasCount(0)
            pad.locator("[data-action='$action']").click()
            assertThat(heading).hasText(name)
        }
        page.screenshot("modeler") // the modeler with the three Tasks drawn
    }

    @Test
    fun `the modeler knows no Schedule, so a start event's panel has no Schedule nor its hint, and the wrench menu offers no timer start`() = browse {
        newProcess("No schedule")
        shape("start").click()
        assertThat(heading).hasText("Start event")
        button("＋ Add field").click()
        assertThat(panel).containsText("Stored value")
        assertThat(panel).not().containsText("Schedule")
        assertThat(page.locator("#explain [title*='Schedule']")).hasCount(0)
        pad.locator("[data-action='replace']").click()
        assertThat(page.locator(".djs-popup")).isVisible()
        assertThat(page.locator(".djs-popup [data-id='replace-with-timer-start']")).hasCount(0)
    }

    @Test
    fun `clicking a Check selects its element, inside a collapsed sub-process too`() = browse {
        agentTask(null)
        val outer = agentTaskXml("outer", "Outer")
        val inner = agentTaskXml("inner", "Inner")
        val (inside, groupPlane) = row("group", """<bpmn:startEvent id="inner-start" />""", inner, """<bpmn:endEvent id="inner-end" />""")
        val (process, plane) = row("checked", """<bpmn:startEvent id="start" />""", outer,
            """<bpmn:subProcess id="group" name="Group">$inside</bpmn:subProcess>""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("checked", "Checked", process, plane + groupPlane))
        home.resolve("agents/writer.md").deleteExisting() // both Tasks' Agent: now each fails its Check

        page.navigate(url("/processes/checked/edit"))
        page.click("#checks a[href='#outer']")
        assertThat(shape("outer")).hasClass(selected)
        assertThat(heading).hasText("Agent Task")
        page.click("#checks a[href='#inner']")
        assertThat(shape("inner")).isVisible() // the sub-process's own plane is open, so Outer is off the page
        assertThat(shape("inner")).hasClass(selected)
    }

    @Test
    fun `an open modeler page answers the Process Builder's diagram tool with its real elements, the unsaved edits included`() = browse {
        agentTask(null)
        http.import("agent-task.bpmn")
        page.navigate(url("/processes/agent-task/edit"))
        shape("draft").click()
        page.locator("#explain input.name").fill("Draft it")
        page.locator("#explain input.name").blur() // an unsaved rename, which the answer carries
        assertThat(page.locator("#canvas")).hasAttribute("data-builder", Pattern.compile(".+"))
        val token = page.locator("#canvas").getAttribute("data-builder")!!

        var text = ""
        eventually { mcpCall(port, token, "diagram").let { (answer, failed) -> text = answer; assertFalse(failed, answer) } }
        val diagram = tools.jackson.databind.json.JsonMapper.shared().readTree(text)
        val elements = diagram["elements"].associateBy { it["id"].asString() }
        assertEquals(setOf("start", "draft", "end"), elements.keys)
        val draft = elements.getValue("draft")
        assertEquals(listOf<Any>("agent", "Draft it", 80), listOf(draft["kind"].asString(), draft["name"].asString(), draft["y"].asInt()))
        assertEquals(listOf("start", "draft", "end"), elements.values.sortedBy { it["x"].asInt() }.map { it["id"].asString() })
        assertEquals("writer", draft["properties"]["ants.agent"].asString())
        assertEquals("\${prompt}", draft["inputs"]["prompt"].asString())
        assertEquals(listOf("f1:start>draft", "f2:draft>end"), diagram["connections"].iterator().asSequence().map { "${it["id"].asString()}:${it["from"].asString()}>${it["to"].asString()}" }.toList())
        val prompt = diagram["startForm"].single()
        assertEquals(listOf<Any>("prompt", "markdown", true), listOf(prompt["id"].asString(), prompt["type"].asString(), prompt["required"].asBoolean()))
    }

    private val json = tools.jackson.databind.json.JsonMapper.shared()

    /** The open modeler page's Process Builder tools: calls one through `/mcp` with the page's token and gives its answer; a failure fails the test. */
    private fun builder(): (String, Map<String, Any?>) -> tools.jackson.databind.JsonNode {
        assertThat(page.locator("#canvas")).hasAttribute("data-builder", Pattern.compile(".+"))
        val token = page.locator("#canvas").getAttribute("data-builder")!!
        eventually { assertFalse(mcpCall(port, token, "diagram").second) } // the page follows its stream
        return { name, arguments -> mcpCall(port, token, name, arguments).let { (text, failed) -> assertFalse(failed, text); json.readTree(text) } }
    }

    /** Ctrl-Z on the canvas. */
    private fun undo() {
        page.locator("#canvas .djs-container > svg").first().focus()
        page.keyboard().press("ControlOrMeta+z")
    }

    @Test
    fun `each Process Builder write tool changes the open canvas without a reload, Ctrl-Z undoes it, and the Checks follow`() = browse {
        agentTask(null)
        http.import("agent-task.bpmn")
        page.navigate(url("/processes/agent-task/edit"))
        val tool = builder()
        page.evaluate("window.unreloaded = true")
        val checks = page.locator("#checks")
        fun diagram() = tool("diagram", emptyMap())
        fun element(id: String) = diagram()["elements"].firstOrNull { it["id"].asString() == id }
        fun connection(id: String) = diagram()["connections"].firstOrNull { it["id"].asString() == id }

        val grill = mapOf("kind" to "agent", "id" to "grill", "name" to "Grill", "x" to 450, "y" to 250)
        assertEquals("grill", tool("add_element", grill)["id"].asString())
        assertThat(shape("grill")).isVisible()
        assertEquals(listOf(450, 250), element("grill")!!.let { listOf(it["x"].asInt(), it["y"].asInt()) })
        assertThat(checks).containsText("Grill (grill) has no Agent.")
        undo()
        assertThat(shape("grill")).hasCount(0)
        assertThat(checks).not().containsText("Grill (grill) has no Agent.")
        tool("add_element", grill)

        assertEquals("""["draft"]""", tool("set_agent_task", mapOf("id" to "grill", "agent" to "writer"))["skills"].toString())
        assertThat(checks).not().containsText("Grill (grill) has no Agent.")
        assertEquals("writer", element("grill")!!["properties"]["ants.agent"].asString())
        undo()
        assertThat(checks).containsText("Grill (grill) has no Agent.")
        tool("set_agent_task", mapOf("id" to "grill", "agent" to "writer"))

        tool("rename", mapOf("id" to "grill", "name" to "Grill hard"))
        assertThat(shape("grill")).containsText("Grill hard")
        undo()
        assertThat(shape("grill")).not().containsText("hard")

        tool("move", mapOf("id" to "grill", "x" to 600, "y" to 250))
        eventually { assertEquals(600, element("grill")!!["x"].asInt()) }
        undo()
        eventually { assertEquals(450, element("grill")!!["x"].asInt()) }

        assertEquals("f3", tool("connect", mapOf("from" to "draft", "to" to "grill", "id" to "f3"))["id"].asString())
        assertThat(shape("f3")).hasCount(1)
        undo()
        assertThat(shape("f3")).hasCount(0)

        tool("add_element", mapOf("kind" to "exclusive", "id" to "decide"))
        tool("connect", mapOf("from" to "decide", "to" to "end", "id" to "done"))
        tool("connect", mapOf("from" to "decide", "to" to "grill", "id" to "again", "name" to "again"))
        assertThat(shape("again_label")).containsText("again")
        tool("set_condition", mapOf("id" to "done", "condition" to "\${grill.summary == 'done'}"))
        eventually { assertEquals("\${grill.summary == 'done'}", connection("done")!!["condition"].asString()) }
        undo()
        eventually { assertTrue(connection("done")!!["condition"].let { it == null || it.isNull }) }
        tool("set_default", mapOf("id" to "again"))
        eventually { assertTrue(connection("again")!!["default"].asBoolean()) }
        undo()
        eventually { assertFalse(connection("again")!!["default"].asBoolean()) }

        val fields = listOf(mapOf("label" to "Prompt", "type" to "markdown", "required" to true), mapOf("id" to "repos", "label" to "Repos", "type" to "repos"))
        assertEquals(listOf("prompt", "repos"), tool("set_start_form", mapOf("fields" to fields))["fields"].iterator().asSequence().map { it.asString() }.toList())
        eventually { assertEquals(listOf("prompt", "repos"), diagram()["startForm"].iterator().asSequence().map { it["id"].asString() }.toList()) }
        undo()
        eventually { assertEquals(listOf("prompt"), diagram()["startForm"].iterator().asSequence().map { it["id"].asString() }.toList()) }

        tool("remove", mapOf("id" to "grill"))
        assertThat(shape("grill")).hasCount(0)
        assertThat(shape("again")).hasCount(0)
        undo()
        assertThat(shape("grill")).isVisible()
        assertThat(shape("again")).hasCount(1)

        for (kind in listOf("start", "end", "timer", "exclusive", "parallel", "agent", "user", "built-in", "call", "subprocess", "annotation", "group")) {
            val id = tool("add_element", mapOf("kind" to kind, "name" to "A $kind"))["id"].asString()
            assertThat(shape(id)).hasCount(1)
        }
        val inside = tool("add_element", mapOf("kind" to "user", "in" to tool("add_element", mapOf("kind" to "subprocess"))["id"].asString()))["id"].asString()
        assertThat(shape(inside)).hasCount(1)
        assertEquals("push", tool("add_element", mapOf("kind" to "built-in", "id" to "ship", "topic" to "push")).let { element("ship")!!["properties"]["topic"].asString() })

        val template = json.readTree(http.get("/templates.json").body).first()["name"].asString()
        val dropped = tool("drop_template", mapOf("name" to template, "x" to 450, "y" to 400))["id"].asString()
        assertThat(shape(dropped)).isVisible()
        assertEquals(true, page.evaluate("window.unreloaded"))
    }

    @Test
    fun `a Process Builder tool naming a missing element fails naming it, and changes nothing`() = browse {
        agentTask(null)
        http.import("agent-task.bpmn")
        page.navigate(url("/processes/agent-task/edit"))
        val tool = builder()
        val token = page.locator("#canvas").getAttribute("data-builder")!!
        val before = tool("diagram", emptyMap())
        for ((name, arguments) in listOf(
            "remove" to mapOf("id" to "Task_x"), "rename" to mapOf("id" to "Task_x", "name" to "X"), "move" to mapOf("id" to "Task_x", "x" to 1, "y" to 1),
            "connect" to mapOf("from" to "draft", "to" to "Task_x"), "set_agent_task" to mapOf("id" to "Task_x", "agent" to "writer"),
            "set_condition" to mapOf("id" to "Task_x", "condition" to "\${true}"), "set_default" to mapOf("id" to "Task_x"),
            "add_element" to mapOf("kind" to "agent", "in" to "Task_x"),
            "set_user_task" to mapOf("id" to "Task_x", "kind" to "approve"), "set_call" to mapOf("id" to "Task_x", "process" to "agent-task"),
            "set_built_in" to mapOf("id" to "Task_x", "topic" to "push"), "set_multi_instance" to mapOf("id" to "Task_x", "mode" to "parallel"),
        )) {
            val (text, failed) = mcpCall(port, token, name, arguments)
            assertTrue(failed, "$name: $text")
            assertContains(text, "No element Task_x", message = name)
        }
        assertEquals(before, tool("diagram", emptyMap()))
    }

    private val builderResult = """{"type":"result","subtype":"success","result":"","session_id":"${'$'}SESSION"}"""

    /** A fake claude's reply [text], as one assistant message. */
    private fun says(text: String) = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]},"session_id":"${'$'}SESSION"}"""

    /** Types [lines] into the Process Builder's prompt box, Shift+Enter between them, and sends them with Enter. */
    private fun ask(vararg lines: String) {
        assertThat(page.locator("#canvas")).hasAttribute("data-builder", Pattern.compile(".+")) // the session is open
        assertThat(page.locator("#pb-send")).isVisible() // no turn runs
        page.locator("#builder .cm-content").click()
        lines.forEachIndexed { i, line ->
            if (i > 0) page.keyboard().press("Shift+Enter")
            page.keyboard().type(line)
        }
        page.keyboard().press("Enter")
    }

    @Test
    fun `the Process Builder asks first, then builds a small Process on the open canvas from the answer, each change one undo step`() = browse {
        val claude = FakeClaude()
        agentTask(claude)
        claude.session("process-builder",
            says("Who answers the questions it asks?"), builderResult, "read",
            """mcp add_element {"kind":"agent","id":"pick","name":"Pick","x":300,"y":80}""",
            """mcp set_agent_task {"id":"pick","agent":"writer","inputs":{"prompt":"${'$'}{prompt}"}}""",
            """mcp add_element {"kind":"end","id":"done","x":500,"y":102}""",
            """mcp connect {"from":"start","to":"pick","id":"toPick"}""",
            """mcp connect {"from":"pick","to":"done","id":"toDone"}""",
            says("Built: Pick, then done."), builderResult)
        newProcess("Built here")
        val dialog = page.locator("#pb-dialog")
        ask("Every morning, pick a small issue and get it to a PR.")
        assertThat(dialog).containsText("Who answers the questions it asks?")
        assertThat(shape("pick")).hasCount(0) // it asks before it builds
        ask("I do.")
        assertThat(dialog).containsText("Built: Pick, then done.")
        assertThat(dialog).containsText("set up the Agent Task pick")
        val built = listOf("pick", "done", "toPick", "toDone")
        for (id in built) assertThat(shape(id)).hasCount(1)
        shape("pick").click()
        assertThat(label("Agent")).hasValue("writer")
        assertThat(page.locator("#explain label.row input").first()).hasValue("\${prompt}")
        page.screenshot("process-builder")
        repeat(5) { undo() }
        for (id in built) assertThat(shape(id)).hasCount(0)
        assertThat(shape("start")).hasCount(1)
    }

    @Test
    fun `the Process Builder keeps one session per page, resumes a dead claude, stops a turn, and a reload keeps the dialog`() = browse {
        val claude = FakeClaude()
        settings(claude)
        val init = """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}""" // claude has started the session
        claude.session("process-builder", 1, init, says("One."), builderResult, "read", says("Two."), "exit 0") // claude dies in the turn
        claude.session("process-builder", 2, says("Three."), "read",
            """{"type":"result","subtype":"error_during_execution","session_id":"${'$'}SESSION"}""", "wait never")
        page.navigate(url("/templates/review-and-push"))
        assertThat(page.locator("#canvas")).isVisible()
        assertThat(page.locator("#builder, #builder-toggle")).hasCount(0) // a Template has no Process Builder
        assertFalse(page.locator("#canvas").getAttribute("data-builder").orEmpty().isNotEmpty())

        newProcess("Talked through")
        page.click("#builder-toggle")
        assertThat(page.locator("#builder")).isHidden()
        page.reload()
        assertThat(page.locator("#builder")).isHidden() // the choice is remembered
        page.click("#builder-toggle")
        assertThat(page.locator("#builder")).isVisible()

        val dialog = page.locator("#pb-dialog")
        ask("First line", "second line")
        assertThat(dialog).containsText("One.")
        eventually { assertTrue("First line\\nsecond line" in claude.launches("process-builder").single().stdin) }
        ask("Again.")
        assertThat(dialog).containsText("Two.")
        assertEquals(1, claude.launches("process-builder").size)
        ask("Third.") // claude exited after Two: this resumes its session
        assertThat(dialog).containsText("Three.")
        val (first, second) = claude.launches("process-builder")
        assertEquals(first.flag("--session-id"), second.flag("--resume"))

        assertThat(page.locator("#pb-send")).isHidden() // Stop replaces Send while the turn runs
        page.click("#pb-stop")
        assertThat(page.locator("#pb-send")).isVisible()
        assertTrue("interrupt" in second.stdin)
        page.reload() // the tab keeps its session: the same claude, the dialog intact
        assertThat(dialog).containsText("Three.")
        assertEquals(2, claude.launches("process-builder").size)
        assertFalse(second.killed)
        claude.release("never")
    }

    @Test
    fun `an Agent Task set up by the Process Builder's tools reads in the saved XML as one set up by hand`() = browse {
        agentTask(null)
        val hand = appendAgentTask()
        label("Agent").selectOption("writer")
        assertThat(label("draft")).isChecked()
        page.locator("#ants-new-input").fill("prompt")
        button("＋ Add input").click()
        assertThat(page.locator("#explain label.row code").first()).hasText("prompt")
        page.locator("#explain label.row input").first().fill("\${prompt}")
        val tool = builder()
        tool("add_element", mapOf("kind" to "agent", "id" to "byTool"))

        // The page sends its XML to the Checks after each change: the one with both Tasks set up.
        val sent = page.waitForRequest({ request ->
            request.url().endsWith("/processes/checks") && Regex("value=\"writer\"").findAll(bpmnOf(request.postData())).count() == 2
        }) { tool("set_agent_task", mapOf("id" to "byTool", "agent" to "writer", "inputs" to mapOf("prompt" to "\${prompt}"))) }
        val xml = bpmnOf(sent.postData())
        // Its attributes but the id, and what its extension elements hold.
        fun task(id: String) = Regex("""<[\w:]*serviceTask ([^>]*)>(.*?)</[\w:]*serviceTask>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { it.groupValues }.single { (_, attributes) -> """id="$id"""" in attributes }
            .let { (_, attributes, body) -> attributes.replace("""id="$id"""", "").trim() + body.substringAfter("extensionElements>").substringBefore("extensionElements>") }
        assertContains(task("byTool"), "\${prompt}")
        assertEquals(task(hand), task("byTool"))
    }

    @Test
    fun `the Process Builder sets an Assignment and two Skills that Save and deploy takes, and gets the Check back for a Task with neither`() = browse {
        agentTask(null, mapOf("draft" to null, "polish" to null))
        home.resolve("skills/unlisted").createDirectories().resolve("SKILL.md").writeText("---\nname: unlisted\ndescription: Not the writer's.\n---\nNo.\n")
        http.import("agent-task.bpmn")
        page.navigate(url("/processes/agent-task/edit"))
        val tool = builder()
        val set = tool("set_agent_task", mapOf("id" to "draft", "assignment" to "Greet the user.\nKeep it short.", "skills" to listOf("draft", "polish")))
        assertEquals("""["draft","polish"]""", set["skills"].toString())
        assertEquals("[]", set["checks"].toString())
        assertThat(page.locator("#summary")).hasText("Ready to deploy")
        shape("draft").click()
        assertThat(label("polish")).isChecked()
        page.locator("#save").click()
        assertThat(page).hasURL(Pattern.compile(".*/processes/agent-task$"))
        val saved = http.get("/processes/agent-task/diagram.bpmn").body
        assertContains(saved, """name="ants.skills" value="draft, polish"""")
        assertContains(saved, """name="ants.assignment" value="Greet the user.""")

        page.navigate(url("/processes/agent-task/edit"))
        val token = builder().let { page.locator("#canvas").getAttribute("data-builder")!! }
        val (lacking, refused) = mcpCall(port, token, "set_agent_task", mapOf("id" to "draft", "skills" to listOf("unlisted")))
        assertTrue(refused, lacking)
        assertContains(lacking, "The Agent writer doesn't have the Skill unlisted: it has draft, polish.")
        val (neither, failed) = mcpCall(port, token, "set_agent_task", mapOf("id" to "draft", "assignment" to "", "skills" to null))
        assertFalse(failed, neither)
        assertEquals("""["Draft (draft) has no Assignment and doesn't list exactly one Skill."]""", json.readTree(neither)["checks"].toString())
        assertThat(page.locator("#checks")).containsText("Draft (draft) has no Assignment and doesn't list exactly one Skill.")
    }

    private fun texts(node: tools.jackson.databind.JsonNode?) = node?.iterator()?.asSequence()?.map { it.asString() }?.toList()

    @Test
    fun `the Process Builder's tools set each Task's inputs, outputs and variables, diagram reads them back, and Ctrl-Z undoes each`() = browse {
        agentTask(null)
        http.import("agent-task.bpmn")
        page.navigate(url("/processes/agent-task/edit"))
        val tool = builder()
        page.evaluate("window.unreloaded = true")
        fun element(id: String) = tool("diagram", emptyMap())["elements"].first { it["id"].asString() == id }
        fun property(id: String, name: String) = element(id)["properties"][name]?.asString()

        assertEquals(listOf("\${draft.summary}"), texts(element("draft")["outputs"]))
        tool("set_agent_task", mapOf("id" to "draft", "resultSchema" to """{"type":"object","properties":{"items":{"type":"array"}}}""", "resume" to true, "vault" to false))
        eventually { assertEquals(listOf("\${draft.output.items}"), texts(element("draft")["outputs"])) }
        assertEquals(listOf("true", "false"), listOf(property("draft", "ants.resume"), property("draft", "ants.vault")))
        undo()
        eventually { assertEquals(listOf("\${draft.summary}"), texts(element("draft")["outputs"])) }

        tool("add_element", mapOf("kind" to "user", "id" to "approve"))
        tool("set_user_task", mapOf("id" to "approve", "fields" to mapOf("title" to "Ship it?", "send-back" to "draft")))
        shape("approve").click()
        assertThat(label("Title")).hasValue("Ship it?")
        assertEquals("draft", property("approve", "ants.send-back"))
        assertEquals(listOf("\${approve.approved}", "\${approve.comment}"), texts(element("approve")["outputs"]))
        tool("set_user_task", mapOf("id" to "approve", "kind" to "review-changes", "fields" to mapOf("walkthrough" to "draft")))
        eventually { assertEquals("review-changes", property("approve", "ants.user-task")) }
        assertEquals(listOf("draft", "draft"), listOf(property("approve", "ants.walkthrough"), property("approve", "ants.send-back")))
        assertEquals(0, element("approve")["inputs"].size()) // Approve's title went with the kind
        undo()
        eventually { assertEquals("Ship it?", element("approve")["inputs"]["title"]?.asString()) }

        tool("add_element", mapOf("kind" to "call", "id" to "again"))
        assertEquals("", tool("set_call", mapOf("id" to "again", "process" to "agent-task"))["inputs"]["prompt"].asString())
        tool("set_call", mapOf("id" to "again", "inputs" to mapOf("prompt" to "\${prompt}")))
        eventually { assertEquals("\${prompt}", element("again")["inputs"]["prompt"].asString()) }
        assertEquals("agent-task", property("again", "calls"))
        undo()
        eventually { assertEquals("", element("again")["inputs"]["prompt"].asString()) }

        tool("add_element", mapOf("kind" to "built-in", "id" to "clone"))
        tool("set_built_in", mapOf("id" to "clone", "inputs" to mapOf("repos" to "\${draft.output.repos}")))
        eventually { assertEquals("\${draft.output.repos}", element("clone")["inputs"]["repos"]?.asString()) }
        tool("set_built_in", mapOf("id" to "clone", "topic" to "accept-recommendations", "asker" to "draft"))
        eventually { assertEquals("accept-recommendations", property("clone", "topic")) }
        assertEquals("draft", property("clone", "ants.asker"))
        undo()
        eventually { assertEquals("clone-repos", property("clone", "topic")) }

        tool("set_multi_instance", mapOf("id" to "draft", "mode" to "parallel", "collection" to "\${items}", "elementVariable" to "ticket", "itemsAreTickets" to true))
        eventually { assertEquals("ticket", element("draft")["properties"]["multiInstance"]?.get("elementVariable")?.asString()) }
        val loop = element("draft")["properties"]
        assertEquals(listOf("\${items}", "false", "true"), listOf(loop["multiInstance"]["collection"].asString(), loop["multiInstance"]["sequential"].asString(), loop["ants.items-are-tickets"].asString()))
        shape("draft").click()
        assertThat(panel).containsText("Parallel multi-instance")
        undo()
        eventually { assertNull(element("draft")["properties"]["multiInstance"]) }
        assertEquals(true, page.evaluate("window.unreloaded"))
    }

    @Test
    fun `a User Task, a call activity and a multi-instance set up by the Process Builder's tools read in the saved XML as ones set up by hand`() = browse {
        agentTask(null)
        http.import("agent-task.bpmn")
        page.navigate(url("/processes/agent-task/edit"))
        val tool = builder()
        // Below the Process, the ones set up by hand in view and clear of the palette: the canvas doesn't scroll to a shape to
        // click it. The multi-instance leftmost, so its context pad (the wrench) is on the canvas too. Nothing above or left
        // of the Process, which Save's align-to-origin would move.
        for ((i, kind, id) in listOf(Triple(0, "agent", "Loop"), Triple(1, "user", "User"), Triple(2, "call", "Call")))
            for ((who, y) in listOf("hand" to 230, "tool" to 400)) tool("add_element", mapOf("kind" to kind, "id" to who + id, "x" to 130 + 150 * i, "y" to y))

        shape("handUser").click()
        label("Title").fill("Ship it?")
        label("Title").press("Tab")
        label("Show").fill("notes.md")
        label("Show").press("Tab")
        label("Send back resumes").selectOption("draft")
        shape("handCall").click()
        label("Process").selectOption("agent-task")
        page.locator("#explain label.row input").first().fill("\${prompt}")
        shape("handLoop").click()
        pad.locator("[data-action='replace']").click()
        page.locator(".djs-popup [data-id='toggle-parallel-mi']").click()
        label("Collection").fill("\${draft.output.items}")
        label("Element variable").fill("ticket")
        page.getByLabel("Items are Tickets").check()

        tool("set_user_task", mapOf("id" to "toolUser", "kind" to "approve", "fields" to mapOf("title" to "Ship it?", "show" to "notes.md", "send-back" to "draft")))
        tool("set_call", mapOf("id" to "toolCall", "process" to "agent-task", "inputs" to mapOf("prompt" to "\${prompt}")))
        // The page sends its XML to the Checks after each change: the one with both multi-instances set up.
        val sent = page.waitForRequest({ request ->
            request.url().endsWith("/processes/checks") && Regex("ants.items-are-tickets").findAll(bpmnOf(request.postData())).count() == 2
        }) { tool("set_multi_instance", mapOf("id" to "toolLoop", "mode" to "parallel", "collection" to "\${draft.output.items}", "elementVariable" to "ticket", "itemsAreTickets" to true)) }
        val xml = bpmnOf(sent.postData())
        // Its attributes but the id, and all it holds.
        fun element(tag: String, id: String) = Regex("""<[\w:]*$tag ([^>]*)>(.*?)</[\w:]*$tag>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { it.groupValues }.single { (_, attributes) -> """id="$id"""" in attributes }
            .let { (_, attributes, body) -> attributes.replace("""id="$id"""", "").trim() + body }
        for ((tag, id) in listOf("userTask" to "User", "callActivity" to "Call", "serviceTask" to "Loop")) {
            assertContains(element(tag, "tool$id"), if (id == "User") "notes.md" else if (id == "Call") "\${prompt}" else "ticket")
            assertEquals(element(tag, "hand$id"), element(tag, "tool$id"), id)
        }
    }

    @Test
    fun `shapes above the Process don't freeze the modeler when a call activity's panel opens and changes`() = browse {
        agentTask(null)
        http.import("agent-task.bpmn")
        page.navigate(url("/processes/agent-task/edit"))
        val tool = builder()
        // Above the Process, where a save's align-to-origin moves every shape; the call activity's panel reads the diagram
        // when it opens and when its Process changes, before the Checks have run and moved them.
        for ((i, kind) in listOf("agent", "user", "call").withIndex()) tool("add_element", mapOf("kind" to kind, "id" to "above$i", "x" to 130 + 150 * i, "y" to -60))
        shape("above2").click()
        label("Process").selectOption("agent-task")
        assertThat(page.locator("#explain label.row input")).hasCount(1) // its input, for the called Process's prompt
        tool("rename", mapOf("id" to "above2", "name" to "Again"))
        assertThat(shape("above2")).containsText("Again")
        assertEquals(emptyList(), errors.filter { "call stack" in it })
    }

    /** The `bpmn` field of a form-encoded body. */
    private fun bpmnOf(body: String?) = body.orEmpty().split("&").firstOrNull { it.startsWith("bpmn=") }
        ?.let { java.net.URLDecoder.decode(it.removePrefix("bpmn="), Charsets.UTF_8) }.orEmpty()

    @Test
    fun `New Agent's modal makes a Draft-only Agent, keeps the unsaved diagram and picks the Agent on the Task, marked`() = browse {
        home.resolve("agents/helper.md").toFile().delete()
        val task = appendAgentTask()
        val modeler = page.url()
        page.locator("#explain button", Page.LocatorOptions().setHasText("New Agent")).click()
        val form = page.frameLocator("dialog iframe")
        form.locator("input[name=name]").fill("helper")
        page.locator("dialog .mh button", Page.LocatorOptions().setHasText("Use")).click()

        assertThat(page.locator("dialog")).hasCount(0)
        assertThat(label("Agent")).hasValue("helper")
        assertThat(label("Agent").locator("option:checked")).hasText("helper (Draft)")
        assertThat(mark("Agent")).hasText("Draft")
        assertEquals(modeler, page.url())
        assertThat(shape(task)).hasClass(selected) // the Task drawn, never saved, is still there
        assertFalse(home.resolve("agents/helper.md").toFile().exists()) // a Draft only, which Publish puts to use
        http.post("/agents/helper/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `Edit's modal saves the Task's Agent and keeps the unsaved diagram`() = browse {
        agentTask(null)
        val task = appendAgentTask()
        label("Agent").selectOption("writer")
        val modeler = page.url()
        page.locator("#explain button", Page.LocatorOptions().setHasText("Edit")).first().click()
        assertThat(page.locator("dialog.modal")).containsText("Edit Agent")
        page.frameLocator("dialog iframe").locator("input[name=description]").fill("Writes warmly.")
        page.locator("dialog .mh button", Page.LocatorOptions().setHasText("Publish")).click()

        assertThat(page.locator("dialog")).hasCount(0)
        assertEquals(modeler, page.url())
        assertThat(shape(task)).hasClass(selected)
        assertThat(label("Agent").locator("option:checked")).hasText("writer · Writes warmly.")
        assertContains(home.resolve("agents/writer.md").readText(), "Writes warmly.")
    }

    @Test
    fun `Edit's modal saves the Task's Skill, and the panel shows its new description`() = browse {
        agentTask(null)
        appendAgentTask()
        label("Agent").selectOption("writer")
        assertThat(label("draft")).isChecked()
        page.locator("#explain button", Page.LocatorOptions().setHasText("Edit")).nth(1).click()
        assertThat(page.locator("dialog.modal")).containsText("Edit Skill")
        page.frameLocator("dialog iframe").locator("#skill-md + .ed .cm-content")
            .fill("---\nname: draft\ndescription: Drafts it better.\n---\nDo it.\n")
        page.locator("dialog .mh button", Page.LocatorOptions().setHasText("Publish")).click()

        assertThat(page.locator("dialog")).hasCount(0)
        assertThat(panel.locator(".skillpick")).containsText("Drafts it better.")
    }

    @Test
    fun `the Agent Task panel's pickers mark a Draft-only Agent and a Skill published with a Draft`() = browse {
        agentTask(null)
        val draftOnly = http.post("/agents/new/draft", mapOf()).path
        val name = draftOnly.removePrefix("/agents/")
        http.autosave("/skills/draft", mapOf("skill" to "---\nname: draft\ndescription: Half done.\n---\n"), draftVersion(http.get("/skills/draft").body))
        try {
            appendAgentTask()
            assertThat(label("Agent").locator("option[value='$name']")).hasText("$name (Draft)")
            assertThat(label("Agent").locator("option[value='writer']")).not().containsText("Draft")
            label("Agent").selectOption("writer")
            assertThat(mark("Agent")).hasCount(0)
            assertThat(panel.locator(".skillpick .draft-mark.p-changed")).hasText("Draft · changes not published")
            label("Agent").selectOption(name)
            assertThat(mark("Agent")).hasText("Draft")
        } finally {
            http.post("$draftOnly/delete", mapOf("confirm" to "on"))
            http.post("/skills/draft/discard", mapOf("confirm" to "on"))
        }
    }

    @Test
    fun `Edit is off for a read-only Agent`() = browse {
        agentTask(null)
        appendAgentTask()
        label("Agent").selectOption("shipped-writer")
        assertThat(page.locator("#explain button", Page.LocatorOptions().setHasText("Edit")).first()).isDisabled()
    }

    /** Opens the modeler on a blank Process and appends a Script Task to its start event; the Task's id. */
    private fun appendScriptTask(): String {
        newProcess("Drawn here")
        assertThat(page.locator(".djs-palette [data-action='create.script-task']")).hasAttribute("aria-label", Pattern.compile("^Script Task: Runs one of your Scripts"))
        shape("start").click()
        pad.locator("[data-action='append.script-task']").click()
        assertThat(heading).hasText("Script Task")
        return page.locator("#canvas .djs-shape.selected").getAttribute("data-element-id")!!
    }

    @Test
    fun `the palette's Script Task drops on the canvas and opens its panel`() = browse {
        newProcess("From the palette")
        page.locator(".djs-palette [data-action='create.script-task']").click()
        page.locator("#canvas").click(Locator.ClickOptions().setPosition(500.0, 300.0))
        assertThat(heading).hasText("Script Task")
        assertThat(label("Script")).isVisible()
    }

    @Test
    fun `New Script's modal makes a Draft-only Script and picks it on the Script Task, marked, with a field for each input`() = browse {
        val task = appendScriptTask()
        val modeler = page.url()
        button("New Script").click()
        assertThat(page.locator("dialog.modal")).containsText("New Script")
        val form = page.frameLocator("dialog iframe")
        assertThat(form.locator("#script-helper")).hasAttribute("data-token", Pattern.compile(".+")) // its helper runs in the modal too
        form.locator("input[name=name]").fill("post-to-chat")
        form.locator("input[name=description]").fill("Posts to the chat.")
        for ((i, input) in listOf("channel", "text").withIndex()) {
            form.locator("#input-schema > .schema-level > [data-add-row]").click()
            form.locator("input[name='inputs[$i].name']").fill(input)
        }
        page.locator("dialog .mh button", Page.LocatorOptions().setHasText("Use")).click()

        assertThat(page.locator("dialog")).hasCount(0)
        assertEquals(modeler, page.url())
        assertThat(shape(task)).hasClass(selected)
        assertThat(label("Script")).hasValue("post-to-chat")
        assertThat(label("Script").locator("option:checked")).hasText("post-to-chat · Posts to the chat. (Draft)")
        assertThat(mark("Script")).hasText("Draft")
        label("channel").fill("general")
        label("text").fill("\${prompt}")
        assertFalse(home.resolve("scripts").toFile().listFiles().orEmpty().any { it.resolve("script.yaml").readText().contains("name: post-to-chat") })
        http.post("/scripts/post-to-chat/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `a Script Task's panel shows each input's type, description and whether it is required`() = browse {
        http.publishNew("/scripts", listOf("name" to "post-it", "description" to "Posts it.", "code" to "console.log('hi')\n", "vars" to "",
            "inputs[0].name" to "channel", "inputs[0].type" to "string", "inputs[0].description" to "Where to post.", "inputs[0].required" to "on",
            "inputs[1].name" to "count", "inputs[1].type" to "number"))
        appendScriptTask()
        label("Script").selectOption("post-it")

        val channel = page.locator("#explain [data-input=channel]")
        assertThat(channel).containsText("text")
        assertThat(channel).containsText("required")
        assertThat(channel).containsText("Where to post.")
        val count = page.locator("#explain [data-input=count]")
        assertThat(count).containsText("number")
        assertThat(count).not().containsText("required")
        label("channel").fill("general")
    }

    @Test
    fun `the input pickers and the condition builder offer a Script Task's payload fields from its output schema, nested ones too`() = browse {
        agentTask(null)
        http.publishNew("/scripts", listOf("name" to "post-ts", "description" to "Posts.", "code" to "ants.output({ts: '1'})\n", "vars" to "",
            "output[0].name" to "ts", "output[0].type" to "string",
            "output[1].name" to "meta", "output[1].type" to "object", "output[1].rows[0].name" to "channel", "output[1].rows[0].type" to "string"))
        val post = """<bpmn:serviceTask id="post" name="Post" operaton:type="external" operaton:topic="script"><bpmn:extensionElements><operaton:properties>""" +
            """<operaton:property name="ants.script" value="post-ts" /></operaton:properties></bpmn:extensionElements></bpmn:serviceTask>"""
        val (process, plane) = row("posted", """<bpmn:startEvent id="start" />""", post, agentTaskXml("draft", "Draft"),
            """<bpmn:exclusiveGateway id="ok" name="Ok?" default="ok-end" />""", """<bpmn:endEvent id="end" />""")
        val back = """<bpmn:sequenceFlow id="ok-draft" sourceRef="ok" targetRef="draft"><bpmn:conditionExpression>${'$'}{post.exitCode == 0}</bpmn:conditionExpression></bpmn:sequenceFlow>"""
        val edge = """<bpmndi:BPMNEdge id="ok-draft_di" bpmnElement="ok-draft"><di:waypoint x="600" y="80" /><di:waypoint x="600" y="40" />""" +
            """<di:waypoint x="450" y="40" /><di:waypoint x="450" y="80" /></bpmndi:BPMNEdge>"""
        deploy(definitions("posted", "Posted", process + back, plane.replace("</bpmndi:BPMNPlane>", "$edge</bpmndi:BPMNPlane>")))

        page.navigate(url("/processes/posted/edit"))
        shape("draft").click()
        for (offered in listOf("post.exitCode", "post.output", "post.payload.ts", "post.payload.meta", "post.payload.meta.channel")) {
            assertThat(page.locator("#ants-references option[value='${'$'}{$offered}']")).hasCount(1)
        }
        shape("ok").click()
        val field = page.locator("#explain .card", Page.LocatorOptions().setHasText("→ Draft")).locator("select").first()
        assertThat(field.locator("option[value='post.payload.ts']")).hasText("post.payload.ts (from Post)")
        assertThat(field.locator("option[value='post.payload.meta.channel']")).hasText("post.payload.meta.channel (from Post)")
        assertThat(field.locator("option[value='post.exitCode']")).hasText("post.exitCode (from Post)")
    }

    @Test
    fun `Edit's modal renames the Task's Script, and the panel shows the new name`() = browse {
        http.publishNew("/scripts", listOf("name" to "tidy-up", "description" to "Tidies up.", "code" to "console.log('tidy')\n", "vars" to ""))
        appendScriptTask()
        label("Script").selectOption("tidy-up")
        button("Edit").click()
        assertThat(page.locator("dialog.modal")).containsText("Edit Script")
        page.frameLocator("dialog iframe").locator("input[name=name]").fill("clean-up")
        page.locator("dialog .mh button", Page.LocatorOptions().setHasText("Publish")).click()

        assertThat(page.locator("dialog")).hasCount(0)
        assertThat(label("Script")).hasValue("clean-up")
    }

    @Test
    fun `the Process Builder's add_element draws a Script Task naming a Script, which Save and deploy keeps as its id`() = browse {
        http.publishNew("/scripts", listOf("name" to "tidy-up", "description" to "Tidies up.", "code" to "console.log('tidy')\n", "inputs[0].name" to "folder", "inputs[0].type" to "string", "vars" to ""))
        agentTask(null)
        http.import("agent-task.bpmn")
        page.navigate(url("/processes/agent-task/edit"))
        val tool = builder()

        tool("add_element", mapOf("kind" to "script", "id" to "tidy", "name" to "Tidy", "script" to "tidy-up", "inputs" to mapOf("folder" to "\${prompt}")))
        assertThat(shape("tidy")).isVisible()
        val drawn = tool("diagram", emptyMap())["elements"].single { it["id"].asString() == "tidy" }
        assertEquals("script", drawn["kind"].asString())
        assertEquals("tidy-up", drawn["properties"]["ants.script"].asString())
        assertEquals("\${prompt}", drawn["inputs"]["folder"].asString())
        assertEquals("out", tool("set_built_in", mapOf("id" to "tidy", "inputs" to mapOf("folder" to "out")))["inputs"]["folder"].asString())
        assertTrue(tool("checks", emptyMap()).none { "tidy" in it["message"].asString().lowercase() && "script" in it["message"].asString().lowercase() })
        undo()
        assertEquals("\${prompt}", tool("diagram", emptyMap())["elements"].single { it["id"].asString() == "tidy" }["inputs"]["folder"].asString())
    }

    @Test
    fun `a new Script's editor shows its placeholder, and a Test run shows the exit code as a badge and the payload as coloured JSON`() = browse {
        home.resolve("settings.yaml").writeText("node: {path: ${ScriptsTest.NODE}}\n")
        page.navigate(url(http.newDraft("/scripts")))
        val editor = page.locator(".code-editor .cm-content")
        assertThat(page.locator(".code-editor .cm-placeholder")).containsText("ants.inputs")
        assertThat(page.locator(".code-editor .cm-placeholder")).containsText("ants.output(")
        editor.click()
        page.keyboard().insertText("const {text} = ants.inputs\nconsole.log('posting')\nants.output({posted: text, count: 2})\n")
        page.getByLabel("Sample inputs, as JSON").fill("""{"text": "hello"}""")
        page.locator(".code-editor button", Page.LocatorOptions().setHasText("Test run")).click()

        val result = page.locator(".test-run")
        assertThat(result.locator(".pill.p-done")).hasText("Succeeded · exit 0")
        assertThat(result.locator("pre.output")).hasText("posting\n")
        assertThat(result.locator("pre.payload")).hasText("{\n  \"posted\": \"hello\",\n  \"count\": 2\n}")
        assertThat(result.locator("pre.payload .j-key")).hasCount(2)
        assertThat(result.locator("pre.payload .j-str")).hasText("\"hello\"")
        assertThat(result.locator("pre.payload .j-num")).hasText("2")
        page.screenshot("script-test-run")

        editor.click()
        page.keyboard().press("Control+End")
        page.keyboard().insertText("process.exit(3)\n")
        page.locator(".code-editor button", Page.LocatorOptions().setHasText("Test run")).click()
        assertThat(result.locator(".pill.p-failed")).hasText("Failed · exit 3")
    }

    @Test
    fun `the Script page's schema card shows Input schema first, nests an object's fields, fills the Test run's sample from the rows, and saves them`() = browse {
        page.navigate(url(http.newDraft("/scripts")))
        fun tab(name: String) = page.locator("#schema").getByRole(AriaRole.TAB, Locator.GetByRoleOptions().setName(name))
        assertThat(tab("Input schema")).hasAttribute("aria-selected", "true")
        assertThat(page.locator("#input-schema")).isVisible()
        assertThat(page.locator("#output-schema")).isHidden()
        tab("Output schema").click()
        assertThat(tab("Output schema")).hasAttribute("aria-selected", "true")
        assertThat(page.locator("#output-schema")).containsText("any JSON payload is accepted")
        assertThat(page.locator("#input-schema")).isHidden()
        tab("Input schema").click()

        val inputs = page.locator("#input-schema > .schema-level")
        inputs.locator(":scope > [data-add-row]").click()
        page.locator("input[name='inputs[0].name']").fill("text")
        page.locator("input[name='inputs[0].required']").check()
        inputs.locator(":scope > [data-add-row]").click()
        page.locator("input[name='inputs[1].name']").fill("ticket")
        val ticket = inputs.locator(":scope > ol > li").nth(1)
        assertThat(ticket.locator(".schema-fields")).isHidden()
        page.locator("select[name='inputs[1].type']").selectOption("object")
        assertThat(ticket.locator(".schema-fields")).isVisible()
        ticket.locator(".schema-fields [data-add-row]").click()
        page.locator("input[name='inputs[1].rows[0].name']").fill("labels")
        page.locator("select[name='inputs[1].rows[0].type']").selectOption("array:string")
        val sample = page.getByLabel("Sample inputs, as JSON")
        assertThat(sample).hasValue("""{"text":"","ticket":{"labels":[]}}""")

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Remove text").setExact(true)).click()
        assertThat(page.locator("input[name='inputs[0].rows[0].name']")).hasValue("labels")
        assertThat(sample).hasValue("""{"ticket":{"labels":[]}}""")
        page.screenshot("script-schema-card")

        page.locator("#name").fill("file-ticket")
        page.locator("#description").fill("Files a ticket.")
        page.locator(".ph button.primary").click()
        assertThat(page).hasURL(Pattern.compile(".*/scripts/file-ticket$"))
        assertThat(page.locator("input[name='inputs[0].rows[0].name']")).hasValue("labels")
        val saved = home.resolve("scripts").toFile().listFiles()!!.map { it.resolve("script.yaml").readText() }.single { "name: file-ticket" in it }
        assertEquals(
            mapOf("type" to "object", "properties" to mapOf("ticket" to mapOf("type" to "object", "properties" to mapOf("labels" to mapOf("type" to "array", "items" to mapOf("type" to "string")))))),
            org.yaml.snakeyaml.Yaml().load<Map<*, *>>(saved)["inputs"],
        )
    }

    @Test
    fun `the Script page's schema rows move up and down, nested ones and payload rows too, renumbered for Save`() = browse {
        page.navigate(url(http.newDraft("/scripts")))
        val inputs = page.locator("#input-schema > .schema-level")
        for ((i, name) in listOf("text", "channel", "ticket").withIndex()) {
            inputs.locator(":scope > [data-add-row]").click()
            page.locator("input[name='inputs[$i].name']").fill(name)
        }
        page.locator("select[name='inputs[2].type']").selectOption("object")
        val ticket = inputs.locator(":scope > ol > li").nth(2)
        for ((i, name) in listOf("id", "title").withIndex()) {
            ticket.locator(":scope > .schema-fields > .schema-level > [data-add-row]").click()
            page.locator("input[name='inputs[2].rows[$i].name']").fill(name)
        }
        fun button(name: String) = page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(name).setExact(true))

        button("Move ticket up").click()
        button("Move text down").click()
        assertThat(page.locator("input[name='inputs[0].name']")).hasValue("ticket")
        assertThat(page.locator("input[name='inputs[1].name']")).hasValue("text")
        assertThat(page.locator("input[name='inputs[2].name']")).hasValue("channel")
        button("Move title up").click()
        assertThat(page.locator("input[name='inputs[0].rows[0].name']")).hasValue("title")
        assertThat(page.locator("input[name='inputs[0].rows[1].name']")).hasValue("id")
        button("Move ticket up").click() // the first row stays first
        assertThat(page.locator("input[name='inputs[0].name']")).hasValue("ticket")
        assertThat(page.getByLabel("Sample inputs, as JSON")).hasValue("""{"ticket":{"title":"","id":""},"text":"","channel":""}""")

        page.locator("#schema").getByRole(AriaRole.TAB, Locator.GetByRoleOptions().setName("Output schema")).click()
        val output = page.locator("#output-schema > .schema-level")
        for ((i, name) in listOf("ts", "user").withIndex()) {
            output.locator(":scope > [data-add-row]").click()
            page.locator("input[name='output[$i].name']").fill(name)
        }
        button("Move user up").click()
        assertThat(page.locator("input[name='output[0].name']")).hasValue("user")

        page.locator("#name").fill("ordered")
        page.locator("#description").fill("Keeps its order.")
        page.locator(".ph button.primary").click()
        assertThat(page).hasURL(Pattern.compile(".*/scripts/ordered$"))
        val saved = org.yaml.snakeyaml.Yaml().load<Map<*, *>>(home.resolve("scripts").toFile().listFiles()!!.map { it.resolve("script.yaml").readText() }.single { "name: ordered" in it })
        val properties = (saved["inputs"] as Map<*, *>)["properties"] as Map<*, *>
        assertEquals(listOf("ticket", "text", "channel"), properties.keys.toList())
        assertEquals(listOf("title", "id"), ((properties["ticket"] as Map<*, *>)["properties"] as Map<*, *>).keys.toList())
        assertEquals(listOf("user", "ts"), ((saved["output"] as Map<*, *>)["properties"] as Map<*, *>).keys.toList())
    }

    @Test
    fun `the Script page's Output schema tab adds payload rows, a Test run shows each mismatch beside the result, and the rows save as output`() = browse {
        home.resolve("settings.yaml").writeText("node: {path: ${ScriptsTest.NODE}}\n")
        page.navigate(url(http.newDraft("/scripts")))
        page.locator("#schema").getByRole(AriaRole.TAB, Locator.GetByRoleOptions().setName("Output schema")).click()
        val none = page.locator("#output-schema [data-no-output]")
        assertThat(none).hasText("No output schema: any JSON payload is accepted.")
        val output = page.locator("#output-schema > .schema-level")
        output.locator(":scope > [data-add-row]").click()
        assertThat(none).isHidden()
        page.locator("input[name='output[0].name']").fill("ts")
        page.locator("input[name='output[0].required']").check()
        assertThat(page.getByLabel("Sample inputs, as JSON")).hasValue("{}")

        page.locator(".code-editor .cm-content").click()
        page.keyboard().insertText("ants.output({ts: 12})\n")
        page.locator(".code-editor button", Page.LocatorOptions().setHasText("Test run")).click()
        val result = page.locator(".test-run")
        assertThat(result.locator(".pill.p-failed")).hasText("Failed · exit 0, the payload breaks the output schema")
        assertThat(result.getByRole(AriaRole.LIST, Locator.GetByRoleOptions().setName("How the payload breaks the output schema"))).hasText("/ts: integer found, string expected")

        page.locator("#name").fill("post-ts")
        page.locator("#description").fill("Posts.")
        page.locator(".ph button.primary").click()
        assertThat(page).hasURL(Pattern.compile(".*/scripts/post-ts$"))
        assertThat(page.locator("input[name='output[0].name']")).hasValue("ts")
        val saved = home.resolve("scripts").toFile().listFiles()!!.map { it.resolve("script.yaml").readText() }.single { "name: post-ts" in it }
        assertEquals(mapOf("type" to "object", "properties" to mapOf("ts" to mapOf("type" to "string")), "required" to listOf("ts")), org.yaml.snakeyaml.Yaml().load<Map<*, *>>(saved)["output"])

        page.locator("#schema").getByRole(AriaRole.TAB, Locator.GetByRoleOptions().setName("Output schema")).click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Remove ts").setExact(true)).click()
        assertThat(none).isVisible()
    }

    @Test
    fun `the Script editor marks the validator's findings on the code's lines and the schema rows as the user types`() = browse {
        home.resolve("settings.yaml").writeText("node: {path: ${ScriptsTest.NODE}}\n")
        page.navigate(url(http.newDraft("/scripts")))
        page.locator("#input-schema > .schema-level > [data-add-row]").click()
        page.locator("input[name='inputs[0].name']").fill("text")
        val row = page.locator("#input-schema .schema-row").first()
        assertThat(row.locator(".finding.f-warning")).hasText("The input text is never read.")

        page.locator(".code-editor .cm-content").click()
        page.keyboard().insertText("console.log(ants.inputs.txt)")
        val marked = page.locator(".code-editor .cm-lintRange-error")
        assertThat(marked).hasCount(1)
        assertThat(page.locator(".code-editor .cm-lint-marker-error")).hasCount(1)
        assertThat(row.locator(".finding")).hasCount(1)

        repeat(3) { page.keyboard().press("Backspace") }
        page.keyboard().insertText("ext)")
        assertThat(marked).hasCount(0)
        assertThat(row.locator(".finding")).hasCount(0)
    }

    @Test
    fun `the Script editor's helper reads the unsaved code and replaces it in the editor, unsaved, and Ctrl-Z takes it back`() = browse {
        val draft = http.newDraft("/scripts")
        page.navigate(url(draft))
        val editor = page.locator(".code-editor .cm-content")
        editor.click()
        page.keyboard().insertText("console.log('mine')")
        val helper = page.getByRole(AriaRole.COMPLEMENTARY, Page.GetByRoleOptions().setName("Script helper"))
        assertThat(helper).hasAttribute("data-token", Pattern.compile(".+"))
        val token = helper.getAttribute("data-token")!!

        eventually { mcpCall(port, token, "buffer").let { (text, failed) -> assertFalse(failed, text); assertEquals("console.log('mine')", text) } }
        val (text, failed) = mcpCall(port, token, "replace_buffer", mapOf("code" to "console.log('helped')\n"))
        assertFalse(failed, text)
        assertThat(editor).hasText("console.log('helped')")
        assertEquals("console.log('helped')\n", page.locator("textarea#code").inputValue())
        assertTrue(page.url().endsWith(draft))
        assertFalse(home.resolve("scripts").toFile().walk().any { it.name == "script.mjs" && "helped" in it.readText() })

        editor.click()
        page.keyboard().press("ControlOrMeta+z")
        assertThat(editor).hasText("console.log('mine')")
    }

    @Test
    fun `the Script editor's helper replaces both schemas with rows on the page, unsaved, and one Ctrl-Z takes the replace back`() = browse {
        page.navigate(url(http.newDraft("/scripts")))
        val editor = page.locator(".code-editor .cm-content")
        editor.click()
        page.keyboard().insertText("console.log(ants.inputs.text)")
        page.locator("#input-schema > .schema-level > [data-add-row]").click()
        page.locator("input[name='inputs[0].name']").fill("mine")
        val helper = page.getByRole(AriaRole.COMPLEMENTARY, Page.GetByRoleOptions().setName("Script helper"))
        assertThat(helper).hasAttribute("data-token", Pattern.compile(".+"))
        val token = helper.getAttribute("data-token")!!
        val inputs = mapOf("type" to "object", "properties" to mapOf(
            "text" to mapOf("type" to "string", "description" to "What to post"),
            "ticket" to mapOf("type" to "object", "properties" to mapOf("title" to mapOf("type" to "string"))),
        ), "required" to listOf("text"))
        val output = mapOf("type" to "object", "properties" to mapOf("ts" to mapOf("type" to "string")), "required" to listOf("ts"))

        val (text, failed) = mcpCall(port, token, "replace_schemas", mapOf("inputs" to inputs, "output" to output))
        assertFalse(failed, text)
        assertThat(page.locator("input[name='inputs[0].name']")).hasValue("text")
        assertThat(page.locator("input[name='inputs[0].required']")).isChecked()
        assertThat(page.locator("input[name='inputs[1].rows[0].name']")).isVisible()
        assertThat(page.locator("input[name='inputs[1].rows[0].name']")).hasValue("title")
        assertThat(page.getByLabel("Sample inputs, as JSON")).hasValue("""{"text":"","ticket":{"title":""}}""")
        assertThat(page.locator("input[name='output[0].name']")).hasValue("ts")
        assertEquals(true, page.locator("[data-no-output]").evaluate("note => note.hidden"))
        val (read, _) = mcpCall(port, token, "schemas")
        assertEquals(mapOf("inputs" to inputs, "output" to output), tools.jackson.databind.json.JsonMapper.shared().readValue(read, Map::class.java))
        assertFalse(home.resolve("scripts").toFile().walk().any { it.name == "script.yaml" })

        editor.click()
        page.keyboard().press("ControlOrMeta+z")
        assertThat(page.locator("input[name='inputs[0].name']")).hasValue("mine")
        assertThat(page.locator("#input-schema .schema-row")).hasCount(1)
        assertThat(page.locator("#output-schema .schema-row")).hasCount(0)
        assertEquals(false, page.locator("[data-no-output]").evaluate("note => note.hidden"))
        assertThat(editor).hasText("console.log(ants.inputs.text)")
        assertThat(page.getByLabel("Sample inputs, as JSON")).hasValue("""{"mine":""}""")

        page.keyboard().press("ControlOrMeta+y")
        assertThat(page.locator("input[name='inputs[1].rows[0].name']")).hasValue("title")
    }

    @Test
    fun `once the user edits the schema rows after the Script helper's replace, Ctrl-Z no longer swaps the old rows back over the edits`() = browse {
        page.navigate(url(http.newDraft("/scripts")))
        val editor = page.locator(".code-editor .cm-content")
        editor.click()
        page.keyboard().insertText("console.log(ants.inputs.text)")
        val helper = page.getByRole(AriaRole.COMPLEMENTARY, Page.GetByRoleOptions().setName("Script helper"))
        assertThat(helper).hasAttribute("data-token", Pattern.compile(".+"))
        val inputs = mapOf("type" to "object", "properties" to mapOf("text" to mapOf("type" to "string")))
        val (text, failed) = mcpCall(port, helper.getAttribute("data-token")!!, "replace_schemas", mapOf("inputs" to inputs))
        assertFalse(failed, text)
        assertThat(page.locator("input[name='inputs[0].name']")).hasValue("text")
        page.locator("#input-schema > .schema-level > [data-add-row]").click()
        page.locator("input[name='inputs[1].name']").fill("channel")

        editor.click()
        page.keyboard().press("ControlOrMeta+z")
        page.keyboard().press("ControlOrMeta+z")
        assertThat(page.locator("textarea#code")).hasValue("")
        assertThat(page.locator("input[name='inputs[0].name']")).hasValue("text")
        assertThat(page.locator("input[name='inputs[1].name']")).hasValue("channel")
        page.keyboard().press("ControlOrMeta+y")
        page.keyboard().press("ControlOrMeta+y")
        assertThat(editor).hasText("console.log(ants.inputs.text)")
        assertThat(page.locator("input[name='inputs[1].name']")).hasValue("channel")
    }

    @Test
    fun `the Secrets card adds two Secrets for one Save, and its ✕ asks first, so Cancel keeps a Secret and Delete removes it once saved`() = browse {
        http.publishNew("/scripts", listOf("name" to "post", "description" to "Posts.", "code" to "console.log('hi')\n", "inputs" to "", "vars" to "",
            "secrets[0].name" to "OLD_TOKEN", "secrets[0].value" to "tok-old", "secrets[0].was" to ""))
        val folder = home.resolve("scripts").toFile().listFiles()!!.single { "name: post" in it.resolve("script.yaml").readText() }.toPath()
        fun ownSecrets() = org.yaml.snakeyaml.Yaml().load<Map<*, *>?>(folder.resolve("secrets.yaml").toFile().takeIf { it.exists() }?.readText() ?: "").orEmpty()
        fun listed() = org.yaml.snakeyaml.Yaml().load<Map<*, *>>(folder.resolve("script.yaml").readText())["secrets"]
        page.navigate(url("/scripts/post"))
        val card = page.locator(card("Secrets"))
        val rows = card.locator(".listed > .row")
        assertThat(rows).hasCount(2)

        card.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Add Secret")).click()
        card.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Add Secret")).click()
        assertThat(rows).hasCount(4)
        rows.nth(2).getByLabel("Name").fill("A_TOKEN")
        rows.nth(2).getByLabel("Value").fill("a-1")
        rows.nth(3).getByLabel("Name").fill("B_TOKEN")
        rows.nth(3).getByLabel("Value").fill("b-2")

        val confirm = page.locator("dialog.modal.confirm")
        rows.first().locator("button.x").click()
        assertThat(confirm).containsText("Delete the Secret OLD_TOKEN? Its value can't be shown again.")
        confirm.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Cancel")).click()
        assertThat(confirm).hasCount(0)
        assertThat(rows).hasCount(4)
        assertThat(rows.first().getByLabel("Name")).hasValue("OLD_TOKEN")

        rows.first().locator("button.x").click()
        confirm.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Delete")).click()
        assertThat(rows).hasCount(3)
        assertThat(card.getByLabel("Name").first()).hasValue("")
        assertEquals(mapOf("OLD_TOKEN" to "tok-old"), ownSecrets())
        assertEquals(listOf("OLD_TOKEN"), listed())
        page.screenshot("script-secrets")

        page.locator(".code-editor .cm-content").click()
        page.keyboard().press("ControlOrMeta+End")
        page.keyboard().insertText("console.log('unpublished')") // the Draft's code, which Save Secrets leaves alone
        assertThat(saveState).hasText(draftSaved)
        card.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Save Secrets")).click()
        assertThat(card.locator("[data-secrets-state]")).hasText(Pattern.compile("^Secrets saved \\d\\d:\\d\\d$"))
        assertEquals(mapOf("A_TOKEN" to "a-1", "B_TOKEN" to "b-2"), ownSecrets())
        assertEquals(listOf("A_TOKEN", "B_TOKEN"), listed())
        assertEquals("console.log('hi')\n", folder.resolve("script.mjs").readText())
        assertThat(page).hasURL(url("/scripts/post"))
        assertThat(rows.nth(1).getByLabel("Value")).hasAttribute("placeholder", "••••")
        assertThat(rows.nth(1).getByLabel("Value")).hasValue("")
    }

    @Test
    fun `a Secret saved on a Draft-only Script's card survives a reload and is there after Publish, and a refused Save says why`() = browse {
        http.get("/scripts")
        page.navigate(url(http.post("/scripts/new/draft", emptyMap()).path))
        assertThat(marker).hasText("Draft")
        page.fill("#name", "kept-token")
        page.waitForURL(url("/scripts/kept-token"))
        val card = page.locator(card("Secrets"))
        val rows = card.locator(".listed > .row")
        val save = card.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Save Secrets"))
        val state = card.locator("[data-secrets-state]")

        rows.first().getByLabel("Name").fill("TOKEN")
        save.click()
        assertThat(state).hasText("Not saved: The Secret TOKEN needs a value.")
        rows.first().getByLabel("Value").fill("t0ps3cret")
        save.click()
        assertThat(state).hasText(Pattern.compile("^Secrets saved \\d\\d:\\d\\d$"))
        assertThat(saveState).hasText(draftSaved) // the name typed reached the Draft too, with no value

        page.reload()
        assertThat(marker).hasText("Draft")
        assertThat(rows.first().getByLabel("Name")).hasValue("TOKEN")
        assertThat(rows.first().getByLabel("Value")).hasAttribute("placeholder", "••••")
        assertFalse("t0ps3cret" in home.resolve("drafts/scripts").toFile().walk().filter { it.isFile }.joinToString { it.readText() })

        page.fill("#description", "Keeps its token.")
        page.locator(".code-editor .cm-content").click()
        page.keyboard().insertText("ants.output({})")
        assertThat(saveState).hasText(draftSaved)
        page.click("button[form=script]")
        page.waitForURL(url("/scripts/kept-token"))
        assertThat(marker).hasText("Published")
        val folder = home.resolve("scripts").toFile().listFiles()!!.single { it.resolve("script.yaml").exists() && "Keeps its token." in it.resolve("script.yaml").readText() }
        assertEquals("TOKEN: t0ps3cret\n", folder.resolve("secrets.yaml").readText())
    }

    @Test
    fun `picking an Agent picks its first Skill and says what later Tasks can read, and the Checks turn Ready to deploy`() = browse {
        agentTask(null, mapOf("draft" to null, "polish" to null))
        val task = appendAgentTask()
        assertThat(page.locator("#summary")).hasText(Pattern.compile("\\d+ to fix"))
        label("Agent").selectOption("writer")
        assertThat(label("draft")).isChecked()
        assertThat(panel).containsText("Later Tasks can read: ${'$'}{$task.summary}")
        assertThat(page.locator("#summary")).hasText("Ready to deploy")
        page.screenshot("modeler-agent-task")
    }

    /** The Agent Task panel's Skill checkboxes. */
    private val skillBoxes get() = page.locator("#explain .skillpick input[type=checkbox]")

    /** The diagram the page sends to the Checks once [action] has changed it so that [sent] holds. */
    private fun checked(sent: (String) -> Boolean, action: () -> Unit) =
        bpmnOf(page.waitForRequest({ it.url().endsWith("/processes/checks") && sent(bpmnOf(it.postData())) }, action).postData())

    @Test
    fun `an Assignment, Skills and an input set in the Agent Task panel are saved on the Task and reach the next launch`() = browse {
        val claude = FakeClaude()
        agentTask(claude, mapOf("draft" to null, "polish" to null, "extra" to null))
        home.resolve("skills/unlisted").createDirectories().resolve("SKILL.md").writeText("---\nname: unlisted\ndescription: Not the writer's.\n---\nNo.\n")
        http.import("agent-task.bpmn")
        page.navigate(url("/processes/agent-task/edit"))
        shape("draft").click()
        // The Task's one listed Skill is ticked; the picker offers only the Agent's.
        assertThat(label("Skills")).hasValue("some")
        assertThat(page.locator("#explain .skillpick label")).hasText(arrayOf("draft", "polish", "extra"))
        assertThat(label("draft")).isChecked()
        label("polish").check()
        assertThat(page.locator("#checks")).containsText("Draft (draft) has no Assignment and doesn't list exactly one Skill.")
        page.locator("#ants-assignment + .ed .cm-content").fill("Greet the user.\nKeep it short.")
        assertThat(page.locator("#summary")).hasText("Ready to deploy")
        page.locator("#ants-new-input").fill("audience")
        button("＋ Add input").click()
        panel.locator("label.row", Locator.LocatorOptions().setHasText("audience")).locator("input").fill("friends")
        page.screenshot("modeler-assignment")
        page.locator("#save").click()
        assertThat(page).hasURL(Pattern.compile(".*/processes/agent-task$"))
        val saved = http.get("/processes/agent-task/diagram.bpmn").body
        assertContains(saved, """name="ants.skills" value="draft, polish"""")

        claude.session("draft", """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Greeted.","output":{}}}""")
        http.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello"))
        eventually { assertEquals(1, claude.launches("draft").size) }
        val launch = claude.launches("draft").single()
        assertContains(launch.stdin, "Greet the user.\\nKeep it short.")
        assertContains(launch.stdin, """\"audience\":\"friends\"""")
        assertEquals(setOf("draft", "polish"), launch.skills.keys)
    }

    @Test
    fun `the Skills picker saves none as an empty list and the Agent's Skills as no list`() = browse {
        agentTask(null, mapOf("draft" to null, "polish" to null))
        http.import("agent-task.bpmn")
        page.navigate(url("/processes/agent-task/edit"))
        shape("draft").click()
        checked({ "name=\"ants.skills\" value=\"\"" in it }) { label("Skills").selectOption("none") }
        assertThat(skillBoxes).hasCount(2)
        for (box in skillBoxes.all()) assertThat(box).not().isChecked()
        checked({ "ants.skills" !in it }) { label("Skills").selectOption("agent") }
        for (box in skillBoxes.all()) { assertThat(box).isChecked(); assertThat(box).isDisabled() }
    }

    @Test
    fun `the Level picker sets the Task's own level and clears it back to the Agent's`() = browse {
        agentTask(null, mapOf("draft" to null))
        http.import("agent-task.bpmn")
        page.navigate(url("/processes/agent-task/edit"))
        shape("draft").click()
        assertThat(label("Level")).hasValue("")
        checked({ "name=\"ants.level\" value=\"5\"" in it }) { label("Level").selectOption("5") }
        checked({ "ants.level" !in it }) { label("Level").selectOption("") }
        checked({ "name=\"ants.level\" value=\"1\"" in it }) { label("Level").selectOption("1") }
        shape("end").click()
        shape("draft").click()
        assertThat(label("Level")).hasValue("1")
    }

    @Test
    fun `changing the Agent drops the listed Skills the new Agent doesn't have, and says so`() = browse {
        agentTask(null, mapOf("draft" to null, "polish" to null))
        home.resolve("agents/editor.md").writeText("---\nskills: polish\n---\nEdit.\n")
        http.import("agent-task.bpmn")
        page.navigate(url("/processes/agent-task/edit"))
        shape("draft").click()
        label("polish").check()
        checked({ "name=\"ants.agent\" value=\"editor\"" in it && "name=\"ants.skills\" value=\"polish\"" in it }) { label("Agent").selectOption("editor") }
        assertThat(panel).containsText("Dropped draft: editor doesn't have it.")
        assertThat(page.locator("#explain .skillpick label")).hasText(arrayOf("polish"))
        assertThat(label("polish")).isChecked()
    }

    @Test
    fun `the Agent Task panel shows the current names of what the Task links by id, writes ids with the names beside them, and shows no id`() = browse {
        agentTask(null, mapOf("draft" to null, "polish" to null), agent = "id: a-writer\n")
        home.resolve("skills/draft/ants.yaml").writeText("id: k-draft\n")
        home.resolve("skills/polish/ants.yaml").writeText("id: k-polish\n")
        http.import("agent-task.bpmn")
        home.resolve("agents/writer.md").writeText("---\nid: a-writer\nname: author\nskills: draft, polish\n---\nWrite plainly.\n")
        page.navigate(url("/processes/agent-task/edit"))
        shape("draft").click()
        assertThat(label("Agent")).hasValue("author")
        assertThat(label("draft")).isChecked()
        val sent = checked({ "name=\"ants.skills\" value=\"k-draft, k-polish\"" in it }) { label("polish").check() }
        assertContains(sent, """name="ants.agent" value="a-writer"""")
        assertContains(sent, """name="ants.name.a-writer" value="author"""")
        assertContains(sent, """name="ants.name.k-polish" value="polish"""")
        assertFalse(Regex("a-writer|k-draft|k-polish").containsMatchIn(page.locator("body").innerText()))
    }

    @Test
    fun `New Skill says the Task has no Agent yet, and New Agent comes with the Task's Skill`() = browse {
        agentTask(null)
        appendAgentTask()
        button("New Skill").click()
        assertThat(page.locator("dialog.modal")).containsText("This Task has no Agent yet.")
        page.keyboard().press("Escape")
        label("Agent").selectOption("writer")
        assertThat(label("draft")).isChecked()
        button("New Skill").click()
        assertThat(page.locator("dialog.modal")).containsText("Added to writer's Skills in its Draft, and picked on this Task.")
        page.waitForTimeout(1000.0) // the form loads in its frame
        page.screenshot("modeler-new-skill")
        page.keyboard().press("Escape")
        button("New Agent").click()
        assertThat(page.frameLocator("dialog iframe").locator(".chipsel input[type=hidden][name=skills]")).hasValue("draft") // a chip on the Agent page (#98)
    }

    @Test
    fun `on a shipped Agent the Skill picker offers Your Skills, and New Skill adds itself to them`() = browse {
        agentTask(null)
        home.resolve("skills/imported").createDirectories().resolve("SKILL.md").writeText("---\nname: imported\ndescription: Imported.\n---\nDo it.\n")
        http.get("/agents/shipped-writer")
        http.post("/agents/shipped-writer/your-skills", mapOf("skills" to "imported"))
        try {
            appendAgentTask()
            label("Agent").selectOption("shipped-writer")
            assertThat(page.locator("#explain .skillpick label")).hasText(arrayOf("imported"))
            button("New Skill").click()
            assertThat(page.locator("dialog.modal")).containsText("Added to Your Skills on shipped-writer in their Draft, and picked on this Task.")
        } finally {
            http.post("/agents/shipped-writer/your-skills", emptyMap())
        }
    }

    @Test
    fun `a User Task asks for its kind's fields and says what later Tasks can read`() = browse {
        newProcess("Gated")
        shape("start").click()
        pad.locator("[data-action='append.user-task']").click()
        val task = page.locator("#canvas .djs-shape.selected").getAttribute("data-element-id")!!
        assertThat(heading).hasText("User Task")
        label("What you do").selectOption("Review changes")
        assertThat(label("Walkthrough")).isVisible()
        assertThat(panel).containsText("${'$'}{$task.comments}")
        label("What you do").selectOption("Approve")
        label("Title").fill("Ship it?")
        label("Title").press("Tab")
        assertThat(panel).containsText("Later Tasks and decisions can read: ${'$'}{$task.approved} ${'$'}{$task.comment}")
        page.screenshot("modeler-user-task")
        label("What you do").selectOption("Answer a Round")
        assertThat(label("Asker")).isVisible()
        label("What you do").selectOption("Approve")
        assertThat(label("Title")).hasValue("") // switching kind dropped the old kind's fields
        label("Title").fill("Ship it?")
        label("Title").press("Tab")
        page.locator("#save").click()
        assertThat(page).hasURL(Pattern.compile(".*/processes/gated$"))
        val saved = http.get("/processes/gated/diagram.bpmn").body
        assertContains(saved, """<operaton:inputParameter name="title">Ship it?</operaton:inputParameter>""")
        assertFalse("ants.user-task" in saved)
    }

    @Test
    fun `a decision's paths pick a condition from what the decision can read, such as the Results of the Tasks before it, and one is the default`() = browse {
        agentTask(null, mapOf("draft" to """{"type":"object","properties":{"verdict":{"type":"string"}}}"""))
        val (process, plane) = row("decided", """<bpmn:startEvent id="start" />""", """<bpmn:userTask id="ask" name="Ask" />""", agentTaskXml("draft", "Draft"),
            """<bpmn:exclusiveGateway id="ok" name="Ok?" default="ok-end" />""", """<bpmn:endEvent id="end" />""")
        val back = """<bpmn:sequenceFlow id="ok-draft" sourceRef="ok" targetRef="draft"><bpmn:conditionExpression>${'$'}{!ask.approved}</bpmn:conditionExpression></bpmn:sequenceFlow>"""
        val edge = """<bpmndi:BPMNEdge id="ok-draft_di" bpmnElement="ok-draft"><di:waypoint x="600" y="80" /><di:waypoint x="600" y="40" />""" +
            """<di:waypoint x="450" y="40" /><di:waypoint x="450" y="80" /></bpmndi:BPMNEdge>"""
        deploy(definitions("decided", "Decided", process + back, plane.replace("</bpmndi:BPMNPlane>", "$edge</bpmndi:BPMNPlane>")))

        page.navigate(url("/processes/decided/edit"))
        shape("ok").click()
        assertThat(heading).hasText("Decision (exclusive gateway)")
        assertThat(panel).containsText("Paths out (2)")
        val toDraft = page.locator("#explain .card", Page.LocatorOptions().setHasText("→ Draft"))
        val field = toDraft.locator("select").first()
        assertThat(field.locator("option[value='draft.output.verdict']")).hasText("draft.output.verdict (from Draft)")
        assertThat(field.locator("option[value='ask.approved']")).hasText("ask.approved (from Ask)")
        assertThat(field.locator("option[value='clones']")).hasText("clones (Ants' own)")
        field.selectOption("draft.output.verdict")
        toDraft.locator("select").nth(1).selectOption("equals")
        toDraft.getByLabel("Value").fill("redo")
        toDraft.getByLabel("Value").press("Tab")
        assertThat(toDraft).containsText("${'$'}{draft.output.verdict == 'redo'}")
        page.screenshot("modeler-decision")

        toDraft.locator("button", Locator.LocatorOptions().setHasText("Select")).click()
        assertThat(heading).hasText("Path")
        button("See every path out of Ok?").click()
        assertThat(heading).hasText("Decision (exclusive gateway)")
        page.locator("#save").click()
        assertThat(page).hasURL(Pattern.compile(".*/processes/decided$"))
        assertContains(http.get("/processes/decided/diagram.bpmn").body, "draft.output.verdict == 'redo'")
    }

    @Test
    fun `a parallel gateway says whether it splits or joins, and an element Ants doesn't run offers what to change it to`() = browse {
        val (process, plane) = row("odd", """<bpmn:startEvent id="start" />""", """<bpmn:parallelGateway id="fork" />""", """<bpmn:scriptTask id="script" name="Script" />""", """<bpmn:endEvent id="end" />""")
        val aside = """<bpmn:sequenceFlow id="fork-end" sourceRef="fork" targetRef="end" />"""
        val edge = """<bpmndi:BPMNEdge id="fork-end_di" bpmnElement="fork-end"><di:waypoint x="300" y="160" /><di:waypoint x="300" y="200" />""" +
            """<di:waypoint x="568" y="200" /><di:waypoint x="568" y="138" /></bpmndi:BPMNEdge>"""
        home.resolve("templates").createDirectories().resolve("odd.bpmn")
            .writeText(definitions("odd", "Odd", process + aside, plane.replace("</bpmndi:BPMNPlane>", "$edge</bpmndi:BPMNPlane>")))

        page.navigate(url("/templates/odd"))
        shape("fork").click()
        assertThat(heading).hasText("Parallel gateway")
        assertThat(panel).containsText("Splits the flow: all 2 branches start at once.")
        assertThat(panel).containsText("→ Script")
        shape("script").click()
        assertThat(panel).containsText("Ants doesn't run this element")
        assertThat(panel).containsText("Change it to:")
        page.screenshot("modeler-not-run")
        button("Agent Task").click()
        assertThat(heading).hasText("Agent Task")
    }

    @Test
    fun `on the Run page a call activity opens the called Process, and the breadcrumb leads back`() = browse {
        settings(null)
        val (called, calledPlane) = row("called", """<bpmn:startEvent id="start" />""", """<bpmn:userTask id="review" name="Review" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("called", "Called", called, calledPlane))
        val (caller, callerPlane) = row("caller", """<bpmn:startEvent id="start" />""", """<bpmn:callActivity id="build" name="Build" calledElement="called" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("caller", "Caller", caller, callerPlane))
        val run = http.post("/processes/caller/runs", mapOf(NAME to "Tiny caller")).path
        assertTrue(run.startsWith("/runs/"), run)

        page.navigate(url(run))
        // A click opens the called Process once the live update has named it.
        eventually {
            shape("build").click()
            assertTrue(page.isVisible("#trail"), "no breadcrumb after clicking the call activity")
        }
        assertThat(shape("review")).isVisible()
        assertThat(page.locator("#trail")).hasText("Tiny caller › Build") // the Run by its name
        page.click("#trail a")
        assertThat(shape("build")).isVisible()
        assertThat(shape("review")).hasCount(0)
        assertThat(page.locator("#trail")).isHidden()
    }

    @Test
    fun `on the Run page an Agent Task that retried shows its model chip with its badge, and its detail on hover`() = browse {
        val claude = FakeClaude()
        agentTask(claude, agent = "level: 3\n", retries = 2)
        claude.session("draft", 1, """{"type":"result","subtype":"error_max_turns","is_error":true,"session_id":"${'$'}SESSION"}""")
        claude.session("draft", """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Drafted.","output":{"text":"Hello"}}}""")
        http.import("agent-task.bpmn")
        val run = http.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path

        page.navigate(url(run))
        val chip = page.locator("#tasks tr:has-text('Draft') .model")
        assertThat(chip).containsText("↻ 1/2")
        assertThat(chip).containsText("opus")
        assertThat(chip.locator("svg.cli-claude")).isVisible()
        assertThat(chip.locator(".bars i")).hasCount(5)
        chip.hover()
        assertThat(chip).hasAttribute("title", "opus · high effort · 1 of 2 retries")
        page.screenshot("run-model-chip")
    }

    @Test
    fun `ticking a checkbox on a Ticket's page saves it, and the page follows the file as an Agent changes it`() = browse {
        settings(null)
        val (process, plane) = row("tracked", """<bpmn:startEvent id="start" />""", """<bpmn:userTask id="wait" name="Wait" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("tracked", "Tracked", process, plane))
        val run = http.post("/processes/tracked/runs", mapOf()).path.removePrefix("/runs/")
        val file = workspace(run).resolve("tickets/issues").createDirectories().resolve("01-hello.md")
        file.writeText("# Say hello\n\nStatus: ready-for-agent\n\n- [ ] Says hello\n")

        page.navigate(url("/runs/$run/tickets/01-hello"))
        page.check("#ticket input[type=checkbox]")

        eventually { assertEquals("# Say hello\n\nStatus: ready-for-agent\n\n- [x] Says hello\n", file.readText()) }
        assertThat(page.locator("#ticket input[type=checkbox]")).isChecked()
        file.writeText("# Say hello\n\nStatus: in-progress\n\n- [x] Says hello\n") // as an Agent would
        assertThat(page.locator("#ticket")).containsText("Status: in-progress")
        page.screenshot("ticket")
    }

    @Test
    fun `the app badge carries the Notification list count`() = browse {
        settings(null)
        page.addInitScript("navigator.setAppBadge = async count => window.badge = count")
        val (process, plane) = row("badged", """<bpmn:startEvent id="start" />""", """<bpmn:userTask id="wait" name="Wait" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("badged", "Badged", process, plane))
        page.navigate(url("/"))
        fun badge() = (page.evaluate("window.badge") as Number?)?.toInt()
        eventually { assertNotNull(badge()) } // the first count has come
        val before = badge()!!

        http.post("/processes/badged/runs", mapOf())
        assertThat(page.locator("#waiting")).hasText("${before + 1}")
        eventually { assertEquals(before + 1, badge()) }
    }

    @Test
    fun `in dark, the drill-down button opens a collapsed sub-process on the Process page, in the modeler and on the Run page`() = browse {
        val (inside, groupPlane) = row("group", """<bpmn:startEvent id="inner-start" />""", """<bpmn:userTask id="inner" name="Inner" />""", """<bpmn:endEvent id="inner-end" />""")
        val (process, plane) = row("drilled", """<bpmn:startEvent id="start" />""", """<bpmn:subProcess id="group" name="Group">$inside</bpmn:subProcess>""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("drilled", "Drilled", process, plane + groupPlane))
        val run = http.post("/processes/drilled/runs", mapOf()).path
        settings(null, "theme: dark\n")
        for (path in listOf("/processes/drilled", "/processes/drilled/edit", run)) {
            page.navigate(url(path))
            assertThat(shape("group")).isVisible()
            // The inverted svg must not lie over the overlays: a covered button fails Playwright's click.
            page.click(".bjs-drilldown", Page.ClickOptions().setTimeout(5000.0))
            assertThat(shape("inner")).isVisible()
        }
    }

    @Test
    fun `the theme setting switches light, dark and system on every page, the modeler and the diagrams included`() = browse {
        val (process, plane) = row("themed", """<bpmn:startEvent id="start" />""", """<bpmn:userTask id="wait" name="Wait" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("themed", "Themed", process, plane))
        val run = http.post("/processes/themed/runs", mapOf()).path
        val diagrams = listOf("/processes/themed", "/processes/themed/edit", run)
        val pages = listOf("/", "/notifications", "/runs", "/processes", "/agents", http.newDraft("/agents"), "/skills", http.newDraft("/skills"), "/templates", "/tickets", "/settings") + diagrams
        val light = "rgb(244, 245, 247)"
        val dark = "rgb(11, 13, 18)"
        for ((theme, system, expected) in listOf(
            Triple("light", ColorScheme.DARK, light), Triple("dark", ColorScheme.LIGHT, dark),
            Triple("system", ColorScheme.LIGHT, light), Triple("system", ColorScheme.DARK, dark),
        )) {
            settings(null, "theme: $theme\n")
            page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(system))
            for (path in pages) {
                page.navigate(url(path))
                assertThat(page.locator("link[rel=stylesheet][href='/ants.css']")).hasCount(1)
                assertEquals(expected, page.evaluate("getComputedStyle(document.documentElement).backgroundColor"), "$theme on $path")
                if (path in diagrams) {
                    val svg = page.locator(".djs-container > svg")
                    assertThat(svg).isVisible()
                    assertEquals(expected == dark, "invert" in svg.evaluate("it => getComputedStyle(it).filter").toString(), "the diagram in $theme on $path")
                }
                if (theme != "system") page.screenshot("theme/$theme${path.replace(Regex("[^a-z0-9]+"), "-")}")
            }
        }
    }

    @Test
    fun `a row of the Notification list, Home's Waiting on you and the Runs list opens its entry or its Run on a click anywhere in it`() = browse {
        settings(null)
        val (process, plane) = row("clicked", """<bpmn:startEvent id="start" />""", """<bpmn:userTask id="wait" name="Wait" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("clicked", "Clicked", process, plane))
        val run = http.post("/processes/clicked/runs", mapOf()).path
        for (list in listOf("/notifications", "/")) {
            page.navigate(url(list))
            page.locator("tr.click:has(a[href='$run']) td:last-child").click()
            page.waitForURL { "/notifications/" in it }
        }
        page.navigate(url("/runs"))
        page.locator("tr.click[data-href='$run'] td:last-child").click()
        page.waitForURL { it.endsWith(run) }
    }

    @Test
    fun `the Notification list asks for notification permission on a click, since macOS badges an app only with it`() = browse {
        // The browser's answer outlives the page, as a real one's does, so the reload below finds it.
        page.addInitScript(
            """
            navigator.setAppBadge = async () => {};
            Object.defineProperty(Notification, 'permission', { get: () => localStorage.getItem('permission') ?? 'default' });
            Notification.requestPermission = async () => { localStorage.setItem('permission', 'granted'); return 'granted' };
            """,
        )
        page.navigate(url("/notifications"))
        val ask = page.getByText("Show the count on the app icon")

        ask.click()

        eventually { assertEquals("granted", page.evaluate("Notification.permission")) }
        assertThat(ask).isHidden()
        page.reload()
        assertThat(ask).isHidden()
    }

    private fun onPhone(javaScript: Boolean = true) {
        val cookies = page.context().cookies()
        page = page.context().browser()!!.newContext(
            NewContextOptions().setViewportSize(390, 844).setIsMobile(true).setHasTouch(true).setJavaScriptEnabled(javaScript),
        ).apply { addCookies(cookies) }.newPage().also(::watch)
    }

    // A modal and a toast fade in from 4px lower, so they are measured once their animations end.
    private fun settled(element: Locator) = element.evaluate("e => Promise.all(e.getAnimations().map(a => a.finished))")

    private fun assertNoSidewaysScroll() {
        val (scroll, client) = (page.evaluate("[document.documentElement.scrollWidth, document.documentElement.clientWidth]") as List<*>).map { (it as Number).toInt() }
        assertEquals(page.viewportSize().width, client, "${page.url()} renders at the screen's width")
        assertTrue(scroll <= client, "${page.url()} is ${scroll}px wide on a ${client}px screen")
    }

    private fun waitingRun(key: String): String {
        settings(null)
        val (process, plane) = row(key, """<bpmn:startEvent id="start" />""", """<bpmn:userTask id="wait" name="Wait" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions(key, key.replaceFirstChar(Char::uppercase), process, plane))
        return http.post("/processes/$key/runs", mapOf()).path
    }

    private val topBar get() = page.locator("body > header")
    private val sidebar get() = page.locator("nav.side")
    private val menu get() = page.getByText("☰", Page.GetByTextOptions().setExact(true))

    @Test
    fun `on a phone Home and the Notification list fit the screen with 16px side padding, Home's cards in one column, and an entry opens on a tap`() = browse {
        val run = waitingRun("phoned")
        onPhone()
        for (path in listOf("/", "/notifications")) {
            page.navigate(url(path))
            assertThat(page.locator("tr.click:has(a[href='$run'])")).isVisible()
            assertNoSidewaysScroll()
            val (left, right) = contentEdges("main")
            assertEquals(16.0, left, 0.5, "main's content starts 16px in on $path")
            assertEquals(374.0, right, 0.5, "main's content ends 16px in on $path")
            page.screenshot("phone${path.replace(Regex("[^a-z0-9]+"), "-")}")
        }

        page.navigate(url("/"))
        assertStacked("/", "#stats", "#waiting-on-you", "#working-now", "#coming-up", "#start-a-run")

        page.navigate(url("/notifications"))
        page.locator("tr.click:has(a[href='$run']) td").last().tap()
        page.waitForURL { "/notifications/" in it }
    }

    @Test
    fun `on a phone the top bar shows Ants and the live Notification list count, and the sidebar isn't beside the content`() = browse {
        waitingRun("counted")
        onPhone()
        page.navigate(url("/runs"))
        assertThat(topBar).containsText("Ants")
        assertEquals(0.0, topBar.boundingBox().y, 0.5)
        assertEquals(390.0, topBar.boundingBox().width, 0.5)
        val count = page.locator("#topbar-waiting")
        assertThat(count).hasText(Pattern.compile("\\d+"))
        val before = count.textContent().toInt()

        http.post("/processes/counted/runs", mapOf())

        assertThat(count).hasText("${before + 1}")
        assertThat(sidebar).isHidden()
        val main = page.locator("main").boundingBox()
        assertEquals(0.0, main.x, 0.5)
        assertEquals(390.0, main.width, 0.5)
    }

    @Test
    fun `on a phone ☰ opens the sidebar as a drawer with the page marked, which closes on a tap outside, on Esc and on following a link`() = browse {
        onPhone()
        page.navigate(url("/runs"))
        menu.tap()
        assertThat(sidebar).isVisible()
        eventually { assertEquals(0.0, sidebar.boundingBox().x, 0.5) }
        fun link(name: String) = sidebar.getByRole(AriaRole.LINK, Locator.GetByRoleOptions().setName(name))
        for (name in listOf("Home", "Notification list", "Runs", "Processes", "Tickets", "Agents", "Skills", "Templates", "Settings")) assertThat(link(name)).isVisible()
        assertThat(sidebar.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Log out"))).hasText(Pattern.compile("Log out v\\S+"))
        assertThat(sidebar.locator("button[name=theme]:visible")).hasCount(1)
        for (notice in listOf("#runs", "#paused", "#update")) assertThat(sidebar.locator(notice)).hasCount(1)
        fun background(name: String) = link(name).evaluate("a => getComputedStyle(a).backgroundColor")
        assertNotEquals(background("Processes"), background("Runs"), "the drawer marks Runs, the page it is on")
        page.screenshot("phone/drawer")

        page.touchscreen().tap(370.0, 600.0)
        assertThat(sidebar).isHidden()
        assertTrue(page.url().endsWith("/runs"), "a tap outside the drawer only closes it")

        menu.tap()
        assertThat(sidebar).isVisible()
        page.keyboard().press("Escape")
        assertThat(sidebar).isHidden()

        menu.tap()
        link("Processes").tap()
        page.waitForURL { it.endsWith("/processes") }
        assertThat(sidebar).isHidden()
    }

    @Test
    fun `on a phone with JavaScript off ☰ still opens and closes the menu`() = browse {
        onPhone(javaScript = false)
        page.navigate(url("/runs"))
        assertThat(sidebar).isHidden()
        menu.tap()
        assertThat(sidebar).isVisible()
        assertThat(sidebar.getByRole(AriaRole.LINK, Locator.GetByRoleOptions().setName("Processes"))).isVisible()
        menu.tap()
        assertThat(sidebar).isHidden()
    }

    @Test
    fun `on a phone the top bar and the drawer follow light and dark mode as the desktop sidebar does`() = browse {
        onPhone()
        fun background(part: Locator) = part.evaluate("e => getComputedStyle(e).backgroundColor")
        for ((theme, pageBackground) in listOf("light" to "rgb(244, 245, 247)", "dark" to "rgb(11, 13, 18)")) {
            settings(null, "theme: $theme\n")
            page.setViewportSize(1024, 844)
            page.navigate(url("/runs"))
            val desktop = background(sidebar)
            page.setViewportSize(390, 844)
            menu.tap()
            assertThat(sidebar).isVisible()
            assertEquals(pageBackground, page.evaluate("getComputedStyle(document.documentElement).backgroundColor"), "the page in $theme")
            assertEquals(desktop, background(sidebar), "the drawer in $theme")
            assertEquals(desktop, background(topBar), "the top bar in $theme")
            assertEquals("rgb(255, 255, 255)", topBar.getByText("Ants").evaluate("e => getComputedStyle(e).color"), "Ants in $theme")
            page.screenshot("phone/drawer-$theme")
        }
    }

    @Test
    fun `above 768px the top bar is hidden and the sidebar sits beside the content`() = browse {
        page.setViewportSize(769, 900)
        page.navigate(url("/runs"))
        assertThat(topBar).isHidden()
        assertThat(sidebar).isVisible()
        assertEquals(232.0, sidebar.boundingBox().width, 0.5)
        assertEquals(232.0, page.locator("main").boundingBox().x, 0.5)
        page.setViewportSize(768, 900)
        assertThat(topBar).isVisible()
        assertThat(sidebar).isHidden()
    }

    private val tapTargets = "button, .btn, .topbar a, .topbar label, nav.side a, .tabs a, .fold > summary, .spine a, .walk nav a, .crumbs a, :is(.chipsel, .tags) .c, [role=tab]"
    private val textFields = "input:not([type=checkbox], [type=radio], [type=hidden], [type=file]), select, textarea, .cm-content"

    // Measured once the page is whole: parsed, and each Markdown field turned into its editor, which markdown.js does
    // after CodeMirror's modules load; before then the textarea stands in for the editor, so the editor went unmeasured.
    private fun visible(selector: String, measure: String): List<Pair<String, Any?>> {
        page.waitForFunction("document.readyState === 'complete' && !document.querySelector('textarea[data-markdown]:not([data-mounted])')")
        return (page.locator(selector).evaluateAll(
            "es => es.filter(e => e.checkVisibility({visibilityProperty: true})).map(e => [e.outerHTML.slice(0, 100), $measure])",
        ) as List<*>).map { (it as List<*>).let { (html, value) -> html as String to value } }.also { assertTrue(it.isNotEmpty(), "${page.url()} shows a $selector") }
    }

    private fun assertTapSized() {
        val short = visible(tapTargets, "e.getBoundingClientRect().height").filter { (_, height) -> (height as Number).toDouble() < 44 }
        assertEquals(listOf<Pair<String, Any?>>(), short, "on ${page.url()} these are under 44px tall")
    }

    private fun assertFieldText(size: String) {
        val other = visible(textFields, "getComputedStyle(e).fontSize").filter { (_, font) -> font != size }
        assertEquals(listOf<Pair<String, Any?>>(), other, "on ${page.url()} these don't have $size text")
    }

    private fun chipper() {
        home.resolve("skills/chip-skill").createDirectories().resolve("SKILL.md").writeText("---\nname: chip-skill\ndescription: Chips.\n---\nChip.\n")
        home.resolve("agents").createDirectories().resolve("chipper.md").writeText("---\nname: chipper\ncli: claude\nskills: chip-skill\ntools: Read\n---\nChip.\n")
    }

    @Test
    fun `on a phone buttons, nav links, chips with their ×, question chips and the diagram's zoom buttons are at least 44px tall`() = browse {
        val (_, entry) = answering(FakeClaude(), "who" to "Title who", "scope" to "Title scope")
        val run = waitingRun("tapped")
        chipper()
        onPhone()
        for (path in listOf("/", "/notifications", run, "/processes/tapped", entry, "/agents/chipper", http.newDraft("/agents"), http.newDraft("/skills"), "/settings")) {
            page.navigate(url(path))
            assertTapSized()
        }

        page.navigate(url("/runs"))
        menu.tap()
        assertThat(sidebar).isVisible()
        assertTapSized()

        picker()
        repoBox.tap()
        repoOption("api").tap()
        repoBox.blur()
        assertThat(repoTags).hasText(arrayOf("api×"))
        assertTapSized()
    }

    @Test
    fun `on a phone Review changes' tabs, story and step links, file links and breadcrumbs are at least 44px tall`() = browse {
        val entry = staleStoryReviewEntry()
        onPhone()
        for (tab in listOf("story", "repo")) {
            page.navigate(url("$entry?tab=$tab"))
            assertTapSized()
        }
    }

    @Test
    fun `on a phone inputs, selects, textareas and the Markdown editors have 16px text, so iOS doesn't zoom in on them, and above 768px they keep theirs`() = browse {
        val (_, entry) = answering(FakeClaude(), "who" to "Title who")
        chipper()
        val pages = listOf(entry, "/agents/chipper", http.newDraft("/agents"), http.newDraft("/skills"), "/settings")
        onPhone()
        for (path in pages) {
            page.navigate(url(path))
            assertFieldText("16px")
        }
        picker()
        assertFieldText("16px")

        page.setViewportSize(769, 900)
        page.navigate(url(http.newDraft("/agents")))
        assertEquals("14px", page.locator("input[name=name]").evaluate("e => getComputedStyle(e).fontSize"))
        assertEquals("14px", page.locator("textarea[name=instructions] + .ed .cm-content").evaluate("e => getComputedStyle(e).fontSize"))
        assertTrue(page.locator("textarea[name=instructions] + .ed .bar button").first().boundingBox().height < 44, "the editor's toolbar keeps its desktop size")
    }

    @Test
    fun `on a phone a tap on a shape in the modeler opens its hover menu, which appends a Task on a tap`() = browse {
        onPhone()
        newProcess("Tapped")
        shape("start").tap()
        assertThat(pad.locator("[data-action='append.agent-task']")).isVisible()
        pad.locator("[data-action='append.agent-task']").tap()
        assertThat(heading).hasText("Agent Task")
    }

    private fun visibleBox(selector: String) = page.locator(selector).first().also { assertThat(it).isVisible() }.boundingBox()

    private fun card(title: String) = ".card:has(> h3:text-is('$title'))"

    private fun assertStacked(path: String, vararg parts: String) {
        val boxes = parts.map { it to visibleBox(it) }
        for ((above, below) in boxes.zipWithNext()) {
            assertEquals(above.second.x, below.second.x, 0.5, "${below.first} starts where ${above.first} starts on $path")
            assertEquals(above.second.width, below.second.width, 0.5, "${below.first} is as wide as ${above.first} on $path")
            assertTrue(below.second.y >= above.second.y + above.second.height, "${below.first} sits under ${above.first} on $path")
        }
    }

    @Test
    fun `on a phone the modeler, a Skill, the Agent form, Templates and Settings fit the screen in one column`() = browse {
        http.import("start-end.bpmn")
        settings(null, "vaults:\n  work: /tmp/work\n  side: /tmp/side\ndefault-vault: work\nagent-folders: [/tmp/a]\n")
        home.resolve("agents").createDirectories().resolve("stacked.md").writeText("---\nname: stacked\ncli: claude\nskills: stacked\ntools: Read, Bash(git log *)\n---\nStacked.\n")
        home.resolve("skills/stacked").createDirectories().resolve("SKILL.md").writeText("---\nname: stacked\ndescription: Stacked.\n---\n" + "A long line of the Skill. ".repeat(40) + "\n")
        val pages = listOf(
            "/processes/start-end/edit" to arrayOf("#canvas", ".props"),
            "/templates/review-and-push" to arrayOf("#canvas", ".props"),
            "/templates" to arrayOf("main form", "main ul"),
            "/skills/stacked" to arrayOf(".card:has(#skill)", card("Run by")),
            http.newDraft("/skills") to arrayOf(".card:has(#skill)", card("Run by")),
            "/agents/stacked" to arrayOf("#name", "#description", "#cli", "#model"),
            http.newDraft("/agents") to arrayOf("#name", "#description", "#cli", "#model"),
            "/agents/engineer" to arrayOf("#name", "#description", "#cli", "#model"),
            "/settings" to arrayOf(card("Claude login"), card("Commands"), card("Vaults"), card("Agent folders"), card("So far"), card("Theme"), card("Server"), card("Updates")),
        )
        onPhone()
        for ((path, parts) in pages) {
            page.navigate(url(path))
            eventually { assertStacked(path, *parts) } // a markdown editor replaces its taller textarea once its module loads
            assertNoSidewaysScroll()
            page.screenshot("phone${path.replace(Regex("[^a-z0-9]+"), "-")}")
        }
    }

    @Test
    fun `just above 768px the modeler, the Skill page, the Agent form and Settings keep their columns side by side`() = browse {
        http.import("start-end.bpmn")
        settings(null)
        home.resolve("agents").createDirectories().resolve("beside.md").writeText("---\nname: beside\ncli: claude\n---\nBeside.\n")
        page.setViewportSize(769, 900)
        fun assertBeside(path: String, left: String, right: String) {
            page.navigate(url(path))
            val (leftBox, rightBox) = listOf(left, right).map(::visibleBox)
            assertEquals(leftBox.y, rightBox.y, 1.0, "$right sits level with $left on $path")
            assertTrue(leftBox.x + leftBox.width < rightBox.x, "$right sits beside $left on $path")
        }
        assertBeside("/processes/start-end/edit", "#canvas", ".props")
        assertBeside(http.newDraft("/skills"), ".card:has(#skill)", card("Run by"))
        assertBeside("/agents/beside", "#name", "#description")
        assertBeside("/settings", card("Claude login"), card("Commands"))
    }

    private fun assertWithinScreen(part: Locator, what: String) {
        val box = part.boundingBox()
        assertTrue(box.x >= 0 && box.x + box.width <= 390, "$what spans ${box.x} to ${box.x + box.width} on a 390px screen")
    }

    @Test
    fun `on a phone the Runs list fits the screen, each row within it, and a Run opens on a tap on its row`() = browse {
        val run = waitingRun("listed")
        onPhone()
        page.navigate(url("/runs"))
        val row = page.locator("tr.click[data-href='$run']")
        assertThat(row).isVisible()
        assertNoSidewaysScroll()
        for (cell in row.locator("td").all()) assertWithinScreen(cell, "a cell of the Run's row")
        page.screenshot("phone/runs")

        row.locator("td").last().tap()
        page.waitForURL { it.endsWith(run) }
    }

    @Test
    fun `on a phone the Run page's Task drawer spans the screen, and neither the Run page nor the Process page scrolls sideways`() = browse {
        val run = waitingRun("drawn")
        onPhone()
        page.navigate(url(run))
        assertThat(shape("wait")).isVisible()
        assertNoSidewaysScroll()
        // On a phone the zoom buttons cover the Task's top right, so the tap goes to its bottom left.
        eventually { shape("wait").run { tap(Locator.TapOptions().setPosition(boundingBox().width * 0.15, boundingBox().height * 0.85)) } }
        val drawer = page.locator("#drawer")
        assertThat(page.locator("#drawer-head")).isVisible()
        eventually { assertEquals(0.0, drawer.boundingBox().x, 0.5) }
        assertEquals(390.0, drawer.boundingBox().width, 0.5)
        assertNoSidewaysScroll()
        page.screenshot("phone/run-drawer")

        page.navigate(url("/processes/drawn"))
        assertThat(shape("wait")).isVisible()
        assertNoSidewaysScroll()
        page.screenshot("phone/process")

        page.setViewportSize(769, 900)
        page.navigate(url(run))
        eventually { shape("wait").click() }
        assertThat(page.locator("#drawer-head")).isVisible()
        eventually { assertEquals(460.0, drawer.boundingBox().width, 0.5) }
    }

    @Test
    fun `on a phone the Run page's and the Process page's diagrams fit the screen, and ＋, －, Fit and ⛶ work on a tap`() = browse {
        val run = waitingRun("tapzoom")
        onPhone()
        for (path in listOf(run, "/processes/tapzoom")) {
            page.navigate(url(path))
            assertThat(shape("wait")).isVisible()
            val diagram = page.locator(".dia").first()
            assertWithinScreen(diagram, "the diagram on $path")
            assertWithinScreen(diagram.locator(".zoom"), "the zoom buttons on $path")
            assertWithinScreen(diagram.locator(".legend"), "the legend on $path")
            fun zoom() = (diagram.evaluate("e => e.canvas.zoom()") as Number).toDouble()
            eventually { assertEquals(true, diagram.evaluate("e => e.canvas != null"), "the diagram on $path is drawn") }
            diagram.locator(".zoom [title='Fit']").tap()
            val fit = zoom()

            diagram.locator(".zoom [title='Zoom in']").tap()
            assertEquals(fit * 1.25, zoom(), 0.001, "＋ on $path")
            diagram.locator(".zoom [title='Zoom out']").tap()
            diagram.locator(".zoom [title='Zoom out']").tap()
            assertEquals(fit / 1.25, zoom(), 0.001, "－ on $path")
            diagram.locator(".zoom [title='Fit']").tap()
            assertEquals(fit, zoom(), 0.001, "Fit on $path")

            diagram.locator(".zoom [title='Fullscreen']").tap()
            eventually { assertEquals(true, diagram.evaluate("e => document.fullscreenElement === e"), "⛶ makes the diagram on $path fullscreen") }
            diagram.locator(".zoom [title='Fullscreen']").tap()
            eventually { assertEquals(true, page.evaluate("document.fullscreenElement === null"), "⛶ again leaves fullscreen on $path") }
        }
    }

    @Test
    fun `on a phone that can't make a diagram fullscreen, as an iPhone can't, the diagram offers ＋, － and Fit without ⛶`() = browse {
        val run = waitingRun("unfull")
        onPhone()
        page.addInitScript("Object.defineProperty(Document.prototype, 'fullscreenEnabled', { get: () => false })")
        for (path in listOf(run, "/processes/unfull")) {
            page.navigate(url(path))
            assertThat(shape("wait")).isVisible()
            assertThat(page.locator(".dia .zoom button")).hasText(arrayOf("＋", "－", "Fit"))
        }
    }

    @Test
    fun `on a phone a modal spans the screen with its editor and preview stacked, and a toast spans the screen at the top`() = browse {
        onPhone()
        page.navigate(url(http.newDraft("/agents")))
        page.locator("textarea[name=instructions] + .ed").locator("button", Locator.LocatorOptions().setHasText("Expand")).tap()
        val modal = page.locator("dialog.modal")
        assertThat(modal.locator(".split .cm-content")).isVisible()
        settled(modal)
        assertEquals(0.0, modal.boundingBox().x, 0.5)
        assertEquals(390.0, modal.boundingBox().width, 0.5)
        val (editor, preview) = modal.locator(".split > *").all().map { it.boundingBox() }
        assertEquals(390.0, editor.width, 0.5, "the editor spans the modal")
        assertEquals(390.0, preview.width, 0.5, "the preview spans the modal")
        assertTrue(preview.y >= editor.y + editor.height - 0.5, "the preview sits under the editor")
        assertTrue(preview.height > 100, "the preview keeps room to read, ${preview.height}px tall")
        page.screenshot("phone/modal")
        modal.locator("button[title=Close]").tap()
        assertThat(modal).hasCount(0)

        page.navigate(url("/settings"))
        page.locator("button[name=action][value=updates]").tap()
        val toast = page.locator(".toast")
        assertThat(toast).containsText("Up to date")
        settled(toast)
        val spot = toast.boundingBox()
        assertEquals(0.0, spot.x, 0.5)
        assertEquals(390.0, spot.width, 0.5)
        assertEquals(0.0, spot.y, 0.5)
        assertNoSidewaysScroll()
        page.screenshot("phone/toast")

        page.setViewportSize(769, 900)
        assertEquals(380.0, toast.boundingBox().width, 0.5)
        toast.locator("button[aria-label=Close]").tap()
        assertThat(toast).hasCount(0)
    }

    @Test
    fun `a reload or a submit back to the same page keeps the scroll position, and a page opened from the sidebar starts at the top`() = browse {
        settings(null)
        fun scrolled() = (page.evaluate("scrollY") as Number).toInt()
        page.navigate(url("/settings"))
        val checkNow = page.locator("button[name=action][value=updates]")
        checkNow.scrollIntoViewIfNeeded()
        val place = scrolled()
        assertTrue(place > 0, "Settings is long enough to scroll")

        page.reload()
        eventually { assertEquals(place, scrolled(), "a reload keeps the place") }

        checkNow.click()
        assertThat(page.locator(".toast")).isVisible()
        eventually { assertEquals(place, scrolled(), "Check now keeps the place") }

        page.evaluate("window.unreloaded = true")
        page.locator("nav.side a[href='/settings']").click()
        page.waitForFunction("!window.unreloaded")
        page.waitForLoadState()
        assertEquals(0, scrolled(), "a page opened from the sidebar starts at the top")
    }

    private fun round(vararg questions: Pair<String, String>) = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":""" +
        """{"outcome":"done","summary":"A Round.","output":{"questions":[""" + questions.joinToString(",") { (id, title) ->
            """{"id":"$id","title":"$title","ask":"Ask $id?","context":"Settles $id.","recommendation":"Recommend $id.","why":"Because $id."}"""
        } + "]}}}"

    private fun answering(claude: FakeClaude, vararg questions: Pair<String, String>): Pair<String, String> {
        agentTask(claude, mapOf("quiz" to """{"type":"object","properties":{"questions":{"type":"array"}}}"""))
        claude.session("quiz", 1, round(*questions))
        claude.session("quiz", 2, round())
        http.upload("/processes", "file", "round.bpmn", javaClass.getResource("/round.bpmn")!!.readBytes())
        val run = http.post("/processes/round/runs", mapOf()).path.removePrefix("/runs/")
        var entry = ""
        eventually { entry = assertNotNull(Regex("""href="(/notifications/[^"]+)"""").find(http.get("/notifications").body)).groupValues[1] }
        return run to entry
    }

    @Test
    fun `Answer a Round shows one question at a time beside a list of them marking answered, accepted and edited, then Review and send`() = browse {
        val claude = FakeClaude()
        val (run, entry) = answering(claude, "who" to "Title who", "scope" to "Title scope")

        page.navigate(url(entry))
        val tabs = page.locator("#tabs [role=tab]")
        assertThat(tabs).hasText(arrayOf("1 Title who", "2 Title scope", "Review and send"))
        page.screenshot("answer")
        val who = page.locator("#answer-who + .ed .cm-content")
        val scope = page.locator("#answer-scope + .ed .cm-content") // each answer is a markdown editor
        assertThat(who).isVisible()
        assertThat(scope).isHidden()
        assertThat(tabs.nth(0)).hasAttribute("aria-selected", "true")
        assertThat(page.locator("#back")).not().isVisible()
        tabs.nth(0).focus()
        for ((key, active) in listOf("ArrowDown" to 1, "ArrowRight" to 2, "ArrowUp" to 1, "ArrowLeft" to 0)) {
            page.keyboard().press(key)
            assertThat(tabs.nth(active)).hasAttribute("aria-selected", "true")
            assertThat(tabs.nth(active)).isFocused()
        }
        assertThat(who).isVisible()
        page.click("#next")
        assertThat(tabs.nth(0)).hasText("1 Title who ✓")
        assertThat(tabs.nth(1)).hasAttribute("aria-selected", "true")
        assertThat(scope).isVisible()
        scope.fill("Only admins.")
        assertThat(tabs.nth(1)).hasText("2 Title scope ✓ ✎")
        page.click("#reset")
        assertThat(scope).hasText("Recommend scope.")
        assertThat(page.locator("#answer-scope")).hasValue("Recommend scope.")
        assertThat(tabs.nth(1)).hasText("2 Title scope")
        // Cleared through the editor's own keys: fill("") sets the DOM selection and presses Delete, which CodeMirror
        // can take before the selectionchange event tells it of the selection, and then deletes nothing.
        scope.press("ControlOrMeta+a")
        scope.press("Delete")
        assertThat(page.locator("#answer-scope")).hasValue("")
        assertThat(tabs.nth(1)).hasText("2 Title scope ✎")
        val buttons = page.locator("footer").boundingBox()
        page.click("#next")
        assertThat(page.locator("#next")).hasText("Send the Round")
        assertThat(tabs.last()).hasAttribute("aria-selected", "true")
        assertEquals(buttons.y, page.locator("footer").boundingBox().y) // the buttons never move
        page.click("#next")
        assertThat(page.locator("#answer-scope + .ed.invalid")).isVisible() // required still stops an empty answer
        assertEquals(url(entry), page.url())
        scope.fill("Only admins.")
        tabs.last().click()
        assertThat(page.locator("#review-list > button").nth(1)).hasText("✓ ✎ Ask scope?Only admins.") // the full question, the answer under it
        page.click("#next")
        page.waitForURL { !it.contains(entry) }
        eventually { assertContains(http.get("/runs/$run").body, """id="state">Completed<""") }
        assertContains(claude.launches("quiz")[1].stdin, """{\"id\":\"who\",\"answer\":\"Recommend who.\",\"source\":\"accepted\"},{\"id\":\"scope\",\"answer\":\"Only admins.\",\"source\":\"written\"}""")
    }

    @Test
    fun `on a phone the questions are a strip of chips above the question, which scrolls sideways within itself and switches the question on a tap`() = browse {
        val titles = (1..6).map { "q$it" to "Question $it asks which option the Run should settle on" }
        val (_, entry) = answering(FakeClaude(), *titles.toTypedArray())
        onPhone()
        page.navigate(url(entry))
        val strip = page.locator("#tabs")
        val tabs = page.locator("#tabs [role=tab]")
        assertThat(tabs).hasCount(7)
        assertThat(strip).hasAttribute("aria-orientation", "horizontal")
        assertNoSidewaysScroll()
        page.screenshot("phone/answer")

        val box = strip.boundingBox()
        assertTrue(box.x >= 0 && box.x + box.width <= 390, "the strip spans ${box.x} to ${box.x + box.width}")
        val question = page.locator("section.question:visible")
        assertTrue(box.y + box.height <= question.boundingBox().y, "the strip sits above the question")
        assertTrue(tabs.nth(1).boundingBox().y < tabs.nth(0).boundingBox().y + tabs.nth(0).boundingBox().height, "the chips run side by side")
        assertEquals(true, strip.evaluate("e => e.scrollWidth > e.clientWidth"), "the chips overflow the strip")

        tabs.nth(4).tap()
        assertThat(tabs.nth(4)).hasAttribute("aria-selected", "true")
        assertThat(page.locator("#answer-q5 + .ed .cm-content")).isVisible()
        assertThat(page.locator("#answer-q1 + .ed .cm-content")).isHidden()
        assertTrue((strip.evaluate("e => e.scrollLeft") as Number).toDouble() > 0, "the strip, not the page, scrolls sideways to reach the chip")
        assertEquals(0, (page.evaluate("scrollX") as Number).toInt(), "the page didn't scroll sideways")
        assertNoSidewaysScroll()

        page.locator("#next").tap()
        assertThat(tabs.nth(4)).containsText("✓")
        assertThat(tabs.nth(5)).hasAttribute("aria-selected", "true")
        eventually {
            val chip = tabs.nth(5).boundingBox()
            val shown = strip.boundingBox()
            assertTrue(chip.x >= shown.x - 0.5 && chip.x + chip.width <= shown.x + shown.width + 0.5, "Next brings the chip it selects into the strip's view")
        }
        page.locator("#answer-q6 + .ed .cm-content").fill("Only admins.")
        assertThat(tabs.nth(5)).containsText("✓ ✎")

        page.setViewportSize(1024, 844)
        assertThat(strip).hasAttribute("aria-orientation", "vertical")
        page.setViewportSize(390, 844)
        assertThat(strip).hasAttribute("aria-orientation", "horizontal")
    }

    @Test
    fun `on a phone the Answer footer spans the screen and never covers the end of the question, even wrapped taller`() = browse {
        val (_, entry) = answering(FakeClaude(), "who" to "Title who", "scope" to "Title scope")
        onPhone()
        page.navigate(url(entry))
        page.locator("#next").tap()
        val footer = page.locator("#round > footer")
        assertThat(page.locator("#back")).isVisible()
        assertTrue(page.locator("#next").boundingBox().y > page.locator("#back").boundingBox().y, "Next wraps under Back and Reset")
        val box = footer.boundingBox()
        assertEquals(0.0, box.x, 0.5)
        assertEquals(390.0, box.width, 0.5)
        assertEquals(844.0, box.y + box.height, 0.5)
        val (left, right) = contentEdges("#round > footer")
        assertEquals(16.0, left, 0.5, "the footer's buttons start 16px in")
        assertEquals(374.0, right, 0.5, "the footer's buttons end 16px in")
        for (button in listOf("#back", "#reset", "#next")) {
            val b = page.locator(button).boundingBox()
            assertTrue(b.x >= 0 && b.x + b.width <= 390, "$button spans ${b.x} to ${b.x + b.width}")
        }
        assertNoSidewaysScroll()
        page.screenshot("phone/answer-footer")

        fun clear(bar: String) = eventually {
            page.evaluate("scrollTo(0, document.documentElement.scrollHeight)")
            val top = footer.boundingBox().y
            val end = page.locator("section.question:visible .ed").boundingBox()
            assertTrue(end.y + end.height <= top, "the answer ends at ${end.y + end.height}px, under the $bar footer at ${top}px")
        }
        clear("wrapped")
        footer.evaluate("bar => bar.insertAdjacentHTML('beforeend', '<p style=\"flex-basis: 100%; height: 240px; margin: 0\"></p>')")
        assertTrue(footer.boundingBox().height > 240)
        clear("grown")
    }

    @Test
    fun `a Round of ten long question titles lists every title in full inside the viewport`() = browse {
        val titles = (1..10).map { "q$it" to "Question $it asks which of the many long and winding options the Run should settle on before it goes on" }
        val (_, entry) = answering(FakeClaude(), *titles.toTypedArray())
        page.setViewportSize(1440, 900)

        page.navigate(url(entry))

        val tabs = page.locator("#tabs [role=tab]")
        assertThat(tabs).hasText((titles.mapIndexed { i, (_, title) -> "${i + 1} $title" } + "Review and send").toTypedArray())
        page.screenshot("answer-long")
        for (i in titles.indices) {
            val box = tabs.nth(i).boundingBox()
            assertTrue(box.x >= 0 && box.x + box.width <= 1440, "row ${i + 1} spans ${box.x} to ${box.x + box.width}")
            assertEquals(true, tabs.nth(i).evaluate("e => e.scrollWidth <= e.clientWidth && e.scrollHeight <= e.clientHeight"), "row ${i + 1} is clipped")
        }
        assertTrue(tabs.nth(0).boundingBox().let { it.x + it.width } <= page.locator("#round").boundingBox().x, "the list sits left of the question")
    }

    @Test
    fun `a markdown field is an editor with the toolbar, ⌘B, Preview and Expand, over the textarea that carries its text`() = browse {
        page.navigate(url(http.newDraft("/agents")))
        val field = page.locator("textarea[name=instructions]")
        val editor = page.locator("textarea[name=instructions] + .ed")
        assertThat(field).isHidden()
        editor.locator(".cm-content").fill("Keep it small")
        page.keyboard().press("ControlOrMeta+a")
        page.keyboard().press("ControlOrMeta+b")
        assertThat(field).hasValue("**Keep it small**")
        editor.locator("button[title=Heading]").click()
        assertThat(field).hasValue("## **Keep it small**")

        editor.locator("button", Locator.LocatorOptions().setHasText("Preview")).click()
        val modal = page.locator("dialog.modal")
        assertThat(modal.locator(".md h2 strong")).hasText("Keep it small") // rendered by the server
        page.screenshot("preview")
        page.keyboard().press("Escape")
        assertThat(modal).hasCount(0)

        editor.locator("button", Locator.LocatorOptions().setHasText("Expand")).click()
        assertThat(modal.locator(".split .cm-content")).isVisible()
        page.keyboard().press("End")
        page.keyboard().type(" now")
        assertThat(modal.locator(".split .md h2")).hasText("Keep it small now") // the live preview
        page.screenshot("expand")
        modal.locator("button[title=Close]").click()
        assertThat(editor.locator(".cm-content")).hasText("## **Keep it small** now") // the same editor, back in the page
        assertThat(field).hasValue("## **Keep it small** now")
    }

    @Test
    fun `pasting an image into the start form's prompt uploads it and references it at the caret, and anything else is refused`() = browse {
        agentTask(null)
        http.import("agent-task.bpmn")
        page.navigate(url("/processes/agent-task"))
        val field = page.locator("textarea[name=prompt]")
        val editor = page.locator("textarea[name=prompt] + .ed")
        editor.locator(".cm-content").fill("Like this: ")
        // What Cmd+V gives the editor for a copied image or a clipboard screenshot: a paste event carrying the file.
        fun paste(name: String, type: String, base64: String) = editor.locator(".cm-content").evaluate(
            """(element, [name, type, base64]) => {
                const clipboardData = new DataTransfer();
                clipboardData.items.add(new File([Uint8Array.from(atob(base64), c => c.charCodeAt(0))], name, {type}));
                element.dispatchEvent(new ClipboardEvent('paste', {clipboardData, bubbles: true, cancelable: true}));
            }""",
            listOf(name, type, base64),
        )
        paste("image.png", "image/png", java.util.Base64.getEncoder().encodeToString(ImagesTest.RED_PNG))
        assertThat(field).hasValue("Like this: ![image](${ImagesTest.RED_PATH})")
        page.keyboard().type(" please")
        assertThat(field).hasValue("Like this: ![image](${ImagesTest.RED_PATH}) please")

        paste("notes.pdf", "application/pdf", java.util.Base64.getEncoder().encodeToString("%PDF-1.4".toByteArray()))
        assertThat(editor.locator(".st")).hasText("Only an image can be pasted: PNG, JPEG, GIF or WebP.")
        assertThat(field).hasValue("Like this: ![image](${ImagesTest.RED_PATH}) please")
    }

    private fun picker(stored: String = "", vararg sourceMap: String = arrayOf("api", "web", "webhooks")) {
        settings(null, sourceMapLines(*sourceMap.map(::repo).toTypedArray()))
        val bpmn = javaClass.getResource("/start-form.bpmn")!!.readText().replace("\"start-form\"", "\"picker\"")
            .replace("""<camunda:property id="ants.type" value="repos" />""", """<camunda:property id="ants.type" value="repos" /><camunda:property id="ants.stored" value="$stored" />""")
        assertFalse("role=\"alert\"" in http.upload("/processes", "file", "picker.bpmn", bpmn.toByteArray()).body)
        page.navigate(url("/processes/picker"))
    }

    private val repoBox get() = page.getByRole(AriaRole.COMBOBOX, Page.GetByRoleOptions().setName("Repos (optional)"))
    private val repoOptions get() = page.getByRole(AriaRole.LISTBOX).getByRole(AriaRole.OPTION)
    private fun repoOption(name: String) = page.getByRole(AriaRole.OPTION, Page.GetByRoleOptions().setName(name).setExact(true))
    private val repoTags get() = page.getByRole(AriaRole.LIST, Page.GetByRoleOptions().setName("Picked repos")).getByRole(AriaRole.LISTITEM)

    @Test
    fun `the start form's Repos combobox filters as you type, toggles a repo on Enter or a click, moves by the arrow keys and closes on Esc`() = browse {
        picker()
        assertThat(page.locator("#start input[type=checkbox]")).hasCount(0)
        assertThat(repoBox).hasAttribute("placeholder", "Filter Source map repos, or type a git URL / owner/name")
        repoBox.click()
        assertThat(page.getByRole(AriaRole.LISTBOX)).hasAttribute("aria-multiselectable", "true")
        assertThat(repoOptions).hasText(arrayOf("api", "web", "webhooks"))

        repoBox.pressSequentially("web")
        assertThat(repoOptions).hasText(arrayOf("web", "webhooks"))
        repoBox.press("ArrowDown")
        repoBox.press("Enter")
        assertThat(repoOption("webhooks")).hasAttribute("aria-selected", "true")
        assertThat(repoOption("web")).hasAttribute("aria-selected", "false")
        repoBox.press("ArrowUp")
        repoBox.press("Enter")
        assertThat(repoOption("web")).hasAttribute("aria-selected", "true")
        repoOption("webhooks").click()
        assertThat(repoOption("webhooks")).hasAttribute("aria-selected", "false")
        assertThat(repoBox).isFocused()

        repoBox.press("Escape")
        assertThat(page.getByRole(AriaRole.LISTBOX)).isHidden()
        assertThat(repoTags).hasText(arrayOf("web×"))
    }

    @Test
    fun `a git URL or owner name typed in the start form's Repos combobox becomes a tag on Enter, and × removes a tag`() = browse {
        picker()
        val none = page.getByText("the scout picks the Run's repos from the whole Source map")
        assertThat(none).isVisible()

        repoBox.click()
        repoBox.pressSequentially("you/new-app")
        assertThat(repoOptions.filter(Locator.FilterOptions().setVisible(true))).hasCount(0)
        repoBox.press("Enter")
        assertThat(repoBox).hasValue("")
        assertThat(page).hasURL(Pattern.compile("/processes/picker$"))
        repoBox.blur()
        assertThat(repoTags).hasText(arrayOf("you/new-app×"))
        assertThat(none).isHidden()

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Remove you/new-app")).click()
        assertThat(repoTags).hasCount(0)
        assertThat(none).isVisible()
    }

    private fun startPicked(page: Page = this.page, prompt: Locator = page.locator("textarea[name=prompt] + .ed .cm-content"), press: (Locator) -> Unit = { it.click() }): String {
        prompt.fill("Add dark mode")
        press(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Start").setExact(true)))
        page.waitForURL(Pattern.compile("/runs/[^/]+$"))
        return page.locator("dt:text-is('repos') + dd").textContent()
    }

    @Test
    fun `the start form's Repos combobox shows the stored repos as tags, and the Run gets exactly the repos picked, a URL still being typed among them`() = browse {
        val elsewhere = repo("elsewhere").remote
        val mirror = repo("webmirror").remote
        picker(stored = "api $elsewhere")
        assertThat(repoTags).hasText(arrayOf("api×", "$elsewhere×"))

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Remove api")).click()
        repoBox.click()
        repoOption("web").click()
        repoBox.press("Escape")
        assertThat(repoTags).hasText(arrayOf("$elsewhere×", "web×"))

        repoBox.pressSequentially("$mirror")
        assertEquals("""["$elsewhere","web","$mirror"]""", startPicked())
    }

    private fun openSchedule() = page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("⏰ Schedule").setExact(true)).click()

    private fun schedulePicked(repo: String) {
        page.locator("textarea[name=prompt] + .ed .cm-content").fill("Add dark mode")
        repoBox.click()
        repoOption(repo).click()
        repoBox.press("Escape")
        openSchedule()
        val every = page.getByLabel("Every")
        assertThat(every).isFocused()
        every.selectOption("day")
        assertThat(page.getByLabel("Cron")).hasValue("0 0 8 * * *")
        every.selectOption("hour")
        assertThat(page.getByLabel("Cron")).hasValue("0 0 * * * *")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Save schedule")).click()
        page.waitForURL(Pattern.compile("/processes/picker$"))
    }

    @Test
    fun `Schedule beside Start saves the filled-in start form with times picked in words, and two Schedules are two rows`() = browse {
        picker()
        assertThat(page.locator("#when")).isHidden()
        schedulePicked("api")
        schedulePicked("web")
        val rows = page.locator("#schedules tr").filter(Locator.FilterOptions().setHasText("Every hour"))
        assertThat(rows).hasCount(2)
        assertThat(rows.nth(0)).containsText("api")
        assertThat(rows.nth(1)).containsText("web")
        assertThat(rows.nth(0)).containsText(Pattern.compile(" · in \\d"))
        page.screenshot("schedules")
    }

    @Test
    fun `Delete on a Process page asks for confirmation, then the Process is gone from the Processes list`() = browse {
        http.import("start-end.bpmn")
        page.navigate(url("/processes/start-end"))
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Delete").setExact(true)).click()
        assertThat(page.locator("h1")).hasText("Delete the Process Start to end?")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Delete").setExact(true)).click()
        page.waitForURL(Pattern.compile("/processes$"))
        assertThat(page.locator("a[href='/processes/start-end']")).hasCount(0)
    }

    @Test
    fun `Pause and Remove on a Schedule's row act on that Schedule alone`() = browse {
        picker()
        schedulePicked("api")
        schedulePicked("web")
        val rows = page.locator("#schedules tr").filter(Locator.FilterOptions().setHasText("Every hour"))
        val api = rows.filter(Locator.FilterOptions().setHasText("api"))
        val web = rows.filter(Locator.FilterOptions().setHasText("web"))
        api.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Pause")).click()
        page.waitForURL(Pattern.compile("/processes/picker$"))
        assertThat(api).containsText("paused")
        assertThat(api.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Resume"))).isVisible()
        assertThat(web).not().containsText("paused")
        web.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Remove")).click()
        page.waitForURL(Pattern.compile("/processes/picker$"))
        assertThat(rows).hasCount(1)
        assertThat(rows.nth(0)).containsText("api")
        api.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Resume")).click()
        page.waitForURL(Pattern.compile("/processes/picker$"))
        assertThat(api).not().containsText("paused")
        api.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Start now")).click()
        page.waitForURL(Pattern.compile("/runs/[^/]+$"))
    }

    private fun assertWithinCard(part: Locator, card: Locator, what: String) {
        assertThat(part).isVisible()
        assertWithinScreen(part, what)
        val (box, cardBox) = part.boundingBox() to card.boundingBox()
        assertTrue(box.x >= cardBox.x && box.x + box.width <= cardBox.x + cardBox.width, "$what spans ${box.x} to ${box.x + box.width} in a card from ${cardBox.x} to ${cardBox.x + cardBox.width}")
    }

    @Test
    fun `on a phone each Schedule is a block within the card, its name first, then labelled lines and its buttons at least 44px tall, each When part is a line of its own, and on desktop a Schedule is a table row and the When parts one row`() = browse {
        val prompt = "Sweep the notes for stale links and rewrite every one of them with a longer explanation than fits on one line"
        picker("", *phoneSourceMap)
        http.get("/processes/picker")
        http.post("/processes/picker/schedule", listOf(NAME to "Unprofiled sweep", "prompt" to "Sweep", "cron" to "0 0 * * * *"))
        val sourceMap = sourceMapLines(*phoneSourceMap.map(::repo).toTypedArray())
        settings(null, sourceMap + "profiles:\n  work:\n    vault: notes\n    source-map: work\n")
        http.get("/processes/picker")
        http.post("/processes/picker/schedule", listOf(NAME to "Profiled sweep", PROFILE to "work", "prompt" to prompt, "repos" to longRepo, "cron" to "0 0 * * * *"))
        val id = Regex("<tr id=\"schedule-([^\"]+)\"><td><b>Profiled sweep</b>").find(http.get("/processes/picker").body)!!.groupValues[1]
        settings(null, sourceMap)
        http.post("/processes/picker/schedules/$id/start", mapOf())

        onPhone()
        page.navigate(url("/processes/picker"))
        val card = page.locator("#schedule")
        val profiled = page.locator("#schedule-$id")
        val unprofiled = page.locator("#schedules tr").filter(Locator.FilterOptions().setHasText("Unprofiled sweep"))
        assertThat(profiled.getByRole(AriaRole.ALERT)).containsText("The scheduled Run didn't start: The Profile work isn't in the settings.")
        assertNoSidewaysScroll()
        val labels = listOf("Profile", "Values", "Runs", "Cron", "Next Run")
        fun button(row: Locator, name: String) = row.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName(name).setExact(true))
        val headers = (listOf("Schedule") + labels).map { page.getByRole(AriaRole.COLUMNHEADER, Page.GetByRoleOptions().setName(it).setExact(true)) }
        for (header in headers) assertThat(header).isHidden()

        for (row in listOf(profiled, unprofiled)) {
            assertWithinCard(row, card, "a Schedule's block")
            for (cell in row.locator("td").all()) assertWithinCard(cell, card, "a line of a Schedule's block")
            for (label in labels) assertWithinCard(row.getByText(label, Locator.GetByTextOptions().setExact(true)), card, "the label $label")
            val lines = row.locator("td").all().map { it.boundingBox() }
            for ((above, below) in lines.zipWithNext()) assertTrue(below.y >= above.y + above.height - 0.5, "a Schedule's lines sit one under another")
            assertThat(row.locator("td").first()).hasText(Pattern.compile("^(Profiled|Unprofiled) sweep\$"))
            for (name in listOf("Start now", "Pause", "Remove")) assertWithinCard(button(row, name), card, name)
        }
        assertThat(profiled).containsText("work")
        assertThat(unprofiled).containsText("—")
        assertWithinCard(profiled.getByText(prompt), card, "the long Prompt value")
        assertWithinCard(profiled.getByText(longRepo), card, "the long Repos value")
        assertWithinCard(profiled.getByText("Every hour"), card, "the Runs line")
        assertWithinCard(profiled.locator("code"), card, "the Cron line")
        assertWithinCard(profiled.getByRole(AriaRole.ALERT), card, "the failed note")
        assertWithinCard(button(profiled, "Dismiss"), card, "Dismiss")
        val (first, second) = page.locator("#schedules tr[id]").all().map { it.boundingBox() }
        assertTrue(second.y >= first.y + first.height, "the second Schedule's block sits under the first")
        assertTapSized()
        page.screenshot("phone/schedules")

        openSchedule()
        val fieldset = page.locator("#when")
        fun part(name: String) = page.locator("#when-$name")
        fun line(control: Locator) = fieldset.locator("label").filter(Locator.FilterOptions().setHas(control))
        val every = part("every")
        for ((choice, shown, hidden) in listOf(Triple("week", listOf("at", "on"), "dom"), Triple("month", listOf("at", "dom"), "on"))) {
            every.selectOption(choice)
            assertNoSidewaysScroll()
            assertThat(line(part(hidden))).isHidden()
            val controls = (listOf("every") + shown).map(::part)
            for (control in controls) {
                val what = "the When part ${control.getAttribute("id")}"
                assertWithinCard(line(control), fieldset, what)
                val (box, lineBox) = control.boundingBox() to line(control).boundingBox()
                assertTrue(box.x > lineBox.x + 8, "$what has its text before its control")
                assertEquals(lineBox.x + lineBox.width, box.x + box.width, 1.0, "$what has its control fill the rest of its line")
            }
            val lines = controls.map { line(it).boundingBox() }
            for ((above, below) in lines.zipWithNext()) assertTrue(below.y >= above.y + above.height - 0.5, "with Every $choice each When part sits on its own line")
        }
        page.getByText("Advanced", Page.GetByTextOptions().setExact(true)).click()
        assertWithinCard(part("cron"), fieldset, "the Advanced cron field")
        assertWithinCard(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Save schedule")), fieldset, "Save schedule")
        val inside = (fieldset.evaluate("f => { const s = getComputedStyle(f); return f.clientWidth - parseFloat(s.paddingLeft) - parseFloat(s.paddingRight) }") as Number).toDouble()
        assertEquals(inside, line(every).boundingBox().width, 1.0, "Every's line spans the When fieldset")
        page.screenshot("phone/when")

        page.setViewportSize(1280, 900)
        page.reload()
        for (header in headers) assertThat(header).isVisible()
        for (label in labels) assertThat(profiled.getByText(label, Locator.GetByTextOptions().setExact(true))).isHidden()
        assertThat(profiled.getByRole(AriaRole.CELL).nth(1)).hasAccessibleName("work")
        val buttons = listOf("Start now", "Pause", "Remove").map { button(profiled, it).boundingBox() }
        for ((left, right) in buttons.zipWithNext()) assertEquals(left.y, right.y, 0.5, "on desktop a Schedule's buttons sit in one row")
        openSchedule()
        every.selectOption("week")
        val parts = listOf("every", "at", "on").map { line(part(it)).boundingBox() }
        for ((left, right) in parts.zipWithNext()) assertEquals(left.y + left.height / 2, right.y + right.height / 2, 0.5, "on desktop the When parts sit in one row")
    }

    @Test
    fun `on a phone a Process with no Schedule shows Add a schedule below the No schedule text, and on desktop beside it`() = browse {
        http.import("start-end.bpmn")
        onPhone()
        page.navigate(url("/processes/start-end"))
        val text = page.getByText("No schedule. Runs start when someone presses Start.")
        val add = page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("＋ Add a schedule"))
        assertNoSidewaysScroll()
        for ((part, what) in listOf(text to "the No schedule text", add to "＋ Add a schedule")) {
            assertThat(part).isVisible()
            assertWithinScreen(part, what)
        }
        assertTrue(add.boundingBox().y >= text.boundingBox().y + text.boundingBox().height, "＋ Add a schedule sits below the No schedule text")
        page.screenshot("phone/no-schedule")

        page.setViewportSize(1280, 900)
        page.reload()
        assertTrue(add.boundingBox().y < text.boundingBox().y + text.boundingBox().height, "on desktop ＋ Add a schedule sits beside the No schedule text")
    }

    @Test
    fun `with JavaScript off the start form's Repos field is the Source map's checkboxes and a text input, which still start the Run`() = browse {
        val elsewhere = repo("elsewhere").remote
        picker()
        val plain = page.context().browser()!!.newContext(NewContextOptions().setJavaScriptEnabled(false)).apply { addCookies(page.context().cookies()) }.newPage()
        plain.navigate(url("/processes/picker"))
        assertThat(plain.getByRole(AriaRole.COMBOBOX, Page.GetByRoleOptions().setName("Repos (optional)"))).hasCount(0)
        assertThat(plain.locator("#start input[type=checkbox]")).hasCount(3)

        plain.getByLabel("web", Page.GetByLabelOptions().setExact(true)).check()
        plain.locator("#field-repos").fill("$elsewhere")
        assertEquals("""["web","$elsewhere"]""", startPicked(plain, plain.locator("textarea[name=prompt]")))
    }

    private val longRepo = "a-repository-whose-name-is-far-too-long-for-one-line-of-a-phone"
    private val phoneSourceMap = arrayOf("api", "web", "webhooks", longRepo, "billing", "search", "docs", "infra")

    private fun assertShownWhole(part: Locator, what: String) {
        assertWithinScreen(part, what)
        assertEquals(true, part.evaluate("e => e.scrollWidth <= e.clientWidth"), "$what shows its whole name")
    }

    private fun assertStartFormInOneColumn() {
        val fields = page.locator("#start .fld").all().map { it.boundingBox() }
        for ((above, below) in fields.zipWithNext()) {
            assertEquals(above.x, below.x, 0.5, "the start form's fields start at one edge")
            assertTrue(below.y >= above.y + above.height, "the start form's fields sit one under another")
        }
    }

    @Test
    fun `on a phone the start form's Repos picker wraps many picked repos within the screen, and taps pick and remove repos and start the Run`() = browse {
        val elsewhere = repo(longRepo).remote
        picker("api web webhooks $longRepo billing docs infra $elsewhere", *phoneSourceMap)
        onPhone()
        page.navigate(url("/processes/picker"))
        assertThat(repoTags).hasCount(8)
        assertNoSidewaysScroll()
        assertStartFormInOneColumn()
        for (tag in repoTags.all()) assertShownWhole(tag, "the tag ${tag.textContent()}")
        page.screenshot("phone/start")

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Remove api")).tap()
        assertThat(repoTags).hasText(arrayOf("web×", "webhooks×", "$longRepo×", "billing×", "docs×", "infra×", "$elsewhere×"))
        repoBox.tap()
        assertThat(page.getByRole(AriaRole.LISTBOX)).isVisible()
        assertWithinScreen(page.getByRole(AriaRole.LISTBOX), "the Source map repos")
        for (option in repoOptions.all()) {
            assertShownWhole(option, "the option ${option.textContent()}")
            val height = option.boundingBox().height
            assertTrue(height >= 44, "the option ${option.textContent()} is ${height}px tall")
        }
        assertNoSidewaysScroll()
        repoOption("search").tap()
        assertThat(repoOption("search")).hasAttribute("aria-selected", "true")
        page.locator("#start h3").tap()
        assertThat(page.getByRole(AriaRole.LISTBOX)).isHidden()
        assertThat(repoTags).hasText(arrayOf("web×", "webhooks×", "$longRepo×", "billing×", "docs×", "infra×", "$elsewhere×", "search×"))
        assertNoSidewaysScroll()

        assertEquals("""["web","webhooks","$longRepo","billing","docs","infra","$elsewhere","search"]""", startPicked(press = { it.tap() }))
    }

    @Test
    fun `on a phone with JavaScript off the start form's Repos checkboxes and text input fit the screen, and taps on them start the Run`() = browse {
        val elsewhere = repo(longRepo).remote
        picker("", *phoneSourceMap)
        onPhone(javaScript = false)
        page.navigate(url("/processes/picker"))
        assertThat(page.getByRole(AriaRole.COMBOBOX, Page.GetByRoleOptions().setName("Repos (optional)"))).hasCount(0)
        val boxes = page.locator("#start label:has(input[type=checkbox])")
        assertThat(boxes).hasCount(phoneSourceMap.size)
        assertNoSidewaysScroll()
        assertStartFormInOneColumn()
        for (box in boxes.all()) assertShownWhole(box, "the checkbox ${box.textContent().trim()}")
        page.screenshot("phone/start-no-js")

        page.getByLabel(longRepo, Page.GetByLabelOptions().setExact(true)).tap()
        page.getByLabel("web", Page.GetByLabelOptions().setExact(true)).tap()
        assertThat(page.getByLabel("web", Page.GetByLabelOptions().setExact(true))).isChecked()
        page.locator("#field-repos").tap()
        page.locator("#field-repos").fill(elsewhere.toString())
        assertNoSidewaysScroll()
        assertEquals("""["web","$longRepo","$elsewhere"]""", startPicked(page, page.locator("textarea[name=prompt]")) { it.tap() })
    }

    private fun byRepoReviewEntryOfApiCommitting(file: String, text: String, also: Map<String, String> = emptyMap()): String {
        agentTask(FakeClaude(), mapOf("mend" to null, "ship" to null), extra = reviewSourceMap())
        http.upload("/processes", "file", "review.bpmn", javaClass.getResource("/review.bpmn")!!.readBytes())
        val run = http.post("/processes/review/runs", listOf("repos" to "api", "repos" to "web")).path.removePrefix("/runs/")
        var entry = ""
        eventually { entry = assertNotNull(Regex("""href="(/notifications/[^"]+)"""").find(http.get("/notifications").body)).groupValues[1] }
        commitFile(workspace(run).resolve("repos/api"), file, text, "Change $file")
        for ((other, otherText) in also) commitFile(workspace(run).resolve("repos/api"), other, otherText, "Change $other")
        return entry
    }

    @Test
    fun `Review changes picks diff lines for a comment by clicking their numbers, and files it`() = browse {
        val entry = byRepoReviewEntryOfApiCommitting("hello.txt", "hi\nthere\nworld\n")

        page.navigate(url(entry))
        val diff = page.locator("table.diff[data-file='repos/api/hello.txt']")
        diff.locator("tr[data-side=new][data-line='1'] td").nth(1).click()
        diff.locator("tr[data-side=new][data-line='2'] td").nth(1).click(Locator.ClickOptions().setModifiers(listOf(KeyboardModifier.SHIFT)))
        assertThat(page.locator("#line-comment [name=repo]")).hasValue("api")
        assertThat(page.locator("#line-comment [name=file]")).hasValue("repos/api/hello.txt")
        assertThat(page.locator("#line-comment [name=from]")).hasValue("1")
        assertThat(page.locator("#line-comment [name=to]")).hasValue("2")
        assertThat(diff.locator("tr.picked")).hasCount(2)
        page.selectOption("#line-comment [name=type]", "SUGGESTION")
        page.fill("#line-comment [name=body]", "Say hello.")
        page.screenshot("review")
        page.click("#line-comment button")
        assertThat(page.locator("#comments")).containsText("Suggestion repos/api/hello.txt:1-2 (new): Say hello.")
    }

    @Test
    fun `a page fills the width beside the sidebar up to 2200px, and the modeler stays full-bleed`() = browse {
        http.import("start-end.bpmn")
        val main = page.locator("main")
        page.setViewportSize(1920, 1080)
        page.navigate(url("/processes"))
        val wide = main.boundingBox().width
        assertTrue(wide > 1160, "main is ${wide}px wide at 1920px")
        page.setViewportSize(3840, 1080)
        assertEquals(2200.0, main.boundingBox().width, 0.5)
        page.navigate(url("/processes/start-end/edit"))
        assertEquals(3840.0 - page.locator("nav.side").boundingBox().width, main.boundingBox().width, 0.5)
    }

    private fun contentEdges(selector: String) = (page.locator(selector).evaluate("e => { const r = e.getBoundingClientRect(), s = getComputedStyle(e); " +
        "return [r.left + parseFloat(s.paddingLeft), r.right - parseFloat(s.paddingRight)] }") as List<*>).map { (it as Number).toDouble() }

    @Test
    fun `the composer on Review changes lines up with the page's content, and the footer on the Answer page with the question column, at 1440px and 1920px`() = browse {
        val claude = FakeClaude()
        agentTask(claude, mapOf("mend" to null, "ship" to null, "quiz" to """{"type":"object","properties":{"questions":{"type":"array"}}}"""), extra = reviewSourceMap())
        claude.session("quiz", 1, """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":""" +
            """{"outcome":"done","summary":"A Round.","output":{"questions":[{"id":"who","title":"Who","ask":"Who?","context":"Settles who.","recommendation":"You.","why":"Because."}]}}}""")
        http.upload("/processes", "file", "review.bpmn", javaClass.getResource("/review.bpmn")!!.readBytes())
        http.upload("/processes", "file", "round.bpmn", javaClass.getResource("/round.bpmn")!!.readBytes())
        fun newEntry(seen: Set<String> = emptySet()): String {
            var entry = ""
            eventually { entry = assertNotNull(Regex("""href="(/notifications/[^"]+)"""").findAll(http.get("/notifications").body).map { it.groupValues[1] }.firstOrNull { it !in seen }) }
            return entry
        }
        http.post("/processes/review/runs", listOf("repos" to "api", "repos" to "web"))
        val review = newEntry()
        http.post("/processes/round/runs", mapOf())
        val answer = newEntry(setOf(review))
        fun right(selector: String) = page.locator(selector).boundingBox().let { it.x + it.width }

        for (width in listOf(1440, 1920)) {
            page.setViewportSize(width, 900)
            page.navigate(url(review))
            for ((main, bar) in contentEdges("main").zip(contentEdges(".composer > div"))) assertEquals(main, bar, 1.0, "the composer's content edges at ${width}px")
            page.navigate(url(answer))
            assertEquals(page.locator("#round").boundingBox().x, contentEdges("#round > footer")[0], 1.0, "the footer's buttons start where the question column starts at ${width}px")
            assertEquals(right("#round"), right("#next"), 1.0, "the footer's buttons end where the question column ends at ${width}px")
        }
    }

    private fun assertTheComposerNeverCoversTheEnd(vararg ends: String) {
        val composer = page.locator(".composer")
        fun clear(bar: String) = eventually {
            page.evaluate("scrollTo(0, document.documentElement.scrollHeight)")
            val top = composer.boundingBox().y
            for (selector in ends) {
                val box = page.locator(selector).boundingBox()
                assertTrue(box.y + box.height <= top, "$selector ends at ${box.y + box.height}px, under the $bar composer at ${top}px")
            }
        }
        clear("one-line")
        page.locator(".composer > div").evaluate("bar => { bar.style.flexWrap = 'wrap'; bar.insertAdjacentHTML('beforeend', '<p style=\"flex-basis: 100%; height: 240px; margin: 0\"></p>') }")
        assertTrue(composer.boundingBox().height > 240)
        clear("grown")
    }

    @Test
    fun `the composer never covers the end of Review changes however tall it grows, and a page without one keeps its own bottom padding`() = browse {
        val entry = staleStoryReviewEntry()
        page.setViewportSize(1440, 700)
        page.navigate(url("$entry#s1-1"))
        page.click(".step:not([hidden]) [data-comment]")
        page.locator(".step:not([hidden]) [name=body]").fill("Why?")
        page.click(".step:not([hidden]) .stepcomment button")
        assertThat(page.locator("#comments")).isVisible()
        assertTheComposerNeverCoversTheEnd(".step:not([hidden]) .code", "#comments", "#comment + .ed")

        page.navigate(url("/processes"))
        fun padding(side: String) = page.locator("main").evaluate("main => getComputedStyle(main).padding$side") as String
        assertEquals(padding("Top"), padding("Bottom"))
    }

    @Test
    fun `a rendered Markdown body keeps a readable measure on a wide page`() = browse {
        settings(null)
        val (process, plane) = row("measured", """<bpmn:startEvent id="start" />""", """<bpmn:userTask id="wait" name="Wait" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("measured", "Measured", process, plane))
        val run = http.post("/processes/measured/runs", mapOf()).path.removePrefix("/runs/")
        workspace(run).resolve("tickets/issues").createDirectories().resolve("01-wide.md").writeText("# Wide\n\nStatus: ready-for-agent\n\n" + "A long line of prose. ".repeat(60) + "\n")
        page.setViewportSize(1920, 1080)
        page.navigate(url("/runs/$run/tickets/01-wide"))
        // Measured in one step: the live update swaps #ticket for a new one, and boundingBox() reads null mid-swap
        val ticket = (page.locator("#ticket").evaluate("e => e.getBoundingClientRect().width") as Number).toDouble()
        assertTrue(ticket <= 860, "the Ticket is ${ticket}px wide")
    }

    private val wideMarkdown = "| Class | Test | Policy | Clock | Ledger | Gateway |\n|---|---|---|---|---|---|\n" +
        "| PaymentRetryPolicy | PaymentRetryPolicyTest | ExponentialBackoffPolicy | FixedClockForRetries | InMemoryPaymentLedger | StripeWebhookGateway |\n\n" +
        "![Ants](/icon-512.png)\n"

    private fun assertWideTableScrollsInsideItsBoxAndTheImageFits(selector: String) = eventually {
        val (right, tableRight, scrolled, imageRight, imageWidth) = (page.locator(selector).evaluate(
            "e => { const t = e.querySelector('table'), i = e.querySelector('img'); t.scrollLeft = 100; " +
                "return [e.getBoundingClientRect().right, t.getBoundingClientRect().right, t.scrollLeft, i.getBoundingClientRect().right, i.complete ? i.getBoundingClientRect().width : 0] }",
        ) as List<*>).map { (it as Number).toDouble() }
        assertTrue(right <= page.viewportSize().width, "$selector ends at ${right}px on a ${page.viewportSize().width}px screen")
        assertTrue(tableRight <= right, "the table ends at ${tableRight}px, past $selector's ${right}px")
        assertTrue(scrolled > 0, "the wide table scrolls sideways inside its own box")
        assertTrue(imageWidth in 1.0..<512.0, "the 512px image shrinks to fit, at ${imageWidth}px")
        assertTrue(imageRight <= right, "the image ends at ${imageRight}px, past $selector's ${right}px")
    }

    @Test
    fun `on a phone the Tickets list, the Spec and a Ticket fit the screen, a wide table scrolls inside its box, an image fits, and a checkbox ticks on a tap`() = browse {
        val run = waitingRun("specced").removePrefix("/runs/")
        workspace(run).resolve("tickets/spec.md").also { it.parent.createDirectories() }.writeText("# Paying again\n\n$wideMarkdown")
        val file = workspace(run).resolve("tickets/issues").createDirectories().resolve("01-retry.md")
        file.writeText("# Retry a failed payment\n\nStatus: ready-for-agent\n\n- [ ] Retries three times\n\n$wideMarkdown")
        onPhone()

        page.navigate(url("/tickets"))
        val cells = page.locator("tr:has(a[href='/runs/$run/tickets/spec']) td")
        assertThat(cells).hasCount(4)
        assertNoSidewaysScroll()
        val boxes = cells.all().map { it.boundingBox() }
        for ((above, below) in boxes.zipWithNext()) {
            assertEquals(above.x, below.x, 0.5, "the Spec's row stacks its cells in one column")
            assertTrue(below.y >= above.y + above.height, "the Spec's row stacks its cells one under the other")
        }
        page.screenshot("phone/tickets")

        page.navigate(url("/runs/$run/tickets/spec"))
        assertThat(page.locator("#ticket")).containsText("Paying again")
        assertNoSidewaysScroll()
        assertWideTableScrollsInsideItsBoxAndTheImageFits("#ticket")

        page.navigate(url("/runs/$run/tickets/01-retry"))
        assertThat(page.locator("#ticket")).containsText("Retries three times")
        assertNoSidewaysScroll()
        assertWideTableScrollsInsideItsBoxAndTheImageFits("#ticket")
        page.screenshot("phone/ticket")
        val box = page.locator("#ticket input[type=checkbox]")
        val size = box.boundingBox()
        assertTrue(size.width >= 24 && size.height >= 24, "the checkbox is ${size.width}×${size.height}px, too small to tap")
        box.tap()
        eventually { assertContains(file.readText(), "- [x] Retries three times") }
        assertThat(page.locator("#ticket input[type=checkbox]")).isChecked()
        assertNoSidewaysScroll()
    }

    private fun buttonNamed(name: String) = page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(name).setExact(true))

    private fun waitingEntry(after: String? = null): String {
        var entry = ""
        eventually { entry = assertNotNull(Regex("""href="(/notifications/[^"]+)"""").find(http.get("/notifications").body)).groupValues[1].also { assertNotEquals(after, it) } }
        return entry
    }

    @Test
    fun `on a phone Approve shows the Spec, the comment and its Send back and Approve in one column, and both work`() = browse {
        val claude = FakeClaude()
        val drafted = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Drafted.","output":{"notes":"notes.md"}}}"""
        claude.session("draft", drafted)
        claude.session("draft", 2, drafted)
        claude.session("polish", """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Polished.","output":{}}}""")
        agentTask(claude, mapOf("draft" to """{"type":"object","properties":{"notes":{"type":"string","format":"path"}}}""", "polish" to null))
        http.import("approve.bpmn")
        val run = http.post("/processes/approve/runs", mapOf()).path.removePrefix("/runs/")
        workspace(run).resolve("notes.md").writeText(
            "# Paying again\n\nSee https://example.com/" + "payments/retries/".repeat(12) + " for the policy.\n\n" +
                "```\n" + "val policy = PaymentRetryPolicy(".repeat(6) + "\n```\n\n$wideMarkdown",
        )
        val first = waitingEntry()
        onPhone()

        page.navigate(url(first))
        val spec = page.locator(".card.md")
        assertThat(spec).containsText("Paying again")
        assertNoSidewaysScroll()
        page.screenshot("phone/approve")
        val (left, right) = contentEdges("main")
        val comment = page.locator("#comment + .ed")
        val specBox = spec.boundingBox()
        val commentBox = comment.boundingBox()
        assertEquals(left, specBox.x, 0.5, "the Spec starts at the page's content edge")
        assertEquals(right, specBox.x + specBox.width, 0.5, "the Spec ends at the page's content edge")
        assertEquals(left, commentBox.x, 0.5, "the comment starts at the page's content edge")
        assertTrue(commentBox.y >= specBox.y + specBox.height, "the comment sits under the Spec")
        for (name in listOf("Send back", "Approve")) {
            val box = buttonNamed(name).boundingBox()
            assertTrue(box.x >= 0 && box.x + box.width <= page.viewportSize().width && box.y + box.height <= page.viewportSize().height, "$name is on the screen at $box")
        }

        comment.locator(".cm-content").tap()
        page.keyboard().type("Shorter, please.")
        buttonNamed("Send back").tap()
        eventually { assertContains(workspace(run).resolve("run-log.md").readText(), "Send back\n\nShorter, please.\n") }

        page.navigate(url(waitingEntry(after = first)))
        buttonNamed("Approve").tap()
        eventually { assertContains(http.get("/runs/$run").body, """id="state">Completed<""") }
        assertEquals(2, claude.launches("draft").size)
    }

    @Test
    fun `on a phone Push is one column of its repos with Cancel and Push on the screen, and Push pushes on a tap`() = browse {
        val repos = listOf(repo("api"), repo("web"))
        settings(null, sourceMapLines(*repos.toTypedArray()))
        http.import("push.bpmn")
        val branch = "ants/retry-a-failed-card-payment-three-times-with-backoff"
        val run = http.post("/processes/ship/runs", repos.map { "repos" to it.name } + ("branch" to branch)).path.removePrefix("/runs/")
        val work = waitingEntry()
        for (repo in repos) commitFile(workspace(run).resolve("repos/${repo.name}"), "retry.txt", "three times\n", "Retry a failed card payment three times, with exponential backoff between the tries")
        http.post(work, mapOf("action" to "approve", "comment" to ""))
        val push = waitingEntry(after = work)
        onPhone()

        page.navigate(url(push))
        val cards = page.locator(".card[data-repo]")
        assertThat(cards).hasCount(2)
        assertNoSidewaysScroll()
        page.screenshot("phone/push")
        val (left, right) = contentEdges("main")
        val boxes = cards.all().map { it.boundingBox() }
        for (box in boxes) {
            assertEquals(left, box.x, 0.5, "a repo's card starts at the page's content edge")
            assertEquals(right, box.x + box.width, 0.5, "a repo's card ends at the page's content edge")
        }
        assertTrue(boxes[1].y >= boxes[0].y + boxes[0].height, "web's card sits under api's")
        for (name in listOf("Cancel the Run", "Push 2 branches")) {
            val box = buttonNamed(name).boundingBox()
            assertTrue(box.x >= left - 0.5 && box.x + box.width <= right + 0.5, "$name is within the page at $box")
        }

        buttonNamed("Push 2 branches").tap()
        assertThat(page.locator(".toast")).containsText("Pushing")
        eventually(30) { for (repo in repos) assertContains(git(repo.remote, "branch", "--list"), branch) }
    }

    @Test
    fun `the Process properties pick the Process's Profile from the settings' Profiles`() = browse {
        settings(null, "profiles:\n  work: {vault: notes}\n  private: {vault: notes}\n")
        val (process, plane) = row("profiled", """<bpmn:startEvent id="start" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("profiled", "Profiled", process, plane))

        page.navigate(url("/processes/profiled/edit"))
        assertThat(heading).hasText("Process")
        page.getByLabel("Profile").selectOption("private")
        page.locator("#save").click()
        assertThat(page).hasURL(Pattern.compile(".*/processes/profiled$"))
        assertContains(http.get("/processes/profiled/diagram.bpmn").body, """name="ants.profile" value="private"""")
    }

    @Test
    fun `switching the Profile on the start form reloads it with Verbosity prefilled from the new Profile`() = browse {
        settings(null, "profiles:\n  work: {vault: notes, verbosity: brief}\n  private: {vault: notes, verbosity: detailed}\n")
        val (process, plane) = row("profiled", """<bpmn:startEvent id="start" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("profiled", "Profiled", process, plane))

        page.navigate(url("/processes/profiled"))
        assertThat(page.getByLabel("Verbosity")).hasValue("brief")
        page.getByLabel("Profile", Page.GetByLabelOptions().setExact(true)).selectOption("private")
        assertThat(page).hasURL(Pattern.compile(".*/processes/profiled\\?ants\\.profile=private$"))
        assertThat(page.getByLabel("Verbosity")).hasValue("detailed")
    }

    @Test
    fun `ticking Items are Tickets on a multi-instance saves it on the element`() = browse {
        agentTask(null, mapOf("draft" to """{"type":"object","properties":{"items":{"type":"array"}}}"""))
        val loop = """<bpmn:multiInstanceLoopCharacteristics operaton:collection="${'$'}{plan.output.items}" /></bpmn:serviceTask>"""
        val (process, plane) = row("ticketed", """<bpmn:startEvent id="start" />""", agentTaskXml("plan", "Plan"),
            agentTaskXml("implement", "Implement").replace("</bpmn:serviceTask>", loop), """<bpmn:endEvent id="end" />""")
        deploy(definitions("ticketed", "Ticketed", process, plane))

        page.navigate(url("/processes/ticketed/edit"))
        shape("implement").click()
        page.getByLabel("Items are Tickets").check()
        page.locator("#save").click()
        assertThat(page).hasURL(Pattern.compile(".*/processes/ticketed$"))
        assertContains(http.get("/processes/ticketed/diagram.bpmn").body, """name="ants.items-are-tickets" value="true"""")
    }

    private val wideAndTall = "let greeting = " + "\"hello\" + ".repeat(80) + "\"world\"\n" + (2..40).joinToString("") { "line $it\n" }

    private fun assertScrollsBothWaysAboveTheComposerKeepingItsNumbers(box: Locator, numbers: Locator) {
        box.evaluate("e => e.scrollIntoView({block: 'start'})")
        val (across, down) = (box.evaluate("e => [e.scrollWidth - e.clientWidth, e.scrollHeight - e.clientHeight]") as List<*>).map { (it as Number).toInt() }
        assertTrue(across > 0 && down > 0, "the box scrolls ${across}px sideways and ${down}px down inside it")
        val (b, bar) = box.boundingBox() to page.locator(".composer > div").boundingBox()
        assertTrue(b.y + b.height <= bar.y, "the box ends, horizontal scrollbar and all, at ${b.y + b.height}, under the composer at ${bar.y}")
        val before = numbers.all().map { it.boundingBox().x }
        page.mouse().move(b.x + b.width / 2, b.y + b.height / 2)
        page.mouse().wheel(300.0, 0.0)
        eventually { assertTrue((box.evaluate("e => e.scrollLeft") as Number).toInt() > 0, "the box scrolled sideways under the pointer") }
        assertEquals(0.0, (page.evaluate("scrollX") as Number).toDouble())
        for ((x, number) in before.zip(numbers.all())) assertEquals(x, number.boundingBox().x, 0.5, "the line number stays put")
    }

    @Test
    fun `a story step's code wider and taller than its box scrolls sideways inside it, its scrollbar on screen and its line numbers stay put`() = browse {
        val entry = staleStoryReviewEntry(wideAndTall, head = "HEAD")
        page.setViewportSize(1440, 240)
        page.navigate(url("$entry#s1-3"))
        val code = page.locator(".step:not([hidden]) .code")
        assertThat(code).containsText("line 12")
        assertScrollsBothWaysAboveTheComposerKeepingItsNumbers(code, code.locator(".ln:visible").nth(1))
    }

    @Test
    fun `a By repo file's diff wider and taller than its box scrolls sideways inside it, and its line numbers stay put and pick lines`() = browse {
        val entry = byRepoReviewEntryOfApiCommitting("wide.txt", wideAndTall)
        page.setViewportSize(1440, 400)
        page.navigate(url(entry))
        val diff = page.locator("table.diff[data-file='repos/api/wide.txt']")
        val numbers = diff.locator("tr[data-side=new][data-line='2'] td:nth-child(-n+2)")
        assertScrollsBothWaysAboveTheComposerKeepingItsNumbers(diff, numbers)
        numbers.nth(1).click()
        assertThat(page.locator("#line-comment [name=file]")).hasValue("repos/api/wide.txt")
        assertThat(page.locator("#line-comment [name=from]")).hasValue("2")
    }

    @Test
    fun `a By repo file's diff is coloured by its language, a comment over lines whole, and a file of no known language stays plain`() = browse {
        val entry = byRepoReviewEntryOfApiCommitting("Retry.kt", "/* Retries\n   an invoice. */\nclass Retry(val times: Int = 3)\n", mapOf("notes.txt" to "class Retry\n"))
        page.navigate(url(entry))
        val diff = page.locator("table.diff[data-file='repos/api/Retry.kt']")
        assertThat(diff).hasClass(Pattern.compile("\\bhl\\b"))
        val line = { n: Int -> diff.locator("tr[data-side=new][data-line='$n'] td").nth(2) }
        assertThat(line(2).locator(".tok-comment")).hasText("   an invoice. */")
        assertThat(line(3).locator(".tok-keyword").first()).hasText("class")
        assertThat(line(3).locator(".tok-number")).hasText("3")
        assertThat(line(3)).hasText("+class Retry(val times: Int = 3)")
        assertThat(line(3).locator(".sign")).hasText("+")
        val notes = page.locator("table.diff[data-file='repos/api/notes.txt']")
        assertThat(notes.locator("[class^=tok-]")).hasCount(0)
        assertThat(notes).not().hasClass(Pattern.compile("\\bhl\\b"))
        page.screenshot("review-coloured")
    }

    /** Review changes on a Story set written against [head]; then [helloText] is committed, so against origin/main its steps go stale and against HEAD they show it. */
    private fun staleStoryReviewEntry(helloText: String = "hello\nWORLD\n", head: String = "origin/main"): String {
        val claude = FakeClaude()
        agentTask(claude, mapOf("narrate" to null, "mend" to null, "ship" to null), extra = reviewSourceMap())
        val set = """{"title":"Nothing changes","ranges":[{"repo":"api","base":"origin/main","head":"$head"}],"stories":[{"id":"s","name":"Greeting",""" +
            """"premise":"Hello stays.","steps":[{"id":"hello","name":"Hello","claim":"It greets.","why":"Politeness.",""" +
            """"site":{"file":"repos/api/hello.txt","side":"new","kind":"context","from":1,"to":1}},""" +
            """{"id":"world","name":"World","claim":"It names the world.","why":"Scope.","site":{"file":"repos/api/hello.txt","side":"new","kind":"context","from":2,"to":2}},""" +
            """{"id":"everyone","name":"Everyone","claim":"It greets the world.","why":"Reach.","site":{"file":"repos/api/hello.txt","side":"new","kind":"context","from":1,"to":2}}]}]}"""
        claude.session("narrate", "write walkthrough/stories.json $set", """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Done.","output":{}}}""")
        http.upload("/processes", "file", "story-set.bpmn", javaClass.getResource("/story-set.bpmn")!!.readBytes())
        val run = http.post("/processes/story-set/runs", listOf("repos" to "api", "repos" to "web")).path.removePrefix("/runs/")
        var entry = ""
        eventually {
            val list = http.get("/notifications").body
            assertContains(list, "[review]") // the walkthrough is done
            entry = Regex("""href="(/notifications/[^"]+)"""").find(list)!!.groupValues[1]
        }
        assertContains(http.get(entry).body, "/runs/$run")
        commitFile(workspace(run).resolve("repos/api"), "hello.txt", helloText, "Shout the world")
        return entry
    }

    @Test
    fun `Review changes opens on the Story tab, walks a story step by step by key, comments on a step, and switches to By repo`() = browse {
        val entry = staleStoryReviewEntry()
        page.navigate(url(entry))
        assertThat(page.locator(".tabs a.on")).hasText("Story")
        val spine = page.locator(".spine")
        assertThat(spine).containsText("Nothing changes")
        assertThat(spine).containsText("Greeting")
        assertThat(spine).containsText("Remainder")
        assertThat(spine.locator("li a[href='#s1-1']")).isHidden()
        assertThat(page.locator("#stories")).isVisible()
        page.click("#stories .sover a")
        val step = page.locator(".step:not([hidden])")
        assertThat(step.locator(".claim")).hasText("It greets.")
        val active = spine.locator("[aria-current=step]")
        assertThat(active).hasText("Hello")
        assertThat(spine.locator("li:visible")).hasText(arrayOf("Hello", "⚠ World", "⚠ Everyone"))
        assertThat(spine.locator(".srow.on")).containsText("Greeting")
        assertThat(step.locator("[data-go='-1']")).isDisabled()
        step.locator("[data-go='1']").press("Enter")
        assertThat(step.locator(".claim")).containsText("It names the world.")
        assertThat(active).hasText("⚠ World")
        spine.locator("li a[href='#s1-1']").press("Enter")
        assertThat(step.locator(".claim")).hasText("It greets.")
        assertThat(active).hasText("Hello")
        spine.locator("li a[href='#s1-2']").click()
        assertThat(active).hasText("⚠ World")
        page.click(".step:not([hidden]) [data-go='-1']")
        assertThat(step.locator(".claim")).hasText("It greets.")
        assertThat(step.locator(".details")).isHidden()
        page.click(".step:not([hidden]) [data-details]")
        assertThat(step.locator(".details")).containsText("Politeness.")
        page.click(".step:not([hidden]) .showdiff")
        assertThat(page.locator("#walkMain")).hasClass(Pattern.compile("diff"))
        assertThat(step.locator(".showdiff")).isChecked()
        page.click(".step:not([hidden]) [data-comment]")
        page.selectOption(".step:not([hidden]) [name=type]", "ISSUE")
        page.locator(".step:not([hidden]) [name=body]").pressSequentially("Why?")
        page.keyboard().press("Enter") // a new line, not the comment sent
        page.keyboard().type("And why now?")
        page.screenshot("story")
        page.click(".step:not([hidden]) .stepcomment button")
        assertThat(page.locator("#comments")).containsText("Greeting › Hello repos/api/hello.txt:1-1 (new): Why?\nAnd why now?")
        assertThat(step.locator(".scomment")).containsText("ISSUE Why?\nAnd why now?") // back on the step it is on
        step.locator(".scomment button").click()
        assertThat(page.locator("#comments")).hasCount(0)
        assertThat(step.locator(".claim")).hasText("It greets.") // still on the step
        assertThat(step.locator(".scomment")).hasCount(0)
        val overall = page.locator("#comment + .ed .cm-content") // the overall comment's markdown editor
        overall.pressSequentially("can type n, p, d, D and c")
        assertThat(overall).hasText("can type n, p, d, D and c")
        assertThat(step.locator(".claim")).hasText("It greets.") // no key steps the story
        page.navigate(url("$entry#s1-3"))
        assertThat(step.locator(".claim")).containsText("It greets the world.")
        assertThat(step.locator(".claim .stale")).hasText("STALE")
        assertThat(active).hasText("⚠ Everyone")
        assertThat(step.locator("[data-go='1']")).isDisabled()
        page.click(".walkstory:not([hidden]) [data-all]")
        assertThat(page.locator("#stories")).isVisible()
        assertThat(active).hasCount(0)
        page.click(".tabs a:not(.on)")
        assertThat(page.locator(".tabs a.on")).hasText("By repo")
        assertThat(page.locator("[data-repo]").first()).containsText("api")
    }

    @Test
    fun `Review changes' sticky story column never slides over the code as a long step scrolls`() = browse {
        val entry = staleStoryReviewEntry()
        page.setViewportSize(1440, 360)
        page.navigate(url("$entry#s1-3"))
        page.click(".step:not([hidden]) [data-details]")
        page.click(".step:not([hidden]) [data-comment]")
        val spine = page.locator(".spine")
        val code = page.locator(".step:not([hidden]) .code")
        assertThat(code).isVisible()
        val height = (page.evaluate("document.documentElement.scrollHeight") as Number).toInt()
        assertTrue(height > 720, "the page is ${height}px tall")
        for (y in 0..height step 40) {
            page.evaluate("y => scrollTo(0, y)", y)
            val (s, c) = spine.boundingBox() to code.boundingBox()
            assertTrue(s.x + s.width <= c.x, "scrolled to $y, the story column (${s.x}, ${s.width}) reaches over the code (${c.x})")
            if (c.y + c.height > 0) assertTrue(s.y + s.height > 0, "scrolled to $y, the code is on screen but the story column has left it (${s.y})")
        }
        val side = page.locator("nav.side").boundingBox()
        assertTrue(spine.boundingBox().x - (side.x + side.width) <= 32, "the story column sits against the sidebar")
    }

    @Test
    fun `Review changes' By repo file list wraps a long file name instead of clipping it`() = browse {
        val long = "PaymentFailedHandlerRetryPolicySchedulerIntegrationTest.kt"
        val entry = byRepoReviewEntryOfApiCommitting(long, "retry\n")

        page.setViewportSize(1440, 900)
        page.navigate(url(entry))
        val nav = page.locator(".walk nav")
        val link = nav.locator("a", Locator.LocatorOptions().setHasText(long))
        assertThat(link).hasText(long)
        assertEquals(200.0, nav.boundingBox().width, 0.5)
        val (n, l) = nav.boundingBox() to link.boundingBox()
        assertTrue(l.x >= n.x && l.x + l.width <= n.x + n.width + 0.5, "the link (${l.x}, ${l.width}) stays inside the nav (${n.x}, ${n.width})")
        assertTrue(l.height > 30, "the long name wraps onto more lines, ${l.height}px tall")
        assertEquals(link.evaluate("e => e.clientWidth"), link.evaluate("e => e.scrollWidth"))
    }

    private fun disclosure(name: String) = page.locator("details", Page.LocatorOptions().setHas(page.getByText(name, Page.GetByTextOptions().setExact(true))))

    private fun assertAtTheTopAcrossTheContent(disclosure: Locator, content: Locator) {
        val (d, c) = disclosure.boundingBox() to content.boundingBox()
        assertEquals(16.0, d.x, 0.5, "the disclosure starts at the content's edge")
        assertEquals(358.0, d.width, 0.5, "the disclosure spans the content")
        assertTrue(d.y + d.height <= c.y, "the disclosure (${d.y}, ${d.height}) sits above the content (${c.y})")
    }

    private fun assertOnePerRow(vararg fields: Locator) {
        for ((above, below) in fields.map { it.boundingBox() }.zipWithNext()) {
            assertTrue(below.y >= above.y + above.height, "a field at ${below.y} sits under the one ending at ${above.y + above.height}")
            assertEquals(above.x, below.x, 0.5, "the fields start at one edge")
        }
    }

    private fun assertTheComposerSpansThePhoneAndNeverCoversTheEnd(vararg ends: String) {
        val composer = page.locator(".composer").boundingBox()
        assertEquals(0.0, composer.x, 0.5)
        assertEquals(390.0, composer.width, 0.5)
        assertTheComposerNeverCoversTheEnd(*ends)
    }

    private fun assertScrollsAwayWithThePage(disclosure: Locator) {
        page.evaluate("scrollTo(0, 0)")
        val before = disclosure.boundingBox().y
        page.evaluate("scrollBy(0, 200)")
        eventually { assertEquals(before - 200, disclosure.boundingBox().y, 0.5, "the disclosure scrolls away with the page") }
        page.evaluate("scrollTo(0, 0)")
    }

    @Test
    fun `on a phone the Story tab fits the screen, its stories in a closed Stories disclosure on top that opens on a tap, jumps to a step and marks it`() = browse {
        val entry = staleStoryReviewEntry(wideAndTall, head = "HEAD")
        onPhone()
        page.navigate(url(entry))
        val stories = disclosure("Stories")
        val greeting = stories.getByRole(AriaRole.LINK, Locator.GetByRoleOptions().setName("Greeting"))
        assertThat(greeting).isHidden()
        assertAtTheTopAcrossTheContent(stories, page.locator("#walkMain"))
        assertNoSidewaysScroll()

        stories.getByText("Stories", Locator.GetByTextOptions().setExact(true)).tap()
        assertThat(greeting).isVisible()
        assertTapSized()
        greeting.tap()
        val step = page.locator(".step:not([hidden])")
        assertThat(step.locator(".claim")).hasText("It greets.")
        val active = stories.locator("[aria-current=step]")
        assertThat(active).hasText("Hello")
        assertThat(active).isVisible()
        stories.getByRole(AriaRole.LINK, Locator.GetByRoleOptions().setName("World")).tap()
        assertThat(step.locator(".claim")).hasText("It names the world.")
        assertThat(active).hasText("World")
        assertThat(stories.locator(".srow.on")).containsText("Greeting")
        assertNoSidewaysScroll()
        page.screenshot("phone/review-story")

        assertScrollsAwayWithThePage(stories)

        page.navigate(url("$entry#s1-3"))
        page.setViewportSize(390, 400)
        val code = step.locator(".code")
        assertThat(code).containsText("line 12")
        assertScrollsBothWaysAboveTheComposerKeepingItsNumbers(code, code.locator(".ln:visible").nth(1))

        page.setViewportSize(390, 844)
        step.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Comment on this step")).tap()
        val form = step.locator(".stepcomment")
        assertOnePerRow(form.locator("[name=type]"), form.locator("[name=body]"), form.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Add")))
        assertNoSidewaysScroll()
        assertTheComposerSpansThePhoneAndNeverCoversTheEnd(".step:not([hidden]) .bandacts", "#comment + .ed")
    }

    @Test
    fun `on a phone By repo fits the screen, its files in a closed Files disclosure on top that opens on a tap and jumps to a file, whose wide diff scrolls inside its box`() = browse {
        val entry = byRepoReviewEntryOfApiCommitting("wide.txt", wideAndTall)
        onPhone()
        page.navigate(url(entry))
        val files = disclosure("Files")
        val wide = files.getByRole(AriaRole.LINK, Locator.GetByRoleOptions().setName("wide.txt"))
        assertThat(wide).isHidden()
        assertAtTheTopAcrossTheContent(files, page.locator("[data-repo]").first())
        assertNoSidewaysScroll()
        assertScrollsAwayWithThePage(files)

        files.getByText("Files", Locator.GetByTextOptions().setExact(true)).tap()
        assertThat(wide).isVisible()
        assertTapSized()
        assertNoSidewaysScroll()
        wide.tap()
        page.waitForURL { it.endsWith("#f-api-1") }
        eventually {
            val y = page.locator("#f-api-1").boundingBox().y
            assertTrue((page.evaluate("scrollY") as Number).toDouble() > 0 && y >= -0.5 && y < 844, "the file is on screen at $y")
        }
        page.screenshot("phone/review-by-repo")

        page.setViewportSize(390, 400)
        val diff = page.locator("table.diff[data-file='repos/api/wide.txt']")
        val numbers = diff.locator("tr[data-side=new][data-line='2'] td:nth-child(-n+2)")
        assertScrollsBothWaysAboveTheComposerKeepingItsNumbers(diff, numbers)
        for (number in numbers.all()) assertEquals(1.0, (number.evaluate("td => getComputedStyle(td).backgroundColor.match(/[\\d.]+/g).map(Number)[3] ?? 1") as Number).toDouble(), "the line number's background is opaque")
        numbers.nth(1).tap()
        val form = page.locator("#line-comment")
        assertThat(form.locator("[name=file]")).hasValue("repos/api/wide.txt")
        assertThat(form.locator("[name=from]")).hasValue("2")
        assertThat(form.locator("[name=body]")).isFocused()
        page.setViewportSize(390, 844)
        assertOnePerRow(*listOf("repo", "file", "side", "from", "to", "type", "body").map { form.locator("[name=$it]") }.toTypedArray(),
            form.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Add the comment")))
        assertNoSidewaysScroll()
        assertTheComposerSpansThePhoneAndNeverCoversTheEnd("#line-comment", "#comment + .ed")
    }

    @Test
    fun `the diagram box zooms with ＋ and －, and Fit fits the diagram again`() = browse {
        settings(null)
        val (process, plane) = row("zoomed", """<bpmn:startEvent id="start" />""", """<bpmn:userTask id="wait" name="Wait" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("zoomed", "Zoomed", process, plane))
        page.navigate(url("/processes/zoomed"))
        assertThat(shape("wait")).isVisible()
        fun zoom() = (page.evaluate("document.querySelector('.dia').canvas.zoom()") as Number).toDouble()
        val fit = zoom()

        page.click(".dia .zoom [title='Zoom in']")
        assertEquals(fit * 1.25, zoom(), 0.001)
        page.click(".dia .zoom [title='Zoom out']")
        page.click(".dia .zoom [title='Zoom out']")
        assertEquals(fit / 1.25, zoom(), 0.001)
        page.click(".dia .zoom [title='Fit']")
        assertEquals(fit, zoom(), 0.001)
    }

    @Test
    fun `a modal closes on Esc, on its ✕ and on a click outside it`() = browse {
        appendAgentTask()
        val newAgent = page.locator("#explain button", Page.LocatorOptions().setHasText("New Agent"))
        val modal = page.locator("dialog.modal")
        for (close in listOf<() -> Unit>({ page.keyboard().press("Escape") }, { modal.locator("[title=Close]").click() }, { page.mouse().click(5.0, 5.0) })) {
            newAgent.click()
            assertThat(modal).isVisible()
            assertThat(modal.locator(".mh")).containsText("New Agent")
            close()
            assertThat(modal).hasCount(0)
        }
    }

    @Test
    fun `on the Run page Rename renames over the page, Esc closes the drawer, a Task's row opens the Task, whose comment is a markdown editor`() = browse {
        settings(null)
        val (process, plane) = row("approving", """<bpmn:startEvent id="start" />""", """<bpmn:userTask id="wait" name="Wait" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("approving", "Approving", process, plane))
        val run = http.post("/processes/approving/runs", mapOf()).path
        page.navigate(url(run))
        page.click("button[popovertarget=rename]")
        page.fill("#new-name", "Renamed")
        page.click("#rename button.primary")
        assertThat(page.locator("#name")).hasText("Renamed")

        eventually { page.locator("#diagram .djs-element[data-element-id='wait']").click() }
        assertThat(page.locator("#drawer-head")).isVisible()
        page.keyboard().press("Escape")
        assertThat(page.locator("#drawer")).isHidden()

        page.click("#tasks tr.click")
        page.waitForURL { "/notifications/" in it }
        assertThat(page.locator("#comment + .ed .cm-content")).isVisible() // markdown.js's editor, whose own test is above
    }

    @Test
    fun `New Process on the Processes list opens a modal that starts the modeler on a Draft-only Process, and a row opens its Process`() = browse {
        http.import("start-end.bpmn")
        page.navigate(url("/processes"))
        page.click("tr.click >> text=you") // anywhere on the row
        page.waitForURL(url("/processes/start-end"))
        page.navigate(url("/processes"))
        page.click(".ph button.primary")
        page.getByLabel("Name").fill("From the list")
        page.click("#new-process button.primary")
        page.waitForURL(Pattern.compile(".*/processes/from-the-list(-\\d+)?/edit$"))
        assertThat(page.locator(".draft-head .draft-mark")).hasText("Draft")
    }

    private val saveState get() = page.locator(".draft-head [data-save-state]")
    private val marker get() = page.locator(".draft-head .draft-mark")
    private val draftSaved = Pattern.compile("^Draft saved \\d\\d:\\d\\d$")

    @Test
    fun `an edit on the Agent page is autosaved to its Draft with no Publish, the header says so, and it survives a reload`() = browse {
        val file = home.resolve("agents").createDirectories().resolve("autosaved.md")
        file.writeText("---\nid: a-auto01\nname: autosaved\ncli: claude\n---\nPublished.\n")
        page.navigate(url("/agents/autosaved"))
        assertThat(marker).hasText("Published")
        assertThat(saveState).isEmpty()

        page.fill("#description", "Kept as a Draft.")
        assertThat(saveState).hasText(draftSaved)
        assertThat(marker).hasText("Draft · changes not published")
        assertEquals("---\nid: a-auto01\nname: autosaved\ncli: claude\n---\nPublished.\n", file.readText())

        page.reload()
        assertThat(page.locator("#description")).hasValue("Kept as a Draft.")
        assertThat(saveState).hasText(draftSaved)
        assertThat(marker).hasText("Draft · changes not published")

        page.fill("#description", "Kept on leaving.")
        page.navigate(url("/agents")) // before the 2 s are up
        eventually { assertContains(http.get("/agents/autosaved").body, """value="Kept on leaving."""") }
        assertThat(page.locator("tr", Page.LocatorOptions().setHasText("autosaved")).locator(".draft-mark")).hasText("Draft · changes not published")

        page.navigate(url("/agents/autosaved"))
        page.click("button[form=agent]")
        page.waitForURL(url("/agents/autosaved"))
        assertThat(marker).hasText("Published")
        assertContains(file.readText(), "description: Kept on leaving.")
    }

    @Test
    fun `an edit on the Skill page is autosaved to its Draft with no Publish, and it survives a reload`() = browse {
        val folder = home.resolve("skills/autosaved-skill").createDirectories()
        folder.resolve("ants.yaml").writeText("id: k-auto01\n")
        val published = "---\nname: autosaved-skill\ndescription: Published.\n---\nPublished.\n"
        folder.resolve("SKILL.md").writeText(published)
        page.navigate(url("/skills/autosaved-skill"))
        assertThat(marker).hasText("Published")

        val drafted = "---\nname: autosaved-skill\ndescription: Kept as a Draft.\n---\nKept as a Draft.\n"
        page.locator("#skill-md + .ed .cm-content").fill(drafted)
        assertThat(saveState).hasText(draftSaved)
        assertThat(marker).hasText("Draft · changes not published")
        assertEquals(published, folder.resolve("SKILL.md").readText())

        page.reload()
        assertThat(page.locator("#skill-md + .ed .cm-content")).containsText("Kept as a Draft.")
        assertThat(marker).hasText("Draft · changes not published")
        page.navigate(url("/skills"))
        assertThat(page.locator("tr", Page.LocatorOptions().setHasText("autosaved-skill")).locator(".draft-mark")).hasText("Draft · changes not published")

        page.navigate(url("/skills/autosaved-skill"))
        page.click("button[form=skill]")
        page.waitForURL(url("/skills/autosaved-skill"))
        assertThat(marker).hasText("Published")
        assertEquals(drafted, folder.resolve("SKILL.md").readText())
    }

    @Test
    fun `an edit made while a save is on its way is kept when the page is left at once`() = browse {
        home.resolve("agents").createDirectories().resolve("in-flight.md").writeText("---\nid: a-flight1\nname: in-flight\ncli: claude\n---\nFlight.\n")
        // The first autosave reaches Ants, but its answer is slow to come back.
        page.addInitScript("""
            const fetched = window.fetch
            let slowed = false
            window.fetch = async (url, options) => {
                const response = await fetched(url, options)
                if (!slowed && String(url).endsWith('/draft')) { slowed = true; await new Promise(done => setTimeout(done, 4000)) }
                return response
            }
        """.trimIndent())
        page.navigate(url("/agents/in-flight"))
        page.fill("#description", "First.")
        assertThat(saveState).hasText("Saving…")
        page.fill("#description", "Second.")
        page.navigate(url("/agents"))
        eventually { assertContains(http.get("/agents/in-flight").body, """value="Second."""") }
    }

    @Test
    fun `a failed autosave says why and is retried until it succeeds`() = browse {
        home.resolve("agents").createDirectories().resolve("retried.md").writeText("---\nid: a-retry1\nname: retried\ncli: claude\n---\nRetry.\n")
        page.navigate(url("/agents/retried"))
        page.route("**/agents/retried/draft") { it.abort() }
        page.fill("#description", "Kept once Ants answers.")
        assertThat(saveState).hasText("Not saved: Ants can't be reached.")
        page.unroute("**/agents/retried/draft")
        assertThat(saveState).hasText(draftSaved, LocatorAssertions.HasTextOptions().setTimeout(15_000.0))
        assertContains(http.get("/agents/retried").body, """value="Kept once Ants answers."""")
    }

    @Test
    fun `a tab whose Draft changed in another tab says so and offers to load the newer Draft`() = browse {
        home.resolve("agents").createDirectories().resolve("two-tabs.md").writeText("---\nid: a-2tabs1\nname: two-tabs\ncli: claude\n---\nTabs.\n")
        page.navigate(url("/agents/two-tabs"))
        val other = otherTab()
        other.navigate(url("/agents/two-tabs"))
        other.fill("#description", "From the other tab.")
        assertThat(other.locator(".draft-head [data-save-state]")).hasText(draftSaved)

        page.fill("#description", "From this tab.")
        assertThat(saveState).hasText("Not saved: This Agent's Draft was changed in another tab or window.")
        page.locator(".draft-head [data-load-newer]").click()
        assertThat(page.locator("#description")).hasValue("From the other tab.")
        assertThat(saveState).hasText(draftSaved)
    }

    @Test
    fun `an edit in the modeler is autosaved to the Process's Draft and survives a reload, and Publish deploys it and leaves no Draft`() = browse {
        newProcess("Autosaved diagram")
        val key = page.url().removeSuffix("/edit").substringAfterLast('/')
        assertThat(marker).hasText("Draft")
        assertThat(saveState).hasText(draftSaved) // New made the Draft
        shape("start").click()
        pad.locator("[data-action='append.user-task']").click()
        val task = page.locator("#canvas .djs-shape.selected").getAttribute("data-element-id")!!
        label("Title").fill("Kept?")
        label("Title").press("Tab")
        assertThat(saveState).hasText(draftSaved)
        assertThat(marker).hasText("Draft")

        page.reload()
        assertThat(shape(task)).isVisible()
        assertThat(saveState).hasText(draftSaved)
        shape(task).click()
        assertThat(label("Title")).hasValue("Kept?")

        page.locator("#save").click()
        page.waitForURL(url("/processes/$key"))
        assertContains(http.get("/processes/$key/diagram.bpmn").body, "Kept?")
        page.navigate(url("/processes/$key/edit"))
        assertThat(marker).hasText("Published")
        assertThat(saveState).isEmpty()
        assertEquals("0", draftVersion(http.get("/processes/$key/edit").body)) // no leaving save brought the Draft back
    }

    @Test
    fun `a Process Builder edit to the open diagram reaches the Process's Draft, and the deployed version stays as it was`() = browse {
        agentTask(null)
        http.import("agent-task.bpmn")
        page.navigate(url("/processes/agent-task/edit"))
        assertThat(marker).hasText("Published")
        val tool = builder()
        tool("add_element", mapOf("kind" to "agent", "id" to "grill", "name" to "Grill", "x" to 450, "y" to 250))
        assertThat(shape("grill")).isVisible()
        assertThat(saveState).hasText(draftSaved)
        assertThat(marker).hasText("Draft · changes not published")
        assertContains(http.get("/processes/agent-task/edit.bpmn").body, """id="grill"""")
        assertFalse("grill" in http.get("/processes/agent-task/diagram.bpmn").body)
    }

    @Test
    fun `Discard Draft on the Agent page asks first, then reopens the published Agent, and no leaving save brings the Draft back`() = browse {
        val file = home.resolve("agents").createDirectories().resolve("discarded.md")
        file.writeText("---\nid: a-disc01\nname: discarded\ndescription: Published.\ncli: claude\n---\nKeep.\n")
        page.navigate(url("/agents/discarded"))
        page.fill("#description", "Unwanted.")
        assertThat(saveState).hasText(draftSaved)
        page.reload()
        page.locator(".ph button", Page.LocatorOptions().setHasText("Discard Draft")).click()
        assertThat(page.locator("h1")).hasText("Discard the Draft of discarded?")
        page.locator("main form button", Page.LocatorOptions().setHasText("Discard")).click()
        page.waitForURL(url("/agents/discarded"))
        assertThat(page.locator("#description")).hasValue("Published.")
        assertThat(marker).hasText("Published")
        assertThat(page.locator(".ph button", Page.LocatorOptions().setHasText("Discard Draft"))).hasCount(0)
        assertEquals("0", draftVersion(http.get("/agents/discarded").body))
        assertContains(file.readText(), "description: Published.")
    }

    @Test
    fun `New Agent opens a Draft-only Agent with a placeholder name, and Delete confirms and leaves it`() = browse {
        page.navigate(url("/agents"))
        page.locator(".ph button", Page.LocatorOptions().setHasText("New Agent")).click()
        page.waitForURL(Pattern.compile("/agents/new-agent(-\\d+)?$"))
        assertThat(marker).hasText("Draft")
        val name = page.url().substringAfterLast('/')
        assertThat(page.locator("#name")).hasValue(name)
        page.locator(".ph button.danger", Page.LocatorOptions().setHasText("Delete")).click()
        assertThat(page.locator("h1")).hasText("Delete the Draft $name?")
        page.locator("main button.danger").click()
        page.waitForURL(url("/agents"))
        assertThat(page.locator("a[href='/agents/$name']")).hasCount(0)
    }

    @Test
    fun `an edit on the Script page, typed or by the Script helper, is autosaved to its Draft with no Publish, and survives a reload`() = browse {
        http.publishNew("/scripts", listOf("name" to "autosaved-script", "description" to "Published.", "code" to "console.log('published')\n", "vars" to ""))
        val code = home.resolve("scripts").toFile().walk().single { it.name == "script.mjs" }
        page.navigate(url("/scripts/autosaved-script"))
        assertThat(marker).hasText("Published")
        assertThat(saveState).isEmpty()

        val editor = page.locator(".code-editor .cm-content")
        editor.click()
        page.keyboard().press("ControlOrMeta+End")
        page.keyboard().insertText("console.log('typed')")
        assertThat(saveState).hasText(draftSaved)
        assertThat(marker).hasText("Draft · changes not published")
        assertEquals("console.log('published')\n", code.readText())

        page.reload()
        assertThat(editor).containsText("console.log('typed')")
        assertThat(marker).hasText("Draft · changes not published")

        val helper = page.getByRole(AriaRole.COMPLEMENTARY, Page.GetByRoleOptions().setName("Script helper"))
        assertThat(helper).hasAttribute("data-token", Pattern.compile(".+"))
        val (text, failed) = mcpCall(port, helper.getAttribute("data-token")!!, "replace_buffer", mapOf("code" to "console.log('helped')\n"))
        assertFalse(failed, text)
        eventually { assertContains(http.get("/scripts/autosaved-script").body, "helped") }
        page.reload()
        assertThat(editor).hasText("console.log('helped')")

        page.click("button[form=script]")
        page.waitForURL(url("/scripts/autosaved-script"))
        assertThat(marker).hasText("Published")
        assertEquals("console.log('helped')", code.readText().trim()) // a posted textarea's lines end in CRLF
    }

    @Test
    fun `Your Skills on a shipped Agent's page adds and removes a Skill, autosaved to a Draft, and Publish puts it to use`() = browse {
        home.resolve("skills/imported").createDirectories().resolve("SKILL.md").writeText("---\nname: imported\ndescription: Imported.\n---\nDo it.\n")
        page.navigate(url("/agents/shipped-coder"))
        assertThat(marker).hasText("Published")
        assertThat(page.locator("#agent .chipsel .c")).hasText(arrayOf("tdd")) // the shipped Skills, apart and read-only
        assertThat(page.locator("#agent .chipsel button")).hasCount(0)
        val yours = page.locator("#your-skills .c", Page.LocatorOptions().setHasText("imported"))

        page.fill("#add-skill", "imported")
        page.locator("#add-skill").dispatchEvent("change")
        assertThat(yours).hasCount(1)
        assertThat(saveState).hasText(draftSaved)
        assertThat(marker).hasText("Draft · changes not published")
        page.click("button[form=your-skills]")
        page.waitForURL(url("/agents/shipped-coder"))
        assertThat(marker).hasText("Published")
        assertThat(yours).hasCount(1)
        eventually { assertContains(http.get("/catalogue.json").body, """"name":"shipped-coder","description":"Stands in for a shipped codex Agent with Skills of its own.","skills":["tdd","imported"]""") }

        yours.locator("button[title=Remove]").click()
        assertThat(yours).hasCount(0)
        assertThat(saveState).hasText(draftSaved)
        page.click("button[form=your-skills]")
        page.waitForURL(url("/agents/shipped-coder"))
        assertThat(marker).hasText("Published")
        assertThat(yours).hasCount(0)
        assertContains(http.get("/catalogue.json").body, """"skills":["tdd"]""")
    }

    @Test
    fun `the Agent page's chips, Limit rules, added tools and CLI switch save what the page shows`() = browse {
        home.resolve("skills/chip-skill").createDirectories().resolve("SKILL.md").writeText("---\nname: chip-skill\ndescription: Chips.\n---\nChip.\n")
        val file = home.resolve("agents").createDirectories().resolve("chipper.md")
        file.writeText("---\nid: a-chip01\nname: chipper\ncli: claude\nskills: gone, kept\ntools: Read\n---\nChip.\n")
        page.navigate(url("/agents/chipper"))

        page.locator(".chipsel .c", Page.LocatorOptions().setHasText("gone")).locator("button").click()
        page.fill("#add-skill", "chip-skill")
        page.locator("#add-skill").dispatchEvent("change")
        assertThat(page.locator(".chipsel .c")).containsText(arrayOf("kept", "chip-skill"))

        val bash = page.locator(".trow", Page.LocatorOptions().setHasText("Shell commands"))
        assertThat(bash.locator("[data-limit]")).isHidden() // Limit… only once it's ticked
        bash.locator("input[name='tool.Bash']").check()
        bash.locator("[data-limit]").click()
        bash.locator("input[name='limit.Bash']").fill("git log *")
        bash.locator("[data-add]").click()
        bash.locator("input[name='limit.Bash']").nth(1).fill("git diff *")
        assertThat(bash.locator("input[name='limit.Bash']").first()).hasAttribute("placeholder", "e.g. pnpm test")

        page.fill(".add-tool input", "mcp__github__get_issue")
        page.click(".add-tool button")
        assertThat(page.locator(".added")).containsText("An MCP tool.")
        page.fill(".add-tool input", "AskUserQuestion")
        page.press(".add-tool input", "Enter")
        assertThat(page.locator("input[name='tool.AskUserQuestion']")).isChecked()
        assertThat(page.locator("details.others")).hasAttribute("open", "")

        page.click("button[form=agent]")
        eventually {
            assertEquals("---\nid: a-chip01\nname: chipper\ncli: claude\nskills: kept, chip-skill\ntools: AskUserQuestion, Bash(git log *), Bash(git diff *), Read, mcp__github__get_issue\n---\nChip.\n", file.readText())
        }

        page.navigate(url("/agents/chipper"))
        page.selectOption("select[name=cli]", "codex")
        assertThat(page.locator("select[name=sandbox]")).isVisible()
        assertThat(page.locator("input[name='tool.Read']")).isHidden()
        page.fill("fieldset[data-cli=codex] input[name=model]", "gpt-5-codex")
        page.click("button[form=agent]")
        eventually { assertContains(file.readText(), "cli: codex\nskills: kept, chip-skill\nsandbox: workspace-write\nmodel: gpt-5-codex\n") }
    }

    private fun misalignedFields() = (page.evaluate("""() => {
        const box = e => e.getBoundingClientRect()
        const content = e => { const r = box(e), s = getComputedStyle(e)
            return [r.left + parseFloat(s.borderLeftWidth) + parseFloat(s.paddingLeft), r.right - parseFloat(s.borderRightWidth) - parseFloat(s.paddingRight)] }
        const describe = e => e.tagName.toLowerCase() + (e.name ? "[name=" + e.name + "]" : "") + (e.className && typeof e.className === "string" ? "." + e.className.trim().split(/\s+/).join(".") : "")
        const offenders = []
        for (const card of document.querySelectorAll("main .card")) {
            if (!card.checkVisibility()) continue
            const [edge, end] = content(card)
            const title = card.querySelector("h3")?.textContent.trim() ?? "the editor card"
            for (const e of card.querySelectorAll("label.f, .help, .tgroup, .grid2, .grid3, input:not([type=hidden]), select, textarea, .ed, .chipsel")) {
                if (!e.checkVisibility() || e.parentElement.closest(".chipsel, .ed")) continue
                const cell = e.parentElement.closest(".grid2 > .fld, .grid3 > .fld, .card")
                const [left, right] = cell === card ? [edge, end] : [box(cell).left, box(cell).right]
                if (Math.abs(box(e).left - left) > 1) offenders.push(title + ": " + describe(e) + " starts at " + box(e).left + ", not " + left)
                if (e.matches("input:not([type=checkbox], [type=radio]), select, textarea, .ed, .chipsel") && !e.closest(".row") && Math.abs(box(e).right - right) > 1)
                    offenders.push(title + ": " + describe(e) + " ends at " + box(e).right + ", not " + right)
            }
            for (const grid of card.querySelectorAll(".grid2, .grid3")) {
                const rows = {}
                for (const cell of grid.children) if (cell.checkVisibility()) (rows[Math.round(box(cell).top)] ??= []).push(box(cell).width)
                for (const widths of Object.values(rows)) if (Math.max(...widths) - Math.min(...widths) > 1) offenders.push(title + ": a row's fields are " + widths.join(", ") + "px wide")
            }
        }
        return offenders
    }""") as List<*>).map { it.toString() }

    @Test
    fun `on the Agent and Skill pages, every field in a card starts at the card's content edge and fills its cell`() = browse {
        home.resolve("agents").createDirectories().resolve("lined-up.md").writeText("---\nname: lined-up\ncli: claude\nskills: implement\ntools: Read, Bash(git log *)\n---\nLined up.\n")
        home.resolve("skills/lined-up").createDirectories().resolve("SKILL.md").writeText("---\nname: lined-up\ndescription: Lined up.\n---\nLined up.\n")
        for (width in listOf(1440, 1920)) {
            page.setViewportSize(width, 900)
            for (path in listOf("/agents/lined-up", http.newDraft("/agents"), "/skills/lined-up")) {
                page.navigate(url(path))
                assertThat(page.locator(".ed").first()).isVisible()
                assertEquals(emptyList(), misalignedFields(), "$path at ${width}px")
            }
        }
    }

    @Test
    fun `the Agent page's cards stay stacked full width, and Who it is lays out in three columns from 1600px`() = browse {
        home.resolve("agents").createDirectories().resolve("wide.md").writeText("---\nname: wide\ncli: claude\n---\nWide.\n")
        page.setViewportSize(1600, 900)
        page.navigate(url("/agents/wide"))
        val (left, right) = contentEdges("main")
        val cards = page.locator("form.agent .card")
        assertEquals(3, cards.count())
        for (i in 0 until 3) assertEquals(right - left, cards.nth(i).boundingBox().width, 1.0, "card ${i + 1} is full width")
        assertTrue(cards.nth(0).boundingBox().let { it.y + it.height } <= cards.nth(1).boundingBox().y, "the cards are stacked")
        val (name, description, cli) = listOf("#name", "#description", "#cli").map { page.locator(it).boundingBox() }
        assertEquals(name.y, description.y, 1.0)
        assertEquals(name.y, cli.y, 1.0)
        assertTrue(name.x + name.width < description.x && description.x + description.width < cli.x, "Name, Description and CLI sit side by side")
    }

    @Test
    fun `the Skill page gives its side column 360px and the editors the rest`() = browse {
        page.setViewportSize(1920, 1080)
        page.navigate(url(http.newDraft("/skills")))
        val (left, right) = contentEdges("main")
        val editors = page.locator(".card:has(#skill)").boundingBox()
        val side = page.locator(".card:has(h3:text-is('Run by'))").boundingBox()
        assertEquals(360.0, side.width, 1.0)
        assertEquals(right, side.x + side.width, 1.0)
        assertEquals(left, editors.x, 1.0)
        val gap = page.locator(".card:has(#skill)").evaluate("e => parseFloat(getComputedStyle(e.parentElement).columnGap)") as Number
        assertEquals(side.x - gap.toDouble(), editors.x + editors.width, 1.0)
    }

    @Test
    fun `the Agent and Skill editors and Review changes use most of the width at 1440px and 1920px, and no page scrolls sideways at 1280px`() = browse {
        val entry = staleStoryReviewEntry(wideAndTall, head = "HEAD")
        home.resolve("agents").createDirectories().resolve("roomy.md").writeText("---\nname: roomy\ncli: claude\ntools: Read\n---\nRoomy.\n")
        home.resolve("skills/roomy").createDirectories().resolve("SKILL.md").writeText("---\nname: roomy\ndescription: Roomy.\n---\n" + "A long line of the Skill. ".repeat(40) + "\n")
        val workAreas = listOf(
            Triple("the Agent editor", "/agents/roomy", ".card:has(#instructions) .ed"),
            Triple("the Skill editor", "/skills/roomy", ".card:has(#skill) .ed"),
            Triple("Review changes' Story tab", "$entry#s1-3", ".step:not([hidden]) .code"),
            Triple("Review changes' By repo tab", "$entry?tab=repo", ".walk [data-repo]"),
        )
        fun windowWidth() = (page.evaluate("document.documentElement.clientWidth") as Number).toDouble()
        fun open(path: String, area: String) = page.locator(area).first().also { page.navigate(url(path)); assertThat(it).isVisible() }

        page.setViewportSize(1280, 900)
        for ((name, path, area) in workAreas) {
            open(path, area)
            val scroll = (page.evaluate("document.documentElement.scrollWidth") as Number).toDouble()
            assertTrue(scroll <= windowWidth(), "$name is ${scroll}px wide in a ${windowWidth()}px window")
        }
        for (width in listOf(1440, 1920)) {
            page.setViewportSize(width, 900)
            for ((name, path, area) in workAreas) {
                val used = open(path, area).boundingBox().width
                val beside = windowWidth() - page.locator("nav.side").boundingBox().width
                assertTrue(used > beside / 2, "$name's work area is ${used}px of the ${beside}px beside the sidebar at ${width}px")
            }
        }
    }

    @Test
    fun `the Skill page edits only SKILL md, with no Result schema editor`() = browse {
        page.navigate(url(http.newDraft("/skills")))
        assertThat(page.locator("#skill .ed")).hasCount(1)
        assertThat(page.locator(".ed.json")).hasCount(0)
        assertThat(page.locator("button[form=skill]")).isEnabled()
    }

    @Test
    fun `Dark mode and Light mode in the sidebar switch the theme and stay on the page`() = browse {
        settings(null, "theme: light\n")
        page.navigate(url("/agents"))
        assertThat(page.getByText("Light mode")).isHidden()
        page.getByText("Dark mode").click()
        page.waitForURL { it.endsWith("/agents") }
        assertEquals("rgb(11, 13, 18)", page.evaluate("getComputedStyle(document.documentElement).backgroundColor"))
        assertThat(page.getByText("Dark mode")).isHidden()
        page.getByText("Light mode").click()
        assertThat(page.locator("meta[name=color-scheme]")).hasAttribute("content", "light")
    }

    @Test
    fun `on Settings, Add and Remove edit a list's rows, which Save writes, and Copy copies the login command`() = browse {
        settings(null, "vaults:\n  work: /tmp/work\n  side: /tmp/side\ndefault-vault: work\nagent-folders: [/tmp/a]\n")
        page.navigate(url("/settings"))
        fun card(title: String) = page.locator(".card", Page.LocatorOptions().setHas(page.locator("h3:text-is('$title')")))
        val vaults = card("Vaults")
        vaults.getByText("Remove").first().click()
        for ((name, row) in listOf("new" to 1, "more" to 2)) {
            vaults.getByText("＋ Add").click()
            val added = vaults.locator(".listed > .row:visible").nth(row)
            added.getByLabel("Name").fill(name)
            added.getByLabel("Path").fill("/tmp/$name")
        }
        vaults.locator(".listed > .row:visible").nth(2).getByText("default").click()
        val folders = card("Agent folders")
        folders.getByText("Remove").first().click()
        folders.getByText("＋ Add folder").click()
        folders.locator("input[name=agent-folders]:visible").fill("/tmp/b")
        page.locator(".cmd button").click()
        assertThat(page.locator(".cmd button")).hasText("Copied")
        page.locator(".ph button.primary").click()
        eventually {
            val saved = org.yaml.snakeyaml.Yaml().load<Map<*, *>>(home.resolve("settings.yaml").readText())
            assertEquals(mapOf("side" to "/tmp/side", "new" to "/tmp/new", "more" to "/tmp/more"), saved["vaults"])
            assertEquals("more", saved["default-vault"])
            assertEquals(listOf("/tmp/b"), saved["agent-folders"])
        }
    }

    @Test
    fun `on Settings, the Source map and instructions modals end just under their Save`() = browse {
        settings(null, sourceMapLines() + "profiles:\n  work: {vault: notes, source-map: work}\n")
        page.navigate(url("/settings"))
        for ((id, field) in listOf("source-map-0" to "textarea", "instructions-0" to ".cm-content")) {
            page.locator("[data-edit=$id]").click()
            val modal = page.locator("dialog#$id")
            assertThat(modal.locator(field)).isVisible()
            settled(modal)
            val (box, save) = modal.boundingBox() to modal.locator(".go").boundingBox()
            assertTrue(box.y + box.height - (save.y + save.height) < 60, "$id ends ${box.y + box.height - (save.y + save.height)}px under its Save")
            modal.locator("button[title=Close]").click()
        }
    }

    @Test
    fun `on Settings, a Profile's Verbosity is picked beside its instructions' Save`() = browse {
        settings(null, sourceMapLines() + "profiles:\n  work: {vault: notes, source-map: work}\n")
        page.navigate(url("/settings"))
        page.locator("[data-edit=instructions-0]").click()
        val modal = page.locator("dialog#instructions-0")
        val verbosity = modal.getByRole(AriaRole.RADIOGROUP, Locator.GetByRoleOptions().setName("Verbosity"))
        assertThat(verbosity.getByLabel("Normal")).isChecked()
        verbosity.getByText("Brief").click()
        modal.locator(".go button").click()
        page.waitForURL(url("/settings"))
        val saved = org.yaml.snakeyaml.Yaml().load<Map<*, *>>(home.resolve("settings.yaml").readText())
        assertEquals("brief", ((saved["profiles"] as Map<*, *>)["work"] as Map<*, *>)["verbosity"])
        assertThat(page.locator("input[name='profiles[0].verbosity']")).hasValue("brief")
    }

    @Test
    fun `on Settings, a Secret is added with a value the page never shows, and a Protected Command picks Secrets by label`() = browse {
        settings(null, "secrets:\n  npm: [NPM_TOKEN]\n")
        val secrets = home.resolve("secrets.yaml").apply { writeText("npm: tok-old\n") }
        try {
            page.navigate(url("/settings"))
            assertFalse("tok-old" in page.content())
            fun card(title: String) = page.locator(".card", Page.LocatorOptions().setHas(page.locator("h3:text-is('$title')")))
            val card = card("Secrets")
            assertThat(card.getByLabel("Value").first()).hasAttribute("placeholder", "••••")
            card.getByText("＋ Add").click()
            val added = card.locator(".listed > .row:visible").nth(1)
            added.getByLabel("Label").fill("maven")
            added.getByLabel("Value").fill("tok-mvn")
            added.getByLabel("Variable names").fill("MAVEN_TOKEN, MVN_TOKEN")
            val commands = card("Protected Commands")
            commands.getByText("＋ Add").click()
            val command = commands.locator(".listed > .row:visible").first()
            command.getByLabel("Command prefix").fill("yarn")
            command.getByLabel("Secrets").selectOption("npm")
            page.locator(".ph button.primary").click()
            eventually {
                val saved = org.yaml.snakeyaml.Yaml().load<Map<*, *>>(home.resolve("settings.yaml").readText())
                assertEquals(mapOf("npm" to listOf("NPM_TOKEN"), "maven" to listOf("MAVEN_TOKEN", "MVN_TOKEN")), saved["secrets"])
                assertEquals(mapOf("yarn" to listOf("npm")), saved["protected-commands"])
                assertEquals(mapOf("npm" to "tok-old", "maven" to "tok-mvn"), org.yaml.snakeyaml.Yaml().load<Map<*, *>>(secrets.readText()))
            }
            page.navigate(url("/settings"))
            assertFalse("tok-" in page.content())
            assertThat(card("Protected Commands").locator("select").first().locator("option")).hasText(arrayOf("npm", "maven")) // once saved, offered
        } finally {
            secrets.deleteIfExists()
        }
    }

    @Test
    fun `a page follows one SSE connection, so three tabs on Run pages and a Transcript leave a fourth free to navigate`() = browse {
        settings(null)
        val (process, plane) = row("followed", """<bpmn:startEvent id="start" />""", """<bpmn:userTask id="wait" name="Wait" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("followed", "Followed", process, plane))
        val run = http.post("/processes/followed/runs", mapOf()).path
        // Tabs share a browser's 6 connections per host, so they share one context here; each counts its open EventSources.
        val tabs = page.context().browser()!!.newContext().apply {
            addCookies(page.context().cookies())
            addInitScript("const Opened = EventSource; window.sources = []; EventSource = class extends Opened { constructor(...a) { super(...a); sources.push(this); } };")
        }
        fun open(path: String) = tabs.newPage().apply { navigate(url(path)) }
        fun Page.drawer() {
            eventually { locator("#diagram .djs-element[data-element-id='wait']").click() }
            assertThat(locator("#drawer-head")).isVisible() // the drawer's stream has come
        }
        fun Page.following() = (evaluate("sources.filter(source => source.readyState !== EventSource.CLOSED).length") as Number).toInt()

        val open = listOf(open(run).apply { drawer() }, open("$run/tasks/wait/transcript"), open(run).apply { drawer() })
        val fourth = tabs.newPage().apply { setDefaultNavigationTimeout(5_000.0) }
        for (path in listOf("/", "/runs", run, "/processes/followed")) {
            fourth.navigate(url(path))
            assertEquals(1, fourth.following(), path)
        }
        for (tab in open) assertEquals(1, tab.following(), tab.url())
    }

    /** Starts a Run of agent-task.bpmn, whose Task `draft` plays [session] on the fake claude [claude]; the Run's path. */
    private fun running(claude: FakeClaude, vararg session: String): String {
        agentTask(claude)
        claude.session("draft", *session)
        http.import("agent-task.bpmn")
        http.get("/processes/agent-task")
        return http.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path
    }

    private val streaming = arrayOf(
        """{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}""",
        """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Almost **there**"}}}""",
    )

    @Test
    fun `a running Task's clock ticks, and its text streams in with a caret until the block ends`() = browse {
        val claude = FakeClaude()
        val run = running(claude, "wait one", *streaming, "wait two", """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"Almost **there**."}]}}""", "wait three")
        page.navigate(url("$run/tasks/draft/transcript"))
        val clock = page.locator("#status [data-since]")
        assertThat(clock).hasText(Pattern.compile("\\d+s"))
        val first = clock.textContent()
        assertThat(clock).not().hasText(first) // a second later
        assertThat(page.locator("#status .shimmer")).hasText(Pattern.compile("\\w+…"))

        claude.release("one")
        assertThat(page.locator("#live.caret")).containsText("Almost there")
        assertEquals("\"▍\"", page.evaluate("getComputedStyle(document.querySelector('#live.caret > :last-child'), '::after').content"))
        page.screenshot("liveness-streaming")
        claude.release("two")
        assertThat(page.locator("#entries .text.new")).containsText("Almost there.") // the whole block fades in
        assertThat(page.locator("#live")).not().hasClass(Pattern.compile("caret"))
        claude.release("three")
    }

    @Test
    fun `a new Notification list entry flashes once as it arrives`() = browse {
        settings(null)
        val (process, plane) = row("flashed", """<bpmn:startEvent id="start" />""", """<bpmn:userTask id="wait" name="Wait" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("flashed", "Flashed", process, plane))
        page.navigate(url("/notifications"))
        page.waitForTimeout(1500.0) // the list's first update has come
        val run = http.post("/processes/flashed/runs", mapOf()).path
        val row = page.locator("#list tr.click", Page.LocatorOptions().setHas(page.locator("a[href='$run']")))
        assertThat(row).hasClass(Pattern.compile("\\bflash\\b"))
        assertThat(row).not().hasClass(Pattern.compile("\\bflash\\b")) // once: it goes as its animation ends
    }

    @Test
    fun `with reduced motion nothing shimmers, flashes or spins, and the clock still ticks`() = browse {
        page.emulateMedia(Page.EmulateMediaOptions().setReducedMotion(com.microsoft.playwright.options.ReducedMotion.REDUCE))
        val claude = FakeClaude()
        val run = running(claude, "wait one", *streaming, "wait two")
        page.navigate(url("$run/tasks/draft/transcript"))
        val clock = page.locator("#status [data-since]")
        assertThat(clock).hasText(Pattern.compile("\\d+s"))
        val first = clock.textContent()
        val spin = page.locator("#status .spin")
        val glyph = spin.textContent()
        assertThat(clock).not().hasText(first) // the clock still ticks
        assertEquals(glyph, spin.textContent()) // the spinner stands still
        assertEquals("none", page.evaluate("getComputedStyle(document.querySelector('#status .shimmer')).animationName"))
        claude.release("one")
        assertThat(page.locator("#live.caret")).containsText("Almost there")
        assertEquals("none", page.evaluate("getComputedStyle(document.querySelector('#live.caret > :last-child'), '::after').animationName"))
        claude.release("two")
    }

    /** An Agent Task [id] running `writer`'s Skill `draft`. */
    private fun agentTaskXml(id: String, name: String) = """<bpmn:serviceTask id="$id" name="$name" operaton:type="external" operaton:topic="agent">""" +
        """<bpmn:extensionElements><operaton:properties><operaton:property name="ants.agent" value="writer" />""" +
        """<operaton:property name="ants.skills" value="draft" /></operaton:properties></bpmn:extensionElements></bpmn:serviceTask>"""

    /**
     * [steps], BPMN elements one after another, joined by sequence flows; and the plane of [owner] laying them out in a
     * row, a sub-process among them collapsed.
     */
    private fun row(owner: String, vararg steps: String): Pair<String, String> {
        val ids = steps.map { Regex("""id="([^"]+)"""").find(it)!!.groupValues[1] }
        // An event is 36 wide and high, any other element 100 by 80, all centred on y = 120, 150 apart.
        val events = steps.map { it.substringBefore(' ').endsWith("Event") }
        fun x(i: Int) = 100 + i * 150
        fun right(i: Int) = x(i) + if (events[i]) 36 else 100
        val shapes = steps.indices.joinToString("") { i ->
            val collapsed = if (steps[i].startsWith("<bpmn:subProcess")) """ isExpanded="false"""" else ""
            val bounds = if (events[i]) """y="102" width="36" height="36"""" else """y="80" width="100" height="80""""
            """<bpmndi:BPMNShape id="${ids[i]}_di" bpmnElement="${ids[i]}"$collapsed><dc:Bounds x="${x(i)}" $bounds /></bpmndi:BPMNShape>"""
        }
        val flows = steps.indices.zipWithNext()
        val semantic = steps.joinToString("") + flows.joinToString("") { (a, b) -> """<bpmn:sequenceFlow id="${ids[a]}-${ids[b]}" sourceRef="${ids[a]}" targetRef="${ids[b]}" />""" }
        val edges = flows.joinToString("") { (a, b) ->
            """<bpmndi:BPMNEdge id="${ids[a]}-${ids[b]}_di" bpmnElement="${ids[a]}-${ids[b]}"><di:waypoint x="${right(a)}" y="120" /><di:waypoint x="${x(b)}" y="120" /></bpmndi:BPMNEdge>"""
        }
        return semantic to """<bpmndi:BPMNDiagram id="${owner}_diagram"><bpmndi:BPMNPlane id="${owner}_plane" bpmnElement="$owner">$shapes$edges</bpmndi:BPMNPlane></bpmndi:BPMNDiagram>"""
    }

    /** The Process [key] named [name]: its [elements] and their [planes]. */
    private fun definitions(key: String, name: String, elements: String, planes: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                          xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                          xmlns:operaton="http://operaton.org/schema/1.0/bpmn" id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="$key" name="$name" isExecutable="true" operaton:historyTimeToLive="180">$elements</bpmn:process>
          $planes
        </bpmn:definitions>"""

    private fun deploy(bpmn: String) {
        val deployed = http.post("/processes/deploy", mapOf("bpmn" to bpmn))
        assertEquals(200, deployed.status, deployed.body)
    }
}

/**
 * Headless Chromium with its own sandbox off (`--no-sandbox`), [executable] or the one `./gradlew playwright` installed.
 * Where it can't launch the test is skipped, or fails in CI, saying how to let it launch.
 */
internal fun launchChromium(playwright: Playwright, executable: Path? = null) = try {
    playwright.chromium().launch(BrowserType.LaunchOptions().setChromiumSandbox(false).setArgs(listOf("--disable-gpu")).setExecutablePath(executable))
} catch (e: RuntimeException) {
    val reason = e.message.orEmpty().let { it.substringAfter("message='", it).lineSequence().first() }
    val help = """
        |Chromium didn't launch, so this browser test is skipped ($reason).
        |It runs with Chromium's own sandbox off (--no-sandbox). Inside nono's Seatbelt sandbox, the nono profile also needs
        |read access to ~/Library/Input Methods and ~/Library/Keyboard Layouts, and these unsafe_macos_seatbelt_rules:
        |  (allow iokit-open-user-client (iokit-user-client-class "RootDomainUserClient"))
        |  (allow mach-register (global-name-prefix "org.chromium.Chromium.MachPortRendezvousServer."))
        |  (allow mach-lookup (global-name-prefix "org.chromium.Chromium.MachPortRendezvousServer."))
        |  (allow mach-register (local-name "com.apple.axserver"))
        |  (allow mach-register (local-name "com.apple.tsm.portname"))
        |  (allow user-preference-read (preference-domain "com.apple.HIToolbox"))
        |Without Chromium, ./gradlew playwright --args="install chromium" installs it. CI always runs these tests.
    """.trimMargin()
    if (System.getenv("CI") != null) throw AssertionError(help, e)
    abort(help)
}

/**
 * Saves the page as it looks now to build/reports/browser/[name].png, for a person or an agent to look at; any test with
 * a Playwright [Page] can call it. CI keeps build/reports.
 */
internal fun Page.screenshot(name: String): Path = Path.of("build/reports/browser/$name.png").also { screenshot(Page.ScreenshotOptions().setPath(it).setFullPage(true)) }

/** What a Chromium that can't launch says, without the test app. */
@Tag("browser")
class ChromiumLaunchTest {
    @Test
    fun `a Chromium that can't launch names nono's Seatbelt rules and --no-sandbox`() {
        Playwright.create().use { playwright ->
            val failed = assertFails { launchChromium(playwright, Path.of("build/no-chromium-here")) }
            for (needed in listOf(
                "--no-sandbox", "unsafe_macos_seatbelt_rules", "~/Library/Input Methods", "~/Library/Keyboard Layouts",
                """(allow iokit-open-user-client (iokit-user-client-class "RootDomainUserClient"))""",
                """(allow mach-register (global-name-prefix "org.chromium.Chromium.MachPortRendezvousServer."))""",
                """(allow mach-lookup (global-name-prefix "org.chromium.Chromium.MachPortRendezvousServer."))""",
                """(allow mach-register (local-name "com.apple.axserver"))""",
                """(allow mach-register (local-name "com.apple.tsm.portname"))""",
                """(allow user-preference-read (preference-domain "com.apple.HIToolbox"))""",
            )) assertContains(failed.message.orEmpty(), needed)
        }
    }
}
