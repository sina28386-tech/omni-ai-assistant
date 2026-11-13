package com.omni.aiassistant.qa

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omni.aiassistant.core.logging.SafeLogger
import com.omni.aiassistant.data.attachment.AttachmentStore
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.device.FileAgentRunner
import com.omni.aiassistant.device.tools.ToolDispatcher
import com.omni.aiassistant.device.tools.ToolRegistry
import com.omni.aiassistant.domain.model.AppSettings
import com.omni.aiassistant.domain.model.ChatMessage
import com.omni.aiassistant.domain.model.MessageRole
import com.omni.aiassistant.domain.model.MessageStatus
import com.omni.aiassistant.domain.model.ProviderProfile
import com.omni.aiassistant.domain.model.ProviderType
import com.omni.aiassistant.domain.model.TokenParameter
import com.omni.aiassistant.domain.repository.ConversationRepository
import com.omni.aiassistant.domain.repository.ProviderRepository
import com.omni.aiassistant.feature.chat.ChatViewModel
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * End-to-end agent-loop test against the checked-in mock OpenAI server.
 *
 * It uses the production gateway, dispatcher, Room transcript and ViewModel.
 * The only fake is the model endpoint. Requires:
 *
 *     python scripts/mock_openai_server.py --host 127.0.0.1 --port 8787 --key test-key
 *
 * The test skips itself (instead of failing) when the fixture is not running so
 * the normal instrumented suite remains hermetic.
 */
@RunWith(AndroidJUnit4::class)
class QaV2AgentLoopTest {

    private val providerId = "qa-mock-${UUID.randomUUID()}"
    private lateinit var context: Context
    private lateinit var providers: ProviderRepository
    private lateinit var conversations: ConversationRepository
    private lateinit var settings: SettingsRepository
    private lateinit var gateway: com.omni.aiassistant.core.network.AiProviderGateway
    private lateinit var attachments: AttachmentStore
    private lateinit var fileAgent: FileAgentRunner
    private lateinit var dispatcher: ToolDispatcher
    private lateinit var logger: SafeLogger
    private lateinit var agentContext: com.omni.aiassistant.feature.chat.AgentContextProvider
    private var previousSettings: AppSettings? = null
    private var conversationId: String? = null
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() = runBlocking {
        assumeTrue("mock OpenAI fixture is not running", mockServerReachable())
        context = ApplicationProvider.getApplicationContext()
        val koin = GlobalContext.get()
        providers = koin.get()
        conversations = koin.get()
        settings = koin.get()
        gateway = koin.get()
        attachments = koin.get()
        fileAgent = koin.get()
        dispatcher = koin.get()
        logger = koin.get()
        agentContext = koin.get()
        previousSettings = settings.snapshot()
        val now = System.currentTimeMillis()
        providers.upsert(
            ProviderProfile(
                id = providerId,
                name = "QA mock",
                type = ProviderType.OPENAI,
                baseUrl = "http://10.0.2.2:8787/v1",
                apiKey = "test-key",
                modelId = "mock-omni",
                organizationId = null,
                systemPrompt = "",
                temperature = 0.0,
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
                localDevelopmentEndpoint = true,
                streaming = true,
                agentMode = true,
                defaultProviderId = providerId,
                autoApproveMediumRisk = false,
            )
        }
        conversationId = conversations.create(providerId, "").id
    }

    @After
    fun tearDown() {
        runBlocking {
            scope?.cancel()
            conversationId?.let { runCatching { conversations.deleteConversation(it) } }
            runCatching { providers.delete(providerId) }
            previousSettings?.let { runCatching { settings.replace(it) } }
        }
    }

    @Test
    fun streamsToolCallsExecutesDeviceStatusAndPersistsTheResult() = runBlocking {
        val viewModel = ChatViewModel(
            conversationId = conversationId!!,
            conversations = conversations,
            providers = providers,
            settingsRepository = settings,
            gateway = gateway,
            attachmentStore = attachments,
            fileAgentRunner = fileAgent,
            toolDispatcher = dispatcher,
            logger = logger,
            agentContext = agentContext,
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { collectorScope ->
            collectorScope.launch { viewModel.state.collect {} }
        }

        viewModel.setDraft("check battery")
        viewModel.send()

        val finished = withTimeout(60_000) {
            viewModel.state.first { state ->
                val toolResult = state.messages.any { it.role == MessageRole.TOOL }
                val finalAssistant = state.messages.lastOrNull {
                    it.role == MessageRole.ASSISTANT
                }
                toolResult &&
                    finalAssistant != null &&
                    finalAssistant.status == MessageStatus.COMPLETE &&
                    finalAssistant.content.contains("QA tool loop completed") &&
                    !state.generating
            }
        }

        val toolCallTurn = finished.messages.first {
            it.role == MessageRole.ASSISTANT && it.toolCalls.any { call -> call.name == "device_status" }
        }
        assertEquals(MessageStatus.COMPLETE, toolCallTurn.status)

        val toolResult: ChatMessage = finished.messages.first {
            it.role == MessageRole.TOOL && it.toolName == "device_status"
        }
        assertTrue(toolResult.content.contains("\"ok\":true"))
        assertTrue(toolResult.content.contains("battery", ignoreCase = true))

        val final = finished.messages.last { it.role == MessageRole.ASSISTANT }
        assertTrue(
            "final assistant text was: ${final.content}",
            final.content.contains("QA tool loop completed"),
        )
        assertTrue(finished.messages.none { it.role == MessageRole.TOOL && it.toolCallId.isNullOrBlank() })
        assertTrue(!finished.generating)
    }

    private fun mockServerReachable(): Boolean = runCatching {
        val connection = URL("http://10.0.2.2:8787/health").openConnection() as HttpURLConnection
        connection.connectTimeout = 1_000
        connection.readTimeout = 1_000
        connection.requestMethod = "GET"
        val code = connection.responseCode
        connection.disconnect()
        code == 200
    }.getOrDefault(false)
}

/** Guarantees the production registry contains every capability the UI promises. */
@RunWith(AndroidJUnit4::class)
class QaV2ToolCatalogueTest {

    @Test
    fun registryContainsEveryAdvertisedCapability() {
        val registry = GlobalContext.get().get<ToolRegistry>()
        val names = registry.specs().map { it.name }.toSet()
        val required = setOf(
            "contacts_search", "contact_create", "call_place", "call_log",
            "sms_send", "sms_read", "sms_search",
            "files_search", "files_recent", "file_read_text", "file_info",
            "file_open", "file_share", "file_delete", "file_move", "file_copy",
            "file_rename", "folder_create", "storage_info",
            "apps_list", "app_open", "app_info", "app_uninstall", "app_settings",
            "device_status", "volume_set", "ringer_set", "media_control",
            "tts_speak", "calendar_events", "calendar_create", "alarm_set",
            "timer_set", "dnd_set", "location_get", "clipboard_get",
            "clipboard_set", "wifi_panel", "bluetooth_panel", "brightness_set",
            "rotation_set", "flashlight", "share_text", "open_url", "maps_search",
            "email_compose", "web_search",
            "screen_read", "ui_tap", "ui_type", "ui_scroll", "ui_global",
            "screenshot", "notifications_read", "notifications_dismiss",
            "notifications_reply",
            "pc_status", "pc_execute",
            "web_fetch", "web_search_query",
            "memory_remember", "memory_recall", "memory_forget",
            "media_search", "media_play", "media_stop",
        )
        val missing = required - names
        assertTrue("Missing tools from registry: $missing", missing.isEmpty())
        assertEquals("Duplicate or unexpected registry size", names.size, registry.size)
    }
}
