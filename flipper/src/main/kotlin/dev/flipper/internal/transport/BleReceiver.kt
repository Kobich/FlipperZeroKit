package dev.flipper.internal.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow

internal class BleReceiver {
    private var channel: Channel<ByteArray>? = null
    var ignoredSetupFragments = 0
        private set

    /**
     * TX notifications before the readiness probe cannot contain its response.
     * Discard them so a fragment from the previous session cannot seed the new decoder.
     */
    fun onReceive(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val incoming = channel
        if (incoming == null) {
            ignoredSetupFragments++
            return true
        }
        return incoming.trySend(bytes.copyOf()).isSuccess
    }

    fun start(): Flow<ByteArray> {
        check(channel == null) { "RPC receiver is already running" }
        val incoming = Channel<ByteArray>(capacity = BUFFER_CAPACITY)
        channel = incoming
        return incoming.consumeAsFlow()
    }

    /**
     * Fully closes channel and removes all existing elements
     */
    fun close() {
        channel?.cancel()
        channel = null
        ignoredSetupFragments = 0
    }


    private companion object {
        const val BUFFER_CAPACITY = 64
    }

}
