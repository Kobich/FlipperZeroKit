package dev.flipper.ui.internal.pages.device

import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.BleDevice
import dev.flipper.api.model.BleFailure
import dev.flipper.api.model.KnownBleDevice
import dev.flipper.api.model.DeviceInfo
import dev.flipper.api.model.PowerInfo
import dev.flipper.api.model.RpcStatus
import dev.flipper.api.model.StorageInfo

internal sealed interface DeviceIntent {
    data object RefreshKnownDevices : DeviceIntent
    data object StartScan : DeviceIntent
    data object StopScan : DeviceIntent
    data class SelectDevice(val device: BleDevice) : DeviceIntent
    data object Disconnect : DeviceIntent
    data object RefreshInfo : DeviceIntent
    data object Ping : DeviceIntent
}

internal sealed interface DeviceInfoState<out T> {
    data object Loading : DeviceInfoState<Nothing>
    data class Ready<T>(val data: T) : DeviceInfoState<T>
    data class Failed(val error: Exception) : DeviceInfoState<Nothing>
}

internal data class DeviceDetails(
    val address: String? = null,
    val info: DeviceInfoState<DeviceInfo> = DeviceInfoState.Loading,
    val power: DeviceInfoState<PowerInfo> = DeviceInfoState.Loading,
    val sdCard: DeviceInfoState<StorageInfo> = DeviceInfoState.Loading,
    val pingMessage: String? = null,
) {
    val isLoading: Boolean get() = listOf(info, power, sdCard).any { it == DeviceInfoState.Loading }
}

internal data class DeviceUiState(
    val connection: BleConnectionState = BleConnectionState.Disconnected,
    val devices: List<BleDevice> = emptyList(),
    val isScanning: Boolean = false,
    val rpcStatus: RpcStatus = RpcStatus.NOT_ACTIVE,
    val details: DeviceDetails = DeviceDetails(),
    val knownDevices: KnownDevicesUiState = KnownDevicesUiState(),
) {
    val isRpcReady: Boolean get() = connection is BleConnectionState.Connected && rpcStatus == RpcStatus.ACTIVE
}

internal data class KnownDevicesUiState(val devices: List<KnownBleDevice> = emptyList(),
    val isLoading: Boolean = false, val hasLoaded: Boolean = false, val failure: BleFailure? = null)
