package dev.flipper.internal.io

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

internal class ProviderTimeoutException : IOException("The document provider stopped responding")
internal class ProviderBusyException : IOException("The document provider is still busy")

/** Bounded daemon workers: a provider ignoring interrupts cannot retain the transfer coroutine. */
internal class ProviderIo(workerCount: Int = 2) {
    private val slots = Semaphore(workerCount)
    private val executor = Executors.newFixedThreadPool(workerCount) { runnable ->
        Thread(runnable, "Flipper-document-IO").apply { isDaemon = true }
    }
    private val active = mutableMapOf<String, Int>()
    fun isBusy(key: String): Boolean = synchronized(active) { (active[key] ?: 0) > 0 }
    private fun enter(key: String) { synchronized(active) { active[key] = (active[key] ?: 0) + 1 } }
    private fun leave(key: String) { synchronized(active) {
        val remaining = (active[key] ?: 1) - 1
        if (remaining == 0) active.remove(key) else active[key] = remaining
    } }

    suspend fun <T> call(key: String, timeoutMillis: Long = 30_000, discard: (T) -> Unit = {}, block: () -> T): T {
        try {
            return withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    if (!slots.tryAcquire()) {
                        continuation.resumeWithException(ProviderBusyException())
                    } else {
                        val caller = AtomicReference<kotlinx.coroutines.CancellableContinuation<T>?>(continuation)
                        val worker = AtomicReference<Thread?>()
                        val released = AtomicBoolean(false)
                        fun release() {
                            if (released.compareAndSet(false, true)) {
                                synchronized(worker) { worker.set(null); Thread.interrupted() }
                                leave(key); slots.release()
                            }
                        }
                        enter(key)
                        continuation.invokeOnCancellation {
                            caller.set(null) // A hung worker must not retain the cancelled UI/coroutine stack.
                            synchronized(worker) { worker.get()?.interrupt() }
                        }
                        executor.execute {
                            worker.set(Thread.currentThread())
                            try {
                                if (caller.get()?.isActive == true) {
                                    val value = block()
                                    val receiver = caller.getAndSet(null)
                                    if (receiver == null) {
                                        discard(value)
                                        return@execute
                                    }
                                    release()
                                    receiver.resume(value) { _, unused, _ ->
                                        // Never close a provider resource on the cancelling UI thread.
                                        enter(key)
                                        executor.execute { try { discard(unused) } catch (_: Exception) { } finally { leave(key) } }
                                    }
                                }
                            } catch (error: Exception) { release(); caller.getAndSet(null)?.resumeWithException(error) }
                            finally { release() }
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) { throw ProviderTimeoutException() }
    }

    companion object { val shared = ProviderIo() }
}
