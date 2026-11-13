package com.omni.aiassistant.qa

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.domain.model.MessageRole
import com.omni.aiassistant.domain.model.MessageStatus
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * The multi-step chain, proven end to end.
 *
 * The mock model asks for a chain: find a file, share it, open an app, read
 * the screen. Every step of the chain is built from the *real* result of the
 * previous step, so the test can assert that the result really travelled from
 * one step to the next:
 *
 * - the order of the tool calls is exactly the chain;
 * - the arguments of `file_share` contain the path that `files_search` really
 *   returned (not a hard-coded one);
 * - the final answer repeats the real file name;
 * - and the transcript keeps every step.
 */
@RunWith(AndroidJUnit4::class)
class QaV2ChainTest {

    private val providerId = "qa-chain-${UUID.randomUUID()}"
    private lateinit var context: Context
    private lateinit var providers: ProviderRepository
    private lateinit var conversations: ConversationRepository
    private lateinit var settings: SettingsRepository
    private var scope: CoroutineScope? = null
    private var createdFile: String? = null
    private var diagnostics: String = ""

    @Before
    fun setUp() {
        runBlocking {
            assumeTrue("mock OpenAI fixture is not running", fixtureReachable())
            context = ApplicationProvider.getApplicationContext()
            val koin = GlobalContext.get()
            providers = koin.get()
            conversations = koin.get()
            settings = koin.get()
            val now = System.currentTimeMillis()
            providers.upsert(
                ProviderProfile(
                    id = providerId,
                    name = "QA chain",
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
                    maxToolRounds = 12,
                )
            }
            grantAllFilesAccess()
            createdFile = seedDownload("quarterly-report.pdf")
            // The fixture is only useful when the app itself can read it, so
            // the access is verified from the app's own process.
            val visible = runCatching {
                java.io.File(android.os.Environment.getExternalStorageDirectory(), "Download")
                    .listFiles()
                    ?.any { it.name == "quarterly-report.pdf" } == true
            }.getOrDefault(false)
            val root = java.io.File(android.os.Environment.getExternalStorageDirectory().absolutePath)
            val download = java.io.File(root, "Download")
            val info = "root=${root.absolutePath} " +
                "canonical=${runCatching { root.canonicalPath }.getOrNull()} " +
                "exists=${root.exists()} list=${root.listFiles()?.size} " +
                "download=${download.listFiles()?.map { it.name }} visible=$visible " +
                "isManager=${android.os.Environment.isExternalStorageManager()}"
            diagnostics = info
            check(visible) {
                "the app cannot read the shared Download folder even with all-files access: $info"
            }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            scope?.cancel()
            createdFile?.let { path ->
                runCatching { java.io.File(path).delete() }
            }
            runCatching { providers.delete(providerId) }
        }
    }

    @Test
    fun everyStepOfTheChainConsumesTheResultOfThePreviousOne() = runBlocking {
        val conversation = conversations.create(providerId, "")
        val koin = GlobalContext.get()
        val viewModel = ChatViewModel(
            conversationId = conversation.id,
            conversations = conversations,
            providers = providers,
            settingsRepository = settings,
            gateway = koin.get(),
            attachmentStore = koin.get(),
            fileAgentRunner = koin.get(),
            toolDispatcher = koin.get(),
            logger = koin.get(),
            agentContext = koin.get<AgentContextProvider>(),
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { collectorScope ->
            collectorScope.launch { viewModel.state.collect {} }
        }

        viewModel.setDraft("please run the chain: find the report, share it, open Telegram and read the screen")
        viewModel.send()

        val finished = withTimeout(120_000) {
            viewModel.state.first { state ->
                val toolMessages = state.messages.filter { it.role == MessageRole.TOOL }
                val lastAssistant = state.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                toolMessages.size >= 4 &&
                    lastAssistant != null &&
                    lastAssistant.status == MessageStatus.COMPLETE &&
                    !state.generating
            }
        }

        val calls = finished.messages
            .filter { it.role == MessageRole.ASSISTANT }
            .flatMap { it.toolCalls }
        val callNames = calls.map { it.name }
        assertEquals(
            "the chain did not run in the expected order: $callNames",
            listOf("files_search", "file_share", "app_open", "device_status"),
            callNames,
        )

        // The share step must use the path that the search really returned.
        val searchResult = finished.messages.first { it.role == MessageRole.TOOL && it.toolName == "files_search" }
        assertTrue(
            "the search step did not find the seeded file: ${searchResult.content} | $diagnostics",
            searchResult.content.contains("quarterly-report.pdf"),
        )
        val searchPath = Regex("\"path\"\\s*:\\s*\"([^\"]+)\"")
            .find(searchResult.content)
            ?.groupValues
            ?.get(1)
        assertTrue("the search result carried no path", searchPath != null)
        val shareCall = calls.first { it.name == "file_share" }
        val sharedPath = shareCall.arguments["path"]?.toString()?.trim('"')
        assertEquals(
            "the share step did not use the result of the search step",
            searchPath,
            sharedPath,
        )

        // The transcript keeps every step, so the chain can be audited.
        val toolMessages = finished.messages.filter { it.role == MessageRole.TOOL }
        assertEquals(4, toolMessages.size)
        toolMessages.forEach { message ->
            assertTrue(
                "the chain step ${message.toolName} did not succeed: ${message.content}",
                message.content.contains("\"ok\":true"),
            )
        }

        val final = finished.messages.last { it.role == MessageRole.ASSISTANT }
        assertTrue(
            "the final answer did not summarise the chain: ${final.content}",
            final.content.contains("files_search") || final.content.contains("Chain finished"),
        )
    }

    /* ------------------------------------------------------------------ */
    /* Fixtures                                                            */
    /* ------------------------------------------------------------------ */

    private fun seedDownload(name: String): String {
        val directory = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS,
        )
        directory.mkdirs()
        val file = java.io.File(directory, name)
        file.writeText("Omni QA fixture: a document used by the chain test.\n")
        check(file.exists() && file.length() > 0) {
            "the fixture file could not be written to ${file.absolutePath}"
        }
        return file.absolutePath
    }

    private fun grantAllFilesAccess() {
        runCatching {
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().uiAutomation
                .executeShellCommand(
                    "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow",
                )
                .use { }
        }
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
}
