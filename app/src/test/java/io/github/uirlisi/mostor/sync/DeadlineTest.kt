package io.github.uirlisi.mostor.sync

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [withDeadline]: a session's socket is closed on time-out, on silence, and when the caller is canceled. */
class DeadlineTest {
    /** A socket whose blocking read ends when it is closed. */
    private class FakeSocket : Closeable {
        val closed = CountDownLatch(1)
        override fun close() = closed.countDown()
        fun blockingRead() {
            closed.await(30, TimeUnit.SECONDS)
            throw java.io.IOException("closed")
        }
        val isClosed get() = closed.count == 0L
    }

    @Test
    fun `a canceled session closes its socket, so a blocked read ends at once`() = runBlocking {
        val socket = FakeSocket()
        val session = async(Dispatchers.IO) {
            runCatching { withDeadline(socket, 60_000, heard = { true }) { withContext(Dispatchers.IO) { socket.blockingRead() } } }
        }
        delay(100)
        val started = System.nanoTime()
        session.cancelAndJoin()
        assertTrue(socket.closed.await(2, TimeUnit.SECONDS))
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
    }

    @Test
    fun `a device that connects and says nothing is dropped after the first-message time`() = runBlocking {
        val socket = FakeSocket()
        val result = runCatching { withDeadline(socket, 60_000, heard = { false }, firstMs = 200) { withContext(Dispatchers.IO) { socket.blockingRead() } } }
        assertTrue(result.isFailure)
        assertTrue(socket.isClosed)
    }

    @Test
    fun `a session that has heard something runs to its full deadline`() = runBlocking {
        val socket = FakeSocket()
        val started = System.nanoTime()
        runCatching { withDeadline(socket, 600, heard = { true }, firstMs = 100) { withContext(Dispatchers.IO) { socket.blockingRead() } } }
        assertTrue(socket.isClosed)
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(550))
    }

    @Test
    fun `a session that finishes leaves the socket to its owner`() = runBlocking {
        val socket = FakeSocket()
        assertEquals(42, withDeadline(socket, 60_000, heard = { false }, firstMs = 10_000) { 42 })
        delay(50)
        assertFalse(socket.isClosed)
    }

    @Test
    fun `heard input notices the first byte`() {
        val input = HeardInput("x".byteInputStream())
        assertFalse(input.heard)
        input.read()
        assertTrue(input.heard)
    }
}
