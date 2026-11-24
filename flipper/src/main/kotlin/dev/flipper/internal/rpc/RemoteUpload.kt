package dev.flipper.internal.rpc

import com.flipperdevices.protobuf.FlipperProto
import dev.flipper.api.model.BleFailure
import dev.flipper.api.model.FlipperRpcException
import dev.flipper.api.model.PendingFileCleanup
import dev.flipper.api.model.TransferProgress
import dev.flipper.api.model.TransferResult
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Protocol workflow, testable independently of Android and GATT. The caller serializes storage. */
internal class RemoteUpload(
    private val rpcClient: RpcClient,
    private val send: suspend (FlipperProto.Main) -> BleFailure?,
    private val blockSend: suspend (FlipperProto.Main) -> BleFailure?,
    private val remember: suspend (PendingFileCleanup) -> Unit,
    private val forget: suspend (PendingFileCleanup) -> Unit,
    private val recover: suspend () -> Unit,
) {
    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private suspend fun freeName(path: String, send: suspend (FlipperProto.Main) -> BleFailure?): String {
        var index = 1
        while (true) {
            val candidate = RemoteNames.candidate(path, index++)
            require(candidate.substringAfterLast('/').length <= 255) { "Resulting file name exceeds 255 ASCII characters" }
            try { rpcClient.stat(candidate, 30_000, send) }
            catch (error: FlipperRpcException) {
                if (error.reason == FlipperRpcException.Reason.REMOTE_ERROR &&
                    error.statusCode == FlipperProto.CommandStatus.ERROR_STORAGE_NOT_EXIST_VALUE) return candidate
                throw error
            }
        }
    }


    suspend fun execute(deviceAddress: String, path: String, source: InputStream, sizeBytes: Long?, timeoutMillis: Long,
        onProgress: (TransferProgress) -> Unit): TransferResult {
        var finalPath = freeName(path, send)
        var finalCleanup: PendingFileCleanup? = null
        val tempPath = path.substringBeforeLast('/') + "/.flipper-transfer-" + UUID.randomUUID() + ".part"
        val cleanup = PendingFileCleanup(deviceAddress, tempPath)
        remember(cleanup) // Persist ownership before the first byte can reach the device.
        val digest = MessageDigest.getInstance("MD5")
        var bytes = 0L
        return try {
            onProgress(TransferProgress(0, sizeBytes))
            // Do not cancel inside a delimited RPC frame. After a complete frame Delete can
            // safely interrupt firmware's continuous write without closing this GATT session.
            rpcClient.writeFile(tempPath, source, timeoutMillis, blockSend) { chunk ->
                digest.update(chunk)
                bytes += chunk.size
                onProgress(TransferProgress(bytes, sizeBytes))
            }
            if (sizeBytes != null && bytes != sizeBytes) throw IOException("Source size changed during upload")
            val localMd5 = digest.hex()
            if (localMd5 != rpcClient.md5(tempPath, 0L, send)) throw IOException("MD5 mismatch after upload")
            while (true) {
                finalPath = freeName(path, send)
                finalCleanup = PendingFileCleanup(deviceAddress, finalPath)
                remember(finalCleanup)
                try { rpcClient.renameFile(tempPath, finalPath, send); break }
                catch (error: FlipperRpcException) {
                    if (error.statusCode != FlipperProto.CommandStatus.ERROR_STORAGE_EXIST_VALUE) throw error
                    finalCleanup?.let { forget(it) }
                    finalCleanup = null
                }
            }
            if (localMd5 != rpcClient.md5(finalPath, 0L, send)) throw IOException("MD5 mismatch after finalizing upload")
            withContext(NonCancellable) {
                forget(cleanup)
                finalCleanup?.let { forget(it) }
            }
            TransferResult(bytes, localMd5, finalPath)
        } catch (error: Exception) {
            // Delete resets firmware's unfinished write and removes only our UUID temp file.
            withContext(NonCancellable) { recover() }
            throw error
        }
    }
}
