package ants

import com.microsoft.playwright.Browser.NewContextOptions
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.ColorScheme
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The look test: every page in light and in dark, as build/reports/browser/look/<page>/app-{light,dark}.png, and on a
 * phone in build/reports/browser/look-phone, for a person or an agent to look at. It asserts nothing about the look
 * itself. The app is seeded with Runs in different states, waiting entries, a Schedule, and the shipped Processes,
 * Agents and Skills, a Dashboard with every Widget type and chart kind, and two devices with Alerts on. Its one check of the look is that a
 * Dashboard fits a phone and shows no light box in Dark mode. Like [BrowserTest], it is skipped where Chromium can't launch.
 */
@Tag("browser")
class LookTest : AntsTest() {
    @Autowired
    private lateinit var processes: Processes

    @Autowired
    private lateinit var dashboards: Dashboards

    private val http by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()
    private val look: Path = Path.of("build/reports/browser/look")
    private val lookOnPhone: Path = Path.of("build/reports/browser/look-phone")

    /** The push services of the devices with Alerts on that Settings lists. */
    private val phones = List(2) { FakePushService() }

    @AfterEach
    fun closePhones() = phones.forEach { it.close() }

    private fun desktop() = NewContextOptions().setViewportSize(1440, 900)
    private fun phone() = NewContextOptions().setViewportSize(390, 844).setIsMobile(true).setHasTouch(true)

    /** A page, shot after [appScript] runs in it, for a state no URL reaches. */
    private class Shot(val name: String, val app: String, val appScript: String? = null)

    private fun result(json: String) = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":$json}"""

    @Test
    @Timeout(25, unit = TimeUnit.MINUTES)
    fun `every page, on a desktop and on a phone, in light and in dark`() {
        Playwright.create().use { playwright ->
            val chromium = launchChromium(playwright)
            val app = seed()
            val shots = shots(app)
            for ((theme, scheme) in listOf("light" to ColorScheme.LIGHT, "dark" to ColorScheme.DARK)) {
                settings(claude, extra + "theme: $theme\n")
                chromium.newContext(desktop().setColorScheme(scheme)).use { shootApp(it.newPage(), shots, app.getValue("running"), theme, look) }
                chromium.newContext(phone().setColorScheme(scheme)).use { shootApp(it.newPage(), shots, app.getValue("running"), theme, lookOnPhone) }
            }
        }
    }

    private fun shootApp(page: Page, shots: List<Shot>, running: String, theme: String, folder: Path) {
        page.onConsoleMessage { if (it.type() == "error") println("LookTest: ${page.url()}: ${it.text()}") }
        page.onPageError { println("LookTest: ${page.url()}: $it") }
        shoot(page, "http://127.0.0.1:$port/login", "login/app-$theme", folder)
        logIn(page)
        for (shot in shots) shoot(page, "http://127.0.0.1:$port${shot.app}", "${shot.name}/app-$theme", folder) { shot.appScript?.let { page.evaluate(it) } }
        shoot(page, "http://127.0.0.1:$port/runs/$running", "drawer/app-$theme", folder) { openDrawer() }
        // A Script's Secrets card with a Secret added, and the modal its ✕ opens.
        shoot(page, "http://127.0.0.1:$port/scripts/post-message", "script-secrets/app-$theme", folder) { addSecret() }
        shoot(page, "http://127.0.0.1:$port/scripts/post-message", "script-secret-delete/app-$theme", folder) { locator("[data-secrets] button.x").first().click() }
    }

    private fun Page.addSecret() = locator("button.add-secret").click()

    private fun Page.openDrawer() {
        locator("#diagram .djs-element[data-element-id='draft']").click()
        waitForSelector("#drawer-head")
    }

    private val exemptionsByOffender: Map<String, String> = emptyMap()

    @Test
    @Timeout(20, unit = TimeUnit.MINUTES)
    fun `every control on the pages LookTest shoots, in the drawer and in the modals' forms has a visible label, and every text field and Markdown editor an example placeholder, on a desktop and on a phone`() {
        Playwright.create().use { playwright ->
            val app = seed()
            val chromium = launchChromium(playwright)
            val offenders = mutableListOf<String>()
            chromium.newContext(desktop()).use { context -> offenders += unlabelledOn(context.newPage(), app).map { "on a desktop, $it" } }
            chromium.newContext(phone()).use { context -> offenders += unlabelledOn(context.newPage(), app, menu = true).map { "on a phone, $it" } }
            assertEquals(emptyList(), offenders.distinct().filter { it !in exemptionsByOffender })
        }
    }

