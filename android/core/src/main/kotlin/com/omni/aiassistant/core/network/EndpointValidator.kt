package com.omni.aiassistant.core.network

import com.omni.aiassistant.core.error.AppError
import com.omni.aiassistant.domain.model.ProviderProfile
import java.net.URI

/**
 * Platform-independent endpoint validation.
 *
 * Cleartext policy is supplied explicitly by each platform entry point:
 * - Android: `BuildConfig.ALLOW_LOCAL_CLEARTEXT` (true only in debug builds).
 * - Desktop: `localDevelopmentEnabled` (cleartext is opt-in at runtime and only for local/private hosts).
 */
object EndpointValidator {
    fun validate(
        profile: ProviderProfile,
        localDevelopmentEnabled: Boolean,
        allowCleartext: Boolean,
    ) {
        if (profile.name.isBlank()) throw AppError.Validation("Profile name is required")
        if (profile.modelId.isBlank()) throw AppError.Validation("Model ID is required")
        if (ProviderPresets.forType(profile.type).apiKeyRequired && profile.apiKey.isBlank()) {
            throw AppError.Validation("API key is required for ${profile.type.name}")
        }
        if (profile.timeoutSeconds !in 5..300) throw AppError.Validation("Timeout must be between 5 and 300 seconds")
        if (profile.maxOutputTokens !in 1..65_536) throw AppError.Validation("Maximum output tokens are out of range")
        if (profile.temperature !in 0.0..2.0) throw AppError.Validation("Temperature must be between 0 and 2")
        validateBaseUrl(profile.baseUrl, localDevelopmentEnabled, allowCleartext)
        validateEndpointPath(profile.endpointPath)
        validateHeaders(profile.customHeaders)
    }

    fun validateBaseUrl(baseUrl: String, localDevelopmentEnabled: Boolean, allowCleartext: Boolean) {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrElse { throw AppError.Endpoint("Invalid base URL") }
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) throw AppError.Endpoint("Base URL cannot contain credentials, query parameters, or fragments")
        val host = uri.host ?: throw AppError.Endpoint("Base URL must contain a valid host")
        when (uri.scheme?.lowercase()) {
            "https" -> Unit
            "http" -> {
                if (!allowCleartext || !localDevelopmentEnabled) {
                    throw AppError.Endpoint("HTTP endpoints are allowed only in Debug with Local development enabled")
                }
                if (!isLocalOrPrivateHost(host)) throw AppError.Endpoint("Cleartext HTTP is restricted to local/private hosts")
            }
            else -> throw AppError.Endpoint("Only HTTPS is allowed, except local Debug HTTP")
        }
    }

    fun buildEndpoint(baseUrl: String, endpointPath: String): String =
        baseUrl.trim().trimEnd('/') + "/" + endpointPath.trim().trim('/').ifBlank { "chat/completions" }

    private fun validateEndpointPath(path: String) {
        val value = path.trim()
        if (value.contains("?") || value.contains("#") || value.contains("\\")) throw AppError.Endpoint("Endpoint path is invalid")
        if (value.contains("..")) throw AppError.Endpoint("Endpoint path cannot contain '..'")
    }

    private fun validateHeaders(headers: Map<String, String>) {
        val forbidden = setOf("authorization", "proxy-authorization", "host", "content-length", "content-type", "connection")
        headers.forEach { (name, value) ->
            val normalized = name.trim().lowercase()
            if (normalized.isBlank() || normalized in forbidden) throw AppError.Validation("Custom header '$name' is not allowed")
            if (!HEADER_NAME.matches(name.trim())) throw AppError.Validation("Invalid custom header name: $name")
            if (value.contains('\r') || value.contains('\n')) throw AppError.Validation("Custom header values cannot contain new lines")
        }
    }

    internal fun isLocalOrPrivateHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h == "::1" || h.endsWith(".localhost")) return true
        if (h.startsWith("fc") || h.startsWith("fd")) return h.contains(":")
        val parts = h.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        return nums[0] == 10 ||
            nums[0] == 127 ||
            (nums[0] == 192 && nums[1] == 168) ||
            (nums[0] == 172 && nums[1] in 16..31) ||
            (nums[0] == 169 && nums[1] == 254)
    }

    private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
}
