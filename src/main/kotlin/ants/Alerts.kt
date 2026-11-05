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
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.exists
import kotlin.io.path.readText

/** A device with Alerts on: its browser's push subscription ([endpoint], [p256dh], [auth]), a [name] from its browser and when it was [added]. */
data class AlertDevice(val endpoint: String, val p256dh: String, val auth: String, val name: String, val added: String) {
    fun toYaml() = mapOf("endpoint" to endpoint, "p256dh" to p256dh, "auth" to auth, "name" to name, "added" to added)

    companion object {
        fun of(yaml: Map<*, *>) = AlertDevice("${yaml["endpoint"]}", "${yaml["p256dh"]}", "${yaml["auth"]}", "${yaml["name"] ?: ""}", "${yaml["added"] ?: ""}")
    }
}

/** A device as Settings lists it: its [name], when it was [added] as the user reads it, and its [endpoint] for **Remove**. */
data class ListedDevice(val endpoint: String, val name: String, val added: String)

/**
 * Alerts: what Ants sends to the user's phones and browsers for each new entry on the Notification list, by Web Push
 * with VAPID (RFC 8030, 8291, 8292). Its key pair is made once and kept in `vapid.yaml`, and each device's subscription
 * in `alert-devices.yaml`, both in ANTS_HOME. With no event bus, a loop polls the Notification list every [every] and
 * alerts the entries whose ids it hasn't seen; what was there when Ants started alerts no one. Sending never waits on
 * a push service: each request runs in the background with a short timeout, and a failure is only logged. A push
 * service answering 404 or 410 says the device is gone, and Ants forgets it.
 */
