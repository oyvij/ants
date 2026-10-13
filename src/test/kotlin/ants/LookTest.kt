package ants

import com.microsoft.playwright.Browser.NewContextOptions
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.ColorScheme
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The look test: every page beside the prototype it must equal, in light and in dark, as
 * build/reports/browser/look/<page>/{prototype,app}-{light,dark}.png, for a person or an agent to compare. It asserts
 * nothing about the look itself. The prototype is `prototype/web-ui`'s one HTML file, opened at a hash route, and
 * `prototype/answer-screen`'s Answer page; CI fetches both branches (test.yml), and they load bpmn-js, CodeMirror and
 * marked from unpkg. The app side is seeded with what the prototype shows: Runs in different states, waiting entries, a
 * Schedule, and the shipped Processes, Agents and Skills. Like [BrowserTest], it is skipped where Chromium can't launch.
 */
@Tag("browser")
class LookTest : AntsTest() {
    @Autowired
    private lateinit var processes: Processes

    private val http by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()
    private val look: Path = Path.of("build/reports/browser/look")

    /** A page and its prototype: a hash route in web-ui.html, or another file of the prototype's. */
    private class Shot(val name: String, val prototype: String, val app: String)

    private fun result(json: String) = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":$json}"""

    @Test
    @Timeout(15, unit = TimeUnit.MINUTES) // about 80 screenshots, each given time to load
    fun `every page beside the prototype, in light and in dark`() {
        Playwright.create().use { playwright ->
            val chromium = launchChromium(playwright)
            val prototype = prototype("prototype/web-ui", "web-ui.html")
            val answerPage = prototype("prototype/answer-screen", "answer-page.html")
            val app = seed()
            val pairs = listOf(
                Shot("home", "$prototype#/", "/"),
                Shot("notifications", "$prototype#/notifications", "/notifications"),
                Shot("runs", "$prototype#/runs", "/runs"),
                Shot("run-review", "$prototype#/runs/billing-retry-impl", "/runs/${app.getValue("review")}"),
                Shot("run-approve", "$prototype#/runs/search-reindex", "/runs/${app.getValue("approve")}"),
                Shot("transcript", "$prototype#/runs/dark-mode-toggle/tasks/grill", "/runs/${app.getValue("running")}/tasks/draft/transcript"),
                Shot("stuck", "$prototype#/runs/payments-webhook/tasks/grill", entry(app.getValue("stuck"))),
                Shot("approve", "$prototype#/runs/search-reindex/tasks/approve", entry(app.getValue("approve"))),
                Shot("review", "$prototype#/runs/billing-retry-impl/tasks/review", entry(app.getValue("review"))),
                Shot("processes", "$prototype#/processes", "/processes"),
                Shot("process", "$prototype#/processes/spec-you", "/processes/ship_with_me"),
                Shot("modeler", "$prototype#/processes/spec-you/edit", "/processes/ship_with_me/edit"),
                Shot("agents", "$prototype#/agents", "/agents"),
                Shot("agent", "$prototype#/agents/griller", "/agents/griller"), // the prototype has no planner
                Shot("skills", "$prototype#/skills", "/skills"),
                Shot("skill", "$prototype#/skills/scout", "/skills/scout"),
                Shot("settings", "$prototype#/settings", "/settings"),
                Shot("answer", answerPage, entry(app.getValue("round"))),
            )
            for ((theme, scheme) in listOf("light" to ColorScheme.LIGHT, "dark" to ColorScheme.DARK)) {
                settings(claude, extra + "theme: $theme\n")
                val options = NewContextOptions().setColorScheme(scheme).setViewportSize(1440, 900)
                chromium.newContext(options).use { context ->
                    val page = context.newPage()
                    page.addInitScript("addEventListener('DOMContentLoaded', () => document.querySelector('details.proto')?.remove())")
                    for (pair in pairs) shoot(page, pair.prototype, "${pair.name}/prototype-$theme")
                    shoot(page, "$prototype#/login", "login/prototype-$theme")
                    shoot(page, "$prototype#/runs/dark-mode-toggle", "drawer/prototype-$theme") { evaluate("openDrawer('dark-mode-toggle', 'grill')") }
                }
                chromium.newContext(options).use { context ->
                    val page = context.newPage()
                    shoot(page, "http://127.0.0.1:$port/login", "login/app-$theme")
                    logIn(page)
                    for (pair in pairs) shoot(page, "http://127.0.0.1:$port${pair.app}", "${pair.name}/app-$theme")
                    // The Run page's drawer, open on its running Agent Task.
                    shoot(page, "http://127.0.0.1:$port/runs/${app.getValue("running")}", "drawer/app-$theme") {
                        locator("#diagram .djs-element[data-element-id='draft']").click()
                        waitForSelector("#drawer-head")
                    }
                }
            }
        }
    }

