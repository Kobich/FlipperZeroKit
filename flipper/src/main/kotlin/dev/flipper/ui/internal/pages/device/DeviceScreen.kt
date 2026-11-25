package dev.flipper.ui.internal.pages.device

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flipper.R
import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.BleDevice
import androidx.compose.material3.TextButton
import dev.flipper.api.model.BleFailure
import dev.flipper.ui.internal.components.FlipperOutlinedButton
import dev.flipper.ui.internal.components.FlipperPrimaryButton
import dev.flipper.ui.internal.components.SectionTitle
import dev.flipper.api.FlipperPermissions

private enum class EntryAction { KNOWN_DEVICES, SCAN }

@Composable
internal fun DeviceRoute(viewModel: DeviceViewModel, embedded: Boolean = false, connectingDevice: BleDevice? = null) {

    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

    var pendingAction by rememberSaveable { mutableStateOf(EntryAction.KNOWN_DEVICES) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var enableDenied by rememberSaveable { mutableStateOf(false) }
    val dispatchAction: (EntryAction) -> Unit = { action ->
        viewModel.onIntent(if (action == EntryAction.SCAN) DeviceIntent.StartScan else DeviceIntent.RefreshKnownDevices)
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
    // Read-only refresh on entry/return, without starting a scan or interrupting an existing session.
    // Permission prompts and Bluetooth enabling remain explicit user actions.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onIntent(DeviceIntent.RefreshKnownDevices) }

    DeviceScreen(
        state = uiState,
        embedded = embedded,
        connectingDevice = connectingDevice,
        permissionDenied = permissionDenied,
        enableDenied = enableDenied,
        onScan = { requestAction(EntryAction.SCAN) },
        onKnownDevices = { requestAction(EntryAction.KNOWN_DEVICES) },
        onStopScan = { viewModel.onIntent(DeviceIntent.StopScan) },
        onConnect = { viewModel.onIntent(DeviceIntent.SelectDevice(it)) },
        onDisconnect = { viewModel.onIntent(DeviceIntent.Disconnect) },
        onRefresh = { viewModel.onIntent(DeviceIntent.RefreshInfo) },
        sendPing = { viewModel.onIntent(DeviceIntent.Ping) },
        onCopyDiagnostics = {
            val report = buildString {
                appendLine("FlipperZeroKit $version / Android ${android.os.Build.VERSION.RELEASE}")
                viewModel.connectionDiagnostics().forEach { appendLine(it) }
                appendLine("Known devices: "+uiState.knownDevices)
            }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Flipper diagnostics", report))
        }
    )
}

@Composable
private fun DeviceScreen(
    state: DeviceUiState,
    embedded: Boolean,
    connectingDevice: BleDevice?,
    permissionDenied: Boolean,
    enableDenied: Boolean,
    onScan: () -> Unit,
    onKnownDevices: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    sendPing: () -> Unit,
    onCopyDiagnostics: () -> Unit,
) {
    Column(
        modifier = (if (embedded) Modifier.fillMaxWidth()
            else Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!embedded) Text(stringResource(R.string.flipper_connection_title), style = MaterialTheme.typography.titleLarge)
        if (!state.isRpcReady) TextButton(onClick = onCopyDiagnostics) { Text(stringResource(R.string.flipper_copy_diagnostics)) }
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
                    connection.device, stringResource(R.string.flipper_connected_status), state.details, onCopyDiagnostics,
                ) { powerExpanded, togglePower ->
                    DeviceActions(powerExpanded, !state.details.isLoading, sendPing, togglePower, onRefresh, onDisconnect)
                }
                state.details.pingMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            pendingDevice != null -> {
                val status = when (connection) {
                    is BleConnectionState.Pairing -> stringResource(R.string.flipper_device_pairing)
                    is BleConnectionState.Connected -> stringResource(R.string.flipper_rpc_waiting)
                    else -> stringResource(R.string.flipper_device_connecting)
                }
                DeviceOverview(pendingDevice, status)
                DisconnectButton(onDisconnect, Modifier.align(Alignment.End))
            }
            else -> {
                if (connection is BleConnectionState.Failed) {
                    Text(
                        failureText(connection.failure),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    stringResource(R.string.flipper_connection_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (permissionDenied) {
                    Text(stringResource(R.string.flipper_permission_denied), color = MaterialTheme.colorScheme.error)
                }
                if (enableDenied) {
                    Text(stringResource(R.string.flipper_bluetooth_enable_denied), color = MaterialTheme.colorScheme.error)
                }
                SectionTitle("Known devices")
                Text("Select a paired Flipper without scanning. The Android connection badge shows Bluetooth status.",
                    style = MaterialTheme.typography.bodySmall)
                FlipperOutlinedButton("Refresh known devices", onKnownDevices,
                    enabled = !state.knownDevices.isLoading)
                if (state.knownDevices.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.knownDevices.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
                if (state.knownDevices.hasLoaded && !state.knownDevices.isLoading &&
                    state.knownDevices.failure == null && state.knownDevices.devices.isEmpty()) Text("No paired or connected Bluetooth devices")
                state.knownDevices.devices.forEach { known ->
                    Card(onClick = { onConnect(known.device) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(known.device.name, style = MaterialTheme.typography.titleSmall)
                            Text(known.device.address, style = MaterialTheme.typography.bodySmall)
                            if (known.isConnectedToSystem) Text("Connected in Android", color = MaterialTheme.colorScheme.primary)
                            Text(if (known.isBonded) "Paired · tap to connect" else "Pairing may require confirmation")
                        }
                    }
                }
                SectionTitle("Find new devices")
                if (state.isScanning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.flipper_scanning))
                    FlipperOutlinedButton(stringResource(R.string.flipper_stop_scan), onStopScan)
                } else {
                    FlipperPrimaryButton(
                        label = stringResource(
                            if (state.devices.isEmpty()) R.string.flipper_connect_device else R.string.flipper_scan_again,
                        ),
                        onClick = onScan,
                    )
                }
                if (state.isScanning || state.devices.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.flipper_available_devices))
                    if (state.devices.isEmpty()) Text(stringResource(R.string.flipper_no_devices_yet))
                    state.devices.forEach { device ->
                        Card(onClick = { onConnect(device) }, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(Icons.Outlined.Bluetooth, contentDescription = null)
                                Column {
                                    Text(device.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        device.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceActions(
    powerExpanded: Boolean,
    refreshEnabled: Boolean,
    onPing: () -> Unit,
    onTogglePower: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            val padding = PaddingValues(horizontal = 8.dp)
            TextButton(onClick = onPing, contentPadding = padding) {
                Text(stringResource(R.string.flipper_command_ping))
            }
            TextButton(
                onClick = onTogglePower,
                contentPadding = padding,
                modifier = Modifier.semantics { selected = powerExpanded },
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (powerExpanded) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ),
            ) {
                Text(stringResource(R.string.flipper_power_details_label))
            }
        }
        IconButton(onClick = onRefresh, enabled = refreshEnabled) {
            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.flipper_refresh_info))
        }
        DisconnectButton(onDisconnect)
    }
}

@Composable
private fun DisconnectButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Outlined.LinkOff, contentDescription = stringResource(R.string.flipper_disconnect_device),
            tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun failureText(failure: BleFailure): String = when (failure) {
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
