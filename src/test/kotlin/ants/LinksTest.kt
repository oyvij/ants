package ants

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinksTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Drafted.","output":{}}}"""
    private val agentTask = javaClass.getResource("/agent-task.bpmn")!!.readText()

    private fun writerAndDraft() {
        agentTask(claude, agent = "id: a-writer\n", extra = "agent-tasks: 3\n")
        home.resolve("skills/draft/ants.yaml").writeText("id: k-draft\n")
    }

    private fun linking(key: String, properties: String) = agentTask.replace("\"agent-task\"", "\"$key\"").replace(
        """<operaton:property name="ants.agent" value="writer" />
          <operaton:property name="ants.skills" value="draft" />""",
        properties,
    )

    private fun property(name: String, value: String) = """<operaton:property name="$name" value="$value" />"""

    private fun deploy(bpmn: String) = browser.get("/processes").let { browser.post("/processes/deploy", mapOf("bpmn" to bpmn)) }

    private fun diagram(key: String) = browser.get("/processes/$key/diagram.bpmn").body

    private fun linksById(diagram: String) {
        assertContains(diagram, """name="ants.agent" value="a-writer"""")
        assertContains(diagram, """name="ants.name.a-writer" value="writer"""")
        assertContains(diagram, """name="ants.skills" value="k-draft"""")
        assertContains(diagram, """name="ants.name.k-draft" value="draft"""")
    }

    private fun start(key: String) = browser.get("/processes/$key").let { browser.post("/processes/$key/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/") }

    @Test
    fun `a Process written with names is saved holding ids, each name beside its id`() {
        writerAndDraft()
        assertEquals("/processes/by-name", deploy(linking("by-name", property("ants.agent", "writer") + property("ants.skills", "draft"))).path)
        linksById(diagram("by-name"))
    }

    @Test
    fun `an imported Process whose ids are unknown here is resolved by the name beside each id`() {
        writerAndDraft()
        val elsewhere = linking("imported", property("ants.agent", "a-zz0001") + property("ants.name.a-zz0001", "writer") +
            property("ants.skills", "k-zz0001") + property("ants.name.k-zz0001", "draft"))
        browser.get("/processes")
        assertFalse("role=\"alert\"" in browser.upload("/processes", "file", "imported.bpmn", elsewhere.toByteArray()).body)
        val diagram = diagram("imported")
        linksById(diagram)
        assertFalse("zz0001" in diagram)
    }

    @Test
    fun `a Check refuses a link whose id and name both match nothing, naming it by its name alone`() {
        writerAndDraft()
        val agentGone = linking("gone", property("ants.agent", "a-zz0002") + property("ants.name.a-zz0002", "nobody") + property("ants.skills", "k-draft"))
        val refused = deploy(agentGone)
        assertEquals(400, refused.status)
        assertContains(refused.body, "Draft (draft): No Agent named nobody.")
        val skillGone = linking("gone", property("ants.agent", "a-writer") + property("ants.skills", "k-zz0003") + property("ants.name.k-zz0003", "nothing"))
        val checks = JsonMapper.shared().readValue(browser.post("/processes/checks", mapOf("bpmn" to skillGone)).body, List::class.java)
        assertEquals(listOf(mapOf("element" to "draft", "message" to "Draft (draft): No Skill named nothing.", "warning" to false)), checks)
        assertFalse("zz000" in refused.body + checks)
    }

    @Test
    fun `a saved Process keeps the current name beside each id`() {
        writerAndDraft()
        deploy(linking("stale", property("ants.agent", "a-writer") + property("ants.name.a-writer", "old-writer") + property("ants.skills", "k-draft")))
        linksById(diagram("stale"))
        assertFalse("old-writer" in diagram("stale"))
    }

    @Test
    fun `a saved Template holds ids, each name beside its id`() {
        writerAndDraft()
        browser.get("/templates")
        browser.post("/templates/new", mapOf("name" to "linked"))
        browser.post("/templates/linked", mapOf("bpmn" to linking("linked", property("ants.agent", "writer") + property("ants.skills", "draft"))))
        linksById(home.resolve("templates/linked.bpmn").readText())
    }

    @Test
    fun `renaming the Agent and the Skill leaves a Process that uses them running, and its pages show the new names`() {
        writerAndDraft()
        claude.session("draft", done)
        deploy(linking("renamed", property("ants.agent", "writer") + property("ants.skills", "draft")))
        home.resolve("agents/writer.md").writeText("---\nid: a-writer\nname: author\nskills: drafting\n---\nWrite plainly.\n")
        home.resolve("skills/draft/SKILL.md").writeText("---\nname: drafting\ndescription: Drafts.\n---\nDo it.\n")
        claude.session("drafting", done)

        val run = start("renamed")
        eventually { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        assertTrue(claude.launches("drafting").single().stdin.contains("\"content\":\"/drafting "))
        val page = browser.get("/runs/$run").body
        assertContains(page, """<a href="/agents/author">author</a>""")
        assertContains(page, """<a href="/skills/drafting">drafting</a>""")
        assertFalse(Regex("""a-writer|k-draft\b""").containsMatchIn(page))
    }

    @Test
    fun `Used by finds a Process by the id it links, after a rename`() {
        writerAndDraft()
        deploy(linking("used", property("ants.agent", "writer") + property("ants.skills", "draft")))
        home.resolve("agents/writer.md").writeText("---\nid: a-writer\nname: author\nskills: drafting\n---\nWrite plainly.\n")
        home.resolve("skills/draft/SKILL.md").writeText("---\nname: drafting\ndescription: Drafts.\n---\nDo it.\n")
        assertContains(browser.get("/agents/author").body, """<a href="/processes/used">""")
        assertContains(browser.get("/skills/drafting").body, """<a href="/processes/used">""")
    }

    @Test
    fun `the modeler's catalogue gives each Agent's and Skill's id to link by`() {
        writerAndDraft()
        home.resolve("skills/other").createDirectories().resolve("SKILL.md").writeText("---\nname: other\ndescription: Other.\n---\nDo it.\n")
        val catalogue = JsonMapper.shared().readValue(browser.get("/catalogue.json").body, Map::class.java)
        val writer = (catalogue["agents"] as List<*>).map { it as Map<*, *> }.single { it["name"] == "writer" }
        assertEquals("a-writer", writer["id"])
        val draft = (catalogue["skills"] as List<*>).map { it as Map<*, *> }.single { it["name"] == "draft" }
        assertEquals("k-draft", draft["id"])
    }
}
