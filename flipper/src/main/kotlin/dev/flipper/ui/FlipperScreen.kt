package dev.flipper.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.flipper.R
import dev.flipper.api.Flipper
import dev.flipper.ui.internal.connection.ConnectionViewModel
import dev.flipper.ui.internal.pages.device.DeviceDetailsStore
import dev.flipper.ui.internal.pages.device.DeviceRoute
import dev.flipper.ui.internal.pages.device.DeviceViewModel
import dev.flipper.ui.internal.pages.files.FileRow
import dev.flipper.ui.internal.pages.files.FilesControls
import dev.flipper.ui.internal.pages.files.FilesViewModel
import dev.flipper.ui.internal.pages.files.rememberFilesActions

/** One host destination with a single scrollable screen of device, transfer and file blocks.
 * The host owns the Flipper client and its lifetime; active file jobs belong to the foreground service.
 */
@Composable
fun FlipperScreen(flipper: Flipper, modifier: Modifier = Modifier, connectionController: FlipperConnectionController? = null) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val controller = if (connectionController != null) connectionController else {
        val factory = remember(flipper, application) { viewModelFactory { initializer { ConnectionViewModel(flipper, application) } } }
        viewModel<ConnectionViewModel>(key = "flipper.connection", factory = factory)
    }
    val deviceFactory = remember(flipper, controller, application) {
        viewModelFactory { initializer { DeviceViewModel(flipper, controller, DeviceDetailsStore(application)) } }
    }
    val filesFactory = remember(flipper, application) { viewModelFactory { initializer { FilesViewModel(flipper, application) } } }
    val device: DeviceViewModel = viewModel(key = "flipper.device", factory = deviceFactory)
    val files: FilesViewModel = viewModel(key = "flipper.files", factory = filesFactory)
    val state by files.state.collectAsStateWithLifecycle()
    val cleanup by flipper.pendingFileCleanup.collectAsStateWithLifecycle()
    val recovering by flipper.isRecovering.collectAsStateWithLifecycle()
    val deferredCleanup = if (recovering || state.transfer?.active == true) 0 else cleanup.size
    val status = controller.status?.collectAsStateWithLifecycle()?.value
    val connectingDevice = (controller as? ConnectionViewModel)?.connectingDevice?.collectAsStateWithLifecycle()?.value
    val actions = rememberFilesActions(files, state)
    val activity = remember(context) { context.findActivity() }
    LifecycleStartEffect(controller) {
        controller.onEnter()
        onStopOrDispose { controller.onLeave(activity?.isChangingConfigurations == true) }
    }
    LazyColumn(modifier.fillMaxSize()) {
        if (!status.isNullOrBlank()) item("connection-status") { Text(status, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        item("device") { DeviceRoute(device, embedded = true, connectingDevice = connectingDevice) }
        item("file-controls") { FilesControls(state, files, actions, deferredCleanup) }
        items(state.files, key = { state.path+"/"+it.name }) { file ->
            FileRow(file, state.rpcReady && state.transfer?.active != true) {
                if (file.isDirectory) files.open(state.path.trimEnd('/')+"/"+file.name) else actions.download(file)
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
