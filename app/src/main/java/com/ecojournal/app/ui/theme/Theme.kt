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

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F2A44),
    onPrimary = Color.White,
    background = Color.White,
    surface = Color(0xFFF7F5F0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB8D6),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
)

private val SepiaColors = lightColorScheme(
    primary = Color(0xFF5C4321),
    background = Color(0xFFFBF3DF),
    surface = Color(0xFFF3E7C8),
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
