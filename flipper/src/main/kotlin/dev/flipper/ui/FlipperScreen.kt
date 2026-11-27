package dev.flipper.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.Lifecycle
import dev.flipper.R
import dev.flipper.api.Flipper
import dev.flipper.api.model.BleConnectionState
import dev.flipper.ui.internal.connection.ConnectionViewModel
import dev.flipper.ui.internal.pages.device.DeviceDetailsPage
import dev.flipper.ui.internal.pages.device.DeviceDetailsStore
import dev.flipper.ui.internal.pages.device.DeviceIntent
import dev.flipper.ui.internal.pages.device.DeviceRoute
import dev.flipper.ui.internal.pages.device.DeviceViewModel
import dev.flipper.ui.internal.pages.device.rememberCopyDeviceDiagnostics
import dev.flipper.ui.internal.pages.files.FilesPage
import dev.flipper.ui.internal.pages.files.FilesOverview
import dev.flipper.ui.internal.pages.files.FilesViewModel
import dev.flipper.ui.internal.pages.files.TransferHistoryPage
import dev.flipper.ui.internal.pages.files.rememberFilesActions

/** One host destination with a scrollable overview and internal device details and file browser pages.
 * The host owns the Flipper client and its lifetime; active file jobs belong to the foreground service.
 */
@Composable
fun FlipperScreen(flipper: Flipper, modifier: Modifier = Modifier, connectionController: FlipperConnectionController? = null) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val filesFactory = remember(flipper, application) {
        viewModelFactory { initializer { FilesViewModel(flipper, application) } }
    }
    val files: FilesViewModel = viewModel(key = "flipper.files", factory = filesFactory)
    val controller = if (connectionController != null) connectionController else {
        val factory = remember(flipper, application, files) {
            viewModelFactory { initializer { ConnectionViewModel(flipper, application, files.transferBusy) } }
        }
        viewModel<ConnectionViewModel>(key = "flipper.connection", factory = factory)
    }
    val deviceFactory = remember(flipper, controller, application) {
        viewModelFactory { initializer { DeviceViewModel(flipper, controller, DeviceDetailsStore(application)) } }
    }
    val device: DeviceViewModel = viewModel(key = "flipper.device", factory = deviceFactory)
    val state by files.state.collectAsStateWithLifecycle()
    val deviceState by device.state.collectAsStateWithLifecycle()
    val cleanup by flipper.pendingFileCleanup.collectAsStateWithLifecycle()
    val recovering by flipper.isRecovering.collectAsStateWithLifecycle()
    val deferredCleanup = if (recovering || state.transfer?.active == true) 0 else cleanup.size
    val status = controller.status?.collectAsStateWithLifecycle()?.value
    val connectingDevice = (controller as? ConnectionViewModel)?.connectingDevice?.collectAsStateWithLifecycle()?.value
    var refreshedTransfer by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.transfer?.id, state.transfer?.active) {
        val transfer = state.transfer
        if (transfer?.successful == true && transfer.id != refreshedTransfer) {
            refreshedTransfer = transfer.id
            device.onIntent(DeviceIntent.RefreshInfo)
        }
    }
    val actions = rememberFilesActions(files, state)
    val copyDeviceDiagnostics = rememberCopyDeviceDiagnostics(device)
    val overviewListState = rememberLazyListState()
    val filesListState = rememberLazyListState()
    var showFiles by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val closeFiles = {
        if (state.isPreparingUpload || state.showUploadDialog) files.clearUpload()
        showFiles = false
    }
    var allInfoAddress by rememberSaveable { mutableStateOf<String?>(null) }
    val detailDevice = (deviceState.connection as? BleConnectionState.Connected)?.device?.takeIf {
        deviceState.isRpcReady && it.address == allInfoAddress
    }
    val activity = remember(context) { context.findActivity() }
    LifecycleStartEffect(controller) {
        controller.onEnter()
        onStopOrDispose { controller.onLeave(activity?.isChangingConfigurations == true) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { device.onIntent(DeviceIntent.RefreshKnownDevices) }
    BackHandler(enabled = detailDevice != null || showFiles || showHistory) {
        when {
            detailDevice != null -> allInfoAddress = null
            showFiles -> closeFiles()
            showHistory -> showHistory = false
        }
    }
    LaunchedEffect(allInfoAddress, detailDevice) {
        if (allInfoAddress != null && detailDevice == null) allInfoAddress = null
    }

    if (detailDevice != null) {
        DeviceDetailsPage(detailDevice, deviceState.details, onBack = { allInfoAddress = null },
            onCopyDiagnostics = copyDeviceDiagnostics, modifier = modifier)
    } else if (showFiles) {
        FilesPage(state, files, actions, deferredCleanup, filesListState, onBack = closeFiles, modifier = modifier)
    } else if (showHistory) {
        val history by files.history.collectAsStateWithLifecycle()
        TransferHistoryPage(history, onBack = { showHistory = false }, modifier = modifier)
    } else {
        LazyColumn(modifier.fillMaxSize(), state = overviewListState) {
            if (!status.isNullOrBlank()) item("connection-status") { Text(status, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            item("device") {
                DeviceRoute(device, deviceState, connectingDevice, copyDeviceDiagnostics,
                    disconnectEnabled = !(state.transferBusy && state.transfer?.userCancelable == false),
                    onShowAllInfo = { allInfoAddress = it })
            }
            item("files-overview") {
                FilesOverview(state, files, deferredCleanup, onOpen = { showFiles = true }, onOpenHistory = { showHistory = true })
            }
        }
    }
    if (recovering || state.transfer?.isCleaningUp == true) {
        RecoveryDialog(recovering)
    }
}

@Composable
private fun RecoveryDialog(reconnecting: Boolean) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(
                    text = stringResource(if (reconnecting) R.string.flipper_recovering_transfer else R.string.flipper_cleaning_up_transfer),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var current: android.content.Context = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return current as? android.app.Activity
}
