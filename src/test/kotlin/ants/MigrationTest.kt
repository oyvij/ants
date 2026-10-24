package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The migration of the user's old-shape Processes, Templates, Skills and Agents (ADR 0024), as a start of Ants runs it
 * ([Migration.migrate]), seen where it crosses a boundary: the files in ANTS_HOME, the diagram a Process opens with,
 * and the claude launch of a Run.
 */
class MigrationTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    @Autowired
    lateinit var migration: Migration

    @Autowired
    lateinit var repository: RepositoryService

    @Autowired
    lateinit var runtime: RuntimeService

    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Drafted.","output":{"text":"Hi"}}}"""
    private val textSchema = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""
    private val agentTask = javaClass.getResource("/agent-task.bpmn")!!.readText()

    /** agent-task.bpmn under [key], its Task naming the Skill [skill] the old way, with [extra] properties. */
    private fun oldShape(key: String, skill: String = "brief", agent: String = "writer", extra: String = "") = agentTask.replace("\"agent-task\"", "\"$key\"")
        .replace("""<operaton:property name="ants.agent" value="writer" />""", """<operaton:property name="ants.agent" value="$agent" />""")
        .replace("""<operaton:property name="ants.skills" value="draft" />""", """<operaton:property name="ants.skill" value="$skill" />$extra""")

    /** Deploys [bpmn] as an older Ants did, without today's Checks. */
    private fun deployedByOlderAnts(key: String, bpmn: String) {
        repository.createDeployment().name("$key.bpmn").addString("$key.bpmn", bpmn).deploy()
    }

    private fun property(name: String, value: String) = """<operaton:property name="$name" value="${value.replace("\"", "&quot;").replace("\n", "&#10;")}" />"""

    /** The Agent `writer` listing only `brief`; `brief` declares inputs, uses `helper`, opts in to the Vault and promises [textSchema]. */
    private fun oldSkills() {
        settings(claude, "agent-tasks: 3\n")
        home.resolve("agents/writer.md").writeText("---\nskills: brief\n---\nWrite plainly.\n")
        val brief = home.resolve("skills/brief").createDirectories()
        brief.resolve("SKILL.md").writeText("---\nname: brief\ndescription: Writes a brief.\nmetadata:\n  ants-inputs: prompt tone audience\n" +
            "  ants-uses: helper\n  varde-vault: true\n  ants-verbosity: true\n---\nWrite the brief.\n")
        brief.resolve("result.schema.json").writeText(textSchema)
        home.resolve("skills/helper").createDirectories().resolve("SKILL.md").writeText("---\nname: helper\ndescription: Helps.\n---\nHelp.\n")
    }

    /** Every file under ANTS_HOME's own folders, by path, with its text. */
    private fun files(): Map<String, String> = listOf("agents", "skills", "templates").map(home::resolve).filter { it.exists() }
        .flatMap { folder -> folder.walk().filter { it.isRegularFile() }.map { it.relativeTo(home).toString() to it.readText() } }.toMap()

    /** A launch as it compares across Runs: its argv and prompt with the Run's workspace, session id and MCP token taken out. */
    private fun comparable(launch: FakeClaude.Launch, run: String): List<String> {
        val workspace = workspace(run).toString()
        val session = launch.flag("--session-id")!!
        return (launch.argv + launch.stdin).map { it.replace(workspace, "WORKSPACE").replace(session, "SESSION").replace(Regex("Bearer [0-9a-f-]+"), "Bearer TOKEN") }
    }

    private fun start(key: String) = browser.get("/processes/$key").let { browser.post("/processes/$key/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/") }

    private fun completed(run: String) = eventually { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }

    @Test
    fun `an old Process, Template and Skill are migrated once, and the Process launches as the same Task drawn the new way does`() {
        oldSkills()
        claude.session("draft", done)
        deployedByOlderAnts("old-shape", oldShape("old-shape"))
        val template = home.resolve("templates/old.bpmn").apply { writeText(oldShape("old")) }
        val before = files()

        migration.migrate()

        // The Task now carries what the Skill gave it: an Assignment, since it used another Skill, both Skills, the
        // Skill's schema and Vault opt-in, and an empty input for the one it declared and didn't fill.
        val drawn = agentTask.replace("\"agent-task\"", "\"new-shape\"").replace("""<operaton:property name="ants.skills" value="draft" />""",
            property("ants.skills", "brief, helper") + property("ants.assignment", "Follow the Skill brief.") +
                property("ants.result-schema", textSchema) + property("ants.vault", "true"))
            .replace("""<operaton:inputParameter name="tone">dry</operaton:inputParameter>""",
                """<operaton:inputParameter name="tone">dry</operaton:inputParameter><operaton:inputParameter name="audience"></operaton:inputParameter>""")
        browser.get("/processes")
        val deployed = browser.upload("/processes", "file", "new-shape.bpmn", drawn.toByteArray()).body
        assertFalse("role=\"alert\"" in deployed, deployed)
        val migrated = start("old-shape")
        completed(migrated)
        val reference = start("new-shape")
        completed(reference)
        val (old, new) = claude.launches("draft")
        assertEquals(comparable(new, reference), comparable(old, migrated))
        assertContains(old.stdin, "Follow the Skill brief.")
        assertEquals(setOf("brief", "helper"), old.skills.keys)

        // Each changed file's old version sits beside it; the Skill is plain, and the Agent lists the Skill its Task now names.
        assertEquals(before.getValue("templates/old.bpmn"), home.resolve("templates/old.bpmn.old").readText())
        assertContains(template.readText(), "ants.skills")
        assertFalse("\"ants.skill\"" in template.readText())
        assertEquals(before.getValue("skills/brief/SKILL.md"), home.resolve("skills/brief/SKILL.md.old").readText())
        assertEquals("---\nname: brief\ndescription: Writes a brief.\n---\nWrite the brief.\n", home.resolve("skills/brief/SKILL.md").readText())
        assertFalse(home.resolve("skills/brief/result.schema.json").exists())
        assertEquals(textSchema, home.resolve("skills/brief/result.schema.json.old").readText())
        assertEquals(before.getValue("agents/writer.md"), home.resolve("agents/writer.md.old").readText())
        assertContains(home.resolve("agents/writer.md").readText(), "helper")

        // A second start changes nothing.
        val after = files()
        val versions = repository.createProcessDefinitionQuery().count()
        migration.migrate()
        assertEquals(after, files())
        assertEquals(versions, repository.createProcessDefinitionQuery().count())
    }

    @Test
    fun `every Agent and Skill without an id gets one, an outside one in ANTS_HOME, and a second start changes nothing`() {
        val outsideAgents = Files.createTempDirectory("outside-agents")
        outsideAgents.resolve("outsider.md").writeText("---\nname: outsider\n---\nLook around.\n")
        val outsideSkills = Files.createTempDirectory("outside-skills")
        outsideSkills.resolve("far").createDirectories().resolve("SKILL.md").writeText("---\nname: far\ndescription: Far away.\n---\nGo.\n")
        settings(claude, "agent-folders: [$outsideAgents]\nskill-folders: [$outsideSkills]\n")
        home.resolve("agents/writer.md").writeText("---\nname: writer\nskills: brief\n---\nWrite plainly.\n")
        home.resolve("agents/plain.md").writeText("Just do it.\n")
        home.resolve("agents/kept.md").writeText("---\nid: a-kept01\nname: kept\n---\nKeep it.\n")
        home.resolve("skills/brief").createDirectories().resolve("SKILL.md").writeText("---\nname: brief\ndescription: Writes a brief.\n---\nWrite the brief.\n")
        val before = files()
        val outside = listOf(outsideAgents, outsideSkills).flatMap { folder -> folder.walk().map { it.toString() to it.readText() } }

        migration.migrate()

        val idLine = Regex("(?m)^id: (a-[a-z0-9]{6})$")
        val writer = home.resolve("agents/writer.md").readText()
        assertEquals("---\nid: ${idLine.find(writer)!!.groupValues[1]}\nname: writer\nskills: brief\n---\nWrite plainly.\n", writer)
        assertEquals(before.getValue("agents/writer.md"), home.resolve("agents/writer.md.old").readText())
        val plain = home.resolve("agents/plain.md").readText()
        assertEquals("---\nid: ${idLine.find(plain)!!.groupValues[1]}\n---\nJust do it.\n", plain)
        assertEquals("plain", Agent(home.resolve("agents/plain.md")).name)
        assertEquals(before.getValue("agents/plain.md"), home.resolve("agents/plain.md.old").readText())
        assertEquals(before.getValue("agents/kept.md"), home.resolve("agents/kept.md").readText())
        assertFalse(home.resolve("agents/kept.md.old").exists())
        assertTrue(Regex("id: k-[a-z0-9]{6}\n").matches(home.resolve("skills/brief/ants.yaml").readText()))
        assertEquals(before.getValue("skills/brief/SKILL.md"), home.resolve("skills/brief/SKILL.md").readText())

        assertEquals(outside, listOf(outsideAgents, outsideSkills).flatMap { folder -> folder.walk().map { it.toString() to it.readText() } })
        assertEquals(listOf(outsideAgents.resolve("outsider.md").toString()), Yaml().load<Map<String, String>>(home.resolve("agent-ids.yaml").readText()).values.toList())
        assertEquals(listOf(outsideSkills.resolve("far").toString()), Yaml().load<Map<String, String>>(home.resolve("skill-ids.yaml").readText()).values.toList())

        val after = files() + listOf("agent-ids.yaml", "skill-ids.yaml").associateWith { home.resolve(it).readText() }
        migration.migrate()
        assertEquals(after, files() + listOf("agent-ids.yaml", "skill-ids.yaml").associateWith { home.resolve(it).readText() })
    }

    @Test
    fun `a Skill used by two Tasks with different overrides keeps each Task's own`() {
        oldSkills()
        val verdict = """{"type":"object","properties":{"verdict":{"type":"string"}}}"""
        deployedByOlderAnts("plain", oldShape("plain"))
        deployedByOlderAnts("overridden", oldShape("overridden", extra = property("ants.result-schema", verdict) + property("ants.vault", "false")))

        migration.migrate()

        val plain = browser.get("/processes/plain/diagram.bpmn").body
        assertContains(plain, property("ants.result-schema", textSchema).removeSuffix(" />"))
        assertContains(plain, property("ants.vault", "true").removeSuffix(" />"))
        val overridden = browser.get("/processes/overridden/diagram.bpmn").body
        assertContains(overridden, property("ants.result-schema", verdict).removeSuffix(" />"))
        assertContains(overridden, property("ants.vault", "false").removeSuffix(" />"))
        assertFalse(textSchema.replace("\"", "&quot;") in overridden)
    }

    @Test
    fun `a Task naming a shipped Skill that is now a shipped Task's Assignment gets that Task's Assignment, Skills, schema and toggles`() {
        settings(claude, "agent-tasks: 3\n")
        // A user's Save of Build and ship's scout, and a copy of Grill you answer as a Template.
        deployedByOlderAnts("my-scout", oldShape("my-scout", skill = "scout", agent = "scout").replace("\"draft\"", "\"scout\""))
        home.resolve("templates/my-grill.bpmn").writeText(oldShape("my_grill", skill = "grill", agent = "griller").replace("\"draft\"", "\"grill\""))

        migration.migrate()

        val shipped = home.resolve("shipped/processes/build-and-ship.bpmn").readText()
        fun properties(xml: String, task: String) = Regex("""<(?:bpmn:)?serviceTask id="$task".*?</(?:bpmn:)?serviceTask>""", RegexOption.DOT_MATCHES_ALL).find(xml)!!.value
            .let { Regex("""<operaton:property name="(ants\.(?:assignment|skills|result-schema|vault|answer-history))" value="([^"]*)"""").findAll(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }
        val scout = properties(browser.get("/processes/my-scout/diagram.bpmn").body, "scout")
        assertEquals(properties(shipped, "scout"), scout)
        assertTrue("ants.assignment" in scout)
        assertEquals(properties(shipped, "grill"), properties(home.resolve("templates/my-grill.bpmn").readText(), "grill"))
    }

    @Test
    fun `a Run in flight on an old version of a Process runs its Task as the migrated version has it`() {
        settings(claude, "agent-tasks: 0\n") // held until the migration
        home.resolve("agents/writer.md").writeText("---\nskills: draft\n---\nWrite plainly.\n")
        val draft = home.resolve("skills/draft").createDirectories()
        draft.resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts.\n---\nDraft it.\n")
        draft.resolve("result.schema.json").writeText(textSchema)
        claude.session("draft", done)
        deployedByOlderAnts("in-flight", oldShape("in-flight", skill = "draft"))
        val run = runtime.startProcessInstanceByKey("in-flight", mapOf("prompt" to "Say hello")).id

        migration.migrate()
        assertFalse(draft.resolve("result.schema.json").exists()) // only the new version has the schema now
        settings(claude, "agent-tasks: 3\n")

        completed(run)
        val launch = claude.launches("draft").single()
        assertTrue(launch.stdin.contains("\"content\":\"/draft The workspace is"), launch.stdin)
        val schema = JsonMapper.shared().readValue(launch.flag("--json-schema")!!, Map::class.java)
        assertEquals(JsonMapper.shared().readValue(textSchema, Map::class.java), (schema["properties"] as Map<*, *>)["output"])
    }

    @Test
    fun `a Process the Checks refuse after migration leaves its Agents as they were, and its Runs fail saying what to fix`() {
        oldSkills()
        settings(claude, "agent-tasks: 0\n") // held until the migration
        home.resolve("agents/writer.md").writeText("---\nid: a-writer\nskills: brief\n---\nWrite plainly.\n")
        val agent = home.resolve("agents/writer.md").readText()
        // An old Ants deployed it without today's Checks, which refuse its result schema.
        deployedByOlderAnts("refused", oldShape("refused", extra = property("ants.result-schema", "[1]")))
        val run = runtime.startProcessInstanceByKey("refused", mapOf("prompt" to "Say hello")).id

        migration.migrate()

        assertEquals(agent, home.resolve("agents/writer.md").readText())
        assertFalse(home.resolve("agents/writer.md.old").exists())
        settings(claude, "agent-tasks: 3\n")
        eventually { assertContains(browser.get("/runs/$run").body, "[failed] The Process refused was not migrated to Assignments and Skills when Ants started") }
        assertContains(browser.get("/runs/$run").body, "Its result schema is invalid")
    }
}

/** The migration runs as Ants starts. */
class MigrationBootTest {
    @Test
    fun `a start migrates the user's old-shape Template`() {
        val home = AntsTest.tempHome()
        val template = home.resolve("templates").createDirectories().resolve("old.bpmn")
        template.writeText(javaClass.getResource("/agent-task.bpmn")!!.readText().replace("name=\"ants.skills\"", "name=\"ants.skill\""))
        AntsTest.bootAnother(home)
        assertContains(template.readText(), "name=\"ants.skills\" value=\"draft\"")
        assertContains(home.resolve("templates/old.bpmn.old").readText(), "name=\"ants.skill\" value=\"draft\"")
    }
}
