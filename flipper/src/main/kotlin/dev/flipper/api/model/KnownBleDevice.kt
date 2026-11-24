package dev.flipper.api.model

/** Snapshot of Android's saved devices and system-wide GATT connections, without scanning.
 * Neither flag confirms that this client has a usable Flipper RPC session.
 */
data class KnownBleDevice(
    val device: BleDevice,
    val isBonded: Boolean,
    val isConnectedToSystem: Boolean,
)

sealed interface KnownDevicesResult {
    data class Available(val devices: List<KnownBleDevice>) : KnownDevicesResult
    data class Failed(val reason: BleFailure) : KnownDevicesResult
}
