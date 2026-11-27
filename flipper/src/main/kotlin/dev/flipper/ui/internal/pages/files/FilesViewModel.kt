package dev.flipper.ui.internal.pages.files

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.flipper.api.Flipper
import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.RpcStatus
import dev.flipper.internal.io.ProviderIo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class FilesViewModel(private val flipper: Flipper, application: Application) : ViewModel() {
    private val resolver = application.contentResolver
    private val transfers = TransferManager.get(flipper, application)
    val history = transfers.historyEntries
    val transferBusy = transfers.busy
    private val local = MutableStateFlow(FilesUiState())
    val state = combine(local, transfers.state, transfers.localCleanupStatus, transferBusy) { screen, operation, cleanup, busy ->
        screen.copy(transfer = operation, cleanupStatus = cleanup, transferBusy = busy)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FilesUiState())
    private var listing: Job? = null
    private var picking: Job? = null
    private var lastDevice: String? = null
    private var selectionRequest = 0L

    init {
        viewModelScope.launch {
            combine(flipper.connection, flipper.rpcStatus) { connection, rpc ->
                (connection as? BleConnectionState.Connected)?.device?.address.takeIf { rpc == RpcStatus.ACTIVE }
            }.distinctUntilChanged().collect { address ->
                listing?.cancel()
                val changedDevice = address != null && lastDevice != null && address != lastDevice
                if (changedDevice) clearUpload()
                if (address != null) lastDevice = address
                local.update { it.copy(rpcReady = address != null, deviceAddress = address, files = emptyList(),
                    hasLoaded = false, isLoading = false, error = null,
                    path = if (changedDevice) "/ext" else it.path) }
                if (address != null) refresh()
            }
        }
        viewModelScope.launch {
            combine(transfers.state, transferBusy) { operation, busy ->
                operation?.takeIf { !it.active && !busy }
            }.distinctUntilChanged().collect { result ->
                if (result == null) return@collect
                if (result.successful) local.update { it.copy(upload = null, showUploadDialog = false) }
                if (result.successful || !local.value.hasLoaded) refresh()
            }
        }
    }
    private fun available() = local.value.rpcReady && !local.value.isPreparingDocument && !transferBusy.value
    fun open(path: String) {
        if (!available()) return
        listing?.cancel()
        local.update { it.copy(path = path, files = emptyList(), hasLoaded = false, error = null) }
        refresh()
    }
    fun refresh() {
        if (!available()) return
        listing?.cancel()
        val path = local.value.path
        val address = local.value.deviceAddress
        listing = viewModelScope.launch {
            local.update { it.copy(isLoading = true, hasLoaded = false, error = null) }
            try { val files = flipper.listFiles(path, expectedDeviceAddress = address); local.update { it.copy(files = files, hasLoaded = true, isLoading = false) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { local.update { it.copy(isLoading = false, error = fileErrorMessage(error)) } }
        }
    }
    fun selectUpload(uri: Uri) {
        val request = ++selectionRequest
        picking?.cancel()
        local.update { it.copy(upload = null, showUploadDialog = false, isPreparingUpload = true, error = null) }
        picking = viewModelScope.launch {
            try {
                val selected = ProviderIo.shared.call(uri.toString()) {
                    var name = "upload.bin"
                    var size: Long? = null
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n)
                            if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s).takeIf { it >= 0 }
                        }
                    }
                    SelectedUpload(uri, name, size)
                }
                local.update { it.copy(upload = selected, showUploadDialog = true, isPreparingUpload = false) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { local.update { it.copy(error = fileErrorMessage(error)) } }
            finally {
                if (request == selectionRequest) local.update { it.copy(isPreparingUpload = false) }
            }
        }
    }
    fun hideUploadDialog() { local.update { it.copy(showUploadDialog = false) } }
    fun clearUpload() {
        selectionRequest++
        picking?.cancel()
        picking = null
        local.update { it.copy(upload = null, showUploadDialog = false, isPreparingUpload = false) }
    }
    fun upload(target: String, expectedDevice: String?) {
        val name = target.substringAfterLast('/')
        val source = local.value.upload ?: return
        if (!available()) return
        if (!isValidUploadName(name)) {
            local.update { it.copy(error = "Use 1–255 ASCII characters without path separators.") }
            return
        }
        listing?.cancel()
        local.update { it.copy(isLoading = false, error = null) }
        transfers.upload(flipper, target, source, expectedDevice)
    }
    fun download(path: String, destination: Uri, expectedDevice: String?) {
        listing?.cancel()
        local.update { it.copy(isLoading = false, error = null) }
        transfers.download(flipper, path, destination, expectedDevice)
    }
    fun prepareDownload(uri: Uri, onReady: () -> Unit) {
        local.update { it.copy(isPreparingDownload = true) }
        transfers.prepareDownload(uri,
            onReady = { local.update { it.copy(isPreparingDownload = false) }; onReady() },
            onFailure = { local.update { it.copy(isPreparingDownload = false) } })
    }
    fun retryCleanup() = transfers.retryCleanup()
    fun cancelTransfer() = transfers.cancel()
}
