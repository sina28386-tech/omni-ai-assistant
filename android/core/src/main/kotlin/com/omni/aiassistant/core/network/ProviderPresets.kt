package com.omni.aiassistant.core.network

import com.omni.aiassistant.domain.model.ProviderType

data class ProviderPreset(
    val type: ProviderType,
    val displayName: String,
    val baseUrl: String,
    val endpointPath: String = "/chat/completions",
    val apiKeyRequired: Boolean = true,
)

object ProviderPresets {
    val all = listOf(
        ProviderPreset(ProviderType.NVIDIA, "NVIDIA NIM", "https://integrate.api.nvidia.com/v1"),
        ProviderPreset(ProviderType.OPENAI, "OpenAI", "https://api.openai.com/v1"),
        ProviderPreset(ProviderType.GROQ, "Groq", "https://api.groq.com/openai/v1"),
        ProviderPreset(ProviderType.OPENROUTER, "OpenRouter", "https://openrouter.ai/api/v1"),
        ProviderPreset(ProviderType.GEMINI, "Google Gemini (OpenAI compatible)", "https://generativelanguage.googleapis.com/v1beta/openai"),
        ProviderPreset(ProviderType.OLLAMA, "Ollama", "http://10.0.2.2:11434/v1", apiKeyRequired = false),
        ProviderPreset(ProviderType.VLLM, "vLLM", "https://localhost/v1", apiKeyRequired = false),
        ProviderPreset(ProviderType.CUSTOM, "Custom OpenAI-compatible", "https://", apiKeyRequired = false),
    )

    fun forType(type: ProviderType): ProviderPreset = all.first { it.type == type }
}
