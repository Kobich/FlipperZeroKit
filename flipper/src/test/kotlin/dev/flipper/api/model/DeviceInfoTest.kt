package dev.flipper.api.model

import org.junit.Assert.*
import org.junit.Test

class DeviceInfoTest {
    @Test
    fun readsOfficialUnderscoreKeysAndPreservesUnknownFields() {
        val info = DeviceInfo(mapOf(
            "firmware_version" to "1.2.3", "firmware_branch" to "release", "firmware_build_date" to "25-11-2026",
            "hardware_ver" to "12", "hardware_model" to "Flipper Zero",
            "hardware_uid" to "ABC", "hardware_name" to "Flipper",
            "custom_key" to "custom_value",
        ))
        assertEquals("1.2.3", info.firmwareVersion)
        assertEquals("25-11-2026", info.firmwareBuildDate)
        assertEquals("release", info.firmwareBranch)
        assertEquals("12", info.hardwareVersion)
        assertEquals("Flipper Zero", info.hardwareModel)
        assertEquals("ABC", info.hardwareUid)
        assertEquals("Flipper", info.name)
        assertEquals("custom_value", info.fields["custom_key"])
    }

    @Test
    fun supportsDotAliasesAndTreatsMissingOrBlankFieldsAsUnknown() {
        val info = DeviceInfo(mapOf("firmware_version" to "", "firmware.version" to "custom",
            "firmware_build_date" to "", "firmware.build.date" to "2026-11-25", "hardware.uid" to "XYZ"))
        assertEquals("custom", info.firmwareVersion)
        assertEquals("2026-11-25", info.firmwareBuildDate)
        assertEquals("XYZ", info.hardwareUid)
        assertNull(info.hardwareVersion)
        assertNull(info.firmwareBranch)
        assertNull(info.name)
    }
}
