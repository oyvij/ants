package ants

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.web.util.HtmlUtils
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/** Dashboards (ADR 0032): made, named and deleted from the menu, each kept in its own folder in ANTS_HOME. */
class DashboardsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    @Autowired
    private lateinit var posts: Posts

    /**
     * Posts [data], JSON, to [address] over `/posts` as a running Script listing it would, `ants.post`'s request: the
     * status and the answer's body.
     */
    private fun post(address: String, data: String, run: String = "r-post"): Pair<Int, String> {
        val (token, url) = posts.open(Sender(Script("s-test", "measure", "Measures.", widgets = listOf(address)), run, "measure"))
        try {
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"address":"$address","data":$data}""")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            return response.statusCode() to response.body()
        } finally {
            posts.close(token)
        }
    }

    /** Posts [data] to [address] and expects it accepted. */
    private fun accepted(address: String, data: String) = assertEquals(200, post(address, data).first, "refused: $data")

    /** Posts [data] to [address] and expects it refused: the message `ants.post` throws. */
    private fun refused(address: String, data: String): String {
        val (status, body) = post(address, data)
        assertEquals(422, status, "accepted: $data")
        return answer(Browser.Page(status, "", body))["error"].toString()
    }

    /** The body of the Widget [widget], as its Dashboard's page draws it. */
    private fun body(dashboard: String, widget: String) = widget(dashboard, widget)!!.substringAfter("""class="w-body"""").substringBefore("</article>")

    /** **+ Add Dashboard**: the new Dashboard's id, from the page it opens. */
    private fun add(browser: Browser = this.browser): String {
        browser.get("/")
        val page = browser.post("/dashboards", mapOf())
        assertEquals("new", page.query)
        return page.path.removePrefix("/dashboards/")
    }

    /** The menu's Dashboards list: each one's link and title, in order. */
    private fun menu(html: String) = Regex("""<a href="/dashboards/([^"]+)"[^>]*><span class="clip">([^<]*)</span></a>""")
        .findAll(html.substringAfter("""id="dashboards-list"""").substringBefore("</div>")).map { it.groupValues[1] to it.groupValues[2] }.toList()

    private fun title(html: String) = Regex("""<h1 id="title"[^>]*>([^<]*)</h1>""").find(html)!!.groupValues[1]

    @Test
    fun `Add Dashboard makes an Untitled dashboard with a random id, opens it, and the menu on every page lists it`() {
        val id = add()
        assertContains(id, Regex("^d-[a-z0-9]{6}$"))
        val page = browser.get("/dashboards/$id").body
        assertEquals("Untitled dashboard", title(page))
        assertContains(page, "No Widgets yet")
        assertEquals(listOf(id to "Untitled dashboard"), menu(browser.get("/runs").body).filter { it.first == id })
        assertContains(browser.get("/runs").body, """<form method="post" action="/dashboards">""")
        assertNotEquals(id, add())
    }

    @Test
    fun `the title is saved as typed, an emptied one falls back to Untitled dashboard, and a rename keeps the id`() {
        val id = add()
        assertEquals("Disk use", answer(browser.post("/dashboards/$id/title", mapOf("title" to "  Disk use "))).get("title"))
        assertEquals("Disk use", title(browser.get("/dashboards/$id").body))
        assertEquals(listOf(id to "Disk use"), menu(browser.get("/").body).filter { it.first == id })

        assertEquals("Untitled dashboard", answer(browser.post("/dashboards/$id/title", mapOf("title" to " "))).get("title"))
        assertEquals("Untitled dashboard", title(browser.get("/dashboards/$id").body))
    }

    @Test
    fun `Dashboards with none chosen opens the one viewed last, and with none at all says to use Add Dashboard`() {
        home.resolve("dashboards").toFile().deleteRecursively()
        val empty = browser.get("/dashboards")
        assertEquals("/dashboards", empty.path)
        assertContains(empty.body, "No Dashboards yet")
        assertContains(empty.body, "Add Dashboard")

        val first = add()
        add()
        browser.get("/dashboards/$first")
        assertEquals("/dashboards/$first", browser.get("/dashboards").path)
    }

    @Test
    fun `Delete asks first, then removes the Dashboard and its folder`() {
        val id = add()
        val asked = browser.post("/dashboards/$id/delete", mapOf())
        assertContains(asked.body, "Delete the Dashboard Untitled dashboard?")
        assertEquals(200, browser.get("/dashboards/$id").status)

        browser.post("/dashboards/$id/delete", mapOf("confirm" to "on"))
        assertEquals(404, browser.get("/dashboards/$id").status)
        assertFalse(home.resolve("dashboards/$id").toFile().exists())
        assertEquals(emptyList(), menu(browser.get("/").body).filter { it.first == id })
    }

    @Test
    fun `a Dashboard is kept in its own folder in ANTS_HOME and survives a restart`() {
        val home = tempHome()
        fun url(ants: Any) = "http://127.0.0.1:${(ants as WebServerApplicationContext).webServer!!.port}"
        val id = startAnother(home).use { ants ->
            val browser = Browser(url(ants)).also { it.login() }
            add(browser).also { browser.post("/dashboards/$it/title", mapOf("title" to "Home lab")) }
        }
        assertContains(home.resolve("dashboards/$id/dashboard.yaml").toFile().readText(), "Home lab")
        startAnother(home).use { ants ->
            val browser = Browser(url(ants)).also { it.login() }
            assertEquals("Home lab", title(browser.get("/dashboards/$id").body))
        }
    }

    @Test
    fun `a new Dashboard goes to the bottom of the menu, a rename keeps its place, and the order dragged survives a restart`() {
        val home = tempHome()
        fun url(ants: Any) = "http://127.0.0.1:${(ants as WebServerApplicationContext).webServer!!.port}"
        val (c, b, d) = startAnother(home).use { ants ->
            val browser = Browser(url(ants)).also { it.login() }
            val (a, b, c) = List(3) { add(browser) }
            browser.post("/dashboards/$a/title", mapOf("title" to "Zebra"))
            assertEquals(listOf(a, b, c), menu(browser.get("/").body).map { it.first })

            assertEquals(200, browser.post("/dashboards/order", mapOf("order" to "$c,$a,$b")).status)
            assertEquals(listOf(c, a, b), menu(browser.get("/").body).map { it.first })
            val d = add(browser)
            assertEquals(listOf(c, a, b, d), menu(browser.get("/").body).map { it.first })
            browser.post("/dashboards/$a/delete", mapOf("confirm" to "on"))
            Triple(c, b, d)
        }
        startAnother(home).use { ants ->
            val browser = Browser(url(ants)).also { it.login() }
            assertEquals(listOf(c, b, d), menu(browser.get("/").body).map { it.first })
            assertEquals(400, browser.post("/dashboards/order", mapOf("order" to "../x")).status)
        }
    }

    /** **+ Widget**, number: the new Widget's id. */
    private fun addWidget(dashboard: String, type: String = "number"): String =
        answer(browser.post("/dashboards/$dashboard/widgets", mapOf("type" to type)))["id"].toString()

    /** The Widget [widget] as the Dashboard's page draws it, its grid item. */
    private fun widget(dashboard: String, widget: String) =
        browser.get("/dashboards/$dashboard").body.let { page -> Regex("""<div class="grid-stack-item" gs-id="$widget".*?</article>""", RegexOption.DOT_MATCHES_ALL).find(page)?.value }

    @Test
    fun `a number Widget shows its type and fixed id, waits for its first Post at its address, and is renamed and removed for good`() {
        val dashboard = add()
        val id = addWidget(dashboard)
        assertContains(id, Regex("^w-[a-z0-9]{6}$"))
        val drawn = widget(dashboard, id)!!
        assertContains(drawn, "New number")
        assertContains(drawn, "number · $id")
        assertContains(drawn, "Waiting for its first Post")
        assertContains(drawn, "<code>$dashboard/$id</code>")
        val page = browser.get("/dashboards/$dashboard").body
        assertContains(page, """id="no-widgets" hidden""")
        assertContains(page.substringAfter("""id="posting""""), "<code>$dashboard/$id</code> · number</dt><dd data-widget=\"$id\"><code>{value: number, unit?: string, delta?: number}</code>")

        assertEquals("Free disk", answer(browser.post("/dashboards/$dashboard/widgets/$id/title", mapOf("title" to " Free disk "))).get("title"))
        assertContains(widget(dashboard, id)!!, "Free disk")
        assertContains(widget(dashboard, id)!!, "number · $id")

        browser.post("/dashboards/$dashboard/widgets/$id/delete", mapOf())
        assertEquals(null, widget(dashboard, id))
        assertEquals(400, browser.post("/dashboards/$dashboard/widgets", mapOf("type" to "pie")).status)
    }

    @Test
    fun `the layout is kept as it is made`() {
        val dashboard = add()
        val first = addWidget(dashboard)
        val second = addWidget(dashboard)
        browser.post("/dashboards/$dashboard/layout", mapOf("layout" to """[{"id":"$first","x":3,"y":0,"w":4,"h":2},{"id":"$second","x":0,"y":0,"w":3,"h":3}]"""))
        assertContains(widget(dashboard, first)!!, """gs-x="3" gs-y="0" gs-w="4" gs-h="2"""")
        assertContains(widget(dashboard, second)!!, """gs-x="0" gs-y="0" gs-w="3" gs-h="3"""")
    }

    @Test
    fun `a progress Widget shows done out of total, replaced by each Post, and refuses another shape with the shape it needs`() {
        val dashboard = add()
        val id = addWidget(dashboard, "progress")
        accepted("$dashboard/$id", """{"done":3,"total":10}""")
        accepted("$dashboard/$id", """{"done":7,"total":10}""")
        val body = body(dashboard, id)
        assertContains(body, """<div class="num">7<small> / 10</small></div>""")
        assertContains(body, """<div class="bar"><div style="width:70%"></div></div>""")
        assertEquals(
            "The progress Widget $dashboard/$id (New progress) takes {done: number, total: number}, but total is missing.",
            refused("$dashboard/$id", """{"done":7}"""),
        )
    }

    @Test
    fun `a number Post without a delta shows its change since the previous Post, and one with a delta shows that`() {
        val dashboard = add()
        val id = addWidget(dashboard)
        accepted("$dashboard/$id", """{"value":40,"unit":"GB"}""")
        assertFalse("since the last Post" in body(dashboard, id))
        accepted("$dashboard/$id", """{"value":42.5,"unit":"GB"}""")
        assertContains(body(dashboard, id), """<div class="delta up">▲ 2.5 since the last Post</div>""")
        accepted("$dashboard/$id", """{"value":39.5}""")
        assertContains(body(dashboard, id), """<div class="delta down">▼ 3 since the last Post</div>""")
        accepted("$dashboard/$id", """{"value":39.5,"delta":-10}""")
        assertContains(body(dashboard, id), """<div class="delta down">▼ 10 since the last Post</div>""")
    }

    @Test
    fun `a status Widget shows each item's state, keeps the newest 100 items of a longer Post, and refuses an unknown state`() {
        val dashboard = add()
        val id = addWidget(dashboard, "status")
        accepted("$dashboard/$id", """{"items":[{"name":"nas","state":"ok"},{"name":"router","state":"fail"}]}""")
        val body = body(dashboard, id)
        assertContains(body, """<div class="st"><span class="dot ok"></span><span class="n">nas</span><span class="s">ok</span></div>""")
        assertContains(body, """<span class="dot fail"></span><span class="n">router</span>""")

        accepted("$dashboard/$id", """{"items":[${(1..150).joinToString(",") { """{"name":"host $it","state":"running"}""" }}]}""")
        val names = Regex("""<span class="n">([^<]*)</span>""").findAll(body(dashboard, id)).map { it.groupValues[1] }.toList()
        assertEquals((51..150).map { "host $it" }, names)

        assertEquals(
            "The status Widget $dashboard/$id (New status) takes {items: [{name: string, state: ok | warn | fail | running}]}, but item 2's state is not ok, warn, fail or running; item 3's name is missing.",
            refused("$dashboard/$id", """{"items":[{"name":"a","state":"ok"},{"name":"b","state":"down"},{"state":"ok"}]}"""),
        )
    }

    @Test
    fun `a list Widget shows titles linking to a new tab with their notes, keeps the newest 100 items, and refuses a link that isn't http`() {
        val dashboard = add()
        val id = addWidget(dashboard, "list")
        accepted("$dashboard/$id", """{"items":[{"title":"Ants 1.2 out","url":"https://example.com/a?b=1&c=2","note":"Dashboards"},{"title":"Plain"}]}""")
        val body = body(dashboard, id)
        assertContains(body, """<div class="li"><a href="https://example.com/a?b=1&amp;c=2" target="_blank" rel="noopener">Ants 1.2 out</a><div>Dashboards</div></div>""")
        assertContains(body, """<div class="li">Plain</div>""")

        accepted("$dashboard/$id", """{"items":[${(1..101).joinToString(",") { """{"title":"story $it"}""" }}]}""")
        val titles = Regex("""<div class="li">([^<]*)</div>""").findAll(body(dashboard, id)).map { it.groupValues[1] }.toList()
        assertEquals((2..101).map { "story $it" }, titles)

        assertEquals(
            "The list Widget $dashboard/$id (New list) takes {items: [{title: string, url?: string, note?: string}]}, but item 1's url is not an http or https address.",
            refused("$dashboard/$id", """{"items":[{"title":"x","url":"javascript:alert(1)"}]}"""),
        )
        assertContains(refused("$dashboard/$id", """{"items":"none"}"""), "but items is not a list.")
    }

    @Test
    fun `a text Widget renders its text as Markdown with its HTML escaped, replaced by each Post`() {
        val dashboard = add()
        val id = addWidget(dashboard, "text")
        accepted("$dashboard/$id", """{"text":"old"}""")
        accepted("$dashboard/$id", """{"text":"Line **bold**\nLine <two>"}""")
        assertContains(body(dashboard, id), "<div class=\"text md\"><p>Line <strong>bold</strong>\nLine &lt;two&gt;</p>\n</div>")
        assertFalse(">old<" in body(dashboard, id))
        assertEquals("The text Widget $dashboard/$id (New text) takes {text: string (Markdown)}, but text is not text.", refused("$dashboard/$id", """{"text":3}"""))
    }

    @Test
    fun `a text Widget renders GFM tables, code blocks and headings inside the Markdown style`() {
        val dashboard = add()
        val id = addWidget(dashboard, "text")
        accepted("$dashboard/$id", """{"text":"# Head\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```\ncode\n```"}""")
        val rendered = body(dashboard, id).substringAfter("<div class=\"text md\">")
        assertContains(rendered, "<h1>Head</h1>")
        assertContains(rendered, "<td>1</td>")
        assertContains(rendered, "<pre><code>code\n</code></pre>")
    }

    @Test
    fun `every Widget's text renders its Markdown, so no star of it shows`() {
        val dashboard = add()
        val shown = mapOf(
            "number" to """{"value":3,"unit":"**GB** free"}""",
            "status" to """{"items":[{"name":"**db** <one>","state":"ok"}]}""",
            "list" to """{"items":[{"title":"**PR** 12","url":"https://example.com"},{"title":"*plain*","note":"by **ana** in `main`"}]}""",
            "log" to """{"line":"**deployed** v2"}""",
        ).mapValues { (type, data) -> addWidget(dashboard, type).also { accepted("$dashboard/$it", data) } }.mapValues { (_, id) -> body(dashboard, id) }
        assertContains(shown.getValue("number"), "<small><strong>GB</strong> free</small>")
        assertContains(shown.getValue("status"), "<span class=\"n\"><strong>db</strong> &lt;one&gt;</span>")
        assertContains(shown.getValue("list"), "rel=\"noopener\"><strong>PR</strong> 12</a>")
        assertContains(shown.getValue("list"), "<em>plain</em><div>by <strong>ana</strong> in <code>main</code></div>")
        assertContains(shown.getValue("log"), "<strong>deployed</strong> v2</div>")
        shown.values.forEach { assertFalse("**" in it, it) }
    }

    @Test
    fun `a chart's labels show without their Markdown`() {
        val dashboard = add()
        val id = addWidget(dashboard, "chart")
        accepted("$dashboard/$id", """{"point":{"x":"**Mon**","y":2,"series":"*builds*"}}""")
        assertContains(body(dashboard, id), "&#34;x&#34;:&#34;Mon&#34;")
        assertFalse("**" in body(dashboard, id))
    }

    @Test
    fun `a text Widget renders no javascript link live`() {
        val dashboard = add()
        val id = addWidget(dashboard, "text")
        accepted("$dashboard/$id", """{"text":"[click](javascript:alert(1))"}""")
        assertFalse("javascript:" in body(dashboard, id))
    }

    @Test
    fun `a log Widget appends each line with its time and level, keeps 90 days unless the user sets other, and refuses an unknown level`() {
        val dashboard = add()
        val id = addWidget(dashboard, "log")
        val log = "$dashboard/$id"
        assertContains(widget(dashboard, id)!!, """class="keep" min="1" max="3650" value="90"""")
        try {
            accepted(log, """{"line":"started"}""")
            clock.offset = java.time.Duration.ofDays(60)
            accepted(log, """{"line":"disk low","level":"warn"}""")
            clock.offset = java.time.Duration.ofDays(91)
            accepted(log, """{"line":"failed","level":"error"}""")
            val lines = Regex("""<div class="(\w+)"><time datetime="[^"]+">[^<]+</time>([^<]*)</div>""").findAll(body(dashboard, id)).map { it.groupValues[1] to it.groupValues[2] }.toList()
            assertEquals(listOf("warn" to "disk low", "error" to "failed"), lines)

            assertEquals(20, answer(browser.post("/dashboards/$dashboard/widgets/$id/keep", mapOf("days" to "20")))["keep"])
            assertContains(widget(dashboard, id)!!, """value="20"""")
            assertEquals(listOf("failed"), Regex("""</time>([^<]+)</div>""").findAll(body(dashboard, id)).map { it.groupValues[1] }.toList())
            assertEquals(400, browser.post("/dashboards/$dashboard/widgets/$id/keep", mapOf("days" to "0")).status)
        } finally {
            clock.offset = java.time.Duration.ZERO
        }
        assertEquals(400, browser.post("/dashboards/$dashboard/widgets/${addWidget(dashboard, "text")}/keep", mapOf("days" to "5")).status)
        assertEquals(
            "The log Widget $log (New log) takes {line: string, level?: info | warn | error}, but level is not info, warn or error.",
            refused(log, """{"line":"x","level":"debug"}"""),
        )
    }

    @Test
    fun `a Post over 256 KB is refused with a message saying so`() {
        val dashboard = add()
        val id = addWidget(dashboard, "text")
        accepted("$dashboard/$id", """{"text":"${"a".repeat(200 * 1024)}"}""")
        assertEquals(
            "The text Widget $dashboard/$id (New text) takes {text: string (Markdown)} in Posts of up to 256 KB, but this one is 301 KB.",
            refused("$dashboard/$id", """{"text":"${"a".repeat(300 * 1024)}"}"""),
        )
    }

    @Test
    fun `Clear data empties a Widget, which keeps its id and title and waits for its next Post`() {
        val dashboard = add()
        val id = addWidget(dashboard, "log")
        browser.post("/dashboards/$dashboard/widgets/$id/title", mapOf("title" to "Backups"))
        accepted("$dashboard/$id", """{"line":"one"}""")
        assertContains(widget(dashboard, id)!!, ">Clear data</button>")
        assertContains(answer(browser.post("/dashboards/$dashboard/widgets/$id/clear", mapOf()))["body"].toString(), "Waiting for its first Post")
        val drawn = widget(dashboard, id)!!
        assertContains(drawn, "Waiting for its first Post · <code>$dashboard/$id</code>")
        assertContains(drawn, """<span class="t" spellcheck="false">Backups</span>""")
        accepted("$dashboard/$id", """{"line":"two"}""")
        assertEquals(listOf("two"), Regex("""</time>([^<]+)</div>""").findAll(body(dashboard, id)).map { it.groupValues[1] }.toList())
    }

    /** **+ Widget**, a chart of [kind]: the new Widget's id. */
    private fun addChart(dashboard: String, kind: String = "line"): String =
        answer(browser.post("/dashboards/$dashboard/widgets", mapOf("type" to "chart", "kind" to kind)))["id"].toString()

    /** The chart [widget] as its page draws it: its kind and its points, `series x=y` each, in the order kept. */
    private fun chart(dashboard: String, widget: String): Pair<String, List<String>> {
        val drawn = Regex("""<div class="chart" data-kind="([^"]+)" data-points="([^"]*)"""").find(body(dashboard, widget))!!
        val points = JsonMapper.shared().readValue(HtmlUtils.htmlUnescape(drawn.groupValues[2]), List::class.java)
        return drawn.groupValues[1] to points.map { (it as Map<*, *>).let { p -> "${p["series"] ?: "-"} ${p["x"]}=${p["y"]}" } }
    }

    @Test
    fun `a chart takes one point or a batch, adds each, and one with the same series and x as a stored one replaces it`() {
        val dashboard = add()
        val id = addChart(dashboard, "bar")
        assertContains(widget(dashboard, id)!!, "New bar chart")
        assertContains(widget(dashboard, id)!!, "chart · $id")
        assertContains(widget(dashboard, id)!!, "Waiting for its first Post")
        val address = "$dashboard/$id"
        accepted(address, """{"point":{"x":"2026-11-01","y":3}}""")
        accepted(address, """{"points":[{"x":"2026-11-02","y":5},{"x":"2026-11-01","y":4,"series":"merged"},{"x":7,"y":1.5,"series":"merged"},{"x":"mon","y":2}]}""")
        accepted(address, """{"point":{"x":"2026-11-01","y":9}}""")
        accepted(address, """{"points":[{"x":7.0,"y":2,"series":"merged"}]}""")
        assertEquals(
            "bar" to listOf("- 2026-11-02=5", "merged 2026-11-01=4", "- mon=2", "- 2026-11-01=9", "merged 7.0=2"),
            chart(dashboard, id),
        )
        assertContains(body(dashboard, id), """<a href="/runs/r-post">""")
    }

    @Test
    fun `a chart refuses a point without a number y or with an x that is not a number, a date or text, naming the shape`() {
        val dashboard = add()
        val address = "$dashboard/${addChart(dashboard)}"
        val named = "The chart Widget $address (New line chart) takes {point: {x: number | date | text, y: number, series?: string}} or {points: [{x, y, series?}, …]}, but"
        assertEquals("$named point's y is not a number.", refused(address, """{"point":{"x":1,"y":"3"}}"""))
        assertEquals(
            "$named point 2's x is missing; point 3's x is not a number, a date or text.",
            refused(address, """{"points":[{"x":1,"y":1},{"y":2},{"x":true,"y":3}]}"""),
        )
        assertEquals("$named point or points is missing; it takes no value.", refused(address, """{"value":1}"""))
        assertEquals("$named points is not a list.", refused(address, """{"points":{"x":1,"y":1}}"""))
        assertEquals("$named it takes point or points, not both.", refused(address, """{"point":{"x":1,"y":1},"points":[]}"""))
        assertEquals("$named point takes no label.", refused(address, """{"point":{"x":1,"y":1,"label":"a"}}"""))
    }

    @Test
    fun `a chart keeps its points 90 days unless the user sets other, and switching its kind keeps what it holds and takes`() {
        val dashboard = add()
        val id = addChart(dashboard)
        val address = "$dashboard/$id"
        assertContains(widget(dashboard, id)!!, """class="keep" min="1" max="3650" value="90"""")
        try {
            accepted(address, """{"point":{"x":"a","y":1}}""")
            clock.offset = java.time.Duration.ofDays(60)
            accepted(address, """{"point":{"x":"b","y":2}}""")
            clock.offset = java.time.Duration.ofDays(91)
            accepted(address, """{"point":{"x":"c","y":3}}""")
            assertEquals("line" to listOf("- b=2", "- c=3"), chart(dashboard, id))
            browser.post("/dashboards/$dashboard/widgets/$id/keep", mapOf("days" to "20"))
            assertEquals("line" to listOf("- c=3"), chart(dashboard, id))
        } finally {
            clock.offset = java.time.Duration.ZERO
        }

        val switched = answer(browser.post("/dashboards/$dashboard/widgets/$id/kind", mapOf("kind" to "donut")))
        assertEquals("donut", switched["kind"])
        assertContains(switched["body"].toString(), """data-kind="donut"""")
        assertContains(widget(dashboard, id)!!, """<option value="donut" selected>Donut</option>""")
        accepted(address, """{"point":{"x":"d","y":4}}""")
        assertEquals("donut" to listOf("- d=4"), chart(dashboard, id).let { (kind, points) -> kind to points.filter { "d=" in it } })
        assertEquals(400, browser.post("/dashboards/$dashboard/widgets/$id/kind", mapOf("kind" to "radar")).status)
        assertEquals(400, browser.post("/dashboards/$dashboard/widgets/${addWidget(dashboard)}/kind", mapOf("kind" to "pie")).status)
    }

    @Test
    fun `+ Widget lists the chart kinds and Custom chart under Charts, drawn by Vega-Lite bundled with Ants and its licence notices`() {
        val page = browser.get("/dashboards/${add()}").body
        val menu = page.substringAfter("""id="add-menu"""").substringBefore("</div></span>")
        assertEquals(
            listOf("Line", "Area", "Bar", "Stacked bar", "Scatter", "Pie", "Donut", "Custom chart"),
            Regex("""<b>([^<]+)</b>""").findAll(menu.substringAfter("Charts · all take points {x, y, series?}")).map { it.groupValues[1] }.toList(),
        )
        assertFalse(">chart<" in menu)
        for (library in listOf("vega", "vega-lite", "vega-embed")) {
            assertContains(page, """<script src="/vendor/vega/$library.min.js"></script>""")
            assertEquals(200, browser.get("/vendor/vega/$library.min.js").status)
            assertContains(browser.get("/vendor/vega/$library.LICENSE").body, "University of Washington Interactive Data Lab")
        }
        assertFalse("cdn" in page.lowercase())
    }

    @Test
    fun `a custom chart takes a Vega-Lite spec with its data inside, replaced by each Post, and refuses one naming a URL`() {
        val dashboard = add()
        val id = addWidget(dashboard, "custom chart")
        val address = "$dashboard/$id"
        assertContains(widget(dashboard, id)!!, "New custom chart")
        assertContains(widget(dashboard, id)!!, "custom chart · $id")
        accepted(address, """{"spec":{"mark":"bar","data":{"values":[{"a":1}]}}}""")
        accepted(address, """{"spec":{"mark":"rect","data":{"values":[{"a":2,"link":"https://example.com"}]},"encoding":{"x":{"field":"a"}}}}""")
        val spec = Regex("""<div class="chart" data-spec="([^"]*)"""").find(body(dashboard, id))!!.groupValues[1]
        assertEquals(
            mapOf("mark" to "rect", "data" to mapOf("values" to listOf(mapOf("a" to 2, "link" to "https://example.com"))), "encoding" to mapOf("x" to mapOf("field" to "a"))),
            JsonMapper.shared().readValue(HtmlUtils.htmlUnescape(spec), Map::class.java),
        )

        val named = "The custom chart Widget $address (New custom chart) takes {spec: Vega-Lite with its data in data.values}, but"
        assertEquals(
            "$named spec.data.url names a URL, and a custom chart loads nothing from outside: put its data in data.values.",
            refused(address, """{"spec":{"mark":"bar","data":{"url":"https://example.com/data.csv"}}}"""),
        )
        assertEquals(
            "$named spec.layer[1].encoding.url names a URL, and a custom chart loads nothing from outside: put its data in data.values.",
            refused(address, """{"spec":{"layer":[{"mark":"bar"},{"mark":"image","encoding":{"url":{"field":"img"}}}]}}"""),
        )
        assertEquals("$named spec is missing.", refused(address, """{}"""))
        assertEquals("$named spec is not an object.", refused(address, """{"spec":"bar"}"""))
    }
}
