package dev.flipper.internal.rpc

import com.flipperdevices.protobuf.FlipperProto
import com.flipperdevices.protobuf.storage.StorageProto
import com.flipperdevices.protobuf.system.SystemProto
import com.google.protobuf.ByteString
import dev.flipper.api.model.BleFailure
import dev.flipper.api.model.DeviceInfo
import dev.flipper.api.model.FlipperRpcException
import dev.flipper.api.model.FlipperRpcException.Reason
import dev.flipper.api.model.PowerInfo
import dev.flipper.api.model.StorageEntry
import dev.flipper.api.model.StorageInfo
import java.io.InputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** Router state is confined to the connection owner dispatcher (Main in DefaultFlipper). */
internal class RpcClient(private val idleTimeoutMillis: Long = 60_000L, private val nextCommandId: () -> Int) {
    private class Pending {
        // Bounded, lossless for accepted frames. Overflow fails the request explicitly.
        val messages = Channel<FlipperProto.Main>(128)
        val failure = CompletableDeferred<Nothing>()
        var activityNanos = System.nanoTime()
    }
    private val pending = mutableMapOf<Int, Pending>()

    private suspend fun exchange(
        timeoutMillis: Long,
        send: suspend (Int) -> Unit,
        allowIdle: Boolean = false,
        receive: suspend (FlipperProto.Main) -> Unit,
    ) {
        require(timeoutMillis >= 0) { "timeoutMillis must be positive" }
        val id = nextCommandId()
        val response = Pending()
        check(id !in pending) { "Duplicate command ID" }
        pending[id] = response
        try {
            val operation: suspend () -> Unit = {
                coroutineScope {
                    val sender = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { send(id) }
                        catch (cancel: CancellationException) { response.failure.completeExceptionally(cancel); throw cancel }
                    }
                    val watchdog = if (timeoutMillis == 0L && !allowIdle) launch {
                        while (isActive) {
                            delay(minOf(1_000L, idleTimeoutMillis))
                            if ((System.nanoTime() - response.activityNanos) / 1_000_000 >= idleTimeoutMillis) {
                                response.failure.completeExceptionally(FlipperRpcException(Reason.TIMEOUT,
                                    "RPC made no progress for $idleTimeoutMillis ms", commandId = id))
                                break
                            }
                        }
                    } else null
                    try {
                        do {
                            val message = select {
                                response.failure.onAwait { it }
                                response.messages.onReceive { it }
                            }
                            receive(message)
                        } while (message.hasNext)
                        sender.join() // Fast replies must still wait for the GATT write acknowledgement.
                    } finally { watchdog?.cancel() }
                }
            }
            if (timeoutMillis == 0L) operation()
            else if (withTimeoutOrNull(timeoutMillis) { operation(); true } == null) {
                throw FlipperRpcException(Reason.TIMEOUT, "RPC timed out after $timeoutMillis ms", commandId = id)
            }
        } finally {
            if (pending[id] === response) pending.remove(id)
            response.messages.cancel()
            response.failure.cancel()
        }
    }

    private suspend fun sendChecked(message: FlipperProto.Main, send: suspend (FlipperProto.Main) -> BleFailure?) {
        send(message)?.let { failure ->
            throw FlipperRpcException(Reason.SEND_FAILED, "RPC send failed: $failure",
                commandId = message.commandId, transportFailure = failure)
        }
        pending[message.commandId]?.activityNanos = System.nanoTime()
    }

    private fun expect(message: FlipperProto.Main, valid: Boolean) {
        if (!valid) throw FlipperRpcException(Reason.UNEXPECTED_RESPONSE,
            "Unexpected RPC response: "+message.contentCase, commandId = message.commandId)
    }

    private suspend fun fields(
        power: Boolean, timeoutMillis: Long, send: suspend (FlipperProto.Main) -> BleFailure?,
    ): Map<String, String> {
        require(timeoutMillis > 0)
        val fields = linkedMapOf<String, String>()
        exchange(timeoutMillis, { id ->
            val request = FlipperProto.Main.newBuilder().setCommandId(id)
            if (power) request.setSystemPowerInfoRequest(SystemProto.PowerInfoRequest.getDefaultInstance())
            else request.setSystemDeviceInfoRequest(SystemProto.DeviceInfoRequest.getDefaultInstance())
            sendChecked(request.build(), send)
        }) { message ->
            expect(message, if (power) message.hasSystemPowerInfoResponse() else message.hasSystemDeviceInfoResponse())
            val (key, value) = if (power) message.systemPowerInfoResponse.let { it.key to it.value }
                else message.systemDeviceInfoResponse.let { it.key to it.value }
            expect(message, key.isNotBlank())
            fields[key] = value
        }
        return fields.toMap()
    }

    suspend fun getDeviceInfo(timeoutMillis: Long, send: suspend (FlipperProto.Main) -> BleFailure?): DeviceInfo =
        DeviceInfo(fields(false, timeoutMillis, send))

    suspend fun getPowerInfo(timeoutMillis: Long, send: suspend (FlipperProto.Main) -> BleFailure?): PowerInfo =
        PowerInfo(fields(true, timeoutMillis, send))

    suspend fun getStorageInfo(path: String, timeoutMillis: Long, send: suspend (FlipperProto.Main) -> BleFailure?): StorageInfo {
        var result: StorageInfo? = null
        exchange(timeoutMillis, { id ->
            sendChecked(FlipperProto.Main.newBuilder().setCommandId(id)
                .setStorageInfoRequest(StorageProto.InfoRequest.newBuilder().setPath(path)).build(), send)
        }) { message ->
            expect(message, message.hasStorageInfoResponse() && !message.hasNext)
            val capacity = message.storageInfoResponse
            expect(message, capacity.freeSpace >= 0 && capacity.freeSpace <= capacity.totalSpace)
            result = StorageInfo(capacity.totalSpace, capacity.freeSpace)
        }
        return checkNotNull(result)
    }

    suspend fun ping(timeoutMillis: Long, send: suspend (FlipperProto.Main) -> BleFailure?) {
        require(timeoutMillis > 0)
        exchange(timeoutMillis, { id -> sendChecked(FlipperProto.Main.newBuilder().setCommandId(id)
            .setSystemPingRequest(SystemProto.PingRequest.getDefaultInstance()).build(), send)
        }) { expect(it, it.hasSystemPingResponse() && !it.hasNext) }
    }

    suspend fun listFiles(path: String, timeoutMillis: Long, send: suspend (FlipperProto.Main) -> BleFailure?): List<StorageEntry> {
        val files = mutableListOf<StorageEntry>()
        exchange(timeoutMillis, { id -> sendChecked(FlipperProto.Main.newBuilder().setCommandId(id)
            .setStorageListRequest(StorageProto.ListRequest.newBuilder().setPath(path)).build(), send)
        }) { message ->
            expect(message, message.hasStorageListResponse())
            message.storageListResponse.fileList.forEach { file ->
                expect(message, file.name.isNotBlank() && '/' !in file.name && file.name != "." && file.name != ".." &&
                    file.type != StorageProto.File.FileType.UNRECOGNIZED)
                files += StorageEntry(file.name, file.type == StorageProto.File.FileType.DIR, file.size.toLong() and 0xffffffffL)
            }
        }
        return files.sortedWith(compareByDescending<StorageEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun stat(path: String, timeoutMillis: Long, send: suspend (FlipperProto.Main) -> BleFailure?): StorageEntry {
        var result: StorageEntry? = null
        exchange(timeoutMillis, { id -> sendChecked(FlipperProto.Main.newBuilder().setCommandId(id)
            .setStorageStatRequest(StorageProto.StatRequest.newBuilder().setPath(path)).build(), send)
        }) { message ->
            expect(message, message.hasStorageStatResponse() && message.storageStatResponse.hasFile() && !message.hasNext)
            val file = message.storageStatResponse.file
            expect(message, file.type != StorageProto.File.FileType.UNRECOGNIZED)
            result = StorageEntry(file.name, file.type == StorageProto.File.FileType.DIR, file.size.toLong() and 0xffffffffL)
        }
        return checkNotNull(result)
    }

    suspend fun md5(path: String, timeoutMillis: Long, send: suspend (FlipperProto.Main) -> BleFailure?): String {
        var result = ""
        exchange(timeoutMillis, { id -> sendChecked(FlipperProto.Main.newBuilder().setCommandId(id)
            .setStorageMd5SumRequest(StorageProto.Md5sumRequest.newBuilder().setPath(path)).build(), send)
        }, allowIdle = timeoutMillis == 0L) { message ->
            expect(message, message.hasStorageMd5SumResponse() && !message.hasNext)
            result = message.storageMd5SumResponse.md5Sum.lowercase()
            expect(message, result.matches(Regex("[0-9a-f]{32}")))
        }
        return result
    }

    suspend fun readFile(path: String, timeoutMillis: Long, send: suspend (FlipperProto.Main) -> BleFailure?,
        onChunk: suspend (ByteArray) -> Unit) {
        exchange(timeoutMillis, { id -> sendChecked(FlipperProto.Main.newBuilder().setCommandId(id)
            .setStorageReadRequest(StorageProto.ReadRequest.newBuilder().setPath(path)).build(), send)
        }) { message ->
            expect(message, message.hasStorageReadResponse() && message.storageReadResponse.hasFile())
            onChunk(message.storageReadResponse.file.data.toByteArray())
        }
    }

    suspend fun writeFile(path: String, source: InputStream, timeoutMillis: Long,
        send: suspend (FlipperProto.Main) -> BleFailure?, onChunk: (ByteArray) -> Unit) {
        var finalSent = false
        exchange(timeoutMillis, { id ->
            val buffer = ByteArray(512)
            while (true) {
                coroutineContext.ensureActive()
                val count = runInterruptible(Dispatchers.IO) { source.read(buffer) }
                if (count < 0) break
                if (count == 0) continue
                val data = buffer.copyOf(count)
                sendChecked(FlipperProto.Main.newBuilder().setCommandId(id).setHasNext(true)
                    .setStorageWriteRequest(StorageProto.WriteRequest.newBuilder().setPath(path)
                        .setFile(StorageProto.File.newBuilder().setData(ByteString.copyFrom(data)))).build(), send)
                onChunk(data)
            }
            // Empty final block also creates an empty file and terminates an exact 512-byte series.
            finalSent = true
            sendChecked(FlipperProto.Main.newBuilder().setCommandId(id).setHasNext(false)
                .setStorageWriteRequest(StorageProto.WriteRequest.newBuilder().setPath(path)
                    .setFile(StorageProto.File.getDefaultInstance())).build(), send)
        }) { message -> expect(message, finalSent && message.hasEmpty() && !message.hasNext) }
    }

    suspend fun deleteFile(path: String, send: suspend (FlipperProto.Main) -> BleFailure?) {
        exchange(15_000, { id -> sendChecked(FlipperProto.Main.newBuilder().setCommandId(id)
            .setStorageDeleteRequest(StorageProto.DeleteRequest.newBuilder().setPath(path).setRecursive(false)).build(), send)
        }) { expect(it, it.hasEmpty() && !it.hasNext) }
    }

    suspend fun renameFile(from: String, to: String, send: suspend (FlipperProto.Main) -> BleFailure?) {
        exchange(0, { id -> sendChecked(FlipperProto.Main.newBuilder().setCommandId(id)
            .setStorageRenameRequest(StorageProto.RenameRequest.newBuilder().setOldPath(from).setNewPath(to)).build(), send)
        }, allowIdle = true) { expect(it, it.hasEmpty() && !it.hasNext) }
    }

    fun onMessage(message: FlipperProto.Main) {
        val response = pending[message.commandId] ?: return
        response.activityNanos = System.nanoTime()
        if (message.commandStatusValue != FlipperProto.CommandStatus.OK_VALUE) {
            response.failure.completeExceptionally(FlipperRpcException(Reason.REMOTE_ERROR,
                "RPC status: "+message.commandStatus+" ("+message.commandStatusValue+")",
                commandId = message.commandId, statusCode = message.commandStatusValue))
        } else if (!response.messages.trySend(message).isSuccess) {
            response.failure.completeExceptionally(FlipperRpcException(Reason.UNEXPECTED_RESPONSE,
                "RPC response buffer overflow; destination is too slow", commandId = message.commandId))
        }
    }

    fun onSessionClosed() {
        val requests = pending.toMap()
        pending.clear()
        requests.forEach { (id, response) -> response.failure.completeExceptionally(
            FlipperRpcException(Reason.SESSION_CLOSED, "RPC session closed", commandId = id)) }
    }
}
