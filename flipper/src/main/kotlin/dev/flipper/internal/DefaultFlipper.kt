package dev.flipper.internal

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import com.flipperdevices.protobuf.FlipperProto
import dev.flipper.api.Flipper
import dev.flipper.api.model.BleConnectionState
import dev.flipper.api.model.BleDevice
import dev.flipper.api.model.BleFailure
import dev.flipper.api.model.RpcStatus
import dev.flipper.internal.ble.BlePairingManager
import dev.flipper.internal.ble.BleScanner
import dev.flipper.internal.ble.FlipperGattUuid
import dev.flipper.internal.ble.GattManager
import dev.flipper.internal.permissions.BluetoothPermissionChecker
import dev.flipper.internal.rpc.RpcClient
import dev.flipper.internal.rpc.RpcMessageFramer
import dev.flipper.api.model.DeviceInfo
import dev.flipper.api.model.FlipperRpcException
import kotlinx.coroutines.channels.BufferOverflow
import dev.flipper.internal.transport.BleReceiver
import dev.flipper.internal.transport.BleSender
import dev.flipper.internal.util.toInt
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates BLE discovery, pairing and GATT connection.
 *
 * BLE scanning and pairing are delegated to dedicated components.
 * This repository owns the current GATT connection and exposes
 * its states to the domain layer.
 *
 * All internal Bluetooth lifecycle operations are serialized
 * on the main thread.
 *
 * Protobuf framing and raw RPC exchange are available.
 * TODO(rpc): add request routing, timeouts and typed operations.
 */
