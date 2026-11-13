package com.omni.aiassistant.core.network

import com.omni.aiassistant.core.error.AppError
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

object ApiErrorMapper {
    fun fromHttp(code: Int, message: String?): AppError {
        val safe = sanitizeProviderMessage(message)
        return when (code) {
            401, 403 -> AppError.Authentication(safe.ifBlank { "Authentication failed" })
            408, 504 -> AppError.Timeout(safe.ifBlank { "Provider request timed out" })
            429 -> if (safe.contains("credit", true) || safe.contains("quota", true) || safe.contains("billing", true)) {
                AppError.InsufficientCredit(safe.ifBlank { "Insufficient quota" })
            } else AppError.RateLimit(safe.ifBlank { "Rate limit reached" })
            404 -> AppError.ModelUnavailable(safe.ifBlank { "Endpoint or model was not found" })
            400, 413, 422 -> when {
                safe.contains("context", true) || safe.contains("token", true) && safe.contains("limit", true) -> AppError.ContextLength(safe)
                safe.contains("model", true) -> AppError.ModelUnavailable(safe)
                else -> AppError.Provider(safe.ifBlank { "Provider rejected the request" })
            }
            in 500..599 -> AppError.Provider(safe.ifBlank { "Provider server error ($code)" })
            else -> AppError.Provider(safe.ifBlank { "Provider returned HTTP $code" })
        }
    }

    fun fromThrowable(t: Throwable): AppError = when (t) {
        is kotlinx.coroutines.CancellationException -> throw t
        is SocketTimeoutException -> AppError.Timeout("Request timed out", t)
        is SSLException -> AppError.Tls("TLS connection failed", t)
        is IOException -> AppError.Network("Network request failed", t)
        is AppError -> t
        else -> AppError.Provider(t.message?.take(300) ?: "Unexpected provider error")
    }
    private fun sanitizeProviderMessage(message: String?): String = message.orEmpty()
        .replace(Regex("(?i)\\bBearer\\s+[^\\s,]+"), "Bearer <redacted>")
        .replace(Regex("(?i)\\b(authorization|api[-_ ]?key|access[-_ ]?token|api[-_ ]?secret|key|token|secret)\\s*[:=]\\s*[^,\\s]+"), "$1=<redacted>")
        .replace(Regex("(?i)(sk-|nvapi-|gsk_)[A-Za-z0-9._-]{8,}"), "<redacted>")
        .replace(Regex("AIza[0-9A-Za-z_-]{20,}"), "<redacted>")
        .take(600)

}
