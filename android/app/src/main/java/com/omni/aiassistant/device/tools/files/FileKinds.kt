package com.omni.aiassistant.device.tools.files

import com.omni.aiassistant.core.device.FileNameNormalize
import java.util.Locale

/** Extension sets and name matching shared by the filesystem walker and MediaStore. */
internal object FileKinds {

    val KINDS = listOf("any", "image", "video", "audio", "document")

    val IMAGE = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tiff", "svg",
    )
    val VIDEO = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "ts", "m4v", "flv", "wmv", "mpg", "mpeg",
    )
    val AUDIO = setOf(
        "mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "mid", "midi", "amr", "wma",
    )
    val DOCUMENT = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "json",
        "xml", "rtf", "odt", "ods", "odp", "epub", "html", "htm", "yml", "yaml", "log",
    )

    /** The contract's exact text-readable whitelist. */
    val TEXT = setOf(
        "txt", "md", "json", "csv", "log", "xml", "kt", "java", "js", "html", "yml", "yaml",
    )

    fun normalize(raw: String?): String {
        val value = raw?.trim()?.lowercase(Locale.US).orEmpty()
        return if (value in KINDS) value else "any"
    }

    fun extensions(kind: String): Set<String>? = when (kind) {
        "image" -> IMAGE
        "video" -> VIDEO
        "audio" -> AUDIO
        "document" -> DOCUMENT
        else -> null
    }

    fun matchesKind(fileName: String, kind: String): Boolean {
        val allowed = extensions(kind) ?: return true
        return fileName.substringAfterLast('.', "").lowercase(Locale.US) in allowed
    }

    /** Token-AND match over the normalised name, tolerant of Persian/Arabic forms. */
    fun matchesName(name: String, query: String): Boolean {
        val tokens = FileNameNormalize.normalize(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val normalizedName = FileNameNormalize.normalize(name)
        return tokens.all { normalizedName.contains(it) }
    }
}
