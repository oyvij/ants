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
    private fun agent(name: String) = home.resolve("agents").createDirectories().resolve("$name.md").also { it.writeText("---\nid: a-$name\nname: $name\n---\nKeep it.\n") }

    /** The Skill [name] in the user's folder. */
    private fun skill(name: String) = home.resolve("skills/$name").createDirectories().also { it.resolve("ants.yaml").writeText("id: k-$name\n") }.resolve("SKILL.md").also {
        it.writeText("---\nname: $name\ndescription: Keeps notes.\n---\nKeep notes.\n")
    }

    /** Deploys the Process used-by, "Keep notes", whose one Agent Task runs [agent] with [skill]; the Agent lists the Skill, and both exist. */
    private fun deploy(agent: String, skill: String) {
        home.resolve("agents").resolve("$agent.md").writeText("---\nid: a-$agent\nname: $agent\nskills: k-$skill\n---\nKeep it.\n")
        if (!home.resolve("skills/$skill").exists()) skill(skill)
        browser.get("/processes")
        val bpmn = javaClass.getResource("/used-by.bpmn")!!.readText().replace("keeper", agent).replace("keep-notes", skill)
        browser.upload("/processes", "file", "used-by.bpmn", bpmn.toByteArray())
    }

    private val keepNotes = """<a href="/processes/used-by">Keep notes</a>"""

    @Test
    fun `Used by lists the deployed Processes whose Tasks name an Agent or list a Skill`() {
        agent("keeper")
        skill("keep-notes")
        skill("keep-index")
        deploy("keeper", "keep-notes")

        val agent = get("/agents/keeper")
        assertContains(agent, "Used by")
        assertContains(agent, keepNotes)

        val skill = get("/skills/keep-notes")
        assertContains(skill, keepNotes)
        assertContains(get("/skills/keep-index"), "Used by: no Process")
    }

    @Test
    fun `Used by also lists an older Process version that live Runs still run`() {
        agent("old-keeper")
        deploy("old-keeper", "old-keeper-skill")
        val settings = "claude:\n  command: [${FakeClaude().command}]\n$vault"
        home.resolve("settings.yaml").writeAtomically("agent-tasks: 0\n$settings") // started while the Skill is there, launched once it's gone
        browser.get("/processes/used-by")
        val run = browser.post("/processes/used-by/runs", mapOf("prompt" to "Keep it")).path.removePrefix("/runs/")
        home.resolve("skills/old-keeper-skill").toFile().deleteRecursively()
        home.resolve("settings.yaml").writeAtomically(settings)
        assertContains(get("/runs/$run"), """id="state">Running<""") // its Skill is missing, so its Task waits on the user
        deploy("new-keeper", "old-keeper-skill")

        val page = get("/agents/old-keeper")
        assertContains(page, keepNotes)
        assertContains(page, Regex("""version \d+, which live Runs still run"""))
    }

    @Test
    fun `renaming an Agent saves at once with no confirm, leaves deployed Processes unchanged, and they still use it`() {
        val file = agent("renamed-agent")
        deploy("renamed-agent", "renamed-agent-skill")
        val diagram = browser.get("/processes/used-by/diagram.bpmn").body
        val form = mapOf("name" to "renamed-agent-2", "cli" to "claude", "skills" to "renamed-agent-skill", "instructions" to "Keep it.")

        assertEquals("/agents/renamed-agent-2", post("/agents/renamed-agent", form).path)
        assertFalse(file.exists())
        assertEquals(diagram, browser.get("/processes/used-by/diagram.bpmn").body)
        assertContains(get("/agents/renamed-agent-2"), keepNotes)
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
    fun `renaming a Skill saves at once with no confirm, leaves deployed Processes unchanged, and they still use it`() {
        val file = skill("renamed-skill")
        deploy("renamed-skill-agent", "renamed-skill")
        val diagram = browser.get("/processes/used-by/diagram.bpmn").body
        val form = mapOf("skill" to file.readText().replace("name: renamed-skill", "name: renamed-skill-2"))

        assertEquals("/skills/renamed-skill-2", post("/skills/renamed-skill", form).path)
        assertFalse(file.parent.exists())
        assertContains(home.resolve("skills/renamed-skill-2/SKILL.md").readText(), "name: renamed-skill-2\n")
        assertEquals(diagram, browser.get("/processes/used-by/diagram.bpmn").body)
        assertContains(get("/skills/renamed-skill-2"), keepNotes)
    }

    @Test
    fun `deleting a Skill asks for confirmation and lists what would break`() {
        val file = skill("deleted-skill")
        deploy("deleted-skill-agent", "deleted-skill")
        val asked = post("/skills/deleted-skill/delete", emptyMap())
        assertContains(asked.body, "Delete the Skill deleted-skill?")
        assertContains(asked.body, keepNotes)
        assertTrue(file.exists())

        assertEquals("/skills", browser.post("/skills/deleted-skill/delete", mapOf("confirm" to "on")).path)
        assertFalse(file.parent.exists())
    }
}
