package com.ecojournal.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
 * screen. M7: invite state is now the real gate — confirmed text,
 * a revocation note, and a manual re-lock button.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    store: SettingsStore,
    onDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
) {
    val scope = rememberCoroutineScope()
    // M8: declared at the top so the Switch semantics below can
    // use it (the old declaration sat after the Switch block).
    val context = LocalContext.current
    var sliderValue by remember(settings.fontScale) { mutableStateOf(settings.fontScale) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_font_heading),
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
            text = stringResource(R.string.settings_theme_heading),
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
                modifier = Modifier.heightIn(min = 48.dp),
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
            text = stringResource(R.string.settings_notifications_heading),
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
        )
        Switch(
            checked = settings.notificationsEnabled,
            modifier = Modifier.semantics {
                contentDescription = context.getString(R.string.settings_notifications_desc)
            },
            onCheckedChange = { v ->
                scope.launch { store.setNotifications(v) }
            },
        )
        // M5 (P2-16): permission is no longer requested blindly on
        // launch. When notifications are ON but the system
        // permission is missing, the screen shows the rationale and
        // a request button; after a denial it shows in-app guidance
        // to the system app-settings page instead of failing
        // silently. The toggle itself still works without the
        // permission — polling continues, notify() is a no-op.
        // Hoisted (not conditional): the launcher must be
        // registered on every composition, not only when the
        // guidance block is visible.
        var permissionCheck by remember { mutableStateOf(0) }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { permissionCheck++ }
        if (settings.notificationsEnabled) {
            // permissionCheck is read here so returning from the
            // permission dialog or system settings recomposes and
            // re-checks the permission.
            val check = permissionCheck
            val hasPermission = check >= 0 && (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                )
            if (!hasPermission) {
                val launcher = permissionLauncher
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.notif_rationale),
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        text = stringResource(R.string.notif_request_button),
                        fontFamily = EcoFontFamily,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.notif_denied_guidance),
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Button(onClick = {
                    context.startActivity(
                        Intent(
                            SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                    permissionCheck++
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        text = stringResource(R.string.notif_open_settings),
                        fontFamily = EcoFontFamily,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (settings.inviteOk) {
                stringResource(R.string.invite_confirmed)
            } else {
                stringResource(R.string.invite_not_confirmed)
            },
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
        )
        if (settings.inviteOk) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.invite_revocation_note),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Button(onClick = {
                // M7: manual re-lock. Clears flag + stored hash,
                // so the next launch shows the code-entry screen
                // again. Cached articles are hidden, not deleted.
                scope.launch { store.clearInvite() }
            }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    text = stringResource(R.string.invite_lock_button),
                    fontFamily = EcoFontFamily,
                )
            }
        }
        // M8: diagnostics screen entry (counts/status only, no PII).
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onDiagnostics,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_diagnostics_button),
                fontFamily = EcoFontFamily,
            )
        }
    }
}
