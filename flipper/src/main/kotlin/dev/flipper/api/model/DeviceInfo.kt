package dev.flipper.api.model

/** Complete DeviceInfo response. Missing firmware-specific fields remain null. */
data class DeviceInfo(val fields: Map<String, String>) {
    val firmwareVersion: String? get() = field("firmware_version", "firmware.version")
    val firmwareBranch: String? get() = field("firmware_branch", "firmware.branch.name", "firmware.branch")
    val hardwareVersion: String? get() = field("hardware_ver", "hardware.ver", "hardware.version")
    val hardwareModel: String? get() = field("hardware_model", "hardware.model")
    val hardwareUid: String? get() = field("hardware_uid", "hardware.uid")
    val name: String? get() = field("hardware_name", "hardware.name")

    private fun field(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { fields[it]?.takeIf(String::isNotBlank) }
}
