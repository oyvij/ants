package ants

import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** An Agent Task's Assignment and its list of Skills, `ants.skills`, at the fake-claude launch seam (ADR 0024). */
class AssignmentTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Greeted.","output":{}}}"""

    /** The Agent `writer` listing [skills] (`"*"` for all), and a Skill for each name in [names]. */
    private fun writer(skills: String, vararg names: String) {
        agentTask(claude, names.associateWith { null })
        home.resolve("agents/writer.md").writeText("---\nskills: $skills\n---\nWrite plainly.\n")
    }

    /**
     * Deploys agent-task.bpmn as [key], its Agent Task `greet` carrying [properties] in place of its `ants.skill`, and
     * starts a Run of it.
     */
    private fun start(key: String, vararg properties: Pair<String, String>): String {
        val bpmn = javaClass.getResource("/agent-task.bpmn")!!.readText().replace("\"agent-task\"", "\"$key\"")
            .replace("""<operaton:property name="ants.skill" value="draft" />""", "PROPERTIES")
            .replace("\"draft\"", "\"greet\"")
            .replace("PROPERTIES", properties.joinToString("") { (name, value) -> """<operaton:property name="$name" value="${value.replace("\n", "&#10;")}" />""" })
        browser.get("/processes")
        val page = browser.upload("/processes", "file", "$key.bpmn", bpmn.toByteArray()).body
        assertFalse("role=\"alert\"" in page, page)
        browser.get("/processes/$key")
        return browser.post("/processes/$key/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    private fun completed(run: String) = eventually { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }

    private fun skillsOf(vararg names: String) = names.associateWith { home.resolve("skills/$it") }

    @Test
    fun `a Task with an Assignment and two listed Skills starts with the Assignment and loads exactly those two`() {
        writer("draft, notes, extra", "draft", "notes", "extra")
        claude.session("greet", done)
        val run = start("assigned", "ants.assignment" to "Greet the user.\nKeep it short.", "ants.skills" to "draft, notes")
        completed(run)
        val launch = claude.launches("greet").single()
        val prompt = "The workspace is ${workspace(run)}.\\n\\nGreet the user.\\nKeep it short." +
            """\n\n{\"prompt\":\"Say hello\",\"tone\":\"dry\"}"""
        assertEquals("""{"type":"user","message":{"role":"user","content":"$prompt"}}""", launch.stdin)
        assertEquals(skillsOf("draft", "notes"), launch.skills)
        assertContains(launch.flag("--tools")!!, "Skill")
    }

    @Test
    fun `a Task with no list of Skills gets its Agent's, and with the Agent's star the whole catalogue`() {
        writer("draft, notes", "draft", "notes", "extra")
        claude.session("greet", done)
        completed(start("inherits", "ants.assignment" to "Greet the user."))
        assertEquals(skillsOf("draft", "notes"), claude.launches("greet").last().skills)

        home.resolve("agents/writer.md").writeText("---\nskills: \"*\"\n---\nWrite plainly.\n")
        completed(start("everything", "ants.assignment" to "Greet the user."))
        val every = claude.launches("greet").last().skills
        assertEquals(skillsOf("draft", "notes", "extra"), every.filterKeys { it in setOf("draft", "notes", "extra") })
        assertContains(every.keys, "grill") // shipped ones too
    }

    @Test
    fun `a Task with an empty list of Skills has no Skill tool and no skills folder`() {
        writer("draft", "draft")
        claude.session("greet", done)
        val run = start("none", "ants.assignment" to "Greet the user.", "ants.skills" to "")
        completed(run)
        val launch = claude.launches("greet").single()
        assertFalse("Skill" in launch.flag("--tools")!!.split(","))
        assertFalse("Skill" in launch.flag("--allowedTools")!!.split(","))
        assertFalse(launch.argv.any { "/.skills/" in it })
        assertEquals(emptyMap(), launch.skills)
    }

    @Test
    fun `a Task with one listed Skill and no Assignment starts with the Skill as its job`() {
        writer("draft, notes", "draft", "notes")
        claude.session("draft", done)
        val run = start("one", "ants.skills" to "draft")
        completed(run)
        val launch = claude.launches("draft").single()
        assertContains(launch.stdin, """"content":"/draft The workspace is ${workspace(run)}.\n\n{""")
        assertEquals(skillsOf("draft"), launch.skills)
    }
}
