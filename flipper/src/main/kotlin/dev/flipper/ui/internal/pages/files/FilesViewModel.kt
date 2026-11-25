package dev.flipper.ui.internal.pages.files

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.flipper.api.Flipper
import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.RpcStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class FilesViewModel(private val flipper: Flipper, application: Application) : ViewModel() {
    private val resolver = application.contentResolver
    private val transfers = TransferManager.get(flipper, application)
    private val local = MutableStateFlow(FilesUiState())
    val state = combine(local, transfers.state, transfers.localCleanupStatus) { screen, operation, cleanup -> screen.copy(transfer = operation, cleanupStatus = cleanup) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FilesUiState())
    private var listing: Job? = null
    private var picking: Job? = null

    init {
        viewModelScope.launch {
            combine(flipper.connection, flipper.rpcStatus) { connection, rpc ->
                (connection as? BleConnectionState.Connected)?.device?.address.takeIf { rpc == RpcStatus.ACTIVE }
            }.distinctUntilChanged().collect { address ->
                listing?.cancel()
                local.update { it.copy(rpcReady = address != null, deviceAddress = address, files = emptyList(), isLoading = false, error = null) }
                if (address != null) refresh()
            }
        }
        viewModelScope.launch {
            transfers.state.collect { operation ->
                if (operation?.active == false && operation.successful) {
                    local.update { it.copy(upload = null) }
                    refresh()
                }
            }
        }
    }
    private fun available() = local.value.rpcReady && transfers.state.value?.active != true
    fun open(path: String) {
        if (!available()) return
        listing?.cancel()
        local.update { it.copy(path = path, files = emptyList(), error = null) }
        refresh()
    }
    fun refresh() {
        if (!available()) return
        listing?.cancel()
        val path = local.value.path
        listing = viewModelScope.launch {
            local.update { it.copy(isLoading = true, error = null) }
            try { val files = flipper.listFiles(path); local.update { it.copy(files = files, isLoading = false) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { local.update { it.copy(isLoading = false, error = error.message ?: error.toString()) } }
        }
    }
    fun selectUpload(uri: Uri) {
        picking?.cancel()
        picking = viewModelScope.launch {
            try {
                val selected = withContext(Dispatchers.IO) {
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
                local.update { it.copy(upload = selected, error = null) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { local.update { it.copy(error = error.message ?: error.toString()) } }
        }
    }
    fun upload(name: String) {
        val source = local.value.upload ?: return
        if (!available()) return
        if (name.isBlank() || name == "." || name == ".." || name.any { it.code !in 32..126 || it == '/' || it.code == 92 }) {
            local.update { it.copy(error = "Укажи ASCII-имя без разделителей пути") }; return
        }
        listing?.cancel()
        local.update { it.copy(isLoading = false, error = null) }
        transfers.upload(flipper, local.value.path.trimEnd('/')+"/"+name, source)
    }
    fun download(path: String, destination: Uri, expectedDevice: String?) {
        listing?.cancel()
        local.update { it.copy(isLoading = false, error = null) }
        transfers.download(flipper, path, destination, expectedDevice)
    }
    fun cancelTransfer() = transfers.cancel()
}
