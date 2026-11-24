package dev.flipper.zerokit.sample.domain.usecase

import android.util.Log
import com.flipperdevices.protobuf.FlipperProto
import com.flipperdevices.protobuf.system.SystemProto
import dev.flipper.api.Flipper
import dev.flipper.api.model.DeviceInfo
import dev.flipper.zerokit.sample.domain.repository.FlipperFileRepository
import javax.inject.Inject

class PingUseCase @Inject constructor(private val flipper: Flipper) {
    suspend operator fun invoke() {
        val request = FlipperProto.Main.newBuilder()
            .setCommandId(flipper.nextCommandId())
            .setSystemPingRequest(SystemProto.PingRequest.getDefaultInstance())
            .build()
        flipper.sendMessage(request)?.let { Log.e("PingUseCase", "RPC Ping send failed: " + it) }
    }
}

class GetFlipperInfoUseCase @Inject constructor(private val flipper: Flipper) {
    suspend operator fun invoke(): DeviceInfo = flipper.getDeviceInfo()
}

class GetFlipperFilesUseCase @Inject constructor(private val repository: FlipperFileRepository) {
    suspend operator fun invoke() = repository.loadFiles()
}
