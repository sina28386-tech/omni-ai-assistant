package com.omni.aiassistant.device.tools.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale

internal class FileOpenTool(
    context: Context,
    capabilities: CapabilityCenter,
) : FilesTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "file_open",
        description = "Open one file with the app that handles its type, so the user can see it.",
        category = ToolCategory.FILES,
        risk = ToolRisk.MEDIUM,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "path" to ToolSchema.string("Path of the file to open"),
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
        if (!file.exists()) return ToolOutcome.Failure("No file named ${file.name}")
        if (file.isDirectory) return ToolOutcome.Failure("${file.name} is a folder, not a file")
        if (!file.isFile) return ToolOutcome.Failure("${file.name} is not a regular file")

        val uri = contentUri(context, file)
            ?: return ToolOutcome.Failure("${file.name} is outside the folders Omni can share")
        val mime = mimeForFile(context, file, uri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri(file.name, uri)
        }
        if (!canHandle(context, intent)) {
            return ToolOutcome.Failure("No app on this phone can open ${file.name}")
        }
        return try {
            context.startActivity(intent)
            success(
                "Opened ${file.name}",
                buildJsonObject {
                    put("name", JsonPrimitive(file.name))
                    put("mime", JsonPrimitive(mime))
                    put("uri", JsonPrimitive(uri.toString()))
                    put("opened", JsonPrimitive(true))
                },
            )
        } catch (error: Exception) {
            ToolOutcome.Failure("Could not open ${file.name}")
        }
    }
}

internal class FileShareTool(
    context: Context,
    capabilities: CapabilityCenter,
) : FilesTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "file_share",
        description = "Share one file through the system share sheet.",
        category = ToolCategory.FILES,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "path" to ToolSchema.string("Path of the file to share"),
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
        if (!file.exists()) return ToolOutcome.Failure("No file named ${file.name}")
        if (file.isDirectory) return ToolOutcome.Failure("Folders cannot be shared")

        val uri = contentUri(context, file)
            ?: return ToolOutcome.Failure("${file.name} is outside the folders Omni can share")
        val mime = mimeForFile(context, file, uri)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri(file.name, uri)
        }
        if (!canHandle(context, intent)) {
            return ToolOutcome.Failure("No app on this phone can share ${file.name}")
        }
        return try {
            context.startActivity(intent)
            success(
                "Shared ${file.name}",
                buildJsonObject {
                    put("name", JsonPrimitive(file.name))
                    put("mime", JsonPrimitive(mime))
                    put("shared", JsonPrimitive(true))
                },
            )
        } catch (error: Exception) {
            ToolOutcome.Failure("Could not share ${file.name}")
        }
    }
}

internal class FileDeleteTool(
    context: Context,
    capabilities: CapabilityCenter,
) : FilesTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "file_delete",
        description = "Permanently delete one regular file. Folders, storage roots and " +
            "symbolic links are refused. The user is asked to confirm every deletion.",
        category = ToolCategory.FILES,
        risk = ToolRisk.HIGH,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "path" to ToolSchema.string("Exact path of the file to delete"),
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
        if (!file.exists()) return ToolOutcome.Failure("No file named ${file.name}")
        if (file.isDirectory) return ToolOutcome.Failure("This tool cannot delete folders")
        if (paths.isStorageRoot(file)) return ToolOutcome.Failure("A storage root cannot be deleted")
        if (target.symlink) return ToolOutcome.Failure("Symbolic links cannot be deleted")
        if (!file.isFile) return ToolOutcome.Failure("${file.name} is not a regular file")

        val name = file.name
        val deleted = try {
            file.delete()
        } catch (denied: SecurityException) {
            throw denied
        } catch (error: Exception) {
            false
        }
        if (!deleted || file.exists()) return ToolOutcome.Failure("Could not delete $name")

        return success(
            "Deleted $name",
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("deleted", JsonPrimitive(true))
            },
        )
    }
}

