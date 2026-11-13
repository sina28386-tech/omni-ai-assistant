package com.omni.aiassistant.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.omni.aiassistant.R
import com.omni.aiassistant.domain.model.ThemeMode

/**
 * OMNI V2 — "Warm graphite & ember".
 *
 * The palette is deliberately warm and nearly monochrome. A single ember accent
 * is used for one primary action per screen, active navigation and live agent
 * activity; everything else is tonal. That restraint is what makes the product
 * read as an instrument rather than a dashboard of coloured cards.
 */
private val OmniDarkColors = darkColorScheme(
    primary = Color(0xFFFF6B45),
    onPrimary = Color(0xFF2A0C04),
    primaryContainer = Color(0xFF3D160B),
    onPrimaryContainer = Color(0xFFFFD8CC),
    inversePrimary = Color(0xFFB02F0F),
    secondary = Color(0xFFC8C0B6),
    onSecondary = Color(0xFF1C1916),
    secondaryContainer = Color(0xFF2A2622),
    onSecondaryContainer = Color(0xFFEDE5DB),
    tertiary = Color(0xFFE7B45A),
    onTertiary = Color(0xFF2E1E05),
    tertiaryContainer = Color(0xFF3D2C0C),
    onTertiaryContainer = Color(0xFFFBE3B6),
    background = Color(0xFF0A0908),
    onBackground = Color(0xFFF3EEE7),
    surface = Color(0xFF0E0D0C),
    onSurface = Color(0xFFF3EEE7),
    surfaceVariant = Color(0xFF1A1714),
    onSurfaceVariant = Color(0xFFADA69D),
    surfaceTint = Color(0xFFFF6B45),
    inverseSurface = Color(0xFFF3EEE7),
    inverseOnSurface = Color(0xFF171412),
    error = Color(0xFFFF7A7A),
    onError = Color(0xFF2E0606),
    errorContainer = Color(0xFF3F1111),
    onErrorContainer = Color(0xFFFFD9D9),
    // Interactive boundaries stay distinguishable on every surface container.
    outline = Color(0xFF7D746A),
    outlineVariant = Color(0xFF26221E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF201C19),
    surfaceDim = Color(0xFF0A0908),
    surfaceContainerLowest = Color(0xFF070606),
    surfaceContainerLow = Color(0xFF131110),
    surfaceContainer = Color(0xFF171513),
    surfaceContainerHigh = Color(0xFF1E1B18),
    surfaceContainerHighest = Color(0xFF26221E),
)

/**
 * Light is warm paper: #F7F6F2 background with a neutral surface and
 * a deep ember primary that keeps 4.5:1 contrast for body-sized button labels.
 */
private val OmniLightColors = lightColorScheme(
    primary = Color(0xFFA84E32),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF4E4DD),
    onPrimaryContainer = Color(0xFF5A1B09),
    inversePrimary = Color(0xFFFF8A6B),
    secondary = Color(0xFF5E564D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE6DC),
    onSecondaryContainer = Color(0xFF211C17),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFEBC4),
    onTertiaryContainer = Color(0xFF2C1D00),
    background = Color(0xFFF7F6F2),
    onBackground = Color(0xFF242321),
    surface = Color(0xFFFFFEFC),
    onSurface = Color(0xFF242321),
    surfaceVariant = Color(0xFFECEAE5),
    onSurfaceVariant = Color(0xFF696660),
    surfaceTint = Color(0xFFA84E32),
    inverseSurface = Color(0xFF322C27),
    inverseOnSurface = Color(0xFFF7F3EC),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF81796F),
    outlineVariant = Color(0xFFE2DFD9),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFEFC),
    surfaceDim = Color(0xFFE5E2DB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFAF7),
    surfaceContainer = Color(0xFFF0EEE9),
    surfaceContainerHigh = Color(0xFFEAE7E1),
    surfaceContainerHighest = Color(0xFFE2DFD8),
)

/**
 * Vazirmatn covers Persian and Latin with matched metrics, so a single family
 * keeps mixed-script lines ("OpenAI · مدل") visually consistent. Bundling it
 * also removes the dependency on the device's Persian font, which varies
 * wildly between OEMs.
 */
val OmniFontFamily: FontFamily = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_black, FontWeight.Black),
)

private fun omniStyle(
    weight: FontWeight,
    size: TextUnit,
    lineHeight: TextUnit,
): TextStyle = TextStyle(
    fontFamily = OmniFontFamily,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    // Persian must never receive negative or expanded Latin tracking.
    letterSpacing = 0.sp,
)

/**
 * Generous line heights are deliberate: they keep Persian diacritics and
 * wrapped body copy readable on small screens and at font scale 2.0.
 */
private val OmniTypography = Typography(
    displayLarge = omniStyle(FontWeight.Black, 42.sp, 52.sp),
    displayMedium = omniStyle(FontWeight.Black, 36.sp, 46.sp),
    displaySmall = omniStyle(FontWeight.Bold, 30.sp, 40.sp),
    headlineLarge = omniStyle(FontWeight.Bold, 28.sp, 38.sp),
    headlineMedium = omniStyle(FontWeight.SemiBold, 24.sp, 34.sp),
    headlineSmall = omniStyle(FontWeight.SemiBold, 20.sp, 30.sp),
    titleLarge = omniStyle(FontWeight.SemiBold, 18.sp, 28.sp),
    titleMedium = omniStyle(FontWeight.Medium, 16.sp, 25.sp),
    titleSmall = omniStyle(FontWeight.Medium, 14.sp, 22.sp),
    bodyLarge = omniStyle(FontWeight.Normal, 16.sp, 27.sp),
    bodyMedium = omniStyle(FontWeight.Normal, 14.sp, 24.sp),
    bodySmall = omniStyle(FontWeight.Normal, 13.sp, 21.sp),
    labelLarge = omniStyle(FontWeight.Medium, 13.sp, 19.sp),
    labelMedium = omniStyle(FontWeight.Medium, 12.sp, 17.sp),
    labelSmall = omniStyle(FontWeight.Medium, 11.sp, 16.sp),
)

private val OmniShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Spacing scale. Screens should compose from these values instead of ad-hoc
 * dp literals so the vertical rhythm stays identical across all five tabs.
 */
object OmniSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val giant = 48.dp
    val screenHorizontal = 20.dp
}

/** Corner scale: cards stay under 22dp so the UI never looks like a toy. */
object OmniRadius {
    val small = 10.dp
    val medium = 14.dp
    val large = 18.dp
    val xl = 22.dp
    val sheet = 28.dp
    val pill = 100.dp
}

@Composable
fun OmniTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    SyncSystemBarAppearance(dark)
    MaterialTheme(
        colorScheme = if (dark) OmniDarkColors else OmniLightColors,
        typography = OmniTypography,
        shapes = OmniShapes,
        content = content,
    )
}

/**
 * Edge-to-edge is enabled in MainActivity. Status/navigation bar icon contrast
 * must follow the app theme (not just the system theme), otherwise forcing the
 * dark theme on a light system leaves dark icons over a near-black background.
 */
@Composable
private fun SyncSystemBarAppearance(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = LocalContext.current.findActivity()?.window ?: return
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
