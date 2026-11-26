package dev.flipper.ui.internal.pages.device

import androidx.core.net.toUri
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.flipper.R
import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.BleDevice
import androidx.compose.material3.TextButton
import dev.flipper.api.model.BleFailure
import dev.flipper.api.FlipperPermissions

private enum class EntryAction { KNOWN_DEVICES, SCAN, CONNECT }

@Composable
internal fun rememberCopyDeviceDiagnostics(viewModel: DeviceViewModel): () -> Unit {
    val context = LocalContext.current
    return remember(context, viewModel) {
        {
            @Suppress("DEPRECATION")
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            val report = buildString {
                appendLine("FlipperZeroKit $version / Android ${android.os.Build.VERSION.RELEASE}")
                viewModel.connectionDiagnostics().forEach { appendLine(it) }
            }
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Flipper diagnostics", report))
        }
    }
}

@Composable
internal fun DeviceRoute(
    viewModel: DeviceViewModel,
    state: DeviceUiState,
    connectingDevice: BleDevice?,
    onCopyDiagnostics: () -> Unit,
    onShowAllInfo: (String) -> Unit,
) {
    val context = LocalContext.current
    var pendingAction by rememberSaveable { mutableStateOf(EntryAction.KNOWN_DEVICES) }
    var targetAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var targetName by rememberSaveable { mutableStateOf("") }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var enableDenied by rememberSaveable { mutableStateOf(false) }
    val dispatchAction: (EntryAction) -> Unit = { action ->
        when (action) {
            EntryAction.SCAN -> viewModel.onIntent(DeviceIntent.StartScan)
            EntryAction.KNOWN_DEVICES -> viewModel.onIntent(DeviceIntent.RefreshKnownDevices)
            EntryAction.CONNECT -> targetAddress?.let { viewModel.onIntent(DeviceIntent.SelectDevice(BleDevice(targetName, it))) }
        }
    }
    val permissionsFor: (EntryAction) -> Array<String> = { action ->
        if (action == EntryAction.SCAN) FlipperPermissions.requiredPermissions else FlipperPermissions.connectPermissions
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            enableDenied = false
            dispatchAction(pendingAction)
        } else enableDenied = true
    }
    val beginAction: (EntryAction) -> Unit = { action ->
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter != null && !adapter.isEnabled) enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else dispatchAction(action)
        } catch (_: SecurityException) { permissionDenied = true }
    }
    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { outcome ->
        val granted = permissionsFor(pendingAction).all {
            outcome[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        permissionDenied = !granted
        if (granted) beginAction(pendingAction)
    }
    val requestAction: (EntryAction) -> Unit = { action ->
        pendingAction = action
        val permissions = permissionsFor(action)
        val granted = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) { permissionDenied = false; beginAction(action) }
        else requestPermissions.launch(permissions)
    }
    DeviceScreen(
        state = state,
        connectingDevice = connectingDevice,
        permissionDenied = permissionDenied,
        enableDenied = enableDenied,
        onScan = { requestAction(EntryAction.SCAN) },
        onKnownDevices = { requestAction(EntryAction.KNOWN_DEVICES) },
        onStopScan = { viewModel.onIntent(DeviceIntent.StopScan) },
        onConnect = { targetAddress = it.address; targetName = it.name; requestAction(EntryAction.CONNECT) },
        onRetryAccess = { requestAction(pendingAction) },
        onSettings = { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:"+context.packageName).toUri())) },
        onDisconnect = { viewModel.onIntent(DeviceIntent.Disconnect) },
        onRefresh = { viewModel.onIntent(DeviceIntent.RefreshInfo) },
        sendPing = { viewModel.onIntent(DeviceIntent.Ping) },
        onCopyDiagnostics = onCopyDiagnostics,
        onShowAllInfo = onShowAllInfo,
    )
}

@Composable
private fun DeviceScreen(
    state: DeviceUiState,
    connectingDevice: BleDevice?,
    permissionDenied: Boolean,
    enableDenied: Boolean,
    onScan: () -> Unit,
    onKnownDevices: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onRetryAccess: () -> Unit,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    sendPing: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onShowAllInfo: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val connection = state.connection
        val pendingDevice = when (connection) {
            is BleConnectionState.Pairing -> connection.device
            is BleConnectionState.Connecting -> connection.device
            is BleConnectionState.Connected -> connection.device.takeUnless { state.isRpcReady }
            else -> connectingDevice
        }
        when {
            connection is BleConnectionState.Connected && state.isRpcReady -> {
                DeviceOverview(
                    device = connection.device,
                    status = stringResource(R.string.flipper_connected_status),
                    details = state.details,
                    onShowAllInfo = { onShowAllInfo(connection.device.address) },
                ) {
                    DeviceActions(!state.details.isLoading, sendPing, onRefresh, onDisconnect)
                }
            }
            pendingDevice != null -> {
                val status = when (connection) {
                    is BleConnectionState.Pairing -> stringResource(R.string.flipper_device_pairing)
                    is BleConnectionState.Connected -> stringResource(R.string.flipper_rpc_waiting)
                    else -> stringResource(R.string.flipper_device_connecting)
                }
                DeviceOverview(pendingDevice, status) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onCopyDiagnostics) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.flipper_copy_diagnostics))
                        }
                        DisconnectButton(onDisconnect)
                    }
                }
            }
            else -> {
                if (connection is BleConnectionState.Failed) {
                    Text(
                        failureText(connection.failure),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (permissionDenied) {
                    Text(stringResource(R.string.flipper_permission_denied), color = MaterialTheme.colorScheme.error)
                }
                if (enableDenied) {
                    Text(stringResource(R.string.flipper_bluetooth_enable_denied), color = MaterialTheme.colorScheme.error)
                }
                if (permissionDenied || enableDenied) Row {
                    TextButton(onClick = onRetryAccess) { Text("Try again") }
                    if (permissionDenied) TextButton(onClick = onSettings) { Text("App settings") }
                }
                DevicePicker(state, onScan, onStopScan, onKnownDevices, onCopyDiagnostics, onConnect)
            }
        }
    }
}

