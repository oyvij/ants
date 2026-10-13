package ants

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SkillsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    /** A `SKILL.md` named [name]. */
    private fun skillMd(name: String, body: String = "Write the summary.") = "---\nname: $name\ndescription: Summarises a text.\n---\n$body\n"

    /** Saves the create form, or the edit form of the Skill [name], with these files. */
    private fun save(skill: String, schema: String = "", name: String? = null): Browser.Page {
        val path = if (name == null) "/skills/new" else "/skills/$name"
        browser.get(path)
        return browser.post(path, mapOf("skill" to skill, "schema" to schema))
    }

    private fun page(path: String) = browser.get(path).body.replace("&#34;", "\"")

    @Test
    fun `creating and editing a Skill writes its folder in ants skills`() {
        val folder = home.resolve("skills/summarise")
        folder.toFile().deleteRecursively()
        val schema = """{"type": "object", "properties": {"summary": {"type": "string"}}}"""
        assertEquals("/skills/summarise", save(skillMd("summarise"), schema).path)
        assertEquals(skillMd("summarise"), folder.resolve("SKILL.md").readText())
        assertEquals(schema, folder.resolve("result.schema.json").readText())
        assertContains(page("/skills"), """<a href="/skills/summarise">summarise</a>""")

        save(skillMd("summarise", "Write it shorter."), "", name = "summarise")
        assertEquals(skillMd("summarise", "Write it shorter."), folder.resolve("SKILL.md").readText())
        assertFalse(folder.resolve("result.schema.json").exists()) // no schema: any output
        assertContains(page("/skills/summarise"), "Write it shorter.")
    }

    @Test
    fun `the lists show each Agent's CLI, model and Skills and each Skill's Run by, with a Vault tag`() {
        val skill = home.resolve("skills/note-taker").createDirectories()
        skill.resolve("SKILL.md").writeText("---\nname: note-taker\ndescription: Takes notes.\nmetadata:\n  varde-vault: true\n---\nTake notes.\n")
        home.resolve("agents").createDirectories().resolve("noter.md").writeText("---\nname: noter\ncli: claude\nmodel: sonnet\nskills: note-taker\n---\nNote.\n")
        try {
            val skills = page("/skills")
            assertContains(skills, """<a href="/skills/note-taker">note-taker</a></b> <span class="tag t-vault">Vault</span>""")
            assertContains(skills, """<td class="small"><a href="/agents/noter">noter</a></td>""")
            assertContains(skills, """<span class="muted">no Agent</span>""") // shipped-draft

            val agents = page("/agents")
            assertContains(agents, """<a href="/agents/noter">noter</a></b> <span class="tag t-vault">Vault</span>""")
            assertContains(agents, """<td class="small">claude · sonnet</td><td class="small"><a href="/skills/note-taker">note-taker</a></td>""")
            assertContains(agents, """<td class="small">claude · default</td>""") // shipped-writer names no model

            val runBy = page("/skills/note-taker")
            assertContains(runBy, """<a href="/agents/noter">noter</a>""")
            assertFalse("No Agent runs this Skill yet" in runBy)
        } finally {
            skill.toFile().deleteRecursively()
            home.resolve("agents/noter.md").toFile().delete()
        }
    }

    /** What the schema editor shows as you type [schema]: the errors the page's check returns. */
    private fun check(schema: String): String {
        browser.get("/skills/new")
        return browser.post("/skills/schema", mapOf("schema" to schema)).body
    }

    @Test
    fun `an invalid result schema is flagged as you type and can't be saved`() {
        assertEquals("[]", check("""{"type": "object"}"""))
        assertEquals("[]", check(""))
        assertContains(check("""{"type": "object",}"""), "not JSON")
        assertContains(check("""["type"]"""), "a JSON object")
        assertContains(check("""{"type": "strin"}"""), "/type")
        assertContains(check("""{"required": "text"}"""), "/required")

        home.resolve("skills/broken").toFile().deleteRecursively()
        val page = save(skillMd("broken"), """{"type": "strin"}""")
        assertEquals("/skills/new", page.path)
        assertContains(page.body, "result.schema.json is invalid")
        assertContains(page.body, "strin") // the form keeps what was typed
        assertFalse(home.resolve("skills/broken").exists())
    }

    @Test
    fun `the editors' scripts are all served`() {
        val page = browser.get("/skills/new").body
        val scripts = Regex(""""(/vendor/codemirror/[^"]+)"""").findAll(page).map { it.groupValues[1] }.toList() + "/skill-editor.js" + "/markdown.js"
        assertEquals(28, scripts.size)
        for (script in scripts) assertEquals(200, browser.get(script).status, script)
    }

    @Test
    fun `a Skill needs a name and a description, and a name no other Skill has`() {
        assertContains(save("---\nname: nameless\n---\nNo description.\n").body, "needs a name and a description")
        assertContains(save("No frontmatter.\n").body, "needs a name and a description")
        assertContains(save(skillMd("Not A Name")).body, "A Skill's name is up to 64 lowercase letters, digits and hyphens.")
        assertContains(save(skillMd("schema")).body, "The Skills page keeps the name schema for itself.")
        assertContains(save(skillMd("shipped-draft")).body, "A Skill named shipped-draft already exists")
    }

    @Test
    fun `a user Skill named like a shipped one shows greyed out`() {
        val yours = home.resolve("skills/mine/shipped-draft").createDirectories()
        try {
            yours.resolve("SKILL.md").writeText(skillMd("shipped-draft"))
            val list = page("/skills")
            assertContains(list, """<b><a href="/skills/shipped-draft">shipped-draft</a></b>""")
            assertContains(list, """<td class="small">Stands in for the Skills Ants ships.</td><td class="muted small">shipped with Ants</td>""")
            assertContains(list, """<tr class="hidden"><td><b>shipped-draft</b></td><td></td><td class="small">Summarises a text.</td><td class="muted small">$yours, hidden by the shipped Skill</td></tr>""")
            assertContains(page("/skills/shipped-draft"), "Stands in for the Skills Ants ships.")
        } finally {
            yours.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a shipped Skill is read-only, and Copy makes an editable one in ants skills`() {
        val copy = home.resolve("skills/shipped-draft-copy")
        copy.toFile().deleteRecursively()
        val shipped = page("/skills/shipped-draft")
        assertContains(shipped, """<textarea name="skill" rows="20" data-markdown readonly""")
        assertFalse("""<button class="primary" form="skill">Save</button>""" in shipped)
        assertContains(shipped, "No Agent runs this Skill yet, so no Task can use it.") // shipped-writer lists no Skills
        assertContains(shipped, "<code>\${&lt;task&gt;.output.text}</code>") // a Result field later Tasks read, under the Task's id
        assertEquals(403, save(skillMd("shipped-draft"), name = "shipped-draft").status)

        val page = browser.post("/skills/shipped-draft/copy", emptyMap())
        assertEquals("/skills/shipped-draft-copy", page.path)
        assertContains(page.body, """<button class="primary" form="skill">Save</button>""")
        assertEquals("---\nname: shipped-draft-copy\ndescription: Stands in for the Skills Ants ships.\n---\nDraft the text.\n", copy.resolve("SKILL.md").readText())
        assertEquals(home.resolve("shipped/skills/shipped-draft/result.schema.json").readText(), copy.resolve("result.schema.json").readText())
    }

    @Test
    fun `a Skill from an extra folder is read-only`() {
        val extra = Files.createTempDirectory("extra").resolve("outside").createDirectories()
        extra.resolve("SKILL.md").writeText(skillMd("outside"))
        val settings = home.resolve("settings.yaml")
        val before = settings.readText()
        try {
            settings.writeText("skill-folders: [${extra.parent}]\n")
            assertContains(page("/skills"), """<td class="muted small">$extra</td>""")
            assertContains(page("/skills/outside"), """action="/skills/outside/copy"""")
            assertEquals(403, save(skillMd("outside", "Changed."), name = "outside").status)
            assertEquals(skillMd("outside"), extra.resolve("SKILL.md").readText())
        } finally {
            settings.writeText(before)
        }
    }

    private fun import(text: String): String {
        browser.get("/skills")
        return browser.upload("/skills/import", "file", "SKILL.md", text.toByteArray()).body.replace("&#34;", "\"")
    }

    @Test
    fun `import fills the create form with one SKILL md, or refuses it with a reason`() {
        home.resolve("skills/imported").toFile().deleteRecursively()
        val form = import(skillMd("imported"))
        assertContains(form, """<form id="skill" method="post" action="/skills/new" class="skill">""")
        assertContains(form, ">${skillMd("imported")}</textarea>")
        assertFalse(home.resolve("skills/imported").exists()) // only Save writes

        assertContains(import("---\nname: imported\n---\nNo description.\n"), "A Skill needs a name and a description")
        assertContains(import("---\nname: [imported\ndescription: Broken.\n---\n"), "The frontmatter isn't valid YAML")
    }

    @Test
    fun `an edit on the page reaches the next launch`() {
        val claude = FakeClaude()
        val settings = home.resolve("settings.yaml")
        val before = settings.readText()
        try {
            settings.writeText("claude:\n  command: [${claude.command}]\n$vault")
            home.resolve("agents").createDirectories().resolve("writer.md").writeText("---\nskills: draft\n---\nWrite plainly.\n")
            home.resolve("skills/draft").toFile().deleteRecursively()
            claude.session("draft", """{"type":"result","subtype":"success","structured_output":{"outcome":"done","summary":"Drafted.","output":{}}}""")
            fun run(): Any? {
                val run = browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path
                eventually { assertContains(browser.get(run).body, """id="state">Completed<""") }
                val argv = claude.launches("draft").last().argv
                return (JsonMapper.shared().readValue(argv[argv.indexOf("--json-schema") + 1], Map::class.java)["properties"] as Map<*, *>)["output"]
            }

            save(skillMd("draft"), """{"type": "object"}""")
            browser.import("agent-task.bpmn") // once the Skill exists
            assertEquals(mapOf("type" to "object"), run())
            save(skillMd("draft"), """{"type": "object", "required": []}""", name = "draft")
            assertEquals(mapOf("type" to "object", "required" to emptyList<Any>()), run())
        } finally {
            settings.writeText(before)
        }
    }

    @Test
    fun `New Skill from the modeler opens the create form in its modal, and Create and use adds it to the Task's Agent`() {
        val agent = home.resolve("agents").createDirectories().resolve("picker.md")
        agent.writeText("---\nname: picker\ndescription: Picks.\ncli: claude\nskills: tdd\n---\nPick.\n")
        home.resolve("skills/chosen").toFile().deleteRecursively()
        val form = browser.get("/skills/new?use=picker").body
        assertContains(form, """<button class="primary" form="skill">Create and use</button>""")
        assertFalse("""<nav class="side">""" in form) // the modeler's own page is behind the modal

        val refused = browser.post("/skills/new", mapOf("skill" to "no frontmatter", "schema" to "", "use" to "picker"))
        assertContains(refused.body, """<button class="primary" form="skill">Create and use</button>""") // a refusal stays in the modal
        assertEquals("/skills/chosen", browser.post("/skills/new", mapOf("skill" to skillMd("chosen"), "schema" to "", "use" to "picker")).path)
        assertEquals(listOf("tdd", "chosen"), Agent(agent).skills)

        // A read-only Agent is left as it is: the Task's Checks say it doesn't list the Skill.
        home.resolve("skills/also-chosen").toFile().deleteRecursively()
        val shipped = Agent(home.resolve("shipped/agents/shipped-writer.md")).skills
        assertEquals("/skills/also-chosen", browser.post("/skills/new", mapOf("skill" to skillMd("also-chosen"), "schema" to "", "use" to "shipped-writer")).path)
        assertEquals(shipped, Agent(home.resolve("shipped/agents/shipped-writer.md")).skills)
    }
}
