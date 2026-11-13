package com.omni.aiassistant.device

import com.omni.aiassistant.core.device.FileNameNormalize
import com.omni.aiassistant.core.device.FileSafetyPolicy
import com.omni.aiassistant.core.device.MediaKind

sealed interface DeviceAction {
    data class WebSearch(val query: String) : DeviceAction
    data class Dial(val phoneNumber: String) : DeviceAction
    data class Sms(val phoneNumber: String, val body: String) : DeviceAction
    data class Email(val address: String, val subject: String) : DeviceAction
    data object Camera : DeviceAction
    data object Gallery : DeviceAction
    data object Contacts : DeviceAction
    data class Maps(val query: String) : DeviceAction
    data object AppSettings : DeviceAction
    data object DeviceInfo : DeviceAction
    /** Read-only: never mutates, never needs confirmation. */
    data class FindFile(val query: String, val kind: MediaKind) : DeviceAction
    /** Opens exactly one match; several matches ask the user to pick. */
    data class PlayFile(val query: String, val kind: MediaKind) : DeviceAction
    /** url = direct download; query = name-based (runner asks for a link). */
    data class DownloadFile(val url: String?, val query: String?) : DeviceAction
    /**
     * Produced ONLY for an explicit delete verb plus an exact, non-vague
     * name. Execution additionally requires a precise confirmation carrying
     * the resolved file identity (see FileSafetyPolicy).
     */
    data class DeleteFile(val exactName: String) : DeviceAction
    /**
     * Set the phone wallpaper from an attached image. Produced ONLY for an
     * explicit wallpaper verb; the image must already be attached to the
     * chat (bound by ChatViewModel). Visible but reversible: no confirmation
     * dialog, result is shown instead.
     */
    data class SetWallpaper(val query: String, val localPath: String? = null) : DeviceAction
}

data class ParsedDeviceCommands(val actions: List<DeviceAction>)

object DeviceCommandParser {
    fun parse(raw: String): ParsedDeviceCommands? {
        val trimmed = raw.trim()
        val commandText = when {
            trimmed.startsWith("/device", true) -> trimmed.drop(7).trim()
            trimmed.startsWith("device:", true) -> trimmed.substringAfter(':').trim()
            trimmed.startsWith("گوشی:") -> trimmed.substringAfter(':').trim()
            else -> return null
        }
        if (commandText.isBlank()) return ParsedDeviceCommands(emptyList())
        val parts = commandText.split(Regex("\\s+(?:then|سپس)\\s+|;", RegexOption.IGNORE_CASE))
            .map { it.trim() }.filter { it.isNotBlank() }.take(4)
        val actions = parts.mapNotNull(::parseOne)
        return ParsedDeviceCommands(actions)
    }

