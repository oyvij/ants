package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScriptTaskTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val secrets = home.resolve("secrets.yaml")
    private val json = JsonMapper.shared()

    @Autowired
    lateinit var runtime: RuntimeService

    @Autowired
    lateinit var repository: RepositoryService

    @Autowired
    lateinit var runner: ScriptRunner

    @BeforeEach
    fun settings() {
        home.resolve("settings.yaml").writeText("node: {path: ${ScriptsTest.NODE}}\nclaude: {env: [PATH, HOME]}\nsecrets:\n  npm: [NPM_TOKEN]\n$vault")
        secrets.writeText("npm: s3cret-npm\n")
    }

    @AfterEach
    fun noSecrets() {
        secrets.deleteIfExists()
    }

    private fun saveScript(name: String, code: String, inputs: String = "", vars: String = "", own: List<Pair<String, String>> = emptyList(), linked: List<String> = emptyList(), was: String? = null, output: List<Pair<String, String>> = emptyList()) {
        val form = listOf("name" to name, "description" to "Does $name.", "code" to code, "vars" to vars) + ScriptsTest.inputRows(inputs) + output +
            own.withIndex().flatMap { (i, secret) -> listOf("secrets[$i].name" to secret.first, "secrets[$i].value" to secret.second, "secrets[$i].was" to if (was == null) "" else secret.first) } +
            linked.map { "linked" to it }
        val page = if (was == null) browser.publishNew("/scripts", form) else browser.get("/scripts/$was").let { browser.post("/scripts/$was", form) }
        assertEquals("/scripts/$name", page.path, page.body)
    }

    private fun scriptTask(id: String, script: String, inputs: Map<String, String> = emptyMap()) = """
        <bpmn:serviceTask id="$id" name="Run $script" operaton:type="external" operaton:topic="script">
          <bpmn:extensionElements>
            <operaton:properties><operaton:property name="ants.script" value="$script" /></operaton:properties>
            <operaton:inputOutput>${inputs.entries.joinToString("") { (name, value) -> """<operaton:inputParameter name="$name">$value</operaton:inputParameter>""" }}</operaton:inputOutput>
          </bpmn:extensionElements>
        </bpmn:serviceTask>
    """.trimIndent()

    private fun deploy(key: String, vararg tasks: String): String {
        val deployed = browser.post("/processes/deploy", mapOf("bpmn" to deployable(key, *tasks)))
        assertEquals("/processes/$key", deployed.path, deployed.body)
        return key
    }

    /** A Process [key] running [tasks] one after another, then waiting. */
    private fun deployable(key: String, vararg tasks: String): String {
        val ids = tasks.map { Regex("""id="([^"]+)"""").find(it)!!.groupValues[1] }
        val steps = listOf("start") + ids + "wait"
        val flows = steps.zipWithNext().withIndex().joinToString("\n") { (i, pair) -> """<bpmn:sequenceFlow id="f$i" sourceRef="${pair.first}" targetRef="${pair.second}" />""" }
        return """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn" id="d" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="$key" name="$key" isExecutable="true" operaton:historyTimeToLive="180">
                <bpmn:startEvent id="start">
                  <bpmn:extensionElements><operaton:formData><operaton:formField id="text" label="Text" type="string" /></operaton:formData></bpmn:extensionElements>
                </bpmn:startEvent>
                ${tasks.joinToString("\n")}
                <bpmn:userTask id="wait" name="Wait" />
                <bpmn:endEvent id="end" />
                $flows
                <bpmn:sequenceFlow id="last" sourceRef="wait" targetRef="end" />
              </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()
    }

    private fun start(key: String, text: String = "hello"): String {
        browser.get("/processes/$key")
        val started = browser.post("/processes/$key/runs", mapOf("text" to text, NAME to ""))
        assertTrue(started.path.startsWith("/runs/"), Regex("role=\"alert\"[^<]*|class=\"warn\"[^<]*").findAll(started.body).joinToString { it.value })
        return started.path.removePrefix("/runs/")
    }

    private fun deployed(key: String): String = repository.getProcessModel(repository.createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult().id).readAllBytes().decodeToString()

    private fun incident(run: String) = runtime.createIncidentQuery().processInstanceId(run).singleResult()

    @Test
    fun `a Script Task runs its Script in the Run workspace, with its inputs as ants inputs and its vars, Secrets and ANTS_ variables in the environment, and a later Task reads what it handed back with ants output`() {
        saveScript(
            "post-message", inputs = "text", vars = "CHANNEL=general", own = listOf("SLACK_TOKEN" to "xoxb-s3cret"), linked = listOf("npm"),
            code = """
                const inputs = ants.inputs
                const e = process.env
                console.log('posting', inputs.text, 'with', e.SLACK_TOKEN)
                ants.output({draft: true})
                ants.output({inputs, channel: e.CHANNEL, slack: e.SLACK_TOKEN, npm: e.NPM_TOKEN,
                    workspace: e.ANTS_RUN_WORKSPACE, run: e.ANTS_RUN_ID, cwd: process.cwd()})
            """.trimIndent(),
        )
        saveScript("echo", inputs = "posted, code:number", code = "ants.output(await Promise.resolve(ants.inputs))\n")
        deploy("posting", scriptTask("post", "post-message", mapOf("text" to "\${text}")), scriptTask("echo", "echo", mapOf("posted" to "\${post.payload.inputs.text}", "code" to "\${post.exitCode}")))

        val run = start("posting")
        eventually { assertNotNull(runtime.getVariable(run, "echo"), browser.get("/runs/$run").body) }

        val result = runtime.getVariable(run, "post") as Map<*, *>
        assertEquals(0, result["exitCode"])
        assertEquals("posting hello with ••••\n", result["output"])
        val payload = result["payload"] as Map<*, *>
        assertEquals<Map<*, *>>(
            mapOf("inputs" to mapOf("text" to "hello"), "channel" to "general", "slack" to "••••", "npm" to "••••", "workspace" to workspace(run).toString(), "run" to run),
            payload.filterKeys { it != "cwd" },
        )
        assertEquals(workspace(run).toRealPath(), Path.of(payload["cwd"].toString()).toRealPath())
        assertEquals(mapOf("posted" to "hello", "code" to 0), (runtime.getVariable(run, "echo") as Map<*, *>)["payload"])
    }

    @Test
    fun `a Script that exits non-zero fails its Task with the masked output, and Retry runs the Script as it is on disk then`() {
        saveScript("flaky", own = listOf("TOKEN" to "t0ps3cret"), code = "console.log('broke with', process.env.TOKEN)\nprocess.exit(3)\n")
        deploy("flaky-run", scriptTask("flaky", "flaky"))
        val run = start("flaky-run")
        eventually { assertNotNull(incident(run)) }

        val entry = "/notifications/" + incident(run).id
        val page = browser.get(entry).body
        assertContains(page, "[failed]")
        assertContains(page, "broke with ••••")
        assertFalse("t0ps3cret" in page)
        assertContains(page, """value="retry">Retry</button>""")
        assertContains(page, """value="cancel">Cancel the Run</button>""")

        saveScript("flaky", own = listOf("TOKEN" to ""), was = "flaky", code = "ants.output({fixed: true})\n")
        browser.post(entry, mapOf("action" to "retry", "instruction" to ""))
        eventually { assertNotNull(runtime.getVariable(run, "flaky")) }
        assertEquals(mapOf("exitCode" to 0, "output" to "", "payload" to mapOf("fixed" to true)), runtime.getVariable(run, "flaky"))
    }

    @Test
    fun `a Script Task whose payload breaks the Script's output schema fails with the mismatch in its output, and Retry after fixing the Script completes it`() {
        val output = listOf("output[0].name" to "ts", "output[0].type" to "string", "output[0].required" to "on")
        saveScript("post-ts", output = output, code = "console.log('posted')\nants.output({ts: 12})\n")
        deploy("posting-ts", scriptTask("post", "post-ts"))
        val run = start("posting-ts")
        eventually { assertNotNull(incident(run)) }

        val entry = "/notifications/" + incident(run).id
        val page = browser.get(entry).body
        assertContains(page, "The payload of the Script post-ts breaks its output schema")
        assertContains(page, "posted\nThe payload breaks the Script's output schema:\n- /ts: integer found, string expected")

        saveScript("post-ts", output = output, was = "post-ts", code = "ants.output({ts: '12'})\n")
        browser.post(entry, mapOf("action" to "retry", "instruction" to ""))
        eventually { assertNotNull(runtime.getVariable(run, "post")) }
        assertEquals(mapOf("exitCode" to 0, "output" to "", "payload" to mapOf("ts" to "12")), runtime.getVariable(run, "post"))
    }

    @Test
    fun `a Script Task whose inputs break the Script's input schema fails before the Script runs, with the reason`() {
        saveScript("count-up", inputs = "count:number", code = "console.log('node ran')\nants.output({next: ants.inputs.count + 1})\n")
        deploy("counting", scriptTask("count", "count-up", mapOf("count" to "\${text}")))
        val run = start("counting", text = "three")
        eventually { assertNotNull(incident(run)) }
        val page = browser.get("/notifications/" + incident(run).id).body
        assertContains(page, "The inputs break the Script's input schema, so it didn't run:\n- /count: string found, number expected")
        assertContains(page, "count")
        assertFalse("node ran" in page)
    }

    @Test
    fun `a Secret added, changed or removed with the Secrets card's Save is used by the next Script Task, with the published code`() {
        val code = "ants.output({ran: 'published', token: process.env.TOKEN === 'tw0' ? 'new' : process.env.TOKEN === 'on3' ? 'old' : 'none', key: process.env.API_KEY === 'k3y'})\n"
        saveScript("keyed", own = listOf("TOKEN" to "on3"), code = code)
        assertEquals(200, browser.autosave("/scripts/keyed", listOf("name" to "keyed", "description" to "Does keyed.", "code" to "ants.output({ran: 'draft'})\n", "vars" to ""), "0").status)
        deploy("keying", scriptTask("keyed", "keyed"))

        val rows = listOf("secrets[0].name" to "TOKEN", "secrets[0].value" to "tw0", "secrets[0].was" to "TOKEN", "secrets[1].name" to "API_KEY", "secrets[1].value" to "k3y", "secrets[1].was" to "")
        assertEquals(200, browser.post("/scripts/keyed/secrets", rows).status)
        val changed = start("keying")
        eventually { assertNotNull(runtime.getVariable(changed, "keyed")) }
        assertEquals(mapOf("ran" to "published", "token" to "new", "key" to true), (runtime.getVariable(changed, "keyed") as Map<*, *>)["payload"])

        assertEquals(200, browser.post("/scripts/keyed/secrets", listOf("secrets[0].name" to "API_KEY", "secrets[0].value" to "", "secrets[0].was" to "API_KEY")).status)
        val removed = start("keying")
        eventually { assertNotNull(runtime.getVariable(removed, "keyed")) }
        assertEquals(mapOf("ran" to "published", "token" to "none", "key" to true), (runtime.getVariable(removed, "keyed") as Map<*, *>)["payload"])
    }

    @Test
    fun `a Script Task runs the published Script while the Script has a Draft, and Retry runs it as it is published then`() {
        saveScript("drafted", code = "console.log('published')\nprocess.exit(3)\n")
        val fixed = listOf("name" to "drafted", "description" to "Does drafted.", "code" to "ants.output({ran: 'draft'})\n", "vars" to "")
        assertEquals(200, browser.autosave("/scripts/drafted", fixed, "0").status)
        deploy("drafting", scriptTask("drafted", "drafted"))
        val run = start("drafting")
        eventually { assertNotNull(incident(run)) }
        val entry = "/notifications/" + incident(run).id
        assertContains(browser.get(entry).body, "published")

        assertEquals("/scripts/drafted", browser.post("/scripts/drafted", listOf("name" to "drafted", "description" to "Does drafted.", "code" to "ants.output({ran: 'published'})\n", "vars" to "")).path)
        browser.post(entry, mapOf("action" to "retry", "instruction" to ""))
        eventually { assertNotNull(runtime.getVariable(run, "drafted")) }
        assertEquals(mapOf("ran" to "published"), (runtime.getVariable(run, "drafted") as Map<*, *>)["payload"])
    }

    @Test
    fun `a Process can't name a Draft-only Script, so it never runs`() {
        val draft = browser.post("/scripts/new/draft", emptyMap()).path
        val named = browser.autosave(draft, listOf("name" to "unpublished", "description" to "Never published.", "code" to "ants.output({})\n", "vars" to ""), draftVersion(browser.get(draft).body))
        assertEquals("/scripts/unpublished", answer(named)["path"])
        val refused = browser.post("/processes/deploy", mapOf("bpmn" to deployable("unpublishing", scriptTask("never", "unpublished"))))
        assertContains(refused.body, "The Script unpublished is a Draft: publish it first.")
        browser.post("/scripts/unpublished/delete", mapOf("confirm" to "on"))
    }

    @Test
    fun `a literal typed into a Script Task's input reaches the Script as the input schema's type, and a text input keeps it as text`() {
        saveScript("echo-typed", inputs = "count:number, flag:boolean, tags:array:string, label", code = "ants.output(ants.inputs)\n")
        deploy("typed", scriptTask("echo", "echo-typed", mapOf("count" to "5", "flag" to "true", "tags" to """["a", "b"]""", "label" to "7")))
        val run = start("typed")
        eventually { assertNotNull(runtime.getVariable(run, "echo"), browser.get("/runs/$run").body) }
        assertEquals(mapOf("count" to 5, "flag" to true, "tags" to listOf("a", "b"), "label" to "7"), (runtime.getVariable(run, "echo") as Map<*, *>)["payload"])
    }

    @Test
    fun `a Script past its time limit fails its Task with what it printed`() {
        runner.timeLimit = Duration.ofSeconds(1)
        try {
            saveScript("slow", code = "console.log('started')\nsetTimeout(() => console.log('finished'), 30000)\n")
            deploy("slow-run", scriptTask("slow", "slow"))
            val run = start("slow-run")
            eventually { assertNotNull(incident(run)) }
            val page = browser.get("/notifications/" + incident(run).id).body
            assertContains(page, "started")
            assertContains(page, "ran past its time limit of 1s, so Ants killed it.")
            assertTrue("finished" !in page)
        } finally {
            runner.timeLimit = Duration.ofMinutes(10)
        }
    }

    @Test
    fun `renaming the Script leaves the Process working, its page shows the Process using it, and the catalogue gives its new name`() {
        saveScript("greet", inputs = "text", code = "ants.output({greeted: true})\n")
        deploy("greeting", scriptTask("greet", "greet", mapOf("text" to "\${text}")))
        val id = Regex("""name="ants.script" value="(s-[a-z0-9]{6})"""").find(deployed("greeting"))!!.groupValues[1]
        assertContains(deployed("greeting"), """name="ants.name.$id" value="greet"""")

        saveScript("say-hello", inputs = "text", was = "greet", code = "ants.output({greeted: true})\n")
        assertContains(browser.get("/scripts/say-hello").body, """<a href="/processes/greeting">greeting</a>""")
        val catalogue = json.readValue(browser.get("/catalogue.json").body, Map::class.java)
        assertEquals(mapOf("id" to id, "name" to "say-hello", "description" to "Does say-hello.", "inputs" to listOf("text"),
            "about" to mapOf("text" to mapOf("type" to "text", "description" to "", "required" to false)), "draft" to null), (catalogue["scripts"] as List<*>).single { (it as Map<*, *>)["id"] == id })

        val run = start("greeting")
        eventually { assertNotNull(runtime.getVariable(run, "greet")) }
        assertEquals(mapOf("greeted" to true), (runtime.getVariable(run, "greet") as Map<*, *>)["payload"])
    }

    @Test
    fun `deleting a Script confirms with the Processes that use it`() {
        saveScript("cleanup", code = "console.log('clean')\n")
        deploy("cleaning", scriptTask("clean", "cleanup"))
        val confirm = browser.post("/scripts/cleanup/delete", emptyMap())
        assertContains(confirm.body, "Delete the Script cleanup?")
        assertContains(confirm.body, """<a href="/processes/cleaning">cleaning</a>""")
        browser.post("/scripts/cleanup/delete", mapOf("confirm" to "on"))
        assertEquals(404, browser.get("/scripts/cleanup").status)
    }

    @Test
    fun `an imported Script Task whose id is unknown here links the Script by the name kept beside it`() {
        saveScript("archive", code = "console.log('archived')\n")
        val shared = scriptTask("store", "s-zz9999").replace("""<operaton:property name="ants.script" value="s-zz9999" />""",
            """<operaton:property name="ants.script" value="s-zz9999" /><operaton:property name="ants.name.s-zz9999" value="archive" />""")
        deploy("archiving", shared)
        val id = Regex("""name="ants.script" value="(s-[a-z0-9]{6})"""").find(deployed("archiving"))!!.groupValues[1]
        assertEquals(id, json.readValue(browser.get("/catalogue.json").body, Map::class.java)["scripts"].let { it as List<*> }.map { it as Map<*, *> }.single { it["name"] == "archive" }["id"])
        assertFalse("value=\"s-zz9999\"" in deployed("archiving"))
        val run = start("archiving")
        eventually { assertNotNull(runtime.getVariable(run, "store")) }
    }
}
