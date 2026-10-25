package com.ecojournal.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.data.Settings
import com.ecojournal.app.data.SettingsStore
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.util.toPersianDigits
import kotlinx.coroutines.launch

/**
 * Settings: font scale, theme (incl. sepia), notifications, invite state.
 *
 * M3 (P1-20): the font slider saves on every change, not only on
 * release — before, leaving Settings mid-drag lost the value — and
 * it shows a numeric value plus a preview paragraph that scales
 * live. The screen scrolls, and its text scales with the setting
 * itself, so the slider's effect is visible without leaving the
 * screen. Invite behaviour is untouched (M7).
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    store: SettingsStore,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
) {
    val scope = rememberCoroutineScope()
    var sliderValue by remember(settings.fontScale) { mutableStateOf(settings.fontScale) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "اندازهٔ فونت مقاله",
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
        )
        Text(
            text = "${stringResource(R.string.font_size_label)}: " +
                "%.2f".format(sliderValue).toPersianDigits() + "×",
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
        Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                // Save on change, not only on release (P1-20).
                scope.launch { store.setFontScale(it) }
            },
            valueRange = 0.85f..1.3f,
        )
        Text(
            text = stringResource(R.string.font_preview),
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * sliderValue,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "پوسته",
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
        )
        for ((key, label) in listOf(
            "system" to "سیستم",
            "light" to "روشن",
            "dark" to "تیره",
            "sepia" to "سپیا",
        )) {
            Button(
                onClick = { scope.launch { store.setTheme(key) } },
            ) {
                Text(
                    text = label + if (settings.theme == key) " ✓" else "",
                    fontFamily = EcoFontFamily,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "اعلان مقالات تازه",
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
        )
        Switch(
            checked = settings.notificationsEnabled,
            onCheckedChange = { v ->
                scope.launch { store.setNotifications(v) }
            },
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (settings.inviteOk) {
                "دسترسی با کد دعوت تأیید شده است."
            } else {
                "کد دعوت هنوز تأیید نشده است (مرحلهٔ کنترل‌صفحه، فاز ۲)."
            },
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
        )
    }
}
