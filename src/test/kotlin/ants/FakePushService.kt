package ants

import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A push service inside the test, standing in for a browser's (RFC 8030): an HTTP server whose one endpoint takes the
 * Alerts for one device, which holds its own key pair and auth secret (RFC 8291), as the browser would.
 */
class FakePushService : AutoCloseable {
    /** One request the push service received: its headers, lower-cased, and its body. */
    class Received(val headers: Map<String, String>, val body: ByteArray, private val device: FakePushService) {
        /** The VAPID header's JWT claims, once its signature is checked against its key `k` (RFC 8292). */
        fun vapid(): Map<*, *> {
            val (scheme, params) = headers["authorization"]!!.split(" ", limit = 2)
            check(scheme == "vapid") { "not a vapid Authorization: ${headers["authorization"]}" }
            val fields = params.split(",").associate { it.trim().substringBefore("=") to it.trim().substringAfter("=") }
            val (header, claims, signature) = fields["t"]!!.split(".")
            val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
            verifier.initVerify(publicKey(fromBase64url(fields["k"]!!)))
            verifier.update("$header.$claims".toByteArray())
            check(verifier.verify(fromBase64url(signature))) { "the VAPID signature doesn't match its key" }
            check(JsonMapper.shared().readValue(fromBase64url(header), Map::class.java)["alg"] == "ES256")
            return JsonMapper.shared().readValue(fromBase64url(claims), Map::class.java)
        }

        /** The VAPID header's key `k`. */
        val key get() = headers["authorization"]!!.substringAfter("k=").substringBefore(",").trim()

        /** The payload, decrypted with the device's keys. */
        fun payload(): Map<*, *> = JsonMapper.shared().readValue(decrypt(body, device.keys, device.auth), Map::class.java)
    }

    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val auth = ByteArray(16).also(SecureRandom()::nextBytes)

    /** Every request received, in order. */
    val received = LinkedBlockingQueue<Received>()

    /** The status the push service answers with. */
    @Volatile
    var status = 201

    /** Whether the push service takes requests and never answers them, until it is closed. */
    @Volatile
    var hang = false
    private val closed = CountDownLatch(1)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/push/device") { exchange ->
            val headers = exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.joinToString(",") }
            received += Received(headers, exchange.requestBody.readAllBytes(), this@FakePushService)
            if (hang) closed.await(60, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        start()
    }

    val origin = "http://127.0.0.1:${server.address.port}"

    /** The subscription a browser's `PushSubscription.toJSON()` gives, as the Settings page posts it. */
    fun subscription(name: String = "Test browser") = JsonMapper.shared().writeValueAsString(mapOf(
        "endpoint" to "$origin/push/device",
        "keys" to mapOf("p256dh" to base64url(raw(keys.public as ECPublicKey)), "auth" to base64url(auth)),
        "name" to name,
    ))

    override fun close() {
        closed.countDown()
        server.stop(0)
    }

    companion object {
        private val P256: ECParameterSpec = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)

        fun fromBase64url(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

        fun base64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun raw(key: ECPublicKey) = byteArrayOf(4) + unsigned(key.w.affineX) + unsigned(key.w.affineY)

        private fun unsigned(n: BigInteger) = n.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }

        fun publicKey(raw: ByteArray): ECPublicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
            ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65))), P256,
        )) as ECPublicKey

        fun keyPair(private: ByteArray, public: ByteArray) = KeyPair(
            publicKey(public), KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, private), P256)) as ECPrivateKey,
        )

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

        /** Decrypts an `aes128gcm` Web Push message ([body]) as the user agent holding [keys] and [auth] does (RFC 8291 and 8188). */
        fun decrypt(body: ByteArray, keys: KeyPair, auth: ByteArray): String {
            val buffer = ByteBuffer.wrap(body)
            val salt = ByteArray(16).also(buffer::get)
            val recordSize = buffer.int
            val sender = ByteArray(buffer.get().toInt()).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            check(ciphertext.size <= recordSize) { "more than one record" }
            val secret = KeyAgreement.getInstance("ECDH").run { init(keys.private); doPhase(publicKey(sender), true); generateSecret() }
            val ikm = hmac(hmac(auth, secret), "WebPush: info".toByteArray() + 0 + raw(keys.public as ECPublicKey) + sender + 1)
            val prk = hmac(salt, ikm)
            val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
            val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)
            val padded = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)); doFinal(ciphertext) }
            val end = padded.indexOfLast { it != 0.toByte() }
            check(padded[end] == 2.toByte()) { "not the last record" }
            return padded.copyOf(end).decodeToString()
        }

        private operator fun ByteArray.plus(byte: Int) = this + byte.toByte()

        fun now(): Long = Instant.now().epochSecond
    }
}
