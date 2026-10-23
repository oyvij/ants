package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerbosityTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    @Autowired
    lateinit var runtime: RuntimeService

    @Autowired
    lateinit var catalogue: Catalogue

    private val claude = FakeClaude()

    private fun profiles(work: String = "verbosity: brief\n", private: String = "verbosity: detailed\n") = """
        source-maps:
          notes: ${vaultPath.resolve("source-map.md")}
        profiles:
          work:
            vault: notes
            source-map: notes
        ${work.prependIndent("    ")}
          private:
            vault: notes
            source-map: notes
        ${private.prependIndent("    ")}
        default-profile: work

    """.trimIndent()

    private fun setUp(extra: String) {
        vaultPath.resolve("source-map.md").writeText("")
        settings(claude, extra)
        browser.import("start-end.bpmn")
    }

    private fun form(query: String = "") = browser.get("/processes/start-end$query").body

    private fun selected(form: String) = Regex("""<select name="$VERBOSITY" id="field-verbosity">(.*?)</select>""", RegexOption.DOT_MATCHES_ALL)
        .find(form)!!.groupValues[1].let { Regex("""<option value="(\w+)" selected>""").find(it)?.groupValues?.get(1) }

    private fun start(vararg values: Pair<String, String>): String {
        val page = browser.post("/processes/start-end/runs", values.toList())
        assertTrue(page.path.startsWith("/runs/"), page.body)
        return page.path.removePrefix("/runs/")
    }

    private fun runPage(run: String) = browser.get("/runs/$run").body

    @Test
    fun `the start form offers Brief, Normal and Detailed, prefilled from the shown Profile`() {
        setUp(profiles())
        val form = form()
        assertContains(form, """<option value="brief" selected>Brief</option>""")
        assertContains(form, """<option value="normal">Normal</option>""")
        assertContains(form, """<option value="detailed">Detailed</option>""")
        assertEquals("detailed", selected(form("?$PROFILE=private")))
    }

    @Test
    fun `the start form shows Verbosity with one Profile, and normal with none`() {
        setUp("source-maps:\n  notes: ${vaultPath.resolve("source-map.md")}\nprofiles:\n  work:\n    vault: notes\n    source-map: notes\n    verbosity: detailed\n")
        assertFalse("""<select name="$PROFILE"""" in form())
        assertEquals("detailed", selected(form()))

        setUp("")
        assertEquals("normal", selected(form()))
    }

    @Test
    fun `a Process that names its Profile prefills Verbosity from it`() {
        setUp(profiles())
        browser.get("/processes")
        browser.upload(
            "/processes", "file", "start-end.bpmn",
            javaClass.getResource("/start-end.bpmn")!!.readText().replace(
                """camunda:historyTimeToLive="180">""",
                """camunda:historyTimeToLive="180"><bpmn:extensionElements><camunda:properties><camunda:property name="ants.profile" value="private" /></camunda:properties></bpmn:extensionElements>""",
            ).toByteArray(),
        )
        assertEquals("detailed", selected(form()))
    }

    @Test
    fun `a Run started at brief shows Verbosity brief beside its Profile, and keeps it when the Profile's default changes`() {
        setUp(profiles())
        form()
        val run = start(PROFILE to "private", VERBOSITY to "brief")
        assertContains(runPage(run), Regex("""<span id="profile">Profile private</span>\s*· <span id="verbosity">Verbosity brief</span>"""))

        settings(claude, profiles(work = "verbosity: detailed\n", private = "verbosity: detailed\n"))
        assertContains(runPage(run), """<span id="verbosity">Verbosity brief</span>""")
    }

    @Test
    fun `a Start posting no Verbosity gets its Profile's default, and with no Profiles normal`() {
        setUp(profiles())
        form()
        assertContains(runPage(start(PROFILE to "private")), """<span id="verbosity">Verbosity detailed</span>""")

        setUp("")
        form()
        val run = start()
        assertFalse("""id="profile"""" in runPage(run))
        assertContains(runPage(run), """<span id="verbosity">Verbosity normal</span>""")
    }

    @Test
    fun `a Start posting a Verbosity outside the three is refused with the form shown again`() {
        setUp(profiles())
        form()
        val refused = browser.post("/processes/start-end/runs", listOf(NAME to "Loud one", PROFILE to "private", VERBOSITY to "loud"))
        assertEquals(400, refused.status)
        assertContains(refused.body, """<p role="alert">Verbosity is none of its choices</p>""")
        assertContains(refused.body, """value="Loud one"""")
    }

    @Test
    fun `a refused Start shows the form again with the posted Verbosity`() {
        setUp(profiles())
        browser.import("start-form.bpmn")
        browser.get("/processes/start-form")
        val refused = browser.post("/processes/start-form/runs", listOf(PROFILE to "work", VERBOSITY to "detailed"))
        assertEquals(400, refused.status)
        assertEquals("detailed", selected(refused.body))
    }

    @Test
    fun `Set up Vault from the Settings page starts a Run at its Profile's default Verbosity`() {
        vaultPath.resolve("source-map.md").writeText("")
        agentTask(claude, extra = profiles())
        browser.import("shipped/processes/set-up-vault.bpmn")
        browser.get("/settings")
        val page = browser.post("/processes/set_up_vault/runs", listOf(PROFILE to "private"))
        assertTrue(page.path.startsWith("/runs/"), page.body)
        assertContains(runPage(page.path.removePrefix("/runs/")), """<span id="verbosity">Verbosity detailed</span>""")
    }

    @Test
    fun `the shipped verbosity file defines brief, normal and detailed`() {
        val file = verbosityFolder.resolve("verbosity.md").readText()
        for (level in listOf("## brief", "## normal", "## detailed")) assertContains(file, level)
    }

    @Test
    fun `every shipped Skill opts in to Verbosity`() {
        val shipped = catalogue.skills(withHidden = true).filter { catalogue.shipped(it) && it.name != "shipped-draft" }
        assertEquals(16, shipped.size)
        assertEquals(emptyList(), shipped.filterNot { it.verbosity }.map { it.name })
    }

    private val optIn = "metadata:\n  ants-verbosity: true\n"

    private val verbosityFolder: Path get() = home.resolve("shipped/verbosity")

    private fun sentence(level: String) = " Write what the user reads at the $level verbosity defined in ${verbosityFolder.resolve("verbosity.md")}."

    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"done","summary":"Drafted.","output":{}}}"""

    private fun completed(run: String) = eventually(20) { assertContains(runPage(run), """id="state">Completed<""") }

    /** The launch of `draft`, its Skill's frontmatter carrying [metadata], in a Run of agent-task started at [verbosity]. */
    private fun launch(metadata: String, verbosity: String): FakeClaude.Launch {
        agentTask(claude, metadata = metadata, extra = profiles())
        claude.session("draft", done)
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        completed(browser.post("/processes/agent-task/runs", listOf("prompt" to "Say hello", VERBOSITY to verbosity)).path.removePrefix("/runs/"))
        return claude.launches("draft").last()
    }

    private val FakeClaude.Launch.handOff get() = "/draft" + stdin.substringAfter("/draft").substringBefore("\\n")

    private val FakeClaude.Launch.readsVerbosity get() = "--add-dir" to "$verbosityFolder" in argv.zipWithNext()

    @Test
    fun `an opted-in Skill at brief or detailed names the level and the verbosity file after the Answer History, and gets read access to it`() {
        for (level in listOf("brief", "detailed")) {
            val launch = launch("$optIn  ants-answer-history: true\n", level)
            assertTrue(launch.handOff.endsWith(" The Answer History is ${home.resolve("answer-history")}.${sentence(level)}"), launch.handOff)
            assertTrue(launch.readsVerbosity, launch.argv.toString())
        }
    }

    private val FakeClaude.Launch.comparableArgv
        get() = argv.withIndex().filterNot { (i, arg) -> i > 0 && (argv[i - 1] in setOf("--session-id", "--mcp-config") || "/.skills/" in arg) }.map { it.value }

    private fun assertToday(launch: FakeClaude.Launch) {
        assertTrue(launch.handOff.matches(Regex("/draft The workspace is [^ ]+\\.")), launch.handOff)
        assertFalse(launch.readsVerbosity)
        assertFalse(launch.argv.any { "$verbosityFolder" in it })
    }

    @Test
    fun `at normal an opted-in Skill gets today's hand-off line and flags, and at brief a Skill that doesn't opt in gets them too`() {
        val normal = launch(optIn, "normal")
        assertToday(normal)
        val notOptedIn = launch("", "brief")
        assertToday(notOptedIn)
        assertEquals(normal.comparableArgv, notOptedIn.comparableArgv)
    }

    @Test
    fun `a Skill that uses an opted-in Skill through ants-uses gets the level and read access`() {
        home.resolve("skills/used/styled").createDirectories().resolve("SKILL.md")
            .writeText("---\nname: styled\ndescription: Used.\n${optIn}---\nUse it.\n")
        val launch = launch("metadata:\n  ants-uses: styled\n", "detailed")
        assertTrue(launch.handOff.endsWith(sentence("detailed")), launch.handOff)
        assertTrue(launch.readsVerbosity)
    }

    @Test
    fun `a Run started at brief writes at brief in its next Task after its Profile's default changes`() {
        agentTask(claude, metadata = optIn, extra = profiles(work = "verbosity: brief\n"))
        claude.session("draft", 1, "wait go", done)
        claude.session("draft", done)
        browser.import("agent-loop.bpmn")
        browser.get("/processes/agent-loop")
        val run = browser.post("/processes/agent-loop/runs", listOf(PROFILE to "work")).path.removePrefix("/runs/")
        eventually { assertEquals(1, claude.launches("draft").size) }
        settings(claude, profiles(work = "verbosity: detailed\n"))
        claude.release("go")
        completed(run)
        for (launch in claude.launches("draft")) assertTrue(launch.handOff.endsWith(sentence("brief")), launch.handOff)
    }

    /** A Process [key] whose only step calls [called], handing on its `prompt`. */
    private fun caller(key: String, called: String) {
        browser.get("/processes")
        val deployed = browser.post("/processes/deploy", mapOf("bpmn" to """<?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn" id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="$key" name="$key" isExecutable="true" operaton:historyTimeToLive="180">
                <bpmn:startEvent id="start">
                  <bpmn:extensionElements><operaton:formData><operaton:formField id="prompt" label="Prompt" type="string" /></operaton:formData></bpmn:extensionElements>
                </bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="call" />
                <bpmn:callActivity id="call" calledElement="$called">
                  <bpmn:extensionElements><operaton:in sourceExpression="${'$'}{prompt}" target="prompt" /></bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:sequenceFlow id="f2" sourceRef="call" targetRef="end" />
                <bpmn:endEvent id="end" />
              </bpmn:process>
            </bpmn:definitions>"""))
        assertEquals(200, deployed.status, deployed.body)
    }

    @Test
    fun `a called Process's opted-in Task writes at its top Run's level, one and two levels down`() {
        agentTask(claude, metadata = optIn, extra = profiles())
        claude.session("draft", done)
        browser.import("agent-task.bpmn")
        caller("middle", "agent-task")
        caller("top", "middle")
        for ((key, level) in listOf("middle" to "detailed", "top" to "brief")) {
            browser.get("/processes/$key")
            completed(browser.post("/processes/$key/runs", listOf("prompt" to "Say hello", VERBOSITY to level)).path.removePrefix("/runs/"))
            val launch = claude.launches("draft").last()
            assertTrue(launch.handOff.endsWith(sentence(level)), launch.handOff)
            assertTrue(launch.readsVerbosity)
        }
    }

    @Test
    fun `the Report names the Run's Profile and Verbosity under its heading`() {
        setUp(profiles())
        form()
        val run = start(NAME to "Terse one", PROFILE to "private", VERBOSITY to "brief")
        assertContains(browser.report(run), Regex("<h1>Terse one</h1>\n<p>[^<]+ · Completed</p>\n<p>Profile private · Verbosity brief</p>"))
    }

    @Test
    fun `the Report of a Run with no Profile names its Verbosity alone`() {
        setUp("")
        form()
        val run = start(NAME to "Plain one", VERBOSITY to "detailed")
        assertContains(browser.report(run), Regex("<h1>Plain one</h1>\n<p>[^<]+ · Completed</p>\n<p>Verbosity detailed</p>"))
    }

    @Test
    fun `a Run started before Verbosity existed reads as normal on its page, in its Report and in its hand-off line`() {
        agentTask(claude, metadata = optIn, extra = profiles())
        claude.session("draft", done)
        browser.import("agent-task.bpmn")
        val run = runtime.startProcessInstanceByKey("agent-task", mapOf("prompt" to "Say hello")).id
        completed(run)
        assertToday(claude.launches("draft").single())
        assertContains(runPage(run), """<span id="verbosity">Verbosity normal</span>""")
        assertContains(browser.report(run), Regex("</h1>\n<p>[^<]+ · Completed</p>\n<p>Verbosity normal</p>"))
    }
}
