package com.omni.aiassistant.core.device

/**
 * Pure, platform-independent core of the on-device file agent.
 *
 * Safety contract (enforced here, re-checked by the Android runner):
 * - Searching/reading never mutates anything and never asks for confirmation.
 * - Deleting is NEVER produced from vague text. A delete request is valid only
 *   with an explicit delete verb plus an exact, non-wildcard file name.
 * - A delete executes only after an explicit confirmation carrying the exact
 *   resolved file identity (name + path). Anything else is Blocked.
 */
data class FileRecord(
    val name: String,
    val path: String,
    val sizeBytes: Long = 0L,
    val modifiedEpochMillis: Long = 0L,
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()
    val nameWithoutExtension: String
        get() = if ('.' in name) name.substringBeforeLast('.') else name
}

enum class MediaKind { VIDEO, IMAGE, AUDIO, ANY }

object FileNameNormalize {
    private val VIDEO_WORDS = setOf("فیلم", "فيلم", "ویدیو", "ویديو", "movie", "video", "film")
    private val IMAGE_WORDS = setOf("عکس", "عكس", "تصویر", "تصوير", "گالری", "photo", "picture", "image", "gallery")
    private val AUDIO_WORDS = setOf("آهنگ", "آهنگ", "موزیک", "موسیقی", "صوت", "ترانه", "music", "audio", "song")

    val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "ts", "m4v", "flv", "wmv", "mpg", "mpeg")
    val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tiff")
    val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "mid", "midi", "amr", "wma")

    /** Persian/Arabic-tolerant normalization so voice-typed queries match file names. */
    fun normalize(raw: String): String {
        val lower = raw.lowercase()
        val out = StringBuilder(lower.length)
        for (c in lower) {
            when (c) {
                'ي' -> out.append('ی')
                'ك' -> out.append('ک')
                'ة' -> out.append('ه')
                in '۰'..'۹' -> out.append('0' + (c - '۰'))
                in '٠'..'٩' -> out.append('0' + (c - '٠'))
                '‌', '‍' -> out.append(' ') // ZWNJ/ZWJ unify with space
                in 'ً'..'ْ' -> { /* tashkeel: drop */ }
                else -> out.append(c)
            }
        }
        return out.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun mediaKindFor(token: String?): MediaKind {
        val t = token?.let(::normalize) ?: return MediaKind.ANY
        return when {
            t in VIDEO_WORDS -> MediaKind.VIDEO
            t in IMAGE_WORDS -> MediaKind.IMAGE
            t in AUDIO_WORDS -> MediaKind.AUDIO
            else -> MediaKind.ANY
        }
    }

    fun extensionsFor(kind: MediaKind): Set<String>? = when (kind) {
        MediaKind.VIDEO -> VIDEO_EXTS
        MediaKind.IMAGE -> IMAGE_EXTS
        MediaKind.AUDIO -> AUDIO_EXTS
        MediaKind.ANY -> null
    }
}

data class ScoredFile(val record: FileRecord, val score: Int)

object FileSearch {
    data class Index(val records: List<FileRecord>, val normalizedNames: List<String>)

    fun buildIndex(records: List<FileRecord>): Index =
        Index(records, records.map { FileNameNormalize.normalize(it.name) })

    /**
     * Token-AND search over a prebuilt index. Every query token must match;
     * exact > word-prefix > substring. Ties break by recency. Pure CPU scan,
     * no I/O, so it stays fast with tens of thousands of files.
     */
    fun search(index: Index, rawQuery: String, kind: MediaKind = MediaKind.ANY, limit: Int = 10): List<ScoredFile> {
        val tokens = FileNameNormalize.normalize(rawQuery).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty() || limit <= 0) return emptyList()
        val allowedExts = FileNameNormalize.extensionsFor(kind)
        val hits = ArrayList<ScoredFile>(limit.coerceAtMost(64))
        for (i in index.records.indices) {
            val record = index.records[i]
            if (allowedExts != null && record.extension !in allowedExts) continue
            val normName = index.normalizedNames[i]
            val normBase = FileNameNormalize.normalize(record.nameWithoutExtension)
            var total = 0
            var matchedAll = true
            for (token in tokens) {
                total += when {
                    normName == token || normBase == token -> 100
                    normBase.split(' ').any { it.startsWith(token) } -> 60
                    normName.contains(token) -> 30
                    else -> { matchedAll = false; break }
                }
            }
            if (matchedAll && total > 0) hits.add(ScoredFile(record, total))
        }
        return hits.sortedWith(
            compareByDescending<ScoredFile> { it.score }
                .thenByDescending { it.record.modifiedEpochMillis }
                .thenBy { it.record.name },
        ).take(limit)
    }
}

