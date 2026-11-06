package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.history.HistoricProcessInstance
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Alerts: every new entry on the Notification list reaches each device with Alerts on, through its push service. */
class AlertsTest : AntsTest() {
    @Autowired
    lateinit var history: HistoryService

    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()
    private val device = FakePushService()

    @AfterEach
    fun closeDevice() = device.close()

    private fun result(result: String) = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":$result}"""

    /** Turns Alerts on for [device], as the Settings page does with the browser's subscription. */
    private fun turnOn(device: FakePushService = this.device, name: String = "Test browser") {
        browser.get("/settings")
        assertEquals(200, browser.postBytes("/alerts/devices", device.subscription(name).toByteArray()).status)
    }

    /** The devices Settings lists with Alerts on: each one's name and when it was added, as its row reads. */
    private fun listed(): List<String> = Regex("""<li data-endpoint="[^"]*">(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(browser.get("/settings").body).map { it.groupValues[1].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim() }.toList()

    /** Presses **Remove** on the device [name] in Settings' list. */
    private fun removeInSettings(name: String) {
        val page = browser.get("/settings").body
        val row = Regex("""<li data-endpoint="[^"]*">((?:(?!</li>).)*?$name(?:(?!</li>).)*)</li>""", RegexOption.DOT_MATCHES_ALL).find(page)!!.groupValues[1]
        val button = Regex("""<button formaction="([^"]+)" name="endpoint" value="([^"]+)">Remove</button>""").find(row)!!
        assertEquals("/settings", browser.post(button.groupValues[1], mapOf("endpoint" to button.groupValues[2].replace("&amp;", "&"))).path)
    }

    /** The one Alert the push service received next. */
    private fun next() = assertNotNull(device.received.poll(15, SECONDS), "no Alert reached the push service")

    /** The Run's row on the Notification list, or null. */
    private fun row(run: String) = Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
        .find(browser.get("/notifications").body)?.groupValues?.get(1)

    /** The address of the Run's entry and the Run's name, as the Notification list shows them. */
    private fun entry(run: String): Pair<String, String> {
        var found = "" to ""
        eventually {
            val row = assertNotNull(row(run))
            found = Regex("""href="(/notifications/[^"]+)"""").find(row)!!.groupValues[1] to Regex("""<a href="/runs/$run">([^<]+)</a>""").find(row)!!.groupValues[1]
        }
        return found
    }

    @Test
    fun `the fake push service decrypts RFC 8291's example message`() {
        // RFC 8291, Appendix A: the user agent's keys and the message the application server sent it.
        val keys = FakePushService.keyPair(
            FakePushService.b64("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"),
            FakePushService.b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
        )
        val body = FakePushService.b64(
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
        )
        assertEquals("When I grow up, I want to be a watermelon", FakePushService.decrypt(body, keys, FakePushService.b64("BTBZMqHH6r4Tts7J_aSIgg")))
    }

    @Test
    fun `each new entry, a stuck Agent Task then an Approve, sends one Alert holding its tag, summary, Run name and address`() {
        agentTask(claude, mapOf("draft" to """{"type":"object","properties":{"notes":{"type":"string","format":"path"}}}""", "polish" to null))
        turnOn()

        claude.session("draft", result("""{"outcome":"stuck","summary":"No tone given.","needs":"Which tone, dry or warm?"}"""))
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        val stuck = browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
        val (stuckEntry, stuckName) = entry(stuck)
        val first = next()
        assertEquals("aes128gcm", first.headers["content-encoding"])
        assertTrue(first.headers.getValue("ttl").toInt() > 0)
        val claims = first.vapid()
        assertEquals(device.origin, claims["aud"])
        assertTrue((claims["exp"] as Number).toLong() in FakePushService.now()..FakePushService.now() + 24 * 3600)
        assertTrue("${claims["sub"]}".startsWith("mailto:") || "${claims["sub"]}".startsWith("https:"))
        val alert = first.payload()
        assertEquals("[stuck]", alert["tag"])
        assertEquals("No tone given.", alert["text"])
        assertEquals(stuckName, alert["run"])
        assertEquals(stuckEntry, alert["url"])

        claude.session("draft", result("""{"outcome":"done","summary":"Drafted.","output":{"notes":"notes.md"}}"""))
        browser.import("approve.bpmn")
        browser.get("/processes/approve")
        val approve = browser.post("/processes/approve/runs", emptyMap()).path.removePrefix("/runs/")
        workspace(approve).resolve("notes.md").writeText("# Notes\n")
        val (approveEntry, approveName) = entry(approve)
        val second = next().payload()
        assertEquals("[approve]", second["tag"])
        assertEquals(approveName, second["run"])
        assertEquals(approveEntry, second["url"])

        assertNull(device.received.poll(3, SECONDS), "an entry alerted twice")
    }

    @Test
    fun `a Round to answer and a Permission request each send one Alert holding its tag and summary`() {
        agentTask(
            claude,
            mapOf("draft" to null, "quiz" to """{"type":"object","properties":{"questions":{"type":"array"}}}""", "stand-in" to """{"type":"object","properties":{"answers":{"type":"array"}}}"""),
            agent = "tools: Bash(git log *)\n",
        )
        turnOn()

        claude.session("quiz", result("""{"outcome":"done","summary":"First round.","output":{"questions":[{"id":"who","title":"Who exports","ask":"Ask who?","context":"Settles who.","recommendation":"Recommend who.","why":"Because who."}]}}"""))
        browser.import("round.bpmn")
        browser.get("/processes/round")
        val round = browser.post("/processes/round/runs", emptyMap()).path.removePrefix("/runs/")
        val (roundEntry, roundName) = entry(round)
        val asked = next().payload()
        assertEquals("[questions]", asked["tag"])
        assertEquals("First round.", asked["text"])
        assertEquals(roundName, asked["run"])
        assertEquals(roundEntry, asked["url"])

        claude.session(
            "draft",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"cargo outdated"}}]},"session_id":"${'$'}SESSION"}""",
            """{"type":"system","subtype":"permission_denied","tool_name":"Bash","tool_use_id":"toolu_1","decision_reason_type":"mode","session_id":"${'$'}SESSION"}""",
            "read",
            """{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"${'$'}SESSION"}""",
            "exit 1",
        )
        browser.import("agent-task.bpmn")
        browser.get("/processes/agent-task")
        val refused = browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello")).path.removePrefix("/runs/")
        val (permissionEntry, _) = entry(refused)
        val permission = next().payload()
        assertEquals("[permission]", permission["tag"])
        assertEquals("writer wants to run `cargo outdated`", permission["text"])
        assertEquals(permissionEntry, permission["url"])

        assertNull(device.received.poll(3, SECONDS), "an entry alerted twice")
    }

    @Test
    fun `a scheduled start that fails sends one Alert leading to its entry`() {
        settings(null)
        turnOn()
        browser.import("notify.bpmn")
        browser.get("/processes/watch")
        browser.post("/processes/watch/schedule", mapOf("pipeline" to "api-build", NAME to "Nightly watch", "cron" to "0 0 0 * * *"))
        val id = Regex("""<tr id="schedule-([^"]+)">""").find(browser.get("/processes/watch").body)!!.groupValues[1]
        val required = """<operaton:formField id="pipeline" label="Pipeline" type="string" />
            <operaton:formField id="branch" label="Branch" type="string"><operaton:validation><operaton:constraint name="required" /></operaton:validation></operaton:formField>"""
        browser.upload("/processes", "file", "notify.bpmn", javaClass.getResource("/notify.bpmn")!!.readText()
            .replace("""<operaton:formField id="pipeline" label="Pipeline" type="string" />""", required).toByteArray())
        browser.get("/processes/watch")
        browser.post("/processes/watch/schedules/$id/start", emptyMap())

        val alert = next().payload()
        assertEquals("[failed]", alert["tag"])
        assertEquals("/notifications/schedule-$id", alert["url"])
        assertTrue("Branch is required" in "${alert["text"]}", "${alert["text"]}")
        assertNull(device.received.poll(2, SECONDS), "the failed start alerted twice")
    }

    @Test
    fun `entries already on the Notification list when Ants starts send no Alert`() {
        val home = tempHome()
        fun url(ants: Any) = "http://127.0.0.1:${(ants as org.springframework.boot.web.server.context.WebServerApplicationContext).webServer!!.port}"
        startAnother(home).use { ants ->
            val browser = Browser(url(ants)).also { it.login() }
            home.resolve("settings.yaml").writeAtomically(vault)
            browser.import("notify.bpmn")
            browser.get("/processes/watch")
            browser.post("/processes/watch/runs", mapOf("pipeline" to "api-build", NAME to "Nightly watch"))
            eventually { assertTrue("[notice]" in browser.get("/notifications").body) }
            browser.get("/settings")
            assertEquals(200, browser.postBytes("/alerts/devices", device.subscription().toByteArray()).status)
        }
        device.received.clear()
        startAnother(home).use { ants ->
            val browser = Browser(url(ants)).also { it.login() }
            assertTrue("[notice]" in browser.get("/notifications").body)
            assertNull(device.received.poll(3, SECONDS), "an entry that was there at startup alerted")
        }
    }

    @Test
    fun `a push endpoint must be https, unless it is on this machine`() {
        browser.get("/settings")
        val subscription = device.subscription()
        for (endpoint in listOf("http://push.example.com/device", "ftp://127.0.0.1/device", "http://127.0.0.1.example.com/device")) {
            assertEquals(400, browser.postBytes("/alerts/devices", subscription.replace(device.origin + "/push/device", endpoint).toByteArray()).status, endpoint)
        }
        for (endpoint in listOf("https://push.example.com/device", "http://localhost:9/device", "http://[::1]:9/device", device.origin + "/push/device")) {
            assertEquals(200, browser.postBytes("/alerts/devices", subscription.replace(device.origin + "/push/device", endpoint).toByteArray()).status, endpoint)
        }
    }

    @Test
    fun `a Notice sends one Alert holding its line and its Run's name`() {
        settings(null)
        turnOn()
        browser.import("notify.bpmn")
        browser.get("/processes/watch")
        val run = browser.post("/processes/watch/runs", mapOf("pipeline" to "api-build", NAME to "Nightly watch")).path.removePrefix("/runs/")
        val (entry, name) = entry(run)
        val alert = next().payload()
        assertEquals("[notice]", alert["tag"])
        assertEquals("Pipeline api-build failed on main", alert["text"])
        assertEquals("Nightly watch", name)
        assertEquals(name, alert["run"])
        assertEquals(entry, alert["url"])
        assertNull(device.received.poll(2, SECONDS), "the Notice alerted twice")
    }

    @Test
    fun `a Notice that only counts up sends no Alert`() {
        settings(null)
        turnOn()
        browser.import("notify.bpmn")
        browser.get("/processes/watch")
        browser.post("/processes/watch/runs", mapOf("pipeline" to "api-build", NAME to "Nightly watch"))
        assertEquals("[notice]", next().payload()["tag"])
        browser.post("/processes/watch/runs", mapOf("pipeline" to "api-build", NAME to "Later watch"))
        eventually { assertTrue("×2, last at" in browser.get("/notifications").body) }
        assertNull(device.received.poll(3, SECONDS), "a Notice that counted up alerted again")
    }

    @Test
    fun `Send a test Alert sends one to this device, signed with the key the Settings page gives the browser`() {
        turnOn()
        val key = Regex("""data-key="([^"]+)"""").find(browser.get("/settings").body)!!.groupValues[1]
        assertEquals(200, browser.postBytes("/alerts/test", device.subscription().toByteArray()).status)
        val test = next()
        test.vapid()
        assertEquals(key, test.key)
        assertEquals("/settings", test.payload()["url"])
        assertNull(device.received.poll(2, SECONDS))
    }

    @Test
    fun `a device turned off gets no more Alerts, and a push service that fails holds up nothing`() {
        turnOn()
        val other = FakePushService().apply { status = 500 }
        try {
            turnOn(other)
            assertEquals(200, browser.postBytes("/alerts/devices/remove", device.subscription().toByteArray()).status)
            assertEquals("The service that delivers this device's Alerts answered 500.", browser.postBytes("/alerts/test", other.subscription().toByteArray()).body)
            assertNotNull(other.received.poll(10, SECONDS))
            assertEquals(404, browser.postBytes("/alerts/test", device.subscription().toByteArray()).status)
            assertNull(device.received.poll(1, SECONDS))
        } finally {
            other.close()
        }
    }

    @Test
    fun `the VAPID key pair is made once and kept in ANTS_HOME`() {
        val home = tempHome()
        fun key() = AntsTest.startAnother(home).use { ants ->
            val url = "http://127.0.0.1:${(ants as org.springframework.boot.web.server.context.WebServerApplicationContext).webServer!!.port}"
            val page = Browser(url).also { it.login() }.get("/settings").body
            Regex("""data-key="([^"]+)"""").find(page)!!.groupValues[1]
        }
        val first = key()
        assertEquals(65, FakePushService.b64(first).size)
        assertEquals(first, key())
    }

    @Test
    fun `Settings lists each device with Alerts on, by its name and when it was added, and Remove forgets it`() {
        assertEquals(emptyList(), listed())
        turnOn(device, "Chrome on Android")
        FakePushService().use { other ->
            turnOn(other, "Firefox on Linux")
            val rows = listed()
            assertEquals(2, rows.size, "$rows")
            assertTrue(rows[0].matches(Regex("""Chrome on Android added \d\d:\d\d Remove""")), rows[0])
            assertTrue(rows[1].startsWith("Firefox on Linux added "), rows[1])

            removeInSettings("Chrome on Android")
            assertEquals(listOf("Firefox on Linux"), listed().map { it.substringBefore(" added") })
            assertEquals(404, browser.postBytes("/alerts/test", device.subscription().toByteArray()).status)
            assertNull(device.received.poll(1, SECONDS))
        }
    }

    @Test
    fun `a push service answering 410 or 404, that the device is gone, removes the device`() {
        FakePushService().use { other ->
            device.status = 410
            other.status = 404
            turnOn(device, "Old phone")
            turnOn(other, "Old laptop")
            for (gone in listOf(device, other)) {
                browser.get("/settings")
                assertEquals(
                    "The service that delivers this device's Alerts says it is gone, so Ants forgot it. Turn Alerts on again.",
                    browser.postBytes("/alerts/test", gone.subscription().toByteArray()).body,
                )
                assertNotNull(gone.received.poll(10, SECONDS))
            }
            eventually { assertEquals(emptyList(), listed()) }
            assertEquals(404, browser.postBytes("/alerts/test", device.subscription().toByteArray()).status)
        }
    }

    @Test
    fun `a push service that is down, never answers or fails holds up no Run, entry or page, and keeps its device`() {
        settings(null)
        val failing = FakePushService().apply { status = 500 }
        val hanging = FakePushService().apply { hang = true }
        val down = FakePushService()
        try {
            turnOn(failing, "Failing phone")
            turnOn(hanging, "Hanging phone")
            turnOn(down, "Phone that is off")
            down.close()
            browser.import("notify.bpmn")
            browser.get("/processes/watch")
            val first = browser.post("/processes/watch/runs", mapOf("pipeline" to "api-build", NAME to "First watch")).path.removePrefix("/runs/")
            assertNotNull(hanging.received.poll(15, SECONDS), "no Alert reached the hanging push service")
            assertNotNull(failing.received.poll(10, SECONDS))

            // While the hanging push service still holds the first Alert, the next Run ends and its Notice shows.
            browser.get("/processes/watch")
            val second = browser.post("/processes/watch/runs", mapOf("pipeline" to "web-build", NAME to "Second watch")).path.removePrefix("/runs/")
            for (run in listOf(first, second)) {
                eventually { assertNotNull(row(run)) }
                eventually { assertEquals(HistoricProcessInstance.STATE_COMPLETED, history.createHistoricProcessInstanceQuery().processInstanceId(run).singleResult()?.state) }
            }
            assertNotNull(failing.received.poll(10, SECONDS), "the second Notice sent no Alert past the hanging one")
            assertEquals(listOf("Failing phone", "Hanging phone", "Phone that is off"), listed().map { it.substringBefore(" added") })
        } finally {
            listOf(failing, hanging, down).forEach { it.close() }
        }
    }
}
