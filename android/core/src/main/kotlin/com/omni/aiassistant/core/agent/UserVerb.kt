package com.omni.aiassistant.core.agent

/**
 * A custom verb the user defined for Arman.
 *
 * The idea is simple: the user picks a word ("تیک") and from then on that
 * word always means one concrete action ("scroll one screen down"). Arman
 * receives the dictionary in every turn, so the mapping is part of the
 * contract rather than something the model has to guess.
 */
enum class VerbAction {
    /** Scroll one screen down. */
    SCROLL_DOWN,

    /** Scroll one screen up. */
    SCROLL_UP,

    /** Take a screenshot of the current screen. */
    SCREENSHOT,

    /** Read the visible screen. */
    READ_SCREEN,

    /** Open an app; [UserVerb.target] carries the app label or package. */
    OPEN_APP,

    /** Say something out loud; [UserVerb.target] carries the text. */
    SAY,
}

data class UserVerb(
    val id: String,
    val phrase: String,
    val action: VerbAction,
    val target: String? = null,
    val createdAt: Long = 0L,
) {
    /**
     * The instruction line that is handed to the model. English on purpose:
     * models follow English tool documentation more reliably, and the user's
     * own word is quoted verbatim inside it.
     */
    fun toInstruction(): String {
        val quoted = "\"$phrase\""
        return when (action) {
            VerbAction.SCROLL_DOWN -> "$quoted means: scroll the current screen down by one page (call ui_scroll with direction=down)."
            VerbAction.SCROLL_UP -> "$quoted means: scroll the current screen up by one page (call ui_scroll with direction=up)."
            VerbAction.SCREENSHOT -> "$quoted means: take a screenshot of the current screen (call screenshot)."
            VerbAction.READ_SCREEN -> "$quoted means: read the visible screen (call screen_read)."
            VerbAction.OPEN_APP -> {
                val app = target?.takeIf { it.isNotBlank() }
                    ?: "the app the user just named"
                "$quoted means: open $app (call ui_open_app)."
            }
            VerbAction.SAY -> {
                val text = target?.takeIf { it.isNotBlank() } ?: "the text the user just gave"
                "$quoted means: say \"$text\" out loud (call tts_speak)."
            }
        }
    }
}

/**
 * Normalises a spoken or typed phrase so that "آرمان"، "آرمان!" and "آرمان؟"
 * all match the same wake word.
 *
 * Arabic and Persian letters that differ only in presentation (ي/ی, ك/ک)
 * are folded, Arabic diacritics are removed and whitespace is collapsed.
 */
fun normalizeWakePhrase(raw: String): String {
    val builder = StringBuilder(raw.length)
    var lastWasSpace = true
    for (character in raw.trim().lowercase()) {
        val folded = when (character) {
            'ي', 'ى' -> 'ی'
            'ك', 'ﻙ', 'ﻚ', 'ﻛ', 'ﻜ' -> 'ک'
            'ۀ', 'ة' -> 'ه'
            'ؤ' -> 'و'
            'إ', 'أ', 'آ', 'ٱ' -> 'ا'
            '’', '‘', '`' -> ' '
            '‌' -> ' '
            in 'ً'..'ٰ' -> null // Arabic diacritics
            in '۰'..'۹' -> ('0' + (character - '۰'))
            in '٠'..'٩' -> ('0' + (character - '٠'))
            else -> character
        }
        if (folded == null) continue
        if (folded.isWhitespace()) {
            // Whitespace is collapsed, never dropped: the word boundaries are
            // what stops "notaraman" from triggering the wake word.
            if (!lastWasSpace) {
                builder.append(' ')
                lastWasSpace = true
            }
            continue
        }
        builder.append(folded)
        lastWasSpace = false
    }
    return builder.toString().trim()
}

/**
 * True when [spoken] contains the wake word [phrase].
 *
 * The match is deliberately loose about punctuation and spacing but strict
 * about the word itself, so "آرمان" triggers and "آرمانی" does not.
 */
fun containsWakePhrase(spoken: String, phrase: String): Boolean {
    val needle = normalizeWakePhrase(phrase)
    if (needle.isEmpty()) return false
    val haystack = normalizeWakePhrase(spoken)
    if (haystack.isEmpty()) return false
    if (haystack == needle) return true
    val index = haystack.indexOf(needle)
    if (index < 0) return false
    val before = haystack.getOrNull(index - 1)
    val after = haystack.getOrNull(index + needle.length)
    val boundaryBefore = before == null || !before.isLetterOrDigit()
    val boundaryAfter = after == null || !after.isLetterOrDigit()
    return boundaryBefore && boundaryAfter
}
