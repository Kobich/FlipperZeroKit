package dev.flipper.zerokit.sample.presentation.pages.device

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.flipper.api.Flipper
import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.FlipperRpcException
import dev.flipper.api.model.RpcStatus
import dev.flipper.zerokit.sample.domain.usecase.ConnectFlipperUseCase
import dev.flipper.zerokit.sample.domain.usecase.DisconnectFlipperUseCase
import dev.flipper.zerokit.sample.domain.usecase.GetFlipperInfoUseCase
import dev.flipper.zerokit.sample.domain.usecase.PingUseCase
import dev.flipper.zerokit.sample.domain.usecase.StartBleScanUseCase
import dev.flipper.zerokit.sample.domain.usecase.StopBleScanUseCase
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val flipper: Flipper,
    private val startScan: StartBleScanUseCase,
    private val stopScan: StopBleScanUseCase,
    private val connect: ConnectFlipperUseCase,
    private val disconnect: DisconnectFlipperUseCase,
    private val ping: PingUseCase,
    private val getInfo: GetFlipperInfoUseCase,
) : ViewModel() {
    private val info = MutableStateFlow<DeviceInfoState>(DeviceInfoState.None)
    private var infoJob: Job? = null

    val state: StateFlow<DeviceUiState> = combine(
        flipper.connection, flipper.discoveredDevices, flipper.isScanning, info, flipper.rpcStatus,
    ) { connection, devices, scanning, deviceInfo, rpcStatus ->
        DeviceUiState(connection, devices, scanning, deviceInfo, rpcStatus)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceUiState())

    init {
        viewModelScope.launch {
            combine(flipper.connection, flipper.rpcStatus) { connection, rpcStatus ->
                connection is BleConnectionState.Connected && rpcStatus == RpcStatus.ACTIVE
            }.distinctUntilChanged().collect { ready ->
                infoJob?.cancel()
                if (ready) refreshInfo() else info.value = DeviceInfoState.None
            }
        }
    }

    fun onIntent(intent: DeviceIntent) {
        viewModelScope.launch {
            when (intent) {
                DeviceIntent.StartScan -> startScan()
                DeviceIntent.StopScan -> stopScan()
                is DeviceIntent.SelectDevice -> connect(intent.device)
                DeviceIntent.Disconnect -> disconnect()
                DeviceIntent.RefreshInfo -> if (isRpcReady()) refreshInfo()
                DeviceIntent.Ping -> if (isRpcReady()) ping()
            }
        }
    }

    private fun isRpcReady() = flipper.connection.value is BleConnectionState.Connected &&
        flipper.rpcStatus.value == RpcStatus.ACTIVE

    private fun refreshInfo() {
        infoJob?.cancel()
        infoJob = viewModelScope.launch {
            info.value = DeviceInfoState.Loading
            try {
                info.value = DeviceInfoState.Ready(getInfo())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: FlipperRpcException) {
                Log.e("DeviceViewModel", "DeviceInfo failed: " + error.reason, error)
                info.value = DeviceInfoState.Failed(error.reason, error.statusCode)
            } catch (error: Exception) {
                Log.e("DeviceViewModel", "DeviceInfo failed", error)
                info.value = DeviceInfoState.Failed()
            }
        }
    }
}
