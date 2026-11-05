package ants

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.util.HtmlUtils
import tools.jackson.databind.json.JsonMapper
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Scripts and the Widgets they Post to link by address (ADR 0032): Publish refuses a Script listing a deleted Widget,
 * each Widget shows the Scripts that use it, deleting one asks first, and a test run Posts nothing.
 */
class WidgetLinksTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val json = JsonMapper.shared()

    @BeforeEach
    fun settings() {
        home.resolve("settings.yaml").writeText("node: {path: ${ScriptsTest.NODE}}\nclaude: {env: [PATH, HOME]}\n")
    }

    private fun dashboard(): String {
        browser.get("/")
        return browser.post("/dashboards", mapOf()).path.removePrefix("/dashboards/")
    }

    private fun widget(dashboard: String, title: String): String {
        val id = answer(browser.post("/dashboards/$dashboard/widgets", mapOf("type" to "number")))["id"].toString()
        browser.post("/dashboards/$dashboard/widgets/$id/title", mapOf("title" to title))
        return id
    }

    private fun form(name: String, widgets: String, code: String = "console.log('hi')\n") =
        listOf("name" to name, "description" to "Does $name.", "code" to code, "vars" to "", "widgets" to widgets)

    /** The Widget [widget]'s grid item and its Posting panel row, as its Dashboard's page draws them. */
    private fun shown(dashboard: String, widget: String): Pair<String, String> {
        val page = browser.get("/dashboards/$dashboard").body
        return page.substringAfter("""gs-id="$widget"""").substringBefore("</article>") to
            page.substringAfter("""<dt data-widget="$widget">""").substringBefore("</dd>")
    }

    @Test
    fun `Publish refuses a Script whose Widgets name one that no longer exists, with a Check naming it`() {
        val dashboard = dashboard()
        val disk = widget(dashboard, "Free disk")
        val gone = widget(dashboard, "Old")
        browser.post("/dashboards/$dashboard/widgets/$gone/delete", mapOf())

        val refused = browser.publishNew("/scripts", form("measure", "$dashboard/$disk\n$dashboard/$gone"))
        assertContains(refused.body, "wasn't published")
        assertContains(HtmlUtils.htmlUnescape(refused.body), "<li>Widgets lists $dashboard/$gone, which is no Widget: it or its Dashboard was deleted, or the address is wrong.</li>")
        assertFalse("$dashboard/$disk, which" in refused.body)

        assertEquals("/scripts/measure", browser.post("/scripts/measure", form("measure", "$dashboard/$disk")).path)
    }

    @Test
    fun `each Widget shows the Scripts that use it, and deleting a used Widget or Dashboard names what would break`() {
        val dashboard = dashboard()
        val disk = widget(dashboard, "Free disk")
        val idle = widget(dashboard, "Idle")
        browser.publishNew("/scripts", form("measure", "$dashboard/$disk"))
        browser.publishNew("/scripts", form("report", "$dashboard/$disk"))

        val (item, row) = shown(dashboard, disk)
        assertContains(item, """Used by: <a href="/scripts/measure">measure</a>, <a href="/scripts/report">report</a>""")
        assertContains(row, """Used by: <a href="/scripts/measure">measure</a>, <a href="/scripts/report">report</a>""")
        assertContains(item, """data-used-by="measure, report"""")
        assertContains(shown(dashboard, idle).first, "Used by: no Script")

        val confirm = browser.post("/dashboards/$dashboard/delete", mapOf())
        assertContains(confirm.body, "These use it by name and will break.")
        assertContains(confirm.body, """<li>Script <a href="/scripts/measure">measure</a></li>""")
        assertContains(confirm.body, """<li>Script <a href="/scripts/report">report</a></li>""")
        assertContains(browser.get("/scripts/measure").body, "Used by: no Agent or Process") // a Script's own Used by is unchanged
    }

    @Test
    fun `a test run from the script editor checks each Post against its Widget and reports would Post without storing it`() {
        val dashboard = dashboard()
        val disk = widget(dashboard, "Free disk")
        val other = widget(dashboard, "Other")
        val code = """
            await ants.post('$dashboard/$disk', {value: 42, unit: 'GB'})
            const refused = []
            for (const [address, data] of [['$dashboard/$disk', {value: 'lots'}], ['$dashboard/$other', {value: 1}]]) {
                try { await ants.post(address, data) } catch (error) { refused.push(error.message) }
            }
            ants.output({refused})
        """.trimIndent()
        browser.get("/scripts")
        val run = json.readValue(browser.post("/scripts/test-run", form("measure", "$dashboard/$disk", code) + ("sample" to "{}")).body, Map::class.java)
        assertEquals(0, run["exitCode"], run["output"].toString())
        assertContains(run["output"].toString(), """would Post {"value":42,"unit":"GB"} to $dashboard/$disk""")
        assertEquals(
            listOf(
                "The number Widget $dashboard/$disk (Free disk) takes {value: number, unit?: string, delta?: number}, but value is not a number.",
                "The Script measure doesn't list the Widget $dashboard/$other under its Widgets, so it may not Post to it.",
            ),
            (run["payload"] as Map<*, *>)["refused"],
        )
        assertContains(shown(dashboard, disk).first, "Waiting for its first Post")
    }
}
