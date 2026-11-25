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
import dev.flipper.api.model.RpcStatus
import dev.flipper.internal.DefaultFlipper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val store: DeviceDetailsStore,
) : ViewModel() {
    private val details = MutableStateFlow(DeviceDetails())
    private var refreshJob: Job? = null
    private var pingJob: Job? = null
    private var knownDevicesJob: Job? = null
    private val knownDevices = MutableStateFlow(KnownDevicesUiState())

    val state: StateFlow<DeviceUiState> = combine(
        flipper.connection, flipper.discoveredDevices, flipper.isScanning, flipper.rpcStatus, details,
    ) { connection, devices, scanning, rpcStatus, deviceDetails ->
        val address = (connection as? BleConnectionState.Connected)?.device?.address
        val visibleDetails = deviceDetails.takeIf { it.address == address } ?: DeviceDetails()
        DeviceUiState(connection, devices, scanning, rpcStatus, visibleDetails)
    }.combine(knownDevices) { state, known -> state.copy(knownDevices = known) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DeviceUiState())

    init {
        viewModelScope.launch {
            combine(flipper.connection, flipper.rpcStatus) { connection, rpcStatus ->
                (connection as? BleConnectionState.Connected)?.device?.address.takeIf { rpcStatus == RpcStatus.ACTIVE }
            }.distinctUntilChanged().collect { address ->
                refreshJob?.cancel()
                pingJob?.cancel()
                val saved = address?.let(store::read)
                details.value = (saved ?: DeviceDetails()).copy(address = address)
                if (address != null && saved == null) refreshInfo(address)
            }
        }
    }

    fun onIntent(intent: DeviceIntent) {
        viewModelScope.launch {
            when (intent) {
                DeviceIntent.RefreshKnownDevices -> refreshKnownDevices()
                DeviceIntent.StartScan -> { connectionController.onDiscoveryStarted(); flipper.startScan() }
                DeviceIntent.StopScan -> flipper.stopScan()
                is DeviceIntent.SelectDevice -> connectionController.connect(intent.device)
                DeviceIntent.Disconnect -> connectionController.disconnect()
                DeviceIntent.RefreshInfo -> readyAddress()?.let(::refreshInfo)
                DeviceIntent.Ping -> if (isRpcReady()) sendPing()
            }
        }
    }

    fun connectionDiagnostics(): List<String> = buildList {
        add("Connection: ${flipper.connection.value}")
        add("RPC: ${flipper.rpcStatus.value}")
        addAll((flipper as? DefaultFlipper)?.recentConnectionEvents().orEmpty())
        val current = details.value
        add(current.pingMessage ?: "No manual ping sent")
        val info = current.info
        if (info is DeviceInfoState.Ready) {
            info.data.fields.toSortedMap().forEach { (key, value) -> add("DeviceInfo $key=$value") }
        } else add("DeviceInfo: $info")
        val power = current.power
        if (power is DeviceInfoState.Ready) {
            power.data.fields.toSortedMap().forEach { (key, value) -> add("PowerInfo $key=$value") }
        } else add("PowerInfo: $power")
        add("SD card: ${current.sdCard}")
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

    private fun readyAddress() = (flipper.connection.value as? BleConnectionState.Connected)?.device?.address
        .takeIf { flipper.rpcStatus.value == RpcStatus.ACTIVE }

    private fun isRpcReady() = readyAddress() != null

    private fun sendPing() {
        if (pingJob?.isActive == true) return
        pingJob = viewModelScope.launch {
            details.update { it.copy(pingMessage = "Waiting for ping reply…") }
            val started = SystemClock.elapsedRealtime()
            try {
                flipper.ping()
                details.update { it.copy(pingMessage = "Ping OK: "+(SystemClock.elapsedRealtime()-started)+" ms") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { details.update { it.copy(pingMessage = "Ping: "+error.message) } }
        }
    }

    private fun refreshInfo(address: String) {
        refreshJob?.cancel()
        details.update { DeviceDetails(address = address, pingMessage = it.pingMessage) }
        refreshJob = viewModelScope.launch {
            val infoJob = launch {
                val info = loadInfo("DeviceInfo") { flipper.getDeviceInfo() }
                details.update { it.copy(info = info) }
            }
            val powerJob = launch {
                val power = loadInfo("PowerInfo") { flipper.getPowerInfo() }
                details.update { it.copy(power = power) }
            }
            val sdJob = launch {
                val sdCard = loadInfo("Storage.Info /ext") { flipper.getStorageInfo("/ext") }
                details.update { it.copy(sdCard = sdCard) }
            }
            infoJob.join()
            powerJob.join()
            sdJob.join()
            if (readyAddress() == address) store.save(address, details.value)
        }
    }

    private suspend fun <T> loadInfo(name: String, load: suspend () -> T): DeviceInfoState<T> = try {
        val data = load()
        currentCoroutineContext().ensureActive()
        DeviceInfoState.Ready(data)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        Log.e("DeviceViewModel", "$name failed", error)
        DeviceInfoState.Failed(error)
    }
}
