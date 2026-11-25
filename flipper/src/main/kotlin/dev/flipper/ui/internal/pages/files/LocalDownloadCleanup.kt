package dev.flipper.ui.internal.pages.files

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Journal only documents created by this feature's CreateDocument flow. No transfer resume. */
internal class LocalDownloadCleanup private constructor(private val app: Application) {
    private data class Record(val uri: String, val addedFlags: Int)
    private val prefs = app.getSharedPreferences("flipper_partial_downloads", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val records = MutableStateFlow(prefs.getStringSet("uris", emptySet()).orEmpty().mapNotNull {
        val parts = it.split('|', limit = 2)
        val flags = parts.firstOrNull()?.toIntOrNull()
        if (flags == null || parts.size != 2 || !parts[1].startsWith("content://")) null else Record(parts[1], flags)
    })
    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val startup: Job = scope.launch { retry() }
    companion object {
        private var instance: LocalDownloadCleanup? = null
        fun get(app: Application): LocalDownloadCleanup = synchronized(this) { instance ?: LocalDownloadCleanup(app).also { instance = it } }
    }
    fun contains(uri: Uri) = records.value.any { it.uri == uri.toString() }
    // KTX edit discards commit's boolean; failure must prevent starting an unjournaled download.
    @SuppressLint("UseKtx")
    private suspend fun persist(updated: List<Record>) = withContext(Dispatchers.IO) {
        if (!prefs.edit().putStringSet("uris", updated.map { it.addedFlags.toString()+"|"+it.uri }.toSet()).commit())
            throw IOException("Could not save the document cleanup record")
    }
    suspend fun remember(uri: Uri, flags: Int) = mutex.withLock {
        val updated = records.value.filterNot { it.uri == uri.toString() } + Record(uri.toString(), flags)
        persist(updated); records.value = updated
    }
    suspend fun forget(uri: Uri, releaseGrant: Boolean = true) = mutex.withLock {
        val record = records.value.firstOrNull { it.uri == uri.toString() } ?: return@withLock
        val updated = records.value - record
        persist(updated); records.value = updated
        if (releaseGrant && record.addedFlags != 0) withContext(Dispatchers.IO) {
            try { app.contentResolver.releasePersistableUriPermission(uri, record.addedFlags) } catch (_: SecurityException) { }
        }
    }
    suspend fun retry() {
        val previous = records.value.toList()
        if (previous.isEmpty()) return
        var removed = 0
        for (record in previous) {
            val uri = record.uri.toUri()
            val deleted = withContext(Dispatchers.IO) {
                try { DocumentsContract.deleteDocument(app.contentResolver, uri) } catch (_: Exception) { false }
            }
            if (deleted) { forget(uri); removed++ }
        }
        _status.value = "Partial downloads removed: "+removed+", pending removal: "+records.value.size
    }
}
