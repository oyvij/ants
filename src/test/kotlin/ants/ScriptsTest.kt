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

/** An input schema declaring [names], each any JSON value and optional. */
fun untypedInputs(vararg names: String): Map<String, Any> = mapOf("type" to "object", "properties" to names.associateWith { emptyMap<String, Any>() })

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
    ): List<Pair<String, String>> = listOf("name" to name, "description" to description, "code" to code, "vars" to vars) + inputRows(inputs) +
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
                "id" to folder.fileName.toString(), "name" to "post-message", "description" to "Posts a message.",
                "inputs" to mapOf("type" to "object", "properties" to mapOf("channel" to mapOf("type" to "string"), "text" to mapOf("type" to "string"))),
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
    fun `a new Script's editor shows a placeholder reading ants inputs, vars and Secrets, and handing back with ants output`() {
        val page = browser.get("/scripts/new").body.replace("&#34;", "\"").replace("&#39;", "'")
        assertContains(page, "data-code-editor")
        assertContains(page, "ants.inputs")
        assertContains(page, "process.env.")
        assertContains(page, "ants.output(")
        assertFalse("process.stdin" in page)
        assertFalse("ANTS_OUTPUT" in page)
    }

    @Test
    fun `a Test run gets its inputs as ants inputs and its vars, Secrets and ANTS_ variables in the environment, masks every Secret, and leaves no scratch folder`() {
        create(form("post-message", own = listOf("SLACK_TOKEN" to "xoxb-s3cret")))
        val code = """
            const inputs = ants.inputs
            const e = process.env
            await new Promise(resolve => setTimeout(resolve, 10))
            console.log(JSON.stringify({inputs, channel: e.CHANNEL, slack: e.SLACK_TOKEN, npm: e.NPM_TOKEN, maven: e.MAVEN_TOKEN ?? null,
                workspace: e.ANTS_RUN_WORKSPACE, run: e.ANTS_RUN_ID, cwd: process.cwd(), path: e.PATH === undefined ? null : 'set',
                key: e.ANTHROPIC_API_KEY ?? null, output: e.ANTS_OUTPUT ?? null}))
            console.error('on stderr')
            ants.output({posted: inputs.text, token: e.SLACK_TOKEN})
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
        assertNull(printed["output"])
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
    fun `a Test run's payload is empty when the Script hands back none, the last ants output wins, and a value JSON can't hold fails the run`() {
        val none = testRun(form("quiet", code = "console.log('done')\n"))
        assertEquals(0, none["exitCode"])
        assertEquals("done\n", none["output"])
        assertNull(none["payload"])

        val twice = testRun(form("twice", code = "ants.output({first: true})\nconsole.log('{\"logged\": true}')\nants.output({second: true})\n"))
        assertEquals(0, twice["exitCode"])
        assertEquals("{\"logged\": true}\n", twice["output"])
        assertEquals(mapOf("second" to true), twice["payload"])

        for ((name, value) in listOf("big" to "10n", "circular" to "(() => { const a = {}; a.self = a; return a })()", "function" to "() => 1")) {
            val broken = testRun(form(name, code = "console.log('before')\nants.output($value)\n"))
            assertTrue(broken["exitCode"] != 0, name)
            assertContains(broken["output"].toString(), "before\n")
            assertContains(broken["output"].toString(), "ants.output got a value JSON can't hold, so the run failed")
            assertNull(broken["payload"])
        }
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

    @Test
    fun `saving a Script writes its input rows as an object JSON Schema, nested rows as nested properties, and opening it shows the same rows`() {
        val rows = listOf(
            "inputs[0].name" to "text", "inputs[0].type" to "string", "inputs[0].description" to "What to post.", "inputs[0].required" to "on",
            "inputs[1].name" to "ticket", "inputs[1].type" to "object", "inputs[1].description" to "",
            "inputs[1].rows[0].name" to "title", "inputs[1].rows[0].type" to "string", "inputs[1].rows[0].description" to "", "inputs[1].rows[0].required" to "on",
            "inputs[1].rows[1].name" to "labels", "inputs[1].rows[1].type" to "array:string", "inputs[1].rows[1].description" to "Its labels.",
            "inputs[2].name" to "comments", "inputs[2].type" to "array:object", "inputs[2].description" to "",
            "inputs[2].rows[0].name" to "body", "inputs[2].rows[0].type" to "string", "inputs[2].rows[0].description" to "", "inputs[2].rows[0].required" to "on",
            "inputs[2].rows[1].name" to "votes", "inputs[2].rows[1].type" to "number", "inputs[2].rows[1].description" to "",
            "inputs[3].name" to "", "inputs[3].type" to "string", "inputs[3].description" to "", // a row left blank is no input
            "inputs[4].name" to "urgent", "inputs[4].type" to "boolean", "inputs[4].description" to "",
            "inputs[0].rows[0].name" to "ignored", "inputs[0].rows[0].type" to "string", // rows under a text input are no fields
        )
        create(form("post-ticket") + rows)
        assertEquals(
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "text" to mapOf("type" to "string", "description" to "What to post."),
                    "ticket" to mapOf(
                        "type" to "object",
                        "properties" to mapOf("title" to mapOf("type" to "string"), "labels" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Its labels.")),
                        "required" to listOf("title"),
                    ),
                    "comments" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "object", "properties" to mapOf("body" to mapOf("type" to "string"), "votes" to mapOf("type" to "number")), "required" to listOf("body")),
                    ),
                    "urgent" to mapOf("type" to "boolean"),
                ),
                "required" to listOf("text"),
            ),
            definition(folder("post-ticket"))["inputs"],
        )

        val page = browser.get("/scripts/post-ticket").body
        assertContains(page, """name="inputs[0].name" value="text"""")
        assertContains(page, """name="inputs[0].description" value="What to post."""")
        assertTrue(Regex("""name="inputs\[0]\.required"[^>]*checked""").containsMatchIn(page))
        assertFalse(Regex("""name="inputs\[1]\.required"[^>]*checked""").containsMatchIn(page))
        assertContains(page, """name="inputs[1].rows[1].name" value="labels"""")
        assertTrue(Regex("""name="inputs\[1]\.rows\[1]\.type"(?:(?!</select>).)*value="array:string" selected""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(page))
        assertContains(page, """name="inputs[2].rows[1].name" value="votes"""")
        assertTrue(Regex("""name="inputs\[3]\.type"(?:(?!</select>).)*value="boolean" selected""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(page))

        val saved = definition(folder("post-ticket"))["inputs"]
        browser.post("/scripts/post-ticket", form("post-ticket") + rows)
        assertEquals(saved, definition(folder("post-ticket"))["inputs"])
    }

    @Test
    fun `saving a Script keeps what its schema file holds beyond the rows the page edits`() {
        val folder = scripts.resolve("s-kept01").also { it.toFile().mkdirs() }
        folder.resolve("script.mjs").writeText("console.log('hi')\n")
        val inputs = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "mode" to mapOf("type" to "string", "enum" to listOf("fast", "slow"), "description" to "How."),
                "count" to mapOf("type" to "integer", "minimum" to 1),
                "anything" to emptyMap<String, Any>(),
            ),
            "required" to listOf("mode"),
            "additionalProperties" to false,
        )
        folder.resolve("script.yaml").writeText(yaml.dump(mapOf("id" to "s-kept01", "name" to "kept", "description" to "Keeps.", "inputs" to inputs, "vars" to emptyMap<String, String>(), "secrets" to emptyList<String>())))

        val page = browser.get("/scripts/kept").body
        assertContains(page, """name="inputs[0].name" value="mode"""")
        assertContains(page, """name="inputs[1].name" value="count"""")
        assertContains(page, """name="inputs[2].name" value="anything"""")
        val asShown = listOf(
            "inputs[0].name" to "mode", "inputs[0].type" to "string", "inputs[0].description" to "How.", "inputs[0].required" to "on",
            "inputs[1].name" to "count", "inputs[1].type" to "number", "inputs[1].description" to "",
            "inputs[2].name" to "anything", "inputs[2].type" to "string", "inputs[2].description" to "",
        )
        browser.post("/scripts/kept", form("kept", description = "Keeps.") + asShown)
        assertEquals(inputs, definition(folder)["inputs"])

        browser.post("/scripts/kept", form("kept", description = "Keeps.") + listOf("inputs[0].name" to "mode", "inputs[0].type" to "number", "inputs[0].description" to ""))
        assertEquals(mapOf("type" to "object", "properties" to mapOf("mode" to mapOf("type" to "number")), "additionalProperties" to false), definition(folder)["inputs"])
    }

    @Test
    fun `an input's name, nested ones too, is letters, digits, hyphens and underscores`() {
        val nested = listOf("inputs[0].name" to "ticket", "inputs[0].type" to "object", "inputs[0].rows[0].name" to "the title", "inputs[0].rows[0].type" to "string")
        assertContains(create(form("bad-input") + nested).body, "An input's name is letters, digits, hyphens and underscores, not the title.")
        assertFalse(scripts.exists() && scripts.listDirectoryEntries().isNotEmpty())
    }

    @Test
    fun `a Test run whose sample inputs break the input schema is refused before Node starts, naming each problem`() {
        val rows = listOf(
            "inputs[0].name" to "text", "inputs[0].type" to "string", "inputs[0].required" to "on",
            "inputs[1].name" to "count", "inputs[1].type" to "number",
            "inputs[2].name" to "ticket", "inputs[2].type" to "object", "inputs[2].rows[0].name" to "title", "inputs[2].rows[0].type" to "string", "inputs[2].rows[0].required" to "on",
        )
        val code = "console.log('node ran')\nants.output(ants.inputs)\n"
        val refused = testRun(form("strict", code = code) + rows, sample = """{"count": "three", "ticket": {}}""")
        assertTrue(refused["exitCode"] != 0)
        val output = refused["output"].toString()
        assertFalse("node ran" in output)
        assertContains(output, "The inputs break the Script's input schema, so it didn't run")
        assertContains(output, "text")
        assertContains(output, "count")
        assertContains(output, "title")
        assertNull(refused["payload"])

        val accepted = testRun(form("strict", code = code) + rows, sample = """{"text": "hi", "count": 3, "ticket": {"title": "Bug"}}""")
        assertEquals(0, accepted["exitCode"], accepted["output"].toString())
        assertEquals(mapOf("text" to "hi", "count" to 3, "ticket" to mapOf("title" to "Bug")), accepted["payload"])
    }

    companion object {
        /** The schema card's rows for [spec], inputs such as `text, count:number`, each optional and with no description, as the page posts them. */
        fun inputRows(spec: String): List<Pair<String, String>> = spec.split(',').map(String::trim).filter(String::isNotEmpty).withIndex().flatMap { (i, input) ->
            listOf("inputs[$i].name" to input.substringBefore(':'), "inputs[$i].type" to input.substringAfter(':', "string"), "inputs[$i].description" to "")
        }

        /** The `node` the build fetched (`node { download = true }`), as the tests' PATH holds no other. */
        val NODE: Path = Path.of(".gradle/nodejs").listDirectoryEntries().single().resolve("bin/node").toAbsolutePath()
    }
}
