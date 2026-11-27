package dev.flipper.ui.internal.pages.files

import android.app.Application
import android.content.Intent
import android.content.ContentResolver
import android.net.Uri
import dev.flipper.internal.io.ProviderIo
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
import kotlinx.coroutines.TimeoutCancellationException
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
    private val history = TransferHistory.get(app)
    val historyEntries = history.entries
    val localCleanupStatus = localCleanup.status
    private var activeDestination: Uri? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<TransferUi?>(null)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
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
        _state.update { if (it?.active == true) it.copy(message = "Canceling transfer, cleaning up, and reconnecting…") else it }
        job?.cancel()
    }
    fun onServiceDestroyed() { if (_state.value?.active == true) cancel() }

    private fun fail(error: Exception, suffix: String = "") {
        _state.update { it?.copy(diagnostic = error.stackTraceToString()) }
        finish(fileErrorMessage(error)+suffix)
    }
    private fun finish(message: String, outcome: TransferOutcome = TransferOutcome.FAILED) {
        _state.update { it?.copy(outcome = outcome, message = message, isCleaningUp = false) }
    }

    private fun preparationFailed(error: Exception) {
        if (_busy.value) return
        val result = TransferUi(++sequence, "Download", outcome = TransferOutcome.FAILED,
            message = fileErrorMessage(error), diagnostic = error.stackTraceToString())
        _state.value = result
        history.record(result)
    }
    fun prepareDownload(destination: Uri, onReady: () -> Unit, onFailure: () -> Unit) {
        try { localCleanup.claim(destination) }
        catch (error: Exception) {
            onFailure()
            scope.launch { localCleanup.remove(destination) }
            preparationFailed(error)
            return
        }
        if (_busy.value || job?.isActive == true) {
            onFailure()
            scope.launch { localCleanup.remove(destination) }
            return
        }
        scope.launch {
            try { localCleanup.prepare(destination); onReady() }
            catch (error: Exception) {
                onFailure()
                withContext(NonCancellable) { localCleanup.remove(destination) }
                preparationFailed(error)
            }
        }
    }
    private suspend fun removePartial(uri: Uri): Boolean = localCleanup.remove(uri)
    fun retryCleanup() { if (!_busy.value) scope.launch { localCleanup.retry() } }

    private suspend fun removeFailedDownload(destination: Uri): Boolean = withContext(NonCancellable) {
        _state.update { it?.copy(isCleaningUp = true) }
        val removed = removePartial(destination)
        removed
    }

    private fun start(client: Flipper, title: String, total: Long?, beforeOperationFailure: suspend () -> Unit = {},
        operation: suspend () -> Unit): Boolean {
        if (_busy.value) return false
        val transfer = TransferUi(++sequence, title, total = total)
        _busy.value = true
        _state.value = transfer
        ready = CompletableDeferred()
        try { TransferService.start(app, this) }
        catch (error: Exception) {
            finish("Could not start the background transfer: "+error.message)
            _state.value?.let(history::record)
            _busy.value = false
            return false
        }
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
            } catch (timeout: TimeoutCancellationException) {
                fail(timeout)
            } catch (cancel: CancellationException) {
                if (_state.value?.active == true) finish("Transfer canceled. Check the cleanup status.", TransferOutcome.CANCELED)
                throw cancel
            } catch (error: Exception) { fail(error) }
            finally {
                recoveryWatch.cancel()
                try { if (!enteredOperation) withContext(NonCancellable) { beforeOperationFailure() } }
                catch (error: Exception) { finish("Could not remove the created document: "+error.message) }
                finally {
                    activeDestination = null
                    TransferService.finish(this@TransferManager)
                    _state.value?.takeIf { it.id == transfer.id }?.let(history::record)
                    job = null
                    _busy.value = false
                }
            }
        }
        return true
    }

    /** Persist only additional grants; never release grants that the host already owned. */
    private suspend fun <T> withUri(uri: Uri, flags: Int, block: suspend (Int) -> T): T {
        val held = ProviderIo.shared.call(uri.toString()) {
            resolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.let {
                (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            } ?: 0
        }
        val added = flags and held.inv()
        var persisted = false
        if (added != 0) ProviderIo.shared.call(uri.toString()) {
            try { resolver.takePersistableUriPermission(uri, added); persisted = true } catch (_: SecurityException) { }
        }
        try { return block(if (persisted) added else 0) }
        finally { if (persisted && !localCleanup.contains(uri)) withContext(NonCancellable) {
            try { ProviderIo.shared.call(uri.toString(), 5_000) { resolver.releasePersistableUriPermission(uri, added) } } catch (_: Exception) { }
        } }
    }

    fun upload(client: Flipper, target: String, source: SelectedUpload, expectedDevice: String?) {
        start(client, "Uploading $target", source.sizeBytes) {
            try {
                val result = withUri(source.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) {
                    withContext(Dispatchers.IO) {
                        val input = ProviderIo.shared.call(source.uri.toString(), discard = { it?.close() }) { resolver.openInputStream(source.uri) }
                            ?: throw IOException("Could not open the source file")
                        input.bounded(source.uri.toString()).use { stream -> client.uploadFile(target, stream, source.sizeBytes, expectedDeviceAddress = expectedDevice ?: throw IOException("Select a connected Flipper")) { progress ->
                            _state.update { it?.copy(bytes = progress.bytesTransferred, total = progress.totalBytes,
                                message = "Uploading, then verifying and choosing an available filename") }
                        } }
                    }
                }
                finish("Uploaded: "+result.path+"\nMD5: "+result.md5, TransferOutcome.SUCCEEDED)
            } catch (cancel: CancellationException) {
                finish(if (client.pendingFileCleanup.value.isEmpty()) "Canceled. Temporary files were cleaned. If finalization had started, inspect $target."
                    else "Canceled. File cleanup will resume when Flipper reconnects.", TransferOutcome.CANCELED)
                throw cancel
            } catch (error: Exception) {
                fail(error, if (client.pendingFileCleanup.value.isEmpty())
                    "\nTemporary upload files were cleaned. If finalization failed, inspect the destination file."
                    else "\nCleanup will resume when Flipper reconnects.")
            }
        }
    }

    fun download(client: Flipper, path: String, destination: Uri, expectedDevice: String?) {
        val started = start(client, "Downloading $path", null, beforeOperationFailure = {
            // CreateDocument already created an empty destination even if FGS never became ready.
            removePartial(destination)
        }) {
            activeDestination = destination
            try {
                val device = (client.connection.value as? dev.flipper.api.model.BleConnectionState.Connected)?.device
                if (expectedDevice == null || device?.address != expectedDevice)
                    throw IOException("The connected device changed while choosing where to save")
                val result = withContext(Dispatchers.IO) {
                        val output = ProviderIo.shared.call(destination.toString(), discard = { it?.close() }) { resolver.openOutputStream(destination, "w") }
                            ?: throw IOException("Could not open the destination file")
                        output.bounded(destination.toString()).use { stream -> client.downloadFile(path, stream, expectedDeviceAddress = expectedDevice) { progress ->
                            _state.update { it?.copy(bytes = progress.bytesTransferred, total = progress.totalBytes,
                                message = "Downloading, then verifying MD5") }
                        } }
                }
                val name = try {
                    ProviderIo.shared.call(destination.toString(), 5_000) {
                        resolver.query(destination, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                    } ?: path.substringAfterLast('/')
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { path.substringAfterLast('/') }
                withContext(NonCancellable) {
                    localCleanup.forget(destination)
                    _state.update { it?.copy(outputUri = destination.toString(), outputName = name) }
                    finish("Saved: "+name+"\nMD5 matched: "+result.md5, TransferOutcome.SUCCEEDED)
                }
            } catch (cancel: CancellationException) {
                val removed = removeFailedDownload(destination)
                finish(if (removed) "Canceled. The partial document was removed."
                    else "Canceled. Cleanup is deferred until the document provider is available. Use Retry cleanup.", TransferOutcome.CANCELED)
                throw cancel
            } catch (error: Exception) {
                val removed = removeFailedDownload(destination)
                fail(error, if (removed) "\nThe partial document was removed." else "\nAutomatic cleanup failed. Check the partial document.")
            }
        }
        if (!started && activeDestination != destination) scope.launch {
            removePartial(destination)
        }
    }
}
