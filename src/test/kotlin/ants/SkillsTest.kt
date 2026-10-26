package ants

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkillsTest : AntsTest() {
    @Autowired
    lateinit var catalogue: Catalogue

    private val browser by lazy { browser().also { it.login() } }

    /** Its own settings, so a hide-shipped left by an earlier test (SubagentTest's) doesn't hide the shipped Skills. */
    @BeforeEach
    fun ownSettings() = settings(null)

    /** A `SKILL.md` named [name]. */
    private fun skillMd(name: String, body: String = "Write the summary.") = "---\nname: $name\ndescription: Summarises a text.\n---\n$body\n"

    /** Saves the create form, or the edit form of the Skill [name], with this `SKILL.md`. */
    private fun save(skill: String, name: String? = null): Browser.Page {
        val path = if (name == null) "/skills/new" else "/skills/$name"
        browser.get(path)
        return browser.post(path, mapOf("skill" to skill))
    }

    private fun page(path: String) = browser.get(path).body.replace("&#34;", "\"")

    @Test
    fun `creating and editing a Skill writes its folder in ants skills`() {
        val folder = home.resolve("skills/summarise")
        folder.toFile().deleteRecursively()
        assertEquals("/skills/summarise", save(skillMd("summarise")).path)
        assertEquals(skillMd("summarise"), folder.resolve("SKILL.md").readText())
        assertContains(page("/skills"), """<a href="/skills/summarise">summarise</a>""")

        save(skillMd("summarise", "Write it shorter."), name = "summarise")
        assertEquals(skillMd("summarise", "Write it shorter."), folder.resolve("SKILL.md").readText())
        assertContains(page("/skills/summarise"), "Write it shorter.")
    }

    @Test
    fun `the lists show each Agent's CLI, model and Skills and each Skill's Run by`() {
        val skill = home.resolve("skills/note-taker").createDirectories()
        skill.resolve("SKILL.md").writeText("---\nname: note-taker\ndescription: Takes notes.\n---\nTake notes.\n")
        home.resolve("agents").createDirectories().resolve("noter.md").writeText("---\nname: noter\ncli: claude\nmodel: sonnet\nskills: note-taker\n---\nNote.\n")
        try {
            val skills = page("/skills")
            assertContains(skills, """<a href="/skills/note-taker">note-taker</a></b></td>""")
            assertContains(skills, """<td class="small"><a href="/agents/noter">noter</a></td>""")
            assertContains(skills, """<span class="muted">no Agent</span>""") // shipped-draft

            val agents = page("/agents")
            assertContains(agents, """<a href="/agents/noter">noter</a></b></td>""")
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

    @Test
    fun `the editors' scripts are all served`() {
        val page = browser.get("/skills/new").body
        val scripts = Regex(""""(/vendor/codemirror/[^"]+)"""").findAll(page).map { it.groupValues[1] }.toList() + "/markdown.js"
        assertEquals(44, scripts.size)
        for (script in scripts) assertEquals(200, browser.get(script).status, script)
    }

    @Test
    fun `a Skill needs a name and a description, and a name no other Skill has`() {
        assertContains(save("---\nname: nameless\n---\nNo description.\n").body, "needs a name and a description")
        assertContains(save("No frontmatter.\n").body, "needs a name and a description")
        assertContains(save(skillMd("Not A Name")).body, "A Skill's name is up to 64 lowercase letters, digits and hyphens.")
        assertContains(save(skillMd("new")).body, "The Skills page keeps the name new for itself.")
        assertContains(save(skillMd("shipped-draft")).body, "A Skill named shipped-draft already exists")
    }

    @Test
    fun `the Skill page edits only SKILL md, and says the job, inputs and Result belong on the Task`() {
        val page = page("/skills/new")
        assertContains(page, "What a Task must do, its inputs and its Result belong on the Task.")
        assertFalse("result.schema.json" in page)
        assertFalse("ants-" in page)
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
    fun `a shipped Skill is read-only, and Copy makes an editable Draft of it, which Publish puts in ants skills`() {
        val copy = home.resolve("skills/shipped-draft-copy")
        copy.toFile().deleteRecursively()
        val shipped = page("/skills/shipped-draft")
        assertContains(shipped, """name="skill" rows="20" placeholder="e.g. ---&#10;name: release-notes&#10;description: Writes the release notes for a version.&#10;---&#10;Read the commits since the last tag and sum them up for users." data-markdown readonly""")
        assertFalse("""<button class="primary" form="skill">Publish</button>""" in shipped)
        assertContains(shipped, "No Agent runs this Skill yet, so no Task can use it.") // shipped-writer lists no Skills
        assertEquals(403, save(skillMd("shipped-draft"), name = "shipped-draft").status)

        val page = browser.post("/skills/shipped-draft/copy", emptyMap())
        assertEquals("/skills/shipped-draft-copy", page.path)
        assertContains(page.body, """<button class="primary" form="skill">Publish</button>""")
        val text = "---\nname: shipped-draft-copy\ndescription: Stands in for the Skills Ants ships.\n---\nDraft the text.\n"
        assertContains(page.body, text)
        assertFalse(copy.exists())
        assertEquals("/skills/shipped-draft-copy", browser.post("/skills/shipped-draft-copy", mapOf("skill" to text)).path)
        assertEquals(text, copy.resolve("SKILL.md").readText())
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

    private fun idOf(folder: Path) = Regex("^id: (k-[a-z0-9]{6})\n$").find(folder.resolve("ants.yaml").readText())?.groupValues?.get(1)

    private fun assertNoPageShows(id: String, vararg paths: String) {
        for (path in paths) assertFalse(id in page(path), "$path shows the id $id")
    }

    @Test
    fun `creating, importing or copying a Skill gives it a new id in its ants yaml, which no page shows and the Catalogue finds it by`() {
        val skills = home.resolve("skills")
        save(skillMd("summary"))
        val created = assertNotNull(idOf(skills.resolve("summary")))
        browser.get("/skills/summary")
        browser.post("/skills/summary", mapOf("skill" to skillMd("summing-up")))
        assertEquals(created, idOf(skills.resolve("summing-up")))
        assertEquals("summing-up", catalogue.skillWithId(created).name)
        assertNoPageShows(created, "/skills", "/skills/summing-up")

        import("theirs/SKILL.md" to skillMd("theirs"), "theirs/ants.yaml" to "id: k-theirs\n")
        val imported = assertNotNull(idOf(skills.resolve("theirs")))
        assertTrue(imported != created)

        skills.resolve("shipped-draft-copy").toFile().deleteRecursively()
        val copy = browser.post("/skills/shipped-draft/copy", emptyMap()).path
        browser.post(copy, mapOf("skill" to "---\nname: shipped-draft-copy\ndescription: Stands in for the Skills Ants ships.\n---\nDraft the text.\n"))
        val copied = assertNotNull(idOf(skills.resolve("shipped-draft-copy")))
        assertTrue(copied !in setOf(created, imported, "k-shipped-draft"))
        assertEquals("shipped-draft", catalogue.skillWithId("k-shipped-draft").name)
    }

    @Test
    fun `a Skill from an extra folder gets its id in skill-ids yaml, and its folder is untouched`() {
        val extra = Files.createTempDirectory("extra").resolve("outside").createDirectories()
        extra.resolve("SKILL.md").writeText(skillMd("outside"))
        val settings = home.resolve("settings.yaml")
        val before = settings.readText()
        try {
            settings.writeText("skill-folders: [${extra.parent}]\n")
            page("/skills")
            val ids = Yaml().load<Map<String, String>>(home.resolve("skill-ids.yaml").readText())
            val id = ids.entries.single { it.value == extra.toString() }.key
            assertTrue(Regex("k-[a-z0-9]{6}").matches(id), id)
            assertEquals("outside", catalogue.skillWithId(id).name)
            page("/skills")
            assertEquals(ids, Yaml().load<Map<String, String>>(home.resolve("skill-ids.yaml").readText()))
            assertNoPageShows(id, "/skills", "/skills/outside")
        } finally {
            settings.writeText(before)
        }
        assertEquals(listOf("SKILL.md"), extra.toFile().list()!!.toList())
        assertEquals(skillMd("outside"), extra.resolve("SKILL.md").readText())
    }

    private fun import(vararg files: Pair<String, String>): Browser.Page {
        browser.get("/skills")
        return browser.upload("/skills/import", "file", files.map { (name, text) -> name to text.toByteArray() })
    }

    @Test
    fun `import adds one SKILL md as a Skill and opens it, or refuses it with a reason`() {
        home.resolve("skills/imported").toFile().deleteRecursively()
        assertEquals("/skills/imported", import("SKILL.md" to skillMd("imported")).path)
        assertEquals(skillMd("imported"), home.resolve("skills/imported/SKILL.md").readText())

        assertContains(import("SKILL.md" to skillMd("imported")).body, "A Skill named imported already exists")
        assertContains(import("SKILL.md" to "---\nname: nameless\n---\nNo description.\n").body, "A Skill needs a name and a description")
        assertContains(import("SKILL.md" to "---\nname: [broken\ndescription: Broken.\n---\n").body, "The frontmatter isn't valid YAML")
        assertContains(import("notes.md" to "Just notes.").body, "a Skill is a SKILL.md, or a folder holding one")
    }

    @Test
    fun `import of a Skill's folder keeps the files beside its SKILL md`() {
        home.resolve("skills/from-the-web").toFile().deleteRecursively()
        val page = import(
            "from-the-web/SKILL.md" to skillMd("from-the-web", "Read reference.md."),
            "from-the-web/reference.md" to "The reference.",
            "from-the-web/scripts/run.sh" to "echo hi\n",
        )
        assertEquals("/skills/from-the-web", page.path)
        val folder = home.resolve("skills/from-the-web")
        assertEquals("The reference.", folder.resolve("reference.md").readText())
        assertEquals("echo hi\n", folder.resolve("scripts/run.sh").readText())
        assertContains(import("x/SKILL.md" to skillMd("escaping"), "x/../../evil.md" to "no").body, "lies outside its folder")
        assertFalse(home.resolve("skills/escaping").exists())
    }

    @Test
    fun `New Skill in the modeler's modal makes a Draft-only Skill and opens its page in the modal, for the Task's Agent`() {
        val made = browser.post("/skills/new/draft", mapOf("use" to "writer"))
        val name = made.path.removePrefix("/skills/")
        assertTrue(Regex("new-skill(-\\d+)?").matches(name), name)
        assertEquals("use=writer", made.query)
        assertContains(made.body, """<input type="hidden" name="use" value="writer">""")
        assertContains(made.body, """class="pill draft-mark p-draft">Draft<""")
        assertFalse("""<nav class="side">""" in made.body)
        browser.post(made.path + "/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `renaming a Skill saves at once with no confirm, keeps its id, and the Agent that lists it shows the new name`() {
        listOf("listed", "relisted").forEach { home.resolve("skills/$it").toFile().deleteRecursively() }
        save(skillMd("listed"))
        val id = catalogue.skill("listed").id!!
        val agent = home.resolve("agents").createDirectories().resolve("lister.md")
        agent.writeText("---\nname: lister\ncli: claude\nskills: $id\n---\nList.\n")

        assertEquals("/skills/relisted", save(skillMd("relisted"), name = "listed").path)
        assertEquals("relisted", catalogue.skillWithId(id).name)
        assertEquals(listOf(id), Agent(agent).skills)
        assertContains(page("/agents/lister"), """<input type="hidden" name="skills" value="relisted">""")
        assertContains(page("/skills/relisted"), """<a href="/agents/lister">lister</a>""")
    }

    @Test
    fun `Edit from the modeler opens the Skill's page in its modal, and Publish lands on it published`() {
        listOf("tidy", "tidier").forEach { home.resolve("skills/$it").toFile().deleteRecursively() }
        save(skillMd("tidy"))
        val form = browser.get("/skills/tidy?use=").body
        assertContains(form, """<button class="primary" form="skill">Publish</button>""")
        assertContains(form, """<input type="hidden" name="use" value="">""")
        assertFalse("""<nav class="side">""" in form)

        val refused = browser.post("/skills/tidy", mapOf("skill" to "no frontmatter", "use" to ""))
        assertContains(refused.body, """<button class="primary" form="skill">Publish</button>""")
        assertFalse("""<nav class="side">""" in refused.body)

        val saved = browser.post("/skills/tidy", mapOf("skill" to skillMd("tidy", "Tidy up."), "use" to ""))
        assertEquals("/skills/tidy" to "saved", saved.path to saved.query)
        assertEquals(skillMd("tidy", "Tidy up."), home.resolve("skills/tidy/SKILL.md").readText())

        val renamed = browser.post("/skills/tidy", mapOf("skill" to skillMd("tidier"), "use" to ""))
        assertEquals("/skills/tidier" to "saved", renamed.path to renamed.query)

        val page = save(skillMd("tidier", "Tidy it on its page."), name = "tidier")
        assertEquals("/skills/tidier" to null, page.path to page.query)
    }

    /** The markers a page shows: Draft, Published, or Draft · changes not published. */
    private fun markers(page: String) = Regex("""class="pill draft-mark[^"]*">([^<]+)<""").findAll(page).map { it.groupValues[1] }.toList()

    /** A Skill of the user's own, published at once as the modeler's modal does, with no Draft. */
    private fun published(name: String): Path {
        val folder = home.resolve("skills/$name")
        folder.toFile().deleteRecursively()
        assertEquals("/skills/$name", save(skillMd(name, "Published.")).path)
        return folder
    }

    @Test
    fun `an edit is autosaved to the Draft, which the page reopens after a new login and a restart, and the published Skill stays as it was`() {
        val folder = published("drafted")
        val page = page("/skills/drafted")
        assertEquals(listOf("Published"), markers(page))
        assertEquals("0", draftVersion(page))

        val saved = browser.autosave("/skills/drafted", mapOf("skill" to skillMd("drafted", "Half done.")), draftVersion(page))
        assertEquals(200, saved.status, saved.body)
        val answer = answer(saved)
        assertTrue(Regex("""Draft saved \d\d:\d\d""").matches(answer["saved"].toString()), answer.toString())
        assertEquals("Draft · changes not published", answer["label"])
        assertEquals("/skills/drafted", answer["path"])
        assertEquals(skillMd("drafted", "Published."), folder.resolve("SKILL.md").readText())

        val again = browser().also { it.login() }.get("/skills/drafted").body
        assertContains(again, "Half done.")
        assertEquals(listOf("Draft · changes not published"), markers(again))
        assertEquals(answer["version"].toString(), draftVersion(again))
        assertContains(Drafts(home).get(DraftKind.SKILL, catalogue.skill("drafted").id!!)!!.content, "Half done.") // as a restarted Ants reads it
        assertContains(page("/skills"), """<a href="/skills/drafted">drafted</a></b><span class="pill draft-mark p-changed">Draft · changes not published</span>""")
        browser.post("/skills/drafted/discard", mapOf("confirm" to "on"))
    }

    @Test
    fun `a save from a tab with an older version gets a conflict, and one with the newest is kept`() {
        published("tabbed")
        val loaded = draftVersion(page("/skills/tabbed"))
        val first = answer(browser.autosave("/skills/tabbed", mapOf("skill" to skillMd("tabbed", "First tab.")), loaded))
        val second = browser.autosave("/skills/tabbed", mapOf("skill" to skillMd("tabbed", "Second tab.")), loaded)
        assertEquals(409, second.status)
        assertEquals("This Skill's Draft was changed in another tab or window.", answer(second)["conflict"])
        assertContains(page("/skills/tabbed"), "First tab.")

        assertEquals(200, browser.autosave("/skills/tabbed", mapOf("skill" to skillMd("tabbed", "First tab, again.")), first["version"].toString()).status)
        assertContains(page("/skills/tabbed"), "First tab, again.")
        browser.post("/skills/tabbed/discard", mapOf("confirm" to "on"))
    }

    @Test
    fun `Publish publishes the page and removes the Draft, and a refused Publish keeps the Draft and says why`() {
        val folder = published("publisher")
        published("publisher-taken")
        browser.autosave("/skills/publisher", mapOf("skill" to skillMd("publisher", "Changed.")), draftVersion(page("/skills/publisher")))

        val refused = browser.post("/skills/publisher", mapOf("skill" to skillMd("publisher-taken", "Renamed.")))
        assertContains(refused.body, "A Skill named publisher-taken already exists")
        assertContains(page("/skills/publisher"), "Renamed.") // the Draft is the page as Publish was pressed
        assertEquals(skillMd("publisher", "Published."), folder.resolve("SKILL.md").readText())

        assertEquals("/skills/publisher", browser.post("/skills/publisher", mapOf("skill" to skillMd("publisher", "Changed."))).path)
        assertEquals(skillMd("publisher", "Changed."), folder.resolve("SKILL.md").readText())
        val page = page("/skills/publisher")
        assertEquals(listOf("Published"), markers(page))
        assertEquals("0", draftVersion(page))
        assertFalse("Draft saved" in page)
    }

    /** Deletes the Draft-only Skill at [page], as its Delete does once confirmed, so the next test's New or Copy gets the same name. */
    private fun deleteDraft(page: String) = assertEquals("/skills", browser.post("$page/delete", mapOf("confirm" to "on")).path)

    @Test
    fun `New and Copy make a Draft-only Skill with a placeholder name, used by nothing, which Publish puts to use`() {
        val first = browser.post("/skills/new/draft", emptyMap())
        val name = first.path.removePrefix("/skills/")
        assertTrue(Regex("new-skill(-\\d+)?").matches(name), name)
        assertEquals(listOf("Draft"), markers(first.body))
        assertContains(first.body, "name: $name\n")
        assertFalse(home.resolve("skills/$name").exists())
        assertTrue(catalogue.skills().none { it.name == name })
        assertContains(page("/skills"), """<a href="/skills/$name">$name</a></b><span class="pill draft-mark p-draft">Draft</span>""")
        val second = browser.post("/skills/new/draft", emptyMap()).path.removePrefix("/skills/")
        assertTrue(second != name && second.startsWith("new-skill-"), second)
        deleteDraft("/skills/$second")

        home.resolve("skills/drafted-first").toFile().deleteRecursively()
        val renamed = answer(browser.autosave("/skills/$name", mapOf("skill" to skillMd("drafted-first")), draftVersion(first.body)))
        assertEquals("/skills/drafted-first", renamed["path"]) // a Draft-only Skill's page follows its name
        assertEquals("Draft", renamed["label"])
        assertEquals(listOf("Draft"), markers(page("/skills/drafted-first")))
        assertEquals("/skills/drafted-first", browser.post("/skills/drafted-first", mapOf("skill" to skillMd("drafted-first"))).path)
        assertEquals(skillMd("drafted-first"), home.resolve("skills/drafted-first/SKILL.md").readText())
        assertEquals(idOf(home.resolve("skills/drafted-first")), catalogue.skill("drafted-first").id)
        assertEquals(listOf("Published"), markers(page("/skills/drafted-first")))
    }

    @Test
    fun `Copy keeps the files beside SKILL md with the Draft, and Publish puts them in the copy's folder`() {
        val source = published("beside")
        source.resolve("reference.md").writeText("The reference.")
        val copy = browser.post("/skills/beside/copy", emptyMap())
        assertEquals("/skills/beside-copy", copy.path)
        assertEquals(listOf("Draft"), markers(copy.body))
        assertContains(copy.body, "name: beside-copy\n")
        val folder = home.resolve("skills/beside-copy")
        assertFalse(folder.exists())

        assertEquals("/skills/beside-copy", browser.post("/skills/beside-copy", mapOf("skill" to skillMd("beside-copy"))).path)
        assertEquals("The reference.", folder.resolve("reference.md").readText())
        assertTrue(assertNotNull(idOf(folder)) != idOf(source))
        browser.post("/skills/beside-copy/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `Discard Draft confirms and reopens the published Skill, and Delete on a Draft-only Skill confirms and leaves the list`() {
        published("discarder")
        browser.autosave("/skills/discarder", mapOf("skill" to skillMd("discarder", "Unwanted.")), draftVersion(page("/skills/discarder")))
        assertContains(page("/skills/discarder"), """<form method="post" action="/skills/discarder/discard"""")
        assertContains(browser.post("/skills/discarder/discard", emptyMap()).body, "Discard the Draft of discarder?")
        assertContains(page("/skills/discarder"), "Unwanted.")
        val discarded = browser.post("/skills/discarder/discard", mapOf("confirm" to "on"))
        assertEquals("/skills/discarder", discarded.path)
        assertContains(discarded.body, "Published.")
        assertEquals(listOf("Published"), markers(discarded.body))

        val draft = browser.post("/skills/new/draft", emptyMap()).path
        assertFalse("/discard" in page(draft))
        assertContains(browser.post("$draft/delete", emptyMap()).body, "Delete the Draft ${draft.removePrefix("/skills/")}?")
        assertEquals("/skills", browser.post("$draft/delete", mapOf("confirm" to "on")).path)
        assertEquals(404, browser.get(draft).status)
        assertFalse("href=\"$draft\"" in page("/skills"))
    }

    @Test
    fun `shipped and extra-folder Skills have no Draft and no autosave`() {
        val shipped = page("/skills/shipped-draft")
        assertFalse("data-autosave" in shipped)
        assertTrue(markers(shipped).isEmpty())
        assertEquals(403, browser.autosave("/skills/shipped-draft", mapOf("skill" to skillMd("shipped-draft")), "0").status)

        val extra = Files.createTempDirectory("extra").resolve("outside-draft").createDirectories()
        extra.resolve("SKILL.md").writeText(skillMd("outside-draft"))
        val settings = home.resolve("settings.yaml")
        val before = settings.readText()
        try {
            settings.writeText("skill-folders: [${extra.parent}]\n")
            assertFalse("data-autosave" in page("/skills/outside-draft"))
            assertEquals(403, browser.autosave("/skills/outside-draft", mapOf("skill" to skillMd("outside-draft", "Changed.")), "0").status)
            assertEquals(skillMd("outside-draft"), extra.resolve("SKILL.md").readText())
        } finally {
            settings.writeText(before)
        }
    }
}
