package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.TaskService
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.temporal.ChronoUnit
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

    private val key = "nightly"

    /**
     * The Process [key] with the Schedule [cron] on its timer start, a required `prompt` with the stored value
     * [stored], then a User Task when [waits], so its Run stays live.
     */
    private fun bpmn(cron: String = "0 0 * * * *", stored: String? = "Sweep", waits: Boolean = false) = """
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
              <bpmn:timerEventDefinition><bpmn:timeCycle>$cron</bpmn:timeCycle></bpmn:timerEventDefinition>
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

    @Test
    fun `a Schedule starts a Run at its time, with the stored values`() {
        home.resolve("settings.yaml").writeText(vault)
        deploy()
        assertEquals(0, management.createJobQuery().processDefinitionKey(key).count(), "Operaton runs no start timer of its own")
        settle()
        assertEquals(0, runs())
        clock.offset = Duration.ofHours(1)
        eventually { assertEquals(1, runs()) }
        val run = history.createHistoricProcessInstanceQuery().processDefinitionKey(key).singleResult()
        val page = browser.get("/runs/${run.id}").body
        assertContains(page, "<dt>prompt</dt><dd><code>Sweep</code></dd>")
        assertContains(page, Regex("<span id=\"name\">Nightly, \\d{1,2} [A-Z][a-z]{2} \\d\\d:\\d\\d</span>")) // named as an unnamed Run is
    }

    @Test
    fun `several missed times run once`() {
        home.resolve("settings.yaml").writeText(vault)
        deploy()
        clock.offset = Duration.ofHours(5)
        eventually { assertEquals(1, runs()) }
        settle()
        assertEquals(1, runs())
    }

    @Test
    fun `an overlapping time is skipped and noted on the Process page, by the latest time skipped, until a Run starts again`() {
        home.resolve("settings.yaml").writeText(vault)
        deploy(bpmn(waits = true))
        clock.offset = Duration.ofHours(1)
        eventually { assertEquals(1, runs()) }
        clock.offset = Duration.ofHours(3) // two times come while the Run waits
        eventually { assertContains(browser.get("/processes/$key").body, "the last Run from this Schedule was still going") }
        assertEquals(1, runs())
        val latest = clock.local(clock.instant().atZone(clock.zone).truncatedTo(ChronoUnit.HOURS).toInstant())
        assertContains(browser.get("/processes/$key").body, "Skipped $latest:")

        tasks.complete(tasks.createTaskQuery().processDefinitionKey(key).singleResult().id)
        clock.offset = Duration.ofHours(4)
        eventually { assertEquals(2, runs()) }
        eventually { assertFalse("Skipped " in browser.get("/processes/$key").body) } // cleared just after the Run starts
    }

    @Test
    fun `a scheduled Run that can't start is a failed entry on the Notification list, with Start now and Dismiss`() {
        home.resolve("settings.yaml").writeText("") // no Vault, so the pre-Run check refuses
        deploy()
        clock.offset = Duration.ofHours(1)
        eventually { assertContains(browser.get("/notifications").body, "Configure a Vault") }
        val list = browser.get("/notifications").body
        assertContains(list, "<i>✕</i>[failed]")
        assertContains(list, "Nightly")
        assertContains(list, "href=\"/notifications/schedule-$key\"")
        assertEquals(0, runs())

        val entry = browser.get("/notifications/schedule-$key").body
        assertContains(entry, "The scheduled Run didn't start: Configure a Vault")
        assertContains(entry, """<form method="post" action="/processes/$key/schedule/start">""")
        assertContains(entry, """<form method="post" action="/processes/$key/schedule/dismiss">""")
        home.resolve("settings.yaml").writeText(vault)
        val run = browser.post("/processes/$key/schedule/start", mapOf())
        assertContains(run.path, "/runs/")
        assertEquals(1, runs())
        assertFalse("Configure a Vault" in browser.get("/notifications").body)

        home.resolve("settings.yaml").writeText("")
        clock.offset = Duration.ofHours(2)
        eventually { assertContains(browser.get("/notifications").body, "Configure a Vault") }
        assertContains(browser.get("/processes/$key").body, """<button class="primary">Start now</button>""") // the Process page has them too
        browser.get("/notifications/schedule-$key")
        assertEquals("/notifications", browser.post("/processes/$key/schedule/dismiss", mapOf("back" to "notifications")).path)
        assertFalse("Configure a Vault" in browser.get("/notifications").body)
        assertFalse("Start now" in browser.get("/processes/$key").body)
        assertEquals(404, browser.get("/notifications/schedule-$key").status)
    }

    @Test
    fun `Save and deploy refuses a Schedule while a required field has no stored value, or a time Ants can't run`() {
        val refused = deploy(bpmn(stored = null))
        assertEquals(400, refused.status)
        assertContains(refused.body, "start has a Schedule, so its required start-form field Prompt needs a stored value.")
        assertContains(deploy(bpmn(cron = "R/PT1H")).body, "start has a Schedule whose times Ants can't read")
        assertEquals(404, browser.get("/processes/$key").status)
    }

    /** Home's Coming up. */
    private fun comingUp() = browser.get("/").body.substringAfter("id=\"coming-up\">").substringBefore("<div class=\"card\" id=")

    @Test
    fun `Home's Coming up lists the next scheduled Runs, a paused Schedule doesn't run, and a removed one is gone`() {
        home.resolve("settings.yaml").writeText(vault)
        deploy(bpmn(cron = "0 30 8 * * *"))
        assertContains(browser.get("/processes/$key").body, "Every day at 08:30")
        assertContains(comingUp(), "Nightly")
        assertContains(comingUp(), "08:30")

        browser.post("/processes/$key/schedule/pause", mapOf())
        assertContains(browser.get("/processes/$key").body, "paused")
        assertFalse("Nightly" in comingUp())
        clock.offset = Duration.ofDays(2)
        settle()
        assertEquals(0, runs())

        browser.post("/processes/$key/schedule/resume", mapOf())
        settle()
        assertEquals(0, runs(), "the times missed while paused don't run")
        assertContains(comingUp(), "Nightly")

        browser.post("/processes/$key/schedule/remove", mapOf())
        assertContains(browser.get("/processes/$key").body, "No schedule. Runs start when someone presses Start.")
        assertFalse("timerEventDefinition" in browser.get("/processes/$key/diagram.bpmn").body)
        assertFalse("Nightly" in comingUp())
    }
}
