package dev.flipper.ui.internal.pages.device

import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.BleDevice
import dev.flipper.api.model.BleFailure
import dev.flipper.api.model.KnownBleDevice
import dev.flipper.api.model.DeviceInfo
import dev.flipper.api.model.PowerInfo
import dev.flipper.api.model.FlipperRpcException
import dev.flipper.api.model.RpcStatus

internal sealed interface DeviceIntent {
    data object RefreshKnownDevices : DeviceIntent
    data object StartScan : DeviceIntent
    data object StopScan : DeviceIntent
    data class SelectDevice(val device: BleDevice) : DeviceIntent
    data object Disconnect : DeviceIntent
    data object RefreshInfo : DeviceIntent
    data object Ping : DeviceIntent
}

internal sealed interface DeviceInfoState {
    data object None : DeviceInfoState
    data object Loading : DeviceInfoState
    data class Ready(val data: DeviceInfo) : DeviceInfoState
    data class Failed(val reason: FlipperRpcException.Reason? = null, val statusCode: Int? = null, val detail: String? = null) : DeviceInfoState
}

internal data class DeviceUiState(
    val connection: BleConnectionState = BleConnectionState.Disconnected,
    val devices: List<BleDevice> = emptyList(),
    val isScanning: Boolean = false,
    val info: DeviceInfoState = DeviceInfoState.None,
    val rpcStatus: RpcStatus = RpcStatus.NOT_ACTIVE,
    val diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
    val knownDevices: KnownDevicesUiState = KnownDevicesUiState(),
) {
    val isRpcReady: Boolean get() = connection is BleConnectionState.Connected && rpcStatus == RpcStatus.ACTIVE
}

internal data class DeviceDiagnostics(val power: PowerInfo? = null, val powerMessage: String = "Ожидание RPC", val pingMessage: String = "Ping ещё не запускался")

internal data class KnownDevicesUiState(val devices: List<KnownBleDevice> = emptyList(),
    val isLoading: Boolean = false, val hasLoaded: Boolean = false, val failure: BleFailure? = null)
