package com.omni.aiassistant.device.tools.system

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.omni.aiassistant.core.agent.ToolArgs.boolean
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.device.capability.Capability
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal class DndSetTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(
    context = context,
    capabilities = capabilities,
    checkedCapability = Capability.DND,
    capabilityLabel = "Allow Do Not Disturb access",
) {

    override val spec = ToolSpec(
        name = "dnd_set",
        description = "Turn Do Not Disturb fully on or off. Requires notification policy access.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.MEDIUM,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "on" to ToolSchema.boolean("True to enable Do Not Disturb, false to disable it"),
            ),
            required = listOf("on"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val on = arguments.requireBooleanArg("on")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return ToolOutcome.Failure("The notification service is unavailable")

        val target = if (on) {
            NotificationManager.INTERRUPTION_FILTER_NONE
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }
        manager.setInterruptionFilter(target)
        val actual = manager.currentInterruptionFilter
        if (actual != target) {
            return ToolOutcome.Failure("The system did not change Do Not Disturb")
        }

        return ToolOutcome.Success(
            summary = if (on) "Do Not Disturb enabled" else "Do Not Disturb disabled",
            data = buildJsonObject {
                put("on", on)
                put(
                    "filter",
                    if (actual == NotificationManager.INTERRUPTION_FILTER_NONE) "none" else "all",
                )
            },
        )
    }
}

internal class LocationGetTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(
    context = context,
    capabilities = capabilities,
    checkedCapability = Capability.LOCATION,
    capabilityLabel = "Allow location access",
) {

    override val spec = ToolSpec(
        name = "location_get",
        description = "Get the phone's current latitude and longitude. Uses the last known fix " +
            "when available and waits up to 10 seconds for a fresh one. Coarse location is used " +
            "when precise location is not granted.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "precise" to ToolSchema.boolean(
                    "Prefer precise GPS over network location",
                    default = true,
                ),
            ),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val preferPrecise = arguments.boolean("precise") ?: true
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return ToolOutcome.Failure("The location service is unavailable")

        val gpsEnabled = runCatching {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        val networkEnabled = runCatching {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)

        var location: Location? = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

        if (location == null) {
            val provider = when {
                preferPrecise && fineGranted && gpsEnabled -> LocationManager.GPS_PROVIDER
                networkEnabled -> LocationManager.NETWORK_PROVIDER
                fineGranted && gpsEnabled -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider == null) {
                return ToolOutcome.Failure(
                    "Location is turned off or unavailable. Enable location services and try again.",
                )
            }
            location = awaitSingleLocation(manager, provider)
        }

        val fix = location
            ?: return ToolOutcome.Failure("Could not get a location fix. Try again with location on.")

        val accuracy = fix.accuracy.roundToInt().coerceAtLeast(0)
        val summary = buildString {
            if (!fineGranted) append("Approximate location ")
            else append("Location ")
            append(String.format(Locale.US, "%.5f, %.5f", fix.latitude, fix.longitude))
            append(" (")
            append('\u00B1')
            append(accuracy)
            append(" m)")
        }.take(200)

        return ToolOutcome.Success(
            summary = summary,
            data = buildJsonObject {
                put("latitude", fix.latitude)
                put("longitude", fix.longitude)
                put("accuracyMeters", accuracy)
                put("provider", fix.provider.orEmpty())
                put("precise", fineGranted)
                put("timestamp", formatDateTime(fix.time))
            },
        )
    }

    private suspend fun awaitSingleLocation(
        manager: LocationManager,
        provider: String,
    ): Location? = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
        suspendCancellableCoroutine<Location?> { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (continuation.isActive) {
                        runCatching { manager.removeUpdates(this) }
                        continuation.resume(location)
                    }
                }

                override fun onProviderDisabled(provider: String) {
                    if (continuation.isActive) {
                        runCatching { manager.removeUpdates(this) }
                        continuation.resume(null)
                    }
                }

                override fun onProviderEnabled(provider: String) = Unit

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            continuation.invokeOnCancellation {
                runCatching { manager.removeUpdates(listener) }
            }
            try {
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (security: SecurityException) {
                throw security
            } catch (unavailable: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private companion object {
        const val LOCATION_TIMEOUT_MS = 10_000L
    }
}

internal class ClipboardGetTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "clipboard_get",
        description = "Read the current clipboard text, capped at 2000 characters. Returns an " +
            "empty success when the clipboard is empty.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(properties = emptyMap()),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ToolOutcome.Failure("The clipboard service is unavailable")
        val clip = manager.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return emptyClipboard()
        }
        val item = clip.getItemAt(0)
        val full = item.coerceToText(context)?.toString().orEmpty()
        if (full.isEmpty()) return emptyClipboard()

        val truncated = full.length > MAX_CLIPBOARD_READ_CHARS
        val text = full.take(MAX_CLIPBOARD_READ_CHARS)
        val summary = if (truncated) {
            "Clipboard contains ${full.length} characters (truncated to $MAX_CLIPBOARD_READ_CHARS)"
        } else {
            "Clipboard contains ${full.length} characters"
        }
        return ToolOutcome.Success(
            summary = summary.take(200),
            data = buildJsonObject {
                put("text", text)
                put("length", full.length)
                put("truncated", truncated)
            },
        )
    }

    private fun emptyClipboard(): ToolOutcome = ToolOutcome.Success(
        summary = "Clipboard is empty",
        data = buildJsonObject {
            put("text", "")
            put("length", 0)
            put("truncated", false)
        },
    )
}

internal class ClipboardSetTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "clipboard_set",
        description = "Copy up to 8000 characters of text to the clipboard.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "text" to ToolSchema.string("Text to copy"),
            ),
            required = listOf("text"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val raw = arguments.requireTextArg("text")
        val truncated = raw.length > MAX_RESULT_CHARS
        val text = raw.take(MAX_RESULT_CHARS)
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ToolOutcome.Failure("The clipboard service is unavailable")

        manager.setPrimaryClip(ClipData.newPlainText("Omni", text))
        return ToolOutcome.Success(
            summary = if (truncated) {
                "Copied $MAX_RESULT_CHARS characters to the clipboard (truncated)"
            } else {
                "Copied ${text.length} characters to the clipboard"
            },
            data = buildJsonObject {
                put("length", text.length)
                put("truncated", truncated)
            },
        )
    }
}

internal class WifiPanelTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "wifi_panel",
        description = "Open the system Wi-Fi controls. Android does not allow apps to toggle Wi-Fi " +
            "silently, so the user changes it in the panel.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(properties = emptyMap()),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val primary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        return context.openSystemSettings(
            primary = primary,
            fallback = Intent(Settings.ACTION_WIFI_SETTINGS),
            label = "Wi-Fi controls",
        )
    }
}

internal class BluetoothPanelTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "bluetooth_panel",
        description = "Open the system Bluetooth settings. Android does not allow apps to toggle " +
            "Bluetooth silently, so the user changes it there.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(properties = emptyMap()),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        // Settings.Panel has no Bluetooth action in the public SDK, so the settings
        // screen is the only stable destination on every API level.
        return context.openSystemSettings(
            primary = Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            label = "Bluetooth settings",
        )
    }
}