    private fun unlabelledOn(page: Page, app: Map<String, String>, menu: Boolean = false): List<String> {
        val offenders = mutableListOf<String>()
        fun visit(path: String, where: String, open: Page.() -> Unit = {}) {
            page.navigate("about:blank")
            page.navigate("http://127.0.0.1:$port$path")
            page.waitForTimeout(1500.0)
            page.open()
            page.waitForTimeout(300.0)
            offenders += unlabelled(page).map { "$where: $it" }
        }
        visit("/login", "login")
        logIn(page)
        if (menu) visit("/", "the ☰ menu") { click("label[for=menu]") }
        for (shot in shots(app)) visit(shot.app, shot.name) { shot.appScript?.let { evaluate(it) } }
        for (run in listOf("running", "stuck")) visit("/runs/${app.getValue(run)}", "the drawer on the $run Run") { openDrawer() }
        visit("/runs/${app.getValue("running")}", "the Run page's Rename") { click("button[popovertarget=rename]") }
        visit("/processes", "New Process") { click(".ph button.primary") }
        visit("/scripts/post-message", "a Script with a Secret added") { addSecret() }
        visit("/settings", "Settings with a row added to every list") { locator("[data-add]").all().forEach { it.click() } }
        visit("/settings", "a Source map's editor") { locator("[data-edit]").first().click() }
        visit(entry(app.getValue("review")) + "#s1-1", "a story step's comment") { locator(".step:not([hidden]) [data-comment]").click() }
        visit(entry(app.getValue("review")) + "?tab=repo", "a line comment") { locator("table.diff tr[data-line] td").nth(1).click() }
        for (list in listOf("/agents", "/skills")) visit(http.newDraft(list), "the modeler's modal's New on $list")
        visit(http.newDraft("/agents"), "a new codex Agent") { selectOption("select[name=cli]", "codex") }
        visit(http.newDraft("/agents"), "a new Agent on another model") { selectOption("select[name=model]", "other") }
        visit(http.newDraft("/agents"), "a new Agent's tool limited") { locator("input[name='tool.Bash']").check(); locator(".trow:has(input[name='tool.Bash']) [data-limit]").click() }
        page.navigate("http://127.0.0.1:$port/processes/build_and_ship/edit")
        page.waitForTimeout(1500.0)
        val shapes = page.evaluate("[...document.querySelectorAll('#canvas .djs-shape')].map(shape => shape.dataset.elementId)") as List<*>
        for (shape in shapes) {
            page.locator("#canvas .djs-element[data-element-id='$shape']").dispatchEvent("click")
            page.waitForTimeout(300.0)
            offenders += unlabelled(page).map { "the modeler on $shape: $it" }
        }
        return offenders
    }

    private fun unlabelled(page: Page): List<String> = page.frames().flatMap { frame ->
        (frame.evaluate("""() => {
            const textLike = /^(text|search|url|email|tel|password|number)$/
            const name = control => control.tagName.toLowerCase() + (control.type && control.tagName === "INPUT" ? "[type=" + control.type + "]" : "") +
                (control.name ? "[name=" + control.name + "]" : control.id ? "#" + control.id : "")
            document.querySelectorAll("details").forEach(details => details.open = true)
            const offenders = []
            for (const control of document.querySelectorAll("input:not([type=hidden]), select, textarea")) {
                const editor = control.matches("textarea") && control.nextElementSibling?.matches(".ed") ? control.nextElementSibling : null
                if (!(editor ?? control).checkVisibility() && ![...control.labels].some(label => label.checkVisibility())) continue
                const what = editor ? "the editor over " + name(control) : name(control)
                if (![...control.labels].some(label => label.checkVisibility() && label.textContent.trim()))
                    offenders.push(what + " has no visible label" + (control.ariaLabel ? ", only aria-label " + control.ariaLabel : ""))
                else if (control.ariaLabel) offenders.push(what + " keeps aria-label " + control.ariaLabel + " beside its visible label")
                if ((control.matches("textarea") || textLike.test(control.type)) && !control.placeholder.trim()) offenders.push(what + " has no placeholder")
                else if (editor && editor.querySelector(".cm-content")?.textContent === "" && !editor.querySelector(".cm-placeholder")?.checkVisibility())
                    offenders.push(what + " shows no placeholder inside the editor")
            }
            return offenders
        }""") as List<*>).map { it.toString() }
    }

