package com.omni.aiassistant.qa

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omni.aiassistant.MainActivity
import com.omni.aiassistant.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Covers the V2 app shell: the five top destinations, the home readiness
 * states, the grouped conversation list, the locked archive and the devices
 * hub. Every assertion is done on rendered text, never on view ids.
 */
@RunWith(AndroidJUnit4::class)
class QaShellNavigationTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(QaAppStateRule()).around(composeRule)

    private val compose: MainActivityRule get() = composeRule

    @Test
    fun homeShowsHeadlineAndCapabilityShortcuts() {
        compose.waitForText(compose.text(R.string.home_headline))
        compose.onAllNodesWithText(compose.text(R.string.home_section_device))
            .fetchSemanticsNodes()
            .let { assertTrue("device capability section missing", it.isNotEmpty()) }
    }

    @Test
    fun homeExplainsThatNoServiceIsConfiguredYet() {
        compose.waitForText(compose.text(R.string.home_headline))
        compose.onAllNodesWithText(compose.text(R.string.home_banner_no_provider))
            .fetchSemanticsNodes()
            .let { assertTrue("provider setup banner missing", it.isNotEmpty()) }
    }

    @Test
    fun homeReportsAReadyServiceAfterAProfileIsSeeded() {
        QaTestData.seedProvider(name = "Seeded Provider")
        compose.waitForText(compose.text(R.string.home_ai_ready))
    }

    @Test
    fun everyTopDestinationIsReachable() {
        val destinations = listOf(
            R.string.nav_home,
            R.string.nav_conversations,
            R.string.nav_history,
            R.string.nav_devices,
            R.string.nav_settings,
        )
        destinations.forEach { res ->
            compose.clickNav(compose.text(res))
            compose.waitForIdle()
            val label = compose.text(res)
            val rendered = compose.onAllNodesWithText(label).fetchSemanticsNodes()
            assertTrue("destination '$label' did not render", rendered.isNotEmpty())
        }
    }

    @Test
    fun conversationsTabListsSeededChats() {
        QaTestData.seedConversations(listOf("Alpha chat", "Beta chat"))
        compose.clickNav(compose.text(R.string.nav_conversations))
        compose.waitForText("Alpha chat")
        compose.onNodeWithText("Beta chat").assertIsDisplayed()
    }

    @Test
    fun conversationsTabShowsEmptyStateWithoutChats() {
        compose.clickNav(compose.text(R.string.nav_conversations))
        compose.waitForText(compose.text(R.string.no_conversations))
    }

    @Test
    fun historyTabIsLockedToArchivedConversations() {
        QaTestData.seedConversations(listOf("Visible chat"))
        compose.clickNav(compose.text(R.string.nav_history))
        compose.waitForText(compose.text(R.string.conversations_empty_archived_title))
        compose.onAllNodesWithText(compose.text(R.string.conversations_empty_archived_locked_body))
            .fetchSemanticsNodes()
            .let { assertTrue("archived screen must explain that it shows archives only", it.isNotEmpty()) }
    }

    @Test
    fun devicesHubShowsCapabilitySections() {
        compose.clickNav(compose.text(R.string.nav_devices))
        compose.waitForText(compose.text(R.string.devices_page_title))
        compose.onAllNodesWithText(compose.text(R.string.devices_group_communication))
            .fetchSemanticsNodes()
            .let { assertTrue("communication capability group missing", it.isNotEmpty()) }
    }

    @Test
    fun devicesHubOpensTheProvidersScreen() {
        compose.clickNav(compose.text(R.string.nav_devices))
        compose.waitForText(compose.text(R.string.devices_page_title))
        compose.scrollToText(compose.text(R.string.nav_providers))
        compose.onAllNodesWithText(compose.text(R.string.nav_providers))[0].performClick()
        compose.waitForText(compose.text(R.string.providers_empty_title))
    }

    @Test
    fun providersScreenListsSeededProfile() {
        QaTestData.seedProvider(name = "Seeded Provider")
        compose.clickNav(compose.text(R.string.nav_devices))
        compose.waitForText(compose.text(R.string.devices_page_title))
        compose.scrollToText(compose.text(R.string.nav_providers))
        compose.onAllNodesWithText(compose.text(R.string.nav_providers))[0].performClick()
        compose.waitForText("Seeded Provider")
    }

    @Test
    fun newChatShortcutOpensTheChatScreen() {
        compose.openNewChat()
    }

    @Test
    fun settingsScreenShowsGroupedSections() {
        compose.clickNav(compose.text(R.string.nav_settings))
        compose.waitForText(compose.text(R.string.settings_section_assistant))
        compose.waitForText(compose.text(R.string.settings_section_about))
    }
}
