package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Alerts: every new entry on the Notification list reaches each device with Alerts on, through its push service. */
class AlertsTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()
    private val device = FakePushService()

    @AfterEach
    fun closeDevice() = device.close()

    private fun result(result: String) = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":$result}"""

    /** Turns Alerts on for [device], as the Settings page does with the browser's subscription. */
    private fun turnOn(device: FakePushService = this.device) {
        browser.get("/settings")
        assertEquals(200, browser.postBytes("/alerts/devices", device.subscription().toByteArray()).status)
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
            assertEquals(200, browser.postBytes("/alerts/test", other.subscription().toByteArray()).status)
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
}
