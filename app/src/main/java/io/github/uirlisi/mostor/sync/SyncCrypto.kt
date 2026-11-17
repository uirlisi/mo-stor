package io.github.uirlisi.mostor.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography of sync (SPEC §9.1, §9.4; DECISIONS 2026-11-14, "Phase 5 spike: crypto"): P-256
 * ECDH for pairing, HKDF-SHA256 for derived keys, AES-256-GCM for every message. Platform APIs only.
 */
object SyncCrypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** X.509 encoding of a public key, as sent to the other phone. */
    fun encode(key: PublicKey): ByteArray = key.encoded

    fun decodePublic(bytes: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    fun agree(mine: PrivateKey, theirs: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").apply { init(mine); doPhase(theirs, true) }.generateSecret()

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }
        digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { update(it) }
        doFinal()
    }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t, info.toByteArray(Charsets.UTF_8), byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    /**
     * The 6-digit code both phones show while pairing: from the shared secret and the whole
     * exchange, so a phone in the middle would have to make two different exchanges give the same
     * code (one chance in a million, as it must commit to its key before seeing the other's).
     */
    fun pairingCode(shared: ByteArray, transcript: ByteArray): String {
        val n = ByteBuffer.wrap(hkdf(shared, transcript, "mostor pairing code", 4)).int.toLong() and 0xFFFFFFFFL
        return (n % 1_000_000).toString().padStart(6, '0')
    }

    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(plain)
        }

    /** Throws if the message was changed or isn't for this key. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(sealed)
        }

    fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()

    fun gunzip(bytes: ByteArray, limit: Int): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            require(out.size() <= limit) { "too large" }
        }
        out.toByteArray()
    }
}

/** Length-prefixed frames over a stream (a Bluetooth socket, or pipes in tests). */
class Link(input: InputStream, output: OutputStream, private val close: () -> Unit = {}) {
    private val input = DataInputStream(input)
    private val output = DataOutputStream(output)

    fun send(bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun receive(): ByteArray {
        val n = input.readInt()
        if (n < 0 || n > MAX_FRAME) throw ProtocolException("frame of $n bytes")
        return ByteArray(n).also { input.readFully(it) }
    }

    fun close() = runCatching { close.invoke() }.let { }

    /**
     * After this side's last message: waits for the other side to close (it does once it has read
     * it), at most [ms]. Closing a Bluetooth socket at once can drop data still on its way.
     */
    fun awaitPeerClose(ms: Long = 3_000) {
        val timer = java.util.Timer(true)
        timer.schedule(object : java.util.TimerTask() { override fun run() = close() }, ms)
        try {
            while (input.read() >= 0) Unit
        } catch (e: java.io.IOException) {
            // closed: the other side is done, or the wait ran out
        } finally {
            timer.cancel()
        }
    }

    companion object {
        /** A whole first sync of months of records fits easily (they are compressed). */
        const val MAX_FRAME = 16 shl 20
    }
}

class ProtocolException(message: String) : Exception(message)

/**
 * Encrypted messages over a [Link]: one AES-GCM key per direction, a counter as the nonce, so a
 * message can't be replayed, reordered, dropped unnoticed or reflected back. Each message is a type
 * byte (JSON or gzipped JSON) and its body.
 */
class SecureLink(private val link: Link, private val sendKey: ByteArray, private val receiveKey: ByteArray) {
    private var sent = 0L
    private var received = 0L

    fun send(type: Byte, body: ByteArray) {
        val nonce = nonce(sent++)
        link.send(SyncCrypto.seal(sendKey, nonce, byteArrayOf(type) + body, AAD))
    }

    fun receive(): Pair<Byte, ByteArray> {
        val nonce = nonce(received++)
        val plain = try {
            SyncCrypto.open(receiveKey, nonce, link.receive(), AAD)
        } catch (e: java.security.GeneralSecurityException) {
            throw ProtocolException("a message didn't decrypt")
        }
        if (plain.isEmpty()) throw ProtocolException("empty message")
        return plain[0] to plain.copyOfRange(1, plain.size)
    }

    private fun nonce(counter: Long) = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    companion object {
        const val JSON: Byte = 'J'.code.toByte()
        const val GZIP_JSON: Byte = 'Z'.code.toByte()
        private val AAD = "mostor-sync-v1".toByteArray()
    }
}

/** A tiny writer for the frames' JSON, so a [DataOutputStream] isn't needed in callers. */
internal fun ByteArray.utf8(): String = String(this, Charsets.UTF_8)
