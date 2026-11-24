package dev.flipper.zerokit.sample.domain.usecase

import dev.flipper.api.Flipper
import dev.flipper.api.model.DeviceInfo
import javax.inject.Inject

class PingUseCase @Inject constructor(private val flipper: Flipper) {
    suspend operator fun invoke() = flipper.ping()
}
class GetFlipperInfoUseCase @Inject constructor(private val flipper: Flipper) {
    suspend operator fun invoke(): DeviceInfo = flipper.getDeviceInfo()
}
