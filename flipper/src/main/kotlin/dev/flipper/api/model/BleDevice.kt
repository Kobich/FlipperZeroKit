package dev.flipper.api.model

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
)
