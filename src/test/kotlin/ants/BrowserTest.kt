package ants

import com.microsoft.playwright.Browser.NewContextOptions
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.KeyboardModifier
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
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        page.screenshot("modeler") // the modeler with the three Tasks drawn
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
        page.locator("dialog .mh button", Page.LocatorOptions().setHasText("Create and use")).click()

        assertThat(page.locator("dialog")).hasCount(0)
        assertThat(page.locator("#explain select").first()).hasValue("helper")
        assertEquals(modeler, page.url())
        assertThat(shape(task)).hasClass(selected) // the Task drawn, never saved, is still there
        assertTrue(home.resolve("agents/helper.md").toFile().exists())
    }

    @Test
    fun `picking an Agent picks its first Skill and says what later Tasks can read, and the Checks turn Ready to deploy`() = browse {
        agentTask(null, mapOf("draft" to """{"type":"object","properties":{"notes":{"type":"string"}}}""", "polish" to null))
        val task = appendAgentTask()
        assertThat(page.locator("#summary")).hasText(Pattern.compile("\\d+ to fix"))
        label("Agent").selectOption("writer")
        assertThat(label("Skill")).hasValue("draft")
        assertThat(panel).containsText("Later Tasks can read: ${'$'}{$task.output.notes}")
        assertThat(page.locator("#summary")).hasText("Ready to deploy")
        page.screenshot("modeler-agent-task")
    }

    @Test
    fun `New Skill says the Task has no Agent yet, and New Agent comes with the Task's Skill`() = browse {
        agentTask(null)
        appendAgentTask()
        button("New Skill").click()
        assertThat(page.locator("dialog.modal")).containsText("This Task has no Agent yet.")
        page.keyboard().press("Escape")
        label("Agent").selectOption("writer")
        assertThat(label("Skill")).hasValue("draft")
        button("New Skill").click()
        assertThat(page.locator("dialog.modal")).containsText("Added to writer's Skills, and picked on this Task.")
        page.waitForTimeout(1000.0) // the form loads in its frame
        page.screenshot("modeler-new-skill")
        page.keyboard().press("Escape")
        button("New Agent").click()
        assertThat(page.frameLocator("dialog iframe").locator(".chipsel input[type=hidden][name=skills]")).hasValue("draft") // a chip on the Agent page (#98)
    }

    @Test
    fun `a User Task asks for its kind's fields and says what later Tasks can read`() = browse {
        page.navigate(url("/processes/new?name=Gated&from="))
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
        val pages = listOf("/", "/notifications", "/runs", "/processes", "/agents", "/agents/new", "/skills", "/skills/new", "/templates", "/tickets", "/settings") + diagrams
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
        page.navigate(url("/agents/new"))
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

    private fun picker(stored: String = "") {
        settings(null, sourceMapLines(repo("api"), repo("web"), repo("webhooks")))
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

    private fun startPicked(page: Page = this.page, prompt: Locator = page.locator("textarea[name=prompt] + .ed .cm-content")): String {
        prompt.fill("Add dark mode")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Start").setExact(true)).click()
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

    @Test
    fun `with JavaScript off the start form's Repos field is the Source map's checkboxes and a text input, which still start the Run`() = browse {
        val elsewhere = repo("elsewhere").remote
        picker()
        val plain = page.context().browser()!!.newContext(NewContextOptions().setJavaScriptEnabled(false)).apply { addCookies(page.context().cookies()) }.newPage()
        plain.navigate(url("/processes/picker"))
        assertThat(plain.getByRole(AriaRole.COMBOBOX)).hasCount(0)
        assertThat(plain.locator("#start input[type=checkbox]")).hasCount(3)

        plain.getByLabel("web", Page.GetByLabelOptions().setExact(true)).check()
        plain.locator("#field-repos").fill("$elsewhere")
        assertEquals("""["web","$elsewhere"]""", startPicked(plain, plain.locator("textarea[name=prompt]")))
    }

    private fun byRepoReviewEntryOfApiCommitting(file: String, text: String): String {
        agentTask(FakeClaude(), mapOf("mend" to null, "ship" to null), extra = reviewSourceMap())
        http.upload("/processes", "file", "review.bpmn", javaClass.getResource("/review.bpmn")!!.readBytes())
        val run = http.post("/processes/review/runs", listOf("repos" to "api", "repos" to "web")).path.removePrefix("/runs/")
        var entry = ""
        eventually { entry = assertNotNull(Regex("""href="(/notifications/[^"]+)"""").find(http.get("/notifications").body)).groupValues[1] }
        commitFile(workspace(run).resolve("repos/api"), file, text, "Change $file")
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

    @Test
    fun `the composer never covers the end of Review changes however tall it grows, and a page without one keeps its own bottom padding`() = browse {
        val entry = staleStoryReviewEntry()
        page.setViewportSize(1440, 700)
        page.navigate(url("$entry#s1-1"))
        page.click(".step:not([hidden]) [data-comment]")
        page.locator(".step:not([hidden]) [name=body]").fill("Why?")
        page.click(".step:not([hidden]) .stepcomment button")
        assertThat(page.locator("#comments")).isVisible()
        val composer = page.locator(".composer")
        fun clear(bar: String) = eventually {
            page.evaluate("scrollTo(0, document.documentElement.scrollHeight)")
            val top = composer.boundingBox().y
            for (selector in listOf(".step:not([hidden]) .code", "#comments", "#comment + .ed")) {
                val box = page.locator(selector).boundingBox()
                assertTrue(box.y + box.height <= top, "$selector ends at ${box.y + box.height}px, under the $bar composer at ${top}px")
            }
        }
        clear("one-line")
        page.locator(".composer > div").evaluate("bar => { bar.style.flexWrap = 'wrap'; bar.insertAdjacentHTML('beforeend', '<p style=\"flex-basis: 100%; height: 240px; margin: 0\"></p>') }")
        assertTrue(composer.boundingBox().height > 240)
        clear("grown")

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
        val ticket = page.locator("#ticket").boundingBox().width
        assertTrue(ticket <= 860, "the Ticket is ${ticket}px wide")
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
        val entry = staleStoryReviewEntry(wideAndTall)
        page.setViewportSize(1440, 240)
        page.navigate(url("$entry#s1-3"))
        val code = page.locator(".step:not([hidden]) .code")
        assertThat(code).containsText("line 12")
        assertScrollsBothWaysAboveTheComposerKeepingItsNumbers(code, code.locator(".ln").nth(1))
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

    private fun staleStoryReviewEntry(helloText: String = "hello\nWORLD\n"): String {
        val claude = FakeClaude()
        agentTask(claude, mapOf("narrate" to null, "mend" to null, "ship" to null), extra = reviewSourceMap())
        val set = """{"title":"Nothing changes","ranges":[{"repo":"api","base":"origin/main","head":"origin/main"}],"stories":[{"id":"s","name":"Greeting",""" +
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
    fun `New Process on the Processes list opens a modal that starts the modeler on a name, and a row opens its Process`() = browse {
        http.import("start-end.bpmn")
        page.navigate(url("/processes"))
        page.click("tr.click >> text=you") // anywhere on the row
        page.waitForURL(url("/processes/start-end"))
        page.navigate(url("/processes"))
        page.click(".ph button.primary")
        page.getByLabel("Name").fill("From the list")
        page.click("#new-process button.primary")
        page.waitForURL { it.contains("/processes/new?name=From+the+list") }
    }

    @Test
    fun `the Agent page's chips, Limit rules, added tools and CLI switch save what the page shows`() = browse {
        home.resolve("skills/chip-skill").createDirectories().resolve("SKILL.md").writeText("---\nname: chip-skill\ndescription: Chips.\n---\nChip.\n")
        val file = home.resolve("agents").createDirectories().resolve("chipper.md")
        file.writeText("---\nname: chipper\ncli: claude\nskills: gone, kept\ntools: Read\n---\nChip.\n")
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
            assertEquals("---\nname: chipper\ncli: claude\nskills: kept, chip-skill\ntools: AskUserQuestion, Bash(git log *), Bash(git diff *), Read, mcp__github__get_issue\n---\nChip.\n", file.readText())
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
            for (path in listOf("/agents/lined-up", "/agents/new", "/skills/lined-up")) {
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
        page.navigate(url("/skills/new"))
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
        val entry = staleStoryReviewEntry(wideAndTall)
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
    fun `the Skill page's schema editor says whether the schema is valid`() = browse {
        page.navigate(url("/skills/new"))
        val bar = page.locator(".ed.json .bar")
        page.locator(".ed.json .cm-content").fill("""{"type": "strin"}""")
        assertThat(bar).containsText("✗")
        assertThat(page.locator("button[form=skill]")).isDisabled()
        page.locator(".ed.json .cm-content").fill("""{"type": "object", "properties": {"text": {"type": "string"}}}""")
        assertThat(bar).containsText("✓ valid schema")
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
