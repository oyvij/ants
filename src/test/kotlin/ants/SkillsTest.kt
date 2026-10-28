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

    /** Publishes a new Skill, or the Skill [name], with this `SKILL.md`. */
    private fun save(skill: String, name: String? = null): Browser.Page {
        if (name == null) return browser.publishNew("/skills", mapOf("skill" to skill))
        browser.get("/skills/$name")
        return browser.post("/skills/$name", mapOf("skill" to skill))
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
        val page = browser.get(browser.newDraft("/skills")).body
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
        val page = page(browser.newDraft("/skills"))
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

    @Test
    fun `a Skill from an extra folder lists and reads its references`() {
        val extra = Files.createTempDirectory("extra").resolve("outside-referrer").createDirectories()
        extra.resolve("SKILL.md").writeText(skillMd("outside-referrer"))
        extra.resolve("guide.md").writeText("The guide.")
        val settings = home.resolve("settings.yaml")
        val before = settings.readText()
        try {
            settings.writeText("skill-folders: [${extra.parent}]\n")
            assertEquals(listOf("guide.md"), references(page("/skills/outside-referrer")))
            assertContains(page("/skills/outside-referrer/references/guide.md"), "The guide.")
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

    private fun references(page: String) = Regex("""data-copy="([^"]+)"""").findAll(page).map { it.groupValues[1] }.toList()

    @Test
    fun `the References card lists the Markdown files beside SKILL md by their relative path, and not a nested Skill's`() {
        val folder = published("referrer")
        folder.resolve("reference.md").writeText("The reference.")
        folder.resolve("references").createDirectories().resolve("format.md").writeText("The format.")
        folder.resolve("LICENSE").writeText("MIT")
        folder.resolve("scripts").createDirectories().resolve("run.sh").writeText("echo hi\n")
        folder.resolve("nested").createDirectories().resolve("SKILL.md").writeText(skillMd("referrer-nested"))
        folder.resolve("nested/inner.md").writeText("The nested Skill's.")
        try {
            assertEquals(listOf("reference.md", "references/format.md"), references(page("/skills/referrer")))
            assertEquals(listOf("inner.md"), references(page("/skills/referrer-nested")))
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a Skill with no references says it has none`() {
        published("unreferenced")
        val page = page("/skills/unreferenced")
        assertEquals(emptyList(), references(page))
        assertContains(page, "No references: no Markdown file is beside its SKILL.md.")
        browser.post("/skills/unreferenced/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `shipped Skills list their references`() {
        assertEquals(listOf("DEEPENING.md", "DESIGN-IT-TWICE.md"), references(page("/skills/codebase-design")))
        assertEquals(listOf("mocking.md", "tests.md"), references(page("/skills/tdd")))
    }

    private fun unmentioned(page: String) = Regex("""<code>([^<]+)</code>(?:<span class="pill p-\w+">\w+</span>)?<span class="pill p-quiet" data-unmentioned>not mentioned in SKILL.md</span>""").findAll(page).map { it.groupValues[1] }.toList()

    private fun missing(page: String) = Regex("""<div class="row ref missing"><span class="grow"><code>([^<]+)</code><span class="pill p-waiting">missing</span></span><span class="muted small">([^<]+)</span>""").findAll(page).map { it.groupValues[1] }.toList()

    @Test
    fun `a link and a code span mention a reference, an unmentioned one is marked, and a mention with no file is listed as missing`() {
        val folder = home.resolve("skills/mentioner")
        folder.toFile().deleteRecursively()
        save(skillMd("mentioner", "See [the guide](guide.md), `references/format.md` and [the API](./references/api.md#calls). Fix `typo.md` and [gone](references/gone.md)."))
        for (path in listOf("guide.md", "references/format.md", "references/api.md", "unread.md", "references/unread.md")) folder.resolve(path).also { it.parent.createDirectories() }.writeText("A reference.")
        try {
            val page = page("/skills/mentioner")
            assertEquals(listOf("references/unread.md", "unread.md"), unmentioned(page))
            assertEquals(listOf("references/gone.md", "typo.md"), missing(page))
            assertContains(page, "No such reference here; it may be a file in the repo being worked on, such as GLOSSARY.md.")
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a URL, an absolute path and a non-Markdown path are no mention`() {
        val folder = home.resolve("skills/linker")
        folder.toFile().deleteRecursively()
        save(skillMd("linker", "See [docs](https://example.com/guide.md), [mail](mailto:a@b.md), `file:guide.md`, [root](/guide.md), `/etc/guide.md`, `~/guide.md`, `C:/guide.md`, [up](../guide.md), `run.sh`, [schema](schema.json) and `guide.txt`."))
        folder.resolve("guide.md").writeText("A reference.")
        try {
            val page = page("/skills/linker")
            assertEquals(listOf("guide.md"), unmentioned(page))
            assertEquals(emptyList(), missing(page))
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `shipped tdd and codebase-design mention each of their references, and tdd's GLOSSARY md is a missing mention`() {
        val tdd = page("/skills/tdd")
        assertEquals(emptyList(), unmentioned(tdd))
        assertEquals(listOf("GLOSSARY.md"), missing(tdd))
        val design = page("/skills/codebase-design")
        assertEquals(emptyList(), unmentioned(design))
        assertEquals(emptyList(), missing(design))
    }

    @Test
    fun `a Skill publishes with unmentioned references and missing mentions`() {
        val folder = referring("unmentioning")
        val published = browser.post("/skills/unmentioning", mapOf("skill" to skillMd("unmentioning", "Read `absent.md`.")))
        assertEquals("/skills/unmentioning", published.path)
        assertEquals(skillMd("unmentioning", "Read `absent.md`."), folder.resolve("SKILL.md").readText())
        val page = page("/skills/unmentioning")
        assertEquals(listOf("guide.md", "references/format.md"), unmentioned(page))
        assertEquals(listOf("absent.md"), missing(page))
        browser.post("/skills/unmentioning/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `a reference reads over HTTP, and a path outside the Skill or that isn't a reference is not found`() {
        val folder = published("readable")
        folder.resolve("references").createDirectories().resolve("format.md").writeText("# The format\n\nOne line each.\n")
        folder.resolve("LICENSE").writeText("MIT")
        folder.resolve("nested").createDirectories().resolve("SKILL.md").writeText(skillMd("readable-nested"))
        folder.resolve("nested/inner.md").writeText("The nested Skill's.")
        home.resolve("skills/outside.md").writeText("Not the Skill's.")
        try {
            val read = browser.get("/skills/readable/references/references/format.md")
            assertEquals(200, read.status)
            assertContains(read.body, "# The format\n\nOne line each.\n")
            for (path in listOf("LICENSE", "SKILL.md", "missing.md", "nested/inner.md", "nested/SKILL.md"))
                assertEquals(404, browser.get("/skills/readable/references/$path").status, path)
            for (path in listOf("../outside.md", "references/../../outside.md", "%2E%2E/outside.md", "references%2F..%2F..%2Foutside.md"))
                assertFalse("Not the Skill" in browser.get("/skills/readable/references/$path").body, path)
            assertContains(browser.get("/skills/tdd/references/tests.md").also { assertEquals(200, it.status) }.body, "data-markdown readonly")
        } finally {
            folder.toFile().deleteRecursively()
            home.resolve("skills/outside.md").deleteIfExists()
        }
    }

    private fun changed(page: String) = Regex("""<code>([^<]+)</code><span class="pill p-changed">changed</span>""").findAll(page).map { it.groupValues[1] }.toList()

    private fun referring(name: String): Path = published(name).also { folder ->
        folder.resolve("guide.md").writeText("Published guide.")
        folder.resolve("references").createDirectories().resolve("format.md").writeText("Published format.")
        folder.resolve("LICENSE").writeText("MIT")
        folder.resolve("scripts").createDirectories().resolve("run.sh").writeText("echo hi\n")
    }

    @Test
    fun `a saved reference is kept in the Draft, which the page and the reference show, marked, until Publish writes it and leaves other files alone`() {
        val folder = referring("referee")
        val saved = browser.post("/skills/referee/references/references/format.md", mapOf("text" to "Draft format.\r\n"))
        assertEquals(200 to "/skills/referee", saved.status to saved.path)
        assertEquals("Published format.", folder.resolve("references/format.md").readText())
        assertEquals(listOf("Draft · changes not published"), markers(saved.body))
        assertEquals(listOf("references/format.md"), changed(page("/skills/referee")))
        assertContains(page("/skills/referee"), "Draft format.\n")
        assertContains(page("/skills/referee/references/references/format.md"), "Draft format.\n")
        assertContains(page("/skills/referee/references/guide.md"), "Published guide.")

        assertEquals("/skills/referee", browser.post("/skills/referee", mapOf("skill" to skillMd("referee", "Published."))).path)
        assertEquals("Draft format.\n", folder.resolve("references/format.md").readText())
        assertEquals("Published guide.", folder.resolve("guide.md").readText())
        assertEquals("MIT", folder.resolve("LICENSE").readText())
        assertEquals("echo hi\n", folder.resolve("scripts/run.sh").readText())
        val page = page("/skills/referee")
        assertEquals(listOf("Published"), markers(page))
        assertEquals(emptyList(), changed(page))
        browser.post("/skills/referee/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `a reference saved beside a SKILL md Draft keeps that Draft, and Discard Draft drops both`() {
        val folder = referring("discarded-referee")
        browser.autosave("/skills/discarded-referee", mapOf("skill" to skillMd("discarded-referee", "Drafted.")), draftVersion(page("/skills/discarded-referee")))
        browser.post("/skills/discarded-referee/references/guide.md", mapOf("text" to "Draft guide."))
        assertContains(page("/skills/discarded-referee"), "Drafted.")
        assertEquals(listOf("guide.md"), changed(page("/skills/discarded-referee")))

        browser.post("/skills/discarded-referee/discard", mapOf("confirm" to "on"))
        val page = page("/skills/discarded-referee")
        assertEquals(listOf("Published"), markers(page))
        assertEquals(emptyList(), changed(page))
        assertContains(page("/skills/discarded-referee/references/guide.md"), "Published guide.")
        assertEquals("Published guide.", folder.resolve("guide.md").readText())
        browser.post("/skills/discarded-referee/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `Copy of a Skill whose Draft changed a reference takes the Draft's reference`() {
        referring("copied-referee")
        browser.post("/skills/copied-referee/references/guide.md", mapOf("text" to "Draft guide."))
        assertEquals("/skills/copied-referee-copy", browser.post("/skills/copied-referee/copy", emptyMap()).path)
        assertContains(page("/skills/copied-referee-copy/references/guide.md"), "Draft guide.")
        assertContains(page("/skills/copied-referee-copy/references/references/format.md"), "Published format.")
        browser.post("/skills/copied-referee-copy/delete", mapOf("confirm" to "on"))
        browser.post("/skills/copied-referee/discard", mapOf("confirm" to "on"))
        browser.post("/skills/copied-referee/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `the user's own Skill's reference is editable, and a Skill the user doesn't own refuses a save with the reason`() {
        referring("editable-referee")
        assertContains(page("/skills/editable-referee"), """action="/skills/editable-referee/references/guide.md"""")
        assertContains(page("/skills/editable-referee/references/guide.md"), """action="/skills/editable-referee/references/guide.md"""")
        browser.post("/skills/editable-referee/delete", mapOf("confirm" to "on"))

        val refused = browser.post("/skills/tdd/references/tests.md", mapOf("text" to "Overwritten."))
        assertEquals(403, refused.status)
        assertContains(refused.body, "The Skill tdd is read-only; Copy it to make your own.")
        assertFalse("Overwritten." in page("/skills/tdd/references/tests.md"))
        assertFalse("""action="/skills/tdd/references/""" in page("/skills/tdd"))
        assertFalse("""action="/skills/tdd/references/""" in page("/skills/tdd/references/tests.md"))

        val extra = Files.createTempDirectory("extra").resolve("outside-referee").createDirectories()
        extra.resolve("SKILL.md").writeText(skillMd("outside-referee"))
        extra.resolve("guide.md").writeText("The guide.")
        val settings = home.resolve("settings.yaml")
        val before = settings.readText()
        try {
            settings.writeText("skill-folders: [${extra.parent}]\n")
            assertEquals(403, browser.post("/skills/outside-referee/references/guide.md", mapOf("text" to "Overwritten.")).status)
            assertEquals("The guide.", extra.resolve("guide.md").readText())
        } finally {
            settings.writeText(before)
        }
    }

    @Test
    fun `a save to a path that isn't a reference is not found, and one outside the Skill is refused`() {
        val folder = referring("strict-referee")
        home.resolve("skills/outside.md").writeText("Not the Skill's.")
        try {
            for (path in listOf("LICENSE", "SKILL.md", "missing.md"))
                assertEquals(404, browser.post("/skills/strict-referee/references/$path", mapOf("text" to "Overwritten.")).status, path)
            for (path in listOf("../outside.md", "%2E%2E/outside.md", "references%2F..%2F..%2Foutside.md"))
                assertTrue(browser.post("/skills/strict-referee/references/$path", mapOf("text" to "Overwritten.")).status in 400..499, path)
            assertEquals("MIT", folder.resolve("LICENSE").readText())
            assertEquals("Not the Skill's.", home.resolve("skills/outside.md").readText())
            assertEquals(listOf("Published"), markers(page("/skills/strict-referee")))
        } finally {
            home.resolve("skills/outside.md").deleteIfExists()
            browser.post("/skills/strict-referee/delete", mapOf("confirm" to "on"))
        }
    }

    private fun marked(page: String) = Regex("""<code>([^<]+)</code><span class="pill p-(added|changed|deleted)">""").findAll(page).map { it.groupValues[1] to it.groupValues[2] }.toList()

    private fun newReference(skill: String, path: String) = browser.post("/skills/$skill/new-reference", mapOf("path" to path))

    private fun importReference(skill: String, filename: String, text: String): Browser.Page {
        browser.get("/skills/$skill")
        return browser.upload("/skills/$skill/import-reference", "file", filename, text.toByteArray())
    }

    @Test
    fun `New reference creates an empty file in the Draft and opens it, adding md to a name without an extension, in a subfolder too`() {
        val folder = referring("adder")
        val created = newReference("adder", "notes")
        assertEquals(Triple(200, "/skills/adder", "reference=notes.md"), Triple(created.status, created.path, created.query))
        assertContains(created.body.replace("&#34;", "\""), """action="/skills/adder/references/notes.md"""")
        val nested = newReference("adder", "references/api.md")
        assertEquals("/skills/adder" to "reference=references/api.md", nested.path to nested.query)
        assertFalse(folder.resolve("notes.md").exists())
        assertFalse(folder.resolve("references/api.md").exists())
        val page = page("/skills/adder")
        assertEquals(listOf("guide.md", "notes.md", "references/api.md", "references/format.md"), references(page))
        assertEquals(listOf("notes.md" to "added", "references/api.md" to "added"), marked(page))
        assertEquals(listOf("Draft · changes not published"), markers(page))

        browser.post("/skills/adder/references/notes.md", mapOf("text" to "The notes."))
        assertEquals("/skills/adder", browser.post("/skills/adder", mapOf("skill" to skillMd("adder", "Published."))).path)
        assertEquals("The notes.", folder.resolve("notes.md").readText())
        assertEquals("", folder.resolve("references/api.md").readText())
        assertEquals(emptyList(), marked(page("/skills/adder")))
        browser.post("/skills/adder/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `each name New reference or Import reference refuses gives its reason and writes nothing`() {
        val folder = referring("refuser")
        folder.resolve("nested").createDirectories().resolve("SKILL.md").writeText(skillMd("refuser-nested"))
        home.resolve("skills/outside.md").deleteIfExists()
        val refusals = mapOf(
            "" to "A reference needs a name.",
            "  " to "A reference needs a name.",
            "notes.txt" to "notes.txt isn't Markdown: a reference's name ends in .md.",
            "/tmp/notes.md" to "/tmp/notes.md is absolute: a reference's name is relative to the Skill's folder.",
            "../outside.md" to "../outside.md leads outside the Skill's folder: a reference's name has no .. in it.",
            "references/../../outside.md" to "references/../../outside.md leads outside the Skill's folder: a reference's name has no .. in it.",
            "SKILL.md" to "SKILL.md is the Skill itself, not a reference.",
            "SKILL" to "SKILL.md is the Skill itself, not a reference.",
            "guide.md" to "guide.md already exists.",
            "references/format" to "references/format.md already exists.",
            "nested/inner" to "nested/inner.md lies in another Skill's folder.",
        )
        for ((name, reason) in refusals) {
            val refused = newReference("refuser", name)
            assertEquals(400, refused.status, name)
            assertContains(refused.body, reason, message = name)
        }
        for ((name, reason) in listOf("notes.txt" to "notes.txt isn't Markdown: a reference's name ends in .md.", "README" to "README isn't Markdown: a reference's name ends in .md.", "guide.md" to "guide.md already exists.", "SKILL.md" to "SKILL.md is the Skill itself, not a reference.")) {
            val refused = importReference("refuser", name, "Imported.")
            assertEquals(400, refused.status, name)
            assertContains(refused.body, reason, message = name)
        }
        assertEquals(listOf("Published"), markers(page("/skills/refuser")))
        assertEquals(listOf("guide.md", "references/format.md"), references(page("/skills/refuser")))
        assertEquals("Published guide.", folder.resolve("guide.md").readText())
        assertFalse(home.resolve("skills/outside.md").exists())
        assertFalse(folder.resolve("nested/inner.md").exists())
        folder.resolve("nested").toFile().deleteRecursively()
        browser.post("/skills/refuser/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `Import reference adds a md file to the Draft under its own file name`() {
        val folder = referring("importer")
        val imported = importReference("importer", "checklist.md", "The checklist.\r\n")
        assertEquals(200 to "/skills/importer", imported.status to imported.path)
        assertEquals(listOf("checklist.md" to "added"), marked(imported.body.replace("&#34;", "\"")))
        assertContains(page("/skills/importer/references/checklist.md"), "The checklist.\n")
        assertFalse(folder.resolve("checklist.md").exists())
        assertEquals("/skills/importer", browser.post("/skills/importer", mapOf("skill" to skillMd("importer", "Published."))).path)
        assertEquals("The checklist.\n", folder.resolve("checklist.md").readText())
        browser.post("/skills/importer/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `delete asks first naming the reference, then keeps it struck through and marked until Publish removes it from the folder`() {
        val folder = referring("deleter")
        val asked = browser.post("/skills/deleter/delete-reference", mapOf("path" to "references/format.md"))
        assertContains(asked.body, "Delete the reference references/format.md?")
        assertEquals(listOf("Published"), markers(page("/skills/deleter")))

        val deleted = browser.post("/skills/deleter/delete-reference", mapOf("path" to "references/format.md", "confirm" to "on"))
        assertEquals("/skills/deleter", deleted.path)
        val page = page("/skills/deleter")
        assertEquals(listOf("references/format.md" to "deleted"), marked(page))
        assertContains(page, """<span class="grow gone"><code>references/format.md</code>""")
        assertFalse("""action="/skills/deleter/references/references/format.md"""" in page)
        assertEquals(404, browser.get("/skills/deleter/references/references/format.md").status)
        assertEquals("Published format.", folder.resolve("references/format.md").readText())
        assertEquals(404, browser.post("/skills/deleter/delete-reference", mapOf("path" to "references/format.md", "confirm" to "on")).status)
        assertEquals(404, browser.post("/skills/deleter/delete-reference", mapOf("path" to "LICENSE", "confirm" to "on")).status)

        assertEquals("/skills/deleter", browser.post("/skills/deleter", mapOf("skill" to skillMd("deleter", "Published."))).path)
        assertFalse(folder.resolve("references/format.md").exists())
        assertEquals("Published guide.", folder.resolve("guide.md").readText())
        assertEquals("MIT", folder.resolve("LICENSE").readText())
        assertEquals("echo hi\n", folder.resolve("scripts/run.sh").readText())
        assertEquals(listOf("guide.md"), references(page("/skills/deleter")))
        assertEquals(emptyList(), marked(page("/skills/deleter")))
        browser.post("/skills/deleter/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `Discard Draft drops added and deleted references alike`() {
        val folder = referring("undone")
        newReference("undone", "notes")
        importReference("undone", "checklist.md", "The checklist.")
        browser.post("/skills/undone/delete-reference", mapOf("path" to "guide.md", "confirm" to "on"))
        assertEquals(listOf("checklist.md" to "added", "guide.md" to "deleted", "notes.md" to "added"), marked(page("/skills/undone")))

        browser.post("/skills/undone/discard", mapOf("confirm" to "on"))
        val page = page("/skills/undone")
        assertEquals(listOf("Published"), markers(page))
        assertEquals(listOf("guide.md", "references/format.md"), references(page))
        assertEquals(emptyList(), marked(page))
        assertEquals(listOf("LICENSE", "SKILL.md", "ants.yaml", "guide.md", "references", "scripts"), folder.toFile().list()!!.sorted())
        browser.post("/skills/undone/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `a Draft-only Skill keeps its references with its Draft, and its first Publish creates its folder with them`() {
        val draft = browser.post("/skills/new/draft", emptyMap()).path
        val key = draft.removePrefix("/skills/")
        assertEquals(draft to "reference=references/api.md", newReference(key, "references/api").let { it.path to it.query })
        browser.post("$draft/references/references/api.md", mapOf("text" to "The API."))
        importReference(key, "checklist.md", "The checklist.")
        newReference(key, "dropped")
        browser.post("$draft/delete-reference", mapOf("path" to "dropped.md", "confirm" to "on"))
        val page = page(draft)
        assertEquals(listOf("checklist.md", "references/api.md"), references(page))
        assertEquals(emptyList(), marked(page))

        val folder = home.resolve("skills/first-published")
        folder.toFile().deleteRecursively()
        assertEquals("/skills/first-published", browser.post(draft, mapOf("skill" to skillMd("first-published"))).path)
        assertEquals("The API.", folder.resolve("references/api.md").readText())
        assertEquals("The checklist.", folder.resolve("checklist.md").readText())
        assertFalse(folder.resolve("dropped.md").exists())
        browser.post("/skills/first-published/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `Copy and Import folder keep a Skill's references`() {
        referring("copied-refs")
        val copy = browser.post("/skills/copied-refs/copy", emptyMap())
        assertEquals("/skills/copied-refs-copy", copy.path)
        assertEquals(listOf("guide.md", "references/format.md"), references(copy.body))
        assertEquals("/skills/copied-refs-copy", browser.post("/skills/copied-refs-copy", mapOf("skill" to skillMd("copied-refs-copy"))).path)
        assertEquals("Published format.", home.resolve("skills/copied-refs-copy/references/format.md").readText())
        browser.post("/skills/copied-refs-copy/delete", mapOf("confirm" to "on"))
        browser.post("/skills/copied-refs/delete", mapOf("confirm" to "on"))

        home.resolve("skills/imported-refs").toFile().deleteRecursively()
        val imported = import(
            "imported-refs/SKILL.md" to skillMd("imported-refs"),
            "imported-refs/guide.md" to "The guide.",
            "imported-refs/references/format.md" to "The format.",
        )
        assertEquals("/skills/imported-refs", imported.path)
        assertEquals(listOf("guide.md", "references/format.md"), references(imported.body))
        assertContains(page("/skills/imported-refs/references/references/format.md"), "The format.")
        browser.post("/skills/imported-refs/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `a read-only Skill has no New reference, Import reference or delete, and its endpoints refuse writes`() {
        val page = page("/skills/tdd")
        for (control in listOf("/skills/tdd/new-reference", "/skills/tdd/import-reference", "/skills/tdd/delete-reference")) assertFalse(control in page, control)
        assertEquals(403, browser.post("/skills/tdd/new-reference", mapOf("path" to "notes")).status)
        assertEquals(403, importReference("tdd", "checklist.md", "The checklist.").status)
        assertEquals(403, browser.post("/skills/tdd/delete-reference", mapOf("path" to "tests.md", "confirm" to "on")).status)
        assertEquals(listOf("mocking.md", "tests.md"), references(page("/skills/tdd")).filter { it in setOf("mocking.md", "tests.md") })
        assertEquals(emptyList(), marked(page("/skills/tdd")))

        referring("own-refs")
        val own = page("/skills/own-refs")
        for (control in listOf("/skills/own-refs/new-reference", "/skills/own-refs/import-reference", "/skills/own-refs/delete-reference")) assertContains(own, control)
        browser.post("/skills/own-refs/delete", mapOf("confirm" to "on"))
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
