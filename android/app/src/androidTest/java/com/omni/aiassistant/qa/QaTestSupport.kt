package com.omni.aiassistant.qa

import android.content.res.Configuration
import android.util.Log
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.aiassistant.MainActivity
import com.omni.aiassistant.R
import com.omni.aiassistant.core.security.LocalSecurityIncident
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.domain.model.Conversation
import com.omni.aiassistant.domain.model.ProviderProfile
import com.omni.aiassistant.domain.model.ProviderType
import com.omni.aiassistant.domain.model.TokenParameter
import com.omni.aiassistant.domain.repository.ConversationRepository
import com.omni.aiassistant.domain.repository.ProviderRepository
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.rules.ExternalResource
import org.koin.core.context.GlobalContext

/** The exact rule type returned by createAndroidComposeRule<MainActivity>(). */
typealias MainActivityRule =
    AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

/**
 * Clears user data and forces English before each test, then does the same
 * after the activity has been closed. This keeps instrumented runs independent
 * without deleting the Android Keystore key (which would be slow and noisy).
 */
class QaAppStateRule : ExternalResource() {
    override fun before() {
        resetAppData()
        forceEnglishLocale()
    }

    override fun after() {
        runCatching {
            resetAppData()
            forceEnglishLocale()
        }.onFailure {
            Log.w("QaAppStateRule", "Post-test reset failed: ${it::class.simpleName}")
        }
    }

    private fun resetAppData() {
        runBlocking {
            withTimeout(20_000) {
                val koin = GlobalContext.get()
                // Conversations first: messages and attachment rows cascade.
                koin.get<ConversationRepository>().clearAll()
                koin.get<ProviderRepository>().deleteAll()
                koin.get<SettingsRepository>().clear()
            }
        }
        LocalSecurityIncident.clear()
    }

    private fun forceEnglishLocale() {
        val english = LocaleListCompat.forLanguageTags("en")
        if (AppCompatDelegate.getApplicationLocales() == english) return
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setApplicationLocales(english)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}

/** Resolves a string through the *current* activity, including after recreation. */
fun MainActivityRule.text(@StringRes id: Int): String {
    var resolved: String? = null
    activityRule.scenario.onActivity { activity ->
        resolved = activity.getString(id)
    }
    return requireNotNull(resolved) { "String resource $id could not be resolved" }
}

/** Resolves a string for an explicit locale without touching global configuration. */
fun localizedText(@StringRes id: Int, languageTag: String): String {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(languageTag))
    return base.createConfigurationContext(configuration).getString(id)
}

/** True when a node with this text exists and is inside the visible window. */
fun MainActivityRule.isTextDisplayed(text: String): Boolean {
    val matches = onAllNodesWithText(text)
    if (matches.fetchSemanticsNodes().isEmpty()) return false
    return runCatching { matches[0].assertIsDisplayed() }.isSuccess
}

/**
 * Waits for a text node and asserts it is on screen. The redesigned screens
 * are long scrolling surfaces, so the node is scrolled into view before the
 * visibility assertion: a node that exists but sits below the fold is not a
 * failure, a node that never appears is.
 */
fun MainActivityRule.waitForText(text: String, timeoutMillis: Long = 10_000) {
    waitUntil("text '$text' did not appear", timeoutMillis) {
        runCatching {
            if (isTextDisplayed(text)) {
                return@runCatching true
            }
            // Either the node is not composed yet (lazy list) or it sits below
            // the fold: both are fixed by scrolling it into view.
            scrollToText(text)
            isTextDisplayed(text)
        }.getOrDefault(false)
    }
    onAllNodesWithText(text)[0].assertIsDisplayed()
}

fun MainActivityRule.waitForTextToDisappear(text: String, timeoutMillis: Long = 10_000) {
    waitUntil("text '$text' did not disappear", timeoutMillis) {
        runCatching {
            onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }.getOrDefault(false)
    }
}

fun MainActivityRule.hasTextNode(text: String): Boolean =
    onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

fun MainActivityRule.hasContentDescriptionNode(description: String): Boolean =
    onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

/** Waits until an icon-only control (content description) disappears. */
fun MainActivityRule.waitForContentDescriptionToDisappear(
    description: String,
    timeoutMillis: Long = 10_000,
) {
    waitUntil("content description '$description' did not disappear", timeoutMillis) {
        runCatching {
            onAllNodesWithContentDescription(description).fetchSemanticsNodes().isEmpty()
        }.getOrDefault(false)
    }
}

