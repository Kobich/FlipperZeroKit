package dev.flipper.internal.rpc

import android.annotation.SuppressLint
import android.content.Context
import dev.flipper.api.model.PendingFileCleanup
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PendingCleanupStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("flipper_partial_uploads", Context.MODE_PRIVATE)
    fun load(): List<PendingFileCleanup> = prefs.getStringSet("paths", emptySet()).orEmpty().mapNotNull { item ->
        val parts = item.split('|', limit = 2)
        val path = parts.getOrNull(1)
        if (parts.size != 2 || path == null || !path.startsWith('/') || path.endsWith('/') ||
            path.split('/').any { it == "." || it == ".." } || path.any { it.code !in 32..126 }) null
        else PendingFileCleanup(parts[0], path)
    }
    // Need commit result before allowing any bytes to be written.
    @SuppressLint("UseKtx")
    suspend fun save(items: List<PendingFileCleanup>) = withContext(Dispatchers.IO) {
        if (!prefs.edit().putStringSet("paths", items.map { it.deviceAddress+"|"+it.path }.toSet()).commit())
            throw IOException("Could not persist partial-file cleanup")
    }
}
