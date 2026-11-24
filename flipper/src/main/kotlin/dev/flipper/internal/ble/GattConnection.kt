package dev.flipper.internal.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattConnectionSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresPermission

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BluetoothDevice.connectGattCompat(
    context: Context,
    callback: BluetoothGattCallback,
    handler: Handler,
): BluetoothGatt? = if (Build.VERSION.SDK_INT >= 37) {
    val settings = BluetoothGattConnectionSettings.Builder()
        .setTransport(BluetoothDevice.TRANSPORT_LE)
        .setAutoConnectEnabled(false)
        // Preserve the existing explicit MTU policy; don't add an initialization GATT operation.
        .setAutomaticMtuEnabled(false)
        .build()
    connectGatt(settings, context.mainExecutor, callback)
} else {
    connectGattLegacy(context, callback, handler)
}

// Android 6–16 has no settings/executor overload. Keep deprecation suppression at this boundary.
@Suppress("DEPRECATION")
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun BluetoothDevice.connectGattLegacy(
    context: Context,
    callback: BluetoothGattCallback,
    handler: Handler,
): BluetoothGatt? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
} else {
    connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
}
