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
import dev.flipper.internal.ble.connectGattCompat
import dev.flipper.internal.ble.KnownDevicesProvider
import dev.flipper.api.model.KnownDevicesResult
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
import dev.flipper.internal.rpc.finalizationTimeout
import dev.flipper.internal.rpc.isOwnedUploadTemp
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import dev.flipper.api.model.PendingFileCleanup
import dev.flipper.internal.rpc.PendingCleanupStore
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.security.MessageDigest
import dev.flipper.api.model.PowerInfo
import dev.flipper.api.model.StorageEntry
import dev.flipper.api.model.StorageInfo
import dev.flipper.api.model.TransferProgress
import dev.flipper.api.model.TransferResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Typed operations are routed by command ID and bound to the current GATT session.
 */
internal class DefaultFlipper(
    private val context: Context,
    private val permissionChecker: BluetoothPermissionChecker
) : Flipper {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gattManager = GattManager()
    private var closed = false
    private var connectionChoice = 0L
    private val _recovering = MutableStateFlow(false)
    override val isRecovering = _recovering.asStateFlow()
    private val cleanupStore = PendingCleanupStore(context)
    private val _cleanup = MutableStateFlow(cleanupStore.load())
    override val pendingFileCleanup = _cleanup.asStateFlow()

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
    private var mtuDiscoveryJob: Job? = null
    // Accessed on Main, including when the screen copies diagnostics.
    private val connectionEvents = ArrayDeque<String>()

    fun recentConnectionEvents(): List<String> = connectionEvents.toList()

    internal fun recordConnectionEvent(event: String) {
        if (connectionEvents.size == 96) connectionEvents.removeFirst()
        connectionEvents.addLast("[${android.os.SystemClock.elapsedRealtime()} ms] $event")
        Log.d(TAG, event)
    }

    private val _connection = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    override val connection = _connection.asStateFlow()

    private val _rpcStatus = MutableStateFlow(RpcStatus.NOT_ACTIVE)
    override val rpcStatus = _rpcStatus.asStateFlow()
    private val transportRpcStatus = MutableStateFlow(RpcStatus.NOT_ACTIVE)
    private var reportedRpcStatus: RpcStatus? = null
    private var initialFlowControlRead = false
    private var rpcProbeJob: Job? = null

    // ---------- Data exchanging ---------- //

    @Volatile
    private var currentGatt: BluetoothGatt? = null

    private val flipperReceiver = BleReceiver()

    private val flipperSender = BleSender(
        getGatt = { currentGatt },
        isBtConnected = connection,
        rpcSessionStatus = transportRpcStatus,
        onWriteFail = { gatt, detail -> failGatt(gatt, BleFailure.RPCError, detail) }
    )


    private val rpcFramer = RpcMessageFramer()
    private val commandIds = AtomicInteger(1)
    private val rpcClient = RpcClient(nextCommandId = ::nextCommandId)
    private val storageMutex = Mutex()
    private val _messages = MutableSharedFlow<FlipperProto.Main>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val messages = _messages.asSharedFlow()
    private var rpcReceiveJob: Job? = null

    // ---------- Public API ---------- //

    override suspend fun getKnownDevices(): KnownDevicesResult = withContext(Dispatchers.Main.immediate) {
        check(!closed) { "Flipper is closed" }
        withContext(Dispatchers.IO) { KnownDevicesProvider(context, permissionChecker).get() }
    }

    override suspend fun startScan() {
        withContext(Dispatchers.Main) {
            connectionChoice++
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
        withContext(Dispatchers.Main) { connectOnMain(device, direct = false) }
    }

    private fun connectOnMain(device: BleDevice, direct: Boolean) {
        connectionChoice++
        check(!closed) { "Flipper is closed" }
        bleScanner.stop()
        blePairingManager.clearBondWaiting()
        closeCurrentGatt()
        recordConnectionEvent("Connect to ${device.name} (${device.address})")

        if (!permissionChecker.checkBluetoothReady(scan = false, onFail = ::fail)) return
        blePairingManager.connect(device, direct)
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.Main) {
            connectionChoice++
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

        if (value == RpcStatus.ACTIVE) {
            connectTimeout?.cancel()
            connectTimeout = null
            scope.launch { try { cleanupPendingFiles() } catch (error: Exception) { Log.w(TAG, "Deferred cleanup pending", error) } }
        }
    }


    override suspend fun close() {
        withContext(Dispatchers.Main) {
            if (closed) return@withContext
            closed = true
            connectionChoice++
            bleScanner.stop()
            blePairingManager.clearBondWaiting()
            closeCurrentGatt()
            saveConnectionState(BleConnectionState.Disconnected)
            scope.cancel()
        }
    }

    // ---------- GATT connection ---------- //

    @SuppressLint("MissingPermission")
    private fun startGattConnection(remote: BluetoothDevice, device: BleDevice, autoConnect: Boolean) {
        if (!permissionChecker.checkBluetoothReady(scan = false, onFail = ::fail)) return

        saveConnectionState(BleConnectionState.Connecting(device))

        try {
            val callback = createGattCallback(device)
            recordConnectionEvent("Open GATT (autoConnect=$autoConnect)")
            val gatt = remote.connectGattCompat(context, callback, mainHandler, autoConnect)

            if (gatt == null) {
                fail(BleFailure.ConnectionFailed(-1))
                return
            }

            currentGatt = gatt

            connectTimeout = scope.launch {
                val timeout = if (autoConnect) BONDED_CONNECTION_TIMEOUT_MS else CONNECTION_TIMEOUT_MS
                delay(timeout.milliseconds)
                if (isCurrentGatt(gatt) && _connection.value is BleConnectionState.Connecting) {
                    failGatt(gatt, BleFailure.TimedOut, "GATT setup timed out after $timeout ms")
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
            recordConnectionEvent("GATT link callback: status=$status, state=$newState")

            when {
                status != BluetoothGatt.GATT_SUCCESS ->
                    failGatt(gatt, BleFailure.ConnectionFailed(status))

                newState == BluetoothProfile.STATE_CONNECTED -> requestMtuBeforeDiscovery(gatt)
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
            if (descriptor.uuid != FlipperGattUuid.BtSigConst.CCCD) return@dispatchGattCallback

            if (status != BluetoothGatt.GATT_SUCCESS) {
                failGatt(gatt, BleFailure.EnableCCCDProblem,
                    "CCCD write for ${descriptor.characteristic.uuid}: GATT status=$status")
                return@dispatchGattCallback
            }

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

        // Required by the platform on API 23–32; newer Android uses the value-parameter overload.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            dispatchRead(gatt, characteristic, characteristic.value?.copyOf() ?: byteArrayOf(), status)

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            dispatchRead(gatt, characteristic, value.copyOf(), status)

        // Required by the platform on API 23–32; newer Android uses the value-parameter overload.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
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
            recordConnectionEvent("MTU callback: status=$status, mtu=$mtu")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                flipperSender.setAttMtu(mtu)
            }
            mtuDiscoveryJob?.let {
                it.cancel()
                mtuDiscoveryJob = null
                discoverServices(gatt)
            }
        }
    }

    private fun dispatchRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
        dispatchGattCallback { handleCharacteristicRead(gatt, characteristic, value, status) }

    private fun dispatchChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
        dispatchGattCallback { handleCharacteristicChanged(gatt, characteristic, value) }

    // ---------- GATT initialization ---------- //

    @SuppressLint("MissingPermission")
    private fun requestMtuBeforeDiscovery(gatt: BluetoothGatt) {
        try {
            if (!gatt.requestMtu(REQUESTED_ATT_MTU)) {
                recordConnectionEvent("MTU request rejected, continuing with current MTU")
                discoverServices(gatt)
                return
            }
            mtuDiscoveryJob = scope.launch {
                delay(MTU_CALLBACK_TIMEOUT_MS)
                mtuDiscoveryJob = null
                if (isCurrentGatt(gatt)) {
                    recordConnectionEvent("No MTU callback, continuing with current MTU")
                    discoverServices(gatt)
                }
            }
        } catch (_: SecurityException) {
            failGatt(gatt, BleFailure.PermissionMissing)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(gatt: BluetoothGatt) {
        recordConnectionEvent("Discover GATT services")
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
        val name = when (characteristic.uuid) {
            FlipperGattUuid.Characteristic.RPC_STATUS.uuid -> "RPC_STATUS"
            FlipperGattUuid.Characteristic.FLOW_CONTROL.uuid -> "FLOW_CONTROL"
            else -> return
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failGatt(gatt, BleFailure.RPCReadError("$name: GATT status=$status"))
            return
        }

        when (characteristic.uuid) {
            FlipperGattUuid.Characteristic.RPC_STATUS.uuid -> {
                // A notification already received during setup takes precedence over the initial read.
                if (reportedRpcStatus == null) reportedRpcStatus = RpcStatus.fromBytes(value)
                recordConnectionEvent("Read RPC_STATUS: $reportedRpcStatus")
                try {
                    if (!gattManager.readFlowControl(gatt)) {
                        failGatt(gatt, BleFailure.RPCReadError("FlowControl"))
                    }
                } catch (_: SecurityException) {
                    failGatt(gatt, BleFailure.PermissionMissing)
                }
            }
            FlipperGattUuid.Characteristic.FLOW_CONTROL.uuid -> {
                handleFlowControlValue(value)
                recordConnectionEvent("Read FLOW_CONTROL: ${value.toInt(ByteOrder.BIG_ENDIAN)} bytes")
                initialFlowControlRead = true
                verifyRpcSession(gatt)
            }
        }
    }

    private fun handleCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (!isCurrentGatt(gatt)) return

        when (characteristic.uuid) {
            FlipperGattUuid.Characteristic.TX.uuid -> handleIncoming(gatt, value)
            FlipperGattUuid.Characteristic.RPC_STATUS.uuid -> {
                val status = RpcStatus.fromBytes(value) ?: return
                recordConnectionEvent("RPC_STATUS notification: $status")
                reportedRpcStatus = status
                if (status != RpcStatus.ACTIVE && transportRpcStatus.value == RpcStatus.ACTIVE) {
                    failGatt(gatt, BleFailure.RPCSessionInactive)
                } else {
                    verifyRpcSession(gatt)
                }
            }
            FlipperGattUuid.Characteristic.FLOW_CONTROL.uuid -> handleFlowControlValue(value)
        }
    }

    private fun verifyRpcSession(gatt: BluetoothGatt) {
        if (!initialFlowControlRead || reportedRpcStatus != RpcStatus.ACTIVE || rpcProbeJob != null) return

        // Only the probe can send before public RPC readiness is confirmed.
        recordConnectionEvent("Start RPC receiver: ignored ${flipperReceiver.ignoredSetupFragments} TX fragments during setup")
        startRpcReceiving(gatt)
        transportRpcStatus.value = RpcStatus.ACTIVE
        rpcProbeJob = scope.launch {
            recordConnectionEvent("RPC readiness Ping sent to transport")
            try {
                rpcClient.ping(RPC_PROBE_TIMEOUT_MS) { sendRpcMessage(it, gatt) }
                if (isCurrentGatt(gatt)) {
                    recordConnectionEvent("RPC readiness PingResponse received")
                    saveRpcState(RpcStatus.ACTIVE)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: FlipperRpcException) {
                Log.w(TAG, "RPC readiness probe failed", error)
                failGatt(gatt, BleFailure.RPCSessionInactive, "Readiness Ping: ${error.reason}, ${error.message}")
            }
        }
    }

    private fun onEnableAllCCCD(gatt: BluetoothGatt, device: BleDevice) {
        recordConnectionEvent("GATT subscriptions complete")
        reportedRpcStatus = null
        connectTimeout?.cancel()
        connectTimeout = scope.launch {
            delay(15_000)
            if (isCurrentGatt(gatt)) failGatt(gatt, BleFailure.RPCSessionInactive,
                "RPC readiness timed out (RPC_STATUS=$reportedRpcStatus, flow control read=$initialFlowControlRead)")
        }
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
        val accepted = flipperReceiver.onReceive(value)
        if (!accepted) failGatt(gatt, BleFailure.RPCError, "TX receiver rejected a fragment")
    }

    override fun nextCommandId(): Int = commandIds.getAndIncrement()

    override suspend fun sendMessage(message: FlipperProto.Main): BleFailure? = withContext(Dispatchers.Main.immediate) {
        if (!permissionChecker.hasConnectPermission()) return@withContext BleFailure.PermissionMissing
        val gatt = currentGatt ?: return@withContext BleFailure.RPCError
        val ready = withTimeoutOrNull(RAW_RPC_READY_TIMEOUT_MS) {
            combine(connection, rpcStatus) { state, rpc -> state to rpc }.first { (state, rpc) ->
                !isCurrentGatt(gatt) || state !is BleConnectionState.Connected || rpc == RpcStatus.ACTIVE
            }.let { (state, rpc) -> state is BleConnectionState.Connected && rpc == RpcStatus.ACTIVE }
        }
        if (ready != true || !isCurrentGatt(gatt)) return@withContext BleFailure.RPCError
        sendRpcMessage(message, gatt)
    }

    private suspend fun sendRpcMessage(message: FlipperProto.Main, expectedGatt: BluetoothGatt): BleFailure? {
        if (!permissionChecker.hasConnectPermission()) return BleFailure.PermissionMissing
        val frame = withContext(Dispatchers.Default) { rpcFramer.encode(message) }
        return try {
            flipperSender.sendRxMessage(frame, expectedGatt)
        } catch (_: SecurityException) {
            BleFailure.PermissionMissing
        }
    }

    private fun requireRpcGatt(): BluetoothGatt {
        val gatt = currentGatt
        if (closed || gatt == null || connection.value !is BleConnectionState.Connected || rpcStatus.value != RpcStatus.ACTIVE) {
            throw FlipperRpcException(FlipperRpcException.Reason.NOT_READY, "Flipper RPC is not ready")
        }
        return gatt
    }

    override suspend fun getDeviceInfo(timeoutMillis: Long): DeviceInfo = withContext(Dispatchers.Main.immediate) {
        val gatt = requireRpcGatt()
        rpcClient.getDeviceInfo(timeoutMillis) { sendRpcMessage(it, gatt) }
    }

    override suspend fun getPowerInfo(timeoutMillis: Long): PowerInfo = withContext(Dispatchers.Main.immediate) {
        val gatt = requireRpcGatt()
        rpcClient.getPowerInfo(timeoutMillis) { sendRpcMessage(it, gatt) }
    }

    override suspend fun getStorageInfo(path: String, timeoutMillis: Long): StorageInfo = withContext(Dispatchers.Main.immediate) {
        checkPath(path)
        require(timeoutMillis > 0)
        val gatt = requireRpcGatt()
        withTimeoutOrNull(timeoutMillis) {
            storageMutex.withLock {
                if (requireRpcGatt() !== gatt) {
                    throw FlipperRpcException(FlipperRpcException.Reason.SESSION_CLOSED, "RPC session changed")
                }
                rpcClient.getStorageInfo(path, timeoutMillis) { sendRpcMessage(it, gatt) }
            }
        } ?: throw FlipperRpcException(FlipperRpcException.Reason.TIMEOUT, "Storage info timed out after $timeoutMillis ms")
    }

    override suspend fun ping(timeoutMillis: Long) = withContext(Dispatchers.Main.immediate) {
        val gatt = requireRpcGatt()
        rpcClient.ping(timeoutMillis) { sendRpcMessage(it, gatt) }
    }

    private fun checkPath(path: String) {
        require(path.startsWith('/') && path.all { it.code in 32..126 } &&
            path.split('/').none { it == "." || it == ".." } && !path.contains("//") &&
            (path == "/" || !path.endsWith('/'))) { "Use an absolute ASCII path without ./ or ../ segments" }
    }

    override suspend fun listFiles(path: String, timeoutMillis: Long, expectedDeviceAddress: String?): List<StorageEntry> =
        withContext(Dispatchers.Main.immediate) {
            checkPath(path)
            storageMutex.withLock {
                val gatt = requireRpcGatt()
                val address = (connection.value as? BleConnectionState.Connected)?.device?.address
                if (expectedDeviceAddress != null && !expectedDeviceAddress.equals(address, ignoreCase = true))
                    throw IOException("The connected Flipper changed. Refresh the file list.")
                rpcClient.listFiles(path, timeoutMillis) { sendRpcMessage(it, gatt) }
            }
        }

    private fun abortTransfer(gatt: BluetoothGatt) {
        if (isCurrentGatt(gatt)) {
            closeCurrentGatt()
            saveConnectionState(BleConnectionState.Disconnected)
        }
    }

    private suspend fun rememberCleanup(item: PendingFileCleanup) {
        val updated = (_cleanup.value + item).distinct()
        cleanupStore.save(updated)
        _cleanup.value = updated
    }

    private suspend fun forgetCleanup(item: PendingFileCleanup) {
        val updated = _cleanup.value - item
        cleanupStore.save(updated)
        _cleanup.value = updated
    }

    private suspend fun cleanupForGatt(gatt: BluetoothGatt, address: String) {
        for (item in _cleanup.value.filter { it.deviceAddress.equals(address, ignoreCase = true) && isOwnedUploadTemp(it.path) }) {
            rpcClient.deleteFile(item.path) { sendRpcMessage(it, gatt) }
            forgetCleanup(item)
        }
    }

    override suspend fun cleanupPendingFiles() = withContext(Dispatchers.Main.immediate) {
        storageMutex.withLock {
            val device = (connection.value as? BleConnectionState.Connected)?.device ?: return@withLock
            if (rpcStatus.value == RpcStatus.ACTIVE) cleanupForGatt(requireRpcGatt(), device.address)
        }
    }

    /** A user choosing another device/disconnecting always wins over operation recovery. */
    private suspend fun recoverAfterTransfer(gatt: BluetoothGatt, device: BleDevice, choice: Long, reset: Boolean,
        direct: Boolean = false) {
        withContext(NonCancellable) {
            _recovering.value = true
            try {
                withTimeout(55_000) {
                    if (closed || connectionChoice != choice) return@withTimeout
                    if (!reset && currentGatt === gatt && rpcStatus.value == RpcStatus.ACTIVE) {
                        try { cleanupForGatt(gatt, device.address); return@withTimeout }
                        catch (error: Exception) { Log.w(TAG, "Soft cleanup failed; resetting session", error) }
                    }
                    if (connectionChoice != choice) return@withTimeout
                    recordConnectionEvent("Transfer recovery: close RPC session and wait before reconnecting")
                    abortTransfer(gatt)
                    delay(TRANSFER_RECONNECT_DELAY_MS)
                    val reconnectChoice = withContext(Dispatchers.Main) {
                        if (closed || connectionChoice != choice) return@withContext null
                        recordConnectionEvent("Transfer recovery: reconnecting")
                        connectOnMain(device, direct)
                        connectionChoice
                    } ?: return@withTimeout
                    combine(connection, rpcStatus) { state, rpc -> state to rpc }.first { (state, rpc) ->
                        connectionChoice != reconnectChoice || state is BleConnectionState.Failed || state == BleConnectionState.Disconnected ||
                            (state is BleConnectionState.Connected && rpc == RpcStatus.ACTIVE)
                    }
                    val ready = connection.value as? BleConnectionState.Connected
                    if (connectionChoice == reconnectChoice && ready?.device?.address == device.address && rpcStatus.value == RpcStatus.ACTIVE) {
                        recordConnectionEvent("Transfer recovery: RPC ready")
                        cleanupForGatt(requireRpcGatt(), device.address)
                    } else if (connectionChoice == reconnectChoice) {
                        recordConnectionEvent("Transfer recovery: ended with ${connection.value}")
                    }
                }
            } catch (error: Exception) {
                recordConnectionEvent("Transfer recovery: ${error.message ?: error.javaClass.simpleName}")
                Log.w(TAG, "Recovery/partial-file cleanup deferred", error)
            }
            finally { _recovering.value = false }
        }
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    override suspend fun downloadFile(path: String, destination: OutputStream, timeoutMillis: Long, expectedDeviceAddress: String?,
        onProgress: (TransferProgress) -> Unit): TransferResult = withContext(Dispatchers.Main.immediate) {
        checkPath(path)
        require(timeoutMillis >= 0)
        storageMutex.withLock {
            val gatt = requireRpcGatt()
            val device = (connection.value as BleConnectionState.Connected).device
            if (expectedDeviceAddress != null && !device.address.equals(expectedDeviceAddress, ignoreCase = true))
                throw IOException("The connected Flipper changed. Select the file again.")
            val choice = connectionChoice
            val send: suspend (FlipperProto.Main) -> BleFailure? = { sendRpcMessage(it, gatt) }
            val file = rpcClient.stat(path, 30_000L, send)
            require(!file.isDirectory) { "Select a file, not a directory" }
            val digest = MessageDigest.getInstance("MD5")
            var bytes = 0L
            var readFinished = false
            try {
                onProgress(TransferProgress(0, file.sizeBytes))
                rpcClient.readFile(path, timeoutMillis, send, onCanceledReadDrained = { readFinished = true }) { chunk ->
                    runInterruptible(Dispatchers.IO) { destination.write(chunk); digest.update(chunk) }
                    bytes += chunk.size
                    if (bytes > file.sizeBytes) throw IOException("File grew during download")
                    onProgress(TransferProgress(bytes, file.sizeBytes))
                }
                readFinished = true
                runInterruptible(Dispatchers.IO) { destination.flush() }
                if (bytes != file.sizeBytes) throw IOException("Size mismatch: expected "+file.sizeBytes+", got "+bytes)
                val localMd5 = digest.hex()
                if (localMd5 != rpcClient.md5(path, finalizationTimeout(file.sizeBytes), send)) throw IOException("MD5 mismatch after download")
                TransferResult(bytes, localMd5, path)
            } catch (error: Exception) {
                if (error is CancellationException && readFinished && isCurrentGatt(gatt) && rpcStatus.value == RpcStatus.ACTIVE) {
                    recordConnectionEvent("Download canceled: read stream ended, keeping RPC session")
                } else {
                    if (error is CancellationException && !readFinished) recordConnectionEvent("Download canceled: read stream did not drain")
                    recoverAfterTransfer(gatt, device, choice, reset = true, direct = true)
                }
                throw error
            }
        }
    }

    override suspend fun uploadFile(path: String, source: InputStream, sizeBytes: Long?, timeoutMillis: Long, expectedDeviceAddress: String?,
        onProgress: (TransferProgress) -> Unit): TransferResult = withContext(Dispatchers.Main.immediate) {
        checkPath(path)
        require(path != "/" && timeoutMillis >= 0 && (sizeBytes == null || sizeBytes >= 0))
        storageMutex.withLock {
            val gatt = requireRpcGatt()
            val device = (connection.value as BleConnectionState.Connected).device
            if (expectedDeviceAddress != null && !device.address.equals(expectedDeviceAddress, ignoreCase = true))
                throw IOException("The connected Flipper changed. Select the file again.")
            val choice = connectionChoice
            val send: suspend (FlipperProto.Main) -> BleFailure? = { sendRpcMessage(it, gatt) }
            dev.flipper.internal.rpc.RemoteUpload(rpcClient, send,
                blockSend = { request -> withContext(NonCancellable) { withTimeout(45_000) { send(request) } } },
                remember = ::rememberCleanup, forget = ::forgetCleanup,
                recover = { recoverAfterTransfer(gatt, device, choice,
                    reset = currentGatt !== gatt || rpcStatus.value != RpcStatus.ACTIVE) },
            ).execute(device.address, path, source, sizeBytes, timeoutMillis, onProgress)
        }
    }

    private fun startRpcReceiving(gatt: BluetoothGatt) {
        if (!isCurrentGatt(gatt)) return
        val chunks = flipperReceiver.start()
        rpcReceiveJob = scope.launch {
            try {
                rpcFramer.decode(chunks)
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
                failGatt(gatt, BleFailure.RPCError, "RPC frame decode: ${e.javaClass.simpleName}")
            }
        }
    }

    // ---------- GATT cleanup ---------- //

    private fun isCurrentGatt(gatt: BluetoothGatt): Boolean = currentGatt === gatt

    @SuppressLint("MissingPermission")
    private fun closeCurrentGatt() {
        connectTimeout?.cancel()
        connectTimeout = null
        mtuDiscoveryJob?.cancel()
        mtuDiscoveryJob = null

        val gatt = currentGatt
        currentGatt = null
        rpcProbeJob?.cancel()
        rpcProbeJob = null
        reportedRpcStatus = null
        initialFlowControlRead = false
        transportRpcStatus.value = RpcStatus.NOT_ACTIVE
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
            try {
                gatt?.close()
            } catch (e: SecurityException) {
                Log.w(TAG, "GATT close permission missing", e)
            }
        }
    }

    private fun failGatt(gatt: BluetoothGatt, reason: BleFailure, detail: String = reason.toString()) {
        if (!isCurrentGatt(gatt)) return
        recordConnectionEvent("Close GATT: $detail")
        closeCurrentGatt()
        saveConnectionState(BleConnectionState.Failed(reason))
    }

    private fun fail(reason: BleFailure) {
        recordConnectionEvent("BLE failure: $reason")
        blePairingManager.clearBondWaiting()
        closeCurrentGatt()
        saveConnectionState(BleConnectionState.Failed(reason))
    }

    companion object {
        private const val TAG = "Flipper"
        private const val CONNECTION_TIMEOUT_MS = 25_000L
        private const val BONDED_CONNECTION_TIMEOUT_MS = 30_000L
        private const val TRANSFER_RECONNECT_DELAY_MS = 1_500L
        private const val REQUESTED_ATT_MTU = 512
        private const val MTU_CALLBACK_TIMEOUT_MS = 3_000L
        private const val RPC_PROBE_TIMEOUT_MS = 10_000L
        private const val RAW_RPC_READY_TIMEOUT_MS = 5_000L
        const val DEFAULT_ATT_MTU = 23
        const val ATT_WRITE_OVERHEAD = 3

        // see https://github.com/flipperdevices/flipperzero-firmware/blob/dev/targets/f7/ble_glue/services/serial_service.h
        // BLE_SVC_SERIAL_DATA_LEN_MAX and BLE_SVC_SERIAL_CHAR_VALUE_LEN_MAX
        const val FLIPPER_RX_MAX_SIZE = 486
    }
}
