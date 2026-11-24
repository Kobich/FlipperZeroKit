package dev.flipper.api

import dev.flipper.internal.permissions.BluetoothPermissionChecker

/** Permissions to request in the host before scanning. Each access returns a new array. */
object FlipperPermissions {
    val requiredPermissions: Array<String>
        get() = BluetoothPermissionChecker.requiredPermissions.copyOf()
}
