package ants

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Agents Post to Widgets through Ants' MCP server (ADR 0032), the tests acting as the agent with [FakeClaude]. */
class AgentPostsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()
    private val json = JsonMapper.shared()

    @Autowired
    lateinit var catalogue: Catalogue

    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"done","summary":"Drafted.","output":{}}}"""

    private fun dashboard(title: String): String {
        browser.get("/")
        val id = browser.post("/dashboards", mapOf()).path.removePrefix("/dashboards/")
        browser.post("/dashboards/$id/title", mapOf("title" to title))
        return id
    }

    private fun widget(dashboard: String, title: String, type: String = "number"): String {
        val id = answer(browser.post("/dashboards/$dashboard/widgets", mapOf("type" to type)))["id"].toString()
        browser.post("/dashboards/$dashboard/widgets/$id/title", mapOf("title" to title))
        return id
    }

    /** A Run of the Agent `writer` with [tools], its session waiting for `go`; the Run's id. */
    private fun run(tools: String, vararg session: String = arrayOf("wait go", done)): String {
        agentTask(claude, agent = "tools: $tools\n")
        claude.session("draft", *session)
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        return browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
    }

    private fun launch(n: Int = 0): FakeClaude.Launch {
        eventually { assertTrue(claude.launches("draft").size > n) }
        return claude.launches("draft")[n]
    }

    private fun rpc(launch: FakeClaude.Launch, method: String, params: Map<String, Any?> = emptyMap()): Map<*, *> {
        val ants = (json.readValue(launch.flag("--mcp-config"), Map::class.java)["mcpServers"] as Map<*, *>)["ants"] as Map<*, *>
        val body = json.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))
        val request = HttpRequest.newBuilder(URI(ants["url"].toString())).POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json").header("Authorization", (ants["headers"] as Map<*, *>)["Authorization"].toString()).build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return json.readValue(response.body(), Map::class.java)["result"] as Map<*, *>
    }

    /** Calls the MCP tool [tool]: its text, and whether it is an error. */
    private fun call(launch: FakeClaude.Launch, tool: String, arguments: Map<String, Any?> = emptyMap()): Pair<String, Boolean> {
        val result = rpc(launch, "tools/call", mapOf("name" to tool, "arguments" to arguments))
        return ((result["content"] as List<*>).single() as Map<*, *>)["text"].toString() to (result["isError"] == true)
    }

    private fun widgets(launch: FakeClaude.Launch): List<Map<*, *>> {
        val (text, failed) = call(launch, "ListWidgets")
        assertFalse(failed, text)
        return json.readValue(text, List::class.java).map { it as Map<*, *> }
    }

    private fun allowed(launch: FakeClaude.Launch) = launch.flag("--allowedTools")!!.split(",")

    private fun shown(dashboard: String, widget: String) =
        browser.get("/dashboards/$dashboard").body.substringAfter("""gs-id="$widget"""").substringBefore("</article>")

    @Test
    fun `an Agent with PostToWidget lists the Widgets with their address, type, title and accepted data, and its Post shows with the Run it came from`() {
        val home = dashboard("Home lab")
        val disk = widget(home, "Free disk")
        val news = widget(home, "News", "list")
        val run = run("Read, PostToWidget")
        val launch = launch()
        assertContains(allowed(launch), "mcp__ants__ListWidgets")
        assertContains(allowed(launch), "mcp__ants__PostToWidget")
        assertFalse("PostToWidget" in launch.flag("--tools")!!.split(","))
        assertEquals(setOf("ListWidgets", "PostToWidget"), (rpc(launch, "tools/list")["tools"] as List<*>).map { (it as Map<*, *>)["name"] }.toSet())

        val listed = widgets(launch).filter { it["dashboard"] == home }
        assertEquals(
            listOf(
                mapOf("address" to "$home/$disk", "dashboard" to home, "dashboardTitle" to "Home lab", "type" to "number", "title" to "Free disk", "accepts" to "{value: number, unit?: string, delta?: number}"),
                mapOf("address" to "$home/$news", "dashboard" to home, "dashboardTitle" to "Home lab", "type" to "list", "title" to "News", "accepts" to "{items: [{title: string, url?: string, note?: string}]}"),
            ),
            listed,
        )

        val (posted, failed) = call(launch, "PostToWidget", mapOf("dashboard" to home, "widget" to disk, "data" to mapOf("value" to 42, "unit" to "GB")))
        assertFalse(failed, posted)
        assertEquals("Posted to $home/$disk.", posted)
        val drawn = shown(home, disk)
        assertContains(drawn, """<div class="num">42<small>GB</small></div>""")
        assertContains(drawn, """<a href="/runs/$run">""")
        val posting = browser.get("/dashboards/$home").body.substringAfter("""id="posting"""")
        assertContains(posting, "<code>PostToWidget($home)</code> lets it Post only here")
        assertContains(posting, "PostToWidget(dashboard: '$home', widget: '$disk', data: {value: 42})")
        claude.release("go")
    }

    @Test
    fun `a refused Post answers an error with the message a Script gets, so the Agent can fix it and Post again`() {
        val home = dashboard("Home lab")
        val disk = widget(home, "Free disk")
        run("PostToWidget")
        val launch = launch()

        assertEquals(
            "The number Widget $home/$disk (Free disk) takes {value: number, unit?: string, delta?: number}, but value is not a number." to true,
            call(launch, "PostToWidget", mapOf("dashboard" to home, "widget" to disk, "data" to mapOf("value" to "lots"))),
        )
        val (gone, error) = call(launch, "PostToWidget", mapOf("dashboard" to home, "widget" to "w-gone00", "data" to mapOf("value" to 1)))
        assertTrue(error)
        assertContains(gone, "No Widget $home/w-gone00: the Widget or its Dashboard was deleted")
        assertContains(shown(home, disk), "Waiting for its first Post")
        assertEquals("Posted to $home/$disk." to false, call(launch, "PostToWidget", mapOf("dashboard" to home, "widget" to disk, "data" to mapOf("value" to 1))))
        claude.release("go")
    }

    @Test
    fun `PostToWidget with a Dashboard limits the Agent to that Dashboard, which is all ListWidgets shows it`() {
        val home = dashboard("Home lab")
        val disk = widget(home, "Free disk")
        val work = dashboard("Work")
        val issues = widget(work, "Issues")
        run("Read, PostToWidget($home)")
        val launch = launch()
        assertContains(allowed(launch), "mcp__ants__PostToWidget")

        assertEquals(listOf("$home/$disk"), widgets(launch).map { it["address"] })
        assertEquals(
            "Your Agent may Post only to the Dashboard $home, so Ants Posted nothing to $work/$issues: end stuck, saying your Agent needs `PostToWidget($work)`." to true,
            call(launch, "PostToWidget", mapOf("dashboard" to work, "widget" to issues, "data" to mapOf("value" to 1))),
        )
        assertContains(shown(work, issues), "Waiting for its first Post")
        assertEquals(false, call(launch, "PostToWidget", mapOf("dashboard" to home, "widget" to disk, "data" to mapOf("value" to 1))).second)
        claude.release("go")
    }

    @Test
    fun `the shipped lead may Post, with the shipped dashboards Skill among its Skills`() {
        val lead = catalogue.agent("lead")
        assertContains(lead.tools!!, "PostToWidget")
        assertContains(catalogue.allowedNames(lead), "dashboards")
        val skill = catalogue.skills().single { it.name == "dashboards" }
        val text = skill.folder.resolve("SKILL.md").readText()
        for (rule in listOf("ListWidgets", "PostToWidget", "small", "refused", "Script that")) assertContains(text, rule)
    }

    private val init = """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}"""

    @Test
    fun `any other Agent that tries to Post gets a Permission request, and Always allow adds PostToWidget for the Dashboard to its tools`() {
        val home = dashboard("Home lab")
        val disk = widget(home, "Free disk")
        val input = """{"dashboard":"$home","widget":"$disk","data":{"value":3}}"""
        val tool = "mcp__ants__PostToWidget"
        // As claude 2.1.318 does under dontAsk when Ants' server offers the tool but --allowedTools doesn't list it.
        val refused = arrayOf(
            init,
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"$tool","input":$input}]},"session_id":"${'$'}SESSION"}""",
            """{"type":"system","subtype":"permission_denied","tool_name":"$tool","tool_use_id":"toolu_1","decision_reason_type":"mode","message":"Permission to use $tool has been denied.","session_id":"${'$'}SESSION"}""",
            "read",
            """{"type":"control_response","response":{"subtype":"success","request_id":"r1","response":{"still_queued":[]}}}""",
            """{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"${'$'}SESSION","terminal_reason":"aborted_tools"}""",
            "exit 1",
        )
        claude.session("draft", 1, "wait first", *refused)
        val run = run("Read, Bash(git log *)", "wait go", done)
        val first = launch()
        assertContains(allowed(first), "mcp__ants__ListWidgets")
        assertFalse(tool in allowed(first))
        assertEquals(1, widgets(first).count { it["address"] == "$home/$disk" })
        val (text, failed) = call(first, "PostToWidget", mapOf("dashboard" to home, "widget" to disk, "data" to mapOf("value" to 3)))
        assertTrue(failed)
        assertEquals("Your Agent may not Post to Widgets, so Ants Posted nothing: end stuck, saying your Agent needs `PostToWidget($home)`.", text)
        claude.release("first")

        var entry = ""
        eventually {
            val row = Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL).find(browser.get("/notifications").body).let(::assertNotNull)
            entry = Regex("""href="(/notifications/[^"]+)"""").find(row.groupValues[1])!!.groupValues[1]
        }
        val page = browser.get(entry).body
        assertContains(page, "writer wants to Post to <code>$home/$disk</code>")
        assertContains(page, "PostToWidget($home)")
        try {
            browser.post("$entry/permission", mapOf("choice" to "always", "limit" to "PostToWidget($home)"))
            val resumed = launch(1)
            assertContains(allowed(resumed), tool)
            assertContains(resumed.stdin, "The user allowed `PostToWidget($home)` for you from now on.")
            assertContains(catalogue.agent("writer").tools!!, "PostToWidget($home)")
            assertEquals(false, call(resumed, "PostToWidget", mapOf("dashboard" to home, "widget" to disk, "data" to mapOf("value" to 3))).second)
        } finally {
            claude.release("go")
        }
    }
}
