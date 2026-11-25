package dev.flipper.ui.internal.pages.files

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.flipper.api.model.StorageEntry

internal data class FilesActions(val choose: () -> Unit, val download: (StorageEntry) -> Unit,
    val upload: () -> Unit, val name: String, val changeName: (String) -> Unit)

@Composable
internal fun rememberFilesActions(viewModel: FilesViewModel, state: FilesUiState): FilesActions {
    val context = LocalContext.current
    var downloadPath by rememberSaveable { mutableStateOf<String?>(null) }
    var downloadDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var downloadUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUpload by rememberSaveable { mutableStateOf(false) }
    var uploadName by rememberSaveable(state.upload?.uri?.toString()) {
        mutableStateOf(state.upload?.originalName?.map { if (it.code in 32..126 && it != '/' && it.code != 92) it else '_' }?.joinToString("") ?: "upload.bin")
    }
    val startPending: () -> Unit = {
        if (pendingUpload) viewModel.upload(uploadName)
        else if (downloadUri != null && downloadPath != null)
            viewModel.download(checkNotNull(downloadPath), checkNotNull(downloadUri).toUri(), downloadDevice)
        pendingUpload = false; downloadUri = null; downloadPath = null; downloadDevice = null
    }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startPending() }
    val requestNotificationAndStart: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startPending()
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && downloadPath != null) { downloadUri = uri.toString(); pendingUpload = false; requestNotificationAndStart() }
        else { downloadPath = null; downloadDevice = null }
    }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.selectUpload(uri) }
    return FilesActions(choose = { choose.launch(arrayOf("*/*")) }, download = { file ->
        downloadPath = state.path.trimEnd('/')+"/"+file.name
        downloadDevice = state.deviceAddress
        save.launch(file.name)
    }, upload = { pendingUpload = true; requestNotificationAndStart() }, name = uploadName, changeName = { uploadName = it })
}

@Composable
internal fun FilesControls(state: FilesUiState, viewModel: FilesViewModel, actions: FilesActions, deferredCleanup: Int) {
    val context = LocalContext.current
    val enabled = state.rpcReady && state.transfer?.active != true
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Files", style = MaterialTheme.typography.titleMedium)
        if (!state.rpcReady) Text("Connect your Flipper to browse files.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("/", "/ext", "/int").forEach { path -> OutlinedButton(onClick = { viewModel.open(path) }, enabled = enabled) { Text(path) } }
        }
        Text(state.path, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { viewModel.open(state.path.substringBeforeLast('/').ifEmpty { "/" }) }, enabled = enabled && state.path != "/") { Text("Up") }
            TextButton(onClick = viewModel::refresh, enabled = enabled && !state.isLoading) { Text("Refresh") }
        }
        state.transfer?.let { transfer -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(transfer.title, style = MaterialTheme.typography.titleSmall)
                Text(transfer.bytes.toString()+" / "+(transfer.total?.toString() ?: "?")+" bytes")
                if (transfer.active) {
                    val total = transfer.total
                    if (total != null && total > 0) {
                        val progress = (transfer.bytes.toDouble()/total).toFloat().coerceIn(0f,1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text((progress*100).toInt().toString()+"%")
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = viewModel::cancelTransfer) { Text("Cancel transfer") }
                }
                Text(transfer.message)
            }
        } }
        OutlinedButton(onClick = actions.choose, enabled = enabled && state.path != "/") { Text("Choose a file") }
        state.upload?.let { selected ->
            Text("Source: "+selected.originalName+" ("+(selected.sizeBytes?.toString() ?: "?")+" bytes)")
            OutlinedTextField(actions.name, actions.changeName, enabled = enabled, singleLine = true,
                label = { Text("Filename on Flipper (ASCII)") }, modifier = Modifier.fillMaxWidth())
            Text("Folder: "+state.path+" · existing names get a (2), (3), or later suffix")
            Button(onClick = actions.upload, enabled = enabled && state.path != "/") { Text("Upload to Flipper") }
        }
        if (state.transfer != null || state.error != null) TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Flipper file result",
                "Device: "+state.deviceAddress+"\nPath: "+state.path+"\nError: "+state.error+"\nTransfer: "+state.transfer))
        }) { Text("Copy result") }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (deferredCleanup > 0) {
            Text("Files pending cleanup: $deferredCleanup. Cleanup will resume when Flipper is available.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.cleanupStatus.isNotBlank()) Text(state.cleanupStatus, style = MaterialTheme.typography.bodySmall)
        if (state.isLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Loading folder…") }
        if (state.rpcReady && !state.isLoading && state.files.isEmpty() && state.error == null) Text("Empty folder")
    }
}

@Composable
internal fun FileRow(file: StorageEntry, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text((if (file.isDirectory) "Folder: " else "File: ")+file.name, style = MaterialTheme.typography.titleSmall)
            if (!file.isDirectory) Text(file.sizeBytes.toString()+" bytes · download")
        }
    }
}
