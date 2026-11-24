package dev.flipper.zerokit.sample.domain.usecase

import dev.flipper.api.model.BleDevice
import dev.flipper.api.Flipper
import javax.inject.Inject

class StartBleScanUseCase @Inject constructor(private val repository: Flipper) {
    suspend operator fun invoke() = repository.startScan()
}

class StopBleScanUseCase @Inject constructor(private val repository: Flipper) {
    suspend operator fun invoke() = repository.stopScan()
}

class ConnectFlipperUseCase @Inject constructor(private val repository: Flipper) {
    suspend operator fun invoke(device: BleDevice) = repository.connect(device)
}

class DisconnectFlipperUseCase @Inject constructor(private val repository: Flipper) {
    suspend operator fun invoke() = repository.disconnect()
}
