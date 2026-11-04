package ants

import org.junit.jupiter.api.Test
import org.springframework.boot.web.server.context.WebServerApplicationContext
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/** Dashboards (ADR 0032): made, named and deleted from the menu, each kept in its own folder in ANTS_HOME. */
class DashboardsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

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
}