    private fun parseOne(rawText: String): DeviceAction? {
        var text = rawText.trim()
        // Voice-style politeness/go prefixes: "برو ...", "لطفا ..."
        if (text.startsWith("برو ")) text = text.drop(4).trim()
        if (text.startsWith("لطفا ")) text = text.drop(5).trim()
        val lower = text.lowercase()
        // Verb-last (Persian SOV, how people speak): "<thing> رو <verb>"
        parseVerbLast(text)?.let { return it }
        // Explicit wallpaper verbs (noun + verb must both be present).
        wallpaper(text)?.let { return it }
        return when {
            lower == "camera" || text == "دوربین" -> DeviceAction.Camera
            lower == "gallery" || text in setOf("گالری", "تصاویر") -> DeviceAction.Gallery
            lower == "contacts" || text == "مخاطبین" -> DeviceAction.Contacts
            lower in setOf("settings", "app settings") || text == "تنظیمات" -> DeviceAction.AppSettings
            lower in setOf("device info", "info") || text in setOf("اطلاعات دستگاه", "اطلاعات گوشی") -> DeviceAction.DeviceInfo
            lower.startsWith("search file ") || lower.startsWith("search files ") -> {
                // "search files godfather" is a file lookup, not a web search.
                val query = text.substringAfter("search ").trim()
                val cleaned = when {
                    query.startsWith("files ", ignoreCase = true) -> query.substring(6).trim()
                    query.startsWith("file ", ignoreCase = true) -> query.substring(5).trim()
                    else -> query
                }
                findFile(cleaned)
            }
            text.startsWith("جستجوی فایل ") || text.startsWith("جستجوی فایل‌ها ") ->
                findFile(text.substringAfter("جستجوی ").trim())
            lower.startsWith("search ") -> DeviceAction.WebSearch(text.substringAfter(' ').trim().take(500))
            text.startsWith("جستجو ") -> DeviceAction.WebSearch(text.substringAfter(' ').trim().take(500))
            lower.startsWith("dial ") || lower.startsWith("phone ") -> parsePhone(text.substringAfter(' '))?.let(DeviceAction::Dial)
            text.startsWith("شماره ") || text.startsWith("تماس ") -> parsePhone(text.substringAfter(' '))?.let(DeviceAction::Dial)
            lower.startsWith("maps ") -> DeviceAction.Maps(text.substringAfter(' ').trim().take(300))
            text.startsWith("نقشه ") -> DeviceAction.Maps(text.substringAfter(' ').trim().take(300))
            lower.startsWith("sms ") || text.startsWith("پیامک ") -> parseSms(text.substringAfter(' '))
            lower.startsWith("email ") || text.startsWith("ایمیل ") -> parseEmail(text.substringAfter(' '))
            lower.startsWith("find ") || text.startsWith("بگرد ") ->
                findFile(text.substringAfter(' '))
            text.startsWith("پیدا کن ") -> findFile(text.removePrefix("پیدا کن "))
            lower.startsWith("play ") || lower.startsWith("open ") ->
                playFile(text.substringAfter(' '))
            text.startsWith("پخش کن ") -> playFile(text.removePrefix("پخش کن "))
            text.startsWith("اجرا کن ") -> playFile(text.removePrefix("اجرا کن "))
            text.startsWith("باز کن ") -> playFile(text.removePrefix("باز کن "))
            lower.startsWith("download ") -> downloadFile(text.substringAfter(' ').trim())
            text.startsWith("دانلود کن ") -> downloadFile(text.removePrefix("دانلود کن ").trim())
            lower.startsWith("delete ") -> deleteFile(text.substringAfter(' ').trim())
            text.startsWith("پاک کن ") -> deleteFile(text.removePrefix("پاک کن ").trim())
            text.startsWith("حذف کن ") -> deleteFile(text.removePrefix("حذف کن ").trim())
            else -> null
        }
    }

    /** Verb-last forms: "فیلم godfather رو پیدا کن", "godfather رو پخش کن". */
    private fun parseVerbLast(text: String): DeviceAction? {
        val verbs = listOf(
            " رو پیدا کن" to "find", " را پیدا کن" to "find",
            " رو بگرد" to "find", " را بگرد" to "find",
            " رو جستجو کن" to "find", " را جستجو کن" to "find",
            " رو نشون بده" to "find", " را نشون بده" to "find",
            " رو نمایش بده" to "find", " را نمایش بده" to "find",
            " رو پخش کن" to "play", " را پخش کن" to "play",
            " رو اجرا کن" to "play", " را اجرا کن" to "play",
            " رو باز کن" to "play", " را باز کن" to "play",
            " رو دانلود کن" to "download", " را دانلود کن" to "download",
            " رو پاک کن" to "delete", " را پاک کن" to "delete",
            " رو حذف کن" to "delete", " را حذف کن" to "delete",
        )
        for ((suffix, verb) in verbs) {
            if (text.endsWith(suffix)) {
                val middle = text.dropLast(suffix.length).trim()
                return when (verb) {
                    "find" -> findFile(middle)
                    "play" -> playFile(middle)
                    "download" -> downloadFile(middle)
                    else -> deleteFile(middle)
                }
            }
        }
        return null
    }