internal class DefaultFlipper(
    private val context: Context,
    private val permissionChecker: BluetoothPermissionChecker
) : Flipper {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gattManager = GattManager()
    private var closed = false

    // ---------- BLE components ---------- //

    private val bleScanner = BleScanner(
        scope = scope,
        adapter = context.getSystemService(BluetoothManager::class.java)?.adapter,
        hasConnectPermission = permissionChecker::hasConnectPermission,
        onFailure = { reason -> fail(reason) }
    )

    private val blePairingManager = BlePairingManager(
        context = context,
        scope = scope,
        hasConnectPermission = permissionChecker::hasConnectPermission,
        onPairingStarted = { device -> saveConnectionState(BleConnectionState.Pairing(device)) },
        onSuccessBonding = ::startGattConnection,
        onFailure = { reason -> fail(reason) },
    )

    override val isScanning = bleScanner.isScanning
    override val discoveredDevices = bleScanner.discoveredDevices

    // ---------- Connection state ---------- //

    private var connectTimeout: Job? = null

    private val _connection = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    override val connection = _connection.asStateFlow()

    private val _rpcStatus = MutableStateFlow(RpcStatus.NOT_ACTIVE)
    override val rpcStatus = _rpcStatus.asStateFlow()

    // ---------- Data exchanging ---------- //

    @Volatile
    private var currentGatt: BluetoothGatt? = null

    private val flipperReceiver = BleReceiver()

    private val flipperSender = BleSender(
        getGatt = { currentGatt },
        isBtConnected = connection,
        rpcSessionStatus = rpcStatus,
        onWriteFail = { gatt -> failGatt(gatt, BleFailure.RPCError) }
    )


    private val rpcFramer = RpcMessageFramer()
    private val commandIds = AtomicInteger(1)
    private val rpcClient = RpcClient(::nextCommandId)
    private val _messages = MutableSharedFlow<FlipperProto.Main>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val messages = _messages.asSharedFlow()
    private var rpcReceiveJob: Job? = null

    // ---------- Public API ---------- //

    override suspend fun startScan() {
        withContext(Dispatchers.Main) {
            check(!closed) { "Flipper is closed" }
            bleScanner.stop()
            blePairingManager.clearBondWaiting()
            closeCurrentGatt()

            if (!permissionChecker.checkBluetoothReady(scan = true, onFail = ::fail)) {
                return@withContext
            }

            saveConnectionState(BleConnectionState.Disconnected)

            bleScanner.start()?.let { reason ->
                fail(reason)
            }
        }
    }

    override suspend fun stopScan() {
        withContext(Dispatchers.Main) {
            check(!closed) { "Flipper is closed" }
            bleScanner.stop()
        }
    }

    override suspend fun connect(device: BleDevice) {
        withContext(Dispatchers.Main) {
            check(!closed) { "Flipper is closed" }
            bleScanner.stop()
            blePairingManager.clearBondWaiting()
            closeCurrentGatt()

            if (!permissionChecker.checkBluetoothReady(scan = false, onFail = ::fail)) {
                return@withContext
            }

            blePairingManager.connect(device)
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.Main) {
            check(!closed) { "Flipper is closed" }
            bleScanner.stop()
            blePairingManager.clearBondWaiting()
            closeCurrentGatt()
            saveConnectionState(BleConnectionState.Disconnected)
        }
    }

    private fun saveConnectionState(value: BleConnectionState) {
        Log.d(TAG, "Connection: $value")
        _connection.update { value }
    }

    private fun saveRpcState(value: RpcStatus) {
        Log.d(TAG, "RPC status: $value")
        _rpcStatus.update { value }

        if (value != RpcStatus.ACTIVE) {
            rpcClient.onSessionClosed()
            currentGatt?.let(flipperSender::onRpcInactive)
            flipperSender.setAvailableBytes(0)
        }
    }


    override suspend fun close() {
        withContext(Dispatchers.Main) {
            if (closed) return@withContext
            closed = true
            bleScanner.stop()
            blePairingManager.clearBondWaiting()
            closeCurrentGatt()
            saveConnectionState(BleConnectionState.Disconnected)
            scope.cancel()
        }
    }

    // ---------- GATT connection ---------- //

    @SuppressLint("MissingPermission")
    private fun startGattConnection(remote: BluetoothDevice, device: BleDevice) {
        if (!permissionChecker.checkBluetoothReady(scan = false, onFail = ::fail)) return

        saveConnectionState(BleConnectionState.Connecting(device))

        try {
            val callback = createGattCallback(device)
            val gatt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                remote.connectGatt(
                    context, false, callback, BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK, mainHandler,
                )
            } else {
                remote.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }

            if (gatt == null) {
                fail(BleFailure.ConnectionFailed(-1))
                return
            }

            currentGatt = gatt

            flipperReceiver.reset()
            startRpcReceiving(gatt)

            connectTimeout = scope.launch {
                delay(CONNECTION_TIMEOUT_MS.milliseconds)
                if (isCurrentGatt(gatt) && _connection.value is BleConnectionState.Connecting) {
                    failGatt(gatt, BleFailure.TimedOut)
                }
            }

        } catch (_: SecurityException) {
            fail(BleFailure.PermissionMissing)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Cannot connect GATT", e)
            fail(BleFailure.ConnectionFailed(-1))
        }
    }

    private fun dispatchGattCallback(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    private fun createGattCallback(device: BleDevice): BluetoothGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = dispatchGattCallback {
            if (!isCurrentGatt(gatt)) return@dispatchGattCallback

            when {
                status != BluetoothGatt.GATT_SUCCESS ->
                    failGatt(gatt, BleFailure.ConnectionFailed(status))

                newState == BluetoothProfile.STATE_CONNECTED -> discoverServices(gatt)
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    closeCurrentGatt()
                    saveConnectionState(BleConnectionState.Disconnected)
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = dispatchGattCallback {
            if (!isCurrentGatt(gatt)) return@dispatchGattCallback

            if (status != BluetoothGatt.GATT_SUCCESS) {
                failGatt(gatt, BleFailure.ServiceDiscoveryFailed(status))
                return@dispatchGattCallback
            }

            if (!gattManager.validateGattConnection(gatt)) {
                failGatt(gatt, BleFailure.InvalidGattProfile)
                return@dispatchGattCallback
            }

            // Start sequential CCCD subscriptions:
            // TX -> FLOW_CONTROL -> RPC_STATUS.
            gattManager.initSubscribingQueue(gatt)
            subscribeNext(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt,descriptor: BluetoothGattDescriptor, status: Int) = dispatchGattCallback {
            if (!isCurrentGatt(gatt)) return@dispatchGattCallback

            if (status != BluetoothGatt.GATT_SUCCESS) {
                failGatt(gatt, BleFailure.EnableCCCDProblem)
                return@dispatchGattCallback
            }

            if (descriptor.uuid != FlipperGattUuid.BtSigConst.CCCD) return@dispatchGattCallback

            when (descriptor.characteristic.uuid) {
                FlipperGattUuid.Characteristic.TX.uuid,
                FlipperGattUuid.Characteristic.FLOW_CONTROL.uuid -> {
                    subscribeNext(gatt)
                }
                FlipperGattUuid.Characteristic.RPC_STATUS.uuid -> {
                    onEnableAllCCCD(gatt, device)
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            dispatchRead(gatt, characteristic, characteristic.value?.copyOf() ?: byteArrayOf(), status)

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            dispatchRead(gatt, characteristic, value.copyOf(), status)

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) =
            dispatchChanged(gatt, characteristic, characteristic.value?.copyOf() ?: byteArrayOf())

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            dispatchChanged(gatt, characteristic, value.copyOf())

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = dispatchGattCallback {
            if (!isCurrentGatt(gatt)) return@dispatchGattCallback
            flipperSender.handleCharacteristicWrite(gatt, characteristic, status)
        }


        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = dispatchGattCallback {
            if (!isCurrentGatt(gatt)) return@dispatchGattCallback

            if (status == BluetoothGatt.GATT_SUCCESS) {
                flipperSender.setAttMtu(mtu)
                Log.d(TAG, "ATT MTU changed, new value: $mtu")
            }
        }
    }

    private fun dispatchRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
        dispatchGattCallback { handleCharacteristicRead(gatt, characteristic, value, status) }

    private fun dispatchChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
        dispatchGattCallback { handleCharacteristicChanged(gatt, characteristic, value) }

    // ---------- GATT initialization ---------- //

    @SuppressLint("MissingPermission")
    private fun discoverServices(gatt: BluetoothGatt) {
        try {
            if (!gatt.discoverServices()) {
                failGatt(gatt, BleFailure.ServiceDiscoveryFailed(-1))
            }
        } catch (_: SecurityException) {
            failGatt(gatt, BleFailure.PermissionMissing)
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeNext(gatt: BluetoothGatt) {
        try {
            gattManager
                .processNextSubscription(gatt)
                ?.let { error ->failGatt(gatt, error) }
        } catch (_: SecurityException) {
            failGatt(gatt, BleFailure.PermissionMissing)
        }
    }

    private fun handleCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (!isCurrentGatt(gatt)) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failGatt(gatt, BleFailure.RPCError)
            return
        }

        when (characteristic.uuid) {
            FlipperGattUuid.Characteristic.RPC_STATUS.uuid -> RpcStatus.fromBytes(value)?.let {
                saveRpcState(it)
                if (!gattManager.readFlowControl(gatt)) {
                    failGatt(gatt, BleFailure.RPCReadError("FlowControl"))
                }
            } ?: Log.e(TAG, "Cannot get RPC status from ${value.contentToString()}")
            FlipperGattUuid.Characteristic.FLOW_CONTROL.uuid -> handleFlowControlValue(value)
        }
    }

    private fun handleCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (!isCurrentGatt(gatt)) return

        when (characteristic.uuid) {
            FlipperGattUuid.Characteristic.TX.uuid -> handleIncoming(gatt, value)
            FlipperGattUuid.Characteristic.RPC_STATUS.uuid ->
                RpcStatus.fromBytes(value)?.let(::saveRpcState)
                    ?: Log.e(TAG, "Cannot get RPC status from ${value.contentToString()}")
            FlipperGattUuid.Characteristic.FLOW_CONTROL.uuid -> handleFlowControlValue(value)
        }
    }

    private fun onEnableAllCCCD(gatt: BluetoothGatt, device: BleDevice) {
        Log.d(TAG, "All CCCD subscriptions enabled")
        connectTimeout?.cancel()
        connectTimeout = null
        saveConnectionState(BleConnectionState.Connected(device))

        // extra read RPC_STATUS characteristic, see result in onCharacteristicRead()
        try {
            if (!gattManager.readRpcStatus(gatt)) {
                failGatt(gatt, BleFailure.RPCReadError("RpcStatus"))
            }
        } catch (_: SecurityException) {
            failGatt(gatt, BleFailure.PermissionMissing)
        }
    }

    private fun handleFlowControlValue(value: ByteArray) {
        flipperSender.setAvailableBytes(value.toInt(ByteOrder.BIG_ENDIAN) ?: 0)
        Log.d(TAG, "FLOW_CONTROL: ${value.toInt(ByteOrder.BIG_ENDIAN) ?: 0} bytes")

        // TODO(transport): notify sender and update
        // the active transmission credit window.
    }

    private fun handleIncoming(gatt: BluetoothGatt, value: ByteArray) {
        // forward TX bytes to incoming Flow/Channel.
        val accepted = flipperReceiver.onReceive(value)
        if (!accepted) failGatt(gatt, BleFailure.RPCError)



    }

    override fun nextCommandId(): Int = commandIds.getAndIncrement()

    override suspend fun sendMessage(message: FlipperProto.Main): BleFailure? =
        sendRpcMessage(message)

    private suspend fun sendRpcMessage(message: FlipperProto.Main, expectedGatt: BluetoothGatt? = null): BleFailure? {
        val frame = withContext(Dispatchers.Default) { rpcFramer.encode(message) }
        return flipperSender.sendRxMessage(frame, expectedGatt)
    }

    override suspend fun getDeviceInfo(timeoutMillis: Long): DeviceInfo = withContext(Dispatchers.Main.immediate) {
        val gatt = currentGatt
        if (closed || gatt == null || connection.value !is BleConnectionState.Connected || rpcStatus.value != RpcStatus.ACTIVE) {
            throw FlipperRpcException(FlipperRpcException.Reason.NOT_READY, "Flipper RPC is not ready")
        }
        rpcClient.getDeviceInfo(timeoutMillis) { request -> sendRpcMessage(request, gatt) }
    }

    private fun startRpcReceiving(gatt: BluetoothGatt) {
        if (!isCurrentGatt(gatt)) return
        rpcReceiveJob?.cancel()
        rpcReceiveJob = scope.launch {
            try {
                rpcFramer.decode(flipperReceiver.incomingChunks)
                    .flowOn(Dispatchers.Default)
                    .collect { message ->
                        Log.d(TAG, "RPC received: commandId=" + message.commandId + ", content=" + message.contentCase + ", status=" + message.commandStatus + ", hasNext=" + message.hasNext)
                        rpcClient.onMessage(message)
                        _messages.tryEmit(message)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "RPC decoding failed", e)
                failGatt(gatt, BleFailure.RPCError)
            }
        }
    }

    // ---------- GATT cleanup ---------- //

    private fun isCurrentGatt(gatt: BluetoothGatt): Boolean = currentGatt === gatt

    @SuppressLint("MissingPermission")
    private fun closeCurrentGatt() {
        connectTimeout?.cancel()
        connectTimeout = null

        val gatt = currentGatt
        currentGatt = null
        rpcClient.onSessionClosed()

        rpcReceiveJob?.cancel()
        rpcReceiveJob = null
        flipperReceiver.close()
        gatt?.let(flipperSender::onGattClosed)

        saveRpcState(RpcStatus.NOT_ACTIVE)
        flipperSender.setAvailableBytes(0)
        flipperSender.setAttMtu(DEFAULT_ATT_MTU)

        gattManager.resetSubscriptions()

        try {
            gatt?.disconnect()
        } catch (e: SecurityException) {
            Log.w(TAG, "Disconnect permission missing", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "GATT already unavailable", e)
        } finally {
            gatt?.close()
        }
    }

    private fun failGatt(gatt: BluetoothGatt, reason: BleFailure) {
        if (!isCurrentGatt(gatt)) return
        Log.w(TAG, "GATT failure: $reason")
        closeCurrentGatt()
        saveConnectionState(BleConnectionState.Failed(reason))
    }

    private fun fail(reason: BleFailure) {
        Log.w(TAG, "BLE failure: $reason")
        blePairingManager.clearBondWaiting()
        closeCurrentGatt()
        saveConnectionState(BleConnectionState.Failed(reason))
    }

    companion object {
        private const val TAG = "Flipper"
        private const val CONNECTION_TIMEOUT_MS = 25_000L
        const val DEFAULT_ATT_MTU = 23
        const val ATT_WRITE_OVERHEAD = 3

        // see https://github.com/flipperdevices/flipperzero-firmware/blob/dev/targets/f7/ble_glue/services/serial_service.h
        // BLE_SVC_SERIAL_DATA_LEN_MAX and BLE_SVC_SERIAL_CHAR_VALUE_LEN_MAX
        const val FLIPPER_RX_MAX_SIZE = 486
        const val FLIPPER_TX_MAX_SIZE = 243
    }
}