internal class FileMoveTool(
    context: Context,
    capabilities: CapabilityCenter,
) : FilesTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "file_move",
        description = "Move one file to a new path. Refuses to overwrite an existing " +
            "destination and creates the destination folder when needed.",
        category = ToolCategory.FILES,
        risk = ToolRisk.MEDIUM,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "from" to ToolSchema.string("Current path of the file"),
                "to" to ToolSchema.string("New full path, including the file name"),
            ),
            required = listOf("from", "to"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val fromRaw = arguments.argString("from")
            ?: return ToolOutcome.Failure("A source path is required")
        val toRaw = arguments.argString("to")
            ?: return ToolOutcome.Failure("A destination path is required")

        val source = paths.resolve(fromRaw)
        val destinationInput = paths.resolve(toRaw)
        storageFailure(source.file)?.let { return it }
        storageFailure(destinationInput.file)?.let { return it }

        val src = source.file
        if (!src.exists()) return ToolOutcome.Failure("No file named ${src.name}")
        if (src.isDirectory) return ToolOutcome.Failure("Only regular files can be moved")
        if (source.symlink) return ToolOutcome.Failure("Symbolic links cannot be moved")
        if (!src.isFile) return ToolOutcome.Failure("${src.name} is not a regular file")

        if (src.path == destinationInput.file.path) {
            return ToolOutcome.Failure("Source and destination are the same file")
        }
        if (destinationInput.symlink) {
            return ToolOutcome.Failure("A file named ${destinationInput.file.name} already exists")
        }
        val destination = paths.prepareDestination(destinationInput.file)
        if (destination.exists() || isSymbolicLink(destination)) {
            return ToolOutcome.Failure("A file named ${destination.name} already exists")
        }
        if (src.path == destination.path) {
            return ToolOutcome.Failure("Source and destination are the same file")
        }

        val moved = try {
            src.renameTo(destination)
        } catch (denied: SecurityException) {
            throw denied
        } catch (error: Exception) {
            false
        }
        if (!moved || !destination.exists()) {
            return ToolOutcome.Failure("Could not move ${src.name}")
        }

        return success(
            "Moved ${src.name} to ${destination.name}",
            buildJsonObject {
                put("name", JsonPrimitive(src.name))
                put("from", JsonPrimitive(src.path))
                put("to", JsonPrimitive(destination.path))
                put("moved", JsonPrimitive(true))
            },
        )
    }
}

internal class FileCopyTool(
    context: Context,
    capabilities: CapabilityCenter,
) : FilesTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "file_copy",
        description = "Copy one file to a new path. The destination folder must already " +
            "exist and an existing file is never overwritten.",
        category = ToolCategory.FILES,
        risk = ToolRisk.MEDIUM,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "from" to ToolSchema.string("Path of the file to copy"),
                "to" to ToolSchema.string("Full destination path, including the file name"),
            ),
            required = listOf("from", "to"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val fromRaw = arguments.argString("from")
            ?: return ToolOutcome.Failure("A source path is required")
        val toRaw = arguments.argString("to")
            ?: return ToolOutcome.Failure("A destination path is required")

        val source = paths.resolve(fromRaw)
        val destinationInput = paths.resolve(toRaw)
        storageFailure(source.file)?.let { return it }
        storageFailure(destinationInput.file)?.let { return it }

        val src = source.file
        if (!src.exists()) return ToolOutcome.Failure("No file named ${src.name}")
        if (src.isDirectory) return ToolOutcome.Failure("Only regular files can be copied")
        if (source.symlink) return ToolOutcome.Failure("Symbolic links cannot be copied")
        if (!src.isFile) return ToolOutcome.Failure("${src.name} is not a regular file")
        if (src.path == destinationInput.file.path) {
            return ToolOutcome.Failure("Source and destination are the same file")
        }
        if (destinationInput.symlink) {
            return ToolOutcome.Failure("A file named ${destinationInput.file.name} already exists")
        }

        val destination = paths.requireExistingParent(destinationInput.file)
        if (destination.exists() || isSymbolicLink(destination)) {
            return ToolOutcome.Failure("A file named ${destination.name} already exists")
        }

        val copied = try {
            src.inputStream().use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (denied: SecurityException) {
            throw denied
        } catch (error: Exception) {
            runCatching { destination.delete() }
            false
        }
        if (!copied || !destination.isFile) {
            return ToolOutcome.Failure("Could not copy ${src.name}")
        }

        return success(
            "Copied ${src.name} to ${destination.name}",
            buildJsonObject {
                put("name", JsonPrimitive(destination.name))
                put("from", JsonPrimitive(src.path))
                put("to", JsonPrimitive(destination.path))
                put("size", JsonPrimitive(destination.length()))
                put("copied", JsonPrimitive(true))
            },
        )
    }
}

