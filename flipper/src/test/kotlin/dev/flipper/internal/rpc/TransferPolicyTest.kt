package dev.flipper.internal.rpc

import com.flipperdevices.protobuf.FlipperProto
import com.flipperdevices.protobuf.storage.StorageProto
import com.google.protobuf.ByteString
import dev.flipper.api.model.BleFailure
import dev.flipper.api.model.FlipperRpcException
import dev.flipper.api.model.PendingFileCleanup
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TransferPolicyTest {
    private class Device {
        var nextId = 0
        val client = RpcClient { ++nextId }
        val files = linkedMapOf<String, ByteArray>()
        val journal = linkedSetOf<PendingFileCleanup>()
        var writingId: Int? = null
        var corruptFinal = false
        var failJournal = false
        var writes = 0
        fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        suspend fun send(request: FlipperProto.Main): BleFailure? {
            val reply = FlipperProto.Main.newBuilder().setCommandId(request.commandId)
            when {
                request.hasStorageStatRequest() -> {
                    val bytes = files[request.storageStatRequest.path]
                    if (bytes == null) reply.setCommandStatus(FlipperProto.CommandStatus.ERROR_STORAGE_NOT_EXIST)
                    else reply.setStorageStatResponse(StorageProto.StatResponse.newBuilder().setFile(StorageProto.File.newBuilder().setSize(bytes.size)))
                }
                request.hasStorageWriteRequest() -> {
                    val write = request.storageWriteRequest
                    assertTrue("Ownership must be journaled before first byte", journal.any { it.path == write.path })
                    if (writingId != request.commandId) files[write.path] = byteArrayOf()
                    writingId = request.commandId
                    writes++
                    files[write.path] = checkNotNull(files[write.path]) + write.file.data.toByteArray()
                    if (request.hasNext) return null
                    reply.setEmpty(FlipperProto.Empty.getDefaultInstance())
                }
                request.hasStorageMd5SumRequest() -> reply.setStorageMd5SumResponse(StorageProto.Md5sumResponse.newBuilder()
                    .setMd5Sum(md5(checkNotNull(files[request.storageMd5SumRequest.path]))))
                request.hasStorageRenameRequest() -> {
                    val rename = request.storageRenameRequest
                    assertTrue(journal.any { it.path == rename.newPath })
                    assertFalse(files.containsKey(rename.newPath))
                    val bytes = checkNotNull(files.remove(rename.oldPath))
                    files[rename.newPath] = if (corruptFinal) bytes + byteArrayOf(7) else bytes
                    reply.setEmpty(FlipperProto.Empty.getDefaultInstance())
                }
                request.hasStorageDeleteRequest() -> {
                    assertFalse(request.storageDeleteRequest.recursive)
                    files.remove(request.storageDeleteRequest.path)
                    reply.setEmpty(FlipperProto.Empty.getDefaultInstance())
                }
                else -> error("Unexpected request "+request.contentCase)
            }
            client.onMessage(reply.build())
            return null
        }
        fun workflow() = RemoteUpload(client, ::send, ::send,
            remember = { if (failJournal) throw IOException("Journal unavailable") else journal.add(it); Unit },
            forget = { journal.remove(it); Unit },
            recover = { for (owned in journal.toList()) { client.deleteFile(owned.path, ::send); journal.remove(owned) } })
    }

    @Test fun choosesNextNameAndNeverChangesExistingFiles() = runBlocking<Unit> {
        val device = Device()
        device.files["/ext/probe.txt"] = byteArrayOf(1)
        device.files["/ext/probe (2).txt"] = byteArrayOf(2)
        val source = ByteArray(1025) { it.toByte() }
        val result = device.workflow().execute("A", "/ext/probe.txt", ByteArrayInputStream(source), source.size.toLong(), 0) { }
        assertEquals("/ext/probe (3).txt", result.path)
        assertEquals(device.md5(source), result.md5)
        assertArrayEquals(source, device.files[result.path])
        assertArrayEquals(byteArrayOf(1), device.files["/ext/probe.txt"])
        assertArrayEquals(byteArrayOf(2), device.files["/ext/probe (2).txt"])
        assertTrue(device.journal.isEmpty())
        assertFalse(device.files.keys.any { it.endsWith(".part") })
    }

    @Test fun cancellationRemovesOwnedPartialButKeepsExistingFile() = runBlocking<Unit> {
        val device = Device()
        device.files["/ext/a.bin"] = byteArrayOf(9)
        try {
            device.workflow().execute("A", "/ext/a.bin", ByteArrayInputStream(ByteArray(2048)), 2048, 0) {
                if (it.bytesTransferred >= 512) throw CancellationException("User cancelled")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(setOf("/ext/a.bin"), device.files.keys)
        assertArrayEquals(byteArrayOf(9), device.files["/ext/a.bin"])
        assertTrue(device.journal.isEmpty())
    }

    @Test fun failedFinalCopyIsNotReportedAsSuccessAndIsCleaned() = runBlocking<Unit> {
        val device = Device().apply { corruptFinal = true }
        try {
            device.workflow().execute("A", "/ext/a.bin", ByteArrayInputStream(byteArrayOf(1,2,3)), 3, 0) { }
            fail("Expected checksum failure")
        } catch (error: IOException) { assertTrue(error.message.orEmpty().contains("MD5")) }
        assertTrue(device.files.isEmpty())
        assertTrue(device.journal.isEmpty())
    }

    @Test fun cleanupFailureKeepsJournalForRecovery() = runBlocking<Unit> {
        val device = Device()
        val workflow = RemoteUpload(device.client, device::send, device::send,
            remember = { device.journal.add(it); Unit }, forget = { device.journal.remove(it); Unit },
            recover = { /* disconnected: retain ownership for the next session */ })
        try {
            workflow.execute("A", "/ext/a.bin", ByteArrayInputStream(ByteArray(1024)), 1024, 0) {
                if (it.bytesTransferred > 0) throw CancellationException("Lost link")
            }
        } catch (_: CancellationException) { }
        assertEquals(1, device.journal.size)
        assertTrue(device.files.keys.single().startsWith("/ext/.flipper-transfer-"))
        for (owned in device.journal.toList()) device.client.deleteFile(owned.path, device::send)
        assertTrue(device.files.isEmpty())
    }

    @Test fun durableJournalFailurePreventsWritingAnything() = runBlocking<Unit> {
        val device = Device().apply { failJournal = true }
        try {
            device.workflow().execute("A", "/ext/a", ByteArrayInputStream(byteArrayOf(1)), 1, 0) { }
            fail("Expected journal failure")
        } catch (_: IOException) { }
        assertEquals(0, device.writes)
        assertTrue(device.files.isEmpty())
    }

    @Test fun sourceReadErrorCleansOnlyOwnedPartialFile() = runBlocking<Unit> {
        val device = Device()
        device.files["/ext/existing"] = byteArrayOf(42)
        val source = object : java.io.InputStream() {
            var reads = 0
            override fun read(): Int = error("bulk read expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (reads++ > 0) throw IOException("Source disappeared")
                buffer.fill(3, offset, offset+minOf(512, length))
                return minOf(512, length)
            }
        }
        try { device.workflow().execute("A", "/ext/a.bin", source, null, 0) { }; fail("Expected source failure") }
        catch (error: IOException) { assertEquals("Source disappeared", error.message) }
        assertEquals(setOf("/ext/existing"), device.files.keys)
        assertTrue(device.journal.isEmpty())
    }

    @Test fun emptyFileAndUnknownSourceSizeAreSupported() = runBlocking<Unit> {
        val device = Device()
        val result = device.workflow().execute("A", "/ext/empty", ByteArrayInputStream(byteArrayOf()), null, 0) { }
        assertEquals(0L, result.bytesTransferred)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", result.md5)
        assertTrue(checkNotNull(device.files[result.path]).isEmpty())
    }

    @Test fun streamingHasNoOverallLimitButStillDetectsIdle() = runBlocking<Unit> {
        var id = 0
        val client = RpcClient(idleTimeoutMillis = 100) { ++id }
        var chunks = 0
        client.readFile("/ext/a", 0, { request ->
            repeat(4) { index ->
                delay(40)
                client.onMessage(FlipperProto.Main.newBuilder().setCommandId(request.commandId).setHasNext(index != 3)
                    .setStorageReadResponse(StorageProto.ReadResponse.newBuilder().setFile(StorageProto.File.newBuilder()
                        .setData(ByteString.copyFromUtf8("x")))).build())
            }; null
        }) { chunks++ }
        assertEquals(4, chunks)
        try { client.readFile("/ext/a", 0, { null }) { }; fail("Expected idle timeout") }
        catch (error: FlipperRpcException) { assertEquals(FlipperRpcException.Reason.TIMEOUT, error.reason) }
    }

    @Test fun longFinalizationCanWaitWhileSessionRemainsAlive() = runBlocking<Unit> {
        var id = 0
        val client = RpcClient(idleTimeoutMillis = 20) { ++id }
        client.renameFile("/ext/temp", "/ext/final") { request ->
            delay(80)
            client.onMessage(FlipperProto.Main.newBuilder().setCommandId(request.commandId)
                .setEmpty(FlipperProto.Empty.getDefaultInstance()).build()); null
        }
    }

    @Test fun suffixesPreserveLastExtensionAndHiddenNames() {
        assertEquals("/ext/a (2).txt", RemoteNames.candidate("/ext/a.txt", 2))
        assertEquals("/ext/archive.tar (3).gz", RemoteNames.candidate("/ext/archive.tar.gz", 3))
        assertEquals("/ext/.config (2)", RemoteNames.candidate("/ext/.config", 2))
        assertEquals("/ext/no-extension (2)", RemoteNames.candidate("/ext/no-extension", 2))
    }
}
