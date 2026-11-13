package com.omni.aiassistant.qa

import com.omni.aiassistant.core.agent.VerbAction
import com.omni.aiassistant.core.security.KeystoreAead
import com.omni.aiassistant.data.memory.UserMemoryRepository
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.data.style.UserStyleRepository
import com.omni.aiassistant.data.verbs.UserVerbRepository
import com.omni.aiassistant.domain.model.MessageRole
import com.omni.aiassistant.domain.model.ProviderProfile
import com.omni.aiassistant.domain.model.ProviderType
import com.omni.aiassistant.domain.model.TokenParameter
import com.omni.aiassistant.domain.repository.ConversationRepository
import com.omni.aiassistant.domain.repository.ProviderRepository
import com.omni.aiassistant.feature.chat.AgentContextProvider
import com.omni.aiassistant.feature.chat.ChatViewModel
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * Deep test of the behaviour layer: what the app *actually sends to the
 * model*.
 *
 * The mock provider records the last request body. The test asserts that the
 * system prompt really carries
 * - the routing rules that separate "inside the app" from "on the internet";
 * - the user's own words;
 * - what Arman already knows about the user;
 * - and that the new web and memory tools are advertised in the schema.
 */
class QaV2PromptContextTest {

    private val providerId = "qa-context-${UUID.randomUUID()}"
    private lateinit var providers: ProviderRepository
    private lateinit var conversations: ConversationRepository
    private lateinit var settings: SettingsRepository
    private lateinit var verbs: UserVerbRepository
    private lateinit var memory: UserMemoryRepository
    private lateinit var style: UserStyleRepository
    private lateinit var agentContext: AgentContextProvider
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        runBlocking {
            assumeTrue("mock OpenAI fixture is not running", fixtureReachable())
            val koin = GlobalContext.get()
            providers = koin.get()
            conversations = koin.get()
            settings = koin.get()
            verbs = koin.get()
            memory = koin.get()
            style = koin.get()
            agentContext = koin.get()
            style.clear()
            val now = System.currentTimeMillis()
            providers.upsert(
                ProviderProfile(
                    id = providerId,
                    name = "QA context",
                    type = ProviderType.OPENAI,
                    baseUrl = "http://10.0.2.2:8787/v1",
                    apiKey = "test-key",
                    modelId = "mock-omni",
                    organizationId = null,
                    systemPrompt = "",
                    temperature = 0.0,
                    maxOutputTokens = 256,
                    timeoutSeconds = 30,
                    supportsVision = false,
                    supportsStreaming = false,
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
                    streaming = false,
                    agentMode = true,
                )
            }
            verbs.upsert(WAKE_WORD, VerbAction.SCROLL_DOWN)
            memory.remember("favorite_music", FAVORITE_ARTIST, source = "test")
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            scope?.cancel()
            // The setup can be skipped (no mock fixture), so the properties may
            // still be uninitialised here.
            if (!this@QaV2PromptContextTest::verbs.isInitialized) return@runBlocking
            verbs.clear()
            memory.clear()
            if (this@QaV2PromptContextTest::style.isInitialized) style.clear()
            runCatching { providers.delete(providerId) }
        }
    }

    @Test
    fun thePromptCarriesTheRoutingRulesTheCustomWordAndTheMemory() = runBlocking {
        val conversation = conversations.create(providerId, "")
        val viewModel = newChatViewModel(conversation.id)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { collectorScope ->
            collectorScope.launch { viewModel.state.collect {} }
        }
        viewModel.setDraft("پخش کن $FAVORITE_ARTIST")
        viewModel.send()
        withTimeout(45_000) {
            viewModel.state.first { state ->
                state.messages.any { it.role == MessageRole.ASSISTANT } && !state.generating
            }
        }

        val body = lastRequestBody()
        val messages = body["messages"]?.jsonArray.orEmpty()
        val prompt = messages
            .map { it.jsonObject }
            .firstOrNull { it["role"]?.jsonPrimitive?.content == "system" }
            ?.get("content")
            ?.jsonPrimitive
            ?.content
            .orEmpty()

        assertTrue("no system prompt was sent", prompt.isNotEmpty())
        // The routing rules are what make "in my phone" and "on the internet"
        // different requests.
        assertTrue("routing rules missing:\n$prompt", prompt.contains("INSIDE AN APP"))
        assertTrue("routing rules missing:\n$prompt", prompt.contains("THE OPEN INTERNET"))
        assertTrue("routing rules missing:\n$prompt", prompt.contains("web_fetch"))
        // The user's own word is quoted verbatim.
        assertTrue("custom verb missing:\n$prompt", prompt.contains(WAKE_WORD))
        // What Arman knows about the user.
        assertTrue("memory missing:\n$prompt", prompt.contains(FAVORITE_ARTIST))

        val tools = body["tools"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .mapNotNull { it["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content }
        assertTrue("web_fetch not advertised", tools.contains("web_fetch"))
        assertTrue("web_search_query not advertised", tools.contains("web_search_query"))
        assertTrue("memory_remember not advertised", tools.contains("memory_remember"))

        conversations.deleteConversation(conversation.id)
    }

    /**
     * The user's sentence must change the profile and that same turn's prompt:
     * learning after the answer would make "from now on be brief" feel ignored.
     */
    @Test
    fun theStyleLearnedFromTheMessageIsInThatSameTurn() = runBlocking {
        style.clear()
        val conversation = conversations.create(providerId, "")
        val viewModel = newChatViewModel(conversation.id)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { collectorScope ->
            collectorScope.launch { viewModel.state.collect {} }
        }
        viewModel.setDraft("از این به بعد کوتاه جواب بده")
        viewModel.send()
        withTimeout(45_000) {
            viewModel.state.first { state ->
                state.messages.any { it.role == MessageRole.ASSISTANT } && !state.generating
            }
        }

        val prompt = lastSystemPrompt()
        assertTrue("learned style missing:\n$prompt", prompt.contains("How this user talks"))
        assertTrue("short preference missing:\n$prompt", prompt.contains("Length: short"))
        assertEquals("short", style.current().verbosity)

        // The same prompt must describe the software that is actually here, and
        // say that the tool list is not a menu.
        assertTrue("environment missing:\n$prompt", prompt.contains("This is the phone right now"))
        assertTrue("device missing:\n$prompt", prompt.contains("Device:"))
        assertTrue("app list missing:\n$prompt", prompt.contains("Apps installed and launchable"))
        assertTrue("capability rules missing:\n$prompt", prompt.contains("primitives, not a menu"))

        conversations.deleteConversation(conversation.id)
    }

    /**
     * A second repository instance has no cache, so this reads the encrypted
     * profile back from disk and proves that the reset really removes it.
     */
    @Test
    fun theLearnedStyleSurvivesANewRepositoryAndCanBeReset() = runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        val aead = GlobalContext.get().get<KeystoreAead>()
        val first = UserStyleRepository(context, aead)
        first.clear()
        first.learn("منو صدا کن آرش")
        first.learn("بدون ایموجی جواب بده")

        val loaded = UserStyleRepository(context, aead).current()
        assertEquals("آرش", loaded.name)
        assertEquals("never", loaded.emoji)

        UserStyleRepository(context, aead).clear()
        assertEquals("", UserStyleRepository(context, aead).current().name)
    }

    private fun newChatViewModel(conversationId: String): ChatViewModel {
        val koin = GlobalContext.get()
        return ChatViewModel(
            conversationId = conversationId,
            conversations = conversations,
            providers = providers,
            settingsRepository = settings,
            gateway = koin.get(),
            attachmentStore = koin.get(),
            fileAgentRunner = koin.get(),
            toolDispatcher = koin.get(),
            logger = koin.get(),
            agentContext = agentContext,
        )
    }

    private fun lastSystemPrompt(): String = lastRequestBody()["messages"]?.jsonArray.orEmpty()
        .map { it.jsonObject }
        .firstOrNull { it["role"]?.jsonPrimitive?.content == "system" }
        ?.get("content")
        ?.jsonPrimitive
        ?.content
        .orEmpty()

    private fun lastRequestBody(): JsonObject {
        val connection = URL("http://10.0.2.2:8787/v1/last_request").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
        return parsed["request"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private fun fixtureReachable(): Boolean = runCatching {
        val connection = URL("http://10.0.2.2:8787/health").openConnection() as HttpURLConnection
        connection.connectTimeout = 1_000
        connection.readTimeout = 1_000
        connection.requestMethod = "GET"
        val code = connection.responseCode
        connection.disconnect()
        code == 200
    }.getOrDefault(false)

    private companion object {
        const val WAKE_WORD = "تیک"
        const val FAVORITE_ARTIST = "حسین علیزاده"
    }
}
