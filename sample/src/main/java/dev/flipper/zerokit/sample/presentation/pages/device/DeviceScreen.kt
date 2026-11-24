package dev.flipper.zerokit.sample.presentation.pages.device

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flipper.zerokit.sample.R
import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.BleDevice
import dev.flipper.api.model.FlipperRpcException
import androidx.compose.material3.TextButton
import dev.flipper.api.model.BleFailure
import dev.flipper.zerokit.sample.presentation.components.FlipperOutlinedButton
import dev.flipper.zerokit.sample.presentation.components.FlipperPrimaryButton
import dev.flipper.zerokit.sample.presentation.components.InfoPanel
import dev.flipper.zerokit.sample.presentation.components.InfoRow
import dev.flipper.zerokit.sample.presentation.components.SectionTitle
import dev.flipper.api.FlipperPermissions

private enum class EntryAction { KNOWN_DEVICES, SCAN }

@Composable
fun DeviceRoute(viewModel: DeviceViewModel = hiltViewModel()) {

    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                appendLine("FlipperZeroKit prototype / Android " + android.os.Build.VERSION.RELEASE)
                appendLine("Connection: "+uiState.connection)
                appendLine("RPC: "+uiState.rpcStatus)
                appendLine("Known devices: "+uiState.knownDevices)
                appendLine(uiState.diagnostics.pingMessage)
                when (val info = uiState.info) {
                    is DeviceInfoState.Ready -> info.data.fields.toSortedMap().forEach { (k, v) -> appendLine("DeviceInfo $k=$v") }
                    else -> appendLine("DeviceInfo: $info")
                }
                appendLine(uiState.diagnostics.powerMessage)
                uiState.diagnostics.power?.fields?.toSortedMap()?.forEach { (k, v) -> appendLine("PowerInfo $k=$v") }
            }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Flipper diagnostics", report))
        }
    )
}

