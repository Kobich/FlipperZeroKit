package dev.flipper.ui.internal.pages.device

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flipperdevices.protobuf.FlipperProto
import dev.flipper.R
import dev.flipper.api.model.BleDevice
import dev.flipper.api.model.FlipperRpcException
import dev.flipper.api.model.PowerInfo
import dev.flipper.api.model.StorageInfo
import dev.flipper.ui.internal.components.InfoPanel
import dev.flipper.ui.internal.components.InfoRow
import dev.flipper.ui.internal.components.rememberFileSizeContext

@Composable
internal fun DeviceOverview(
    device: BleDevice,
    status: String,
    details: DeviceDetails? = null,
    onShowAllInfo: (() -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    val sizeFormatContext = rememberFileSizeContext()
    val overview = details ?: DeviceDetails()
    val card = (overview.sdCard as? DeviceInfoState.Ready)?.data

    InfoPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            actions()
        }
        overview.pingMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        InfoRow(stringResource(R.string.flipper_firmware_label), overview.info.summaryValue { it.firmwareVersion })
        InfoRow(stringResource(R.string.flipper_build_date_label), overview.info.summaryValue { it.firmwareBuildDate })
        InfoRow(stringResource(R.string.flipper_battery_charge_label), overview.power.summaryValue { power ->
            power.chargePercent?.let { charge ->
                val label = if (power.chargeState == "charging") R.string.flipper_battery_charging else R.string.flipper_battery_percent
                stringResource(label, charge)
            }
        })
        HorizontalDivider()
        InfoRow(stringResource(R.string.flipper_sd_card_label), when (val sd = overview.sdCard) {
            DeviceInfoState.Loading -> null
            is DeviceInfoState.Failed -> storageError(sd.error)
            is DeviceInfoState.Ready -> stringResource(
                R.string.flipper_sd_card_usage,
                Formatter.formatShortFileSize(sizeFormatContext, sd.data.usedBytes),
                Formatter.formatShortFileSize(sizeFormatContext, sd.data.totalBytes),
            )
        })
        if (card != null) {
            if (card.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (card.usedBytes.toDouble() / card.totalBytes).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            InfoRow(stringResource(R.string.flipper_storage_free_label), Formatter.formatShortFileSize(sizeFormatContext, card.freeBytes))
        } else if (overview.sdCard == DeviceInfoState.Loading) {
            InfoRow(stringResource(R.string.flipper_storage_free_label), null)
        }
        if (onShowAllInfo != null) {
            TextButton(onClick = onShowAllInfo, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.flipper_all_info), Modifier.weight(1f))
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            }
        }
    }
}

@Composable
internal fun DeviceDetailsPage(
    device: BleDevice,
    details: DeviceDetails,
    onBack: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sizeFormatContext = rememberFileSizeContext()
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.flipper_back_to_overview))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.flipper_all_info), style = MaterialTheme.typography.titleLarge)
                Text(device.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onCopyDiagnostics) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.flipper_copy_diagnostics))
            }
        }
        HorizontalDivider()
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceDetailsPanels(device.address, details, sizeFormatContext)
        }
    }
}

@Composable
private fun DeviceDetailsPanels(address: String, details: DeviceDetails, sizeFormatContext: Context) {
    InfoPanel {
        Text(stringResource(R.string.flipper_device_details_label), style = MaterialTheme.typography.titleSmall)
        InfoRow(stringResource(R.string.flipper_device_address_label), address)
        when (val info = details.info) {
            DeviceInfoState.Loading -> InfoRow(stringResource(R.string.flipper_device_info_title), null)
            is DeviceInfoState.Ready -> RawInfoFields(info.data.fields)
            is DeviceInfoState.Failed -> Text(deviceInfoError(info.error), color = MaterialTheme.colorScheme.error)
        }
    }
    InfoPanel { PowerInfoFields(details.power) }
    InfoPanel {
        Text(stringResource(R.string.flipper_sd_card_label), style = MaterialTheme.typography.titleSmall)
        when (val sd = details.sdCard) {
            DeviceInfoState.Loading -> InfoRow(stringResource(R.string.flipper_storage_total_label), null)
            is DeviceInfoState.Ready -> CardCapacityFields(sizeFormatContext, sd.data)
            is DeviceInfoState.Failed -> Text(storageError(sd.error))
        }
    }
}

@Composable
private fun <T> DeviceInfoState<T>.summaryValue(value: @Composable (T) -> String?): String? = when (this) {
    DeviceInfoState.Loading -> null
    is DeviceInfoState.Ready -> value(data) ?: stringResource(R.string.flipper_value_unknown)
    is DeviceInfoState.Failed -> stringResource(R.string.flipper_value_unavailable)
}

@Composable
private fun CardCapacityFields(context: Context, card: StorageInfo) {
    InfoRow(stringResource(R.string.flipper_storage_total_label), Formatter.formatFileSize(context, card.totalBytes), "total_space")
    InfoRow(stringResource(R.string.flipper_storage_used_label), Formatter.formatFileSize(context, card.usedBytes))
    InfoRow(stringResource(R.string.flipper_storage_free_label), Formatter.formatFileSize(context, card.freeBytes), "free_space")
}