@Composable
private fun DeviceActions(
    refreshEnabled: Boolean,
    onPing: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPing, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(stringResource(R.string.flipper_command_ping))
        }
        IconButton(onClick = onRefresh, enabled = refreshEnabled) {
            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.flipper_refresh_info))
        }
        DisconnectButton(onDisconnect)
    }
}

@Composable
private fun DisconnectButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Outlined.LinkOff, contentDescription = stringResource(R.string.flipper_disconnect_device),
            tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun failureText(failure: BleFailure): String = when (failure) {
    BleFailure.BluetoothUnsupported -> stringResource(R.string.flipper_bluetooth_not_supported)
    BleFailure.BluetoothDisabled -> stringResource(R.string.flipper_bluetooth_disabled)
    BleFailure.PermissionMissing -> stringResource(R.string.flipper_bluetooth_permission_missing)
    BleFailure.LocationServicesDisabled -> stringResource(R.string.flipper_permission_location_off)
    BleFailure.PairingFailed -> stringResource(R.string.flipper_bluetooth_pairing_failed)
    BleFailure.TimedOut -> stringResource(R.string.flipper_bluetooth_timeout)
    is BleFailure.ScanFailed -> stringResource(R.string.flipper_bluetooth_scan_failed, failure.code)
    is BleFailure.ConnectionFailed -> stringResource(R.string.flipper_bluetooth_connect_failed, failure.code)
    is BleFailure.ServiceDiscoveryFailed -> stringResource(R.string.flipper_bluetooth_services_failed, failure.code)
    is BleFailure.Unknown -> stringResource(R.string.flipper_bluetooth_unknown_error)
    BleFailure.InvalidGattProfile -> stringResource(R.string.flipper_bluetooth_services_validation_failed)
    BleFailure.RPCSessionInactive -> stringResource(R.string.flipper_rpc_unavailable)
    BleFailure.RPCError,
    BleFailure.EnableCCCDProblem,
    is BleFailure.RPCReadError -> stringResource(R.string.flipper_rpc_error)
}
