package com.ecojournal.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.ecojournal.app.data.Settings
import com.ecojournal.app.data.SettingsStore
import com.ecojournal.app.ui.theme.EcoFontFamily
import kotlinx.coroutines.launch

/** Settings: font scale, theme (incl. sepia), notifications, invite state. */
@Composable
fun SettingsScreen(
    settings: Settings,
    store: SettingsStore,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var fontScale by remember(settings.fontScale) { mutableStateOf(settings.fontScale) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("اندازهٔ فونت مقاله", fontFamily = EcoFontFamily)
        Slider(
            value = fontScale,
            onValueChange = { fontScale = it },
            onValueChangeFinished = {
                scope.launch { store.setFontScale(fontScale) }
            },
            valueRange = 0.85f..1.3f,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("پوسته", fontFamily = EcoFontFamily)
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
        Text("اعلان مقالات تازه", fontFamily = EcoFontFamily)
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
        )
    }
}
