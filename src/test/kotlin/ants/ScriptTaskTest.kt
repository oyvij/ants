package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
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

    private fun saveScript(name: String, code: String, inputs: String = "", vars: String = "", own: List<Pair<String, String>> = emptyList(), linked: List<String> = emptyList(), was: String? = null) {
        val form = listOf("name" to name, "description" to "Does $name.", "code" to code, "inputs" to inputs, "vars" to vars) +
            own.withIndex().flatMap { (i, secret) -> listOf("secrets[$i].name" to secret.first, "secrets[$i].value" to secret.second, "secrets[$i].was" to if (was == null) "" else secret.first) } +
            linked.map { "linked" to it }
        browser.get("/scripts/${was ?: "new"}")
        val page = browser.post("/scripts/${was ?: "new"}", form)
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
        val ids = tasks.map { Regex("""id="([^"]+)"""").find(it)!!.groupValues[1] }
        val steps = listOf("start") + ids + "wait"
        val flows = steps.zipWithNext().withIndex().joinToString("\n") { (i, pair) -> """<bpmn:sequenceFlow id="f$i" sourceRef="${pair.first}" targetRef="${pair.second}" />""" }
        val bpmn = """
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
        val deployed = browser.post("/processes/deploy", mapOf("bpmn" to bpmn))
        assertEquals("/processes/$key", deployed.path, deployed.body)
        return key
    }

    private fun start(key: String, text: String = "hello"): String {
        browser.get("/processes/$key")
        val started = browser.post("/processes/$key/runs", mapOf("text" to text, NAME to ""))
        assertTrue(started.path.startsWith("/runs/"), Regex("role=\"alert\"[^<]*|class=\"warn\"[^<]*").findAll(started.body).joinToString { it.value })
        return started.path.removePrefix("/runs/")
    }

    private fun incident(run: String) = runtime.createIncidentQuery().processInstanceId(run).singleResult()

    @Test
    fun `a Script Task runs its Script in the Run workspace, with its inputs on stdin and its vars, Secrets and ANTS_ variables in the environment, and a later Task reads its Result`() {
        saveScript(
            "post-message", inputs = "text", vars = "CHANNEL=general", own = listOf("SLACK_TOKEN" to "xoxb-s3cret"), linked = listOf("npm"),
            code = """
                import {readFileSync, writeFileSync} from 'node:fs'
                const inputs = JSON.parse(readFileSync(0, 'utf8'))
                const e = process.env
                console.log('posting', inputs.text, 'with', e.SLACK_TOKEN)
                writeFileSync(e.ANTS_OUTPUT, JSON.stringify({inputs, channel: e.CHANNEL, slack: e.SLACK_TOKEN, npm: e.NPM_TOKEN,
                    workspace: e.ANTS_RUN_WORKSPACE, run: e.ANTS_RUN_ID, cwd: process.cwd()}))
            """.trimIndent(),
        )
        saveScript("echo", inputs = "posted, code", code = "import {readFileSync, writeFileSync} from 'node:fs'\nwriteFileSync(process.env.ANTS_OUTPUT, readFileSync(0, 'utf8'))\n")
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

        saveScript("flaky", own = listOf("TOKEN" to ""), was = "flaky", code = "import {writeFileSync} from 'node:fs'\nwriteFileSync(process.env.ANTS_OUTPUT, JSON.stringify({fixed: true}))\n")
        browser.post(entry, mapOf("action" to "retry", "instruction" to ""))
        eventually { assertNotNull(runtime.getVariable(run, "flaky")) }
        assertEquals(mapOf("exitCode" to 0, "output" to "", "payload" to mapOf("fixed" to true)), runtime.getVariable(run, "flaky"))
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
}
