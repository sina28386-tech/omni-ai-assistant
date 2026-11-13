package com.omni.aiassistant.device.tools.files

import android.content.Context
import android.webkit.MimeTypeMap
import com.omni.aiassistant.core.agent.Tool
import com.omni.aiassistant.core.agent.ToolArgs
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.device.capability.Capability
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale

/**
 * Shared plumbing for every file/app tool:
 * - runs all work on [Dispatchers.IO];
 * - converts path rejections and unexpected errors into model-readable outcomes;
 * - never lets a [SecurityException] escape.
 */
internal abstract class FilesToolBase(protected val context: Context) : Tool {

    final override suspend fun execute(arguments: JsonObject): ToolOutcome = try {
        withContext(Dispatchers.IO) { run(arguments) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (security: SecurityException) {
        securityFailure()
    } catch (rejected: UnsafePathException) {
        ToolOutcome.Failure(rejected.message ?: "That path is not allowed")
    } catch (error: Exception) {
        ToolOutcome.Failure("${spec.name} failed (${error::class.simpleName ?: "error"})")
    }

    protected abstract suspend fun run(arguments: JsonObject): ToolOutcome

    /** File tools ask for all-files access; app tools override this. */
    protected open fun securityFailure(): ToolOutcome =
        ToolOutcome.NeedsPermission(Capability.STORAGE.name, "All files access")
}

/** Base for tools that touch shared storage or Omni's sandbox. */
internal abstract class FilesTool(
    context: Context,
    protected val capabilities: CapabilityCenter,
) : FilesToolBase(context) {

    protected val paths: SafePaths by lazy { SafePaths(context) }

    /**
     * Omni's own folders need no storage permission. Shared storage needs
     * all-files access; otherwise the caller must ask the user.
     */
    protected fun storageFailure(file: File): ToolOutcome? {
        if (paths.isAppPrivate(file)) return null
        if (capabilities.isGranted(Capability.STORAGE)) return null
        return ToolOutcome.NeedsPermission(Capability.STORAGE.name, "All files access")
    }

    /** Compact description of a MediaStore row, used by search and recents. */
    protected fun mediaJson(hit: MediaHit): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(hit.name))
        val path = paths.resolveOrNull(hit.path)
        if (path != null) put("path", JsonPrimitive(path.path))
        put("uri", JsonPrimitive(hit.uri))
        put("size", JsonPrimitive(hit.size))
        put("modified", JsonPrimitive(hit.modified))
        put("mime", JsonPrimitive(hit.mime?.takeIf { it.isNotBlank() } ?: mimeForName(hit.name)))
    }
}

/** Base for package-manager tools: there is no runtime permission to request. */
internal abstract class AppsTool(context: Context) : FilesToolBase(context) {
    override fun securityFailure(): ToolOutcome =
        ToolOutcome.Failure("The system denied access to installed apps")
}

// --- argument readers (delegating to the core ToolArgs helpers) -------------

internal fun JsonObject.argString(key: String): String? =
    with(ToolArgs) { this@argString.string(key) }

internal fun JsonObject.argInt(key: String): Int? =
    with(ToolArgs) { this@argInt.int(key) }

// --- result helpers ---------------------------------------------------------

internal fun shortSummary(text: String): String =
    text.replace('\n', ' ').replace('\r', ' ').trim().take(200)

internal fun success(summary: String, data: JsonObject): ToolOutcome =
    ToolOutcome.Success(shortSummary(summary), data)

internal fun fileJson(file: File): JsonObject = buildJsonObject {
    put("name", JsonPrimitive(file.name))
    put("path", JsonPrimitive(file.path))
    put("size", JsonPrimitive(file.length()))
    put("modified", JsonPrimitive(file.lastModified()))
    put("isDirectory", JsonPrimitive(file.isDirectory))
    put(
        "mime",
        JsonPrimitive(if (file.isDirectory) "inode/directory" else mimeForName(file.name)),
    )
}

internal fun mimeForName(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
    if (extension.isEmpty()) return "application/octet-stream"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: "application/octet-stream"
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}
