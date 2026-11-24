package dev.flipper.zerokit.sample.presentation.pages.device

import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.BleDevice
import dev.flipper.api.model.DeviceInfo
import dev.flipper.api.model.FlipperRpcException
import dev.flipper.api.model.RpcStatus

sealed interface DeviceIntent {
    data object StartScan : DeviceIntent
    data object StopScan : DeviceIntent
    data class SelectDevice(val device: BleDevice) : DeviceIntent
    data object Disconnect : DeviceIntent
    data object RefreshInfo : DeviceIntent
    data object Ping : DeviceIntent
}

sealed interface DeviceInfoState {
    data object None : DeviceInfoState
    data object Loading : DeviceInfoState
    data class Ready(val data: DeviceInfo) : DeviceInfoState
    data class Failed(val reason: FlipperRpcException.Reason? = null, val statusCode: Int? = null) : DeviceInfoState
}

data class DeviceUiState(
    val connection: BleConnectionState = BleConnectionState.Disconnected,
    val devices: List<BleDevice> = emptyList(),
    val isScanning: Boolean = false,
    val info: DeviceInfoState = DeviceInfoState.None,
    val rpcStatus: RpcStatus = RpcStatus.NOT_ACTIVE,
) {
    val isRpcReady: Boolean get() = connection is BleConnectionState.Connected && rpcStatus == RpcStatus.ACTIVE
}
