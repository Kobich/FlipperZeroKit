package dev.flipper.api

import android.content.Context
import dev.flipper.api.model.PowerInfo
import dev.flipper.api.model.StorageEntry
import dev.flipper.api.model.TransferProgress
import dev.flipper.api.model.TransferResult
import java.io.InputStream
import java.io.OutputStream
import com.flipperdevices.protobuf.FlipperProto
import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.BleDevice
import dev.flipper.api.model.DeviceInfo
import dev.flipper.api.model.FlipperRpcException
import dev.flipper.api.model.BleFailure
import dev.flipper.api.model.RpcStatus
import dev.flipper.internal.DefaultFlipper
import dev.flipper.internal.permissions.BluetoothPermissionChecker
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface Flipper {
    val rpcStatus: StateFlow<RpcStatus>
    val connection: StateFlow<BleConnectionState>
    val discoveredDevices: StateFlow<List<BleDevice>>
    val isScanning: StateFlow<Boolean>

    /** No replay; a slow observer may lose old messages. Typed requests use a separate internal router. */
    val messages: SharedFlow<FlipperProto.Main>
    fun nextCommandId(): Int
    /** A successful send acknowledges GATT writes; the caller must await and validate the RPC reply. */
    suspend fun sendMessage(message: FlipperProto.Main): BleFailure?

    /** Requires Connected + RPC ACTIVE. Collects the whole reply; cancellation never returns partial data.
     * @throws FlipperRpcException on timeout, session loss, transport/protocol failure or an RPC error.
     */
    suspend fun getDeviceInfo(timeoutMillis: Long = 10_000L): DeviceInfo

    suspend fun getPowerInfo(timeoutMillis: Long = 10_000L): PowerInfo
    /** Completes only after PingResponse, rather than GATT write acknowledgement. */
    suspend fun ping(timeoutMillis: Long = 10_000L)
    suspend fun listFiles(path: String, timeoutMillis: Long = 30_000L): List<StorageEntry>

    /** Streams on IO; the caller owns/ closes the stream. Success includes size and MD5 verification.
     * Cancellation/failure during transfer disconnects this session; a partial destination may remain.
     * timeoutMillis bounds each transfer exchange; metadata/checksum requests have separate timeouts.
     */
    suspend fun downloadFile(path: String, destination: OutputStream,
        timeoutMillis: Long = 600_000L, onProgress: (TransferProgress) -> Unit = {}): TransferResult

    /** Experimental. Refuses an existing destination (preflight Stat; not an atomic create).
     * ASCII absolute paths only. The caller owns/closes source. One storage operation at a time.
     * A partial file may remain after failure; it is never reported as completed.
     */
    suspend fun uploadFile(path: String, source: InputStream, sizeBytes: Long? = null,
        timeoutMillis: Long = 600_000L, onProgress: (TransferProgress) -> Unit = {}): TransferResult

    suspend fun startScan()
    suspend fun stopScan()
    suspend fun connect(device: BleDevice)
    suspend fun disconnect()
    /** Permanently releases this client. Create a new instance after closing. */
    suspend fun close()

    companion object {
        /** The host owns permission requests and must close the client when its lifetime ends. */
        fun create(context: Context): Flipper {
            val applicationContext = context.applicationContext
            return DefaultFlipper(applicationContext, BluetoothPermissionChecker(applicationContext))
        }
    }
}