    /** Opens [url] in a fresh document, lets its diagrams and editors load, does [then], and saves it as [name]. */
    private fun shoot(page: Page, url: String, name: String, then: (Page.() -> Unit)? = null) {
        page.navigate("about:blank")
        page.navigate(url)
        page.waitForTimeout(2500.0) // the pages' streams never let the network idle; bpmn-js and CodeMirror load and lay out
        then?.let {
            page.it()
            page.waitForTimeout(500.0) // the drawer slides in
        }
        page.screenshot(Page.ScreenshotOptions().setPath(look.resolve("$name.png")).setFullPage(true))
    }

    private fun logIn(page: Page) {
        page.navigate("http://127.0.0.1:$port/login")
        page.fill("input[name=username]", "admin")
        page.fill("input[name=password]", "admin")
        page.click("button[type=submit]")
        page.waitForURL { !it.contains("/login") }
    }

    /** The prototype's [file] on [branch], written out and given as a file: URL. */
    private fun prototype(branch: String, file: String): String {
        val git = ProcessBuilder("git", "show", "origin/$branch:prototype/$file").redirectErrorStream(true).start()
        val html = git.inputStream.readAllBytes().decodeToString()
        assertEquals(0, git.waitFor(), "git show origin/$branch:prototype/$file (CI fetches the prototype branches): $html")
        return Path.of("build/look").createDirectories().resolve(file).also { it.writeText(html) }.toUri().toString()
    }

    /** settings.yaml's lines beyond the Vault and claude: the Source map of the Review changes Run, and no So far. */
    private val extra by lazy { reviewSourceMap() + "so-far: false\n" }

    /**
     * Seeds what the prototype shows, one Run at a time, each waiting until it has reached the state it is for, since the
     * fake claude plays a Skill's sessions in launch order. The Run ids, by what each is for.
     */
    private fun seed(): Map<String, String> {
        processes.deployShipped()
        agentTask(claude, mapOf(
            "draft" to """{"type":"object","properties":{"notes":{"type":"string","format":"path"}}}""",
            "polish" to null, "mend" to null, "ship" to null,
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

        // Review changes over two cloned repos, one with a commit.
        http.import("review.bpmn")
        runs["review"] = start("review", listOf("repos" to "api", "repos" to "web"))
        entry(runs.getValue("review"))
        commitFile(workspace(runs.getValue("review")).resolve("repos/api"), "hello.txt", "hello\nthere\nworld\n", "Greet them")

        // A Round of questions.
        claude.session("quiz", 1, result("""{"outcome":"done","summary":"Round 1 on the invoice CSV export.","output":{"questions":[""" + listOf(
            """{"id":"who","title":"Who exports","ask":"Who gets the export button: everyone who can see invoices, or only admins?","context":"Settles who sees the button.","recommendation":"Everyone who can see the invoice list.","why":"The export holds exactly what they can already see."}""",
            """{"id":"which","title":"Which invoices","ask":"Does the export take the invoices filtered on screen, or all of them?","context":"Settles the export's scope.","recommendation":"The current filter.","why":"What you see is what you get."}""",
        ).joinToString(",") + "]}}"))
        http.import("round.bpmn")
        runs["round"] = start("round")
        entry(runs.getValue("round"))

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
                <bpmn:startEvent id="start"><bpmn:timerEventDefinition><bpmn:timeCycle>0 0 0 * * *</bpmn:timeCycle></bpmn:timerEventDefinition></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="end" /><bpmn:endEvent id="end" />
              </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()))
        return runs
    }

    /** The path of [run]'s entry, once it is on the Notification list. */
    private fun entry(run: String): String {
        var path = ""
        eventually(30) {
            val row = Regex("""<tr>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL).find(http.get("/notifications").body)
            path = Regex("""href="(/notifications/[^"]+)"""").find(assertNotNull(row, "$run isn't on the Notification list").groupValues[1])!!.groupValues[1]
        }
        return path
    }
}
