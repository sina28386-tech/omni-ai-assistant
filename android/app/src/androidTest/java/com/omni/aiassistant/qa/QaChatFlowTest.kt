package com.omni.aiassistant.qa

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omni.aiassistant.MainActivity
import com.omni.aiassistant.R
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Covers the V2 chat surface: the provider-gate state, Persian input in the
 * composer, and the stop-generation path against a server that never finishes
 * its stream.
 */
@RunWith(AndroidJUnit4::class)
class QaChatFlowTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(QaAppStateRule()).around(composeRule)

    private val compose: MainActivityRule get() = composeRule

    private var hangingServer: HangingSseServer? = null

    @After
    fun tearDown() {
        hangingServer?.close()
        hangingServer = null
    }

    private fun openNewChat() {
        compose.openNewChat()
    }

    @Test
    fun chatWithoutProviderExplainsThatAServiceIsRequired() {
        openNewChat()
        compose.waitForText(compose.text(R.string.chat_no_provider))
    }

    @Test
    fun composerAcceptsPersianText() {
        QaTestData.seedProvider(name = "Seeded Provider")
        openNewChat()
        val persian = "سلام دنیا"
        compose.onNode(hasSetTextAction()).performTextInput(persian)
        compose.onAllNodesWithText(persian)
            .fetchSemanticsNodes()
            .let { assertTrue("Persian draft was not rendered", it.isNotEmpty()) }
    }

    @Test
    fun generatedReplyCanBeStoppedWhileTheStreamIsOpen() {
        val server = HangingSseServer()
        hangingServer = server
        QaTestData.seedProvider(
            name = "Streaming Provider",
            baseUrl = "http://127.0.0.1:${server.port}/v1",
            modelId = "qa-streaming",
            localDevelopment = true,
        )
        openNewChat()

        compose.onNode(hasSetTextAction()).performTextInput("stream please")
        compose.onAllNodesWithContentDescription(compose.text(R.string.chat_send))[0].performClick()

        // The server never terminates the response, so the stop control must
        // stay available until the user cancels the generation.
        compose.waitForContentDescription(compose.text(R.string.chat_stop), timeoutMillis = 20_000)
        compose.onAllNodesWithContentDescription(compose.text(R.string.chat_stop))[0].performClick()
        compose.waitForContentDescriptionToDisappear(
            compose.text(R.string.chat_stop),
            timeoutMillis = 20_000,
        )
    }
}