@Controller
class Alerts(
    private val notifications: Notifications,
    private val clock: Clock,
    @Value("\${ants.home}") home: Path,
    @Value("\${ants.alerts-every:1s}") private val every: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val keyFile = home.resolve("vapid.yaml")
    private val devicesFile = home.resolve("alert-devices.yaml")
    private val http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
    private val loop = Executors.newSingleThreadScheduledExecutor { Thread(it, "alerts").apply { isDaemon = true } }
    private var seen: Set<String>? = null

    /** The VAPID key pair, made the first time it is asked for. */
    private val keys: KeyPair by lazy { synchronized(this) { readKeys() ?: newKeys() } }

    /** The VAPID public key, as the browser's `pushManager.subscribe` takes it: base64url of the raw P-256 point. */
    val publicKey get() = b64(raw(keys.public as ECPublicKey))

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        keys
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
        for (entry in entries.filter { it.id !in before }) {
            val alert = mapOf("tag" to entry.tag, "icon" to entry.icon, "title" to entry.task, "text" to entry.summary.take(TEXT), "run" to entry.runName, "process" to entry.process, "url" to "/notifications/${entry.id}")
            devices().forEach { send(it, alert) }
        }
    }

    /** **Turn on Alerts** in Settings: registers this device's push subscription, as `PushSubscription.toJSON()` gives it, with a name. */
    @PostMapping("/alerts/devices")
    @ResponseBody
    fun register(@RequestBody body: String): ResponseEntity<String> {
        val subscription = subscription(body) ?: return ResponseEntity.badRequest().body("Not a push subscription.")
        update { devices -> devices.filter { it.endpoint != subscription.endpoint } + subscription }
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
    fun listed(): List<ListedDevice> = devices().map { device ->
        ListedDevice(device.endpoint, device.name.ifBlank { "A browser" }, runCatching { clock.local(Instant.parse(device.added)) }.getOrDefault(""))
    }

    /** **Send a test Alert**: sends one to this device, if it has Alerts on, and says whether its push service took it. */
    @PostMapping("/alerts/test")
    @ResponseBody
    fun test(@RequestBody body: String): ResponseEntity<String> {
        val endpoint = endpoint(body)
        val device = devices().firstOrNull { it.endpoint == endpoint } ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Alerts aren't on for this device.")
        val status = runCatching { send(device, mapOf("tag" to "", "icon" to "", "title" to "Ants", "text" to "A test Alert: Alerts reach this device.", "run" to null, "url" to "/settings")).get() }
        val said = status.fold({
            when (it) {
                in 200..299 -> "Sent a test Alert."
                404, 410 -> "The push service says this device is gone, so Ants forgot it. Turn Alerts on again."
                else -> "The push service answered $it."
            }
        }, { "The push service couldn't be reached." })
        return ResponseEntity.ok(said)
    }

    /** The devices with Alerts on. */
    fun devices(): List<AlertDevice> = synchronized(this) {
        if (!devicesFile.exists()) emptyList() else yaml.load<List<Map<*, *>>?>(devicesFile.readText()).orEmpty().map(AlertDevice::of)
    }

    private fun forget(endpoint: String) = update { devices -> devices.filter { it.endpoint != endpoint } }

    private fun update(change: (List<AlertDevice>) -> List<AlertDevice>) = synchronized(this) {
        devicesFile.writeAtomically(yaml.dump(change(devices()).map { it.toYaml() }))
    }

    private fun subscription(body: String): AlertDevice? = runCatching {
        val json = JsonMapper.shared().readValue(body, Map::class.java)
        val keys = json["keys"] as Map<*, *>
        val endpoint = "${json["endpoint"]}".takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        AlertDevice(endpoint, "${keys["p256dh"]}", "${keys["auth"]}", "${json["name"] ?: ""}".take(100), "${clock.instant()}")
            .takeIf { b64(it.p256dh).size == 65 && b64(it.auth).size == 16 }
    }.getOrNull()

    private fun endpoint(body: String) = runCatching { JsonMapper.shared().readValue(body, Map::class.java)["endpoint"] as? String }.getOrNull()

    /** Sends [alert] to [device] in the background; the push service's status, or a failure, which is logged. */
    private fun send(device: AlertDevice, alert: Map<String, Any?>): CompletableFuture<Int> {
        val request = runCatching {
            val endpoint = URI(device.endpoint)
            HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", "${Duration.ofDays(1).toSeconds()}")
                .header("Urgency", "high")
                .header("Authorization", "vapid t=${jwt("${endpoint.scheme}://${endpoint.authority}")}, k=$publicKey")
                .POST(HttpRequest.BodyPublishers.ofByteArray(encrypt(JsonMapper.shared().writeValueAsBytes(alert), device)))
                .build()
        }.getOrElse { return CompletableFuture.failedFuture<Int>(it).also { _ -> log.warn("Couldn't make an Alert for ${device.name}", it) } }
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenApply { response ->
            response.statusCode().also {
                when (it) {
                    in 200..299 -> {}
                    404, 410 -> forget(device.endpoint).also { _ -> log.info("The push service of ${device.name} answered $it: the device is gone, so Ants forgot it") }
                    else -> log.warn("The push service of ${device.name} answered $it to an Alert")
                }
            }
        }.whenComplete { _, failure -> if (failure != null) log.warn("Couldn't send an Alert to ${device.name}: $failure") }
    }

    /** The VAPID JWT for the push service at [audience], signed with ES256, good for 12 hours (RFC 8292). */
    private fun jwt(audience: String): String {
        val header = b64("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val claims = b64(JsonMapper.shared().writeValueAsBytes(mapOf("aud" to audience, "exp" to clock.instant().plus(Duration.ofHours(12)).epochSecond, "sub" to SUBJECT)))
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(keys.private)
            update("$header.$claims".toByteArray())
            sign()
        }
        return "$header.$claims.${b64(signature)}"
    }

    private fun readKeys(): KeyPair? {
        if (!keyFile.exists()) return null
        val saved = yaml.load<Map<*, *>>(keyFile.readText())
        val private = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode("${saved["private"]}")))
        return KeyPair(publicKey(b64("${saved["public"]}")), private)
    }

    private fun newKeys(): KeyPair = p256().also {
        keyFile.writeAtomically(yaml.dump(mapOf("public" to b64(raw(it.public as ECPublicKey)), "private" to Base64.getEncoder().encodeToString(it.private.encoded))))
    }

    companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val TEXT = 1000
        private const val SUBJECT = "https://github.com/oyvij/ants"
        private const val RECORD = 4096

        private val P256: ECParameterSpec = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)

        private fun p256(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun b64(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trimEnd('='))

        /** The uncompressed point: 4, then x and y, 32 bytes each. */
        private fun raw(key: ECPublicKey) = byteArrayOf(4) + unsigned(key.w.affineX) + unsigned(key.w.affineY)

        private fun unsigned(n: BigInteger) = n.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }

        private fun publicKey(raw: ByteArray): ECPublicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
            ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65))), P256,
        )) as ECPublicKey

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

        /** [payload] encrypted for [device] as one `aes128gcm` record (RFC 8291 and 8188), with a fresh key pair and salt. */
        private fun encrypt(payload: ByteArray, device: AlertDevice): ByteArray {
            val ua = b64(device.p256dh)
            val sender = p256()
            val senderPublic = raw(sender.public as ECPublicKey)
            val secret = KeyAgreement.getInstance("ECDH").run { init(sender.private); doPhase(publicKey(ua), true); generateSecret() }
            val ikm = hmac(hmac(b64(device.auth), secret), "WebPush: info".toByteArray() + 0 + ua + senderPublic + 1)
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            val prk = hmac(salt, ikm)
            val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
            val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)
            val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
                doFinal(payload + 2) // the last record's delimiter
            }
            return ByteBuffer.allocate(16 + 4 + 1 + senderPublic.size + ciphertext.size)
                .put(salt).putInt(RECORD).put(senderPublic.size.toByte()).put(senderPublic).put(ciphertext).array()
        }

        private operator fun ByteArray.plus(byte: Int) = this + byte.toByte()
    }
}
