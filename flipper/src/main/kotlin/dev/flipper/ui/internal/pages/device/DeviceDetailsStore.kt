package dev.flipper.ui.internal.pages.device

import android.content.Context
import androidx.core.content.edit
import dev.flipper.api.model.DeviceInfo
import dev.flipper.api.model.FlipperRpcException
import dev.flipper.api.model.PowerInfo
import dev.flipper.api.model.StorageInfo
import org.json.JSONException
import org.json.JSONObject

/** Each saved snapshot belongs to the Bluetooth address that returned it. */
internal class DeviceDetailsStore(context: Context) {
    private val prefs = context.getSharedPreferences("flipper_device_details", Context.MODE_PRIVATE)

    fun read(address: String): DeviceDetails? {
        val saved = prefs.getString(address.uppercase(), null) ?: return null
        return try {
            val json = JSONObject(saved)
            val info = json.optJSONObject("device")?.fields()?.let { DeviceInfoState.Ready(DeviceInfo(it)) }
            val power = json.optJSONObject("power")?.fields()?.let { DeviceInfoState.Ready(PowerInfo(it)) }
            val sd = json.optJSONObject("sdCard")?.let {
                DeviceInfoState.Ready(StorageInfo(it.getLong("totalBytes"), it.getLong("freeBytes")))
            }
            val storageStatus = json.optInt("storageStatus").takeIf { it != 0 }
            if (info == null && power == null && sd == null && storageStatus == null) return null
            DeviceDetails(
                updatedAtMillis = json.optLong("updatedAt").takeIf { it > 0 },
                isCached = true,
                info = info ?: unavailable(),
                power = power ?: unavailable(),
                sdCard = sd ?: unavailable(storageStatus),
            )
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun save(address: String, details: DeviceDetails) {
        val key = address.uppercase()
        val json = JSONObject()
        details.updatedAtMillis?.let { json.put("updatedAt", it) }
        (details.info as? DeviceInfoState.Ready)?.data?.let { json.put("device", JSONObject(it.fields)) }
        (details.power as? DeviceInfoState.Ready)?.data?.let { json.put("power", JSONObject(it.fields)) }
        (details.sdCard as? DeviceInfoState.Ready)?.data?.let {
            json.put("sdCard", JSONObject().put("totalBytes", it.totalBytes).put("freeBytes", it.freeBytes))
            json.remove("storageStatus")
        }
        val storageError = (details.sdCard as? DeviceInfoState.Failed)?.error as? FlipperRpcException
        storageError?.statusCode?.let {
            json.put("storageStatus", it)
            json.remove("sdCard")
        }
        if (json.length() > 0) prefs.edit { putString(key, json.toString()) }
    }

    private fun JSONObject.fields(): Map<String, String> = keys().asSequence().associateWith { getString(it) }

    private fun unavailable(status: Int? = null): DeviceInfoState.Failed {
        val message = "Unavailable in last refresh"
        val error = if (status == null) IllegalStateException(message)
            else FlipperRpcException(FlipperRpcException.Reason.REMOTE_ERROR, message, statusCode = status)
        return DeviceInfoState.Failed(error)
    }
}
