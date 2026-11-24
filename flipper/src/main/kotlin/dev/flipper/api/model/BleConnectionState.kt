package dev.flipper.api.model

sealed interface BleConnectionState {
    data object Disconnected : BleConnectionState
    data class Pairing(val device: BleDevice) : BleConnectionState
    data class Connecting(val device: BleDevice) : BleConnectionState
    data class Connected(val device: BleDevice) : BleConnectionState // Connected == GATT services discovered, NOT Flipper RPC is ready
    data class Failed(val failure: BleFailure) : BleConnectionState
}
