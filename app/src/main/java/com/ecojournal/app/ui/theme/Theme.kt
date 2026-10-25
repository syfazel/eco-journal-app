package com.ecojournal.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import com.ecojournal.app.R

/**
 * Vazirmatn (OFL, free), Regular + Bold, fetched 2026-10-25 from the
 * official release v33.003 (rastikerdar/vazirmatn). License copy in
 * app/docs/OFL-vazirmatn.txt. No substitute font is used anywhere.
 */
val EcoFontFamily: FontFamily = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

/**
 * Newspaper paper palette. Flat paper-white background, ink-black
 * text, navy accent and light grey rules in light and sepia themes.
 * Dark theme keeps the same hierarchy on dark paper. No shadows
 * are used anywhere in the newspaper UI; hierarchy comes from
 * type size, weight and rules only.
 *
 * M3: schemes are now complete (secondary / tertiary /
 * surfaceVariant / surfaceContainer / error and their on-* colours),
 * so no M3 purple-tinted default leaks through anywhere (P2-02).
 * Typography carries Vazirmatn as its default font family, so a
 * Text without an explicit fontFamily no longer falls back to the
 * system font (P2-03) — the search field's typed text included.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0F2A44),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6F0),
    onPrimaryContainer = Color(0xFF0F2A44),
    secondary = Color(0xFF3D5A73),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2EAF2),
    onSecondaryContainer = Color(0xFF0F2A44),
    tertiary = Color(0xFF6B5D4A),
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF121212),
    surface = Color.White,
    onSurface = Color(0xFF121212),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF5A5A5A),
    surfaceContainer = Color(0xFFF7F7F7),
    surfaceContainerHigh = Color(0xFFEDEDED),
    outline = Color(0xFFD8D8D8),
    outlineVariant = Color(0xFFE8E8E8),
    error = Color(0xFF9E2B25),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB8D6),
    onPrimary = Color(0xFF0F2A44),
    primaryContainer = Color(0xFF24384F),
    onPrimaryContainer = Color(0xFFDCE6F0),
    secondary = Color(0xFF8FA9C4),
    onSecondary = Color(0xFF0F2A44),
    secondaryContainer = Color(0xFF24384F),
    onSecondaryContainer = Color(0xFFDCE6F0),
    tertiary = Color(0xFFC9B79A),
    onTertiary = Color(0xFF2B2118),
    background = Color(0xFF121212),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB0B0B0),
    surfaceContainer = Color(0xFF242424),
    surfaceContainerHigh = Color(0xFF303030),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF2A2A2A),
    error = Color(0xFFE57373),
    onError = Color(0xFF1A1A1A),
)

private val SepiaColors = lightColorScheme(
    primary = Color(0xFF5C4321),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DCBE),
    onPrimaryContainer = Color(0xFF5C4321),
    secondary = Color(0xFF7A6248),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E6CC),
    onSecondaryContainer = Color(0xFF5C4321),
    tertiary = Color(0xFF5C4321),
    onTertiary = Color.White,
    background = Color(0xFFFBF3DF),
    onBackground = Color(0xFF2B2118),
    surface = Color(0xFFFBF3DF),
    onSurface = Color(0xFF2B2118),
    surfaceVariant = Color(0xFFF3E8CC),
    onSurfaceVariant = Color(0xFF6B5D4A),
    surfaceContainer = Color(0xFFF8EFDB),
    surfaceContainerHigh = Color(0xFFEFE2C2),
    outline = Color(0xFFD8C9A8),
    outlineVariant = Color(0xFFE8DCBE),
    error = Color(0xFF9E2B25),
    onError = Color.White,
)

/**
 * theme: "system" | "light" | "dark" | "sepia".
 * "system" is resolved by the caller via isSystemInDarkTheme().
 *
 * The status bar follows the paper colour (P2-02): its background is
 * the theme background and its icon style flips with theme darkness,
 * so sepia and dark paper no longer sit under a mismatched bar.
 */
@Composable
fun EcoTheme(
    theme: String,
    darkSystem: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = when (theme) {
        "light" -> LightColors
        "dark" -> DarkColors
        "sepia" -> SepiaColors
        else -> if (darkSystem) DarkColors else LightColors
    }
    val isDark = theme == "dark" || (theme != "light" && theme != "sepia" && darkSystem)
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = colors.background.toArgb()
        WindowCompat.getInsetsController(window, view)
            .isAppearanceLightStatusBars = !isDark
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(defaultFontFamily = EcoFontFamily),
        content = content,
    )
}
