package com.omni.aiassistant.device.tools.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.omni.aiassistant.core.agent.ToolArgs.string
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

internal class AlarmSetTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "alarm_set",
        description = "Create an alarm in the phone's clock app for a time today or tomorrow, " +
            "using the device's local 24-hour clock. No UI is shown when the clock app supports it.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "hour" to ToolSchema.integer("Alarm hour in 24-hour local time", minimum = 0, maximum = 23),
                "minute" to ToolSchema.integer("Alarm minute", minimum = 0, maximum = 59),
                "label" to ToolSchema.string("Optional short label shown on the alarm"),
            ),
            required = listOf("hour", "minute"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val hour = arguments.requireIntArg("hour")
        val minute = arguments.requireIntArg("minute")
        if (hour !in 0..23) return ToolOutcome.Failure("hour must be between 0 and 23")
        if (minute !in 0..59) return ToolOutcome.Failure("minute must be between 0 and 59")
        val label = arguments.string("label")?.trim()?.take(120)

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        }

        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val time = String.format(Locale.US, "%02d:%02d", hour, minute)
            val summary = buildString {
                append("Alarm set for ")
                append(time)
                label?.let {
                    append(" (")
                    append(it.take(60))
                    append(")")
                }
            }.take(200)
            ToolOutcome.Success(
                summary = summary,
                data = buildJsonObject {
                    put("hour", hour)
                    put("minute", minute)
                    label?.let { put("label", it) }
                },
            )
        } catch (notFound: ActivityNotFoundException) {
            ToolOutcome.Failure("No clock app can set alarms on this device")
        }
    }
}

internal class TimerSetTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "timer_set",
        description = "Start a countdown timer in the phone's clock app. The clock app is opened " +
            "only if it does not support silent timer creation.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "seconds" to ToolSchema.integer(
                    "Timer length in seconds",
                    minimum = 1,
                    maximum = 86_400,
                ),
                "label" to ToolSchema.string("Optional short timer label"),
            ),
            required = listOf("seconds"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val seconds = arguments.requireIntArg("seconds")
        if (seconds !in 1..86_400) return ToolOutcome.Failure("seconds must be between 1 and 86400")
        val label = arguments.string("label")?.trim()?.take(120)

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        }

        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val summary = buildString {
                append("Timer set for ")
                append(formatDuration(seconds))
                label?.let {
                    append(" (")
                    append(it.take(60))
                    append(")")
                }
            }.take(200)
            ToolOutcome.Success(
                summary = summary,
                data = buildJsonObject {
                    put("seconds", seconds)
                    label?.let { put("label", it) }
                },
            )
        } catch (notFound: ActivityNotFoundException) {
            ToolOutcome.Failure("No clock app can set timers on this device")
        }
    }
}
