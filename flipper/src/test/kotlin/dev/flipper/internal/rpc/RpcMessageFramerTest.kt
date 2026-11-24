package dev.flipper.internal.rpc

import com.flipperdevices.protobuf.FlipperProto
import com.flipperdevices.protobuf.system.SystemProto
import com.google.protobuf.ByteString
import java.io.EOFException
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RpcMessageFramerTest {
    private val framer = RpcMessageFramer()

    @Test
    fun decodesSplitLengthAndPayloadAndAdjacentFrames() = runBlocking {
        val ping = FlipperProto.Main.newBuilder()
            .setCommandId(1)
            .setSystemPingResponse(SystemProto.PingResponse.newBuilder()
                .setData(ByteString.copyFrom(ByteArray(300) { it.toByte() })))
            .build()
        val info = FlipperProto.Main.newBuilder()
            .setCommandId(2)
            .setSystemDeviceInfoResponse(SystemProto.DeviceInfoResponse.newBuilder()
                .setKey("firmware_version").setValue("test"))
            .build()
        val bytes = framer.encode(ping) + framer.encode(info)
        val expected = listOf(ping, info)
        assertEquals(expected, framer.decode(bytes.map { byteArrayOf(it) }.asFlow()).toList())
        assertEquals(expected, framer.decode(flowOf(bytes)).toList())
    }

    @Test
    fun rejectsOversizedAndOverflowingLengths() {
        for (prefix in listOf(
            byteArrayOf(0x81.toByte(), 0x80.toByte(), 0x40), // 1 MiB + 1
            byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x10),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { framer.decode(flowOf(prefix)).toList() }
            }
        }
    }

    @Test
    fun reportsTruncatedPayload() {
        val message = FlipperProto.Main.newBuilder().setCommandId(7)
            .setSystemPingResponse(SystemProto.PingResponse.getDefaultInstance()).build()
        val encoded = framer.encode(message)
        assertThrows(EOFException::class.java) {
            runBlocking { framer.decode(flowOf(encoded.copyOf(encoded.size - 1))).toList() }
        }
    }
}
