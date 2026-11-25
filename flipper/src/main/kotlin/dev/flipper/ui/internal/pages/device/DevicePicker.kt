package dev.flipper.ui.internal.pages.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.flipper.R
import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.BleDevice

private const val VISIBLE_NEARBY_DEVICES = 4

@Composable
internal fun DevicePicker(
    state: DeviceUiState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onRefreshKnown: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onConnect: (BleDevice) -> Unit,
) {
    val known = state.knownDevices
    var hasScanned by rememberSaveable { mutableStateOf(false) }
    var showAllNearby by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.isScanning) {
        if (state.isScanning) hasScanned = true
    }
    val nearby = remember(state.devices, known.devices) {
        val knownAddresses = known.devices.mapTo(hashSetOf()) { it.device.address.uppercase() }
        state.devices.filterNot { it.address.uppercase() in knownAddresses }
    }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.flipper_devices), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onCopyDiagnostics) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.flipper_copy_diagnostics))
                }
                IconButton(onClick = onRefreshKnown, enabled = !known.isLoading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.flipper_refresh_known_devices))
                }
                FilledTonalButton(
                    onClick = {
                        if (state.isScanning) onStopScan() else {
                            showAllNearby = false
                            onScan()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(stringResource(if (state.isScanning) R.string.flipper_stop_scan else R.string.flipper_scan))
                }
            }
            if (state.isScanning || known.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            known.failure?.let {
                Text(failureText(it), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (known.devices.isNotEmpty()) {
                DeviceGroupLabel(stringResource(R.string.flipper_known_devices))
                known.devices.forEach { entry ->
                    val status = when {
                        entry.isConnectedToSystem && entry.isBonded -> stringResource(R.string.flipper_device_paired) + "\n" +
                            stringResource(R.string.flipper_android_connected)
                        entry.isConnectedToSystem -> stringResource(R.string.flipper_android_connected)
                        entry.isBonded -> stringResource(R.string.flipper_device_paired)
                        else -> null
                    }
                    key(entry.device.address) { BluetoothDeviceRow(entry.device, status, onConnect) }
                }
            }

            if (nearby.isNotEmpty()) {
                if (known.devices.isNotEmpty()) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                DeviceGroupLabel(stringResource(R.string.flipper_nearby_devices))
                val shown = if (showAllNearby) nearby else nearby.take(VISIBLE_NEARBY_DEVICES)
                shown.forEach { device ->
                    key(device.address) { BluetoothDeviceRow(device, null, onConnect) }
                }
                val hiddenCount = nearby.size - VISIBLE_NEARBY_DEVICES
                if (hiddenCount > 0) {
                    TextButton(onClick = { showAllNearby = !showAllNearby }, modifier = Modifier.padding(start = 4.dp)) {
                        Text(if (showAllNearby) stringResource(R.string.flipper_show_fewer_devices)
                            else stringResource(R.string.flipper_show_more_devices, hiddenCount))
                    }
                }
            } else {
                when {
                    state.isScanning -> DeviceGroupLabel(stringResource(R.string.flipper_scanning))
                    hasScanned && state.connection !is BleConnectionState.Failed ->
                        DeviceGroupLabel(stringResource(R.string.flipper_no_devices_yet))
                    known.hasLoaded && known.failure == null && known.devices.isEmpty() ->
                        DeviceGroupLabel(stringResource(R.string.flipper_no_known_devices))
                }
            }
        }
    }
}

@Composable
private fun DeviceGroupLabel(text: String) {
    Text(text, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BluetoothDeviceRow(device: BleDevice, status: String?, onConnect: (BleDevice) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .clickable(role = Role.Button) { onConnect(device) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(device.address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (status != null) {
            Text(status, Modifier.widthIn(max = 100.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End)
        }
    }
}
