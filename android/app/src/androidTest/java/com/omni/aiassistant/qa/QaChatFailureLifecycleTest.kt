package com.omni.aiassistant.qa

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.core.network.AiProviderGateway
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.domain.model.*
import com.omni.aiassistant.domain.repository.ConversationRepository
import com.omni.aiassistant.domain.repository.ProviderRepository
import com.omni.aiassistant.feature.chat.ChatViewModel
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Real transcript/ViewModel lifecycle with a controlled transport; no API server or key. */
@RunWith(AndroidJUnit4::class)
class QaChatFailureLifecycleTest {
    private val koin get() = GlobalContext.get()
    private val providerId = "qa-failure-${UUID.randomUUID()}"
    private lateinit var settings: SettingsRepository
    private lateinit var conversations: ConversationRepository
    private lateinit var providers: ProviderRepository
    private lateinit var previous: AppSettings
    private var conversationId: String? = null
    private var scope: CoroutineScope? = null
    private var viewModel: ChatViewModel? = null

    @Before
    fun prepare() = runBlocking {
        settings = koin.get()
        conversations = koin.get()
        providers = koin.get()
        previous = settings.snapshot()
        val now = System.currentTimeMillis()
        providers.upsert(ProviderProfile(
            id = providerId, name = "QA failure", type = ProviderType.OPENAI,
            baseUrl = "https://example.invalid/v1", apiKey = "fixture-only",
            modelId = "fixture", organizationId = null, systemPrompt = "",
            temperature = 0.0, maxOutputTokens = 128, timeoutSeconds = 10,
            supportsVision = false, supportsStreaming = false, enabled = true,
            customHeaders = emptyMap(), endpointPath = "/chat/completions",
            tokenParameter = TokenParameter.AUTO, createdAt = now, updatedAt = now,
            supportsTools = false,
        ))
        settings.update { it.copy(streaming = false, agentMode = false) }
        conversationId = conversations.create(providerId, "").id
    }

    @After
    fun cleanUp() = runBlocking {
        withContext(Dispatchers.Main) { viewModel?.stopGeneration() }
        scope?.cancel()
        conversationId?.let { conversations.deleteConversation(it) }
        providers.delete(providerId)
        settings.replace(previous)
    }

    @Test
    fun nonStreamingTransportFailurePersistsARetryableFailedMessage() = runBlocking {
        val model = createModel { throw IOException("fixture transport failure") }
        withContext(Dispatchers.Main) {
            model.setDraft("hello")
            model.send()
        }
        val failed = withTimeout(10_000) {
            model.state.first { !it.generating && it.error != null }
        }
        assertEquals(MessageStatus.FAILED, failed.messages.last().status)
        assertEquals("fixture transport failure", failed.messages.last().errorDetails)
    }

    @Test
    fun aCanceledTransportIsNotRecordedAsAProviderFailure() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val model = createModel {
            entered.complete(Unit)
            awaitCancellation()
        }
        withContext(Dispatchers.Main) {
            model.setDraft("hello")
            model.send()
        }
        withTimeout(10_000) { entered.await() }
        withContext(Dispatchers.Main) { model.stopGeneration() }
        val canceled = withTimeout(10_000) {
            model.state.first { it.messages.lastOrNull()?.status == MessageStatus.CANCELED }
        }
        assertFalse(canceled.messages.any { it.status == MessageStatus.FAILED })
    }

    private suspend fun createModel(complete: suspend () -> AiCompletion): ChatViewModel {
        val gateway = object : AiProviderGateway {
            override suspend fun complete(profile: ProviderProfile, systemPrompt: String,
                messages: List<ChatMessage>, localDevelopmentEnabled: Boolean,
                tools: List<ToolSpec>): AiCompletion = complete()
            override fun stream(profile: ProviderProfile, systemPrompt: String,
                messages: List<ChatMessage>, localDevelopmentEnabled: Boolean,
                tools: List<ToolSpec>): Flow<AiStreamEvent> = error("Streaming is disabled")
            override suspend fun testConnection(profile: ProviderProfile,
                localDevelopmentEnabled: Boolean): AiCompletion = error("Not used")
        }
        val model = withContext(Dispatchers.Main) {
            ChatViewModel(conversationId!!, conversations, providers, settings, gateway,
                koin.get(), koin.get(), koin.get(), koin.get(), koin.get())
        }
        viewModel = model
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also {
            it.launch { model.state.collect() }
        }
        withTimeout(10_000) { model.state.first { it.conversation != null } }
        return model
    }
}
