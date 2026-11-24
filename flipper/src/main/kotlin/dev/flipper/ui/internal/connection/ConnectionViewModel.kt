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

    init {
        viewModelScope.launch {
            combine(flipper.connection, flipper.rpcStatus, flipper.isRecovering, flipper.isScanning) { connection, rpc, recovery, scan ->
                Snapshot(connection, rpc, recovery, scan)
            }.collect { snapshot ->
                if (snapshot.connection is BleConnectionState.Connected) prefs.edit {
                    putString("address", snapshot.connection.device.address); putString("name", snapshot.connection.device.name)
                }
                if (snapshot.connection is BleConnectionState.Connected && snapshot.rpc == RpcStatus.ACTIVE) {
                    exhaustedAt = Long.MIN_VALUE
                    _status.value = "Подключено"
                } else if (snapshot.recovery) {
                    retryJob?.cancel(); retryJob = null
                    _status.value = "Восстановление после файловой операции…"
                } else if (foreground && automatic && !snapshot.scan &&
                    (snapshot.connection is BleConnectionState.Disconnected || snapshot.connection is BleConnectionState.Failed)) schedule()
            }
        }
    }
    private data class Snapshot(val connection: BleConnectionState, val rpc: RpcStatus, val recovery: Boolean, val scan: Boolean)
    private fun allowed() = FlipperPermissions.connectPermissions.all {
        ContextCompat.checkSelfPermission(getApplication<Application>(), it) == PackageManager.PERMISSION_GRANTED
    }
    private fun remembered(): BleDevice? = prefs.getString("address", null)?.let { BleDevice(prefs.getString("name", null) ?: "Flipper", it) }
    private fun schedule(device: BleDevice? = remembered(), explicit: Boolean = false) {
        if (device == null || retryJob?.isActive == true || !allowed() || flipper.isRecovering.value) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!explicit && exhaustedAt != Long.MIN_VALUE && now - exhaustedAt < 60_000) return
        retryJob = viewModelScope.launch {
            val pauses = longArrayOf(0, 2_000, 6_000)
            for (attempt in pauses.indices) {
                if ((!foreground && !explicit) || !automatic || !allowed()) break
                delay(pauses[attempt])
                _status.value = "Подключение: попытка "+(attempt+1)+" из 3"
                try { flipper.connect(device) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { _status.value = "Подключение недоступно: "+error.message; break }
                val result = withTimeoutOrNull(95_000) {
                    combine(flipper.connection, flipper.rpcStatus) { state, rpc -> state to rpc }.first { (state, rpc) ->
                        state is BleConnectionState.Failed || (state is BleConnectionState.Connected && rpc == RpcStatus.ACTIVE)
                    }
                }
                if (result?.first is BleConnectionState.Connected) return@launch
                val failure = (result?.first as? BleConnectionState.Failed)?.failure
                if (failure == BleFailure.PermissionMissing || failure == BleFailure.BluetoothDisabled ||
                    failure == BleFailure.BluetoothUnsupported || failure == BleFailure.PairingFailed) break
            }
            exhaustedAt = android.os.SystemClock.elapsedRealtime()
            _status.value = "Не удалось подключиться. Выберите устройство для ручного повтора."
        }
    }
    override fun onEnter() {
        if (foreground) return
        foreground = true
        if (automatic && flipper.connection.value !is BleConnectionState.Connected && !flipper.isScanning.value) schedule()
    }
    override fun onLeave(changingConfigurations: Boolean) {
        if (!changingConfigurations) { foreground = false; retryJob?.cancel(); retryJob = null }
    }
    override suspend fun connect(device: BleDevice) {
        retryJob?.cancel(); retryJob = null
        automatic = true; prefs.edit { putBoolean("automatic", true) }
        exhaustedAt = Long.MIN_VALUE
        schedule(device, explicit = true)
    }
    override fun onDiscoveryStarted() { stopAutomatic() }
    private fun stopAutomatic() {
        automatic = false; prefs.edit { putBoolean("automatic", false) }
        retryJob?.cancel(); retryJob = null
        _status.value = "Автоподключение остановлено пользователем"
    }
    override suspend fun disconnect() { stopAutomatic(); flipper.disconnect() }
}
