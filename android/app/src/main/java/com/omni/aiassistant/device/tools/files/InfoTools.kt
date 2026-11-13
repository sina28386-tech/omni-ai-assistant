package com.omni.aiassistant.device.tools.files

import android.content.Context
import android.os.StatFs
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.device.capability.Capability
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.round

internal class FileInfoTool(
    context: Context,
    capabilities: CapabilityCenter,
) : FilesTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "file_info",
        description = "Show details for one file or folder: name, path, size, modified time, " +
            "type and MIME type.",
        category = ToolCategory.FILES,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "path" to ToolSchema.string("Absolute path, or a path relative to shared storage"),
            ),
            required = listOf("path"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val raw = arguments.argString("path")
            ?: return ToolOutcome.Failure("A file path is required")
        val target = paths.resolve(raw)
        storageFailure(target.file)?.let { return it }

        val file = target.file
        if (!file.exists()) {
            return ToolOutcome.Failure("No file or folder named ${file.name}")
        }

        val data = buildJsonObject {
            put("name", JsonPrimitive(file.name))
            put("path", JsonPrimitive(file.path))
            put("size", JsonPrimitive(file.length()))
            put("modified", JsonPrimitive(file.lastModified()))
            put("isDirectory", JsonPrimitive(file.isDirectory))
            put(
                "mime",
                JsonPrimitive(
                    if (file.isDirectory) "inode/directory" else mimeForName(file.name),
                ),
            )
            put("symlink", JsonPrimitive(target.symlink))
        }
        val summary = if (file.isDirectory) {
            "Folder ${file.name}"
        } else {
            "${file.name}: ${formatBytes(file.length())}"
        }
        return success(summary, data)
    }
}

internal class StorageInfoTool(
    context: Context,
    capabilities: CapabilityCenter,
) : FilesTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "storage_info",
        description = "Report total, used and available space on shared storage.",
        category = ToolCategory.FILES,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(properties = emptyMap()),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        if (!capabilities.isGranted(Capability.STORAGE)) {
            return ToolOutcome.NeedsPermission(Capability.STORAGE.name, "All files access")
        }

        val stats = try {
            StatFs(paths.externalRoot.path)
        } catch (error: Exception) {
            return ToolOutcome.Failure("Storage statistics are unavailable")
        }
        val total = stats.totalBytes
        val available = stats.availableBytes
        val used = (total - available).coerceAtLeast(0L)
        val usedPercent = if (total > 0L) {
            round(used.toDouble() * 1000.0 / total.toDouble()) / 10.0
        } else {
            0.0
        }

        return success(
            "${formatBytes(available)} free of ${formatBytes(total)} ($usedPercent% used)",
            buildJsonObject {
                put("totalBytes", JsonPrimitive(total))
                put("availableBytes", JsonPrimitive(available))
                put("usedBytes", JsonPrimitive(used))
                put("usedPercent", JsonPrimitive(usedPercent))
            },
        )
    }
}
