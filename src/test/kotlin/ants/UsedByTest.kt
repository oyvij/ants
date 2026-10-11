package ants

import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsedByTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    private fun get(path: String) = browser.get(path).body.replace("&#34;", "\"")

    /** Posts [form] to [path] from the page it's on. */
    private fun post(path: String, form: Map<String, String>): Browser.Page {
        browser.get(path.removeSuffix("/delete"))
        return browser.post(path, form)
    }

    /** The Agent [name] in the user's folder. */
    private fun agent(name: String) = home.resolve("agents").createDirectories().resolve("$name.md").also { it.writeText("---\nname: $name\n---\nKeep it.\n") }

    /** The Skill [name] in the user's folder, using [uses]. */
    private fun skill(name: String, uses: String? = null) = home.resolve("skills/$name").createDirectories().resolve("SKILL.md").also {
        it.writeText("---\nname: $name\ndescription: Keeps notes.\n" + uses?.let { "metadata:\n  ants-uses: $it\n" }.orEmpty() + "---\nKeep notes.\n")
    }

    /** Deploys the Process used-by, "Keep notes", whose one Agent Task runs [agent] with [skill]; the Agent lists the Skill, and both exist. */
    private fun deploy(agent: String, skill: String) {
        home.resolve("agents").resolve("$agent.md").writeText("---\nname: $agent\nskills: $skill\n---\nKeep it.\n")
        if (!home.resolve("skills/$skill").exists()) skill(skill)
        browser.get("/processes")
        val bpmn = javaClass.getResource("/used-by.bpmn")!!.readText().replace("keeper", agent).replace("keep-notes", skill)
        browser.upload("/processes", "file", "used-by.bpmn", bpmn.toByteArray())
    }

    private val keepNotes = """<a href="/processes/used-by">Keep notes</a>"""

    @Test
    fun `Used by lists the deployed Processes and using Skills`() {
        agent("keeper")
        skill("keep-notes")
        skill("keep-index", uses = "keep-notes")
        deploy("keeper", "keep-notes")

        val agent = get("/agents/keeper")
        assertContains(agent, "Used by")
        assertContains(agent, keepNotes)

        val skill = get("/skills/keep-notes")
        assertContains(skill, keepNotes)
        assertContains(skill, """<a href="/skills/keep-index">keep-index</a>""")
        assertContains(get("/skills/keep-index"), "Nothing uses it.")
    }

    @Test
    fun `Used by also lists an older Process version that live Runs still run`() {
        agent("old-keeper")
        deploy("old-keeper", "old-keeper-skill")
        home.resolve("skills/old-keeper-skill").toFile().deleteRecursively()
        browser.get("/processes/used-by")
        val run = browser.post("/processes/used-by/runs", mapOf("prompt" to "Keep it")).path.removePrefix("/runs/")
        assertContains(get("/runs/$run"), "State: Running") // its Skill is missing, so its Task waits on the user
        deploy("new-keeper", "old-keeper-skill")

        val page = get("/agents/old-keeper")
        assertContains(page, keepNotes)
        assertContains(page, Regex("""version \d+, which live Runs still run"""))
    }

    @Test
    fun `renaming an Agent asks for confirmation, lists what would break and leaves deployed Processes unchanged`() {
        val file = agent("renamed-agent")
        deploy("renamed-agent", "renamed-agent-skill")
        val diagram = browser.get("/processes/used-by/diagram.bpmn").body
        val form = mapOf("name" to "renamed-agent-2", "cli" to "claude", "instructions" to "Keep it.")

        val asked = post("/agents/renamed-agent", form)
        assertContains(asked.body, "Rename the Agent renamed-agent to renamed-agent-2?")
        assertContains(asked.body, keepNotes)
        assertTrue(file.exists())

        assertEquals("/agents/renamed-agent-2", browser.post("/agents/renamed-agent", form + ("confirm" to "on")).path)
        assertFalse(file.exists())
        assertEquals(diagram, browser.get("/processes/used-by/diagram.bpmn").body)
    }

    @Test
    fun `deleting an Agent asks for confirmation and lists what would break`() {
        val file = agent("deleted-agent")
        deploy("deleted-agent", "deleted-agent-skill")
        val asked = post("/agents/deleted-agent/delete", emptyMap())
        assertContains(asked.body, "Delete the Agent deleted-agent?")
        assertContains(asked.body, keepNotes)
        assertTrue(file.exists())

        assertEquals("/agents", browser.post("/agents/deleted-agent/delete", mapOf("confirm" to "on")).path)
        assertFalse(file.exists())
    }

    @Test
    fun `renaming a Skill asks for confirmation, lists what would break and leaves deployed Processes unchanged`() {
        val file = skill("renamed-skill")
        skill("renamed-skill-user", uses = "renamed-skill")
        deploy("renamed-skill-agent", "renamed-skill")
        val diagram = browser.get("/processes/used-by/diagram.bpmn").body
        val form = mapOf("skill" to file.readText().replace("name: renamed-skill", "name: renamed-skill-2"), "schema" to "")

        val asked = post("/skills/renamed-skill", form)
        assertContains(asked.body, "Rename the Skill renamed-skill to renamed-skill-2?")
        assertContains(asked.body, keepNotes)
        assertContains(asked.body, """<a href="/skills/renamed-skill-user">renamed-skill-user</a>""")
        assertTrue(file.exists())

        assertEquals("/skills/renamed-skill-2", browser.post("/skills/renamed-skill", form + ("confirm" to "on")).path)
        assertFalse(file.parent.exists())
        assertContains(home.resolve("skills/renamed-skill-2/SKILL.md").readText(), "name: renamed-skill-2\n")
        assertEquals(diagram, browser.get("/processes/used-by/diagram.bpmn").body)
    }

    @Test
    fun `deleting a Skill asks for confirmation and lists what would break`() {
        val file = skill("deleted-skill")
        skill("deleted-skill-user", uses = "deleted-skill")
        val asked = post("/skills/deleted-skill/delete", emptyMap())
        assertContains(asked.body, "Delete the Skill deleted-skill?")
        assertContains(asked.body, """<a href="/skills/deleted-skill-user">deleted-skill-user</a>""")
        assertTrue(file.exists())

        assertEquals("/skills", browser.post("/skills/deleted-skill/delete", mapOf("confirm" to "on")).path)
        assertFalse(file.parent.exists())
    }
}
