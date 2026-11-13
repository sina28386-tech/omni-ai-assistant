package com.omni.aiassistant.qa

import com.omni.aiassistant.core.agent.ToolCall
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.domain.model.MessageRole
import com.omni.aiassistant.domain.model.ProviderProfile
import com.omni.aiassistant.domain.model.ProviderType
import com.omni.aiassistant.domain.model.TokenParameter
import com.omni.aiassistant.domain.repository.ConversationRepository
import com.omni.aiassistant.domain.repository.ProviderRepository
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * Seeds the on-device database with a working mock provider and one completed
 * tool-calling conversation.
 *
 * It is a *fixture*, not an assertion: it deliberately keeps the data so the
 * real app can be launched and screenshotted afterwards. Run it with
 *
 *     ./gradlew connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.omni.aiassistant.qa.QaV2ScreenshotSeed
 */
class QaV2ScreenshotSeed {

    @Test
    fun seedsMockProviderAndToolConversation() = runBlocking {
        val koin = GlobalContext.get()
        val providers = koin.get<ProviderRepository>()
        val conversations = koin.get<ConversationRepository>()
        val settings = koin.get<SettingsRepository>()

        val providerId = "qa-mock-provider"
        val now = System.currentTimeMillis()
        providers.upsert(
            ProviderProfile(
                id = providerId,
                name = "Omni Mock",
                type = ProviderType.OPENAI,
                baseUrl = "http://10.0.2.2:8787/v1",
                apiKey = "test-key",
                modelId = "mock-omni",
                organizationId = null,
                systemPrompt = "",
                temperature = 0.2,
                maxOutputTokens = 512,
                timeoutSeconds = 30,
                supportsVision = false,
                supportsStreaming = true,
                enabled = true,
                customHeaders = emptyMap(),
                endpointPath = "/chat/completions",
                tokenParameter = TokenParameter.AUTO,
                createdAt = now,
                updatedAt = now,
                supportsTools = true,
            ),
        )
        settings.update {
            it.copy(
                defaultProviderId = providerId,
                localDevelopmentEndpoint = true,
                streaming = true,
                agentMode = true,
                autoApproveMediumRisk = false,
            )
        }

        // Remove any previous seeded conversation so the fixture is stable.
        conversations.observeConversations(false).first().forEach { conversation ->
            if (conversation.title == "Battery check") {
                conversations.deleteConversation(conversation.id)
            }
        }
        val conversation = conversations.create(providerId, "You are Omni, a phone assistant.")
        conversations.rename(conversation.id, "Battery check")
        conversations.addUserMessage(
            conversation.id,
            "Check the battery and tell me what the screen says.",
            emptyList(),
        )
        val assistant = conversations.addAssistantPlaceholder(conversation.id)
        val call = ToolCall(id = "call_device_status_1", name = "device_status")
        conversations.setAssistantToolCalls(assistant.id, listOf(call))
        conversations.updateAssistant(
            assistant.id,
            "",
            com.omni.aiassistant.domain.model.MessageStatus.COMPLETE,
        )
        conversations.addToolResult(
            conversation.id,
            call,
            """
            {"ok":true,"summary":"Battery 87%, charging off, signal -58 dBm","data":{"battery":{"level":87,"charging":false},"memory":{"usedPercent":41},"network":{"signal":"-58 dBm","carrier":"Emulator"}}}
            """.trimIndent(),
        )
        conversations.addUserMessage(conversation.id, "And what can you do?", emptyList())
        val final = conversations.addAssistantPlaceholder(conversation.id)
        conversations.updateAssistant(
            final.id,
            """
            I can read the screen, send messages, place calls, find files, set alarms and
            control the system — with your confirmation for anything sensitive.

            The battery tool ran on this phone and returned real values.
            """.trimIndent(),
            com.omni.aiassistant.domain.model.MessageStatus.COMPLETE,
        )

        assertTrue(conversations.get(conversation.id) != null)
    }
}
