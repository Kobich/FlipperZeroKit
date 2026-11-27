package dev.flipper.internal.rpc

import com.flipperdevices.protobuf.FlipperProto
import com.flipperdevices.protobuf.system.SystemProto
import com.google.protobuf.ByteString
import dev.flipper.internal.transport.BleReceiver
import java.io.EOFException
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcMessageFramerTest {
    private val framer = RpcMessageFramer()

    @Test
    fun reconnectDiscardsOldTxBeforeDecodingReadinessPing() = runBlocking {
        val receiver = BleReceiver()
        receiver.start()
        assertTrue(receiver.onReceive(byteArrayOf(0))) // Unconsumed bytes from the interrupted session.
        receiver.close()

        // A one-byte payload with an invalid protobuf tag must not reach the new decoder.
        assertTrue(receiver.onReceive(byteArrayOf(1, 0)))
        assertEquals(1, receiver.ignoredSetupFragments)

        val chunks = receiver.start()
        val ping = FlipperProto.Main.newBuilder().setCommandId(8)
            .setSystemPingResponse(SystemProto.PingResponse.getDefaultInstance()).build()
        val frame = framer.encode(ping)
        assertTrue(receiver.onReceive(frame.copyOfRange(0, 1)))
        assertTrue(receiver.onReceive(frame.copyOfRange(1, frame.size)))
        assertEquals(ping, withTimeout(1_000) { framer.decode(chunks).first() })
        receiver.close()
    }

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
