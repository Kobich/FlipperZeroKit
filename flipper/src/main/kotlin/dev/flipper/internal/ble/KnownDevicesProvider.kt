package dev.flipper.internal.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import dev.flipper.api.model.BleDevice
import dev.flipper.api.model.BleFailure
import dev.flipper.api.model.KnownBleDevice
import dev.flipper.api.model.KnownDevicesResult
import dev.flipper.internal.permissions.BluetoothPermissionChecker

/** Read-only snapshot. Does not scan, unpair, connect, disconnect or toggle Bluetooth. */
internal class KnownDevicesProvider(
    private val context: Context,
    private val permissionChecker: BluetoothPermissionChecker,
) {
    fun get(): KnownDevicesResult {
        var failure: BleFailure? = null
        if (!permissionChecker.checkBluetoothReady(scan = false) { failure = it }) {
            return KnownDevicesResult.Failed(checkNotNull(failure))
        }
        return try {
            val manager = context.getSystemService(BluetoothManager::class.java)
                ?: return KnownDevicesResult.Failed(BleFailure.BluetoothUnsupported)
            val adapter = manager.adapter
                ?: return KnownDevicesResult.Failed(BleFailure.BluetoothUnsupported)
            val bonded = adapter.bondedDevices.orEmpty().filter { it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC }
            val connected = manager.getConnectedDevices(BluetoothProfile.GATT)
            val bondedAddresses = bonded.mapTo(hashSetOf()) { it.address }
            val connectedAddresses = connected.mapTo(hashSetOf()) { it.address }
            val devices = (bonded + connected).distinctBy { it.address }.map { remote ->
                KnownBleDevice(
                    device = BleDevice(remote.name?.takeIf { it.isNotBlank() } ?: remote.address, remote.address),
                    isBonded = remote.address in bondedAddresses,
                    isConnectedToSystem = remote.address in connectedAddresses,
                )
            }.sortedWith(compareByDescending<KnownBleDevice> { it.isConnectedToSystem }
                .thenBy { it.device.name.lowercase() }.thenBy { it.device.address })
            KnownDevicesResult.Available(devices)
        } catch (_: SecurityException) {
            // Also covers revocation between checkPermission and a platform getter.
            KnownDevicesResult.Failed(BleFailure.PermissionMissing)
        } catch (error: IllegalStateException) {
            KnownDevicesResult.Failed(BleFailure.Unknown(error.message))
        }
    }
}