    private fun splitKindAndQuery(raw: String): Pair<String, MediaKind> {
        var cleaned = raw.trim()
        // drop a trailing object marker left over from verb-last forms
        if (cleaned.endsWith(" رو")) cleaned = cleaned.dropLast(3).trim()
        if (cleaned.endsWith(" را")) cleaned = cleaned.dropLast(3).trim()
        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        var kind = MediaKind.ANY
        val kept = ArrayList<String>(tokens.size)
        for (token in tokens) {
            val tokenKind = FileNameNormalize.mediaKindFor(token)
            if (tokenKind != MediaKind.ANY && kind == MediaKind.ANY) {
                kind = tokenKind
            } else if (tokenKind != MediaKind.ANY) {
                kept.add(token)
            } else if (token != "یه" && token != "یک") {
                kept.add(token)
            }
        }
        return kept.joinToString(" ").take(200) to kind
    }

    private fun findFile(rest: String): DeviceAction.FindFile? {
        val (query, kind) = splitKindAndQuery(rest)
        // Empty query + a media kind means "list my movies/photos" — allowed (read-only).
        if (query.isBlank() && kind == MediaKind.ANY) return null
        return DeviceAction.FindFile(query, kind)
    }

    private fun playFile(rest: String): DeviceAction.PlayFile? {
        val (query, kind) = splitKindAndQuery(rest)
        if (query.isBlank()) return null
        return DeviceAction.PlayFile(query, kind)
    }

    private fun downloadFile(rest: String): DeviceAction.DownloadFile? {
        if (rest.isBlank()) return null
        val first = rest.split(Regex("\\s+")).first()
        return if (first.startsWith("http://") || first.startsWith("https://")) {
            DeviceAction.DownloadFile(url = first.take(2000), query = null)
        } else {
            val (query, _) = splitKindAndQuery(rest)
            if (query.isBlank()) null else DeviceAction.DownloadFile(url = null, query = query)
        }
    }

    private val WALLPAPER_NOUNS = listOf(
        "بک‌گراند", "بک گراند", "والپیپر", "پس‌زمینه", "پس زمینه",
        "wallpaper", "background",
    )
    private val WALLPAPER_VERBS = listOf(
        "بکن", "بذار", "بگذار", "بزار", "ست کن", " کن",
        "set", "make",
    )

    /**
     * Wallpaper needs an explicit noun AND verb ("اینو بک‌گراند کن").
     * Bare nouns without a verb yield nothing, so viewing/searching text
     * about wallpapers can never trigger it.
     */
    private fun wallpaper(text: String): DeviceAction.SetWallpaper? {
        val lower = text.lowercase()
        if (WALLPAPER_NOUNS.none { text.contains(it) || lower.contains(it) }) return null
        if (WALLPAPER_VERBS.none { text.contains(it) || lower.contains(it) }) return null
        return DeviceAction.SetWallpaper(text.take(200))
    }

    private fun deleteFile(rest: String): DeviceAction.DeleteFile? {        var target = rest.trim()
        if (target.endsWith(" رو")) target = target.dropLast(3).trim()
        if (target.endsWith(" را")) target = target.dropLast(3).trim()
        val exact = target.take(200)
        // The policy rejects vague/wildcard targets: such commands yield NO
        // action at all, so nothing can ever be deleted by mistake.
        if (!FileSafetyPolicy.isDeleteTargetValid(exact)) return null
        return DeviceAction.DeleteFile(exact)
    }

    private fun parsePhone(raw: String): String? = raw.trim().takeIf { PHONE.matches(it) }

    private fun parseSms(raw: String): DeviceAction.Sms? {
        val number = raw.substringBefore(' ').trim()
        val body = raw.substringAfter(' ', "").trim().take(1000)
        return parsePhone(number)?.let { DeviceAction.Sms(it, body) }
    }

    private fun parseEmail(raw: String): DeviceAction.Email? {
        val address = raw.substringBefore(' ').trim()
        val subject = raw.substringAfter(' ', "").trim().take(300)
        return address.takeIf { EMAIL.matches(it) }?.let { DeviceAction.Email(it, subject) }
    }

    private val PHONE = Regex("^\\+?[0-9() -]{3,30}$")
    private val EMAIL = Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$")
}
