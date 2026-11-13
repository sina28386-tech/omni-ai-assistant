package com.omni.aiassistant.qa

import android.graphics.Color as AndroidColor
import android.view.View
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omni.aiassistant.MainActivity
import com.omni.aiassistant.R
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.domain.model.AppLanguage
import com.omni.aiassistant.domain.model.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Verifies the two presentation axes that cannot be judged from a static
 * screenshot set alone: the Persian right-to-left locale and the dark/light
 * palettes. The theme check compares real rendered pixels instead of trusting
 * the stored preference.
 */
@RunWith(AndroidJUnit4::class)
class QaLocaleThemeTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(QaAppStateRule()).around(composeRule)

    private val compose: MainActivityRule get() = composeRule

    private fun updateSettings(transform: (com.omni.aiassistant.domain.model.AppSettings) -> com.omni.aiassistant.domain.model.AppSettings) {
        runBlocking {
            GlobalContext.get().get<SettingsRepository>().update(transform)
        }
    }

    @Test
    fun persianLocaleRendersPersianCopyAndMirrorsTheBottomBar() {
        val persianHome = localizedText(R.string.nav_home, "fa")
        val persianSettings = localizedText(R.string.nav_settings, "fa")

        updateSettings { it.copy(language = AppLanguage.PERSIAN) }

        compose.waitUntil(20_000) {
            runCatching { compose.text(R.string.nav_home) }.getOrNull() == persianHome
        }

        assertTrue(
            "Persian navigation label not applied",
            compose.onAllNodesWithText(persianHome).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "Layout direction is not RTL",
            compose.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL,
        )

        val homeBounds = compose.onAllNodesWithText(persianHome)[0].fetchSemanticsNode().boundsInRoot
        val settingsBounds = compose.onAllNodesWithText(persianSettings)[0].fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Bottom bar is not mirrored in RTL (home=${homeBounds.left}, settings=${settingsBounds.left})",
            homeBounds.left > settingsBounds.left,
        )
    }

    @Test
    fun englishLocaleKeepsLeftToRightOrder() {
        val englishHome = compose.text(R.string.nav_home)
        val englishSettings = compose.text(R.string.nav_settings)

        assertTrue(
            "Layout direction is not LTR",
            compose.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR,
        )
        val homeBounds = compose.onAllNodesWithText(englishHome)[0].fetchSemanticsNode().boundsInRoot
        val settingsBounds = compose.onAllNodesWithText(englishSettings)[0].fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Bottom bar is not left aligned in LTR (home=${homeBounds.left}, settings=${settingsBounds.left})",
            homeBounds.left < settingsBounds.left,
        )
    }

    @Test
    fun darkAndLightThemesRenderDifferentPalettes() {
        updateSettings { it.copy(theme = ThemeMode.LIGHT) }
        compose.waitUntil(15_000) { runCatching { averageLuminance() }.getOrNull() != null }
        compose.waitForIdle()
        val lightLuminance = averageLuminance()

        updateSettings { it.copy(theme = ThemeMode.DARK) }
        compose.waitUntil(15_000) { runCatching { averageLuminance() }.getOrNull() != null }
        compose.waitForIdle()
        val darkLuminance = averageLuminance()

        assertTrue(
            "Dark theme did not render darker than light (dark=$darkLuminance, light=$lightLuminance)",
            darkLuminance < lightLuminance - 20.0,
        )
    }

    /** Average perceived brightness of the rendered root, sampled on a grid. */
    private fun averageLuminance(): Double {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        var total = 0.0
        var samples = 0
        val stepX = (bitmap.width / 32).coerceAtLeast(1)
        val stepY = (bitmap.height / 32).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                total += 0.299 * AndroidColor.red(pixel) +
                    0.587 * AndroidColor.green(pixel) +
                    0.114 * AndroidColor.blue(pixel)
                samples++
                x += stepX
            }
            y += stepY
        }
        return if (samples == 0) 0.0 else total / samples
    }
}