object FileSafetyPolicy {
    private val VAGUE_TARGETS = setOf("همه", "هەموو", "all", "everything", "کل", "تمام", "files", "فایلها", "فایل ها")

    /**
     * Words that never identify a single file on their own. A target built
     * only from these words ("همه فایل ها", "all the files") is a bulk request
     * and must never reach the filesystem.
     */
    private val GENERIC_WORDS = setOf(
        "file", "files", "فایل", "ها", "موارد", "چیزها", "the", "of",
        "همه", "کل", "تمام", "هەموو", "all", "everything",
    )

    /** A delete request is valid only with an exact, non-wildcard, non-vague name. */
    fun isDeleteTargetValid(exactName: String): Boolean {
        val trimmed = exactName.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.contains('*') || trimmed.contains('%')) return false
        // No path syntax at all: separators and parent references are never a
        // plain file name, and they are the shape of a traversal attempt.
        if (trimmed.contains('/') || trimmed.contains('\\')) return false
        if (trimmed.contains("..")) return false
        val norm = FileNameNormalize.normalize(trimmed)
        if (norm.isEmpty() || norm in VAGUE_TARGETS) return false
        if (norm.length < 3) return false
        // A lone generic word ("فیلم", "movie", "عکس") is not an identity.
        val tokens = norm.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        if (tokens.size == 1 && FileNameNormalize.mediaKindFor(tokens[0]) != MediaKind.ANY) return false
        // A target made only of generic words is a bulk request, not an identity.
        if (tokens.size > 1 && tokens.all { isGenericToken(it) }) return false
        return true
    }

    private fun isGenericToken(token: String): Boolean =
        token in GENERIC_WORDS ||
            token in VAGUE_TARGETS ||
            FileNameNormalize.mediaKindFor(token) != MediaKind.ANY

    /**
     * Second line of defense, executed right before deleting: the confirmed
     * name must equal the resolved file (extension optional, so voice saying
     * "godfather" matches "godfather.mp4" — but only when it is the single
     * resolved candidate).
     */
    fun confirmDelete(requestedExactName: String, candidate: FileRecord): Boolean {
        if (!isDeleteTargetValid(requestedExactName)) return false
        val want = FileNameNormalize.normalize(requestedExactName)
        val gotName = FileNameNormalize.normalize(candidate.name)
        val gotBase = FileNameNormalize.normalize(candidate.nameWithoutExtension)
        return want == gotName || want == gotBase
    }

    sealed interface DeleteDecision {
        data class NeedsConfirmation(val candidate: FileRecord) : DeleteDecision
        data class Blocked(val reason: String) : DeleteDecision
    }

    /** Gate every delete through here: vague/empty/ambiguous requests are Blocked, never executed. */
    fun decideDelete(requestedExactName: String, matches: List<FileRecord>): DeleteDecision {
        if (!isDeleteTargetValid(requestedExactName)) {
            return DeleteDecision.Blocked("Delete needs an exact file name, never a vague or wildcard target")
        }
        if (matches.isEmpty()) return DeleteDecision.Blocked("No file matches that exact name")
        if (matches.size > 1) return DeleteDecision.Blocked("Multiple files match; name one file exactly")
        return DeleteDecision.NeedsConfirmation(matches.single())
    }

    sealed interface PlayResolution {
        data object NotFound : PlayResolution
        data class Launch(val record: FileRecord) : PlayResolution
        data class Disambiguate(val options: List<FileRecord>) : PlayResolution
    }

    /**
     * Opening is exact-or-ask: one match launches, several matches ask the
     * user to pick, zero matches fail. A random file is never opened.
     */
    fun resolvePlay(matches: List<FileRecord>): PlayResolution = when {
        matches.isEmpty() -> PlayResolution.NotFound
        matches.size == 1 -> PlayResolution.Launch(matches.single())
        else -> PlayResolution.Disambiguate(matches.take(5))
    }
}
