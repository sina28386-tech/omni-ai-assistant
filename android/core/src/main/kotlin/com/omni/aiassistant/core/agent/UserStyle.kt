package com.omni.aiassistant.core.agent

import kotlinx.serialization.Serializable

/**
 * How this user talks and what they expect from an answer.
 *
 * The profile is learned from the user's own words - never guessed from a
 * single ambiguous sentence - and every field is optional. An empty profile
 * means "no evidence yet", not "use defaults".
 */
@Serializable
data class UserStyle(
    /** What the user wants to be called. */
    val name: String = "",
    /** BCP-47-ish language code, or "mixed". */
    val language: String = "",
    /** informal / neutral / formal */
    val formality: String = "",
    /** short / normal / detailed */
    val verbosity: String = "",
    /** never / sometimes */
    val emoji: String = "",
    /** Explicit standing instructions, quoted from the user. */
    val notes: List<String> = emptyList(),
    val updatedAt: Long = 0L,
    /** How many explicit signals produced this profile. */
    val evidenceCount: Int = 0,
) {
    val isEmpty: Boolean
        get() = name.isBlank() && language.isBlank() && formality.isBlank() &&
            verbosity.isBlank() && emoji.isBlank() && notes.isEmpty()

    fun with(update: StyleLearner.Update, now: Long): UserStyle = when (update.field) {
        StyleLearner.Field.NAME -> copy(name = update.value, updatedAt = now)
        StyleLearner.Field.LANGUAGE -> copy(language = update.value, updatedAt = now)
        StyleLearner.Field.FORMALITY -> copy(formality = update.value, updatedAt = now)
        StyleLearner.Field.VERBOSITY -> copy(verbosity = update.value, updatedAt = now)
        StyleLearner.Field.EMOJI -> copy(emoji = update.value, updatedAt = now)
        StyleLearner.Field.NOTE -> copy(
            notes = (notes + update.value)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .takeLast(MAX_NOTES),
            updatedAt = now,
        )
    }.copy(evidenceCount = evidenceCount + 1)

    private companion object {
        const val MAX_NOTES = 8
    }
}

/**
 * Turns the user's own sentences into durable style preferences.
 *
 * Deterministic on purpose:
 * - the same message always yields the same updates, so it is testable;
 * - nothing is inferred from a vague sentence - only explicit instructions
 *   ("be brief", "from now on ...") change the profile;
 * - the user can see and reset every learned field in the settings screen.
 *
 * The learner runs *before* the prompt is built, so "from now on answer in
 * English" applies to the very same turn.
 */
object StyleLearner {

    enum class Field { NAME, LANGUAGE, FORMALITY, VERBOSITY, EMOJI, NOTE }

    data class Update(
        val field: Field,
        val value: String,
        /** The fragment that triggered the update, for the settings screen. */
        val evidence: String,
    )

    private val NAME_PATTERNS = listOf(
        Regex("""(?:منو|من\s*را|مرا|به\s+من)\s+(?:صدا(?:م)?\s*کن|صداش\s*کن)\s+([^،.!؟\n]{2,30})"""),
        Regex("""(?:صدایم|صدام)\s*(?:کن|بزن)\s*([^،.!؟\n]{2,30})"""),
        Regex("""\bcall\s+me\s+([A-Za-z][\w .\-]{0,30})""", RegexOption.IGNORE_CASE),
    )

    private val LANGUAGE_EN = listOf(
        Regex("""(?:جواب|پاسخ|حرف\s*بزن|بنویس)\s*(?:رو|را)?\s*انگلیسی"""),
        Regex("""انگلیسی\s*(?:جواب|پاسخ|حرف\s*بزن|بنویس)"""),
        Regex("""\b(?:answer|reply|speak|write|talk)\s+(?:to\s+me\s+)?in\s+english\b""", RegexOption.IGNORE_CASE),
        Regex("""\benglish\s+(?:please|from\s+now\s+on)\b""", RegexOption.IGNORE_CASE),
    )

    private val LANGUAGE_FA = listOf(
        Regex("""(?:جواب|پاسخ|حرف\s*بزن|بنویس)\s*(?:رو|را)?\s*فارسی"""),
        Regex("""فارسی\s*(?:جواب|پاسخ|حرف\s*بزن|بنویس)"""),
        Regex("""\b(?:answer|reply|speak|write|talk)\s+(?:to\s+me\s+)?in\s+(?:persian|farsi)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:persian|farsi)\s+(?:please|from\s+now\s+on)\b""", RegexOption.IGNORE_CASE),
    )

    private val LANGUAGE_MIXED = listOf(
        Regex("""(?:دوزبانه|قاطی|مخلوط)\s*(?:جواب|حرف|بنویس)"""),
        Regex("""\b(?:mixed|bilingual)\s+(?:language|reply|answers?)\b""", RegexOption.IGNORE_CASE),
    )