fun MainActivityRule.waitForContentDescription(
    description: String,
    timeoutMillis: Long = 10_000,
) {
    waitUntil("content description '$description' did not appear", timeoutMillis) {
        runCatching {
            onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
    }
    onAllNodesWithContentDescription(description)[0].assertIsDisplayed()
}

/**
 * Clicks a bottom-navigation destination. Navigation items expose their label
 * as text at normal font scales and as a content description once the tab bar
 * collapses to icons (large accessibility font scale), so both are accepted.
 */
fun MainActivityRule.clickNav(label: String) {
    val byText = onAllNodes(hasText(label) and isSelectable())
    if (byText.fetchSemanticsNodes().isNotEmpty()) {
        byText[0].performClick()
        return
    }
    val byDescription = onAllNodes(hasContentDescription(label) and isSelectable())
    if (byDescription.fetchSemanticsNodes().isNotEmpty()) {
        byDescription[0].performClick()
        return
    }
    onNode(hasText(label) and isSelectable()).performClick()
}

/**
 * Scrolls the primary scroll container until [text] is visible. Works for
 * verticalScroll columns and for LazyColumn items that are not composed yet.
 */
fun MainActivityRule.scrollToText(text: String) {
    val scrollables = onAllNodes(hasScrollAction())
    val count = scrollables.fetchSemanticsNodes().size
    if (count == 0) {
        onNodeWithText(text).assertIsDisplayed()
        return
    }
    for (index in 0 until count) {
        val scrolled = runCatching {
            scrollables[index].performScrollToNode(hasText(text))
        }.isSuccess
        if (scrolled) return
    }
    onNodeWithText(text).assertIsDisplayed()
}

/**
 * Opens a brand new chat through the conversations screen. The add button sits
 * in the app bar and is therefore always composed, which makes this stable on
 * every screen size (the home shortcut can sit below the fold in a lazy list).
 */
fun MainActivityRule.openNewChat() {
    clickNav(text(R.string.nav_conversations))
    waitForIdle()
    waitForContentDescription(text(R.string.new_chat))
    onAllNodesWithContentDescription(text(R.string.new_chat))[0].performClick()
    waitForText(text(R.string.chat_title))
}

/**
 * True when a bottom-navigation destination exists, either as visible text or
 * (at large font scales, where the tab bar collapses to icons) as an
 * accessibility label.
 */
fun MainActivityRule.hasNavLabel(label: String): Boolean =
    onAllNodes(hasText(label) and isSelectable()).fetchSemanticsNodes().isNotEmpty() ||
        onAllNodes(hasContentDescription(label) and isSelectable())
            .fetchSemanticsNodes().isNotEmpty()

/**
 * Scrolls to the first text variant that exists, returning the match. */
fun MainActivityRule.scrollToAnyText(vararg texts: String): String {
    var lastFailure: Throwable? = null
    for (text in texts) {
        try {
            scrollToText(text)
            if (hasTextNode(text)) return text
        } catch (failure: Throwable) {
            lastFailure = failure
        }
    }
    throw AssertionError("None of ${texts.toList()} could be scrolled into view", lastFailure)
}

/** Minimal in-process SSE endpoint that accepts one request and then stalls. */
class HangingSseServer {
    private val serverSocket = java.net.ServerSocket(
        0,
        1,
        java.net.InetAddress.getByName("127.0.0.1"),
    )
    @Volatile private var clientSocket: java.net.Socket? = null
    private val thread = Thread {
        try {
            serverSocket.soTimeout = 20_000
            val socket = serverSocket.accept()
            clientSocket = socket
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            // Consume request headers but leave the response body open.
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            socket.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Connection: keep-alive\r\n\r\n"
                        ).toByteArray(Charsets.ISO_8859_1),
                )
                flush()
            }
            while (!socket.isClosed) Thread.sleep(100)
        } catch (_: Throwable) {
            // Test teardown closes the sockets; interruption is expected.
        }
    }.apply {
        isDaemon = true
        name = "qa-hanging-sse"
        start()
    }

    val port: Int get() = serverSocket.localPort

    fun close() {
        runCatching { clientSocket?.close() }
        runCatching { serverSocket.close() }
        thread.interrupt()
    }
}

/** Repository-level helpers used only by instrumented QA tests. */
object QaTestData {
    fun seedProvider(
        id: String = "qa-provider",
        name: String = "QA Provider",
        modelId: String = "qa-model",
        type: ProviderType = ProviderType.CUSTOM,
        baseUrl: String = "https://example.invalid/v1",
        apiKey: String = "",
        supportsVision: Boolean = true,
        supportsStreaming: Boolean = true,
        enabled: Boolean = true,
        endpointPath: String = "/chat/completions",
        makeDefault: Boolean = true,
        localDevelopment: Boolean = false,
    ): ProviderProfile {
        val now = System.currentTimeMillis()
        val profile = ProviderProfile(
            id = id,
            name = name,
            type = type,
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelId = modelId,
            organizationId = null,
            systemPrompt = "",
            temperature = 0.7,
            maxOutputTokens = 512,
            timeoutSeconds = 30,
            supportsVision = supportsVision,
            supportsStreaming = supportsStreaming,
            enabled = enabled,
            customHeaders = emptyMap(),
            endpointPath = endpointPath,
            tokenParameter = TokenParameter.AUTO,
            createdAt = now,
            updatedAt = now,
        )
        runBlocking {
            val koin = GlobalContext.get()
            koin.get<ProviderRepository>().upsert(profile)
            koin.get<SettingsRepository>().update { settings ->
                settings.copy(
                    defaultProviderId = if (makeDefault) profile.id else settings.defaultProviderId,
                    localDevelopmentEndpoint = localDevelopment,
                )
            }
        }
        return profile
    }

    fun seedConversation(
        title: String,
        providerId: String? = null,
        archived: Boolean = false,
        messages: List<String> = emptyList(),
    ): Conversation = runBlocking {
        val repository = GlobalContext.get().get<ConversationRepository>()
        val conversation = repository.create(providerId)
        messages.forEach { content ->
            repository.addUserMessage(conversation.id, content, emptyList())
        }
        repository.rename(conversation.id, title)
        if (archived) repository.setArchived(conversation.id, true)
        requireNotNull(repository.get(conversation.id))
    }

    fun seedConversations(
        titles: List<String>,
        providerId: String? = null,
        archived: Boolean = false,
    ): List<Conversation> = runBlocking {
        val repository = GlobalContext.get().get<ConversationRepository>()
        titles.map { title ->
            val conversation = repository.create(providerId)
            repository.rename(conversation.id, title)
            if (archived) repository.setArchived(conversation.id, true)
            conversation.copy(title = title, archived = archived)
        }
    }
}
