package dev.flipper.ui.internal.pages.files

import android.content.Intent
import android.content.ActivityNotFoundException
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.material.icons.outlined.OpenInNew
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.flipper.R
import dev.flipper.api.model.StorageEntry
import dev.flipper.ui.internal.components.rememberFileSizeContext

internal data class FilesActions(val choose: () -> Unit, val download: (StorageEntry) -> Unit,
    val upload: () -> Unit, val name: String, val changeName: (String) -> Unit)

@Composable
internal fun rememberFilesActions(viewModel: FilesViewModel, state: FilesUiState): FilesActions {
    val context = LocalContext.current
    var downloadPath by rememberSaveable { mutableStateOf<String?>(null) }
    var downloadDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var downloadUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUpload by rememberSaveable { mutableStateOf(false) }
    var uploadTarget by rememberSaveable { mutableStateOf("") }
    var uploadDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var uploadName by rememberSaveable(state.upload?.uri?.toString()) {
        mutableStateOf(state.upload?.originalName?.map { if (it.code in 32..126 && it != '/' && it.code != 92) it else '_' }?.joinToString("") ?: "upload.bin")
    }
    val startPending: () -> Unit = {
        if (pendingUpload) viewModel.upload(uploadTarget, uploadDevice)
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
        if (uri != null && downloadPath != null) { downloadUri = uri.toString(); pendingUpload = false
            viewModel.prepareDownload(uri) { requestNotificationAndStart() } }
        else { downloadPath = null; downloadDevice = null }
    }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.selectUpload(uri) }
    return FilesActions(choose = { choose.launch(arrayOf("*/*")) }, download = { file ->
        downloadPath = state.path.trimEnd('/')+"/"+file.name
        downloadDevice = state.deviceAddress
        save.launch(file.name)
    }, upload = {
        uploadTarget = state.path.trimEnd('/')+"/"+uploadName
        uploadDevice = state.deviceAddress
        pendingUpload = true; requestNotificationAndStart()
    }, name = uploadName, changeName = { uploadName = it })
}

@Composable
internal fun FilesControls(state: FilesUiState, viewModel: FilesViewModel, actions: FilesActions, deferredCleanup: Int) {
    val context = LocalContext.current
    val enabled = state.rpcReady && !state.isPreparingDocument && !state.transferBusy
    val copyResult = {
        val report = "Device: ${state.deviceAddress}\nPath: ${state.path}\nError: ${state.error}\nTransfer: ${state.transfer}"
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Flipper file result", report))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.rpcReady) {
            FilesToolbar(state.path, enabled, state.isLoading, viewModel::open, viewModel::refresh, actions.choose)
        } else {
            Text(stringResource(R.string.flipper_files_connect), style = MaterialTheme.typography.bodySmall)
        }
        state.transfer?.takeIf { it.active }?.let { TransferSummary(it, viewModel::cancelTransfer, copyResult) }

        state.error?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (state.transfer?.active != true) CopyResultButton(copyResult)
            }
        }
        if (deferredCleanup > 0) {
            Text("Files pending cleanup: $deferredCleanup. Cleanup will resume when Flipper is available.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.cleanupStatus.isNotBlank()) {
            Text(state.cleanupStatus, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::retryCleanup, enabled = !state.transferBusy) { Text("Retry cleanup") }
        }
        if (state.transferBusy && state.transfer?.active == false) Text("Finishing operation…", style = MaterialTheme.typography.bodySmall)
        if (state.isPreparingDocument) Text("Preparing document…", style = MaterialTheme.typography.bodySmall)
        if (state.isLoading || state.isPreparingDocument) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.rpcReady && state.hasLoaded && !state.isLoading && state.files.isEmpty() && state.error == null) {
            Text(stringResource(R.string.flipper_files_empty), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (state.showUploadDialog && state.upload != null) {
        UploadDialog(state.upload, state.path, actions, enabled, onDismiss = {
            viewModel.clearUpload()
        }, onSend = { viewModel.hideUploadDialog(); actions.upload() })
    }
}

@Composable
private fun FilesToolbar(path: String, enabled: Boolean, loading: Boolean, onOpen: (String) -> Unit,
    onRefresh: () -> Unit, onChoose: () -> Unit) {
    var showLocations by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onOpen(path.substringBeforeLast('/').ifEmpty { "/" }) }, enabled = enabled && path != "/") {
            Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.flipper_files_up))
        }
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { showLocations = true }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(path, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = stringResource(R.string.flipper_files_locations))
            }
            DropdownMenu(expanded = showLocations && enabled, onDismissRequest = { showLocations = false }) {
                listOf(
                    "/" to R.string.flipper_files_root,
                    "/ext" to R.string.flipper_files_sd_card,
                    "/int" to R.string.flipper_files_internal,
                ).forEach { (location, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                        showLocations = false
                        onOpen(location)
                    })
                }
            }
        }
        IconButton(onClick = onRefresh, enabled = enabled && !loading) {
            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.flipper_files_refresh))
        }
        IconButton(onClick = onChoose, enabled = enabled && path != "/") {
            Icon(Icons.Outlined.UploadFile, contentDescription = stringResource(R.string.flipper_files_choose))
        }
    }
}

