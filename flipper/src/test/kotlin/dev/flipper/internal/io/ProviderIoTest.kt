package dev.flipper.internal.io

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import dev.flipper.ui.internal.pages.files.bounded
import org.junit.Assert.*
import org.junit.Test

class ProviderIoTest {
    private fun ignoreInterruptsUntil(gate: CountDownLatch) {
        while (gate.count > 0) try { gate.await() } catch (_: InterruptedException) { }
    }
    private suspend fun until(predicate: () -> Boolean) = withTimeout(3_000) { while (!predicate()) delay(5) }

    @Test fun sequentialCallsDoNotReportFalseBusy() = runBlocking<Unit> {
        val io = ProviderIo(1)
        repeat(200) { assertEquals(it, io.call("document") { it }) }
        until { !io.isBusy("document") }
    }

    @Test fun hungProviderTimesOutWithoutReleasingOwnershipOrGrowingWorkers() = runBlocking<Unit> {
        val io = ProviderIo(1)
        val gate = CountDownLatch(1)
        val discarded = CountDownLatch(1)
        try {
            try {
                io.call("document", 100, discard = { _: String -> discarded.countDown() }) {
                    ignoreInterruptsUntil(gate)
                    "late resource"
                }
                fail("Expected timeout")
            } catch (_: ProviderTimeoutException) { }
            assertTrue(io.isBusy("document"))
            try { io.call("other", 100) { "must not run" }; fail("Expected bounded worker rejection") }
            catch (_: ProviderBusyException) { }
        } finally { gate.countDown() }
        until { discarded.count == 0L && !io.isBusy("document") }
        assertEquals("recovered", io.call("document") { "recovered" })
    }

    @Test fun userCancellationReturnsBeforeUninterruptibleCallAndDisposesLateResource() = runBlocking<Unit> {
        val io = ProviderIo(1)
        val gate = CountDownLatch(1)
        val started = AtomicBoolean(false)
        val discarded = AtomicBoolean(false)
        val job = launch {
            io.call("document", discard = { _: String -> discarded.set(true) }) {
                started.set(true)
                ignoreInterruptsUntil(gate)
                "resource"
            }
        }
        try {
            until { started.get() }
            job.cancel()
            withTimeout(1_000) { job.join() }
            assertTrue(io.isBusy("document"))
        } finally { gate.countDown() }
        until { discarded.get() && !io.isBusy("document") }
    }

    @Test fun providerFailureDoesNotOccupyTheWorkerForever() = runBlocking<Unit> {
        val io = ProviderIo(1)
        try { io.call<Unit>("document") { throw IOException("provider failure") }; fail("Expected IO error") }
        catch (error: IOException) { assertEquals("provider failure", error.message) }
        assertEquals(42, io.call("document") { 42 })
    }

    @Test fun abandonedReadCannotMutateTheCallersBuffer() = runBlocking<Unit> {
        val io = ProviderIo(1)
        val gate = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val input = object : InputStream() {
            override fun read(): Int = error("bulk read required")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                ignoreInterruptsUntil(gate)
                buffer.fill(9, offset, offset + length)
                finished.set(true)
                return length
            }
        }.bounded("document", io, 100)
        val destination = ByteArray(8) { 1 }
        try {
            try { withContext(Dispatchers.IO) { input.read(destination) }; fail("Expected timeout") }
            catch (_: ProviderTimeoutException) { }
        } finally { gate.countDown() }
        until { finished.get() && !io.isBusy("document") }
        assertArrayEquals(ByteArray(8) { 1 }, destination)
    }

    @Test fun lateWriteUsesSnapshotAndStaysBusyUntilProviderFinishes() = runBlocking<Unit> {
        val io = ProviderIo(1)
        val gate = CountDownLatch(1)
        val written = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
        val raw = object : java.io.OutputStream() {
            override fun write(value: Int) = error("bulk write required")
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                ignoreInterruptsUntil(gate)
                written.set(buffer.copyOfRange(offset, offset + length))
            }
        }
        val source = ByteArray(8) { 1 }
        try {
            try { withContext(Dispatchers.IO) { raw.bounded("document", io, 100).write(source) }; fail("Expected timeout") }
            catch (_: ProviderTimeoutException) { }
            source.fill(2)
            assertTrue(io.isBusy("document"))
        } finally { gate.countDown() }
        until { written.get() != null && !io.isBusy("document") }
        assertArrayEquals(ByteArray(8) { 1 }, written.get())
    }

    @Test fun boundedStreamPreservesBytesAndClosesNormally() = runBlocking<Unit> {
        val closed = AtomicBoolean(false)
        val raw = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() { closed.set(true); super.close() }
        }
        withContext(Dispatchers.IO) {
            raw.bounded("document", ProviderIo(1)).use { assertArrayEquals(byteArrayOf(1, 2, 3), it.readBytes()) }
        }
        assertTrue(closed.get())
    }
}