    private fun shots(app: Map<String, String>) = listOf(
        Shot("home", "/"),
        Shot("notifications", "/notifications"),
        Shot("runs", "/runs"),
        Shot("run-review", "/runs/${app.getValue("review")}"),
        Shot("run-approve", "/runs/${app.getValue("approve")}"),
        Shot("transcript", "/runs/${app.getValue("running")}/tasks/draft/transcript"),
        Shot("stuck", entry(app.getValue("stuck"))),
        Shot("permission", entry(app.getValue("permission"))),
        Shot("approve", entry(app.getValue("approve"))),
        Shot("review", entry(app.getValue("review"))),
        // A story walked to its third step, with the diff and the details shown.
        Shot("review-step", entry(app.getValue("review")) + "#s1-3", "document.querySelector('.step:not([hidden]) .showdiff').click(); document.querySelector('.step:not([hidden]) [data-details]').click()"),
        Shot("review-by-repo", entry(app.getValue("review")) + "?tab=repo"),
        Shot("push", entry(app.getValue("push"))),
        Shot("processes", "/processes"),
        Shot("process", "/processes/build_and_ship"),
        Shot("modeler", "/processes/build_and_ship/edit"),
        Shot("agents", "/agents"),
        Shot("agent", "/agents/lead"),
        Shot("skills", "/skills"),
        Shot("skill", "/skills/walkthrough"),
        Shot("skill-references", "/skills/tdd"),
        Shot("skill-reference", "/skills/tdd", "document.querySelector('.references [data-open]').click()"),
        Shot("settings", "/settings"),
        Shot("answer", entry(app.getValue("round"))),
        Shot("notice", entry(app.getValue("notice"))),
        Shot("dashboard", "/dashboards/${app.getValue("dashboard")}"),
    )

    /** Opens [url] in a fresh document, lets its diagrams and editors load, does [then], and saves it as [name]. */
    private fun shoot(page: Page, url: String, name: String, folder: Path = look, then: (Page.() -> Unit)? = null) {
        page.navigate("about:blank")
        page.navigate(url)
        page.waitForTimeout(2500.0) // the pages' streams never let the network idle; bpmn-js and CodeMirror load and lay out
        then?.let {
            // A step that fails still leaves its screenshot, to see why.
            runCatching { page.it() }.onFailure { println("LookTest: $name: ${it.message}") }
            page.waitForTimeout(500.0) // the drawer slides in
        }
        page.screenshot(Page.ScreenshotOptions().setPath(folder.resolve("$name.png")).setFullPage(true))
    }

    private fun logIn(page: Page) {
        page.navigate("http://127.0.0.1:$port/login")
        page.fill("input[name=username]", "admin")
        page.fill("input[name=password]", "admin")
        page.click("button[type=submit]")
        page.waitForURL { !it.contains("/login") }
    }

    /**
     * settings.yaml's lines beyond the Vault and claude: the Source map of the Review changes Run, no So far, and an extra
     * Agent and Skill folder.
     */
    private val extra by lazy { reviewSourceMap() + "so-far: false\nagent-folders: [~/shared/agents]\nskill-folders: [~/shared/skills]\n" }

