package com.ecojournal.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0F2A44),
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF121212),
    surface = Color.White,
    onSurface = Color(0xFF121212),
    onSurfaceVariant = Color(0xFF5A5A5A),
    outline = Color(0xFFD8D8D8),
    outlineVariant = Color(0xFFE8E8E8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB8D6),
    background = Color(0xFF121212),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF2A2A2A),
)

private val SepiaColors = lightColorScheme(
    primary = Color(0xFF5C4321),
    onPrimary = Color.White,
    background = Color(0xFFFBF3DF),
    onBackground = Color(0xFF2B2118),
    surface = Color(0xFFFBF3DF),
    onSurface = Color(0xFF2B2118),
    onSurfaceVariant = Color(0xFF6B5D4A),
    outline = Color(0xFFD8C9A8),
    outlineVariant = Color(0xFFE8DCBE),
)

/**
 * theme: "system" | "light" | "dark" | "sepia".
 * "system" is resolved by the caller via isSystemInDarkTheme().
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
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
