package dev.flipper.zerokit.sample.presentation.pages.files

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun FilesRoute(viewModel: FilesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var downloadPath by rememberSaveable { mutableStateOf<String?>(null) }
    var downloadDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var uploadName by rememberSaveable(state.upload?.uri?.toString()) {
        mutableStateOf(state.upload?.originalName?.map { if (it.code in 32..126 && it != '/' && it.code != 92) it else '_' }?.joinToString("") ?: "upload.bin")
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val selected = downloadPath
        if (uri != null && selected != null) viewModel.download(selected, uri, downloadDevice)
        downloadPath = null
        downloadDevice = null
    }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectUpload(uri)
    }
    val busy = state.transfer?.active == true
    val enabled = state.rpcReady && !busy
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Файлы Flipper · пробная версия", style = MaterialTheme.typography.titleLarge)
            Text(if (state.rpcReady) "RPC готов. Нажми каталог, чтобы открыть; файл — чтобы скачать."
                else "Сначала подключись к Flipper на вкладке устройства и дождись RPC.")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("/", "/ext", "/int").forEach { path ->
                    OutlinedButton(onClick = { viewModel.open(path) }, enabled = enabled) { Text(path) }
                }
            }
            Text(state.path, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewModel.open(state.path.substringBeforeLast('/').ifEmpty { "/" }) },
                    enabled = enabled && state.path != "/") { Text("Вверх") }
                TextButton(onClick = viewModel::refresh, enabled = enabled && !state.isLoading) { Text("Обновить") }
            }
        }
        state.transfer?.let { transfer ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(transfer.title, style = MaterialTheme.typography.titleSmall)
                        Text(transfer.bytes.toString()+" / "+(transfer.total?.toString() ?: "?")+" байт")
                        if (transfer.active) {
                            val total = transfer.total
                            if (total != null && total > 0) {
                                val progress = (transfer.bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                Text((progress * 100).toInt().toString()+"%")
                            } else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Text(transfer.message)
                        if (transfer.active) TextButton(onClick = viewModel::cancelTransfer) { Text("Отменить передачу") }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { choose.launch(arrayOf("*/*")) }, enabled = enabled && state.path != "/") {
                Text("Выбрать файл телефона")
            }
            state.upload?.let { selected ->
                Text("Источник: "+selected.originalName+" ("+(selected.sizeBytes?.toString() ?: "?")+" байт)")
                OutlinedTextField(value = uploadName, onValueChange = { uploadName = it },
                    enabled = enabled, label = { Text("Имя на Flipper (ASCII)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("Назначение: "+state.path.trimEnd('/')+"/"+uploadName)
                Text("Существующее имя не перезаписывается. После сбоя частичный файл может остаться.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = { viewModel.upload(uploadName) }, enabled = enabled && state.path != "/") {
                    Text("Отправить на Flipper")
                }
            }
        }
        if (state.transfer != null || state.error != null) item {
            TextButton(onClick = {
                val text = "FlipperZeroKit prototype\nDevice: "+state.deviceAddress+"\nPath: "+state.path+
                    "\nRPC ready: "+state.rpcReady+"\nError: "+state.error+"\nTransfer: "+state.transfer
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("File test result", text))
            }) { Text("Скопировать результат") }
        }
        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        if (state.isLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Чтение каталога…") }
        if (state.rpcReady && !state.isLoading && state.files.isEmpty() && state.error == null)
            item { Text("Каталог пуст") }
        items(state.files, key = { it.name }) { file ->
            Card(onClick = {
                if (enabled) {
                    val path = state.path.trimEnd('/') + "/" + file.name
                    if (file.isDirectory) viewModel.open(path)
                    else { downloadPath = path; downloadDevice = state.deviceAddress; save.launch(file.name) }
                }
            }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text((if (file.isDirectory) "Каталог: " else "Файл: ")+file.name, style = MaterialTheme.typography.titleSmall)
                    if (!file.isDirectory) Text(file.sizeBytes.toString()+" байт · нажми для скачивания")
                }
            }
        }
    }
}