    /**
     * Seeds the pages, one Run at a time, each waiting until it has reached the state it is for, since the
     * fake claude plays a Skill's sessions in launch order. The Run ids, by what each is for.
     */
    private fun seed(): Map<String, String> {
        processes.deployShipped()
        home.resolve("claude-usage.json").writeText("""{"five_hour":{"utilization":0.82},"seven_day":{"utilization":0.41}}""") // Settings' meters
        agentTask(claude, mapOf(
            "draft" to """{"type":"object","properties":{"notes":{"type":"string","format":"path"}}}""",
            "polish" to null, "mend" to null, "ship" to null, "narrate" to null,
            "quiz" to """{"type":"object","properties":{"questions":{"type":"array"}}}""",
        ), extra = extra)
        val runs = mutableMapOf<String, String>()
        fun start(process: String, form: List<Pair<String, String>> = listOf()) = http.post("/processes/$process/runs", form).path.removePrefix("/runs/")

        // An Approve showing a file: Draft → Check the draft.
        claude.session("draft", 1, result("""{"outcome":"done","summary":"Drafted the release notes.","output":{"notes":"notes.md"}}"""))
        http.import("approve.bpmn")
        runs["approve"] = start("approve").also { workspace(it).resolve("notes.md").writeText("# Release notes\n\nRetries a failed card payment **three times**, with backoff.\n") }
        entry(runs.getValue("approve"))

        // A stuck Agent Task.
        claude.session("draft", 2, result("""{"outcome":"stuck","summary":"Can't tell which webhook provider the prompt means.","needs":"The prompt says *the webhook*, but payments has two: Stripe and Vipps. Which one, or both?"}"""))
        http.import("agent-task.bpmn")
        runs["stuck"] = start("agent-task", listOf("prompt" to "Make the payments webhook retry."))
        entry(runs.getValue("stuck"))

        // A running Agent Task, which waits for a file that never comes.
        claude.session(
            "draft", 3,
            """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}""",
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"The theme lives in one place, but three components read colours directly. Let me check how each one does it."}],"usage":{"input_tokens":1200,"output_tokens":300}}}""",
            """{"type":"assistant","message":{"id":"m2","content":[{"type":"tool_use","id":"t1","name":"Grep","input":{"pattern":"color:","path":"repos/web/src/components"}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"Header.tsx\nSidebar.tsx"}]}}""",
            """{"type":"assistant","message":{"id":"m3","content":[{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"repos/web/src/components/Header.tsx"}}]}}""",
            "wait never",
        )
        runs["running"] = start("agent-task", listOf("prompt" to "Add a dark mode toggle."))
        eventually { assertEquals(3, claude.launches("draft").size) }

        claude.session(
            "draft", 4,
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"cargo outdated --root-deps-only"}}]}}""",
            """{"type":"system","subtype":"permission_denied","tool_name":"Bash","tool_use_id":"t1","decision_reason_type":"mode","session_id":"${'$'}SESSION"}""",
            "read",
            """{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"${'$'}SESSION"}""",
            "exit 1",
        )
        runs["permission"] = start("agent-task", listOf("prompt" to "List the outdated crates."))
        entry(runs.getValue("permission"))

        // Review changes over two cloned repos, told as a Story set, with a step gone stale and a change no step covers.
        claude.session("narrate", "wait commits", "write walkthrough/stories.json ${BillingRetry.stories}", result("""{"outcome":"done","summary":"Told the change as three stories.","output":{}}"""))
        http.import("story-set.bpmn")
        runs["review"] = start("story-set", listOf("repos" to "api", "repos" to "web"))
        eventually { assertEquals(1, claude.launches("narrate").size) }
        BillingRetry.commit(workspace(runs.getValue("review")).resolve("repos"))
        claude.release("commits")
        entry(runs.getValue("review"))

        // A Push of two repos, each with a commit.
        http.import("push.bpmn")
        runs["push"] = start("ship", listOf("repos" to "api", "repos" to "web", "branch" to "ants/billing-retry"))
        val work = entry(runs.getValue("push"))
        for (repo in listOf("api", "web")) commitFile(workspace(runs.getValue("push")).resolve("repos/$repo"), "retry.txt", "three times\n", "Retry a failed card payment")
        http.post(work, mapOf("action" to "approve", "comment" to ""))
        eventually(30) { check(entry(runs.getValue("push")) != work) }

        // A Script with a Secret of its own, for the Secrets card.
        http.publishNew("/scripts", mapOf("name" to "post-message", "description" to "Posts a message to a Slack channel.", "code" to "console.log('posting')\n",
            "inputs" to "channel, text", "vars" to "CHANNEL=general", "secrets[0].name" to "SLACK_TOKEN", "secrets[0].value" to "xoxb-1234", "secrets[0].was" to ""))

        // The Agents list with both Draft markers: a Draft-only Agent, and a published one with a Draft.
        http.get("/agents")
        http.post("/agents/new/draft", emptyMap())
        home.resolve("agents/reviewer.md").writeText("---\nid: a-review1\nname: reviewer\ndescription: Reviews a change.\ncli: claude\n---\nReview it.\n")
        http.autosave("/agents/reviewer", mapOf("name" to "reviewer", "cli" to "claude", "description" to "Reviews a change, strictly.", "instructions" to "Review it."), "0")
        // The shipped Agent's page with Your Skills beside the Skills it ships with.
        http.get("/agents/lead")
        http.post("/agents/lead/your-skills", mapOf("skills" to "polish", "tool.Bash" to "on", "limit.Bash" to "cargo *"))

        // A Round of questions.
        claude.session("quiz", 1, result("""{"outcome":"done","summary":"Round 1 on the invoice CSV export.","output":{"questions":[""" + listOf(
            """{"id":"who","title":"Who exports","ask":"Who gets the export button: everyone who can see invoices, or only admins?","context":"Settles who sees the button.","recommendation":"Everyone who can see the invoice list.","why":"The export holds exactly what they can already see."}""",
            """{"id":"which","title":"Which invoices","ask":"Does the export take the invoices filtered on screen, or all of them?","context":"Settles the export's scope.","recommendation":"The current filter.","why":"What you see is what you get."}""",
        ).joinToString(",") + "]}}"))
        http.import("round.bpmn")
        runs["round"] = start("round")
        entry(runs.getValue("round"))

        // A Notice from a watcher's Run, which has ended, for the Notification list and the Notice's page.
        http.import("notify.bpmn")
        runs["notice"] = start("watch", listOf("pipeline" to "api-build", NAME to "Watch the api pipelines"))
        entry(runs.getValue("notice"))

        // An ended Run, a cancelled one, and a Schedule for Coming up.
        http.import("start-end.bpmn")
        start("start-end")
        http.post("/processes/deploy", mapOf("bpmn" to """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="cancelled" name="Flaky tests" isExecutable="true" camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start" /><bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait" />
                <bpmn:userTask id="wait" name="Wait" /><bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="end" /><bpmn:endEvent id="end" />
              </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()))
        http.post("/runs/${start("cancelled")}/cancel", mapOf())
        http.post("/processes/deploy", mapOf("bpmn" to """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="nightly" name="Nightly knowledge sweep" isExecutable="true" camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start" />
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="end" /><bpmn:endEvent id="end" />
              </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()))
        http.get("/processes/nightly")
        http.post("/processes/nightly/schedule", mapOf("cron" to "0 0 0 * * *"))
        runs["dashboard"] = dashboard()

        // Two devices with Alerts on, for Settings' list.
        http.get("/settings")
        for ((device, name) in phones.zip(listOf("Chrome on Android", "Firefox on Linux"))) http.postBytes("/alerts/devices", device.subscription(name).toByteArray())
        return runs
    }

    /**
     * A Dashboard with every Widget type and chart kind, each Posted to, with a long title, long words and long lines
     * that must wrap or be cut short to fit a phone; its id.
     */
    private fun dashboard(): String {
        val id = dashboards.rename(dashboards.create().id, "The home lab, its disks, its backups and everything the nightly sweep finds out").id
        val simple = listOf(WidgetType.NUMBER, WidgetType.PROGRESS, WidgetType.STATUS, WidgetType.LIST, WidgetType.TEXT, WidgetType.LOG)
        val widgets = simple.map { dashboards.addWidget(id, it) } +
            ChartKind.entries.map { dashboards.addWidget(id, WidgetType.CHART, it) } + dashboards.addWidget(id, WidgetType.CUSTOM)
        // The charts two to a row, then the text and the log side by side, then four small Widgets to a row from right
        // to left: an order unlike the one they were added in.
        dashboards.layout(id, widgets.mapIndexed { i, widget ->
            when {
                i < 4 -> Placement(widget.id, (3 - i) * 3, 19, 3, 2)
                i < 6 -> Placement(widget.id, (i - 4) * 6, 16, 6, 3)
                else -> Placement(widget.id, (i - 6) % 2 * 6, (i - 6) / 2 * 4, 6, 4)
            }
        })
        dashboards.renameWidget(id, widgets[0].id, "Requests served by the reverse proxy since the last restart of the whole home lab")
        fun post(widget: Widget, data: Any) = dashboards.post(WidgetAddress.of("$id/${widget.id}"), data, "r-look", "measure")
        post(widgets[0], mapOf("value" to 1234567890123, "unit" to "requests"))
        post(widgets[1], mapOf("done" to 7, "total" to 9))
        post(widgets[2], mapOf("items" to listOf(
            mapOf("name" to "nas", "state" to "ok"), mapOf("name" to "backup-of-the-photo-library-to-the-offsite-disk", "state" to "running"),
            mapOf("name" to "router", "state" to "fail"), mapOf("name" to "printer", "state" to "warn"),
        )))
        post(widgets[3], mapOf("items" to listOf(
            mapOf("title" to "https://example.com/a/very/long/address/that/has/no/spaces/anywhere/in/it/at/all", "url" to "https://example.com/news", "note" to "today"),
            mapOf("title" to "Ants 1.4 is out"),
        )))
        post(widgets[4], mapOf("text" to "The sweep found three new notes.\nSupercalifragilisticexpialidociousandthensomemorewithoutanybreaks was the longest word."))
        listOf(
            "info" to "Started the nightly sweep",
            "warn" to "The offsite disk answered slowly: /mnt/offsite/backups/photos/2026/11/05/full-backup-of-everything.tar.zst",
            "error" to "Gave up on the printer",
        ).forEach { (level, line) -> post(widgets[5], mapOf("line" to line, "level" to level)) }
        val points = listOf("2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02").flatMapIndexed { i, day ->
            listOf(mapOf("x" to day, "y" to i + 1, "series" to "opened issues"), mapOf("x" to day, "y" to 4 - i, "series" to "closed issues"))
        }
        widgets.filter { it.type == WidgetType.CHART }.forEach { post(it, mapOf("points" to points)) }
        post(widgets.last(), mapOf("spec" to mapOf(
            "title" to "Commits by hour",
            "data" to mapOf("values" to (8..18).map { mapOf("hour" to it, "commits" to it % 5) }),
            "encoding" to mapOf("x" to mapOf("field" to "hour", "type" to "ordinal"), "y" to mapOf("field" to "commits", "type" to "quantitative")),
            "layer" to listOf(mapOf("mark" to "bar"), mapOf("mark" to mapOf("type" to "text", "dy" to -6), "encoding" to mapOf("text" to mapOf("field" to "commits")))),
        )))
        return id
    }

    @Test
    @Timeout(5, unit = TimeUnit.MINUTES)
    fun `a Dashboard is one column on a phone in its layout's order without editing, nothing on it is wider than the screen, and Dark mode shows no light box or dark text`() {
        Playwright.create().use { playwright ->
            val chromium = launchChromium(playwright)
            val id = dashboard()
            val layout = dashboards.get(id).widgets
            val offenders = mutableListOf<String>()
            for ((theme, scheme) in listOf("light" to ColorScheme.LIGHT, "dark" to ColorScheme.DARK)) {
                settings(claude, "theme: $theme\n")
                for ((device, options) in listOf("desktop" to desktop(), "phone" to phone())) chromium.newContext(options.setColorScheme(scheme)).use { context ->
                    val page = context.newPage()
                    logIn(page)
                    page.navigate("http://127.0.0.1:$port/dashboards/$id")
                    page.waitForFunction("[...document.querySelectorAll('.chart')].every(chart => chart.querySelector('svg .mark-text, svg .mark-arc, svg .mark-rect, svg .mark-line, svg .mark-area, svg .mark-symbol'))")
                    page.waitForTimeout(500.0) // the charts settle at their Widgets' sizes
                    val where = "on a $device in $theme"
                    if (device == "phone") {
                        offenders += (page.evaluate(tooWide) as List<*>).map { "$where, $it" }
                        val boxes = (page.locator(".grid-stack-item").evaluateAll(
                            "items => items.map(item => [item.getAttribute('gs-id'), Math.round(item.getBoundingClientRect().left), Math.round(item.getBoundingClientRect().width), item.getBoundingClientRect().top])",
                        ) as List<*>).map { it as List<*> }
                        assertEquals(1, boxes.map { it.subList(1, 3) }.distinct().size, "$where, every Widget is in one column: $boxes")
                        assertEquals(layout.sortedWith(compareBy({ it.y }, { it.x })).map { it.id }, boxes.sortedBy { (it[3] as Number).toDouble() }.map { it[0] }, "$where, in the layout's order")
                        assertTrue(page.locator("#edit").isHidden && page.locator("#add").isHidden, "$where, Edit layout and + Widget are hidden")
                    }
                    if (theme == "dark") offenders += (page.evaluate(lightInDark) as List<*>).map { "$where, $it" }
                }
            }
            assertEquals(emptyList(), offenders)
            assertEquals(layout, dashboards.get(id).widgets, "a phone's one column never replaces the layout")
        }
    }

    /** The visible elements in the page's main part reaching past the screen's edges, and the page if it scrolls sideways. */
    private val tooWide = """() => {
        const width = document.documentElement.clientWidth
        const name = e => e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (e.classList.length ? '.' + [...e.classList].join('.') : '')
        const wide = [...document.querySelectorAll('main *')].filter(e => e.checkVisibility({ visibilityProperty: true }))
            .filter(e => { const box = e.getBoundingClientRect(); return box.width > 0 && (box.left < -0.5 || box.right > width + 0.5) })
            .map(e => name(e) + ' reaches ' + Math.round(e.getBoundingClientRect().left) + ' to ' + Math.round(e.getBoundingClientRect().right) + ' of ' + width)
        return (document.documentElement.scrollWidth > width ? ['the page scrolls sideways to ' + document.documentElement.scrollWidth] : []).concat(wide).slice(0, 15)
    }"""

    /**
     * On a Dashboard, the elements with a white or light grey background, a chart's background drawn so, and a chart's
     * text drawn dark; the first fifteen. A colour such as a status dot's green may be light: it is a colour, not a box.
     */
    private val lightInDark = """() => {
        const probe = document.createElement('span')
        document.body.append(probe)
        // A colour's red, green and blue from 0 to 1; nothing for none or one nearly transparent.
        const rgb = colour => {
            if (!colour || colour === 'none') return null
            probe.style.color = 'rgba(0, 0, 0, 0)'
            probe.style.color = colour
            const [r, g, b, a = 1] = getComputedStyle(probe).color.match(/[\d.]+/g).map(Number)
            return a > 0.1 ? [r / 255, g / 255, b / 255] : null
        }
        const lightness = ([r, g, b]) => 0.2126 * r + 0.7152 * g + 0.0722 * b
        const whitish = colour => { const c = rgb(colour); return !!c && lightness(c) > 0.6 && Math.max(...c) - Math.min(...c) < 0.15 }
        const dark = colour => { const c = rgb(colour); return !!c && lightness(c) < 0.35 }
        const name = e => e.tagName.toLowerCase() + (e.classList.length ? '.' + [...e.classList].join('.') : '') + (e.textContent.trim() ? ' “' + e.textContent.trim().slice(0, 30) + '”' : '')
        const shown = [...document.querySelectorAll('main.dashboard *')].filter(e => e.checkVisibility({ visibilityProperty: true }))
        const offenders = [
            ...shown.filter(e => !(e instanceof SVGElement) && whitish(getComputedStyle(e).backgroundColor)).map(e => name(e) + ' has a light background'),
            ...shown.filter(e => e.matches('svg .background') && whitish(getComputedStyle(e).fill)).map(e => name(e) + ' is a light box'),
            ...shown.filter(e => e.matches('svg text') && dark(getComputedStyle(e).fill)).map(e => name(e) + ' is dark text'),
        ]
        probe.remove()
        return offenders.slice(0, 15)
    }"""

    /** The path of [run]'s entry, once it is on the Notification list. */
    private fun entry(run: String): String {
        var path = ""
        eventually(30) {
            val row = Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL).find(http.get("/notifications").body)
            path = Regex("""href="(/notifications/[^"]+)"""").find(assertNotNull(row, "$run isn't on the Notification list").groupValues[1])!!.groupValues[1]
        }
        return path
    }
}

