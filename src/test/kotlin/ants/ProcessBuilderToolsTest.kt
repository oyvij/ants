package ants

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Process Builder's MCP session (ADR 0019): a modeler page opens it and gets a token of its own on `/mcp`, which
 * opens only the modeler tools. The test acts as the page: it follows its SSE and answers each call relayed to it.
 */
class ProcessBuilderToolsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login(); it.get("/processes") } }
    private val json = JsonMapper.shared()

    @Autowired
    private lateinit var protectedCommands: ProtectedCommands

    @Autowired
    private lateinit var builder: ProcessBuilderTools

    private val agentTaskXml = javaClass.getResource("/agent-task.bpmn")!!.readText()

    /** Opens a Process Builder session as a modeler page does; its token. */
    private fun open(): String = json.readValue(browser.post("/process-builder", emptyMap()).body, Map::class.java)["token"].toString()

    private fun tools(token: String) = ((mcp(port, token, "tools/list").second["result"] as Map<*, *>)["tools"] as List<*>).map { (it as Map<*, *>)["name"] }

    private fun call(token: String, name: String, arguments: Map<String, Any?> = emptyMap()) = mcpCall(port, token, name, arguments)

    private fun page(token: String, answer: (String, Map<*, *>) -> Any?) = browser.actAsPage(token, builder, answer)

    @Test
    fun `after a reload only the page's newest stream takes the relayed calls`() {
        val token = open()
        browser.events("builder=$token", "builder") // the page before the reload, its stream not yet found gone
        eventually { assertTrue(builder.connected(token)) }
        browser.actAsPage(token, builder) { _, _ -> mapOf("elements" to emptyList<Any>()) }
        val (text, failed) = CompletableFuture.supplyAsync { call(token, "diagram") }.get(10, SECONDS)
        assertFalse(failed, text)
    }

    @Test
    fun `a page's token lists the modeler's read tools and not run, and a Task's token lists run and no modeler tool`() {
        val page = open()
        val task = protectedCommands.open(Files.createTempDirectory("workspace")).first
        try {
            assertEquals(listOf("diagram", "agents", "skills", "skill", "processes", "process", "built_in_tasks", "templates", "references", "checks",
                "create_agent", "create_skill", "change_agent", "change_skill", "delete_agent", "delete_skill", "build_log", "copy",
                "add_element", "remove", "rename", "move", "connect", "set_condition", "set_default", "set_agent_task", "set_start_form",
                "set_user_task", "set_call", "set_built_in", "set_multi_instance", "drop_template"), tools(page))
            assertEquals(listOf("run"), tools(task))
            assertTrue(call(task, "diagram").second)
            assertTrue(call(page, "run", mapOf("command" to "ls")).second)
        } finally {
            protectedCommands.close(task)
        }
    }

    @Test
    fun `the diagram tool is relayed to the page and answered with what it sends back`() {
        val token = open()
        val diagram = mapOf("elements" to listOf(mapOf("id" to "start", "kind" to "start")), "connections" to emptyList<Any>(), "startForm" to emptyList<Any>())
        page(token) { tool, _ -> diagram.takeIf { tool == "diagram" } }
        val (text, failed) = call(token, "diagram")
        assertEquals(false, failed, text)
        assertEquals(diagram, json.readValue(text, Map::class.java))
    }

    @Test
    fun `a write tool is relayed to the page with its arguments, and the page's refusal comes back as the tool's error`() {
        val token = open()
        page(token) { tool, arguments -> if (tool == "add_element") mapOf("id" to arguments["id"], "x" to arguments["x"]) else null }
        val (text, failed) = call(token, "add_element", mapOf("kind" to "agent", "id" to "grill", "x" to 300))
        assertEquals(false, failed, text)
        assertEquals(mapOf("id" to "grill", "x" to 300), json.readValue(text, Map::class.java))

        val refusing = open()
        val events = browser.events("builder=$refusing", "builder")
        Thread.ofVirtual().start {
            val relayed = json.readValue(events.take(), Map::class.java)
            browser.post("/process-builder/answer", mapOf("token" to refusing, "call" to relayed["id"].toString(), "error" to "No element Task_x."))
        }
        eventually { assertTrue(builder.connected(refusing)) }
        val (refusal, refused) = call(refusing, "remove", mapOf("id" to "Task_x"))
        assertTrue(refused)
        assertTrue("No element Task_x." in refusal, refusal)
    }

    @Test
    fun `the catalogue tools and the Checks answer as the modeler's endpoints do, the Checks on the page's diagram`() {
        agentTask(null)
        home.resolve("skills/draft/reference.md").writeText("A file beside it.")
        browser.import("agent-task.bpmn")
        val token = open()
        val broken = agentTaskXml.replace("value=\"writer\"", "value=\"nobody\"")
        page(token) { tool, _ -> broken.takeIf { tool == "xml" } }

        fun tool(name: String, arguments: Map<String, Any?> = emptyMap()) = call(token, name, arguments).also { assertEquals(false, it.second, it.first) }.first.let { json.readTree(it) }
        fun endpoint(body: String) = json.readTree(body)
        val catalogue = endpoint(browser.get("/catalogue.json").body)
        assertEquals(catalogue["agents"], tool("agents"))
        assertEquals(catalogue["skills"], tool("skills"))
        assertEquals(endpoint(browser.get("/processes.json").body), tool("processes"))
        assertEquals(endpoint(browser.get("/templates.json").body), tool("templates"))
        assertEquals(json.valueToTree(builtIns), tool("built_in_tasks"))
        assertEquals(endpoint(browser.post("/processes/checks", mapOf("bpmn" to broken)).body), tool("checks"))
        assertTrue(tool("checks").toString().contains("nobody"))
        assertEquals(endpoint(browser.post("/processes/inputs", mapOf("bpmn" to broken, "element" to "draft")).body), tool("references", mapOf("element" to "draft")))

        val skill = tool("skill", mapOf("name" to "draft"))
        assertEquals("Do it.", skill["instructions"].asString())
        assertEquals("""["reference.md"]""", skill["files"].toString())
        assertTrue(call(token, "skill", mapOf("name" to "nothing")).second)
        assertTrue(call(token, "process", mapOf("key" to "agent-task")).first.contains("""<bpmn:serviceTask id="draft""""))
        assertTrue(call(token, "process", mapOf("key" to "nothing")).second)
    }

    @Test
    fun `a diagram call with no page connected fails at once, and one the page never answers fails at the time limit`() {
        val token = open()
        val start = System.nanoTime()
        val (text, failed) = call(token, "diagram")
        assertTrue(failed)
        assertTrue("No modeler page" in text, text)
        assertTrue(Duration.ofNanos(System.nanoTime() - start) < Duration.ofSeconds(2))

        builder.timeLimit = Duration.ofMillis(500)
        try {
            browser.events("builder=$token", "builder") // connected, never answering
            eventually { assertTrue(builder.connected(token)) }
            val late = CompletableFuture.supplyAsync { call(token, "diagram") }.get(10, SECONDS)
            assertTrue(late.second)
            assertTrue("didn't answer" in late.first, late.first)
        } finally {
            builder.timeLimit = ProcessBuilderTools.TIME_LIMIT
        }
    }

    @Test
    fun `the Build log tool replaces the Build log of the page's Process, which outlives Save and deploy`() {
        agentTask(null)
        val token = open()
        page(token) { tool, _ -> agentTaskXml.takeIf { tool == "xml" } }
        val log = home.resolve("processes/agent-task/build-log.md")

        assertEquals(false, call(token, "build_log", mapOf("markdown" to "# First draft")).second)
        val second = call(token, "build_log", mapOf("markdown" to "# Agent Task\nDecided: one Task.\n"))
        assertEquals(false, second.second, second.first)
        assertEquals("# Agent Task\nDecided: one Task.\n", log.readText())

        assertEquals("/processes/agent-task", browser.post("/processes/deploy", mapOf("bpmn" to agentTaskXml)).path)
        assertEquals("# Agent Task\nDecided: one Task.\n", log.readText())
    }

    @Test
    fun `a Process key unsafe for a path gets no Build log, and nothing is written outside the processes folder`() {
        val token = open()
        var key = ""
        page(token) { tool, _ -> agentTaskXml.replace("""id="agent-task"""", """id="$key"""").takeIf { tool == "xml" } }
        for (unsafe in listOf("..", "../escaped", "a/b", "")) {
            key = unsafe
            val refused = call(token, "build_log", mapOf("markdown" to "# Escaped"))
            assertTrue(refused.second, "$key: ${refused.first}")
        }
        assertFalse(home.resolve("escaped").exists())
        assertFalse(home.resolve("processes/build-log.md").exists())
        assertFalse(home.resolve("build-log.md").exists())
    }

    @Test
    fun `an Agent and a Skill created through the Process Builder are in the catalogue at once, and a Task can pick them`() {
        agentTask(null, emptyMap())
        home.resolve("agents/writer.md").toFile().delete()
        val token = open()
        page(token) { tool, _ -> agentTaskXml.takeIf { tool == "xml" } }

        val skill = call(token, "create_skill", mapOf("skill" to "---\nname: draft\ndescription: Drafts a text.\n---\nDraft it.\n"))
        assertEquals(false, skill.second, skill.first)
        assertEquals(mapOf("name" to "draft", "page" to "/skills/draft"), json.readValue(skill.first, Map::class.java))
        val agent = call(token, "create_agent", mapOf("file" to "---\nname: writer\ndescription: Writes.\nskills: draft\ntools: Read, Bash(git log *)\n---\nWrite plainly.\n"))
        assertEquals(false, agent.second, agent.first)
        assertEquals(mapOf("name" to "writer", "page" to "/agents/writer"), json.readValue(agent.first, Map::class.java))

        val catalogue = json.readTree(browser.get("/catalogue.json").body)
        assertTrue(catalogue["agents"].any { it["name"].asString() == "writer" && it["skills"].toString() == """["draft"]""" })
        assertTrue(catalogue["skills"].any { it["name"].asString() == "draft" })
        assertEquals(listOf("Bash(git log *)", "Read"), Agent(home.resolve("agents/writer.md")).tools!!.sorted())
        val checks = call(token, "checks").first
        assertTrue("writer" !in checks && "draft" !in checks, checks)
    }

    @Test
    fun `a Skill made or changed with a schema is a plain SKILL md, and the answer says the schema belongs on the Task`() {
        val token = open()
        val schema = """{"type":"object","properties":{"notes":{"type":"string"}}}"""
        val (made, failed) = call(token, "create_skill", mapOf("skill" to "---\nname: draft\ndescription: Drafts a text.\n---\nDraft it.\n", "schema" to schema))
        assertFalse(failed, made)
        val answer = json.readValue(made, Map::class.java)
        assertEquals("draft", answer["name"])
        assertEquals(SCHEMA_IGNORED, answer["note"])
        assertEquals(listOf("SKILL.md", "ants.yaml"), home.resolve("skills/draft").toFile().list()!!.sorted())
        val (changed, refused) = call(token, "change_skill", mapOf("name" to "draft", "skill" to "---\nname: draft\ndescription: Drafts.\n---\nDraft.\n", "schema" to schema))
        assertFalse(refused, changed)
        assertEquals(SCHEMA_IGNORED, json.readValue(changed, Map::class.java)["note"])
        assertEquals(listOf("SKILL.md", "ants.yaml"), home.resolve("skills/draft").toFile().list()!!.sorted())
        assertFalse("note" in json.readValue(call(token, "change_skill", mapOf("name" to "draft", "skill" to "---\nname: draft\ndescription: D.\n---\n")).first, Map::class.java))
    }

    @Test
    fun `set_agent_task answers with what the page wrote and the Checks Save and deploy refuses on that Task`() {
        agentTask(null)
        val token = open()
        val neither = agentTaskXml.replace("""<operaton:property name="ants.skills" value="draft" />""", "")
        val written = mapOf("id" to "draft", "agent" to "writer", "assignment" to null, "skills" to null)
        page(token) { tool, _ -> when (tool) { "set_agent_task" -> written; "xml" -> neither; else -> null } }
        val (text, failed) = call(token, "set_agent_task", mapOf("id" to "draft", "skills" to null))
        assertFalse(failed, text)
        val answer = json.readValue(text, Map::class.java)
        assertEquals("draft", answer["id"])
        assertEquals(listOf("Draft (draft) has no Assignment and doesn't list exactly one Skill."), answer["checks"])

        val fine = open()
        page(fine) { tool, _ -> when (tool) { "set_agent_task" -> written; "xml" -> agentTaskXml; else -> null } }
        assertEquals(emptyList<Any>(), json.readValue(call(fine, "set_agent_task", mapOf("id" to "draft")).first, Map::class.java)["checks"])
    }

    @Test
    fun `an invalid Agent or Skill gets the page's errors back and writes nothing`() {
        val token = open()
        fun refused(tool: String, arguments: Map<String, Any?>) = call(token, tool, arguments).also { assertTrue(it.second, it.first) }.first
        assertTrue(refused("create_agent", mapOf("file" to "---\nname: [broken\n---\n")).startsWith("The agent file has broken YAML"))
        assertEquals("Ants runs only claude or codex, not gemini.", refused("create_agent", mapOf("file" to "---\nname: coder\ncli: gemini\n---\n")))
        assertEquals("codex takes the sandbox read-only, workspace-write or danger-full-access, not yolo.",
            refused("create_agent", mapOf("file" to "---\nname: coder\ncli: codex\nsandbox: yolo\n---\n")))
        assertEquals("An Agent's name is up to 64 lowercase letters, digits and hyphens.", refused("create_agent", mapOf("file" to "Code.\n")))
        assertEquals("An Agent named shipped-writer already exists.", refused("create_agent", mapOf("file" to "---\nname: shipped-writer\n---\n")))
        assertEquals("A Skill needs a name and a description in its frontmatter.", refused("create_skill", mapOf("skill" to "---\nname: draft\n---\n")))
        assertEquals(emptyList(), home.resolve("agents").toFile().list()!!.toList())
        assertEquals(emptyList(), home.resolve("skills").toFile().list()!!.toList())
    }

    @Test
    fun `a shipped Agent or Skill can't be changed, and copying one, or a Template, makes the user's own, which can`() {
        val token = open()
        val refusedAgent = call(token, "change_agent", mapOf("name" to "shipped-writer", "file" to "---\nname: shipped-writer\n---\nChanged.\n"))
        assertEquals("shipped-writer is read-only. Copy it to make one you can edit." to true, refusedAgent)
        val refusedSkill = call(token, "change_skill", mapOf("name" to "shipped-draft", "skill" to "---\nname: shipped-draft\ndescription: D.\n---\n"))
        assertEquals("The Skill shipped-draft is read-only; Copy it to make your own." to true, refusedSkill)
        assertTrue("Changed." !in home.resolve("shipped/agents/shipped-writer.md").toFile().readText())

        fun copy(kind: String, name: String) = call(token, "copy", mapOf("kind" to kind, "name" to name)).also { assertEquals(false, it.second, it.first) }
            .first.let { json.readValue(it, Map::class.java) }
        assertEquals(mapOf("name" to "shipped-writer-copy", "page" to "/agents/shipped-writer-copy"), copy("agent", "shipped-writer"))
        assertEquals(mapOf("name" to "shipped-draft-copy", "page" to "/skills/shipped-draft-copy"), copy("skill", "shipped-draft"))
        assertEquals(mapOf("name" to "review-loop-copy", "page" to "/templates/review-loop-copy"), copy("template", "review-loop"))
        assertTrue(home.resolve("templates/review-loop-copy.bpmn").toFile().exists())
        assertTrue(call(token, "copy", mapOf("kind" to "process", "name" to "x")).second)

        val renamed = call(token, "change_agent", mapOf("name" to "shipped-writer-copy", "file" to "---\nname: my-writer\n---\nChanged.\n"))
        assertEquals(mapOf("name" to "my-writer", "page" to "/agents/my-writer"), json.readValue(renamed.first, Map::class.java))
        assertEquals("Changed.", Agent(home.resolve("agents/my-writer.md")).instructions)
        val skill = call(token, "change_skill", mapOf("name" to "shipped-draft-copy", "skill" to "---\nname: shipped-draft-copy\ndescription: Mine.\n---\nMine.\n"))
        assertEquals(mapOf("name" to "shipped-draft-copy", "page" to "/skills/shipped-draft-copy"), json.readValue(skill.first, Map::class.java))
        assertTrue(home.resolve("skills/shipped-draft-copy/SKILL.md").toFile().readText().endsWith("Mine.\n"))
    }

    @Test
    fun `an Agent or Skill of the user's own can be deleted, and a shipped one can't`() {
        val token = open()
        assertEquals(false, call(token, "create_skill", mapOf("skill" to "---\nname: draft\ndescription: Drafts a text.\n---\nDraft it.\n")).second)
        assertEquals(false, call(token, "create_agent", mapOf("file" to "---\nname: writer\ndescription: Writes.\nskills: draft\n---\nWrite plainly.\n")).second)

        assertEquals("Deleted the Agent writer." to false, call(token, "delete_agent", mapOf("name" to "writer")))
        assertEquals("Deleted the Skill draft." to false, call(token, "delete_skill", mapOf("name" to "draft")))
        val catalogue = json.readTree(browser.get("/catalogue.json").body)
        assertTrue(catalogue["agents"].none { it["name"].asString() == "writer" })
        assertTrue(catalogue["skills"].none { it["name"].asString() == "draft" })
        assertFalse(home.resolve("skills/draft").exists())

        assertEquals("shipped-writer is read-only. Copy it to make one you can edit." to true, call(token, "delete_agent", mapOf("name" to "shipped-writer")))
        assertEquals("The Skill shipped-draft is read-only; Copy it to make your own." to true, call(token, "delete_skill", mapOf("name" to "shipped-draft")))
        assertTrue(home.resolve("shipped/agents/shipped-writer.md").exists())
        assertTrue(call(token, "delete_agent", mapOf("name" to "nobody")).second)
    }

    @Test
    fun `a Skill whose folder holds another Skill isn't deleted`() {
        val token = open()
        val outer = home.resolve("skills/outer")
        outer.resolve("inner").createDirectories().resolve("SKILL.md").writeText("---\nname: inner\ndescription: Inside.\n---\nIn.\n")
        outer.resolve("SKILL.md").writeText("---\nname: outer\ndescription: Outside.\n---\nOut.\n")

        assertEquals("$outer holds other Skills (inner); move or delete them first." to true, call(token, "delete_skill", mapOf("name" to "outer")))
        assertTrue(outer.resolve("inner/SKILL.md").exists())
    }
}

/** Calls Ants' `/mcp` on [port] with [token]: the HTTP status and the JSON-RPC response. */
fun mcp(port: Int, token: String, method: String, params: Map<String, Any?> = emptyMap()): Pair<Int, Map<*, *>> {
    val json = JsonMapper.shared()
    val body = json.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))
    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp")).POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json").header("Authorization", "Bearer $token")
    val response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
    return response.statusCode() to json.readValue(response.body(), Map::class.java)
}

/** Calls the MCP tool [name] with [token]: its text, and whether it failed. */
fun mcpCall(port: Int, token: String, name: String, arguments: Map<String, Any?> = emptyMap()): Pair<String, Boolean> {
    val result = mcp(port, token, "tools/call", mapOf("name" to name, "arguments" to arguments)).second["result"] as Map<*, *>
    return ((result["content"] as List<*>).single() as Map<*, *>)["text"].toString() to (result["isError"] == true)
}

/** Acts as the modeler page holding [token]: follows its SSE and answers each call relayed to it with what [answer] gives for its tool. */
fun Browser.actAsPage(token: String, builder: ProcessBuilderTools, answer: (String, Map<*, *>) -> Any?) {
    val json = JsonMapper.shared()
    val events = events("builder=$token", "builder")
    Thread.ofVirtual().start {
        while (true) {
            val relayed = json.readValue(events.take(), Map::class.java)
            post("/process-builder/answer", mapOf("token" to token, "call" to relayed["id"].toString(),
                "answer" to json.writeValueAsString(answer(relayed["tool"].toString(), relayed["arguments"] as Map<*, *>))))
        }
    }
    eventually { assertTrue(builder.connected(token)) }
}
