package ants

import com.microsoft.playwright.Browser.NewContextOptions
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.ColorScheme
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
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
 * `prototype/answer-screen`'s Answer page; CI fetches both branches (ci.yml), and they load bpmn-js, CodeMirror and
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

    /**
     * A page and its prototype: a hash route in web-ui.html, or another file of the prototype's, each shot after
     * [script] runs in the prototype and the app is given [keys], space-separated, for a state no URL reaches.
     */
    private class Shot(val name: String, val prototype: String, val app: String, val script: String? = null, val keys: String? = null)

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
                // A story walked to its third step, with the diff and the details shown.
                Shot("review-step", "$prototype#/runs/billing-retry-impl/tasks/review", entry(app.getValue("review")) + "#s1-3", "walk(0, 2); W.diff = true; W.details = true; renderWalk()", "d D"),
                Shot("review-by-repo", "$prototype#/runs/billing-retry-impl/tasks/review", entry(app.getValue("review")) + "?tab=repo", "reviewTab('repo')"),
                // The prototype confirms a Push in a modal over Review changes; Ants waits on Push's own page.
                Shot("push", "$prototype#/runs/billing-retry-impl/tasks/review", entry(app.getValue("push")), "confirmPush('billing-retry-impl', 'review')"),
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
                    for (pair in pairs) shoot(page, pair.prototype, "${pair.name}/prototype-$theme") { pair.script?.let { page.evaluate(it) } }
                    shoot(page, "$prototype#/login", "login/prototype-$theme")
                    shoot(page, "$prototype#/runs/dark-mode-toggle", "drawer/prototype-$theme") { evaluate("openDrawer('dark-mode-toggle', 'grill')") }
                }
                chromium.newContext(options).use { context ->
                    val page = context.newPage()
                    page.onConsoleMessage { if (it.type() == "error") println("LookTest: ${page.url()}: ${it.text()}") }
                    page.onPageError { println("LookTest: ${page.url()}: $it") }
                    shoot(page, "http://127.0.0.1:$port/login", "login/app-$theme")
                    logIn(page)
                    for (pair in pairs) shoot(page, "http://127.0.0.1:$port${pair.app}", "${pair.name}/app-$theme") { pair.keys?.split(" ")?.forEach { page.keyboard().press(it) } }
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
            // A step that fails still leaves its screenshot, to see why.
            runCatching { page.it() }.onFailure { println("LookTest: $name: ${it.message}") }
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

    /**
     * settings.yaml's lines beyond the Vault and claude: the Source map of the Review changes Run, no So far, and an extra
     * Agent and Skill folder, as Settings in the prototype lists Varde's.
     */
    private val extra by lazy { reviewSourceMap() + "so-far: false\nagent-folders: [~/.varde/ai/agents]\nskill-folders: [~/.varde/ai/skills]\n" }

    /**
     * Seeds what the prototype shows, one Run at a time, each waiting until it has reached the state it is for, since the
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

        // Review changes over two cloned repos, told as the prototype's Story set, with a step gone stale and a change no step covers.
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
            val row = Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL).find(http.get("/notifications").body)
            path = Regex("""href="(/notifications/[^"]+)"""").find(assertNotNull(row, "$run isn't on the Notification list").groupValues[1])!!.groupValues[1]
        }
        return path
    }
}

/**
 * The prototype's Review changes, `billing-retry-impl`, in two clones, api and web: three commits in api (the handler
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