/**
 * A Review changes, `billing-retry-impl`, in two clones, api and web: three commits in api (the handler
 * as it was, the retry, and a later change to a line a step is on, which makes that step stale) and one in web; and
 * the Story set telling it, with a test no step covers.
 */
internal object BillingRetry {
    private val handler = listOf(
        "package billing", "", "class PaymentFailedHandler(private val invoices: InvoiceRepository) {", "    fun handle(event: PaymentFailed) {",
        "        val invoice = invoices.get(event.invoiceId)", "        log.info(\"Payment failed for \${invoice.id}, attempt \${event.attempt}\")",
        "        invoice.markUnpaid()", "        invoices.save(invoice)", "    }", "}",
    )
    private val retrying = listOf(
        "package billing", "", "class PaymentFailedHandler(", "    private val invoices: InvoiceRepository,", "    private val policy: PaymentRetryPolicy,",
        "    private val retries: RetryScheduler,", ") {", "    fun handle(event: PaymentFailed) {", "        val invoice = invoices.get(event.invoiceId)",
        "        log.info(\"Payment failed for \${invoice.id}, attempt \${event.attempt}\")", "        policy.next(event.attempt)?.let { retries.schedule(invoice, it) }",
        "            ?: invoice.markUnpaid()", "        invoices.save(invoice)", "    }", "}",
    )
    private val policy = listOf(
        "package billing", "", "import kotlin.time.Duration", "import kotlin.time.Duration.Companion.hours", "",
        "/** How long to wait before each retry of a failed card payment. */", "data class PaymentRetryPolicy(",
        "    val delays: List<Duration> = listOf(1.hours, 6.hours, 24.hours),", ") {", "    /** The delay before retry [attempt], or null when none are left. */",
        "    fun next(attempt: Int): Duration? = delays.getOrNull(attempt - 1)", "}",
    )
    private val scheduler = listOf(
        "package billing", "", "class RetryScheduler(private val jobs: JobQueue) {", "    fun schedule(invoice: Invoice, after: Duration) {",
        "        invoice.nextRetryAt = Instant.now() + after", "        jobs.enqueue(RetryPayment(invoice.id), after)", "    }", "}",
    )
    private val test = listOf(
        "class PaymentRetryPolicyTest {", "    private val policy = PaymentRetryPolicy()", "",
        "    @Test fun `first retry waits an hour`() = assertEquals(1.hours, policy.next(1))", "    @Test fun `no fourth retry`() = assertNull(policy.next(4))", "}",
    )
    private val banner = listOf(
        "import { Banner } from \"../ui/Banner\"", "import { relative } from \"../time\"", "import type { Invoice } from \"./types\"", "",
        "export function InvoiceBanner({ invoice }: { invoice: Invoice }) {", "  if (invoice.status === \"UNPAID\") return <Banner tone=\"danger\">This invoice is unpaid.</Banner>",
        "  if (invoice.nextRetryAt)", "    return <Banner tone=\"info\">We'll try your card again {relative(invoice.nextRetryAt)}.</Banner>", "  return null", "}",
    )

