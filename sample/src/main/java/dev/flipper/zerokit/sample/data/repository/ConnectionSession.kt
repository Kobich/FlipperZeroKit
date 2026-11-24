package dev.flipper.zerokit.sample.data.repository

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.flipper.api.Flipper
import dev.flipper.ui.FlipperConnectionController
import dev.flipper.api.FlipperPermissions
import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.BleDevice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-owned client policy. One reconnect attempt per feature entry/foreground return. */
@Singleton
class ConnectionSession @Inject constructor(
    private val flipper: Flipper,
    @param:ApplicationContext private val context: Context,
) : FlipperConnectionController {
    private val preferences = context.getSharedPreferences("last_flipper", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false
    private var manuallyDisconnected = false

    init {
        scope.launch {
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
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }) return
        val address = preferences.getString("address", null) ?: return
        val name = preferences.getString("name", null) ?: "Flipper"
        scope.launch { flipper.connect(BleDevice(name, address)) }
    }

    override fun onLeave(changingConfigurations: Boolean) {
        if (!changingConfigurations) { foreground = false; manuallyDisconnected = false }
    }
    override suspend fun connect(device: BleDevice) { manuallyDisconnected = false; flipper.connect(device) }
    override suspend fun disconnect() { manuallyDisconnected = true; flipper.disconnect() }
}
