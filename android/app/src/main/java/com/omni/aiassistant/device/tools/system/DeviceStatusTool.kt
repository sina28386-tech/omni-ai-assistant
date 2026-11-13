package com.omni.aiassistant.device.tools.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

internal class DeviceStatusTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "device_status",
        description = "Read a snapshot of this phone: battery percentage and charging state, " +
            "internal storage total/free, media/ring/alarm/notification volume percentages, " +
            "ringer mode, active network type, screen state, uptime, model and Android version.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(properties = emptyMap()),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val batteryIntent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) {
            (level * 100 / scale).coerceIn(0, 100)
        } else {
            -1
        }
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0

        val storage = runCatching { StatFs(Environment.getDataDirectory().absolutePath) }.getOrNull()
        val totalBytes = storage?.totalBytes ?: 0L
        val freeBytes = storage?.availableBytes ?: 0L

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val volumes = buildJsonObject {
            if (audio == null) {
                put("available", false)
            } else {
                put("available", true)
                put("media", volumePercent(audio, AudioManager.STREAM_MUSIC))
                put("ring", volumePercent(audio, AudioManager.STREAM_RING))
                put("alarm", volumePercent(audio, AudioManager.STREAM_ALARM))
                put("notification", volumePercent(audio, AudioManager.STREAM_NOTIFICATION))
            }
        }
        val ringer = audio?.let { ringerModeName(it.ringerMode) } ?: "unknown"

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connectivity?.activeNetwork
        val caps = if (connectivity != null && activeNetwork != null) {
            connectivity.getNetworkCapabilities(activeNetwork)
        } else {
            null
        }
        val networkType = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenOn = power?.isInteractive ?: false
        val uptimeSeconds = SystemClock.elapsedRealtime() / 1_000L

        val summary = buildString {
            if (batteryPercent >= 0) {
                append("Battery $batteryPercent%")
                append(if (charging) " charging" else " on battery")
            } else {
                append("Battery unknown")
            }
            append(", ")
            append(networkType)
            append(", ")
            append(formatBytes(freeBytes))
            append(" free")
        }.take(200)

        val data = buildJsonObject {
            put(
                "battery",
                buildJsonObject {
                    put("percent", batteryPercent)
                    put("charging", charging)
                    put("status", batteryStatusName(status))
                    put("plugged", plugged != 0)
                },
            )
            put(
                "storage",
                buildJsonObject {
                    put("totalBytes", totalBytes)
                    put("freeBytes", freeBytes)
                    put("total", formatBytes(totalBytes))
                    put("free", formatBytes(freeBytes))
                },
            )
            put("volumes", volumes)
            put("ringer", ringer)
            put(
                "network",
                buildJsonObject {
                    put("type", networkType)
                    put("connected", activeNetwork != null)
                    put("internet", internet)
                    put("validated", validated)
                },
            )
            put("screenOn", screenOn)
            put("uptimeSeconds", uptimeSeconds)
            put("uptime", formatUptime(uptimeSeconds))
            put(
                "device",
                buildJsonObject {
                    put("manufacturer", Build.MANUFACTURER.orEmpty())
                    put("model", Build.MODEL.orEmpty())
                    put("android", Build.VERSION.RELEASE.orEmpty())
                    put("sdk", Build.VERSION.SDK_INT)
                },
            )
        }

        return ToolOutcome.Success(summary = summary, data = data)
    }

    private fun volumePercent(audio: AudioManager, stream: Int): Int {
        val max = runCatching { audio.getStreamMaxVolume(stream) }.getOrDefault(0)
        if (max <= 0) return 0
        val current = runCatching { audio.getStreamVolume(stream) }.getOrDefault(0)
        return (current * 100.0 / max).roundToInt().coerceIn(0, 100)
    }

    private fun batteryStatusName(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }
}
