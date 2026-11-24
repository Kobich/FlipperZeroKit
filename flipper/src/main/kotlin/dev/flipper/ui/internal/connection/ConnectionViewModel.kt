package dev.flipper.ui.internal.connection

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.flipper.api.Flipper
import dev.flipper.ui.FlipperConnectionController
import dev.flipper.api.FlipperPermissions
import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.BleDevice
import kotlinx.coroutines.launch

/** Feature-owned saved-device policy, scoped to the host destination. The host owns Flipper. */
internal class ConnectionViewModel(
    private val flipper: Flipper,
    application: Application,
) : AndroidViewModel(application), FlipperConnectionController {
    private val preferences = application.getSharedPreferences("last_flipper", Context.MODE_PRIVATE)
    private var foreground = false
    private var manuallyDisconnected = false

    init {
        viewModelScope.launch {
            flipper.connection.collect { connection ->
                if (connection is BleConnectionState.Connected) preferences.edit {
                    putString("address", connection.device.address)
                    putString("name", connection.device.name)
                }
            }
        }
    }

    override fun onEnter() {
        if (foreground) return
        foreground = true
        if (manuallyDisconnected || flipper.isScanning.value ||
            flipper.connection.value !is BleConnectionState.Disconnected && flipper.connection.value !is BleConnectionState.Failed) return
        if (!FlipperPermissions.connectPermissions.all {
            ContextCompat.checkSelfPermission(getApplication<Application>(), it) == PackageManager.PERMISSION_GRANTED
        }) return
        val address = preferences.getString("address", null) ?: return
        val name = preferences.getString("name", null) ?: "Flipper"
        viewModelScope.launch { flipper.connect(BleDevice(name, address)) }
    }

    override fun onLeave(changingConfigurations: Boolean) {
        if (!changingConfigurations) { foreground = false; manuallyDisconnected = false }
    }
    override suspend fun connect(device: BleDevice) { manuallyDisconnected = false; flipper.connect(device) }
    override suspend fun disconnect() { manuallyDisconnected = true; flipper.disconnect() }
}
