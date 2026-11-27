package dev.flipper.ui.internal.pages.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.text.format.DateUtils
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.flipper.R

@Composable
internal fun TransferHistoryPage(entries: List<TransferHistoryEntry>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.flipper_back_to_overview))
            }
            Text(stringResource(R.string.flipper_files_history), style = MaterialTheme.typography.titleLarge)
        }
        if (entries.isEmpty()) {
            Text(stringResource(R.string.flipper_files_history_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(entries, key = { it.id }) { entry ->
                    TransferHistoryRow(entry)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun TransferHistoryRow(entry: TransferHistoryEntry) {
    val context = LocalContext.current
    val status = stringResource(when (entry.outcome) {
        TransferOutcome.SUCCEEDED -> R.string.flipper_files_history_completed
        TransferOutcome.FAILED -> R.string.flipper_files_history_failed
        TransferOutcome.CANCELED -> R.string.flipper_files_history_canceled
    })
    val date = DateUtils.formatDateTime(context, entry.completedAtMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall)
            Text("$status · $date", style = MaterialTheme.typography.labelMedium,
                color = if (entry.outcome == TransferOutcome.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(entry.message, style = MaterialTheme.typography.bodySmall)
        }
        if (entry.outcome == TransferOutcome.SUCCEEDED && entry.outputUri != null) {
            IconButton(onClick = {
                val extension = entry.outputName?.substringAfterLast('.', "")?.lowercase().orEmpty()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(entry.outputUri.toUri(), mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.flipper_files_history_no_viewer, Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    Toast.makeText(context, R.string.flipper_files_history_no_access, Toast.LENGTH_LONG).show()
                }
            }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.flipper_files_history_open_saved))
            }
        }
        CopyResultButton {
            val report = buildString {
                appendLine(entry.title)
                appendLine("$status · $date")
                append(entry.message)
                entry.diagnostic?.let {
                    append("\n\n")
                    append(it)
                }
            }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(entry.title, report))
        }
    }
}
