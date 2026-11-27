package dev.flipper.internal.rpc

import com.flipperdevices.protobuf.FlipperProto
import com.flipperdevices.protobuf.storage.StorageProto
import com.flipperdevices.protobuf.system.SystemProto
import com.google.protobuf.ByteString
import dev.flipper.api.model.BleFailure
import dev.flipper.api.model.FlipperRpcException
import dev.flipper.api.model.PowerInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class StorageRpcTest {
    private var id = 0
    private val client = RpcClient { ++id }
    private fun reply(command: Int) = FlipperProto.Main.newBuilder().setCommandId(command)
    private suspend fun fails(reason: FlipperRpcException.Reason, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (error: FlipperRpcException) { assertEquals(reason, error.reason) }
    }

    @Test fun powerUsesOwnContentAndPreservesUnknownKeys() = runBlocking<Unit> {
        val result = client.getPowerInfo(1_000) { request ->
            assertTrue(request.hasSystemPowerInfoRequest())
            client.onMessage(reply(request.commandId).setHasNext(true).setSystemPowerInfoResponse(
                SystemProto.PowerInfoResponse.newBuilder().setKey("charge_level").setValue("73")).build())
            client.onMessage(reply(request.commandId).setSystemPowerInfoResponse(
                SystemProto.PowerInfoResponse.newBuilder().setKey("future_key").setValue("kept")).build())
            null
        }
        assertEquals(73, result.chargePercent)
        assertEquals("kept", result.fields["future_key"])
        assertNull(PowerInfo(emptyMap()).chargePercent)
        assertNull(PowerInfo(mapOf("charge_level" to "101")).chargePercent)
        assertNull(PowerInfo(mapOf("charge_level" to "unknown")).chargePercent)
    }

    @Test fun listCollectsBatchesAndConvertsUnsignedSize() = runBlocking<Unit> {
        val result = client.listFiles("/ext", 1_000) { request ->
            assertEquals("/ext", request.storageListRequest.path)
            client.onMessage(reply(request.commandId).setHasNext(true).setStorageListResponse(
                StorageProto.ListResponse.newBuilder().addFile(StorageProto.File.newBuilder().setName("z.bin").setSize(-1))).build())
            client.onMessage(reply(request.commandId).setStorageListResponse(StorageProto.ListResponse.newBuilder()
                .addFile(StorageProto.File.newBuilder().setName("nfc").setType(StorageProto.File.FileType.DIR))).build())
            null
        }
        assertTrue(result.first().isDirectory)
        assertEquals(4294967295L, result.last().sizeBytes)
    }

    @Test fun storageInfoRequestsSdCardAndKeeps64BitCapacity() = runBlocking<Unit> {
        val total = 128_000_000_000L
        val free = 96_000_000_000L
        val info = client.getStorageInfo("/ext", 1_000) { request ->
            assertEquals("/ext", request.storageInfoRequest.path)
            client.onMessage(reply(request.commandId)
                .setStorageInfoResponse(StorageProto.InfoResponse.newBuilder().setTotalSpace(total).setFreeSpace(free))
                .build())
            null
        }
        assertEquals(total, info.totalBytes)
        assertEquals(free, info.freeBytes)
        assertEquals(32_000_000_000L, info.usedBytes)
    }

    @Test fun storageInfoRejectsInvalidCapacityAndReportsUnmountedCard() = runBlocking<Unit> {
        fails(FlipperRpcException.Reason.UNEXPECTED_RESPONSE) {
            client.getStorageInfo("/ext", 1_000) { request ->
                client.onMessage(reply(request.commandId)
                    .setStorageInfoResponse(StorageProto.InfoResponse.newBuilder().setTotalSpace(10).setFreeSpace(11))
                    .build())
                null
            }
        }
        fails(FlipperRpcException.Reason.REMOTE_ERROR) {
            client.getStorageInfo("/ext", 1_000) { request ->
                client.onMessage(reply(request.commandId)
                    .setCommandStatus(FlipperProto.CommandStatus.ERROR_STORAGE_NOT_READY).build())
                null
            }
        }
    }

    @Test fun emptyDirectoryIsValidAndRemoteFailureIsNotEmptySuccess() = runBlocking<Unit> {
        assertTrue(client.listFiles("/ext", 1_000) { request ->
            client.onMessage(reply(request.commandId).setStorageListResponse(StorageProto.ListResponse.getDefaultInstance()).build()); null
        }.isEmpty())
        fails(FlipperRpcException.Reason.REMOTE_ERROR) {
            client.listFiles("/missing", 1_000) { request ->
                client.onMessage(reply(request.commandId).setCommandStatus(FlipperProto.CommandStatus.ERROR_STORAGE_NOT_EXIST)
                    .setEmpty(FlipperProto.Empty.getDefaultInstance()).build()); null
            }
        }
    }

    @Test fun rejectsUnsafeListNamesAndWrongReadContent() = runBlocking<Unit> {
        fails(FlipperRpcException.Reason.UNEXPECTED_RESPONSE) {
            client.listFiles("/ext", 1_000) { request ->
                client.onMessage(reply(request.commandId).setStorageListResponse(StorageProto.ListResponse.newBuilder()
                    .addFile(StorageProto.File.newBuilder().setName("../escape"))).build()); null
            }
        }
        fails(FlipperRpcException.Reason.UNEXPECTED_RESPONSE) {
            client.readFile("/ext/a", 1_000, { request ->
                client.onMessage(reply(request.commandId).setEmpty(FlipperProto.Empty.getDefaultInstance()).build()); null
            }) { fail("No chunk expected") }
        }
    }

    @Test fun readsBinaryChunksAndEmptyFiles() = runBlocking<Unit> {
        val output = ByteArrayOutputStream()
        val expected = byteArrayOf(0, 127, -1, 42)
        client.readFile("/ext/a", 1_000, { request ->
            expected.asList().chunked(2).forEachIndexed { index, bytes ->
                client.onMessage(reply(request.commandId).setHasNext(index == 0).setStorageReadResponse(
                    StorageProto.ReadResponse.newBuilder().setFile(StorageProto.File.newBuilder()
                        .setData(ByteString.copyFrom(bytes.toByteArray())))).build())
            }; null
        }) { output.write(it) }
        assertArrayEquals(expected, output.toByteArray())
        var chunks = 0
        client.readFile("/ext/empty", 1_000, { request ->
            client.onMessage(reply(request.commandId).setStorageReadResponse(StorageProto.ReadResponse.newBuilder()
                .setFile(StorageProto.File.getDefaultInstance())).build()); null
        }) { assertTrue(it.isEmpty()); chunks++ }
        assertEquals(1, chunks)
    }

    @Test fun readCancellationUnregistersAndLateRepliesDoNotPoisonRetry() = runBlocking<Unit> {
        val started = CompletableDeferred<Int>()
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            client.readFile("/ext/a", 1_000, { request -> started.complete(request.commandId); null }) { }
        }
        val oldId = started.await()
        caller.cancelAndJoin()
        client.onMessage(reply(oldId).setCommandStatus(FlipperProto.CommandStatus.ERROR).build())
        client.ping(1_000) { request ->
            client.onMessage(reply(request.commandId).setSystemPingResponse(SystemProto.PingResponse.getDefaultInstance()).build()); null
        }
    }

    @Test fun canceledReadDrainsUntilFinalFrameWithoutWritingMoreChunks() = runBlocking<Unit> {
        val firstChunk = CompletableDeferred<Unit>()
        var requestId = 0
        var writtenChunks = 0
        var drained = false
        val readResponse = StorageProto.ReadResponse.newBuilder()
            .setFile(StorageProto.File.newBuilder().setData(ByteString.copyFromUtf8("chunk"))).build()
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            client.readFile("/ext/a", 1_000, { request ->
                requestId = request.commandId
                client.onMessage(reply(requestId).setHasNext(true).setStorageReadResponse(readResponse).build())
                null
            }, onCanceledReadDrained = { drained = true }) {
                writtenChunks++
                firstChunk.complete(Unit)
                awaitCancellation()
            }
        }
        firstChunk.await()
        caller.cancel()
        yield()
        assertFalse(caller.isCompleted)
        client.onMessage(reply(requestId).setHasNext(true).setStorageReadResponse(readResponse).build())
        client.onMessage(reply(requestId).setStorageReadResponse(readResponse).build())
        withTimeout(1_000) { caller.join() }
        assertTrue(caller.isCancelled)
        assertTrue(drained)
        assertEquals(1, writtenChunks)
    }

    @Test fun disconnectDuringCanceledReadDoesNotReportDrained() = runBlocking<Unit> {
        val sent = CompletableDeferred<Unit>()
        var drained = false
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            client.readFile("/ext/a", 1_000, {
                sent.complete(Unit)
                null
            }, onCanceledReadDrained = { drained = true }) { fail("No chunk expected") }
        }
        sent.await()
        caller.cancel()
        yield()
        assertFalse(caller.isCompleted)
        client.onSessionClosed()
        withTimeout(1_000) { caller.join() }
        assertTrue(caller.isCancelled)
        assertFalse(drained)
    }

    @Test fun writeUsesOneIdBoundedChunksAndFinalAckAfterAllData() = runBlocking<Unit> {
        val expected = ByteArray(1025) { it.toByte() }
        val received = ByteArrayOutputStream()
        val commands = mutableSetOf<Int>()
        val sizes = mutableListOf<Int>()
        var progress = 0
        var acknowledged = false
        client.writeFile("/ext/a", ByteArrayInputStream(expected), 1_000, { request ->
            commands += request.commandId
            assertEquals("/ext/a", request.storageWriteRequest.path)
            val data = request.storageWriteRequest.file.data.toByteArray()
            sizes += data.size
            received.write(data)
            if (request.hasNext) assertTrue(data.isNotEmpty())
            else {
                client.onMessage(reply(request.commandId).setEmpty(FlipperProto.Empty.getDefaultInstance()).build())
                delay(10)
                acknowledged = true
            }; null
        }) { progress += it.size }
        assertEquals(listOf(512, 512, 1, 0), sizes)
        assertEquals(1, commands.size)
        assertArrayEquals(expected, received.toByteArray())
        assertEquals(1025, progress)
        assertTrue(acknowledged)
    }

    @Test fun emptyUploadStillSendsFinalRequest() = runBlocking<Unit> {
        var sends = 0
        client.writeFile("/ext/empty", ByteArrayInputStream(byteArrayOf()), 1_000, { request ->
            sends++
            assertFalse(request.hasNext)
            assertTrue(request.storageWriteRequest.hasFile())
            assertTrue(request.storageWriteRequest.file.data.isEmpty)
            client.onMessage(reply(request.commandId).setEmpty(FlipperProto.Empty.getDefaultInstance()).build()); null
        }) { fail("Empty upload has no content chunks") }
        assertEquals(1, sends)
    }

    @Test fun writeRejectsPrematureSuccessAndCancelsSenderOnRemoteError() = runBlocking<Unit> {
        var stopped = false
        fails(FlipperRpcException.Reason.UNEXPECTED_RESPONSE) {
            client.writeFile("/ext/a", ByteArrayInputStream(ByteArray(1000)), 1_000, { request ->
                client.onMessage(reply(request.commandId).setEmpty(FlipperProto.Empty.getDefaultInstance()).build())
                try { awaitCancellation() } finally { stopped = true }
            }) { }
        }
        assertTrue(stopped)
        fails(FlipperRpcException.Reason.REMOTE_ERROR) {
            client.writeFile("/ext/a", ByteArrayInputStream(ByteArray(1000)), 1_000, { request ->
                client.onMessage(reply(request.commandId).setCommandStatus(FlipperProto.CommandStatus.ERROR_STORAGE_INTERNAL).build())
                awaitCancellation()
            }) { }
        }
    }

    @Test fun missingFinalWriteAckTimesOutAndTransportFailureIsReported() = runBlocking<Unit> {
        fails(FlipperRpcException.Reason.TIMEOUT) {
            client.writeFile("/ext/a", ByteArrayInputStream(byteArrayOf()), 30, { null }) { }
        }
        fails(FlipperRpcException.Reason.SEND_FAILED) {
            client.writeFile("/ext/a", ByteArrayInputStream(byteArrayOf(1)), 1_000, { BleFailure.RPCError }) { }
        }
    }

    @Test fun overflowFailsInsteadOfSilentlyDroppingFileData() = runBlocking<Unit> {
        fails(FlipperRpcException.Reason.UNEXPECTED_RESPONSE) {
            client.readFile("/ext/a", 1_000, { request ->
                repeat(129) {
                    client.onMessage(reply(request.commandId).setHasNext(true).setStorageReadResponse(
                        StorageProto.ReadResponse.newBuilder().setFile(StorageProto.File.newBuilder()
                            .setData(ByteString.copyFromUtf8("x")))).build())
                }; null
            }) { }
        }
    }

    @Test fun statAndMd5ValidateResponses() = runBlocking<Unit> {
        val file = client.stat("/ext/a", 1_000) { request ->
            client.onMessage(reply(request.commandId).setStorageStatResponse(StorageProto.StatResponse.newBuilder()
                .setFile(StorageProto.File.newBuilder().setSize(5))).build()); null
        }
        assertEquals(5L, file.sizeBytes)
        val hash = "D41D8CD98F00B204E9800998ECF8427E"
        assertEquals(hash.lowercase(), client.md5("/ext/a", 1_000) { request ->
            client.onMessage(reply(request.commandId).setStorageMd5SumResponse(StorageProto.Md5sumResponse.newBuilder()
                .setMd5Sum(hash)).build()); null
        })
        fails(FlipperRpcException.Reason.UNEXPECTED_RESPONSE) {
            client.md5("/ext/a", 1_000) { request ->
                client.onMessage(reply(request.commandId).setStorageMd5SumResponse(StorageProto.Md5sumResponse.newBuilder()
                    .setMd5Sum("broken")).build()); null
            }
        }
    }
}
