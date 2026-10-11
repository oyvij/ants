package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.ManagementService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ScheduleTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    @Autowired
    lateinit var history: HistoryService

    @Autowired
    lateinit var runtime: RuntimeService

    @Autowired
    lateinit var management: ManagementService

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

    /** Waits up to 5 s for [check], which the Schedule loop (every 200 ms in tests) should have made true. */
    private fun eventually(check: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!check()) {
            if (System.nanoTime() > deadline) throw AssertionError("not within 5 seconds")
            Thread.sleep(100)
        }
    }

    /** Lets the loop go round a few times, for what should not happen. */
    private fun settle() = Thread.sleep(1000)

    @AfterEach
    fun cleanUp() {
        clock.offset = Duration.ZERO
        // Removing the Schedule keeps it from starting Runs in other tests.
        if (browser.get("/processes/$key").status == 200) browser.post("/processes/$key/schedule/remove", mapOf())
        runtime.createProcessInstanceQuery().processDefinitionKey(key).list().forEach { runtime.deleteProcessInstance(it.id, "test over") }
        history.createHistoricProcessInstanceQuery().processDefinitionKey(key).list().forEach { history.deleteHistoricProcessInstance(it.id) }
        home.resolve("settings.yaml").writeText("")
    }

    @Test
    fun `a Schedule starts a Run at its time, with the stored values`() {
        home.resolve("settings.yaml").writeText(vault)
        deploy()
        assertEquals(0, management.createJobQuery().processDefinitionKey(key).count(), "Operaton runs no start timer of its own")
        settle()
        assertEquals(0, runs())
        clock.offset = Duration.ofHours(1)
        eventually { runs() == 1L }
        val run = history.createHistoricProcessInstanceQuery().processDefinitionKey(key).singleResult()
        assertContains(browser.get("/runs/${run.id}").body, "<dt>prompt</dt><dd><code>Sweep</code></dd>")
    }

    @Test
    fun `several missed times run once`() {
        home.resolve("settings.yaml").writeText(vault)
        deploy()
        clock.offset = Duration.ofHours(5)
        eventually { runs() == 1L }
        settle()
        assertEquals(1, runs())
    }

    @Test
    fun `an overlapping time is skipped and noted on the Process page`() {
        home.resolve("settings.yaml").writeText(vault)
        deploy(bpmn(waits = true))
        clock.offset = Duration.ofHours(1)
        eventually { runs() == 1L }
        clock.offset = Duration.ofHours(2)
        eventually { "the last Run from this Schedule was still going" in browser.get("/processes/$key").body }
        assertEquals(1, runs())
        assertContains(browser.get("/processes/$key").body, "Skipped ")
    }

    @Test
    fun `a scheduled Run that can't start is a failed entry on the Notification list, with Start now and Dismiss`() {
        deploy() // no Vault, so the pre-Run check refuses
        clock.offset = Duration.ofHours(1)
        eventually { "Configure a Vault" in browser.get("/notifications").body }
        val list = browser.get("/notifications").body
        assertContains(list, "✕ [failed]")
        assertContains(list, "Nightly")
        assertContains(list, "href=\"/processes/$key\"")
        assertEquals(0, runs())

        val page = browser.get("/processes/$key").body
        assertContains(page, "<button>Start now</button>")
        assertContains(page, "<button>Dismiss</button>")
        home.resolve("settings.yaml").writeText(vault)
        val run = browser.post("/processes/$key/schedule/start", mapOf())
        assertContains(run.path, "/runs/")
        assertEquals(1, runs())
        assertFalse("Configure a Vault" in browser.get("/notifications").body)

        home.resolve("settings.yaml").writeText("")
        clock.offset = Duration.ofHours(2)
        eventually { "Configure a Vault" in browser.get("/notifications").body }
        browser.get("/processes/$key")
        browser.post("/processes/$key/schedule/dismiss", mapOf())
        assertFalse("Configure a Vault" in browser.get("/notifications").body)
        assertFalse("Start now" in browser.get("/processes/$key").body)
    }

    @Test
    fun `Save and deploy refuses a Schedule while a required field has no stored value, or a time Ants can't run`() {
        val refused = deploy(bpmn(stored = null))
        assertEquals(400, refused.status)
        assertContains(refused.body, "start has a Schedule, so its required start-form field Prompt needs a stored value.")
        assertContains(deploy(bpmn(cron = "R/PT1H")).body, "start has a Schedule that isn't a cron")
        assertEquals(404, browser.get("/processes/$key").status)
    }

    /** Home's Coming up. */
    private fun comingUp() = browser.get("/").body.substringAfter("<section id=\"coming-up\">").substringBefore("</section>")

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
        assertFalse("<section id=\"schedule\">" in browser.get("/processes/$key").body)
        assertFalse("timerEventDefinition" in browser.get("/processes/$key/diagram.bpmn").body)
        assertFalse("Nightly" in comingUp())
    }
}
