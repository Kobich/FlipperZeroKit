package dev.flipper.ui.internal.connection

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.flipper.api.Flipper
import dev.flipper.api.FlipperPermissions
import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.BleDevice
import dev.flipper.api.model.BleFailure
import dev.flipper.api.model.KnownDevicesResult
import dev.flipper.api.model.RpcStatus
import dev.flipper.ui.FlipperConnectionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class ConnectionViewModel(private val flipper: Flipper, application: Application) :
    AndroidViewModel(application), FlipperConnectionController {
    private val prefs = application.getSharedPreferences("last_flipper", Context.MODE_PRIVATE)
    private var foreground = false
    private var automatic = prefs.getBoolean("automatic", true)
    private var retryJob: Job? = null
    private var exhaustedAt = Long.MIN_VALUE
    private val _status = MutableStateFlow("")
    override val status = _status.asStateFlow()
    private val _connectingDevice = MutableStateFlow<BleDevice?>(null)
    val connectingDevice = _connectingDevice.asStateFlow()

    init {
        viewModelScope.launch {
            combine(flipper.connection, flipper.rpcStatus, flipper.isRecovering, flipper.isScanning) { connection, rpc, recovery, scan ->
                Snapshot(connection, rpc, recovery, scan)
            }.collect { snapshot ->
                if (snapshot.connection is BleConnectionState.Connected && snapshot.rpc == RpcStatus.ACTIVE) {
                    if (remembered() == null) remember(snapshot.connection.device)
                    exhaustedAt = Long.MIN_VALUE
                    _status.value = ""
                } else if (snapshot.recovery) {
                    cancelRetry()
                } else if (foreground && automatic && !snapshot.scan && snapshot.connection.canReconnect()) schedule()
            }
        }
    }
    private data class Snapshot(val connection: BleConnectionState, val rpc: RpcStatus, val recovery: Boolean, val scan: Boolean)
    private fun BleConnectionState.canReconnect() = this is BleConnectionState.Disconnected || this is BleConnectionState.Failed
    private fun allowed() = FlipperPermissions.connectPermissions.all {
        ContextCompat.checkSelfPermission(getApplication<Application>(), it) == PackageManager.PERMISSION_GRANTED
    }
    private fun remembered(): BleDevice? = prefs.getString("address", null)?.let { BleDevice(prefs.getString("name", null) ?: "Flipper", it) }
    private fun remember(device: BleDevice) = prefs.edit {
        putString("address", device.address)
        putString("name", device.name)
    }

    private fun schedule(device: BleDevice? = remembered(), explicit: Boolean = false) {
        if (device == null || retryJob?.isActive == true || flipper.isRecovering.value) return
        if (!allowed()) {
            if (explicit) _status.value = "Bluetooth permission is missing. Grant Nearby devices access in Android settings."
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (!explicit && exhaustedAt != Long.MIN_VALUE && now - exhaustedAt < 60_000) return
        _connectingDevice.value = device
        retryJob = viewModelScope.launch {
            if (!explicit) {
                val known = flipper.getKnownDevices()
                if (known is KnownDevicesResult.Available && known.devices.none {
                        it.isBonded && it.device.address.equals(device.address, ignoreCase = true)
                    }) {
                    _status.value = "${device.name} is no longer paired. Select a device."
                    _connectingDevice.value = null
                    return@launch
                }
            }
            val pauses = longArrayOf(0, 2_000, 6_000)
            for (attempt in pauses.indices) {
                if ((!foreground && !explicit) || !automatic || !allowed()) break
                delay(pauses[attempt])
                _status.value = "Connecting, attempt "+(attempt+1)+" of 3"
                try { flipper.connect(device) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { _status.value = "Connection unavailable: "+error.message; break }
                val result = withTimeoutOrNull(95_000) {
                    combine(flipper.connection, flipper.rpcStatus) { state, rpc -> state to rpc }.first { (state, rpc) ->
                        state.canReconnect() || (state is BleConnectionState.Connected && rpc == RpcStatus.ACTIVE)
                    }
                }
                if (result?.first is BleConnectionState.Connected) {
                    _connectingDevice.value = null
                    _status.value = ""
                    return@launch
                }
                val failure = (result?.first as? BleConnectionState.Failed)?.failure
                if (failure == BleFailure.PermissionMissing || failure == BleFailure.BluetoothDisabled ||
                    failure == BleFailure.BluetoothUnsupported || failure == BleFailure.PairingFailed) break
            }
            exhaustedAt = android.os.SystemClock.elapsedRealtime()
            _status.value = "Could not connect. Select a device to try again."
            _connectingDevice.value = null
        }
    }
    override fun onEnter() {
        if (foreground) return
        foreground = true
        if (automatic && flipper.connection.value.canReconnect() && !flipper.isScanning.value) schedule()
    }
    override fun onLeave(changingConfigurations: Boolean) {
        if (!changingConfigurations) { foreground = false; cancelRetry() }
    }
    override suspend fun connect(device: BleDevice) {
        cancelRetry()
        remember(device)
        automatic = true; prefs.edit { putBoolean("automatic", true) }
        exhaustedAt = Long.MIN_VALUE
        schedule(device, explicit = true)
    }
    override fun onDiscoveryStarted() { stopAutomatic() }
    private fun cancelRetry() {
        if (retryJob?.isActive == true) _status.value = ""
        retryJob?.cancel()
        retryJob = null
        _connectingDevice.value = null
    }
    private fun stopAutomatic() {
        automatic = false; prefs.edit { putBoolean("automatic", false) }
        cancelRetry()
        _status.value = "Auto-connect is off"
    }
    override suspend fun disconnect() { stopAutomatic(); flipper.disconnect() }
}
