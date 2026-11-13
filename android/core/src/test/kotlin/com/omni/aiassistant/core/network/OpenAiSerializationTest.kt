package com.omni.aiassistant.core.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class OpenAiSerializationTest {
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    @Test fun serializesMaxCompletionTokensWithoutMaxTokens() {
        val request = OpenAiChatRequest(
            model = "reasoning-model",
            messages = listOf(OpenAiMessage("user", JsonPrimitive("hello"))),
            maxTokens = null,
            maxCompletionTokens = 128,
        )
        val encoded = json.encodeToString(request)
        assertTrue(encoded.contains("\"max_completion_tokens\":128"))
        assertFalse(encoded.contains("\"max_tokens\""))
    }

    @Test fun parsesStreamingDeltaShape() {
        val response = json.decodeFromString<OpenAiChatResponse>("""{"choices":[{"delta":{"content":"hi"}}]}""")
        assertEquals("hi", response.choices.single().delta?.content)
    }
}
