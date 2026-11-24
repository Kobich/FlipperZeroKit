package dev.flipper.zerokit.sample.presentation.pages.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.flipper.api.Flipper
import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.RpcStatus
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val flipper: Flipper,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(FilesUiState())
    val state = _state.asStateFlow()
    private var listing: Job? = null
    private var transfer: Job? = null
    private var picking: Job? = null

    init {
        viewModelScope.launch {
            combine(flipper.connection, flipper.rpcStatus) { connection, rpc ->
                (connection as? BleConnectionState.Connected)?.device?.address.takeIf { rpc == RpcStatus.ACTIVE }
            }.distinctUntilChanged().collect { address ->
                listing?.cancel()
                _state.update { it.copy(rpcReady = address != null, deviceAddress = address,
                    files = emptyList(), isLoading = false, error = null) }
                if (address != null) refresh()
            }
        }
    }

    fun open(path: String) {
        if (!_state.value.rpcReady || _state.value.transfer?.active == true) return
        listing?.cancel()
        _state.update { it.copy(path = path, files = emptyList(), error = null) }
        refresh()
    }

    fun refresh() {
        if (!_state.value.rpcReady || _state.value.transfer?.active == true) return
        listing?.cancel()
        val path = _state.value.path
        listing = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val files = flipper.listFiles(path)
                _state.update { it.copy(files = files, isLoading = false) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                Log.e("FilesViewModel", "List failed: $path", error)
                _state.update { it.copy(isLoading = false, error = error.message ?: error.toString()) }
            }
        }
    }

    fun selectUpload(uri: Uri) {
        picking?.cancel()
        picking = viewModelScope.launch {
            try {
                val selected = withContext(Dispatchers.IO) {
                    var name = "upload.bin"
                    var size: Long? = null
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                        }
                    }
                    SelectedUpload(uri, name, size)
                }
                _state.update { it.copy(upload = selected, error = null) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { _state.update { it.copy(error = error.message ?: error.toString()) } }
        }
    }

    fun upload(name: String) {
        val selected = _state.value.upload ?: return
        val snapshot = _state.value
        if (!snapshot.rpcReady || snapshot.transfer?.active == true) return
        if (name.isBlank() || name == "." || name == ".." || name.any { it.code !in 32..126 || it == '/' || it.code == 92 }) {
            _state.update { it.copy(error = "Для первой проверки используй имя из ASCII без / и \\.") }
            return
        }
        val target = snapshot.path.trimEnd('/') + "/" + name
        listing?.cancel()
        _state.update { it.copy(isLoading = false, error = null, transfer = TransferUi("Отправка: $target", total = selected.sizeBytes)) }
        transfer = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(selected.uri)
                        ?: throw IOException("Не удалось открыть выбранный файл")
                    input.use {
                        flipper.uploadFile(target, it, selected.sizeBytes) { progress ->
                            _state.update { state -> state.copy(transfer = state.transfer?.copy(bytes = progress.bytesTransferred,
                                total = progress.totalBytes, message = "Отправка; затем подтверждение и MD5")) }
                        }
                    }
                }
                _state.update { it.copy(transfer = it.transfer?.copy(active = false,
                    message = "Готово, MD5 совпал: "+result.md5), upload = null) }
                refresh()
            } catch (cancel: CancellationException) {
                finish("Отменено. На Flipper мог остаться частичный файл. Подключись снова и выбери другое имя.")
                throw cancel
            } catch (error: Exception) {
                Log.e("FilesViewModel", "Upload failed: $target", error)
                finish("Ошибка: "+(error.message ?: error.toString())+". Если запись началась, возможен частичный файл.")
            }
        }
    }

    fun download(path: String, destination: Uri, expectedDevice: String?) {
        if (_state.value.transfer?.active == true) return
        listing?.cancel()
        _state.update { it.copy(isLoading = false, error = null, transfer = TransferUi("Скачивание: $path")) }
        transfer = viewModelScope.launch {
            try {
                if (expectedDevice == null || _state.value.deviceAddress != expectedDevice || !_state.value.rpcReady)
                    throw IOException("Подключение изменилось во время выбора файла. Выбери файл заново")
                val result = withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(destination, "w")
                        ?: throw IOException("Не удалось открыть место сохранения")
                    output.use {
                        flipper.downloadFile(path, it) { progress ->
                            _state.update { state -> state.copy(transfer = state.transfer?.copy(bytes = progress.bytesTransferred,
                                total = progress.totalBytes, message = "Скачивание; затем проверка MD5")) }
                        }
                    }
                }
                finish("Сохранено, MD5 совпал: "+result.md5)
            } catch (cancel: CancellationException) {
                val removed = removePartial(destination)
                finish("Отменено. "+if (removed) "Частичный документ удалён." else "Не удалось удалить частичный документ: удали его вручную.")
                throw cancel
            } catch (error: Exception) {
                Log.e("FilesViewModel", "Download failed: $path", error)
                val removed = removePartial(destination)
                finish("Ошибка: "+(error.message ?: error.toString())+if (removed) ". Частичный документ удалён." else ". Проверь и удали частичный документ вручную.")
            }
        }
    }

    private suspend fun removePartial(uri: Uri): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (_: Exception) { false }
    }
    private fun finish(message: String) { _state.update { it.copy(transfer = it.transfer?.copy(active = false, message = message)) } }
    fun cancelTransfer() { transfer?.cancel() }
}
