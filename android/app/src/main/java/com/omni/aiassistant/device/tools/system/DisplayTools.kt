package com.omni.aiassistant.device.tools.system

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.Settings
import android.view.Surface
import com.omni.aiassistant.core.agent.ToolArgs.clamp
import com.omni.aiassistant.core.agent.ToolArgs.string
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolArgs
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.device.capability.Capability
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.roundToInt

internal class BrightnessSetTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(
    context = context,
    capabilities = capabilities,
    checkedCapability = Capability.WRITE_SETTINGS,
    capabilityLabel = "Allow modifying system settings",
) {

    override val spec = ToolSpec(
        name = "brightness_set",
        description = "Set the screen brightness percentage and switch the display to manual " +
            "brightness. Requires the Modify system settings permission.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.MEDIUM,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "percent" to ToolSchema.integer("Brightness percentage", minimum = 0, maximum = 100),
            ),
            required = listOf("percent"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val percent = clamp(arguments.requireIntArg("percent"), 0, 100)
        val resolver = context.contentResolver
        val modeOk = Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        val rawValue = (1 + percent / 100.0 * 254.0).roundToInt().coerceIn(1, 255)
        val valueOk = Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS,
            rawValue,
        )
        if (!modeOk || !valueOk) {
            return ToolOutcome.Failure("The system rejected the brightness change")
        }

        val stored = Settings.System.getInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS,
            rawValue,
        ).coerceIn(1, 255)
        val actualPercent = ((stored - 1) * 100.0 / 254.0).roundToInt().coerceIn(0, 100)

        return ToolOutcome.Success(
            summary = "Screen brightness set to $actualPercent%",
            data = buildJsonObject {
                put("percent", actualPercent)
                put("rawValue", stored)
                put("mode", "manual")
            },
        )
    }
}

internal class RotationSetTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(
    context = context,
    capabilities = capabilities,
    checkedCapability = Capability.WRITE_SETTINGS,
    capabilityLabel = "Allow modifying system settings",
) {

    override val spec = ToolSpec(
        name = "rotation_set",
        description = "Enable auto-rotate or lock the screen to portrait or landscape. Requires " +
            "the Modify system settings permission.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.MEDIUM,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "mode" to ToolSchema.string(
                    "Rotation mode",
                    choices = listOf("auto", "portrait", "landscape"),
                ),
            ),
            required = listOf("mode"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val mode = arguments.string("mode")?.lowercase(Locale.US)
            ?: return ToolOutcome.Failure("mode is required")
        if (mode !in setOf("auto", "portrait", "landscape")) {
            return ToolOutcome.Failure("mode must be auto, portrait or landscape")
        }
        val resolver = context.contentResolver
        val auto = mode == "auto"
        val autoOk = Settings.System.putInt(
            resolver,
            Settings.System.ACCELEROMETER_ROTATION,
            if (auto) 1 else 0,
        )
        var rotationOk = true
        if (!auto) {
            val rotation = if (mode == "portrait") Surface.ROTATION_0 else Surface.ROTATION_90
            rotationOk = Settings.System.putInt(resolver, Settings.System.USER_ROTATION, rotation)
        }
        if (!autoOk || !rotationOk) {
            return ToolOutcome.Failure("The system rejected the rotation change")
        }

        return ToolOutcome.Success(
            summary = if (auto) "Auto-rotate enabled" else "Screen locked to $mode",
            data = buildJsonObject {
                put("mode", mode)
                put("autoRotate", auto)
            },
        )
    }
}

internal class FlashlightTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(
    context = context,
    capabilities = capabilities,
    checkedCapability = Capability.CAMERA,
    capabilityLabel = "Allow camera access for the flashlight",
) {

    override val spec = ToolSpec(
        name = "flashlight",
        description = "Turn the rear camera flashlight on or off.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "on" to ToolSchema.boolean("True to switch the flashlight on, false to switch it off"),
            ),
            required = listOf("on"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val on = arguments.requireBooleanArg("on")
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolOutcome.Failure("The camera service is unavailable")

        val cameraId = try {
            manager.getCameraIdList().firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (access: CameraAccessException) {
            return ToolOutcome.Failure("The camera service is unavailable right now")
        } ?: return ToolOutcome.Failure("This device has no flashlight")

        return try {
            manager.setTorchMode(cameraId, on)
            ToolOutcome.Success(
                summary = if (on) "Flashlight turned on" else "Flashlight turned off",
                data = buildJsonObject { put("on", on) },
            )
        } catch (access: CameraAccessException) {
            ToolOutcome.Failure("The flashlight is unavailable right now")
        } catch (invalid: IllegalArgumentException) {
            ToolOutcome.Failure("The flashlight is unavailable right now")
        }
    }
}
