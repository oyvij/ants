package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.TaskService
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ScheduleTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    @Autowired
    lateinit var history: HistoryService

    @Autowired
    lateinit var management: ManagementService

    @Autowired
    lateinit var tasks: TaskService

    @Autowired
    lateinit var repository: RepositoryService

    private val key = "nightly"

    private fun bpmn(cron: String? = null, stored: String? = "Sweep", waits: Boolean = false) = """
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                          id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="$key" name="Nightly" isExecutable="true" camunda:historyTimeToLive="180">
            <bpmn:startEvent id="start">
              <bpmn:extensionElements>
                <camunda:formData>
                  <camunda:formField id="prompt" label="Prompt" type="string">
                    ${stored?.let { """<camunda:properties><camunda:property id="ants.stored" value="$it" /></camunda:properties>""" }.orEmpty()}
                    <camunda:validation><camunda:constraint name="required" /></camunda:validation>
                  </camunda:formField>
                </camunda:formData>
              </bpmn:extensionElements>
              ${cron?.let { "<bpmn:timerEventDefinition><bpmn:timeCycle>$it</bpmn:timeCycle></bpmn:timerEventDefinition>" }.orEmpty()}
            </bpmn:startEvent>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="${if (waits) "wait" else "end"}" />
            ${if (waits) """<bpmn:userTask id="wait" /><bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="end" />""" else ""}
            <bpmn:endEvent id="end" />
          </bpmn:process>
        </bpmn:definitions>
    """.trimIndent()

    /** Save and deploy, as the modeler does: the Process page, else the refusal. */
    private fun deploy(bpmn: String = bpmn()): Browser.Page {
        browser.get("/processes")
        return browser.post("/processes/deploy", mapOf("bpmn" to bpmn))
    }

    private fun runs() = history.createHistoricProcessInstanceQuery().processDefinitionKey(key).count()

    /** Lets the loop go round a few times, for what should not happen. */
    private fun settle() = Thread.sleep(1000)

    @Autowired
    lateinit var schedules: Schedules

    private fun deployDrawn(bpmn: String) {
        val operaton = bpmn.replace("http://camunda.org/schema/1.0/bpmn", "http://operaton.org/schema/1.0/bpmn")
        repository.createDeployment().name("$key.bpmn").addString("$key.bpmn", operaton).deploy()
    }

    private fun drawnState(state: String) = home.resolve("schedules.yaml").writeText("$key:\n$state")

    private fun versions() = repository.createProcessDefinitionQuery().processDefinitionKey(key).count()

    private fun latestDiagram() = browser.get("/processes/$key/diagram.bpmn").body

    @Test
    fun `a drawn timer becomes a Schedule with its stored values, the Process's Profile and its paused state, and the latest version has no timer`() {
        twoProfiles()
        val profiled = bpmn(cron = "0 0 * * * *").replace("camunda:historyTimeToLive=\"180\">",
            "camunda:historyTimeToLive=\"180\"><bpmn:extensionElements><camunda:properties><camunda:property name=\"$PROFILE\" value=\"other\" /></camunda:properties></bpmn:extensionElements>")
        deployDrawn(profiled)
        drawnState("  paused: true\n  last: '${clock.instant()}'\n")

        schedules.migrate()
        val migrated = row("Nightly")!!
        val migratedId = idOf("Nightly")
        assertContains(migrated, "<td>other</td>")
        assertContains(migrated, "<span class=\"muted\">Prompt</span> Sweep")
        assertContains(migrated, "Every hour")
        assertContains(migrated, "<span class=\"muted\">paused</span>")
        assertEquals(2, versions())
        assertFalse("timerEventDefinition" in latestDiagram())
        assertContains(latestDiagram(), "value=\"other\"")

        schedules.migrate()
        assertEquals(2, versions())
        assertEquals(1, Regex("<tr id=\"schedule-").findAll(browser.get("/processes/$key").body).count())
        assertEquals(migratedId, idOf("Nightly"))
    }

    @Test
    fun `a migrated Schedule runs the times missed since its last start once, then starts a Run at each time`() {
        home.resolve("settings.yaml").writeAtomically(vault)
        deployDrawn(bpmn(cron = "0 0 * * * *"))
        assertEquals(0, management.createJobQuery().processDefinitionKey(key).count(), "Operaton runs no start timer of its own")
        drawnState("  last: '${clock.instant().minus(Duration.ofHours(5))}'\n")

        schedules.migrate()
        eventually { assertEquals(1, runs()) }
        settle()
        assertEquals(1, runs())
        val run = history.createHistoricProcessInstanceQuery().processDefinitionKey(key).singleResult()
        assertEquals(2, repository.getProcessDefinition(run.processDefinitionId).version)
        val page = browser.get("/runs/${run.id}").body
        assertContains(page, "<dt>prompt</dt><dd><code>Sweep</code></dd>")
        assertContains(page, Regex("<span id=\"name\">Nightly, \\d{1,2} [A-Z][a-z]{2} \\d\\d:\\d\\d</span>"))

        clock.offset = Duration.ofHours(1)
        eventually { assertEquals(2, runs()) }
        assertEquals(0, management.createJobQuery().processDefinitionKey(key).count())
    }

    /** Home's Coming up. */
    private fun comingUp() = browser.get("/").body.substringAfter("id=\"coming-up\">").substringBefore("<div class=\"card\" id=")

    private val api by lazy { repo("api") }
    private val web by lazy { repo("web") }

    private fun twoProfiles() = home.resolve("settings.yaml").writeAtomically(
        vault + sourceMapLines(api, web) +
            "profiles:\n  work:\n    vault: notes\n    source-map: work\n  other:\n    vault: notes\n    source-map: work\ndefault-profile: work\n",
    )

    private fun schedule(values: List<Pair<String, String>>, cron: String = "0 0 * * * *"): Browser.Page {
        browser.get("/processes/clone-repos")
        return browser.post("/processes/clone-repos/schedule", values + ("cron" to cron))
    }

    private fun cloneRuns() = history.createHistoricProcessInstanceQuery().processDefinitionKey("clone-repos").orderByProcessInstanceStartTime().asc().list()

    private fun runPage(run: String) = browser.get("/runs/$run").body.replace("&#34;", "\"")

    @Test
    fun `a Schedule from the start form starts a Run at its time with exactly its values, Profile and Name`() {
        twoProfiles()
        browser.import("clone-repos.bpmn")
        val saved = schedule(listOf(NAME to "Ship api", PROFILE to "other", "repos" to "api", "branch" to "nightly"))
        assertEquals("/processes/clone-repos", saved.path, saved.body)
        settle()
        assertEquals(0, cloneRuns().size)

        clock.offset = Duration.ofHours(1)
        eventually { assertEquals(1, cloneRuns().size) }
        val run = cloneRuns().single().id
        val page = runPage(run)
        assertContains(page, "<dt>branch</dt><dd><code>nightly</code></dd>")
        assertContains(page, "<dt>$PROFILE</dt><dd><code>other</code></dd>")
        assertContains(page, Regex("<span id=\"name\">Ship api, \\d{1,2} [A-Z][a-z]{2} \\d\\d:\\d\\d</span>"))
        eventually { assertEquals(listOf("api"), clones(workspace(run)).map { it.fileName.toString() }) }
    }

    @Test
    fun `two Schedules of one Process with different repos are two rows, and each starts its own Runs, cloning only its repos`() {
        twoProfiles()
        browser.import("clone-repos.bpmn")
        schedule(listOf("repos" to "api", "branch" to "a"))
        schedule(listOf(NAME to "Nightly web", "repos" to "web", "branch" to "w"), "0 30 8 * * MON-FRI")
        assertEquals(1, repository.createProcessDefinitionQuery().processDefinitionKey("clone-repos").count(), "scheduling makes no new version")

        val rows = browser.get("/processes/clone-repos").body.substringAfter("<table id=\"schedules\">").substringBefore("</table>")
        assertContains(rows, "<b>Clone repos · api</b>")
        assertContains(rows, "<b>Nightly web</b>")
        assertContains(rows, "<td>work</td>")
        assertContains(rows, "<span class=\"muted\">Branch</span> w")
        assertContains(rows, "Every hour")
        assertContains(rows, "<code>0 30 8 * * MON-FRI</code>")
        assertContains(rows, "Every weekday at 08:30")
        assertContains(rows, Regex("\\d\\d:\\d\\d · in \\d"))
        home.resolve("settings.yaml").writeAtomically(home.resolve("settings.yaml").readText().replace("default-profile: work", "default-profile: other"))

        clock.offset = Duration.ofDays(4)
        eventually { assertEquals(2, cloneRuns().size) }
        val (hourly, weekday) = cloneRuns().partition { "<dt>branch</dt><dd><code>a</code></dd>" in runPage(it.id) }.toList().map { it.single().id }
        eventually { assertEquals(listOf("api"), clones(workspace(hourly)).map { it.fileName.toString() }) }
        eventually { assertEquals(listOf("web"), clones(workspace(weekday)).map { it.fileName.toString() }) }
        assertContains(runPage(hourly), "<dt>$PROFILE</dt><dd><code>work</code></dd>", message = "the Profile in force when it was saved")
        assertContains(runPage(hourly), Regex("<span id=\"name\">Clone repos, \\d{1,2} [A-Z][a-z]{2} \\d\\d:\\d\\d</span>"))
    }

    @Test
    fun `Schedule is refused, with the filled-in values kept, on an empty required field, a Can't start or a cron Ants can't read`() {
        home.resolve("settings.yaml").writeAtomically(vault)
        deploy()
        assertContains(browser.get("/processes/$key").body, "<fieldset id=\"when\" hidden>")
        val unfilled = browser.post("/processes/$key/schedule", listOf(NAME to "Sweeper", "prompt" to " ", "cron" to "0 0 * * * *"))
        assertEquals(400, unfilled.status)
        assertContains(unfilled.body, "Prompt is required")
        assertContains(unfilled.body, "value=\"Sweeper\"")

        val unreadable = browser.post("/processes/$key/schedule", listOf(NAME to "Sweeper", "prompt" to "Tidy up", "cron" to "R/PT1H"))
        assertEquals(400, unreadable.status)
        assertContains(unreadable.body, "Ants can't read the cron R/PT1H")
        assertContains(unreadable.body, "value=\"Tidy up\"")
        assertContains(unreadable.body, "value=\"R/PT1H\"")
        assertContains(unreadable.body, "<fieldset id=\"when\">")

        home.resolve("settings.yaml").writeAtomically("")
        val cantStart = browser.post("/processes/$key/schedule", listOf("prompt" to "Tidy up", "cron" to "0 0 * * * *"))
        assertEquals(400, cantStart.status)
        assertContains(cantStart.body, "Configure a Vault")
        assertContains(cantStart.body, "value=\"Tidy up\"")

        assertFalse("id=\"schedules\"" in browser.get("/processes/$key").body)
    }

    @Test
    fun `a page Schedule runs times missed once, on the latest version of its Process`() {
        home.resolve("settings.yaml").writeAtomically(vault)
        deploy()
        browser.get("/processes/$key")
        browser.post("/processes/$key/schedule", listOf("prompt" to "Tidy up", "cron" to "0 0 * * * *"))
        deploy(bpmn(stored = "Edited"))
        clock.offset = Duration.ofHours(5)
        eventually { assertEquals(1, runs()) }
        settle()
        assertEquals(1, runs())
        val run = history.createHistoricProcessInstanceQuery().processDefinitionKey(key).singleResult()
        assertEquals(2, repository.getProcessDefinition(run.processDefinitionId).version)
        assertContains(browser.get("/runs/${run.id}").body, "<dt>prompt</dt><dd><code>Tidy up</code></dd>")
    }

    private fun scheduleHourly(name: String, prompt: String = name, cron: String = "0 0 * * * *") {
        browser.get("/processes/$key")
        val saved = browser.post("/processes/$key/schedule", listOf(NAME to name, "prompt" to prompt, "cron" to cron))
        assertEquals("/processes/$key", saved.path, saved.body)
    }

    private fun row(name: String) = Regex("<tr id=\"schedule-[^\"]+\"><td><b>$name</b>.*?</tr>", RegexOption.DOT_MATCHES_ALL)
        .find(browser.get("/processes/$key").body)?.value

    private fun idOf(name: String) = Regex("<tr id=\"schedule-([^\"]+)\">").find(row(name)!!)!!.groupValues[1]

    private fun act(name: String, action: String) = browser.post("/processes/$key/schedules/${idOf(name)}/$action", mapOf())

    private fun runsWith(prompt: String) = history.createHistoricProcessInstanceQuery().processDefinitionKey(key).variableValueEquals("prompt", prompt).count()

    @Test
    fun `Pause stops one Schedule's Runs while the other starts its own, and Resume drops the times passed while paused`() {
        home.resolve("settings.yaml").writeAtomically(vault)
        deploy()
        scheduleHourly("Sweep A")
        scheduleHourly("Sweep B")
        assertEquals("/processes/$key", act("Sweep A", "pause").path)
        assertContains(row("Sweep A")!!, "<button>Resume</button>")
        assertContains(row("Sweep A")!!, "<span class=\"muted\">paused</span>")
        assertContains(row("Sweep B")!!, "<button>Pause</button>")

        clock.offset = Duration.ofHours(3)
        eventually { assertEquals(1, runsWith("Sweep B")) }
        settle()
        assertEquals(0, runsWith("Sweep A"))

        act("Sweep A", "resume")
        assertContains(row("Sweep A")!!, "<button>Pause</button>")
        settle()
        assertEquals(0, runsWith("Sweep A"), "the times passed while paused don't run")
        clock.offset = Duration.ofHours(4)
        eventually { assertEquals(1, runsWith("Sweep A")) }
        eventually { assertEquals(2, runsWith("Sweep B")) }
    }

    @Test
    fun `Remove deletes one Schedule and deploys nothing, and the other keeps starting its Runs`() {
        home.resolve("settings.yaml").writeAtomically(vault)
        deploy()
        scheduleHourly("Sweep A")
        scheduleHourly("Sweep B")
        assertEquals("/processes/$key", act("Sweep A", "remove").path)
        assertEquals(null, row("Sweep A"))
        assertContains(row("Sweep B")!!, "<button>Pause</button>")
        assertEquals(1, repository.createProcessDefinitionQuery().processDefinitionKey(key).count(), "Remove makes no new version")

        clock.offset = Duration.ofHours(2)
        eventually { assertEquals(1, runsWith("Sweep B")) }
        settle()
        assertEquals(0, runsWith("Sweep A"))
    }

    @Test
    fun `Start now starts one Schedule's Run with its values and goes to it`() {
        home.resolve("settings.yaml").writeAtomically(vault)
        deploy()
        scheduleHourly("Sweep A", "Tidy A")
        scheduleHourly("Sweep B", "Tidy B")
        val run = act("Sweep B", "start")
        val started = history.createHistoricProcessInstanceQuery().processDefinitionKey(key).singleResult()
        assertEquals("/runs/${started.id}", run.path)
        assertContains(run.body, "<dt>prompt</dt><dd><code>Tidy B</code></dd>")
        assertContains(run.body, Regex("<span id=\"name\">Sweep B, \\d{1,2} [A-Z][a-z]{2} \\d\\d:\\d\\d</span>"))
        assertEquals(0, runsWith("Tidy A"))
        assertEquals(404, browser.post("/processes/$key/schedules/none/start", mapOf()).status)
    }

    @Test
    fun `an overlapping time of one Schedule is skipped and noted on its row, while the other Schedule still starts its Run`() {
        home.resolve("settings.yaml").writeAtomically(vault)
        deploy(bpmn(waits = true))
        scheduleHourly("Sweep A")
        scheduleHourly("Sweep B")
        clock.offset = Duration.ofHours(1)
        eventually { assertEquals(1, runsWith("Sweep A")) }
        eventually { assertEquals(1, runsWith("Sweep B")) }
        tasks.complete(tasks.createTaskQuery().processDefinitionKey(key).processVariableValueEquals("prompt", "Sweep B").singleResult().id)

        clock.offset = Duration.ofHours(3)
        eventually { assertContains(row("Sweep A")!!, "the last Run from this Schedule was still going") }
        val latest = clock.local(clock.instant().atZone(clock.zone).truncatedTo(ChronoUnit.HOURS).toInstant())
        assertContains(row("Sweep A")!!, "Skipped $latest:")
        eventually { assertEquals(2, runsWith("Sweep B")) }
        assertFalse("Skipped" in row("Sweep B")!!)
        assertEquals(1, runsWith("Sweep A"))

        tasks.complete(tasks.createTaskQuery().processDefinitionKey(key).processVariableValueEquals("prompt", "Sweep A").singleResult().id)
        clock.offset = Duration.ofHours(4)
        eventually { assertEquals(2, runsWith("Sweep A")) }
        eventually { assertFalse("Skipped" in row("Sweep A")!!) }
    }

    private fun failedEntries() = Regex("href=\"/notifications/(schedule-[^\"]+)\"").findAll(browser.get("/notifications").body).map { it.groupValues[1] }.distinct().toList()

    @Test
    fun `a Schedule whose Profile is removed fails as one failed entry by its label, with Start now and Dismiss, noted on its row, and never falls back`() {
        twoProfiles()
        browser.import("clone-repos.bpmn")
        schedule(listOf(NAME to "Ship api", PROFILE to "other", "repos" to "api", "branch" to "nightly"))
        home.resolve("settings.yaml").writeAtomically(vault + sourceMapLines(api, web) + "profiles:\n  work:\n    vault: notes\n    source-map: work\n")

        clock.offset = Duration.ofHours(1)
        eventually { assertEquals(1, failedEntries().size) }
        assertEquals(0, cloneRuns().size)
        val id = browser.get("/processes/clone-repos").body.let { Regex("<tr id=\"schedule-([^\"]+)\">").find(it)!!.groupValues[1] }
        assertEquals(listOf("schedule-$id"), failedEntries())
        assertContains(browser.get("/notifications").body, "Ship api")

        val entry = browser.get("/notifications/schedule-$id").body
        assertContains(entry, "Ship api")
        assertContains(entry, "The scheduled Run didn't start: The Profile other isn't in the settings.")
        assertContains(entry, """<form method="post" action="/processes/clone-repos/schedules/$id/start">""")
        assertContains(entry, """<form method="post" action="/processes/clone-repos/schedules/$id/dismiss">""")
        val row = browser.get("/processes/clone-repos").body.substringAfter("<tr id=\"schedule-$id\">").substringBefore("</tr>")
        assertContains(row, "✕ [failed] The scheduled Run didn't start: The Profile other isn't in the settings.")

        home.resolve("settings.yaml").writeAtomically(vault + sourceMapLines(api, web))
        assertEquals("/processes/clone-repos", browser.post("/processes/clone-repos/schedules/$id/start", mapOf()).path)
        assertEquals(0, cloneRuns().size, "no Profile at all is no fallback to the defaults either")
        assertContains(browser.get("/notifications/schedule-$id").body, "The Profile other isn't in the settings.")

        browser.get("/notifications/schedule-$id")
        assertEquals("/notifications", browser.post("/processes/clone-repos/schedules/$id/dismiss", mapOf("back" to "notifications")).path)
        assertEquals(emptyList(), failedEntries())
        assertFalse("[failed]" in browser.get("/processes/clone-repos").body.substringAfter("<tr id=\"schedule-$id\">").substringBefore("</tr>"))
    }

    @Test
    fun `a repo gone from the Source map fails its Schedule's start, while the other Schedule of the Process starts its Run`() {
        twoProfiles()
        browser.import("clone-repos.bpmn")
        schedule(listOf(NAME to "Ship api", "repos" to "api", "branch" to "a"))
        schedule(listOf(NAME to "Ship web", "repos" to "web", "branch" to "w"))
        home.resolve("settings.yaml").writeAtomically(home.resolve("settings.yaml").readText().replace(Regex("source-maps:\n  work: .*\n"), sourceMapLines(web)))

        clock.offset = Duration.ofHours(1)
        eventually { assertEquals(1, cloneRuns().size) }
        eventually { assertEquals(1, failedEntries().size) }
        assertContains(runPage(cloneRuns().single().id), "<dt>branch</dt><dd><code>w</code></dd>")
        val entry = browser.get("/notifications/${failedEntries().single()}").body
        assertContains(entry, "Ship api")
        assertContains(entry, "The Source map work has no repo api.")
    }

    @Test
    fun `a field made required after scheduling fails both Schedules, as two failed entries, and Dismiss on one leaves the other`() {
        home.resolve("settings.yaml").writeAtomically(vault)
        deploy()
        scheduleHourly("Sweep A")
        scheduleHourly("Sweep B")
        val target = """<camunda:formField id="target" label="Target" type="string"><camunda:validation><camunda:constraint name="required" /></camunda:validation></camunda:formField>"""
        deploy(bpmn().replace("</camunda:formData>", "$target</camunda:formData>"))

        clock.offset = Duration.ofHours(1)
        eventually { assertEquals(2, failedEntries().size) }
        assertEquals(0, runs())
        val list = browser.get("/notifications").body
        assertContains(list, "Sweep A")
        assertContains(list, "Sweep B")
        assertContains(row("Sweep A")!!, "✕ [failed] The scheduled Run didn't start: Target is required")
        assertContains(row("Sweep B")!!, "✕ [failed] The scheduled Run didn't start: Target is required")

        browser.post("/processes/$key/schedules/${idOf("Sweep A")}/dismiss", mapOf("back" to "notifications"))
        assertEquals(listOf("schedule-${idOf("Sweep B")}"), failedEntries())
        assertFalse("[failed]" in row("Sweep A")!!)
        assertContains(row("Sweep B")!!, "[failed]")
    }

    @Test
    fun `Home's Coming up lists every unpaused Schedule's next Run by its label, soonest first`() {
        home.resolve("settings.yaml").writeAtomically(vault)
        deploy()
        scheduleHourly("Sweep yearly", cron = "0 0 0 1 1 *")
        scheduleHourly("Sweep hourly")
        scheduleHourly("Sweep paused")
        act("Sweep paused", "pause")

        val coming = comingUp()
        assertContains(coming, "Sweep hourly")
        assertContains(coming, "Sweep yearly")
        assertFalse("Sweep paused" in coming)
        assertContains(coming, Regex("Sweep hourly.*Sweep yearly", RegexOption.DOT_MATCHES_ALL))
        assertContains(coming, Regex("\\d\\d:\\d\\d · in \\d"))
    }

    private fun processRow(key: String) = Regex("<tr class=\"click\" data-href=\"/processes/$key\">.*?</tr>", RegexOption.DOT_MATCHES_ALL).find(browser.get("/processes").body)!!.value

    @Test
    fun `the Processes list shows how many Schedules a Process has, and nothing with none`() {
        home.resolve("settings.yaml").writeAtomically(vault)
        deploy()
        assertFalse("⏰" in processRow(key))
        scheduleHourly("Sweep A")
        assertContains(processRow(key), "<td class=\"small\">⏰ 1</td>")
        scheduleHourly("Sweep B")
        act("Sweep B", "pause")
        assertContains(processRow(key), "<td class=\"small\">⏰ 2</td>")
        act("Sweep A", "remove")
        act("Sweep B", "remove")
        assertFalse("⏰" in processRow(key))
    }
}
