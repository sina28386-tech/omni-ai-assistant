package com.omni.aiassistant.core.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url

interface OpenAiApi {
    @POST
    suspend fun chat(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: OpenAiChatRequest,
    ): Response<OpenAiChatResponse>
}
