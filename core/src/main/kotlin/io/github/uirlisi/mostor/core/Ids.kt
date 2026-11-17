package io.github.uirlisi.mostor.core

import java.security.SecureRandom
import java.util.Random

/** Row and device ids: UUIDv7 strings (RFC 9562), lower-case and hyphenated. */
object Ids {
    private val secureRandom = SecureRandom()
    private val hex = "0123456789abcdef".toCharArray()

    /** A UUIDv7: a 48-bit Unix millisecond timestamp, the version and variant bits, then random bits. */
    fun uuidV7(nowMs: Long, random: Random = secureRandom): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return uuidV7(nowMs, bytes)
    }

    /** A UUIDv7 with [nowMs] as its timestamp and the rest taken from [source] (at least 16 bytes). */
    fun uuidV7(nowMs: Long, source: ByteArray): String {
        require(source.size >= 16)
        val bytes = source.copyOf(16)
        for (i in 0 until 6) bytes[i] = (nowMs ushr (8 * (5 - i))).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val out = StringBuilder(36)
        bytes.forEachIndexed { i, b ->
            if (i == 4 || i == 6 || i == 8 || i == 10) out.append('-')
            out.append(hex[(b.toInt() ushr 4) and 0xF]).append(hex[b.toInt() and 0xF])
        }
        return out.toString()
    }

    /** The millisecond timestamp stored in a UUIDv7. */
    fun timestampOf(uuidV7: String): Long = uuidV7.replace("-", "").substring(0, 12).toLong(16)
}