@Composable
private fun PowerInfoFields(power: DeviceInfoState<PowerInfo>) {
    Text(stringResource(R.string.flipper_power_details_label), style = MaterialTheme.typography.titleSmall)
    when (power) {
        DeviceInfoState.Loading -> InfoRow(stringResource(R.string.flipper_battery_charge_label), null)
        is DeviceInfoState.Ready -> RawInfoFields(power.data.fields)
        is DeviceInfoState.Failed -> Text(stringResource(R.string.flipper_power_info_failed), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun RawInfoFields(fields: Map<String, String>) {
    if (fields.isEmpty()) {
        Text(stringResource(R.string.flipper_value_unknown), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    fields.toSortedMap().forEach { (key, value) ->
        InfoRow(fieldLabel(key), value.ifEmpty { stringResource(R.string.flipper_value_empty) }, rawKey = key)
    }
}

@Composable
private fun fieldLabel(key: String): String {
    val label = when (key.replace('_', '.')) {
        "firmware.version" -> R.string.flipper_firmware_label
        "firmware.build.date" -> R.string.flipper_build_date_label
        "firmware.branch", "firmware.branch.name" -> R.string.flipper_firmware_branch_label
        "firmware.branch.num" -> R.string.flipper_firmware_branch_number_label
        "firmware.commit", "firmware.commit.hash" -> R.string.flipper_firmware_commit_label
        "firmware.commit.dirty" -> R.string.flipper_firmware_dirty_label
        "firmware.target" -> R.string.flipper_firmware_target_label
        "firmware.origin.fork" -> R.string.flipper_firmware_fork_label
        "firmware.origin.git" -> R.string.flipper_firmware_origin_label
        "firmware.api.major" -> R.string.flipper_firmware_api_major_label
        "firmware.api.minor" -> R.string.flipper_firmware_api_minor_label
        "hardware.name" -> R.string.flipper_device_name_label
        "hardware.ver", "hardware.version" -> R.string.flipper_hardware_label
        "hardware.model" -> R.string.flipper_hardware_model_label
        "hardware.uid" -> R.string.flipper_uid_label
        "hardware.region", "hardware.region.builtin" -> R.string.flipper_hardware_region_label
        "hardware.region.provisioned" -> R.string.flipper_region_provisioned_label
        "hardware.otp.ver" -> R.string.flipper_hardware_otp_label
        "hardware.color" -> R.string.flipper_hardware_color_label
        "hardware.timestamp" -> R.string.flipper_hardware_timestamp_label
        "hardware.target" -> R.string.flipper_hardware_target_label
        "hardware.body" -> R.string.flipper_hardware_body_label
        "hardware.connect" -> R.string.flipper_hardware_connector_label
        "hardware.display" -> R.string.flipper_hardware_display_label
        "device.info.major", "power.info.major", "format.major" -> R.string.flipper_format_major_label
        "device.info.minor", "power.info.minor", "format.minor" -> R.string.flipper_format_minor_label
        "protobuf.version.major" -> R.string.flipper_protobuf_major_label
        "protobuf.version.minor" -> R.string.flipper_protobuf_minor_label
        "radio.stack.major" -> R.string.flipper_radio_major_label
        "radio.stack.minor" -> R.string.flipper_radio_minor_label
        "radio.stack.sub" -> R.string.flipper_radio_sub_label
        "radio.stack.type" -> R.string.flipper_radio_type_label
        "charge.level" -> R.string.flipper_battery_charge_label
        "charge.state" -> R.string.flipper_charge_state_label
        "charge.voltage.limit" -> R.string.flipper_charge_voltage_limit_label
        "battery.voltage" -> R.string.flipper_battery_voltage_label
        "battery.current" -> R.string.flipper_battery_current_label
        "battery.temp" -> R.string.flipper_battery_temperature_label
        "battery.health" -> R.string.flipper_battery_health_label
        "capacity.remain" -> R.string.flipper_battery_capacity_left_label
        "capacity.full" -> R.string.flipper_battery_capacity_full_label
        "capacity.design" -> R.string.flipper_battery_capacity_design_label
        else -> return key.replace('.', ' ').replace('_', ' ').replaceFirstChar { it.titlecase() }
    }
    return stringResource(label)
}

@Composable
private fun storageError(error: Exception): String = when ((error as? FlipperRpcException)?.statusCode) {
    FlipperProto.CommandStatus.ERROR_STORAGE_NOT_READY_VALUE,
    FlipperProto.CommandStatus.ERROR_STORAGE_NOT_EXIST_VALUE -> stringResource(R.string.flipper_sd_card_unavailable)
    else -> stringResource(R.string.flipper_sd_card_failed)
}

@Composable
private fun deviceInfoError(error: Exception): String {
    val rpc = error as? FlipperRpcException
    return when (rpc?.reason) {
        FlipperRpcException.Reason.NOT_READY -> stringResource(R.string.flipper_rpc_waiting)
        FlipperRpcException.Reason.SESSION_CLOSED -> stringResource(R.string.flipper_device_info_session_closed)
        FlipperRpcException.Reason.TIMEOUT -> stringResource(R.string.flipper_device_info_timeout)
        FlipperRpcException.Reason.REMOTE_ERROR -> stringResource(R.string.flipper_device_info_remote_error, rpc.statusCode ?: -1)
        FlipperRpcException.Reason.SEND_FAILED -> stringResource(R.string.flipper_device_info_send_failed)
        FlipperRpcException.Reason.UNEXPECTED_RESPONSE -> stringResource(R.string.flipper_device_info_unexpected_response)
        null -> stringResource(R.string.flipper_device_info_failed)
    }
}
