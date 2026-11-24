package dev.flipper.internal.rpc

import com.flipperdevices.protobuf.FlipperProto
import com.flipperdevices.protobuf.system.SystemProto
import dev.flipper.api.model.BleFailure
import dev.flipper.api.model.FlipperRpcException
import dev.flipper.api.model.FlipperRpcException.Reason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RpcClientTest {
    private var id = 0
    private val client = RpcClient { ++id }

    private fun reply(commandId: Int, key: String, value: String, more: Boolean = false): FlipperProto.Main =
        FlipperProto.Main.newBuilder().setCommandId(commandId).setHasNext(more)
            .setSystemDeviceInfoResponse(SystemProto.DeviceInfoResponse.newBuilder().setKey(key).setValue(value)).build()

    private suspend fun failure(reason: Reason, block: suspend () -> Any?): FlipperRpcException {
        try {
            block()
            throw AssertionError("Expected RPC failure: " + reason)
        } catch (error: FlipperRpcException) {
            assertEquals(reason, error.reason)
            return error
        }
    }

    @Test
    fun registersBeforeSendingAndCollectsTheWholeSeries() = runBlocking<Unit> {
        var sends = 0
        val info = client.getDeviceInfo(1_000) { request ->
            sends++
            assertTrue(request.hasSystemDeviceInfoRequest())
            client.onMessage(reply(request.commandId, "firmware_version", "1.0", more = true))
            client.onMessage(reply(request.commandId, "hardware_uid", "UID"))
            null
        }
        assertEquals(1, sends)
        assertEquals("1.0", info.firmwareVersion)
        assertEquals("UID", info.hardwareUid)
        assertEquals(2, info.fields.size)
    }

    @Test
    fun waitsForLastMessageAndIgnoresOtherCommands() = runBlocking<Unit> {
        val command = CompletableDeferred<Int>()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            client.getDeviceInfo(1_000) { command.complete(it.commandId); null }
        }
        val commandId = command.await()
        client.onMessage(reply(commandId + 100, "unrelated", "ignored"))
        client.onMessage(reply(commandId, "custom_field", "kept", more = true))
        assertFalse(result.isCompleted)
        client.onMessage(reply(commandId, "hardware_ver", "12"))
        val info = result.await()
        assertEquals("kept", info.fields["custom_field"])
        assertEquals("12", info.hardwareVersion)
        assertFalse(info.fields.containsKey("unrelated"))
    }

    @Test
    fun routesConcurrentRequestsByCommandId() = runBlocking<Unit> {
        val firstId = CompletableDeferred<Int>()
        val secondId = CompletableDeferred<Int>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            client.getDeviceInfo(1_000) { firstId.complete(it.commandId); null }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            client.getDeviceInfo(1_000) { secondId.complete(it.commandId); null }
        }
        client.onMessage(reply(secondId.await(), "hardware_name", "Second"))
        client.onMessage(reply(firstId.await(), "hardware_name", "First"))
        assertEquals("First", first.await().name)
        assertEquals("Second", second.await().name)
    }

    @Test
    fun reportsRemoteStatusBeforeCheckingContent() = runBlocking<Unit> {
        val error = failure(Reason.REMOTE_ERROR) {
            client.getDeviceInfo(1_000) { request ->
                client.onMessage(FlipperProto.Main.newBuilder().setCommandId(request.commandId)
                    .setCommandStatus(FlipperProto.CommandStatus.ERROR_BUSY).build())
                null
            }
        }
        assertEquals(FlipperProto.CommandStatus.ERROR_BUSY_VALUE, error.statusCode)
    }

    @Test
    fun rejectsUnexpectedContentAndEmptyKeys() = runBlocking<Unit> {
        failure(Reason.UNEXPECTED_RESPONSE) {
            client.getDeviceInfo(1_000) { request ->
                client.onMessage(FlipperProto.Main.newBuilder().setCommandId(request.commandId)
                    .setSystemPingResponse(SystemProto.PingResponse.getDefaultInstance()).build())
                null
            }
        }
        failure(Reason.UNEXPECTED_RESPONSE) {
            client.getDeviceInfo(1_000) { request ->
                client.onMessage(reply(request.commandId, "", "invalid")); null
            }
        }
    }

    @Test
    fun reportsTransportFailure() = runBlocking<Unit> {
        val error = failure(Reason.SEND_FAILED) {
            client.getDeviceInfo(1_000) { BleFailure.RPCError }
        }
        assertEquals(BleFailure.RPCError, error.transportFailure)
    }

    @Test
    fun timesOutWithoutReturningPartialDataAndCanRetry() = runBlocking<Unit> {
        var timedOutId = 0
        failure(Reason.TIMEOUT) {
            client.getDeviceInfo(30) { request ->
                timedOutId = request.commandId
                client.onMessage(reply(request.commandId, "partial", "discard", more = true))
                null
            }
        }
        val retry = client.getDeviceInfo(1_000) { request ->
            client.onMessage(reply(timedOutId, "stale", "ignored"))
            client.onMessage(reply(request.commandId, "new", "accepted"))
            null
        }
        assertEquals(mapOf("new" to "accepted"), retry.fields)
    }

    @Test
    fun sessionCloseFailsWaitingRequestAndCancelsItsSender() = runBlocking<Unit> {
        val sent = CompletableDeferred<Int>()
        var cancelled = false
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            failure(Reason.SESSION_CLOSED) {
                client.getDeviceInfo(1_000) { request ->
                    sent.complete(request.commandId)
                    try { awaitCancellation() } finally { cancelled = true }
                }
            }
        }
        val oldId = sent.await()
        client.onSessionClosed()
        waiting.await()
        assertTrue(cancelled)
        val nextSession = client.getDeviceInfo(1_000) { request ->
            client.onMessage(reply(oldId, "old", "ignored"))
            client.onMessage(reply(request.commandId, "session", "new"))
            null
        }
        assertEquals(mapOf("session" to "new"), nextSession.fields)
    }

    @Test
    fun timeoutAlsoCoversABlockedSend() = runBlocking<Unit> {
        var cancelled = false
        failure(Reason.TIMEOUT) {
            client.getDeviceInfo(30) {
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        assertTrue(cancelled)
    }

    @Test
    fun callerCancellationStopsSendingAndAllowsAnotherRequest() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            client.getDeviceInfo(1_000) {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        started.await()
        caller.cancelAndJoin()
        assertTrue(cancelled)
        val retry = client.getDeviceInfo(1_000) { request ->
            client.onMessage(reply(request.commandId, "retry", "ok")); null
        }
        assertEquals("ok", retry.fields["retry"])
    }

    @Test
    fun fastReplyDoesNotCancelThePendingGattWriteCallback() = runBlocking<Unit> {
        var writeAcknowledged = false
        val info = client.getDeviceInfo(1_000) { request ->
            client.onMessage(reply(request.commandId, "hardware_name", "Fast"))
            delay(20)
            writeAcknowledged = true
            null
        }
        assertTrue(writeAcknowledged)
        assertEquals("Fast", info.name)
    }

    @Test
    fun rejectsNonPositiveTimeouts() = runBlocking<Unit> {
        try {
            client.getDeviceInfo(0) { null }
            fail("Expected invalid timeout")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, id)
        }
    }
}
