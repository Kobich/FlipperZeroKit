package dev.flipper.api

import android.Manifest
import android.os.Build
import dev.flipper.internal.permissions.BluetoothPermissionChecker

/** Permissions to request in the host before scanning. Each access returns a new array. */
object FlipperPermissions {
    /** Runtime permissions for known-device lookup and direct connection, without discovery.
     * On API 23–30 BLUETOOTH is a normal manifest permission; no runtime location is needed here.
     */
    val connectPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    val requiredPermissions: Array<String>
        get() = BluetoothPermissionChecker.requiredPermissions.copyOf()
}
