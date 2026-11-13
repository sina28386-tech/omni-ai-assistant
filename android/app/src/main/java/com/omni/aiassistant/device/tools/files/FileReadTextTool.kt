package com.omni.aiassistant.device.tools.files

import android.content.Context
import com.omni.aiassistant.core.agent.ToolArgs
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
import java.util.Locale

internal class FileReadTextTool(
    context: Context,
    capabilities: CapabilityCenter,
) : FilesTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "file_read_text",
        description = "Read a small plain-text file (txt, md, json, csv, log, xml, code). " +
            "Binary files are refused and the text is capped.",
        category = ToolCategory.FILES,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "path" to ToolSchema.string("Path of the text file to read"),
                "max_chars" to ToolSchema.integer(
                    "Maximum characters to return",
                    minimum = 500,
                    maximum = 8000,
                    default = 4000,
                ),
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
        if (file.isDirectory) return ToolOutcome.Failure("${file.name} is a folder, not a text file")
        if (!file.isFile) return ToolOutcome.Failure("${file.name} is not a regular file")

        val extension = file.extension.lowercase(Locale.US)
        if (extension !in FileKinds.TEXT) {
            return ToolOutcome.Failure(
                "Only text files can be read (txt, md, json, csv, log, xml, kt, java, js, html, yml, yaml)",
            )
        }

        val maxChars = ToolArgs.clamp(arguments.argInt("max_chars") ?: 4000, 500, 8000)
        val bytes = try {
            file.inputStream().use { input -> input.readAtMost(MAX_READ_BYTES) }
        } catch (denied: SecurityException) {
            throw denied
        } catch (error: Exception) {
            return ToolOutcome.Failure("Could not read ${file.name}")
        }

        if (bytes.isEmpty()) {
            return success(
                "${file.name} is empty",
                buildJsonObject {
                    put("name", JsonPrimitive(file.name))
                    put("path", JsonPrimitive(file.path))
                    put("chars", JsonPrimitive(0))
                    put("truncated", JsonPrimitive(false))
                    put("content", JsonPrimitive(""))
                },
            )
        }

        val headSize = minOf(bytes.size, 512)
        for (index in 0 until headSize) {
            if (bytes[index] == 0.toByte()) {
                return ToolOutcome.Failure("${file.name} looks like a binary file")
            }
        }

        val text = String(bytes, Charsets.UTF_8)
        val truncated = text.length > maxChars
        val content = if (truncated) text.substring(0, maxChars) else text
        val summary = if (content.isEmpty()) {
            "${file.name} is empty"
        } else {
            "Read ${content.length} characters from ${file.name}"
        }

        return success(
            summary,
            buildJsonObject {
                put("name", JsonPrimitive(file.name))
                put("path", JsonPrimitive(file.path))
                put("chars", JsonPrimitive(content.length))
                put("truncated", JsonPrimitive(truncated))
                put("content", JsonPrimitive(content))
            },
        )
    }

    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val read = read(buffer, total, limit - total)
            if (read <= 0) break
            total += read
        }
        return buffer.copyOf(total)
    }

    companion object {
        private const val MAX_READ_BYTES = 512 * 1024
    }
}
