package com.omni.aiassistant.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class OpenAiApiMockWebServerTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OpenAiApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenAiApi::class.java)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun sendsBearerHeaderAndParsesAssistantResponse() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"choices":[{"message":{"role":"assistant","content":"OK"}}],"usage":{"prompt_tokens":2,"completion_tokens":1}}"""
        ))
        val response = api.chat(
            server.url("/v1/chat/completions").toString(),
            mapOf("Authorization" to "Bearer test-key"),
            OpenAiChatRequest("model", listOf(OpenAiMessage("user", JsonPrimitive("hello"))), maxTokens = 16),
        )
        assertTrue(response.isSuccessful)
        assertEquals("OK", response.body()?.choices?.single()?.message?.content)
        val request = server.takeRequest()
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"model\":\"model\""))
    }
}
