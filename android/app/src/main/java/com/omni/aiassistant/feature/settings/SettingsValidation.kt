package com.omni.aiassistant.feature.settings

import com.omni.aiassistant.device.HermesBridge
import java.net.URI

internal const val MAX_HERMES_URL_LENGTH = 2048
internal const val MAX_HERMES_TOKEN_LENGTH = 4096

internal val defaultHermesUrl: String
    get() = HermesBridge.DEFAULT_BASE_URL

/**
 * Returns the value that should be stored, or null when the input is not a
 * usable bridge address. A blank field deliberately restores the built-in
 * default instead of persisting an empty endpoint.
 */
internal fun normalizeHermesUrl(raw: String): String? {
    val trimmed = raw.trim().take(MAX_HERMES_URL_LENGTH)
    val candidate = trimmed.ifEmpty { defaultHermesUrl }.trimEnd('/')
    return candidate.takeIf(::isValidHermesUrl)
}

/**
 * The bridge appends `/status` and `/execute` to the base address, so query
 * strings, fragments and embedded credentials are rejected rather than being
 * silently concatenated into a broken URL.
 */
internal fun isValidHermesUrl(value: String): Boolean {
    if (value.isBlank() || value.length > MAX_HERMES_URL_LENGTH) return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    if (uri.host.isNullOrBlank()) return false
    if (uri.userInfo != null || uri.query != null || uri.fragment != null) return false
    return uri.port == -1 || uri.port in 1..65535
}

internal fun isCleartextHermesUrl(value: String): Boolean =
    value.trim().startsWith("http://", ignoreCase = true)
