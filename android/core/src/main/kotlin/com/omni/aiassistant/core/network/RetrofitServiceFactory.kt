package com.omni.aiassistant.core.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class RetrofitServiceFactory(
    private val baseClient: OkHttpClient,
    private val json: Json,
) {
    private val cache = ConcurrentHashMap<Int, OpenAiApi>()

    fun api(timeoutSeconds: Int): OpenAiApi = cache.getOrPut(timeoutSeconds) {
        val client = baseClient.newBuilder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout((timeoutSeconds + 10).toLong(), TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://localhost/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenAiApi::class.java)
    }
}
