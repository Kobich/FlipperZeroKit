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
    fun onServiceFailure(error: Exception) { finish("Ошибка фоновой службы: "+error.message); ready.completeExceptionally(error) }
    fun cancel() {
        _state.update { it?.copy(message = "Отмена, очистка файла и восстановление связи…") }
        job?.cancel()
    }
    fun onServiceDestroyed() { if (_state.value?.active == true) cancel() }

    private fun finish(message: String, success: Boolean = false) {
        _state.update { it?.copy(active = false, successful = success, message = message) }
    }
    private suspend fun removePartial(uri: Uri): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        try { DocumentsContract.deleteDocument(resolver, uri) } catch (_: Exception) { false }
    }

    private fun start(title: String, total: Long?, beforeOperationFailure: suspend () -> Unit = {}, operation: suspend () -> Unit): Boolean {
        if (_state.value?.active == true) return false
        _state.value = TransferUi(++sequence, title, total = total)
        ready = CompletableDeferred()
        try { TransferService.start(app, this) }
        catch (error: Exception) { finish("Не удалось запустить фоновую передачу: "+error.message); return false }
        job = scope.launch {
            var enteredOperation = false
            try {
                withTimeout(8_000) { ready.await() }
                localCleanup.startup.join()
                localCleanup.retry()
                enteredOperation = true
                operation()
            } catch (cancel: CancellationException) {
                if (_state.value?.active == true) finish("Операция отменена; проверьте состояние очистки файла")
                throw cancel
            } catch (error: Exception) { finish("Ошибка: "+(error.message ?: error.toString())) }
            finally {
                try { if (!enteredOperation) withContext(NonCancellable) { beforeOperationFailure() } }
                catch (error: Exception) { finish("Не удалось очистить созданный документ: "+error.message) }
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

    fun upload(client: Flipper, target: String, source: SelectedUpload) {
        start("Отправка: $target", source.sizeBytes) {
            try {
                val result = withUri(source.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) {
                    withContext(Dispatchers.IO) {
                        val input = resolver.openInputStream(source.uri) ?: throw IOException("Не удалось открыть источник")
                        input.use { stream -> client.uploadFile(target, stream, source.sizeBytes) { progress ->
                            _state.update { it?.copy(bytes = progress.bytesTransferred, total = progress.totalBytes,
                                message = "Передача; затем проверка и назначение свободного имени") }
                        } }
                    }
                }
                finish("Готово: "+result.path+"\nMD5: "+result.md5, true)
            } catch (cancel: CancellationException) {
                finish(if (client.pendingFileCleanup.value.isEmpty()) "Отменено. Частичный файл удалён."
                    else "Отменено. Удаление файла сохранено и повторится при подключении.")
                throw cancel
            } catch (error: Exception) {
                finish("Ошибка: "+error.message+if (client.pendingFileCleanup.value.isEmpty())
                    "\nЧастичные файлы очищены либо запись ещё не начиналась."
                    else "\nОчистка ожидает доступного соединения с Flipper.")
            }
        }
    }

    fun download(client: Flipper, path: String, destination: Uri, expectedDevice: String?) {
        val started = start("Скачивание: $path", null, beforeOperationFailure = {
            // CreateDocument already created an empty destination even if FGS never became ready.
            if (!removePartial(destination)) localCleanup.remember(destination, 0)
        }) {
            activeDestination = destination
            try {
                val device = (client.connection.value as? dev.flipper.api.model.BleConnectionState.Connected)?.device
                if (expectedDevice == null || device?.address != expectedDevice)
                    throw IOException("Устройство изменилось во время выбора места сохранения")
                val result = withUri(destination, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) { added ->
                    localCleanup.remember(destination, added)
                    withContext(Dispatchers.IO) {
                        val output = resolver.openOutputStream(destination, "w") ?: throw IOException("Не удалось открыть назначение")
                        output.use { stream -> client.downloadFile(path, stream) { progress ->
                            _state.update { it?.copy(bytes = progress.bytesTransferred, total = progress.totalBytes,
                                message = "Скачивание; затем проверка MD5") }
                        } }
                    }
                }
                withContext(NonCancellable) { localCleanup.forget(destination) }
                finish("Сохранено, MD5 совпал: "+result.md5, true)
            } catch (cancel: CancellationException) {
                val removed = removePartial(destination)
                if (removed) withContext(NonCancellable) { localCleanup.forget(destination) }
                finish(if (removed) "Отменено. Частичный документ удалён." else "Отменено. Не удалось удалить документ: требуется ручная очистка.")
                throw cancel
            } catch (error: Exception) {
                val removed = removePartial(destination)
                if (removed) withContext(NonCancellable) { localCleanup.forget(destination) }
                finish("Ошибка: "+error.message+if (removed) "\nЧастичный документ удалён." else "\nПроверьте частичный документ: автоматическое удаление не удалось.")
            }
        }
        if (!started && activeDestination != destination) scope.launch {
            if (!removePartial(destination)) localCleanup.remember(destination, 0)
        }
    }
}