@Composable
private fun DeviceScreen(
    state: DeviceUiState,
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.headlineMedium)
        Text("Пробная версия · RPC: "+state.rpcStatus)
        TextButton(onClick = onCopyDiagnostics) { Text("Скопировать диагностику") }
        Text(
            stringResource(R.string.connection_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val connection = state.connection) {
            is BleConnectionState.Connected -> {
                Text(
                    stringResource(R.string.connected_status),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall,
                )
                SectionTitle(stringResource(R.string.device_info_title))
                InfoPanel {
                    InfoRow(stringResource(R.string.device_name_label), connection.device.name)
                    InfoRow(stringResource(R.string.device_address_label), connection.device.address)
                    when (val info = state.info) {
                        DeviceInfoState.None -> Text(stringResource(R.string.rpc_waiting))
                        DeviceInfoState.Loading -> {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.loading_info))
                        }
                        is DeviceInfoState.Ready -> {
                            val unknown = stringResource(R.string.value_unknown)
                            InfoRow(stringResource(R.string.firmware_label), info.data.firmwareVersion ?: unknown)
                            InfoRow(stringResource(R.string.firmware_branch_label), info.data.firmwareBranch ?: unknown)
                            InfoRow(stringResource(R.string.hardware_label), info.data.hardwareVersion ?: unknown)
                            InfoRow(stringResource(R.string.hardware_model_label), info.data.hardwareModel ?: unknown)
                            InfoRow(stringResource(R.string.uid_label), info.data.hardwareUid ?: unknown)
                            var showAll by remember(info.data) { mutableStateOf(false) }
                            TextButton(onClick = { showAll = !showAll }) {
                                Text(stringResource(if (showAll) R.string.hide_device_fields else R.string.show_device_fields, info.data.fields.size))
                            }
                            if (showAll) info.data.fields.toSortedMap().forEach { (key, value) -> InfoRow(key, value) }
                        }
                        is DeviceInfoState.Failed -> Text(deviceInfoError(info)+"\n"+(info.detail ?: ""), color = MaterialTheme.colorScheme.error)
                    }
                }
                InfoPanel {
                    Text(state.diagnostics.powerMessage)
                    state.diagnostics.power?.let { power ->
                        InfoRow("Заряд", power.chargePercent?.let { "$it%" } ?: "Неизвестен")
                        InfoRow("Питание", power.chargeState ?: "Неизвестно")
                        var showPower by remember(power) { mutableStateOf(false) }
                        TextButton(onClick = { showPower = !showPower }) { Text("Все поля PowerInfo") }
                        if (showPower) power.fields.toSortedMap().forEach { (key, value) -> InfoRow(key, value) }
                    }
                }
                Text(state.diagnostics.pingMessage)
                FlipperPrimaryButton(stringResource(R.string.command_ping), sendPing, enabled = state.isRpcReady)
                FlipperPrimaryButton(stringResource(R.string.refresh_info), onRefresh,
                    enabled = state.isRpcReady && state.info !is DeviceInfoState.Loading)

                FlipperOutlinedButton(stringResource(R.string.disconnect_device), onDisconnect)
            }
            is BleConnectionState.Pairing -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.device_pairing, connection.device.name))
                FlipperOutlinedButton(stringResource(R.string.disconnect_device), onDisconnect)
            }
            is BleConnectionState.Connecting -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.device_connecting, connection.device.name))
                FlipperOutlinedButton(stringResource(R.string.disconnect_device), onDisconnect)
            }
            BleConnectionState.Disconnected, is BleConnectionState.Failed -> {
                if (connection is BleConnectionState.Failed) {
                    Text(
                        failureText(connection.failure),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (permissionDenied) {
                    Text(stringResource(R.string.permission_denied), color = MaterialTheme.colorScheme.error)
                }
                if (enableDenied) {
                    Text(stringResource(R.string.bluetooth_enable_denied), color = MaterialTheme.colorScheme.error)
                }
                SectionTitle("Уже известные устройства")
                Text("Можно выбрать сопряжённый Flipper без поиска. Метка подключения относится к Android; RPC приложения запускается отдельно.",
                    style = MaterialTheme.typography.bodySmall)
                FlipperOutlinedButton("Обновить подключённые и сопряжённые", onKnownDevices,
                    enabled = !state.knownDevices.isLoading)
                if (state.knownDevices.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.knownDevices.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
                if (state.knownDevices.hasLoaded && !state.knownDevices.isLoading &&
                    state.knownDevices.failure == null && state.knownDevices.devices.isEmpty()) Text("Сохранённых BLE-устройств и GATT-подключений нет")
                state.knownDevices.devices.forEach { known ->
                    Card(onClick = { onConnect(known.device) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(known.device.name, style = MaterialTheme.typography.titleSmall)
                            Text(known.device.address, style = MaterialTheme.typography.bodySmall)
                            if (known.isConnectedToSystem) Text("GATT подключён в Android", color = MaterialTheme.colorScheme.primary)
                            Text(if (known.isBonded) "Сопряжено · нажми для подключения приложения" else "Не сопряжено · может потребоваться подтверждение")
                        }
                    }
                }
                SectionTitle("Поиск новых устройств")
                if (state.isScanning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.scanning))
                    FlipperOutlinedButton(stringResource(R.string.stop_scan), onStopScan)
                } else {
                    FlipperPrimaryButton(
                        label = stringResource(
                            if (state.devices.isEmpty()) R.string.connect_device else R.string.scan_again,
                        ),
                        onClick = onScan,
                    )
                }
                if (state.isScanning || state.devices.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.available_devices))
                    if (state.devices.isEmpty()) Text(stringResource(R.string.no_devices_yet))
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
private fun failureText(failure: BleFailure): String = when (failure) {
    BleFailure.BluetoothUnsupported -> stringResource(R.string.bluetooth_not_supported)
    BleFailure.BluetoothDisabled -> stringResource(R.string.bluetooth_disabled)
    BleFailure.PermissionMissing -> stringResource(R.string.bluetooth_permission_missing)
    BleFailure.LocationServicesDisabled -> stringResource(R.string.permission_location_off)
    BleFailure.PairingFailed -> stringResource(R.string.bluetooth_pairing_failed)
    BleFailure.TimedOut -> stringResource(R.string.bluetooth_timeout)
    is BleFailure.ScanFailed -> stringResource(R.string.bluetooth_scan_failed, failure.code)
    is BleFailure.ConnectionFailed -> stringResource(R.string.bluetooth_connect_failed, failure.code)
    is BleFailure.ServiceDiscoveryFailed -> stringResource(R.string.bluetooth_services_failed, failure.code)
    is BleFailure.Unknown -> stringResource(R.string.bluetooth_unknown_error)
    BleFailure.InvalidGattProfile -> stringResource(R.string.bluetooth_services_validation_failed)
    BleFailure.RPCError,
    BleFailure.RPCSessionInactive,
    BleFailure.EnableCCCDProblem,
    is BleFailure.RPCReadError -> stringResource(R.string.rpc_error)
}

@Composable
private fun deviceInfoError(error: DeviceInfoState.Failed): String = when (error.reason) {
    FlipperRpcException.Reason.NOT_READY -> stringResource(R.string.rpc_waiting)
    FlipperRpcException.Reason.SESSION_CLOSED -> stringResource(R.string.device_info_session_closed)
    FlipperRpcException.Reason.TIMEOUT -> stringResource(R.string.device_info_timeout)
    FlipperRpcException.Reason.REMOTE_ERROR -> stringResource(R.string.device_info_remote_error, error.statusCode ?: -1)
    FlipperRpcException.Reason.SEND_FAILED -> stringResource(R.string.device_info_send_failed)
    FlipperRpcException.Reason.UNEXPECTED_RESPONSE -> stringResource(R.string.device_info_unexpected_response)
    null -> stringResource(R.string.device_info_failed)
}
