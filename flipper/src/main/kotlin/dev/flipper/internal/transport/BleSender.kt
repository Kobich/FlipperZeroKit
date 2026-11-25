package dev.flipper.internal.transport

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.BleFailure
import dev.flipper.api.model.RpcStatus
import dev.flipper.internal.DefaultFlipper.Companion.ATT_WRITE_OVERHEAD
import dev.flipper.internal.DefaultFlipper.Companion.DEFAULT_ATT_MTU
import dev.flipper.internal.DefaultFlipper.Companion.FLIPPER_RX_MAX_SIZE
import dev.flipper.internal.ble.FlipperGattUuid
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class BleSender(
    private val getGatt: () -> BluetoothGatt?,
    private val isBtConnected: StateFlow<BleConnectionState>,
    private val rpcSessionStatus: StateFlow<RpcStatus>,
    private val onWriteFail: (BluetoothGatt, String) -> Unit
) {

    private var currentAttMtu = DEFAULT_ATT_MTU
    private var availableBytesToSend = MutableStateFlow(0)  // depends on FLOW_CONTROL

    /**
     * Sends a full message (byte sequence) through RX
     *
     * * serializes concurrent sends with `Mutex`
     * * splits data according to the current ATT MTU
     * * waits for `onCharacteristicWrite()` after every chunk
     *
     * @return `null` if all GATT writes completed successfully
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun sendRxMessage(message: ByteArray, gatt: BluetoothGatt): BleFailure? = withContext(Dispatchers.Main) {
        if (message.isEmpty()) return@withContext BleFailure.RPCError
        if (getGatt() !== gatt) return@withContext BleFailure.RPCSessionInactive
        val buffer = message.copyOf()

        // The caller supplies a complete RPC frame.

        return@withContext writeRxMutex.withLock {
            if (!isConnectionValid(gatt)) return@withLock BleFailure.RPCError

            var offset = 0
            try {
                while (offset < buffer.size) {
                    currentCoroutineContext().ensureActive()

                    // wait for flow control credits
                    val available = awaitAvailableBytes(gatt) ?: return@withLock BleFailure.RPCError

                    // calculate chunk size
                    val chunkSize = minOf(
                        currentAttMtu - ATT_WRITE_OVERHEAD,
                        FLIPPER_RX_MAX_SIZE,
                        available,
                        buffer.size - offset
                    )
                    Log.v(TAG, "Calculated chunk size = $chunkSize")
                    val chunk = buffer.copyOfRange(offset, offset + chunkSize)
                    availableBytesToSend.value = available - chunk.size // calculate free bytes

                    val result = writeRxChunk(gatt, chunk)
                    if (result != null) {
                        if (offset > 0) onWriteFail(gatt, "RX frame interrupted at $offset bytes: $result")
                        return@withLock result
                    }

                    offset += chunkSize
                }
                null
            } catch (e: CancellationException) {
                if (offset > 0) onWriteFail(gatt, "RX frame cancelled at $offset bytes")
                throw e
            }
        }
    }


    /**
     * Use to write fragments sequentially
     */
    private val writeRxMutex = Mutex()
    /**
     * Reference to one specific operation
     */
    private var pendingRxWrite: PendingWrite? = null

    fun handleCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (characteristic.uuid != FlipperGattUuid.Characteristic.RX.uuid) return
        val pending = pendingRxWrite ?: return
        if (pending.gatt != gatt) return

        val result = if (status == BluetoothGatt.GATT_SUCCESS) {
            WriteResult.Success
        } else {
            WriteResult.Failure(status)
        }

        pending.completion.complete(result)
    }

    fun onGattClosed(gatt: BluetoothGatt) {
        setAvailableBytes(0)

        val pending = pendingRxWrite ?: return
        if (pending.gatt != gatt) return

        pending.completion.complete(WriteResult.Disconnected)
    }

    fun setAttMtu(value: Int) {
        currentAttMtu = value
    }

    fun setAvailableBytes(value: Int) {
        availableBytesToSend.value = value
    }


    // ---------- Private block ---------- //

    /**
     * Executes one GATT-record and waiting for onCharacteristicWrite()
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun writeRxChunk(gatt: BluetoothGatt, bytes: ByteArray): BleFailure? {
        if (!isConnectionValid(gatt)) return BleFailure.RPCError

        val rxChar = gatt.getService(FlipperGattUuid.SERVICE).getCharacteristic(FlipperGattUuid.Characteristic.RX.uuid)

        val pending = PendingWrite(gatt = gatt, completion = CompletableDeferred())
        pendingRxWrite = pending
        var writeStarted = false

        try {
            writeStarted = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(rxChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else @Suppress("DEPRECATION") {
                    rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    rxChar.value = bytes.copyOf()
                    gatt.writeCharacteristic(rxChar)
                }
            } catch (_: SecurityException) {
                return BleFailure.PermissionMissing
            } catch (_: Exception) {
                false
            }

            // failed initiation may indicate an unavailable or busy GATT session
            if (!writeStarted) return BleFailure.RPCError

            val result = withTimeoutOrNull(WRITE_TIMEOUT_MS.milliseconds) {
                pending.completion.await()
            }
            return when(result) {
                WriteResult.Success -> if (isConnectionValid(gatt)) null else BleFailure.RPCError
                WriteResult.Disconnected -> BleFailure.RPCError
                is WriteResult.Failure -> {
                    onWriteFail(gatt, "RX write: GATT status=${result.status}")
                    BleFailure.RPCError
                }
                null -> {
                    onWriteFail(gatt, "RX write callback timed out")
                    BleFailure.RPCError
                }
            }
        } catch (e: CancellationException) {
            if (writeStarted && !pending.completion.isCompleted) onWriteFail(gatt, "RX write cancelled before callback")
            throw e
        } finally {
            pendingRxWrite = null
        }
    }

    private data class PendingWrite(
        val gatt: BluetoothGatt,
        val completion: CompletableDeferred<WriteResult>,
    )
    private sealed interface WriteResult {
        data object Success : WriteResult
        data object Disconnected : WriteResult
        data class Failure(val status: Int) : WriteResult
    }


    /**
     * Waiting for available bytes to have opportunity to send/continue to send RX command
     */
    private suspend fun awaitAvailableBytes(gatt: BluetoothGatt): Int? = withTimeoutOrNull(FLOW_CONTROL_TIMEOUT_MS.milliseconds) {
        combine(availableBytesToSend, isBtConnected, rpcSessionStatus) { credits, connection, rpcStatus ->
            credits to isConnectionValid(connection, rpcStatus)
        }
            // if the connection was lost or rpc became inactive -> stop it
            .first { (credits, ready) -> credits > 0 || !ready || getGatt() !== gatt }
            .let { (credits, ready) ->
                // if there is no new data transition window -> null
                // else -> value
                credits.takeIf { it > 0 && ready && getGatt() === gatt }
            }
    }

    private fun isConnectionValid(gatt: BluetoothGatt) =
        getGatt() == gatt && isBtConnected.value is BleConnectionState.Connected && rpcSessionStatus.value == RpcStatus.ACTIVE
    private fun isConnectionValid(connection: BleConnectionState, rpcStatus: RpcStatus) =
        connection is BleConnectionState.Connected && rpcStatus == RpcStatus.ACTIVE


    companion object {
        private const val TAG = "RpcSender"

        private const val WRITE_TIMEOUT_MS = 10_000L
        private const val FLOW_CONTROL_TIMEOUT_MS = 30_000L
    }

}
