package dev.flipper.ui.internal.pages.device

import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.flow.update
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.flipper.ui.FlipperConnectionController
import dev.flipper.api.Flipper
import dev.flipper.api.model.KnownDevicesResult
import dev.flipper.api.model.BleFailure
import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.FlipperRpcException
import dev.flipper.api.model.RpcStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class DeviceViewModel constructor(
    private val flipper: Flipper,
    private val connectionController: FlipperConnectionController,
) : ViewModel() {
    private val info = MutableStateFlow<DeviceInfoState>(DeviceInfoState.None)
    private var infoJob: Job? = null
    private var powerJob: Job? = null
    private var pingJob: Job? = null
    private var knownDevicesJob: Job? = null
    private val knownDevices = MutableStateFlow(KnownDevicesUiState())
    private val diagnostics = MutableStateFlow(DeviceDiagnostics())

    val state: StateFlow<DeviceUiState> = combine(
        flipper.connection, flipper.discoveredDevices, flipper.isScanning, info, flipper.rpcStatus,
    ) { connection, devices, scanning, deviceInfo, rpcStatus ->
        DeviceUiState(connection, devices, scanning, deviceInfo, rpcStatus)
    }.combine(diagnostics) { state, details -> state.copy(diagnostics = details) }.combine(knownDevices) { state, known -> state.copy(knownDevices = known) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceUiState())

    init {
        viewModelScope.launch {
            combine(flipper.connection, flipper.rpcStatus) { connection, rpcStatus ->
                connection is BleConnectionState.Connected && rpcStatus == RpcStatus.ACTIVE
            }.distinctUntilChanged().collect { ready ->
                infoJob?.cancel()
                powerJob?.cancel()
                pingJob?.cancel()
                if (ready) refreshInfo() else {
                    info.value = DeviceInfoState.None
                    diagnostics.value = DeviceDiagnostics()
                }
            }
        }
    }

    fun onIntent(intent: DeviceIntent) {
        viewModelScope.launch {
            when (intent) {
                DeviceIntent.RefreshKnownDevices -> refreshKnownDevices()
                DeviceIntent.StartScan -> flipper.startScan()
                DeviceIntent.StopScan -> flipper.stopScan()
                is DeviceIntent.SelectDevice -> connectionController.connect(intent.device)
                DeviceIntent.Disconnect -> connectionController.disconnect()
                DeviceIntent.RefreshInfo -> if (isRpcReady()) refreshInfo()
                DeviceIntent.Ping -> if (isRpcReady()) sendPing()
            }
        }
    }

    private fun refreshKnownDevices() {
        knownDevicesJob?.cancel()
        knownDevicesJob = viewModelScope.launch {
            knownDevices.value = knownDevices.value.copy(isLoading = true, failure = null)
            try {
                knownDevices.value = when (val result = flipper.getKnownDevices()) {
                    is KnownDevicesResult.Available -> KnownDevicesUiState(result.devices, hasLoaded = true)
                    is KnownDevicesResult.Failed -> KnownDevicesUiState(hasLoaded = true, failure = result.reason)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                Log.e("DeviceViewModel", "Known devices failed", error)
                knownDevices.value = KnownDevicesUiState(hasLoaded = true, failure = BleFailure.Unknown(error.message))
            }
        }
    }

    private fun isRpcReady() = flipper.connection.value is BleConnectionState.Connected &&
        flipper.rpcStatus.value == RpcStatus.ACTIVE

    private fun sendPing() {
        if (pingJob?.isActive == true) return
        pingJob = viewModelScope.launch {
            diagnostics.update { it.copy(pingMessage = "Ожидание PingResponse…") }
            val started = SystemClock.elapsedRealtime()
            try {
                flipper.ping()
                diagnostics.update { it.copy(pingMessage = "Ping OK: "+(SystemClock.elapsedRealtime()-started)+" мс") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { diagnostics.update { it.copy(pingMessage = "Ping: "+error.message) } }
        }
    }

    private fun refreshInfo() {
        powerJob?.cancel()
        powerJob = viewModelScope.launch {
            diagnostics.update { it.copy(powerMessage = "Получение PowerInfo…") }
            try {
                val power = flipper.getPowerInfo()
                diagnostics.update { it.copy(power = power, powerMessage = "PowerInfo получен") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                Log.e("DeviceViewModel", "PowerInfo failed", error)
                diagnostics.update { it.copy(power = null, powerMessage = "PowerInfo: "+error.message) }
            }
        }
        infoJob?.cancel()
        infoJob = viewModelScope.launch {
            info.value = DeviceInfoState.Loading
            try {
                info.value = DeviceInfoState.Ready(flipper.getDeviceInfo())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: FlipperRpcException) {
                Log.e("DeviceViewModel", "DeviceInfo failed: " + error.reason, error)
                info.value = DeviceInfoState.Failed(error.reason, error.statusCode, error.message)
            } catch (error: Exception) {
                Log.e("DeviceViewModel", "DeviceInfo failed", error)
                info.value = DeviceInfoState.Failed(detail = error.message)
            }
        }
    }
}
