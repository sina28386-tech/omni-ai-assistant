package com.omni.aiassistant.device.tools.files

import android.content.Context
import com.omni.aiassistant.core.agent.ToolArgs
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.device.capability.Capability
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class FilesSearchTool(
    context: Context,
    capabilities: CapabilityCenter,
) : FilesTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "files_search",
        description = "Search files on the phone by name. Uses all-files access when granted, " +
            "otherwise falls back to a media-library search.",
        category = ToolCategory.FILES,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "query" to ToolSchema.string("Part of the file name to look for"),
                "kind" to ToolSchema.string(
                    "Restrict results to one kind of file",
                    choices = FileKinds.KINDS,
                    default = "any",
                ),
                "limit" to ToolSchema.integer("Maximum number of results", 1, 20, 20),
            ),
            required = listOf("query"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val query = arguments.argString("query")?.trim().orEmpty()
        if (query.isEmpty()) return ToolOutcome.Failure("A search text is required")
        if (query.length > 200) return ToolOutcome.Failure("The search text is too long")
        val kind = FileKinds.normalize(arguments.argString("kind"))
        val limit = ToolArgs.clamp(arguments.argInt("limit") ?: 20, 1, 20)

        val items = when {
            capabilities.isGranted(Capability.STORAGE) -> {
                val walk = ExternalWalk.search(paths.externalRoot, query, kind, limit)
                if (walk.isEmpty && walk.permissionDenied) {
                    // The walk was not allowed to look. Saying "no files found"
                    // would be a lie: the truth is that access is missing.
                    return ToolOutcome.NeedsPermission(
                        Capability.STORAGE.name,
                        "All files access",
                    )
                }
                walk.files.map { fileJson(it) }
            }

            capabilities.isGranted(Capability.MEDIA) -> try {
                MediaSearch.search(context, query, kind, limit).map { mediaJson(it) }
            } catch (denied: SecurityException) {
                val permission = if (kind == "document") Capability.STORAGE else Capability.MEDIA
                return ToolOutcome.NeedsPermission(
                    permission.name,
                    if (kind == "document") "All files access" else "Photos, videos and audio access",
                )
            }

            else -> return ToolOutcome.NeedsPermission(
                Capability.STORAGE.name,
                "All files access",
            )
        }

        return success(
            "Found ${items.size} file(s) matching \"${query.take(60)}\"",
            buildJsonObject {
                put("query", JsonPrimitive(query))
                put("kind", JsonPrimitive(kind))
                put("count", JsonPrimitive(items.size))
                put("results", buildJsonArray { items.forEach { add(it) } })
            },
        )
    }
}

internal class FilesRecentTool(
    context: Context,
    capabilities: CapabilityCenter,
) : FilesTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "files_recent",
        description = "List the most recently added or modified files, newest first.",
        category = ToolCategory.FILES,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "kind" to ToolSchema.string(
                    "Restrict results to one kind of file",
                    choices = FileKinds.KINDS,
                    default = "any",
                ),
                "limit" to ToolSchema.integer("Maximum number of results", 1, 20, 20),
            ),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val kind = FileKinds.normalize(arguments.argString("kind"))
        val limit = ToolArgs.clamp(arguments.argInt("limit") ?: 20, 1, 20)

        val media = if (capabilities.isGranted(Capability.MEDIA)) {
            try {
                MediaSearch.recent(context, kind, limit)
            } catch (denied: SecurityException) {
                null
            }
        } else {
            null
        }

        val hasStorage = capabilities.isGranted(Capability.STORAGE)
        if (media != null && (media.isNotEmpty() || !hasStorage)) {
            val items = media.map { mediaJson(it) }
            return success(
                "Found ${items.size} recent file(s)",
                buildJsonObject {
                    put("kind", JsonPrimitive(kind))
                    put("count", JsonPrimitive(items.size))
                    put("results", buildJsonArray { items.forEach { add(it) } })
                },
            )
        }

        if (hasStorage) {
            val walk = ExternalWalk.recent(paths.externalRoot, kind, limit)
            if (walk.isEmpty && walk.permissionDenied) {
                return ToolOutcome.NeedsPermission(
                    Capability.STORAGE.name,
                    "All files access",
                )
            }
            val items = walk.files.map { fileJson(it) }
            return success(
                "Found ${items.size} recent file(s)",
                buildJsonObject {
                    put("kind", JsonPrimitive(kind))
                    put("count", JsonPrimitive(items.size))
                    put("results", buildJsonArray { items.forEach { add(it) } })
                },
            )
        }

        val permission = if (kind == "document") Capability.STORAGE else Capability.MEDIA
        return ToolOutcome.NeedsPermission(
            permission.name,
            if (kind == "document") "All files access" else "Photos, videos and audio access",
        )
    }
}
