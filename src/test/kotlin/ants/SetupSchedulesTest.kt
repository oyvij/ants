package ants

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A Setup carries Schedules in `setup.yaml`, landing paused, and Dashboards as their layout, with no Posts. */
class SetupSchedulesTest : AntsTest() {
    @Autowired
    lateinit var setup: Setup

    @Autowired
    lateinit var schedules: Schedules

    private fun entries(zip: ByteArray): Map<String, String> = ZipInputStream(ByteArrayInputStream(zip)).use { input ->
        generateSequence { input.nextEntry }.filterNot { it.isDirectory }.associate { it.name to input.readBytes().decodeToString() }
    }

    /** Every item of [preview] ticked, as the preview starts. */
    private fun all(preview: Setup.Preview) = preview.items.associate { it.key to Setup.Choice() }

    @Test
    fun `an imported Schedule keeps its id and values and lands paused, which the report says`() {
        val other = tempHome()
        val (zip, id) = startAnother(other).use { ants ->
            ants.getBean(Processes::class.java).deploy(javaClass.getResource("/start-end.bpmn")!!.readBytes())
            val id = ants.getBean(Schedules::class.java).add("start-end", mapOf(NAME to listOf("Nightly")), "0 0 8 * * MON-FRI")
            ants.getBean(Setup::class.java).export() to id
        }
        val written = entries(zip).getValue("setup.yaml")
        assertContains(written, id)
        assertFalse("last" in written, written)

        val preview = setup.preview(zip)
        assertEquals(Setup.Status.NEW, preview.items.single { it.noun == "Schedule" }.status)
        val report = setup.import(zip, all(preview)).single { it.noun == "Schedule" }
        assertEquals(Setup.Outcome.ADDED, report.outcome, report.toString())
        assertContains(report.reason!!, "Paused")

        val schedule = schedules.of("start-end").single()
        assertEquals(id, schedule.id)
        assertTrue(schedule.paused)
        assertEquals("0 0 8 * * MON-FRI", schedule.cron)
        assertEquals("Nightly", schedule.label)
    }

    @Autowired
    lateinit var dashboards: Dashboards

    @Test
    fun `a Dashboard round-trips with its Widget ids and no Posts, and a Script Posting to its Widget works after Import`() {
        val other = tempHome()
        val (zip, dashboard) = startAnother(other).use { ants ->
            val there = ants.getBean(Dashboards::class.java)
            val dashboard = there.rename(there.create().id, "Disks")
            there.addWidget(dashboard.id, WidgetType.NUMBER, named = "disk", titled = "Free disk")
            there.post(WidgetAddress(dashboard.id, "disk"), mapOf("value" to 1), "r-old", "t-old")
            val script = Script("s-disk01", "measure-disk", "Measures the disk.", widgets = listOf("${dashboard.id}/disk"))
            ants.getBean(Scripts::class.java).save(script, "await ants.post('${dashboard.id}/disk', {value: 7})\n", emptyMap())
            ants.getBean(Setup::class.java).export() to dashboard.id
        }
        assertEquals(setOf("dashboards/$dashboard/dashboard.yaml"), entries(zip).keys.filter { it.startsWith("dashboards/") }.toSet())

        val report = setup.import(zip, all(setup.preview(zip)))
        assertEquals(listOf(Setup.Outcome.ADDED), report.map { it.outcome }.distinct(), report.toString())
        val landed = dashboards.get(dashboard)
        assertEquals("Disks", landed.title)
        val widget = landed.widget("disk")!!
        assertEquals("Free disk", widget.title)
        assertEquals(emptyList(), dashboards.posts(dashboard, widget))

        settings(null, "node: {path: ${ScriptsTest.NODE}}\n")
        val script = scripts.withId("s-disk01")!!
        val run = runner.run(script, emptyMap(), scripts.where(script).resolve("script.mjs"), Files.createTempDirectory("workspace"), "r-new", emptyMap<String, Any>(), Sender(script, "r-new", "t-new"))
        assertEquals(0, run.exitCode, run.output)
        assertEquals(listOf(7), dashboards.posts(dashboard, widget).map { it.data["value"] })
    }

    @Autowired
    lateinit var scripts: Scripts

    @Autowired
    lateinit var runner: ScriptRunner
}
