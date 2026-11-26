package dev.flipper.ui.internal.pages.files

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import dev.flipper.internal.io.ProviderIo
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Journal only newly created SAF documents. State is confined to Main; IO never owns journal locks. */
internal class LocalDownloadCleanup private constructor(private val app: Application) {
    private data class Record(val uri: String, val addedFlags: Int)
    private val prefs = app.getSharedPreferences("flipper_partial_downloads", Context.MODE_PRIVATE)
    private val records = prefs.getStringSet("uris", emptySet()).orEmpty().mapNotNull {
        val parts = it.split('|', limit = 2)
        val flags = parts.firstOrNull()?.toIntOrNull()
        if (flags == null || parts.size != 2 || !parts[1].startsWith("content://")) null else Record(parts[1], flags)
    }.toMutableList()
    private val reserved = mutableSetOf<String>()
    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val startup = scope.launch { retry() }
    companion object {
        private var instance: LocalDownloadCleanup? = null
        fun get(app: Application): LocalDownloadCleanup = synchronized(this) { instance ?: LocalDownloadCleanup(app).also { instance = it } }
    }
    fun contains(uri: Uri) = records.any { it.uri == uri.toString() }
    // Synchronous commit in the SAF callback closes the former notification/service gap.
    // This small write deliberately precedes any suspend point or notification permission dialog.
    @SuppressLint("UseKtx")
    private fun save(updated: List<Record>) {
        if (!prefs.edit().putStringSet("uris", updated.map { it.addedFlags.toString()+"|"+it.uri }.toSet()).commit())
            throw IOException("Could not save the document cleanup record")
        records.clear(); records.addAll(updated)
        updateStatus()
    }
    private fun updateStatus() {
        val pending = records.count { it.uri !in reserved }
        _status.value = if (pending == 0) "" else "Partial downloads awaiting cleanup: $pending. Retry cleanup when the document provider is available."
    }
    fun claim(uri: Uri) {
        reserved.add(uri.toString())
        try { save(records.filterNot { it.uri == uri.toString() } + Record(uri.toString(), 0)) }
        catch (error: Exception) { reserved.remove(uri.toString()); throw error }
    }
    suspend fun prepare(uri: Uri) {
        val key = uri.toString()
        val flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val added = ProviderIo.shared.call(key, discard = { added: Int ->
            if (added != 0) app.contentResolver.releasePersistableUriPermission(uri, added)
        }) {
            val held = app.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.isWritePermission == true
            if (held) 0 else try { app.contentResolver.takePersistableUriPermission(uri, flags); flags } catch (_: SecurityException) { 0 }
        }
        // Retain only grants added by this operation; an existing host grant is never released.
        try { save(records.filterNot { it.uri == key } + Record(key, added)) }
        catch (error: Exception) {
            if (added != 0) try { ProviderIo.shared.call(key, 5_000) { app.contentResolver.releasePersistableUriPermission(uri, added) } } catch (_: Exception) { }
            throw error
        }
    }
    suspend fun forget(uri: Uri) {
        val record = records.firstOrNull { it.uri == uri.toString() } ?: return
        save(records - record)
        reserved.remove(uri.toString()); updateStatus()
        if (record.addedFlags != 0) try {
            ProviderIo.shared.call(uri.toString(), 5_000) { app.contentResolver.releasePersistableUriPermission(uri, record.addedFlags) }
        } catch (_: Exception) { }
    }
    suspend fun remove(uri: Uri): Boolean = withContext(NonCancellable) {
        reserved.remove(uri.toString()); updateStatus()
        // A timed-out writer may still be inside Binder. Never delete underneath it.
        if (ProviderIo.shared.isBusy(uri.toString())) return@withContext false
        val deleted = try {
            ProviderIo.shared.call(uri.toString(), 10_000) { DocumentsContract.deleteDocument(app.contentResolver, uri) }
        } catch (_: FileNotFoundException) { true } catch (_: Exception) { false }
        if (deleted) forget(uri)
        deleted
    }
    suspend fun retry() {
        val previous = records.toList().filter { it.uri !in reserved }
        val started = System.nanoTime()
        for (record in previous) {
            if ((System.nanoTime() - started) / 1_000_000 >= 10_000) break
            try { remove(record.uri.toUri()) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { }
        }
        updateStatus()
    }
}