    private val FORMAL = listOf(
        Regex("""(?<!غیر)(?<!غیر\s)(?<!غیر\u200c)(?:رسمی|محترمانه)\s*(?:جواب|حرف|بنویس|باشه)?"""),
        Regex("""\bformal(?:ly)?\b""", RegexOption.IGNORE_CASE),
    )

    private val INFORMAL = listOf(
        Regex("""(?:خودمونی|دوستانه|غیر[\s\u200c]*رسمی|بی[\s\u200c]*تکلف)"""),
        Regex("""\b(?:casual|informal|friendly)\b""", RegexOption.IGNORE_CASE),
    )

    private val SHORT = listOf(
        Regex("""(?:جواب|پاسخ|جوابت|پاسخت|حرفت)\s*(?:رو|را)?\s*(?:خیلی\s+)?کوتاه"""),
        Regex("""کوتاه(?:تر)?\s*(?:جواب|بگو|بنویس|باشه|بده)"""),
        Regex("""(?:خلاصه|مختصر)\s*(?:بگو|جواب|بنویس|باشه)"""),
        Regex("""\b(?:be|keep\s+it|reply|answer)\s+(?:brief|short|concise)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bshort\s+answers?\b""", RegexOption.IGNORE_CASE),
        Regex("""\btl;?dr\b""", RegexOption.IGNORE_CASE),
    )

    private val DETAILED = listOf(
        Regex("""(?:بیشتر|کاملتر|کامل)\s*(?:توضیح|بگو|بنویس|شرح)"""),
        Regex("""(?:جزئیات|مفصل|شرح\s+بده)"""),
        Regex("""\b(?:detailed|in\s+depth|explain\s+more|more\s+detail)\b""", RegexOption.IGNORE_CASE),
    )

    private val NO_EMOJI = listOf(
        Regex("""(?:بدون|بی)\s*ایموجی"""),
        Regex("""ایموجی\s*(?:نزن|نفرست|نذار|استفاده\s*نکن)"""),
        Regex("""\bno\s+emoji\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwithout\s+emoji\b""", RegexOption.IGNORE_CASE),
    )

    private val WITH_EMOJI = listOf(
        Regex("""با\s*ایموجی"""),
        Regex("""ایموجی\s*(?:بزن|بذار|استفاده\s*کن)"""),
        Regex("""\buse\s+emoji\b""", RegexOption.IGNORE_CASE),
    )

    private val STANDING = listOf(
        Regex("""از\s+(?:این|الان|حالا)\s+به\s+بعد[،,:]?\s+(.{3,140})"""),
        Regex("""\bfrom\s+now\s+on[,\s]+(.{3,140})""", RegexOption.IGNORE_CASE),
    )

    fun observe(raw: String): List<Update> {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 2_000) return emptyList()
        val updates = mutableListOf<Update>()

        firstMatch(text, NAME_PATTERNS)?.let { fragment ->
            val name = fragment.groupValues[1].trim().trimEnd('.', '،', '!', '?')
            if (name.length in 2..30) updates += Update(Field.NAME, name, fragment.value)
        }
        when {
            matches(text, LANGUAGE_MIXED) -> updates += Update(Field.LANGUAGE, "mixed", firstFragment(text, LANGUAGE_MIXED))
            matches(text, LANGUAGE_EN) -> updates += Update(Field.LANGUAGE, "en", firstFragment(text, LANGUAGE_EN))
            matches(text, LANGUAGE_FA) -> updates += Update(Field.LANGUAGE, "fa", firstFragment(text, LANGUAGE_FA))
        }
        when {
            matches(text, INFORMAL) -> updates += Update(Field.FORMALITY, "informal", firstFragment(text, INFORMAL))
            matches(text, FORMAL) -> updates += Update(Field.FORMALITY, "formal", firstFragment(text, FORMAL))
        }
        when {
            matches(text, SHORT) -> updates += Update(Field.VERBOSITY, "short", firstFragment(text, SHORT))
            matches(text, DETAILED) -> updates += Update(Field.VERBOSITY, "detailed", firstFragment(text, DETAILED))
        }
        when {
            matches(text, NO_EMOJI) -> updates += Update(Field.EMOJI, "never", firstFragment(text, NO_EMOJI))
            matches(text, WITH_EMOJI) -> updates += Update(Field.EMOJI, "sometimes", firstFragment(text, WITH_EMOJI))
        }
        for (pattern in STANDING) {
            val match = pattern.find(text) ?: continue
            val note = match.groupValues[1].trim().trimEnd('.', '،')
            if (note.length in 3..140) {
                updates += Update(Field.NOTE, note, match.value.take(160))
                break
            }
        }
        return updates
    }

    private fun matches(text: String, patterns: List<Regex>): Boolean =
        patterns.any { it.containsMatchIn(text) }

    private fun firstMatch(text: String, patterns: List<Regex>): MatchResult? =
        patterns.firstNotNullOfOrNull { it.find(text) }

    private fun firstFragment(text: String, patterns: List<Regex>): String =
        firstMatch(text, patterns)?.value?.take(80).orEmpty()
}
