package dev.flipper.api.model

data class BleDevice(
    val name: String,
    val address: String,
    /** Null when no scan measurement is available (saved/system-connected device). */
    val rssi: Int? = null,
)
