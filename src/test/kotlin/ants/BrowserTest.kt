package ants

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import java.util.regex.Pattern
import kotlin.io.path.deleteExisting
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * What only the pages' scripts do, in a real browser: Chromium through Playwright, on the running test Ants, logged in.
 * CI installs Chromium (test.yml); where it can't launch, such as in nono's sandbox, these tests are skipped
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
        page = launchChromium(playwright).newPage()
        page.onConsoleMessage { if (it.type() == "error") errors += "console: ${it.text()}" }
        page.onPageError { errors += "page error: $it" }
        page.navigate(url("/login"))
        page.fill("input[name=username]", "admin")
        page.fill("input[name=password]", "admin")
        page.click("button[type=submit]")
        page.waitForURL { !it.contains("/login") }
    }

    @AfterEach
    fun close() {
        if (::playwright.isInitialized) playwright.close()
    }

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    /**
     * Runs [steps]; a failure says where the page was, its console errors, and where its screenshot is (CI keeps
     * build/reports).
     */
    private fun browse(steps: () -> Unit) = try {
        steps()
    } catch (e: Throwable) {
        val shot = runCatching { screenshot("failed/$test") }.getOrNull()
        throw AssertionError("${e.message}\nAt ${page.url()}, screenshot $shot. The page's console errors:\n" + errors.joinToString("\n").ifEmpty { "(none)" }, e)
    }

    /** Saves the page as it looks now to build/reports/browser/[name].png, for a person or an agent to look at. */
    private fun screenshot(name: String): Path = Path.of("build/reports/browser/$name.png").also { page.screenshot(Page.ScreenshotOptions().setPath(it).setFullPage(true)) }

    /** An element's shape on the diagram, by its id. */
    private fun shape(id: String) = page.locator("#canvas .djs-element[data-element-id='$id'], #diagram .djs-element[data-element-id='$id']")

    private val pad get() = page.locator(".djs-context-pad")
    private val heading get() = page.locator("#explain h2")
    private val selected = Pattern.compile("\\bselected\\b")

    /** Opens the modeler on a blank Process and appends an Agent Task to its start event; the Task's id. */
    private fun appendAgentTask(): String {
        page.navigate(url("/processes/new?name=Drawn+here&from="))
        shape("start").click()
        pad.locator("[data-action='append.agent-task']").click()
        assertThat(heading).hasText("Agent Task")
        return page.locator("#canvas .djs-shape.selected").getAttribute("data-element-id")!!
    }

    @Test
    fun `the hover menu appends an Agent, User or Built-in Task and offers nothing Ants doesn't run`() = browse {
        page.navigate(url("/processes/new?name=Hover+menu&from="))
        for ((action, name) in listOf("append.agent-task" to "Agent Task", "append.user-task" to "User Task", "append.built-in-task" to "Built-in Task")) {
            shape("start").click()
            assertThat(heading).hasText("Start event")
            assertThat(pad.locator("[data-action='$action']")).isVisible()
            for (gone in listOf("append.append-task", "append.intermediate-event", "append")) assertThat(pad.locator("[data-action='$gone']")).hasCount(0)
            pad.locator("[data-action='$action']").click()
            assertThat(heading).hasText(name)
        }
        screenshot("modeler") // the modeler with the three Tasks drawn
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
    fun `New Agent's modal keeps the unsaved diagram and picks the new Agent on the Task`() = browse {
        val task = appendAgentTask()
        val modeler = page.url()
        page.locator("#explain button", Page.LocatorOptions().setHasText("New Agent")).click()
        val form = page.frameLocator("dialog iframe")
        form.locator("input[name=name]").fill("helper")
        form.locator("button", com.microsoft.playwright.FrameLocator.LocatorOptions().setHasText("Create and use")).click()

        assertThat(page.locator("dialog")).hasCount(0)
        assertThat(page.locator("#explain select").first()).hasValue("helper")
        assertEquals(modeler, page.url())
        assertThat(shape(task)).hasClass(selected) // the Task drawn, never saved, is still there
        assertTrue(home.resolve("agents/helper.md").toFile().exists())
    }

    @Test
    fun `on the Run page a call activity opens the called Process, and the breadcrumb leads back`() = browse {
        settings(null)
        val (called, calledPlane) = row("called", """<bpmn:startEvent id="start" />""", """<bpmn:userTask id="review" name="Review" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("called", "Called", called, calledPlane))
        val (caller, callerPlane) = row("caller", """<bpmn:startEvent id="start" />""", """<bpmn:callActivity id="build" name="Build" calledElement="called" />""", """<bpmn:endEvent id="end" />""")
        deploy(definitions("caller", "Caller", caller, callerPlane))
        val run = http.post("/processes/caller/runs", mapOf()).path
        assertTrue(run.startsWith("/runs/"), run)

        page.navigate(url(run))
        // A click opens the called Process once the live update has named it.
        eventually {
            shape("build").click()
            assertTrue(page.isVisible("#trail"), "no breadcrumb after clicking the call activity")
        }
        assertThat(shape("review")).isVisible()
        assertThat(page.locator("#trail")).hasText("Caller › Build")
        page.click("#trail a")
        assertThat(shape("build")).isVisible()
        assertThat(shape("review")).hasCount(0)
        assertThat(page.locator("#trail")).isHidden()
    }

    /** An Agent Task [id] running `writer`'s Skill `draft`. */
    private fun agentTaskXml(id: String, name: String) = """<bpmn:serviceTask id="$id" name="$name" operaton:type="external" operaton:topic="agent">""" +
        """<bpmn:extensionElements><operaton:properties><operaton:property name="ants.agent" value="writer" />""" +
        """<operaton:property name="ants.skill" value="draft" /></operaton:properties></bpmn:extensionElements></bpmn:serviceTask>"""

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
