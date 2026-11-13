package com.omni.aiassistant.device.tools.system

import android.content.Context
import com.omni.aiassistant.core.agent.Tool
import com.omni.aiassistant.core.agent.ToolGroup
import com.omni.aiassistant.device.capability.CapabilityCenter

/**
 * System and media actions: device status, alarms/timers, calendar, volume,
 * ringer, brightness, flashlight, Do Not Disturb, location, clipboard, system
 * panels, rotation, speech, media keys and the intent-based sharing tools.
 */
class SystemToolGroup(
    private val context: Context,
    private val capabilities: CapabilityCenter,
) : ToolGroup {

    override fun tools(): List<Tool> = listOf(
        DeviceStatusTool(context, capabilities),
        AlarmSetTool(context, capabilities),
        TimerSetTool(context, capabilities),
        CalendarEventsTool(context, capabilities),
        CalendarCreateTool(context, capabilities),
        VolumeSetTool(context, capabilities),
        RingerSetTool(context, capabilities),
        BrightnessSetTool(context, capabilities),
        FlashlightTool(context, capabilities),
        DndSetTool(context, capabilities),
        LocationGetTool(context, capabilities),
        ClipboardGetTool(context, capabilities),
        ClipboardSetTool(context, capabilities),
        WifiPanelTool(context, capabilities),
        BluetoothPanelTool(context, capabilities),
        RotationSetTool(context, capabilities),
        TtsSpeakTool(context, capabilities),
        MediaControlTool(context, capabilities),
        ShareTextTool(context, capabilities),
        OpenUrlTool(context, capabilities),
        WebSearchTool(context, capabilities),
        MapsSearchTool(context, capabilities),
        EmailComposeTool(context, capabilities),
    )
}
