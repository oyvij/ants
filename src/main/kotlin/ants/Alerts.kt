package ants

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS

/** A device with Alerts on: its browser's push subscription ([endpoint], [p256dh], [auth]), a [name] from its browser and when it was [added]. */
data class AlertDevice(val endpoint: String, val p256dh: String, val auth: String, val name: String, val added: Instant?) {
    fun toYaml() = mapOf("endpoint" to endpoint, "p256dh" to p256dh, "auth" to auth, "name" to name, "added" to added?.toString())

    companion object {
        fun of(yaml: Map<*, *>) = AlertDevice(
            "${yaml["endpoint"]}", "${yaml["p256dh"]}", "${yaml["auth"]}", "${yaml["name"] ?: ""}", (yaml["added"] as? String)?.let(Instant::parse),
        )
    }
}

/** A device as Settings lists it: its [name], when it was [added] as the user reads it, and its [endpoint] for **Remove**. */
data class ListedDevice(val endpoint: String, val name: String, val added: String)

/** What an Alert says, as the service worker (`sw.js`) shows it, in plain text, without its Markdown: an entry's [tag], [icon], [title] and [text], its [run] and [process], and the [url] a tap opens. */
data class Alert(val tag: String, val icon: String, val title: String, val text: String, val run: String?, val process: String?, val url: String) {
    companion object {
        /** The most of an entry's summary an Alert carries. */
        private const val LONGEST_TEXT = 1000

        fun of(entry: Entry) = Alert(entry.tag, entry.icon, Transcript.plain(entry.task), Transcript.plain(entry.summary).take(LONGEST_TEXT), entry.runName?.let(Transcript::plain), Transcript.plain(entry.process), "/notifications/${entry.id}")

        val TEST = Alert("", "", "Ants", "A test Alert: Alerts reach this device.", null, null, "/settings")
    }
}

/** What a push service's answer to an Alert means. */
private enum class Answer {
    TAKEN, GONE, REFUSED;

    companion object {
        fun of(status: Int) = when (status) {
            in 200..299 -> TAKEN
            404, 410 -> GONE
            else -> REFUSED
        }
    }
}

/**
 * Alerts: what Ants sends to the user's phones and browsers for each new entry on the Notification list, by [WebPush].
 * Its key pair is kept in `vapid.yaml`, and each device's subscription in `alert-devices.yaml`, both in ANTS_HOME. With
 * no event bus, a loop polls the Notification list every [every] and alerts the entries whose ids it hasn't seen; what
 * was there when Ants started alerts no one. Sending never waits on a push service: each request runs in the background
 * with a short timeout, and a failure is only logged. A push service answering 404 or 410 says the device is gone, and
 * Ants forgets it.
 */
