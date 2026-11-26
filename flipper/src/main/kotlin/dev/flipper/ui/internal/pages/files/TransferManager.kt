package dev.flipper.ui.internal.pages.files

import android.app.Application
import android.content.Intent
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import dev.flipper.api.Flipper
import java.io.IOException
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Process/service-owned operation. No ViewModel, Activity or navigation owner controls its job. */
internal class TransferManager private constructor(private val app: Application) {
    private val resolver: ContentResolver = app.contentResolver
    private val localCleanup = LocalDownloadCleanup.get(app)
    val localCleanupStatus = localCleanup.status
    private var activeDestination: Uri? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<TransferUi?>(null)
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var ready = CompletableDeferred<Unit>()
    private var sequence = 0L

    companion object {
        private val managers = WeakHashMap<Flipper, TransferManager>()
        fun get(client: Flipper, context: Application): TransferManager = synchronized(managers) {
            managers.getOrPut(client) { TransferManager(context) }
        }
    }

    fun onServiceReady() { ready.complete(Unit) }
    fun onServiceFailure(error: Exception) { finish("Background transfer failed: "+error.message); ready.completeExceptionally(error) }
    fun cancel() {
        _state.update { it?.copy(message = "Canceling transfer, cleaning up, and reconnecting…") }
        job?.cancel()
    }
    fun onServiceDestroyed() { if (_state.value?.active == true) cancel() }

    private fun finish(message: String, success: Boolean = false) {
        _state.update { it?.copy(active = false, successful = success, message = message, isCleaningUp = false) }
    }
    private suspend fun removePartial(uri: Uri): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        try { DocumentsContract.deleteDocument(resolver, uri) } catch (_: Exception) { false }
    }

    private suspend fun removeFailedDownload(destination: Uri): Boolean = withContext(NonCancellable) {
        _state.update { it?.copy(isCleaningUp = true) }
        val removed = removePartial(destination)
        if (removed) localCleanup.forget(destination)
        removed
    }

    private fun start(client: Flipper, title: String, total: Long?, beforeOperationFailure: suspend () -> Unit = {},
        operation: suspend () -> Unit): Boolean {
        if (_state.value?.active == true) return false
        val transfer = TransferUi(++sequence, title, total = total)
        _state.value = transfer
        ready = CompletableDeferred()
        try { TransferService.start(app, this) }
        catch (error: Exception) { finish("Could not start the background transfer: "+error.message); return false }
        job = scope.launch {
            // Keep observing after cancellation so the dialog can span RPC recovery and local cleanup.
            val recoveryWatch = scope.launch {
                client.isRecovering.first { it }
                _state.update { current ->
                    if (current?.id == transfer.id && current.active) current.copy(isCleaningUp = true) else current
                }
            }
            var enteredOperation = false
            try {
                withTimeout(8_000) { ready.await() }
                localCleanup.startup.join()
                localCleanup.retry()
                enteredOperation = true
                operation()
            } catch (cancel: CancellationException) {
                if (_state.value?.active == true) finish("Transfer canceled. Check the cleanup status.")
                throw cancel
            } catch (error: Exception) { finish("Transfer failed: "+(error.message ?: error.toString())) }
            finally {
                recoveryWatch.cancel()
                try { if (!enteredOperation) withContext(NonCancellable) { beforeOperationFailure() } }
                catch (error: Exception) { finish("Could not remove the created document: "+error.message) }
                finally { activeDestination = null; TransferService.finish(this@TransferManager); job = null }
            }
        }
        return true
    }

    /** Persist only additional grants; never release grants that the host already owned. */
    private suspend fun <T> withUri(uri: Uri, flags: Int, block: suspend (Int) -> T): T {
        val held = withContext(Dispatchers.IO) {
            resolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.let {
                (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            } ?: 0
        }
        val added = flags and held.inv()
        var persisted = false
        if (added != 0) withContext(Dispatchers.IO) {
            try { resolver.takePersistableUriPermission(uri, added); persisted = true } catch (_: SecurityException) { }
        }
        try { return block(if (persisted) added else 0) }
        finally { if (persisted && !localCleanup.contains(uri)) withContext(NonCancellable + Dispatchers.IO) {
            try { resolver.releasePersistableUriPermission(uri, added) } catch (_: SecurityException) { }
        } }
    }

    fun upload(client: Flipper, target: String, source: SelectedUpload, expectedDevice: String?) {
        start(client, "Uploading $target", source.sizeBytes) {
            try {
                val result = withUri(source.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) {
                    withContext(Dispatchers.IO) {
                        val input = resolver.openInputStream(source.uri) ?: throw IOException("Could not open the source file")
                        input.use { stream -> client.uploadFile(target, stream, source.sizeBytes, expectedDeviceAddress = expectedDevice ?: throw IOException("Select a connected Flipper")) { progress ->
                            _state.update { it?.copy(bytes = progress.bytesTransferred, total = progress.totalBytes,
                                message = "Uploading, then verifying and choosing an available filename") }
                        } }
                    }
                }
                finish("Uploaded: "+result.path+"\nMD5: "+result.md5, true)
            } catch (cancel: CancellationException) {
                finish(if (client.pendingFileCleanup.value.isEmpty()) "Canceled. Temporary files were cleaned. If finalization had started, inspect $target."
                    else "Canceled. File cleanup will resume when Flipper reconnects.")
                throw cancel
            } catch (error: Exception) {
                finish("Transfer failed: "+error.message+if (client.pendingFileCleanup.value.isEmpty())
                    "\nTemporary upload files were cleaned. If finalization failed, inspect the destination file."
                    else "\nCleanup will resume when Flipper reconnects.")
            }
        }
    }

    fun download(client: Flipper, path: String, destination: Uri, expectedDevice: String?) {
        val started = start(client, "Downloading $path", null, beforeOperationFailure = {
            // CreateDocument already created an empty destination even if FGS never became ready.
            if (!removePartial(destination)) localCleanup.remember(destination, 0)
        }) {
            activeDestination = destination
            try {
                val device = (client.connection.value as? dev.flipper.api.model.BleConnectionState.Connected)?.device
                if (expectedDevice == null || device?.address != expectedDevice)
                    throw IOException("The connected device changed while choosing where to save")
                val result = withUri(destination, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) { added ->
                    localCleanup.remember(destination, added)
                    withContext(Dispatchers.IO) {
                        val output = resolver.openOutputStream(destination, "w") ?: throw IOException("Could not open the destination file")
                        output.use { stream -> client.downloadFile(path, stream, expectedDeviceAddress = expectedDevice) { progress ->
                            _state.update { it?.copy(bytes = progress.bytesTransferred, total = progress.totalBytes,
                                message = "Downloading, then verifying MD5") }
                        } }
                    }
                }
                withContext(NonCancellable) { localCleanup.forget(destination) }
                finish("Saved with matching MD5: "+result.md5, true)
            } catch (cancel: CancellationException) {
                val removed = removeFailedDownload(destination)
                finish(if (removed) "Canceled. The partial document was removed." else "Canceled. Remove the partial document manually.")
                throw cancel
            } catch (error: Exception) {
                val removed = removeFailedDownload(destination)
                finish("Transfer failed: "+error.message+if (removed) "\nThe partial document was removed." else "\nAutomatic cleanup failed. Check the partial document.")
            }
        }
        if (!started && activeDestination != destination) scope.launch {
            if (!removePartial(destination)) localCleanup.remember(destination, 0)
        }
    }
}
