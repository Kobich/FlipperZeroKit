package dev.flipper.ui

import dev.flipper.api.model.BleDevice

/** Optional host policy for saved devices/reconnect. The host owns the Flipper client lifetime.
 * Calls are made on Main. Entry/leave track the feature destination as well as app visibility.
 */
interface FlipperConnectionController {
    val status: kotlinx.coroutines.flow.StateFlow<String>? get() = null
    fun onDiscoveryStarted() {}
    suspend fun connect(device: BleDevice)
    suspend fun disconnect()
    fun onEnter() {}
    /** Rotation must not be treated as an intentional exit/re-entry. */
    fun onLeave(changingConfigurations: Boolean) {}
}
