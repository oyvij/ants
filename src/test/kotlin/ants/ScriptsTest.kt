package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scripts: the user's library of JavaScript in `ANTS_HOME/scripts/<id>/`, edited on the Scripts pages, and a Test run
 * of the unsaved code through the one runner every caller uses, on the real `node` the build fetched.
 */
class ScriptsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val json = JsonMapper.shared()
    private val scripts get() = home.resolve("scripts")
    private val secrets = home.resolve("secrets.yaml")

    @Autowired
    lateinit var runner: ScriptRunner

    @BeforeEach
    fun settings() {
        home.resolve("settings.yaml").writeText(
            "node: {path: $NODE}\nclaude: {env: [PATH, HOME]}\n" +
                "secrets:\n  npm: [NPM_TOKEN]\n  maven: [MAVEN_TOKEN]\n",
        )
        secrets.writeText("npm: s3cret-npm\nmaven: s3cret-mvn\n")
    }

    @AfterEach
    fun noSecrets() {
        secrets.deleteIfExists()
    }

    /** The form of a Script named [name], as its page posts it. */
    private fun form(
        name: String,
        code: String = "console.log('hi')\n",
        description: String = "Posts a message.",
        inputs: String = "",
        vars: String = "",
        own: List<Pair<String, String>> = emptyList(),
        linked: List<String> = emptyList(),
    ): List<Pair<String, String>> = listOf("name" to name, "description" to description, "code" to code, "inputs" to inputs, "vars" to vars) +
        own.withIndex().flatMap { (i, secret) -> listOf("secrets[$i].name" to secret.first, "secrets[$i].value" to secret.second, "secrets[$i].was" to "") } +
        linked.map { "linked" to it }

    private fun create(form: List<Pair<String, String>>): Browser.Page {
        browser.get("/scripts/new")
        return browser.post("/scripts/new", form)
    }

    private fun folder(name: String): Path = scripts.listDirectoryEntries().single { definition(it)["name"] == name }

    private fun definition(folder: Path): Map<*, *> = Yaml().load(folder.resolve("script.yaml").readText())

    private fun testRun(form: List<Pair<String, String>>, sample: String = "{}", script: String? = null): Map<*, *> {
        browser.get("/scripts/new")
        val page = browser.post("/scripts/test-run", form + listOf("sample" to sample) + listOfNotNull(script?.let { "script" to it }))
        assertEquals(200, page.status, page.body)
        return json.readValue(page.body, Map::class.java)
    }

    @Test
    fun `Scripts is in the nav, and its list shows each Script's name and description`() {
        assertContains(browser.get("/").body, """<a href="/scripts">""")
        create(form("post-message"))
        create(form("reshape", description = "Reshapes a JSON file."))
        val list = browser.get("/scripts").body
        assertContains(list, """<a href="/scripts/post-message">post-message</a>""")
        assertContains(list, "Posts a message.")
        assertContains(list, """<a href="/scripts/reshape">reshape</a>""")
        assertContains(list, "Reshapes a JSON file.")
        assertFalse(Regex("s-[a-z0-9]{6}").containsMatchIn(list))
    }

    @Test
    fun `creating a Script writes its code and definition under a new s- id, and its own Secret values only to secrets yaml`() {
        val page = create(
            form(
                "post-message", code = "console.log(process.env.SLACK_TOKEN)\n", inputs = "channel, text",
                vars = "CHANNEL=general\nURL=https://example.com/?a=b", own = listOf("SLACK_TOKEN" to "xoxb-s3cret"), linked = listOf("npm"),
            ),
        )
        assertEquals("/scripts/post-message", page.path)
        val folder = folder("post-message")
        assertTrue(Regex("s-[a-z0-9]{6}").matches(folder.fileName.toString()))
        assertEquals("console.log(process.env.SLACK_TOKEN)\n", folder.resolve("script.mjs").readText())
        assertEquals(
            mapOf(
                "id" to folder.fileName.toString(), "name" to "post-message", "description" to "Posts a message.", "inputs" to listOf("channel", "text"),
                "vars" to mapOf("CHANNEL" to "general", "URL" to "https://example.com/?a=b"), "secrets" to listOf("SLACK_TOKEN", "npm"),
            ),
            definition(folder),
        )
        assertEquals(mapOf("SLACK_TOKEN" to "xoxb-s3cret"), Yaml().load<Map<*, *>>(folder.resolve("secrets.yaml").readText()))
        assertFalse("xoxb-s3cret" in folder.resolve("script.yaml").readText())
        assertFalse("xoxb-s3cret" in page.body)
        assertFalse(folder.fileName.toString() in page.body)

        val shown = browser.get("/scripts/post-message").body // without JS: the saved Secret, and one empty row to add one
        assertContains(shown, """name="secrets[0].name" value="SLACK_TOKEN"""")
        assertContains(shown, """name="secrets[1].name" value=""""")
        assertFalse("secrets[2]" in shown)

        browser.post("/scripts/post-message", form("post-message", inputs = "channel", own = listOf("SLACK_TOKEN" to "")))
        assertEquals(mapOf("SLACK_TOKEN" to "xoxb-s3cret"), Yaml().load<Map<*, *>>(folder.resolve("secrets.yaml").readText()))
        browser.post("/scripts/post-message", form("post-message"))
        assertFalse(folder.resolve("secrets.yaml").exists())
    }

    @Test
    fun `a Script needs a valid name no other Script has`() {
        create(form("post-message"))
        assertContains(create(form("post-message")).body, "A Script named post-message already exists.")
        assertContains(create(form("Not A Name")).body, "A Script's name is up to 64 lowercase letters, digits and hyphens.")
        assertContains(create(form("bad-var", vars = "lower=1")).body, "A var or Secret's name is upper case letters, digits and underscores")
        assertContains(create(form("no-value", own = listOf("SLACK_TOKEN" to ""))).body, "The Secret SLACK_TOKEN needs a value.")
        assertEquals(1, scripts.listDirectoryEntries().size)
    }

    @Test
    fun `renaming a Script changes only its name, with no confirm, and deleting one confirms`() {
        create(form("post-message", inputs = "text"))
        val folder = folder("post-message")
        val renamed = browser.post("/scripts/post-message", form("send-message", inputs = "text"))
        assertEquals("/scripts/send-message", renamed.path)
        assertEquals(folder, folder("send-message"))
        assertEquals("send-message", definition(folder)["name"])
        assertEquals(folder.fileName.toString(), definition(folder)["id"])

        val confirm = browser.post("/scripts/send-message/delete", emptyMap())
        assertContains(confirm.body, "Delete the Script send-message?")
        assertTrue(folder.exists())
        browser.post("/scripts/send-message/delete", mapOf("confirm" to "on"))
        assertFalse(folder.exists())
        assertFalse("send-message" in browser.get("/scripts").body)
    }

    @Test
    fun `deleting a Script confirms with the Agents that list it and the Processes whose Agent Tasks narrow to it`() {
        create(form("post-message"))
        val id = folder("post-message").fileName.toString()
        agentTask(null, agent = "scripts: [$id]\n")
        home.resolve("agents/bystander.md").writeText("---\nname: bystander\n---\nWatch.\n")
        val bpmn = javaClass.getResource("/agent-task.bpmn")!!.readText()
            .replace("""<operaton:property name="ants.skills" value="draft" />""", """<operaton:property name="ants.skills" value="draft" /><operaton:property name="ants.scripts" value="post-message" />""")
        browser.get("/processes")
        assertFalse("role=\"alert\"" in browser.upload("/processes", "file", "agent-task.bpmn", bpmn.toByteArray()).body)

        val confirm = browser.post("/scripts/post-message/delete", emptyMap()).body
        assertContains(confirm, """Agent <a href="/agents/writer">writer</a>""")
        assertContains(confirm, """Process <a href="/processes/agent-task">Agent Task</a>""")
        assertFalse("bystander" in confirm)
        assertContains(browser.get("/scripts/post-message").body, """<a href="/agents/writer">writer</a>""")
    }

    @Test
    fun `the Script page in the modeler's modal has no nav, and saving there opens the saved Script`() {
        val modal = browser.get("/scripts/new?use").body
        assertContains(modal, """<input type="hidden" name="use" value="">""")
        assertFalse("""<a href="/scripts">""" in modal)
        val created = browser.post("/scripts/new", form("in-modal") + ("use" to ""))
        assertEquals("/scripts/in-modal", created.path)
        assertEquals("saved", created.query)
        val renamed = browser.post("/scripts/in-modal", form("renamed-in-modal") + ("use" to ""))
        assertEquals("/scripts/renamed-in-modal", renamed.path)
        assertEquals("saved", renamed.query)
        assertEquals(null, browser.post("/scripts/renamed-in-modal", form("renamed-in-modal")).query)
        assertContains(browser.post("/scripts/new", form("Bad Name") + ("use" to "")).body, """<input type="hidden" name="use" value="">""")
    }

    @Test
    fun `a new Script's editor shows a placeholder reading inputs, vars and Secrets, and writing the payload`() {
        val page = browser.get("/scripts/new").body.replace("&#34;", "\"").replace("&#39;", "'")
        assertContains(page, "data-code-editor")
        assertContains(page, "process.stdin")
        assertContains(page, "process.env.")
        assertContains(page, "process.env.ANTS_OUTPUT")
    }

    @Test
    fun `a Test run gets its inputs on stdin and its vars, Secrets and ANTS_ variables in the environment, masks every Secret, and leaves no scratch folder`() {
        create(form("post-message", own = listOf("SLACK_TOKEN" to "xoxb-s3cret")))
        val code = """
            import {readFileSync, writeFileSync} from 'node:fs'
            const inputs = JSON.parse(readFileSync(0, 'utf8'))
            const e = process.env
            console.log(JSON.stringify({inputs, channel: e.CHANNEL, slack: e.SLACK_TOKEN, npm: e.NPM_TOKEN, maven: e.MAVEN_TOKEN ?? null,
                workspace: e.ANTS_RUN_WORKSPACE, run: e.ANTS_RUN_ID, cwd: process.cwd(), path: e.PATH === undefined ? null : 'set',
                key: e.ANTHROPIC_API_KEY ?? null}))
            console.error('on stderr')
            writeFileSync(e.ANTS_OUTPUT, JSON.stringify({posted: inputs.text, token: e.SLACK_TOKEN}))
        """.trimIndent()
        val result = testRun(
            form("post-message", code = code, inputs = "text", vars = "CHANNEL=general", own = listOf("SLACK_TOKEN" to ""), linked = listOf("npm")),
            sample = """{"text": "hello"}""", script = "post-message",
        )

        assertEquals(0, result["exitCode"])
        val output = result["output"].toString()
        val printed = json.readValue(output.lines().first(), Map::class.java)
        assertEquals(mapOf("text" to "hello"), printed["inputs"])
        assertEquals("general", printed["channel"])
        assertEquals("••••", printed["slack"])
        assertEquals("••••", printed["npm"])
        assertNull(printed["maven"])
        assertNull(printed["key"])
        assertEquals("set", printed["path"])
        assertEquals("test-run", printed["run"])
        assertEquals(printed["cwd"], printed["workspace"])
        assertContains(output, "on stderr")
        assertFalse("s3cret" in output)
        assertEquals(mapOf("posted" to "hello", "token" to "••••"), result["payload"])
        assertFalse(Path.of(printed["cwd"].toString()).exists())
        assertEquals(setOf("script.mjs", "script.yaml", "secrets.yaml"), folder("post-message").listDirectoryEntries().map { it.fileName.toString() }.toSet())
        assertEquals("console.log('hi')\n", folder("post-message").resolve("script.mjs").readText())
    }

    @Test
    fun `a Test run's payload is empty when the Script wrote none, and a payload that isn't JSON fails the run`() {
        val none = testRun(form("quiet", code = "console.log('done')\n"))
        assertEquals(0, none["exitCode"])
        assertEquals("done\n", none["output"])
        assertNull(none["payload"])

        val broken = testRun(form("broken", code = "import {writeFileSync} from 'node:fs'\nwriteFileSync(process.env.ANTS_OUTPUT, 'not json')\n"))
        assertTrue(broken["exitCode"] != 0)
        assertContains(broken["output"].toString(), "ANTS_OUTPUT holds no JSON")
        assertNull(broken["payload"])
    }

    @Test
    fun `a Test run past the time limit is killed and fails`() {
        runner.timeLimit = Duration.ofSeconds(1)
        try {
            val result = testRun(form("slow", code = "console.log('started')\nsetTimeout(() => console.log('finished'), 30000)\n"))
            assertTrue(result["exitCode"] != 0)
            assertContains(result["output"].toString(), "started")
            assertFalse("finished" in result["output"].toString())
            assertContains(result["output"].toString(), "ran past its time limit of 1s, so Ants killed it.")
        } finally {
            runner.timeLimit = Duration.ofMinutes(10)
        }
    }

    @Test
    fun `a Script that imports an npm package fails with Node's own error`() {
        val result = testRun(form("needs-a-package", code = "import chalk from 'chalk'\nconsole.log(chalk.red('hi'))\n"))
        assertTrue(result["exitCode"] != 0)
        assertContains(result["output"].toString(), "ERR_MODULE_NOT_FOUND")
        assertContains(result["output"].toString(), "Cannot find package 'chalk'")
    }

    companion object {
        /** The `node` the build fetched (`node { download = true }`), as the tests' PATH holds no other. */
        val NODE: Path = Path.of(".gradle/nodejs").listDirectoryEntries().single().resolve("bin/node").toAbsolutePath()
    }
}