    /** Commits the change in the clones under [repos], one commit at a time. */
    fun commit(repos: Path) {
        fun write(repo: String, file: String, lines: List<String>) = repos.resolve("$repo/$file").writeText(lines.joinToString("\n", postfix = "\n"))
        commitFile(repos.resolve("api"), "PaymentFailedHandler.kt", handler.joinToString("\n", postfix = "\n"), "Mark a failed payment unpaid")
        write("api", "PaymentRetryPolicy.kt", policy)
        write("api", "RetryScheduler.kt", scheduler)
        write("api", "PaymentRetryPolicyTest.kt", test)
        commitFile(repos.resolve("api"), "PaymentFailedHandler.kt", retrying.joinToString("\n", postfix = "\n"), "Retry a failed card payment three times")
        commitFile(repos.resolve("api"), "PaymentFailedHandler.kt", retrying.joinToString("\n", postfix = "\n").replace("invoices.save(invoice)", "invoices.save(invoice, flush = true)"), "Flush the save")
        commitFile(repos.resolve("web"), "InvoiceBanner.tsx", banner.joinToString("\n", postfix = "\n"), "Tell the customer when the card is tried again")
    }

    private fun step(name: String, file: String, kind: String, from: Int, to: Int, claim: String, why: String, vararg more: Pair<String, Any>) =
        mapOf("id" to name.lowercase().replace(' ', '-'), "name" to name, "claim" to claim, "why" to why, "site" to mapOf("file" to file, "side" to "new", "kind" to kind, "from" to from, "to" to to)) + more

