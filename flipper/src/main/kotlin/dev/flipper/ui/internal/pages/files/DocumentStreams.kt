package dev.flipper.ui.internal.pages.files

import dev.flipper.internal.io.ProviderIo
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking

/** Core RPC owns its coroutine; these adapters bound each external-provider call. */
internal fun InputStream.bounded(key: String, io: ProviderIo = ProviderIo.shared, timeoutMillis: Long = 30_000): InputStream = object : InputStream() {
    override fun read(): Int = runBlocking { io.call(key, timeoutMillis) { this@bounded.read() } }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // The abandoned worker must never mutate the next transfer's caller buffer.
        val bytes = ByteArray(length)
        val count = runBlocking { io.call(key, timeoutMillis) { this@bounded.read(bytes, 0, length) } }
        if (count > 0) bytes.copyInto(buffer, offset, 0, count)
        return count
    }
    override fun close() = runBlocking { io.call(key, 5_000) { this@bounded.close() } }
}

internal fun OutputStream.bounded(key: String, io: ProviderIo = ProviderIo.shared, timeoutMillis: Long = 30_000): OutputStream = object : OutputStream() {
    override fun write(value: Int) = runBlocking { io.call(key, timeoutMillis) { this@bounded.write(value) } }
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val bytes = buffer.copyOfRange(offset, offset + length)
        runBlocking { io.call(key, timeoutMillis) { this@bounded.write(bytes) } }
    }
    override fun flush() = runBlocking { io.call(key, timeoutMillis) { this@bounded.flush() } }
    override fun close() = runBlocking { io.call(key, 5_000) { this@bounded.close() } }
}
