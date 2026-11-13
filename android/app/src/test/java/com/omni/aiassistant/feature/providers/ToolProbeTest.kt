package com.omni.aiassistant.feature.providers

import com.omni.aiassistant.core.agent.ToolCall
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.core.network.AiProviderGateway
import com.omni.aiassistant.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolProbeTest {
    private val profile = ProviderProfile("id", "test", ProviderType.CUSTOM, "https://example.test", "", "test", null, "", 0.1, 100, 10, false, false, true, emptyMap(), "/chat/completions", TokenParameter.AUTO, 0, 0, true)
    private class FakeGateway(var reply: AiCompletion? = null, var failure: Exception? = null) : AiProviderGateway {
        var calls = 0
        override suspend fun complete(profile: ProviderProfile, systemPrompt: String, messages: List<ChatMessage>, localDevelopmentEnabled: Boolean, tools: List<ToolSpec>): AiCompletion {
            calls++
            check(tools.single().name == "omni_capability_probe")
            failure?.let { throw it }
            return reply!!
        }
        override fun stream(profile: ProviderProfile, systemPrompt: String, messages: List<ChatMessage>, localDevelopmentEnabled: Boolean, tools: List<ToolSpec>): Flow<AiStreamEvent> = emptyFlow()
        override suspend fun testConnection(profile: ProviderProfile, localDevelopmentEnabled: Boolean): AiCompletion = error("not used")
    }
    @Test fun reportsOnlyAnActualToolCallAsConfirmed() = runBlocking {
        val gateway = FakeGateway(AiCompletion("", null, listOf(ToolCall("id", "omni_capability_probe"))))
        assertEquals(ToolTestResult.CONFIRMED, probeToolCalling(gateway, profile, false))
        gateway.reply = AiCompletion("I cannot do that", null)
        assertEquals(ToolTestResult.UNCONFIRMED, probeToolCalling(gateway, profile, false))
        gateway.failure = IllegalStateException("provider error")
        assertEquals(ToolTestResult.UNCONFIRMED, probeToolCalling(gateway, profile, false))
        assertEquals(ToolTestResult.NOT_RUN, probeToolCalling(gateway, profile.copy(supportsTools = false), false))
        assertEquals(3, gateway.calls)
    }
    @Test(expected = CancellationException::class)
    fun cancellationIsNotMisreportedAsAProviderResult() {
        runBlocking { probeToolCalling(FakeGateway(failure = CancellationException("cancelled")), profile, false) }
    }
}
