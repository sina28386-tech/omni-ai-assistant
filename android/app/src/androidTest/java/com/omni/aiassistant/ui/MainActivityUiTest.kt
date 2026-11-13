package com.omni.aiassistant.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.omni.aiassistant.MainActivity
import com.omni.aiassistant.R
import com.omni.aiassistant.qa.openNewChat
import org.junit.Rule
import org.junit.Test

/**
 * Light smoke coverage for the V2 shell. The deeper behavioural matrix lives
 * in the `qa` package.
 */
class MainActivityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeDashboardRenders() {
        compose.onNodeWithText(compose.activity.getString(R.string.home_headline)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.home_section_device)).assertIsDisplayed()
    }

    @Test fun devicesHubRenders() {
        val devices = compose.activity.getString(R.string.nav_devices)
        compose.onNodeWithText(devices).performClick()
        compose.onAllNodesWithText(compose.activity.getString(R.string.devices_page_title))[0]
            .assertIsDisplayed()
    }

    @Test fun settingsTabRenders() {
        val settings = compose.activity.getString(R.string.nav_settings)
        compose.onNodeWithText(settings).performClick()
        compose.onAllNodesWithText(settings).fetchSemanticsNodes()
        compose.onNodeWithText(compose.activity.getString(R.string.settings_section_assistant))
            .assertIsDisplayed()
    }

    @Test fun historyTabRendersArchivedTitle() {
        val history = compose.activity.getString(R.string.nav_history)
        compose.onNodeWithText(history).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.conversations_empty_archived_title))
            .assertIsDisplayed()
    }

    @Test fun newChatFromHomeShortcutOpensChat() {
        compose.openNewChat()
    }
}
