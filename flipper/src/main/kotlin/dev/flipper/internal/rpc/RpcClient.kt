package dev.flipper.internal.rpc

import com.flipperdevices.protobuf.FlipperProto
import com.flipperdevices.protobuf.system.SystemProto
import dev.flipper.api.model.BleFailure
import dev.flipper.api.model.DeviceInfo
import dev.flipper.api.model.FlipperRpcException
import dev.flipper.api.model.FlipperRpcException.Reason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** All methods run on the connection owner dispatcher (Main in DefaultFlipper). */
internal class RpcClient(private val nextCommandId: () -> Int) {
    private class PendingInfo {
        val fields = linkedMapOf<String, String>()
        val result = CompletableDeferred<DeviceInfo>()
    }

    private val pending = mutableMapOf<Int, PendingInfo>()

    suspend fun getDeviceInfo(
        timeoutMillis: Long,
        send: suspend (FlipperProto.Main) -> BleFailure?,
    ): DeviceInfo {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val commandId = nextCommandId()
        val request = FlipperProto.Main.newBuilder()
            .setCommandId(commandId)
            .setSystemDeviceInfoRequest(SystemProto.DeviceInfoRequest.getDefaultInstance())
            .build()
        val response = PendingInfo()
        check(commandId !in pending) { "Duplicate command ID" }
        // Register synchronously before the sender can receive a fast reply.
        pending[commandId] = response
        try {
            return withTimeoutOrNull(timeoutMillis) {
                coroutineScope {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        val failure = send(request)
                        if (failure != null) {
                            response.result.completeExceptionally(FlipperRpcException(
                                Reason.SEND_FAILED, "DeviceInfo send failed: " + failure,
                                commandId = commandId, transportFailure = failure,
                            ))
                        }
                    }
                    // Keep waiting for the GATT write callback if the RPC reply arrived first.
                    // coroutineScope cancels the sender on errors, timeout or caller cancellation.
                    response.result.await()
                }
            } ?: throw FlipperRpcException(
                Reason.TIMEOUT, "DeviceInfo timed out after " + timeoutMillis + " ms",
                commandId = commandId,
            )
        } finally {
            if (pending[commandId] === response) pending.remove(commandId)
            response.result.cancel()
        }
    }

    fun onMessage(message: FlipperProto.Main) {
        val response = pending[message.commandId] ?: return
        when {
            message.commandStatusValue != FlipperProto.CommandStatus.OK_VALUE -> {
                pending.remove(message.commandId)
                response.result.completeExceptionally(FlipperRpcException(
                    Reason.REMOTE_ERROR, "DeviceInfo RPC status: " + message.commandStatusValue,
                    commandId = message.commandId, statusCode = message.commandStatusValue,
                ))
            }
            !message.hasSystemDeviceInfoResponse() || message.systemDeviceInfoResponse.key.isBlank() -> {
                pending.remove(message.commandId)
                response.result.completeExceptionally(FlipperRpcException(
                    Reason.UNEXPECTED_RESPONSE, "Unexpected DeviceInfo response: " + message.contentCase,
                    commandId = message.commandId,
                ))
            }
            else -> {
                val field = message.systemDeviceInfoResponse
                response.fields[field.key] = field.value
                if (!message.hasNext) {
                    pending.remove(message.commandId)
                    response.result.complete(DeviceInfo(response.fields.toMap()))
                }
            }
        }
    }

    fun onSessionClosed() {
        val requests = pending.toMap()
        pending.clear()
        requests.forEach { (commandId, response) ->
            response.result.completeExceptionally(FlipperRpcException(
                Reason.SESSION_CLOSED, "RPC session closed while waiting for DeviceInfo",
                commandId = commandId,
            ))
        }
    }
}