@Composable
private fun UploadDialog(selected: SelectedUpload, path: String, actions: FilesActions, enabled: Boolean,
    onDismiss: () -> Unit, onSend: () -> Unit) {
    val validName = actions.name.isNotBlank() && actions.name != "." && actions.name != ".." &&
        actions.name.length <= 255 && actions.name.all { it.code in 32..126 && it != '/' && it.code != 92 }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flipper_files_upload)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(selected.originalName)
                selected.sizeBytes?.let { Text(Formatter.formatShortFileSize(rememberFileSizeContext(), it)) }
                Text(stringResource(R.string.flipper_files_destination, path))
                OutlinedTextField(actions.name, actions.changeName, enabled = enabled, singleLine = true,
                    label = { Text(stringResource(R.string.flipper_files_upload_name)) },
                    isError = !validName, modifier = Modifier.fillMaxWidth())
                if (!validName) Text(stringResource(R.string.flipper_files_name_hint), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.flipper_files_collision_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onSend, enabled = enabled && path != "/" && validName) {
            Text(stringResource(R.string.flipper_files_upload))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.flipper_files_close)) } })
}

@Composable
private fun TransferSummary(transfer: TransferUi, onCancel: () -> Unit, onCopyResult: () -> Unit) {
    val context = LocalContext.current
    val total = transfer.total
    val progress = total?.takeIf { it > 0 }?.let { (transfer.bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(transfer.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                if (transfer.active) {
                    IconButton(onClick = onCancel, enabled = !transfer.isCleaningUp) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.flipper_files_cancel_transfer))
                    }
                } else {
                    if (transfer.successful && transfer.outputUri != null) IconButton(onClick = {
                        val extension = transfer.outputName?.substringAfterLast('.', "")?.lowercase().orEmpty()
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                        try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(transfer.outputUri.toUri(), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        catch (_: ActivityNotFoundException) { Toast.makeText(context, "No app can open this file. Use Files.", Toast.LENGTH_LONG).show() }
                        catch (_: SecurityException) { Toast.makeText(context, "Access expired. Open this file in Files.", Toast.LENGTH_LONG).show() }
                    }) { Icon(Icons.Outlined.OpenInNew, contentDescription = "Open saved file") }
                    CopyResultButton(onCopyResult)
                }
            }
            if (transfer.active) {
                if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
                else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                val bytes = "${transfer.bytes} / ${total ?: "?"} bytes"
                Text(if (progress == null) bytes else "$bytes · ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            Text(transfer.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CopyResultButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.flipper_files_copy_result))
    }
}

@Composable
internal fun FileRow(file: StorageEntry, enabled: Boolean, onClick: () -> Unit) {
    val actionLabel = stringResource(if (file.isDirectory) R.string.flipper_files_open_folder else R.string.flipper_files_download)
    val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (file.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null,
                modifier = Modifier.size(20.dp), tint = secondaryColor)
            Text(file.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis, color = contentColor)
            if (!file.isDirectory) {
                Text(Formatter.formatShortFileSize(rememberFileSizeContext(), file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall, color = secondaryColor)
            }
            Icon(if (file.isDirectory) Icons.Outlined.ChevronRight else Icons.Outlined.FileDownload, contentDescription = null,
                modifier = Modifier.size(18.dp), tint = secondaryColor)
        }
        HorizontalDivider(Modifier.padding(start = 48.dp, end = 16.dp))
    }
}

@Composable
internal fun FilesPage(state: FilesUiState, viewModel: FilesViewModel, actions: FilesActions,
    deferredCleanup: Int, listState: LazyListState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.flipper_back_to_overview))
            }
            Text(stringResource(R.string.flipper_files_title), style = MaterialTheme.typography.titleLarge)
        }
        FilesControls(state, viewModel, actions, deferredCleanup)
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(state.files, key = { state.path + "/" + it.name }) { file ->
                FileRow(file, state.rpcReady && !state.isPreparingDocument && state.transfer?.active != true) {
                    if (file.isDirectory) viewModel.open(state.path.trimEnd('/') + "/" + file.name) else actions.download(file)
                }
            }
        }
    }
}

@Composable
internal fun FilesOverview(state: FilesUiState, viewModel: FilesViewModel, deferredCleanup: Int, onOpen: () -> Unit) {
    var showTransfer by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Folder, contentDescription = null)
                Text(stringResource(R.string.flipper_files_open), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            }
        }
        state.transfer?.let { transfer ->
            Card(onClick = { showTransfer = true }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(transfer.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    if (transfer.active) {
                        val progress = transfer.total?.takeIf { it > 0 }?.let { (transfer.bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
                        if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                    Text(transfer.message, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (deferredCleanup > 0) Text("Files pending cleanup: $deferredCleanup", style = MaterialTheme.typography.bodySmall)
        if (state.cleanupStatus.isNotBlank()) Text(state.cleanupStatus, style = MaterialTheme.typography.bodySmall)
    }
    val transfer = state.transfer
    if (showTransfer && transfer != null) {
        val context = LocalContext.current
        AlertDialog(onDismissRequest = { showTransfer = false },
            title = { Text(stringResource(R.string.flipper_files_transfer_details)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                TransferSummary(transfer, viewModel::cancelTransfer) {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("Flipper transfer", transfer.toString()))
                }
            } },
            confirmButton = { TextButton(onClick = { showTransfer = false }) { Text(stringResource(R.string.flipper_files_close)) } })
    }
}
