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

    @BeforeEach
    fun noDrafts() {
        home.resolve("drafts/scripts").toFile().deleteRecursively() // ANTS_HOME's scripts folder starts empty for each test, so its Drafts do too
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

    @Test
    fun `without JavaScript the Script page shows both schemas' rows, so an output schema can be seen and edited`() {
        create(form("posts", inputs = "text") + listOf("output[0].name" to "ts", "output[0].type" to "string", "output[0].description" to ""))
        val page = browser.get("/scripts/posts").body
        val panels = Regex("""<div role="tabpanel" id="(input|output)-schema"[^>]*>""").findAll(page).map { it.value }.toList()
        assertEquals(2, panels.size, page)
        assertTrue(panels.none { " hidden" in it }, panels.toString())
        assertContains(page, """name="output[0].name" value="ts"""")
    }

    @Test
    fun `saving a Script writes its output rows as output in script yaml, opening it shows them, and with none the tab says any payload is accepted`() {
        create(form("no-output"))
        assertFalse("output" in definition(folder("no-output")))
        assertTrue(Regex("""<p[^>]*data-no-output[^>]*>No output schema: any JSON payload is accepted.""").containsMatchIn(browser.get("/scripts/no-output").body))
        assertFalse(Regex("""<p[^>]* hidden[^>]*>No output schema""").containsMatchIn(browser.get("/scripts/no-output").body))

        val rows = listOf(
            "output[0].name" to "ts", "output[0].type" to "string", "output[0].description" to "When it was posted.", "output[0].required" to "on",
            "output[1].name" to "user", "output[1].type" to "object", "output[1].description" to "",
            "output[1].rows[0].name" to "id", "output[1].rows[0].type" to "number", "output[1].rows[0].description" to "",
        )
        create(form("posts", inputs = "text") + rows)
        val definition = definition(folder("posts"))
        assertEquals(
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "ts" to mapOf("type" to "string", "description" to "When it was posted."),
                    "user" to mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "number"))),
                ),
                "required" to listOf("ts"),
            ),
            definition["output"],
        )
        val page = browser.get("/scripts/posts").body
        assertContains(page, """name="output[0].name" value="ts"""")
        assertContains(page, """name="output[0].description" value="When it was posted."""")
        assertTrue(Regex("""name="output\[0]\.required"[^>]*checked""").containsMatchIn(page))
        assertContains(page, """name="output[1].rows[0].name" value="id"""")
        assertContains(page, """name="inputs[0].name" value="text"""")
        assertTrue(Regex("""<p[^>]* hidden[^>]*>No output schema""").containsMatchIn(page))

        browser.post("/scripts/posts", form("posts", inputs = "text"))
        assertFalse("output" in definition(folder("posts")))
    }

    @Test
    fun `a Test run whose payload breaks the output schema fails, naming each mismatch, and one with no output schema accepts any payload`() {
        val output = listOf(
            "output[0].name" to "ts", "output[0].type" to "string", "output[0].required" to "on",
            "output[1].name" to "user", "output[1].type" to "object", "output[1].rows[0].name" to "id", "output[1].rows[0].type" to "number",
        )
        val code = "console.log('ran')\nants.output({user: {id: 'seven'}})\n"
        val broken = testRun(form("checked", code = code) + output)
        assertEquals(0, broken["exitCode"])
        assertContains(broken["output"].toString(), "ran")
        assertEquals(mapOf("user" to mapOf("id" to "seven")), broken["payload"])
        val mismatches = broken["mismatches"] as List<*>
        assertEquals(2, mismatches.size, mismatches.toString())
        assertTrue(mismatches.any { "ts" in it.toString() }, mismatches.toString())
        assertTrue(mismatches.any { "/user/id" in it.toString() }, mismatches.toString())

        val nothing = testRun(form("checked", code = "console.log('none')\n") + output)
        assertEquals(1, (nothing["mismatches"] as List<*>).size, nothing.toString())

        val fits = testRun(form("checked", code = "ants.output({ts: '1.2', user: {id: 7}})\n") + output)
        assertEquals(emptyList<Any>(), fits["mismatches"])

        val free = testRun(form("free", code = "ants.output([1, 'two'])\n"))
        assertEquals(0, free["exitCode"])
        assertEquals(listOf(1, "two"), free["payload"])
        assertEquals(emptyList<Any>(), free["mismatches"])
    }

    /** What the validator finds in the page's unsaved [form], each as `severity line-or-row: message`. */
    private fun check(form: List<Pair<String, String>>): List<String> {
        browser.get("/scripts/new")
        val page = browser.post("/script-check", form)
        assertEquals(200, page.status, page.body)
        return (json.readValue(page.body, Map::class.java)["findings"] as List<*>).map {
            val finding = it as Map<*, *>
            "${finding["severity"]} ${finding["line"] ?: finding["row"]}: ${finding["message"]}"
        }
    }

    @Test
    fun `the validator marks a syntax error on its line with Node's message`() {
        assertEquals(listOf("error 2: SyntaxError: Unexpected token ';'"), check(form("broken", code = "const a = 1\nconst x = ;\n")))
        assertEquals(emptyList(), check(form("fine", code = "const a = await Promise.resolve(1)\nconsole.log(a)\n")))
    }

    @Test
    fun `without a node to run the validator warns so, and still marks what the code reads against the schemas`() {
        home.resolve("settings.yaml").writeText("node: {path: ${home.resolve("no-such-node")}}\nclaude: {env: [PATH, HOME]}\n")
        val findings = check(form("typo", code = "console.log(ants.inputs.txt)\n", inputs = "txt2"))
        assertEquals(3, findings.size, findings.toString())
        assertTrue(findings[0].startsWith("warning null: Ants can't run node to check the syntax"), findings.toString())
        assertEquals(
            listOf("error 1: ants.inputs.txt reads an input the input schema doesn't declare.", "warning inputs[0]: The input txt2 is never read."),
            findings.drop(1),
        )
    }

    @Test
    fun `the validator marks a read of an undeclared input as an error on its line, and a declared input never read as a warning on its row`() {
        val code = "// ants.inputs.commented is no read\nconst greeting = `hi ${'$'}{ants.inputs.txt}`\nconsole.log(greeting, ants.inputs.channel, 'ants.inputs.quoted')\n"
        assertEquals(
            listOf("error 2: ants.inputs.txt reads an input the input schema doesn't declare.", "warning inputs[0]: The input text is never read."),
            check(form("typo", code = code, inputs = "text, channel")),
        )
    }

    @Test
    fun `the validator marks an undeclared payload field as an error on its line, and a required one never written as a warning on its row`() {
        val output = listOf(
            "output[0].name" to "ts", "output[0].type" to "string", "output[0].required" to "on",
            "output[1].name" to "posted", "output[1].type" to "boolean",
        )
        val code = "const ok = true\nants.output({\n  posted: ok,\n  'channel': 'general',\n  user: {id: 7, ts: 1},\n})\n"
        assertEquals(
            listOf(
                "error 4: ants.output writes channel, a field the output schema doesn't declare.",
                "error 5: ants.output writes user, a field the output schema doesn't declare.",
                "warning output[0]: The required payload field ts is never written.",
            ),
            check(form("payload", code = code) + output),
        )
        assertEquals(emptyList(), check(form("free", code = code)), "with no output schema any payload is accepted")
    }

    @Test
    fun `the validator marks a missing or repeated name and an object with no fields as errors on their rows`() {
        val rows = listOf(
            "inputs[0].name" to "text", "inputs[0].type" to "string",
            "inputs[1].name" to "text", "inputs[1].type" to "number",
            "inputs[2].name" to "", "inputs[2].type" to "string", "inputs[2].description" to "What to post.",
            "inputs[3].name" to "", "inputs[3].type" to "string", "inputs[3].description" to "",
            "inputs[4].name" to "ticket", "inputs[4].type" to "object",
            "inputs[5].name" to "tickets", "inputs[5].type" to "array:object", "inputs[5].rows[0].name" to "", "inputs[5].rows[0].description" to "A title.",
            "output[0].name" to "user", "output[0].type" to "object", "output[0].rows[0].name" to "id", "output[0].rows[1].name" to "id",
        )
        val code = "console.log(ants.inputs.text, ants.inputs.ticket, ants.inputs.tickets)\nants.output({user: {}})\n"
        assertEquals(
            listOf(
                "error inputs[1]: Another input is named text too.",
                "error inputs[2]: Input row 3 has no name.",
                "error inputs[4]: ticket is an object with no fields.",
                "error inputs[5].rows[0]: Field 1 of tickets has no name.",
                "error output[0].rows[1]: Another field of user is named id too.",
            ),
            check(form("rows", code = code) + rows),
        )
    }

    @Test
    fun `the validator gives no finding for code passing ants inputs or the payload on under another name`() {
        val output = listOf("output[0].name" to "ts", "output[0].type" to "string", "output[0].required" to "on")
        val code = "const {text} = ants.inputs\nconst payload = {ts: text}\nants.output(payload)\n"
        assertEquals(emptyList(), check(form("passes-on", code = code, inputs = "text, channel") + output))
        val spread = "const given = ants.inputs\nants.output({...given})\n"
        assertEquals(emptyList(), check(form("spreads", code = spread, inputs = "text") + output))
    }

    /** The markers a page shows: Draft, Published, or Draft · changes not published. */
    private fun markers(page: String) = Regex("""class="pill draft-mark[^"]*">([^<]+)<""").findAll(page).map { it.groupValues[1] }.toList()

    private fun unescaped(page: String) = page.replace("&#34;", "\"").replace("&#39;", "'")

    @Test
    fun `an edit is autosaved to the Draft, which the page reopens after a reload and a restart, and the published Script stays as it was`() {
        create(form("drafter", code = "console.log(ants.inputs.text)\n", inputs = "text", own = listOf("SLACK_TOKEN" to "xoxb-s3cret")))
        val folder = folder("drafter")
        val page = browser.get("/scripts/drafter").body
        assertEquals(listOf("Published"), markers(page))
        assertEquals("0", draftVersion(page))

        val half = form("drafter", code = "console.log(ants.inputs.txt)\nconst x = ;\n", description = "Half done.", inputs = "text, count:number", own = listOf("SLACK_TOKEN" to "xoxb-n3w"))
        val saved = browser.autosave("/scripts/drafter", half, draftVersion(page))
        assertEquals(200, saved.status, saved.body)
        val answer = answer(saved)
        assertTrue(Regex("""Draft saved \d\d:\d\d""").matches(answer["saved"].toString()), answer.toString())
        assertEquals("Draft · changes not published", answer["label"])
        assertEquals("/scripts/drafter", answer["path"])
        assertEquals("console.log(ants.inputs.text)\n", folder.resolve("script.mjs").readText())
        assertEquals("Posts a message.", definition(folder)["description"])

        val again = unescaped(browser().also { it.login() }.get("/scripts/drafter").body)
        assertContains(again, """name="description" value="Half done."""")
        assertContains(again, "console.log(ants.inputs.txt)\nconst x = ;")
        assertContains(again, """name="inputs[1].name" value="count"""")
        assertEquals(listOf("Draft · changes not published"), markers(again))
        assertContains(again, answer["saved"].toString())
        assertEquals(answer["version"].toString(), draftVersion(again))
        val id = folder.fileName.toString()
        val draft = Drafts(home).get(DraftKind.SCRIPT, id)!!.content // as a restarted Ants reads it
        assertContains(draft, "Half done.")
        assertFalse("xoxb" in draft) // a Secret's value is never in the Draft
        assertEquals(mapOf("SLACK_TOKEN" to "xoxb-s3cret"), Yaml().load<Map<*, *>>(folder.resolve("secrets.yaml").readText()))
        assertContains(browser.get("/scripts").body, """<a href="/scripts/drafter">drafter</a></b><span class="pill draft-mark p-changed">Draft · changes not published</span>""")
    }

    @Test
    fun `Publish refuses a Draft with a validator error, listing each one, and keeps the Draft, and one with only warnings publishes and removes it`() {
        create(form("warned", code = "console.log('hi')\n", inputs = "text"))
        val broken = form("warned", code = "console.log(ants.inputs.txt)\nconst x = ;\n", inputs = "text, text")
        assertEquals(200, browser.autosave("/scripts/warned", broken, "0").status) // a Draft with errors is kept

        val refused = browser.post("/scripts/warned", broken)
        assertContains(refused.body, "The Script has 2 errors, so it wasn't published:")
        assertContains(refused.body, "<li>Line 2: SyntaxError: Unexpected token ';'</li>")
        assertContains(refused.body, "<li>Another input is named text too.</li>")
        assertEquals(listOf("Draft · changes not published"), markers(refused.body))
        assertEquals("console.log('hi')\n", folder("warned").resolve("script.mjs").readText())
        assertContains(unescaped(browser.get("/scripts/warned").body), "const x = ;")

        val published = browser.post("/scripts/warned", form("warned", code = "console.log('warned')\n", inputs = "text"))
        assertEquals("/scripts/warned", published.path)
        assertFalse("wasn't published" in published.body)
        assertEquals("console.log('warned')\n", folder("warned").resolve("script.mjs").readText())
        assertEquals(listOf("Published"), markers(published.body))
        assertEquals("0", draftVersion(published.body))
    }

    @Test
    fun `a save from a tab with an older version gets a conflict, and Publish refuses a name another Script has`() {
        create(form("tabbed"))
        create(form("tabbed-taken"))
        val loaded = draftVersion(browser.get("/scripts/tabbed").body)
        val first = answer(browser.autosave("/scripts/tabbed", form("tabbed", description = "First tab."), loaded))
        val second = browser.autosave("/scripts/tabbed", form("tabbed", description = "Second tab."), loaded)
        assertEquals(409, second.status)
        assertEquals("This Script's Draft was changed in another tab or window.", answer(second)["conflict"])
        assertEquals(200, browser.autosave("/scripts/tabbed", form("tabbed", description = "First tab, again."), first["version"].toString()).status)
        assertContains(browser.get("/scripts/tabbed").body, """value="First tab, again."""")

        val taken = browser.post("/scripts/tabbed", form("tabbed-taken", description = "Renamed."))
        assertContains(taken.body, "A Script named tabbed-taken already exists.")
        assertContains(browser.get("/scripts/tabbed").body, """value="Renamed."""")
        assertEquals("Posts a message.", definition(folder("tabbed"))["description"])
    }

    @Test
    fun `New and Copy make a Draft-only Script with a placeholder name, which a rename moves and Publish puts to use`() {
        val first = browser.post("/scripts/new/draft", emptyMap())
        val name = first.path.removePrefix("/scripts/")
        assertEquals("new-script", name)
        assertEquals(listOf("Draft"), markers(first.body))
        assertContains(first.body, """name="name" value="new-script"""")
        assertFalse(scripts.listDirectoryEntries().isNotEmpty())
        assertContains(browser.get("/scripts").body, """<a href="/scripts/new-script">new-script</a></b><span class="pill draft-mark p-draft">Draft</span>""")
        assertEquals("/scripts/new-script-2", browser.post("/scripts/new/draft", emptyMap()).path)

        create(form("poster", code = "console.log(ants.inputs.text)\n", inputs = "text", own = listOf("SLACK_TOKEN" to "xoxb-s3cret")))
        val copy = browser.post("/scripts/poster/copy", emptyMap())
        assertEquals("/scripts/poster-copy", copy.path)
        assertEquals(listOf("Draft"), markers(copy.body))
        assertContains(copy.body, """name="inputs[0].name" value="text"""")
        assertContains(copy.body, """name="secrets[0].name" value="SLACK_TOKEN"""")
        assertFalse("••••" in copy.body) // the copy names its own Secret, but has no value for it
        assertEquals(1, scripts.listDirectoryEntries().size)

        val renamed = answer(browser.autosave("/scripts/new-script", form("drafted-first", code = "ants.output({})\n"), draftVersion(first.body)))
        assertEquals("/scripts/drafted-first", renamed["path"]) // a Draft-only Script's page follows its name
        assertEquals("Draft", renamed["label"])
        assertEquals(404, browser.get("/scripts/new-script").status)
        assertEquals("/scripts/drafted-first", browser.post("/scripts/drafted-first", form("drafted-first", code = "ants.output({})\n")).path)
        assertEquals("ants.output({})\n", folder("drafted-first").resolve("script.mjs").readText())
        assertEquals(listOf("Published"), markers(browser.get("/scripts/drafted-first").body))
    }

    @Test
    fun `Discard Draft confirms and reopens the published Script, and Delete on a Draft-only Script confirms and leaves the list`() {
        create(form("discarder"))
        browser.autosave("/scripts/discarder", form("discarder", description = "Unwanted."), "0")
        assertContains(browser.get("/scripts/discarder").body, """<form method="post" action="/scripts/discarder/discard"""")
        assertContains(browser.post("/scripts/discarder/discard", emptyMap()).body, "Discard the Draft of discarder?")
        assertContains(browser.get("/scripts/discarder").body, """value="Unwanted."""")
        val discarded = browser.post("/scripts/discarder/discard", mapOf("confirm" to "on"))
        assertEquals("/scripts/discarder", discarded.path)
        assertContains(discarded.body, """value="Posts a message."""")
        assertEquals(listOf("Published"), markers(discarded.body))

        val draft = browser.post("/scripts/new/draft", emptyMap()).path
        assertFalse("/discard" in browser.get(draft).body)
        assertContains(browser.post("$draft/delete", emptyMap()).body, "Delete the Draft ${draft.removePrefix("/scripts/")}?")
        assertEquals("/scripts", browser.post("$draft/delete", mapOf("confirm" to "on")).path)
        assertEquals(404, browser.get(draft).status)
        assertFalse("href=\"$draft\"" in browser.get("/scripts").body)

        browser.autosave("/scripts/discarder", form("discarder", description = "Again."), "0")
        browser.post("/scripts/discarder/delete", mapOf("confirm" to "on"))
        assertEquals(404, browser.get("/scripts/discarder").status) // its Draft went with it
    }

    @Test
    fun `Test runs the page as it is, which is the Draft's code and schemas`() {
        create(form("tested", code = "ants.output({said: 'published'})\n"))
        val draft = form("tested", code = "ants.output({said: ants.inputs.text})\n", inputs = "text") + listOf("output[0].name" to "said", "output[0].type" to "string", "output[0].description" to "")
        browser.autosave("/scripts/tested", draft, "0")
        val run = testRun(draft, sample = """{"text": "drafted"}""", script = "tested")
        assertEquals(mapOf("said" to "drafted"), run["payload"])
        assertEquals("ants.output({said: 'published'})\n", folder("tested").resolve("script.mjs").readText())
        val refused = testRun(draft, sample = """{"text": 1}""", script = "tested")
        assertContains(refused["output"].toString(), "The inputs break the Script's input schema") // the Draft's schema, which the published Script hasn't
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