internal class FileRenameTool(
    context: Context,
    capabilities: CapabilityCenter,
) : FilesTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "file_rename",
        description = "Rename one file in place. Only the file name changes; folders and " +
            "path separators are not accepted in the new name.",
        category = ToolCategory.FILES,
        risk = ToolRisk.MEDIUM,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "path" to ToolSchema.string("Current path of the file"),
                "new_name" to ToolSchema.string("New file name only, without a folder"),
            ),
            required = listOf("path", "new_name"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val raw = arguments.argString("path")
            ?: return ToolOutcome.Failure("A file path is required")
        val newName = arguments.argString("new_name")?.trim().orEmpty()
        if (newName.isEmpty()) return ToolOutcome.Failure("A new file name is required")
        if (newName.length > 200) return ToolOutcome.Failure("The new file name is too long")
        if (newName == "." || newName == "..") {
            return ToolOutcome.Failure("That new file name is not allowed")
        }
        if (newName.any { it == '/' || it == '\\' || it == '\u0000' }) {
            return ToolOutcome.Failure("The new name must not contain a folder separator")
        }

        val target = paths.resolve(raw)
        storageFailure(target.file)?.let { return it }

        val src = target.file
        if (!src.exists()) return ToolOutcome.Failure("No file named ${src.name}")
        if (src.isDirectory) return ToolOutcome.Failure("This tool renames files, not folders")
        if (target.symlink) return ToolOutcome.Failure("Symbolic links cannot be renamed")
        if (!src.isFile) return ToolOutcome.Failure("${src.name} is not a regular file")

        val parent = src.parentFile
            ?: return ToolOutcome.Failure("The file has no parent folder")
        val destinationTarget = paths.resolve(File(parent, newName).absolutePath)
        val destination = destinationTarget.file
        if (destination.path == src.path) {
            return ToolOutcome.Failure("The file already has that name")
        }
        if (destinationTarget.symlink || destination.exists()) {
            return ToolOutcome.Failure("A file named $newName already exists")
        }

        val renamed = try {
            src.renameTo(destination)
        } catch (denied: SecurityException) {
            throw denied
        } catch (error: Exception) {
            false
        }
        if (!renamed || !destination.exists()) {
            return ToolOutcome.Failure("Could not rename ${src.name}")
        }

        return success(
            "Renamed ${src.name} to $newName",
            buildJsonObject {
                put("name", JsonPrimitive(newName))
                put("path", JsonPrimitive(destination.path))
                put("renamed", JsonPrimitive(true))
            },
        )
    }
}

internal class FolderCreateTool(
    context: Context,
    capabilities: CapabilityCenter,
) : FilesTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "folder_create",
        description = "Create a folder, including missing parent folders. Does nothing " +
            "harmful when the folder already exists.",
        category = ToolCategory.FILES,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "path" to ToolSchema.string("Full path of the folder to create"),
            ),
            required = listOf("path"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val raw = arguments.argString("path")
            ?: return ToolOutcome.Failure("A folder path is required")
        val target = paths.resolve(raw)
        storageFailure(target.file)?.let { return it }

        val folder = target.file
        if (folder.exists()) {
            if (folder.isDirectory) {
                return success(
                    "Folder already exists: ${folder.name}",
                    buildJsonObject {
                        put("name", JsonPrimitive(folder.name))
                        put("path", JsonPrimitive(folder.path))
                        put("created", JsonPrimitive(false))
                    },
                )
            }
            return ToolOutcome.Failure("A file named ${folder.name} already exists")
        }

        val destination = paths.prepareDestination(folder)
        val created = try {
            destination.mkdir()
        } catch (denied: SecurityException) {
            throw denied
        } catch (error: Exception) {
            false
        }
        if (!created && !destination.isDirectory) {
            return ToolOutcome.Failure("Could not create folder ${folder.name}")
        }

        return success(
            "Created folder ${destination.name}",
            buildJsonObject {
                put("name", JsonPrimitive(destination.name))
                put("path", JsonPrimitive(destination.path))
                put("created", JsonPrimitive(true))
            },
        )
    }
}

// --- shared intent helpers --------------------------------------------------

private fun contentUri(context: Context, file: File): Uri? = try {
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
} catch (error: IllegalArgumentException) {
    null
} catch (error: Exception) {
    null
}

private fun mimeForFile(context: Context, file: File, uri: Uri): String {
    val extension = file.extension.lowercase(Locale.US)
    val byExtension = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    if (!byExtension.isNullOrBlank()) return byExtension
    val byResolver = try {
        context.contentResolver.getType(uri)
    } catch (error: Exception) {
        null
    }
    return byResolver?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
}

private fun canHandle(context: Context, intent: Intent): Boolean = try {
    context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
} catch (error: Exception) {
    false
}
