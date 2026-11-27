package dev.flipper.ui.internal.pages.files

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class TransferHistoryEntry(
    val id: String,
    val completedAtMillis: Long,
    val title: String,
    val outcome: TransferOutcome,
    val message: String,
    val outputUri: String?,
    val outputName: String?,
    val diagnostic: String?,
)

/** Local history shared by the transfer managers in the host process. */
internal class TransferHistory private constructor(app: Application) {
    private val prefs = app.getSharedPreferences("flipper_transfer_history", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(read())
    val entries = _entries.asStateFlow()

    fun record(transfer: TransferUi) {
        val outcome = transfer.outcome ?: return
        val entry = TransferHistoryEntry(
            id = UUID.randomUUID().toString(),
            completedAtMillis = System.currentTimeMillis(),
            title = transfer.title,
            outcome = outcome,
            message = transfer.message,
            outputUri = transfer.outputUri,
            outputName = transfer.outputName,
            diagnostic = transfer.diagnostic?.take(MAX_DIAGNOSTIC_LENGTH),
        )
        val updated = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        val encoded = JSONArray()
        for (item in updated) {
            encoded.put(JSONObject().apply {
                put("id", item.id)
                put("completedAt", item.completedAtMillis)
                put("title", item.title)
                put("outcome", item.outcome.name)
                put("message", item.message)
                put("outputUri", item.outputUri)
                put("outputName", item.outputName)
                put("diagnostic", item.diagnostic)
            })
        }
        prefs.edit { putString(ENTRIES, encoded.toString()) }
        _entries.value = updated
    }

    private fun read(): List<TransferHistoryEntry> {
        val saved = prefs.getString(ENTRIES, null) ?: return emptyList()
        val entries = try { JSONArray(saved) } catch (_: JSONException) { return emptyList() }
        return (0 until minOf(entries.length(), MAX_ENTRIES)).mapNotNull { index ->
            val item = entries.optJSONObject(index) ?: return@mapNotNull null
            try {
                TransferHistoryEntry(
                    id = item.getString("id"),
                    completedAtMillis = item.getLong("completedAt"),
                    title = item.getString("title"),
                    outcome = TransferOutcome.valueOf(item.getString("outcome")),
                    message = item.getString("message"),
                    outputUri = item.optString("outputUri").takeIf { it.isNotEmpty() },
                    outputName = item.optString("outputName").takeIf { it.isNotEmpty() },
                    diagnostic = item.optString("diagnostic").takeIf { it.isNotEmpty() },
                )
            } catch (_: JSONException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    companion object {
        private const val ENTRIES = "entries"
        private const val MAX_ENTRIES = 100
        private const val MAX_DIAGNOSTIC_LENGTH = 4096
        private var instance: TransferHistory? = null

        fun get(app: Application): TransferHistory = synchronized(this) {
            instance ?: TransferHistory(app).also { instance = it }
        }
    }
}
