package io.github.uirlisi.mostor.sync

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs [block], closing [socket] (so a blocked read fails) when it takes longer than [ms], when
 * nothing has arrived after [firstMs] ([heard]), or when the caller is canceled: a canceled sync
 * mustn't leave a read blocked, holding the session lock until the other phone gives up. A device that
 * connects and stays silent is dropped after [firstMs].
 */
internal suspend fun <T> withDeadline(
    socket: Closeable,
    ms: Long,
    heard: (() -> Boolean)? = null,
    firstMs: Long = FIRST_MESSAGE_MS,
    block: suspend () -> T,
): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val watchdog = launch(Dispatchers.Default) {
        try {
            if (heard != null && firstMs < ms) {
                delay(firstMs)
                if (!heard()) return@launch
                delay(ms - firstMs)
            } else {
                delay(ms)
            }
        } finally {
            // Timed out, silent, or canceled: unless the block finished, the socket goes.
            if (!finished.get()) runCatching { socket.close() }
        }
    }
    try {
        block().also { finished.set(true) }
    } finally {
        watchdog.cancel()
    }
}

/** How long a phone that connected has to send its first message. */
internal const val FIRST_MESSAGE_MS = 5_000L

/** An input that remembers whether anything arrived ([withDeadline]'s first-message check). */
internal class HeardInput(input: java.io.InputStream) : java.io.FilterInputStream(input) {
    @Volatile var heard = false
        private set

    override fun read(): Int = super.read().also { if (it >= 0) heard = true }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) heard = true }
}
