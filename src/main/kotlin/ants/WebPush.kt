package ants

import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpRequest
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
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Web Push with VAPID (RFC 8030, 8291, 8292): a request to a device's push service carries a payload encrypted for that
 * device alone and is signed with Ants' key pair, made once and kept in [keyFile].
 */
class WebPush(private val keyFile: Path, private val clock: Clock) {
    /** The VAPID key pair, made the first time it is asked for. */
    private val keys: KeyPair by lazy { synchronized(this) { readKeys() ?: newKeys() } }

    /** The VAPID public key, as the browser's `pushManager.subscribe` takes it: base64url of the raw P-256 point. */
    val publicKey get() = base64url(raw(keys.public as ECPublicKey))

    /** The request that delivers [payload] to [device] through its push service, within [timeout]. */
    fun request(device: AlertDevice, payload: ByteArray, timeout: Duration): HttpRequest {
        val endpoint = URI(device.endpoint)
        return HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Content-Encoding", "aes128gcm")
            .header("Content-Type", "application/octet-stream")
            .header("TTL", "${Duration.ofDays(1).toSeconds()}")
            .header("Urgency", "high")
            .header("Authorization", "vapid t=${jwt("${endpoint.scheme}://${endpoint.authority}")}, k=$publicKey")
            .POST(HttpRequest.BodyPublishers.ofByteArray(encrypt(payload, device)))
            .build()
    }

    /** The VAPID JWT for the push service at [audience], signed with ES256, good for 12 hours (RFC 8292). */
    private fun jwt(audience: String): String {
        val header = base64url("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val claims = base64url(JsonMapper.shared().writeValueAsBytes(mapOf("aud" to audience, "exp" to clock.instant().plus(Duration.ofHours(12)).epochSecond, "sub" to SUBJECT)))
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(keys.private)
            update("$header.$claims".toByteArray())
            sign()
        }
        return "$header.$claims.${base64url(signature)}"
    }

    private fun readKeys(): KeyPair? {
        if (!keyFile.exists()) return null
        val saved = yaml.load<Map<*, *>>(keyFile.readText())
        val private = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode("${saved["private"]}")))
        return KeyPair(publicKey(fromBase64url("${saved["public"]}")), private)
    }

    private fun newKeys(): KeyPair = p256().also {
        keyFile.writeAtomically(yaml.dump(mapOf("public" to base64url(raw(it.public as ECPublicKey)), "private" to Base64.getEncoder().encodeToString(it.private.encoded))))
    }

    companion object {
        private const val SUBJECT = "https://github.com/oyvij/ants"

        /** The size of the one record a payload is encrypted into: larger than any Alert. */
        private const val RECORD_SIZE = 4096

        private val P256: ECParameterSpec = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)

        fun base64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun fromBase64url(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trimEnd('='))

        private fun p256(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        /** The uncompressed point: 4, then x and y, 32 bytes each. */
        private fun raw(key: ECPublicKey) = byteArrayOf(4) + unsigned(key.w.affineX) + unsigned(key.w.affineY)

        private fun unsigned(n: BigInteger) = n.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }

        private fun publicKey(raw: ByteArray): ECPublicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
            ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65))), P256,
        )) as ECPublicKey

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

        /** [payload] encrypted for [device] as one `aes128gcm` record (RFC 8291 and 8188), with a fresh key pair and salt. */
        private fun encrypt(payload: ByteArray, device: AlertDevice): ByteArray {
            val deviceKey = fromBase64url(device.p256dh)
            val sender = p256()
            val senderKey = raw(sender.public as ECPublicKey)
            val secret = KeyAgreement.getInstance("ECDH").run { init(sender.private); doPhase(publicKey(deviceKey), true); generateSecret() }
            val ikm = hmac(hmac(fromBase64url(device.auth), secret), "WebPush: info".toByteArray() + 0 + deviceKey + senderKey + 1)
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            val prk = hmac(salt, ikm)
            val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
            val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)
            val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
                doFinal(payload + 2) // the last record's delimiter
            }
            return ByteBuffer.allocate(16 + 4 + 1 + senderKey.size + ciphertext.size)
                .put(salt).putInt(RECORD_SIZE).put(senderKey.size.toByte()).put(senderKey).put(ciphertext).array()
        }

        private operator fun ByteArray.plus(byte: Int) = this + byte.toByte()
    }
}
