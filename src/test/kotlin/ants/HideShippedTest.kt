package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HideShippedTest : AntsTest() {
    @Autowired
    private lateinit var processes: Processes

    @Autowired
    private lateinit var catalogue: Catalogue

    private val browser by lazy { browser().also { it.login() } }
    private val file = home.resolve("settings.yaml")
    private lateinit var before: String

    @BeforeEach
    fun hide() {
        before = file.readText()
        settings(null, extra = "hide-shipped: true\n")
    }

    @AfterEach
    fun restore() = file.writeText(before)

    private fun show() = settings(null)

    @Test
    fun `hidden shipped Agents and Skills leave their lists, and their pages and Copy answer 404, until the toggle is off`() {
        assertFalse("shipped-writer" in browser.get("/agents").body)
        assertFalse("shipped-draft" in browser.get("/skills").body)
        assertEquals(404, browser.get("/agents/shipped-writer").status)
        assertEquals(404, browser.get("/skills/shipped-draft").status)
        browser.get("/agents")
        assertEquals(404, browser.post("/agents/shipped-writer/copy", emptyMap()).status)
        assertEquals(404, browser.post("/skills/shipped-draft/copy", emptyMap()).status)

        show()
        assertContains(browser.get("/agents").body, "shipped-writer")
        assertContains(browser.get("/skills").body, "shipped-draft")
        assertEquals(200, browser.get("/agents/shipped-writer").status)
        assertEquals(200, browser.get("/skills/shipped-draft").status)
    }

    @Test
    fun `the modeler's choices and the Agent form's Skill picker leave hidden shipped items out`() {
        assertFalse("shipped-writer" in browser.get("/catalogue.json").body)
        assertFalse("shipped-draft" in browser.get("/catalogue.json").body)
        assertFalse("shipped-draft" in browser.get(browser.newDraft("/agents")).body)

        show()
        assertContains(browser.get("/catalogue.json").body, "shipped-writer")
        assertContains(browser.get(browser.newDraft("/agents")).body, "shipped-draft")
    }

    @Test
    fun `hidden shipped Processes leave the lists and Home, and their pages answer 404, until the toggle is off`() {
        processes.deployShipped()
        assertFalse("set_up_vault" in browser.get("/processes").body)
        assertFalse("set_up_vault" in browser.get("/processes.json").body)
        assertFalse("set_up_vault" in browser.get("/").body)
        assertEquals(404, browser.get("/processes/set_up_vault").status)
        assertEquals(404, browser.get("/processes/set_up_vault/edit").status)
        assertEquals(404, browser.get("/processes/set_up_vault/diagram.bpmn").status)

        show()
        assertContains(browser.get("/processes").body, "set_up_vault")
        assertContains(browser.get("/processes.json").body, "set_up_vault")
        assertContains(browser.get("/").body, "set_up_vault")
        assertEquals(200, browser.get("/processes/set_up_vault").status)
    }

    @Test
    fun `a shipped Process the user has saved over is theirs, so it stays listed`() {
        processes.deployShipped()
        val bpmn = home.resolve("shipped/processes/set-up-vault.bpmn").readText().replace("name=\"Set up Vault\"", "name=\"My Vault set-up\"")
        browser.get("/processes")
        assertEquals("/processes/set_up_vault", browser.post("/processes/deploy", mapOf("bpmn" to bpmn)).path)

        assertContains(browser.get("/processes").body, "My Vault set-up")
        assertEquals(200, browser.get("/processes/set_up_vault").status)
    }

    @Test
    fun `while hidden the user's own Skill and Agent win a shipped name, and with the toggle off the shipped ones win it again`() {
        assertEquals("/skills/shipped-draft", browser.publishNew("/skills", mapOf("skill" to "---\nname: shipped-draft\ndescription: Mine.\n---\nMine.\n", "schema" to "")).path)
        assertEquals("/agents/shipped-writer", browser.publishNew("/agents", mapOf("name" to "shipped-writer", "description" to "Mine.")).path)
        assertEquals(home.resolve("skills/shipped-draft/SKILL.md"), catalogue.skill("shipped-draft").file)
        assertEquals(home.resolve("agents/shipped-writer.md"), catalogue.agent("shipped-writer").file)

        show()
        assertEquals(home.resolve("shipped/skills/shipped-draft/SKILL.md"), catalogue.skill("shipped-draft").file)
        assertEquals(home.resolve("shipped/agents/shipped-writer.md"), catalogue.agent("shipped-writer").file)
        assertContains(browser.get("/skills").body, "hidden by the shipped Skill")
    }

    @Test
    fun `a Process naming a hidden shipped Agent and Skill still passes the Checks`() {
        browser.get("/processes")
        val bpmn = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                              id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="uses_shipped" name="Uses shipped" isExecutable="true" operaton:historyTimeToLive="180">
                <bpmn:startEvent id="start" />
                <bpmn:serviceTask id="draft" name="Draft" operaton:type="external" operaton:topic="agent">
                  <bpmn:extensionElements><operaton:properties>
                    <operaton:property name="ants.agent" value="shipped-writer" />
                    <operaton:property name="ants.skills" value="shipped-draft" />
                  </operaton:properties></bpmn:extensionElements>
                </bpmn:serviceTask>
              </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()
        val checks = browser.post("/processes/checks", mapOf("bpmn" to bpmn)).body
        assertFalse("No Agent named" in checks, checks)
        assertFalse("No Skill named" in checks, checks)
    }
}
