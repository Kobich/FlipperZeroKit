package dev.flipper.ui

import dev.flipper.R

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.flipper.ui.internal.connection.ConnectionViewModel
import dev.flipper.api.Flipper
import dev.flipper.ui.internal.pages.device.DeviceRoute
import dev.flipper.ui.internal.pages.device.DeviceViewModel
import dev.flipper.ui.internal.pages.files.FilesRoute
import dev.flipper.ui.internal.pages.files.FilesViewModel

private enum class Section { DEVICE, FILES }

/** One destination in a host's navigation graph. Does not create an Activity, NavController,
 * application theme, top app bar or bottom navigation. Uses the destination's ViewModelStoreOwner.
 * Keep one stable Flipper/controller for the lifetime of that destination; the host closes Flipper.
 * By default the feature saves the last device and reconnects once on entry/foreground return.
 * A custom controller can replace that policy; it does not change ownership of the Flipper client.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlipperScreen(
    flipper: Flipper,
    modifier: Modifier = Modifier,
    connectionController: FlipperConnectionController? = null,
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val controller = if (connectionController != null) connectionController else {
        val connectionFactory = remember(flipper, applicationContext) {
            viewModelFactory { initializer { ConnectionViewModel(flipper, applicationContext as Application) } }
        }
        viewModel<ConnectionViewModel>(key = "flipper.connection", factory = connectionFactory)
    }
    val deviceFactory = remember(flipper, controller) {
        viewModelFactory { initializer { DeviceViewModel(flipper, controller) } }
    }
    val filesFactory = remember(flipper, applicationContext) {
        viewModelFactory { initializer { FilesViewModel(flipper, applicationContext.contentResolver) } }
    }
    // Both belong to the outer destination, so switching inner tabs does not destroy a transfer.
    val deviceViewModel: DeviceViewModel = viewModel(key = "flipper.device", factory = deviceFactory)
    val filesViewModel: FilesViewModel = viewModel(key = "flipper.files", factory = filesFactory)
    val files by filesViewModel.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(Section.DEVICE) }
    val savedSections = rememberSaveableStateHolder()
    val activity = remember(context) { context.findActivity() }

    LifecycleStartEffect(controller) {
        controller.onEnter()
        onStopOrDispose { controller.onLeave(activity?.isChangingConfigurations == true) }
    }
    BackHandler(enabled = section == Section.FILES) {
        val atStorageRoot = files.path in setOf("/", "/ext", "/int", "/any")
        if (!atStorageRoot && files.transfer?.active != true) {
            filesViewModel.open(files.path.substringBeforeLast('/').ifEmpty { "/" })
        } else section = Section.DEVICE
    }

    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = section.ordinal) {
            Section.entries.forEach { tab ->
                Tab(selected = section == tab, onClick = { section = tab }, text = {
                    Text(stringResource(if (tab == Section.DEVICE) R.string.flipper_tab_device else R.string.flipper_tab_files))
                })
            }
        }
        files.transfer?.takeIf { it.active }?.let { transfer ->
            TextButton(onClick = { section = Section.FILES }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(transfer.title+" · "+transfer.bytes+" / "+(transfer.total?.toString() ?: "?")+" байт",
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        savedSections.SaveableStateProvider(section) {
            when (section) {
                Section.DEVICE -> DeviceRoute(deviceViewModel)
                Section.FILES -> FilesRoute(filesViewModel)
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
