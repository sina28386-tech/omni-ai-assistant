package com.omni.aiassistant.qa

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.aiassistant.MainActivity
import com.omni.aiassistant.R
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Exercises the runtime conditions that break layouts most often: the
 * accessibility font scale of 2.0 and a 320 dp wide screen. Both checks run
 * against the real device configuration and are restored afterwards so the
 * rest of the suite is unaffected.
 */
@RunWith(AndroidJUnit4::class)
class QaDisplayConditionsTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(QaAppStateRule()).around(composeRule)

    private val compose: MainActivityRule get() = composeRule

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { }
    }

    /**
     * The device configuration is global state. It must be restored even when
     * an assertion fails, otherwise every later test in the suite runs on a
     * 320 dp screen with a 2.0 font scale and fails for the wrong reason.
     */
    @After
    fun restoreDeviceConfiguration() {
        shell("settings put system font_scale 1.0")
        shell("wm size reset")
        shell("wm density reset")
        runCatching { compose.activityRule.scenario.recreate() }
    }

    @Test
    fun doubleFontScaleKeepsEveryDestinationUsable() {
        val baseline = titleHeight()
        shell("settings put system font_scale 2.0")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        val scaled = titleHeight()
        assertTrue(
            "Font scale 2.0 was not applied to the UI (baseline=$baseline, scaled=$scaled)",
            scaled > baseline,
        )

        listOf(
            R.string.nav_conversations,
            R.string.nav_devices,
            R.string.nav_settings,
            R.string.nav_home,
        ).forEach { res ->
            compose.clickNav(compose.text(res))
            compose.waitForIdle()
            val label = compose.text(res)
            assertTrue(
                "Destination '$label' is not reachable at font scale 2.0",
                compose.hasNavLabel(label),
            )
        }
        shell("settings put system font_scale 1.0")
        compose.activityRule.scenario.recreate()
    }

    @Test
    fun narrowScreenKeepsEveryDestinationUsable() {
        // 720 px at 360 dpi is exactly 320 dp wide, the narrowest target width.
        shell("wm size 720x1600")
        shell("wm density 360")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        compose.clickNav(compose.text(R.string.nav_settings))
        compose.waitForText(compose.text(R.string.settings_section_assistant))
        compose.onNodeWithText(compose.text(R.string.settings_section_assistant)).assertIsDisplayed()

        compose.clickNav(compose.text(R.string.nav_home))
        compose.waitForText(compose.text(R.string.home_headline))

        shell("wm size reset")
        shell("wm density reset")
        compose.activityRule.scenario.recreate()
    }

    /** Height of the largest text node on screen, used to detect scaling. */
    private fun titleHeight(): Float {
        val nodes = compose.onAllNodesWithText(compose.text(R.string.home_headline)).fetchSemanticsNodes()
        if (nodes.isEmpty()) return 0f
        return nodes.maxOf { it.boundsInRoot.height }
    }
}
