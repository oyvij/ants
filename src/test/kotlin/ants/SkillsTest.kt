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
        val scripts = Regex(""""(/vendor/codemirror/[^"]+)"""").findAll(page).map { it.groupValues[1] }.toList() + "/skill-editor.js"
        assertEquals(19, scripts.size)
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
            assertContains(list, """<li><a href="/skills/shipped-draft">shipped-draft</a> Stands in for the Skills Ants ships. <small>shipped</small></li>""")
            assertContains(list, """<li style="color: GrayText">shipped-draft Summarises a text. <small>$yours, hidden by the shipped Skill</small></li>""")
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
        assertContains(shipped, """<textarea name="skill" rows="20" cols="80" readonly>""")
        assertFalse("<button>Save</button>" in shipped)
        assertEquals(403, save(skillMd("shipped-draft"), name = "shipped-draft").status)

        val page = browser.post("/skills/shipped-draft/copy", emptyMap())
        assertEquals("/skills/shipped-draft-copy", page.path)
        assertContains(page.body, "<button>Save</button>")
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
            assertContains(page("/skills"), "<small>$extra</small>")
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
        assertContains(form, """<form method="post" action="/skills/new" class="skill">""")
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
            settings.writeText("claude:\n  command: [${claude.command}]\n")
            home.resolve("agents").createDirectories().resolve("writer.md").writeText("---\nskills: draft\n---\nWrite plainly.\n")
            home.resolve("skills/draft").toFile().deleteRecursively()
            claude.session("draft", """{"type":"result","subtype":"success","structured_output":{"outcome":"done","summary":"Drafted.","output":{}}}""")
            fun run(): Any? {
                val run = browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path
                eventually { assertContains(browser.get(run).body, "State: Completed") }
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
}
