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
import androidx.compose.material3.OutlinedTextField
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
/** Phase 4 helper: "HH:MM" (Tehran wall clock) → minutes after midnight, or null. */
private fun parseQuietTime(raw: String): Int? {
    val latin = buildString {
        for (c in raw.trim()) {
            append(
                when (c) {
                    in '۰'..'۹' -> ('0' + (c - '۰'))
                    in '٠'..'٩' -> ('0' + (c - '٠'))
                    else -> c
                }
            )
        }
    }
    val parts = latin.split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun formatQuietTime(minutes: Int): String =
    "%02d:%02d".format(minutes / 60, minutes % 60)

@Composable
fun SettingsScreen(
    settings: Settings,
    store: SettingsStore,
    onDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
    // Phase 4: threshold PRESENCE only (never the value — the
    // value belongs on the Market banner, not in Settings).
    marketThresholdSet: Boolean = false,
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
        // ---- Phase 4: per-service toggles + quiet hours ----
        // The master switch above remains the kill switch. Each
        // row below is one DataStore key (spec §4.7); turning the
        // master back ON restores per-service behaviour without
        // re-firing suppressed items as a burst (dispatcher rule).
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_notifications_heading_p4),
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
        )
        Text(
            text = stringResource(R.string.settings_notifications_master_desc),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
        val p4Toggles: List<Triple<String, Boolean, (Boolean) -> Unit>> = listOf(
            Triple(stringResource(R.string.settings_notify_urgent), settings.notifyUrgent) { v: Boolean ->
                scope.launch { store.setNotifyUrgent(v) }
            },
            Triple(stringResource(R.string.settings_notify_market_alerts), settings.notifyMarketAlerts) { v: Boolean ->
                scope.launch { store.setNotifyMarketAlerts(v) }
            },
            Triple(stringResource(R.string.settings_notify_bundles), settings.notifyBundles) { v: Boolean ->
                scope.launch { store.setNotifyBundles(v) }
            },
            Triple(stringResource(R.string.settings_notify_sitrep), settings.notifySitrep) { v: Boolean ->
                scope.launch { store.setNotifySitrep(v) }
            },
            Triple(stringResource(R.string.settings_notify_nightly), settings.notifyNightly) { v: Boolean ->
                scope.launch { store.setNotifyNightly(v) }
            },
            Triple(stringResource(R.string.settings_notify_special_issues), settings.notifySpecialIssues) { v: Boolean ->
                scope.launch { store.setNotifySpecialIssues(v) }
            },
            Triple(stringResource(R.string.settings_notify_radar), settings.notifyRadar) { v: Boolean ->
                scope.launch { store.setNotifyRadar(v) }
            },
        )
        for ((label, checked, onChange) in p4Toggles) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
            Switch(checked = checked, onCheckedChange = onChange)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (marketThresholdSet) {
                stringResource(R.string.settings_threshold_status_set)
            } else {
                stringResource(R.string.settings_threshold_status_unset)
            },
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_quiet_heading),
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
        )
        Switch(
            checked = settings.quietHoursEnabled,
            onCheckedChange = { v -> scope.launch { store.setQuietHoursEnabled(v) } },
        )
        var quietStartText by remember(settings.quietStartMin) {
            mutableStateOf(formatQuietTime(settings.quietStartMin))
        }
        var quietEndText by remember(settings.quietEndMin) {
            mutableStateOf(formatQuietTime(settings.quietEndMin))
        }
        var quietError by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = quietStartText,
            onValueChange = { quietStartText = it },
            label = {
                Text(
                    text = stringResource(R.string.settings_quiet_start_label),
                    fontFamily = EcoFontFamily,
                )
            },
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = quietEndText,
            onValueChange = { quietEndText = it },
            label = {
                Text(
                    text = stringResource(R.string.settings_quiet_end_label),
                    fontFamily = EcoFontFamily,
                )
            },
        )
        if (quietError) {
            Text(
                text = stringResource(R.string.settings_quiet_invalid),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Button(onClick = {
            val start = parseQuietTime(quietStartText)
            val end = parseQuietTime(quietEndText)
            if (start == null || end == null) {
                quietError = true
            } else {
                quietError = false
                scope.launch {
                    store.setQuietStartMin(start)
                    store.setQuietEndMin(end)
                }
            }
        }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(text = "ذخیرهٔ ساعات سکوت", fontFamily = EcoFontFamily)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_quiet_mute_urgent),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
        Text(
            text = stringResource(R.string.settings_quiet_mute_urgent_desc),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
        Switch(
            checked = settings.quietMuteUrgent,
            onCheckedChange = { v -> scope.launch { store.setQuietMuteUrgent(v) } },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_system_notifications_desc),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
        Button(onClick = {
            context.startActivity(
                Intent(
                    SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
            )
        }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(
                text = stringResource(R.string.settings_system_notifications),
                fontFamily = EcoFontFamily,
            )
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
