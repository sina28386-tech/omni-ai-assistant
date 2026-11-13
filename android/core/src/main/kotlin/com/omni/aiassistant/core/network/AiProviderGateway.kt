package com.omni.aiassistant.core.network

import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.domain.model.AiCompletion
import com.omni.aiassistant.domain.model.AiStreamEvent
import com.omni.aiassistant.domain.model.ChatMessage
import com.omni.aiassistant.domain.model.ProviderProfile
import kotlinx.coroutines.flow.Flow

interface AiProviderGateway {
    suspend fun complete(
        profile: ProviderProfile,
        systemPrompt: String,
        messages: List<ChatMessage>,
        localDevelopmentEnabled: Boolean,
        tools: List<ToolSpec> = emptyList(),
    ): AiCompletion

    fun stream(
        profile: ProviderProfile,
        systemPrompt: String,
        messages: List<ChatMessage>,
        localDevelopmentEnabled: Boolean,
        tools: List<ToolSpec> = emptyList(),
    ): Flow<AiStreamEvent>

    suspend fun testConnection(profile: ProviderProfile, localDevelopmentEnabled: Boolean): AiCompletion
}