    private const val HANDLER = "repos/api/PaymentFailedHandler.kt"
    private const val POLICY = "repos/api/PaymentRetryPolicy.kt"

    /** The Story set, as JSON on one line. */
    val stories: String = JsonMapper.shared().writeValueAsString(mapOf(
        "protocolVersion" to 1, "title" to "A failed card payment is retried three times before the invoice goes unpaid",
        "ranges" to listOf(mapOf("repo" to "api", "base" to "HEAD~2", "head" to "HEAD~1"), mapOf("repo" to "web", "base" to "origin/main", "head" to "HEAD")),
        "stories" to listOf(
            mapOf("id" to "retry", "name" to "Retrying a failed payment", "premise" to "A failed payment no longer marks the invoice unpaid at once: it asks a policy for a delay and schedules a retry.", "steps" to listOf(
                step("Failure arrives", HANDLER, "context", 8, 10, "A failed payment arrives in `PaymentFailedHandler.handle`, carrying which attempt it was.",
                    "This is where control enters: the payment provider's webhook ends up here.",
                    "flow" to mapOf("in" to "PaymentWebhookController posts PaymentFailed", "out" to "policy.next(attempt)"),
                    "values" to listOf(mapOf("name" to "attempt", "value" to "event.attempt", "provenance" to "literal", "cite" to mapOf("file" to HANDLER, "line" to 10)))),
                step("Ask the policy", HANDLER, "changed", 11, 11, "Instead of giving up, the handler asks the retry policy how long to wait.",
                    "Keeps the numbers out of the handler, so changing the schedule never touches the flow."),
                step("Three delays", POLICY, "changed", 7, 11, "The policy holds three delays, 1 h, 6 h and 24 h, and has none for a fourth attempt.",
                    "The schedule is data, so it can be tuned without a deploy of the handler.",
                    "values" to listOf(
                        mapOf("name" to "delays", "value" to "1.hours, 6.hours, 24.hours", "provenance" to "literal", "cite" to mapOf("file" to POLICY, "line" to 8)),
                        mapOf("name" to "next(4)", "value" to "null", "provenance" to "invented"),
                    )),
                step("Schedule it", "repos/api/RetryScheduler.kt", "changed", 4, 7, "The retry becomes a delayed job on the existing JobQueue, so it survives a restart.",
                    "A job outlives the request that failed.", "nudge" to "Look at what happens if the job runs after the invoice was paid some other way."),
            )),
            mapOf("id" to "customer", "name" to "What the customer sees", "premise" to "While a retry is planned, the invoice page says when.", "steps" to listOf(
                step("The banner", "repos/web/InvoiceBanner.tsx", "changed", 7, 8, "The invoice page shows an info banner while `nextRetryAt` is set.", "The customer knows the card will be tried again."),
            )),
            mapOf("id" to "giving-up", "name" to "Giving up", "premise" to "After the last retry the invoice ends up unpaid, as before.", "steps" to listOf(
                step("Last attempt", HANDLER, "changed", 12, 12, "With no delay left, the handler marks the invoice unpaid: the old behaviour.", "Nothing changes for the last attempt."),
                step("Saved either way", HANDLER, "context", 13, 13, "Retried or unpaid, the invoice is saved.", "One save, whichever way it went."),
            )),
        ),
    ))
}
