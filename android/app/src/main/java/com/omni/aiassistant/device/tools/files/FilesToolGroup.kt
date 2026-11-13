package com.omni.aiassistant.device.tools.files

import android.content.Context
import com.omni.aiassistant.core.agent.Tool
import com.omni.aiassistant.core.agent.ToolGroup
import com.omni.aiassistant.device.capability.CapabilityCenter

/**
 * Files & apps capability package.
 *
 * Every path is funnelled through [SafePaths]; every mutating operation is
 * refused unless it targets an existing regular file inside shared storage or
 * Omni's own sandbox. The dispatcher still enforces confirmation by risk.
 */
class FilesToolGroup(
    private val context: Context,
    private val capabilities: CapabilityCenter,
) : ToolGroup {

    override fun tools(): List<Tool> = listOf(
        FilesSearchTool(context, capabilities),
        FilesRecentTool(context, capabilities),
        FileInfoTool(context, capabilities),
        FileOpenTool(context, capabilities),
        FileDeleteTool(context, capabilities),
        FileMoveTool(context, capabilities),
        FileCopyTool(context, capabilities),
        FileRenameTool(context, capabilities),
        FolderCreateTool(context, capabilities),
        FileShareTool(context, capabilities),
        StorageInfoTool(context, capabilities),
        FileReadTextTool(context, capabilities),
        AppsListTool(context),
        AppOpenTool(context),
        AppInfoTool(context),
        AppUninstallTool(context),
        AppSettingsTool(context),
    )
}
