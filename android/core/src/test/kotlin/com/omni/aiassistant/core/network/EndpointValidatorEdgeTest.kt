package com.omni.aiassistant.core.network

import com.omni.aiassistant.core.error.AppError
import com.omni.aiassistant.domain.model.ProviderProfile
import com.omni.aiassistant.domain.model.ProviderType
import com.omni.aiassistant.domain.model.TokenParameter
import org.junit.Assert.*
import org.junit.Test

/** Edge coverage for [EndpointValidator] URL, profile, path, and header rules. */
class EndpointValidatorEdgeTest {
    private fun profile(
        name: String = "Test",
        type: ProviderType = ProviderType.OPENAI,
        baseUrl: String = "https://api.example.com/v1",
        apiKey: String = "sk-test",
        modelId: String = "gpt-test",
        timeoutSeconds: Int = 30,
        maxOutputTokens: Int = 1024,
        temperature: Double = 0.7,
        customHeaders: Map<String, String> = emptyMap(),
        endpointPath: String = "/chat/completions",
    ) = ProviderProfile(
        id = "id",
        name = name,
        type = type,
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelId = modelId,
        organizationId = null,
        systemPrompt = "",
        temperature = temperature,
        maxOutputTokens = maxOutputTokens,
        timeoutSeconds = timeoutSeconds,
        supportsVision = false,
        supportsStreaming = true,
        enabled = true,
        customHeaders = customHeaders,
        endpointPath = endpointPath,
        tokenParameter = TokenParameter.AUTO,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun assertValidation(block: () -> Unit) {
        assertThrows(AppError.Validation::class.java) { block() }
    }

    private fun assertEndpoint(block: () -> Unit) {
        assertThrows(AppError.Endpoint::class.java) { block() }
    }

    @Test fun profileFieldBoundaries() {
        EndpointValidator.validate(profile(), false, false)
        EndpointValidator.validate(profile(timeoutSeconds = 5), false, false)
        EndpointValidator.validate(profile(timeoutSeconds = 300), false, false)
        EndpointValidator.validate(profile(maxOutputTokens = 1), false, false)
        EndpointValidator.validate(profile(maxOutputTokens = 65_536), false, false)
        EndpointValidator.validate(profile(temperature = 0.0), false, false)
        EndpointValidator.validate(profile(temperature = 2.0), false, false)

        assertValidation { EndpointValidator.validate(profile(name = " "), false, false) }
        assertValidation { EndpointValidator.validate(profile(modelId = ""), false, false) }
        assertValidation { EndpointValidator.validate(profile(timeoutSeconds = 4), false, false) }
        assertValidation { EndpointValidator.validate(profile(timeoutSeconds = 301), false, false) }
        assertValidation { EndpointValidator.validate(profile(maxOutputTokens = 0), false, false) }
        assertValidation { EndpointValidator.validate(profile(maxOutputTokens = 65_537), false, false) }
        assertValidation { EndpointValidator.validate(profile(temperature = -0.01), false, false) }
        assertValidation { EndpointValidator.validate(profile(temperature = 2.01), false, false) }
        assertValidation { EndpointValidator.validate(profile(temperature = Double.NaN), false, false) }
    }

    @Test fun apiKeyIsOnlyRequiredForPresetsThatNeedIt() {
        assertValidation { EndpointValidator.validate(profile(apiKey = ""), false, false) }
        EndpointValidator.validate(profile(type = ProviderType.OLLAMA, apiKey = ""), false, false)
        EndpointValidator.validate(profile(type = ProviderType.CUSTOM, apiKey = ""), false, false)
    }

    @Test fun baseUrlSchemeAndHostPolicy() {
        EndpointValidator.validateBaseUrl(" HTTPS://api.example.com/v1 ", false, false)

        listOf(
            "http://localhost:8080/v1",
            "http://127.0.0.1:8080/v1",
            "http://127.9.9.9:8080/v1",
            "http://10.0.2.2:11434/v1",
            "http://192.168.1.20/v1",
            "http://172.16.0.1/v1",
            "http://172.31.255.255/v1",
            "http://169.254.1.1/v1",
            "http://[::1]:8080/v1",
        ).forEach { url ->
            EndpointValidator.validateBaseUrl(url, localDevelopmentEnabled = true, allowCleartext = true)
        }

        listOf(
            "http://example.com/v1",
            "http://8.8.8.8/v1",
            "http://172.32.0.1/v1",
            "http://172.15.255.255/v1",
            "http://192.169.1.1/v1",
            "http://11.0.0.1/v1",
            "http://localhost.evil.com/v1",
            "ftp://example.com/v1",
            "not a url",
            "https:///v1",
            "https://exa mple.com/v1",
            "https://example.com:bad/v1",
        ).forEach { url ->
            assertEndpoint {
                EndpointValidator.validateBaseUrl(url, localDevelopmentEnabled = true, allowCleartext = true)
            }
        }
    }

    @Test fun cleartextRequiresBothSwitches() {
        assertEndpoint {
            EndpointValidator.validateBaseUrl("http://10.0.2.2:8080/v1", true, false)
        }
        assertEndpoint {
            EndpointValidator.validateBaseUrl("http://10.0.2.2:8080/v1", false, true)
        }
    }

    @Test fun baseUrlRejectsCredentialsQueryAndFragment() {
        listOf(
            "https://user:pass@example.com/v1",
            "https://user@example.com/v1",
            "https://example.com/v1?key=x",
            "https://example.com/v1#frag",
        ).forEach { url ->
            assertEndpoint { EndpointValidator.validateBaseUrl(url, false, false) }
        }
    }

    @Test fun endpointPathAndCustomHeaderRules() {
        EndpointValidator.validate(profile(endpointPath = ""), false, false)
        EndpointValidator.validate(
            profile(customHeaders = mapOf("X-Test" to "value", " X-Other " to "")),
            false,
            false,
        )

        listOf("../chat", "a?b", "a#b", "a\\b").forEach { path ->
            assertEndpoint { EndpointValidator.validate(profile(endpointPath = path), false, false) }
        }

        listOf(
            mapOf("Authorization" to "x"),
            mapOf("proxy-authorization" to "x"),
            mapOf("Host" to "x"),
            mapOf("Content-Length" to "1"),
            mapOf("Content-Type" to "x"),
            mapOf("Connection" to "close"),
            mapOf("" to "x"),
            mapOf("Bad Header" to "x"),
            mapOf("X-Test" to "a\nb"),
            mapOf("X-Test" to "a\rb"),
        ).forEach { headers ->
            assertValidation {
                EndpointValidator.validate(profile(customHeaders = headers), false, false)
            }
        }
    }

    @Test fun buildEndpointNormalizesSlashesAndDefaultsPath() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            EndpointValidator.buildEndpoint(" https://api.example.com/v1/ ", "/chat/completions"),
        )
        assertEquals(
            "https://api.example.com/v1/models",
            EndpointValidator.buildEndpoint("https://api.example.com/v1///", "///models"),
        )
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            EndpointValidator.buildEndpoint("https://api.example.com/v1/", "/"),
        )
        assertEquals("/chat/completions", EndpointValidator.buildEndpoint("  ", " "))
    }

    @Test fun privateHostDetectionEdges() {
        listOf(
            "localhost", "LOCALHOST", "app.localhost", "::1", "[::1]",
            "fc00::1", "[fd12:3456::1]", "127.0.0.1", "127.255.255.255",
            "10.0.0.1", "10.255.255.255", "192.168.0.1", "192.168.255.255",
            "172.16.0.0", "172.31.255.255", "169.254.10.20",
        ).forEach { host ->
            assertTrue("Expected private/local: $host", EndpointValidator.isLocalOrPrivateHost(host))
        }

        listOf(
            "", "localhost.evil.com", "evil-localhost", "8.8.8.8", "0.0.0.0",
            "11.0.0.1", "192.169.1.1", "172.15.0.1", "172.32.0.1",
            "169.253.1.1", "10.0.0.256", "1.2.3", "1.2.3.4.5", "1.2.3.a",
            "fe80::1", "fc00", "fd00", "192.168.1.1:8080",
        ).forEach { host ->
            assertFalse("Expected public/invalid: $host", EndpointValidator.isLocalOrPrivateHost(host))
        }
    }
}