@Controller
class Alerts(
    private val notifications: Notifications,
    private val clock: Clock,
    @Value("\${ants.home}") home: Path,
    @Value("\${ants.alerts-every:1s}") private val every: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val push = WebPush(home.resolve("vapid.yaml"), clock)
    private val devices = YamlRecords(home.resolve("alert-devices.yaml"), AlertDevice::of, AlertDevice::toYaml)
    private val http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
    private val loop = Executors.newSingleThreadScheduledExecutor { Thread(it, "alerts").apply { isDaemon = true } }
    private var seen: Set<String>? = null

    /** The VAPID public key the browser subscribes with. */
    val publicKey get() = push.publicKey

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        publicKey // makes the key pair now, not on the first Alert
        loop.scheduleWithFixedDelay({ runCatching(::poll).onFailure { log.warn("Couldn't look for new entries to alert", it) } }, 0, every.toMillis(), MILLISECONDS)
    }

    @PreDestroy
    fun stop() {
        loop.shutdownNow()
    }

    /** Alerts every entry new since the last poll. */
    private fun poll() {
        val entries = notifications.entries()
        val before = seen
        seen = entries.map { it.id }.toSet()
        if (before == null) return
        for (entry in entries.filter { it.id !in before }) devices.all().forEach { send(it, Alert.of(entry)) }
    }

    /** **Turn on Alerts** in Settings: registers this device's push subscription, as `PushSubscription.toJSON()` gives it, with a name. */
    @PostMapping("/alerts/devices")
    @ResponseBody
    fun register(@RequestBody body: String): ResponseEntity<String> {
        val subscription = subscription(body) ?: return ResponseEntity.badRequest().body("Not a push subscription.")
        devices.change { devices -> devices.filter { it.endpoint != subscription.endpoint } + subscription }
        return ResponseEntity.ok("")
    }

    /** Turning Alerts off in Settings: forgets this device's subscription. */
    @PostMapping("/alerts/devices/remove")
    @ResponseBody
    fun remove(@RequestBody body: String): ResponseEntity<String> {
        val endpoint = endpoint(body) ?: return ResponseEntity.badRequest().body("No endpoint.")
        forget(endpoint)
        return ResponseEntity.ok("")
    }

    /** **Remove** on a device in Settings' list: forgets it, as turning Alerts off on it would. */
    @PostMapping("/settings/alerts/remove")
    fun removeListed(@RequestParam endpoint: String): String {
        forget(endpoint)
        return "redirect:/settings"
    }

    /** The devices with Alerts on, as Settings lists them, in the order they were added. */
    fun listed(): List<ListedDevice> = devices.all().map { device ->
        ListedDevice(device.endpoint, device.name.ifBlank { "A browser" }, device.added?.let(clock::local).orEmpty())
    }

    /** **Send a test Alert**: sends one to this device, if it has Alerts on, and says whether the service that delivers its Alerts took it. */
    @PostMapping("/alerts/test")
    @ResponseBody
    fun test(@RequestBody body: String): ResponseEntity<String> {
        val endpoint = endpoint(body)
        val device = devices.all().firstOrNull { it.endpoint == endpoint } ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Alerts aren't on for this device.")
        val reply = runCatching { send(device, Alert.TEST).get() }.fold({ status ->
            when (Answer.of(status)) {
                Answer.TAKEN -> "Sent a test Alert."
                Answer.GONE -> "The service that delivers this device's Alerts says it is gone, so Ants forgot it. Turn Alerts on again."
                Answer.REFUSED -> "The service that delivers this device's Alerts answered $status."
            }
        }, { "The service that delivers this device's Alerts couldn't be reached." })
        return ResponseEntity.ok(reply)
    }

    private fun forget(endpoint: String) = devices.change { devices -> devices.filter { it.endpoint != endpoint } }

    private fun subscription(body: String): AlertDevice? = runCatching {
        val json = json(body) ?: return null
        val keys = json["keys"] as Map<*, *>
        val endpoint = "${json["endpoint"]}".takeIf(::pushable) ?: return null
        AlertDevice(endpoint, "${keys["p256dh"]}", "${keys["auth"]}", "${json["name"] ?: ""}".take(100), clock.instant())
            .takeIf { WebPush.fromBase64url(it.p256dh).size == 65 && WebPush.fromBase64url(it.auth).size == 16 }
    }.getOrNull()

    private fun endpoint(body: String) = json(body)?.get("endpoint") as? String

    private fun json(body: String): Map<*, *>? = runCatching { JsonMapper.shared().readValue(body, Map::class.java) }.getOrNull()

    /** Sends [alert] to [device] in the background; the push service's status, or a failure, which is logged. */
    private fun send(device: AlertDevice, alert: Alert): CompletableFuture<Int> {
        val request = runCatching { push.request(device, JsonMapper.shared().writeValueAsBytes(alert), TIMEOUT) }
            .getOrElse { return CompletableFuture.failedFuture<Int>(it).also { _ -> log.warn("Couldn't make an Alert for ${device.name}", it) } }
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenApply { response ->
            response.statusCode().also { status ->
                when (Answer.of(status)) {
                    Answer.TAKEN -> {}
                    Answer.GONE -> forget(device.endpoint).also { log.info("The push service of ${device.name} answered $status: the device is gone, so Ants forgot it") }
                    Answer.REFUSED -> log.warn("The push service of ${device.name} answered $status to an Alert")
                }
            }
        }.whenComplete { _, failure -> if (failure != null) log.warn("Couldn't send an Alert to ${device.name}: $failure") }
    }

    companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(10)

        /** Whether Ants sends to [endpoint]: https, or plain http to this machine only. */
        private fun pushable(endpoint: String): Boolean {
            val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
            val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return false
            return uri.scheme == "https" || uri.scheme == "http" && (host == "localhost" || runCatching { InetAddress.ofLiteral(host).isLoopbackAddress }.getOrDefault(false))
        }
    }
}
