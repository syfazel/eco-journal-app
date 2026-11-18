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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ecojournal.app.R
import com.ecojournal.app.data.AppUpdateRepository
import com.ecojournal.app.data.AppUpdateSession
import com.ecojournal.app.data.BROADCAST_KEY_GRACE_DAYS
import com.ecojournal.app.data.Jalali
import com.ecojournal.app.data.Settings
import com.ecojournal.app.data.SettingsStore
import com.ecojournal.app.data.deriveSigningKeyHex
import com.ecojournal.app.data.installedVersion
import com.ecojournal.app.ui.components.AppUpdateCard
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.util.toPersianDigits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings (Phase 4, v5.3 — Yemen-only): font scale, theme
 * (incl. sepia), the master notification switch (which now
 * gates only the app-update notice), invite state, diagnostics
 * entry, and the app-update card.
 *
 * Removed in v5.3 with their sections: the reader helpers
 * (ticker / ruler / calm — feed-reader features), the Phase 4
 * per-service notification toggles and quiet hours (their
 * notification types no longer exist), the market-threshold
 * presence line, and the Method / voices / special-issues /
 * sections-hub entries.
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
    // Phase 5 (v5.4): hoisted, not conditional — remember() inside
    // the sender-only branch would reset whenever the toggle flips.
    var senderName by remember(settings.senderDisplayName) {
        mutableStateOf(settings.senderDisplayName)
    }
    // Phase 6 (v5.5): shared signing password. The password
    // lives ONLY in this field until the save tap; what is
    // stored is the PBKDF2-derived key hex, never the password.
    var keyPassword by remember { mutableStateOf("") }
    var keyNotice by remember { mutableStateOf<String?>(null) }
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
            modifier = Modifier.semantics { heading() },
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
            // Phase 7: the value text above is a separate node, so
            // the bare slider used to announce with no context.
            modifier = Modifier.semantics {
                contentDescription = context.getString(R.string.font_size_desc)
            },
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
            modifier = Modifier.semantics { heading() },
        )
        for ((key, labelRes) in listOf(
            "system" to R.string.theme_system,
            "light" to R.string.theme_light,
            "dark" to R.string.theme_dark,
            "sepia" to R.string.theme_sepia,
        )) {
            val label = stringResource(labelRes)
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
            modifier = Modifier.semantics { heading() },
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
        // permission — the update worker's notify() is a no-op.
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
        // Phase 5 (v5.4) broadcast messaging: the sender-mode
        // toggle (default off — only Yasin turns it on), the
        // broadcast notification toggle (default on, Law 15), and
        // the display name shown on outgoing envelopes.
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.tab_broadcast),
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Text(
                text = stringResource(R.string.broadcast_sender_toggle),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = settings.broadcastSender,
                onCheckedChange = { v ->
                    scope.launch { store.setBroadcastSender(v) }
                },
            )
        }
        Text(
            text = stringResource(R.string.broadcast_sender_desc),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
        if (settings.broadcastSender) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.broadcast_sender_name_label),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
            TextField(
                value = senderName,
                onValueChange = {
                    senderName = it
                    scope.launch { store.setSenderDisplayName(it) }
                },
                placeholder = {
                    Text(text = stringResource(R.string.broadcast_sender_name_hint), fontFamily = EcoFontFamily)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Text(
                text = stringResource(R.string.broadcast_notif_toggle),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = settings.broadcastNotifications,
                onCheckedChange = { v ->
                    scope.launch { store.setBroadcastNotifications(v) }
                },
            )
        }
        Text(
            text = stringResource(R.string.broadcast_notif_desc),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
        // Phase 6 (v5.5) signing key (design §3.4): the user
        // types the shared password HERE, on their own phone
        // (H-2). Only the PBKDF2-derived key is stored; the
        // password is never stored, logged, or shown. Entering
        // a new password rotates the key (version + 1) and keeps
        // the old key for 7 days of grace.
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.broadcast_key_heading),
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (settings.broadcastKeyVersion >= 1) {
                stringResource(
                    R.string.broadcast_key_status,
                    settings.broadcastKeyVersion.toString().toPersianDigits(),
                    settings.broadcastKeySince?.let { Jalali.formatShamsiDate(it) } ?: "—",
                )
            } else {
                stringResource(R.string.broadcast_key_none)
            },
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
        if (settings.broadcastAnnouncedKeyVersion > settings.broadcastKeyVersion) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.broadcast_key_announced,
                    settings.broadcastAnnouncedKeyVersion.toString().toPersianDigits(),
                ),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        TextField(
            value = keyPassword,
            onValueChange = { keyPassword = it },
            visualTransformation = PasswordVisualTransformation(),
            placeholder = {
                Text(text = stringResource(R.string.broadcast_key_password_hint), fontFamily = EcoFontFamily)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            scope.launch {
                val pw = keyPassword
                if (pw.length < 8) {
                    keyNotice = context.getString(R.string.broadcast_key_short)
                    return@launch
                }
                val newVersion = if (settings.broadcastKeyVersion >= 1) {
                    settings.broadcastKeyVersion + 1
                } else {
                    1
                }
                // 100,000 PBKDF2 iterations off the main thread.
                val derived = withContext(Dispatchers.Default) {
                    deriveSigningKeyHex(pw, newVersion)
                }
                val prevHex = settings.broadcastKeyHex
                val prevVersion = settings.broadcastKeyVersion
                val prevUntil = if (prevHex != null && prevVersion >= 1) {
                    System.currentTimeMillis() +
                        BROADCAST_KEY_GRACE_DAYS * 24L * 3_600_000L
                } else {
                    null
                }
                store.setBroadcastSigningKey(
                    derivedHex = derived,
                    version = newVersion,
                    sinceEpoch = System.currentTimeMillis(),
                    prevHex = prevHex,
                    prevVersion = prevVersion,
                    prevUntilEpoch = prevUntil,
                )
                keyPassword = ""
                keyNotice = context.getString(R.string.broadcast_key_saved)
            }
        }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(
                text = stringResource(R.string.broadcast_key_save),
                fontFamily = EcoFontFamily,
            )
        }
        if (settings.broadcastKeyVersion >= 1) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    store.clearBroadcastSigningKey()
                    keyPassword = ""
                    keyNotice = context.getString(R.string.broadcast_key_cleared)
                }
            }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    text = stringResource(R.string.broadcast_key_clear),
                    fontFamily = EcoFontFamily,
                )
            }
        }
        keyNotice?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = it,
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.broadcast_key_rotate_note),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
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
                // again. Cached Yemen rows are hidden, not deleted.
                // v5.1: mark the lock as user-initiated BEFORE
                // clearing, so MainActivity's flip guard can
                // tell this explicit lock apart from a transient
                // settings emission that lost flag + hash.
                com.ecojournal.app.InviteLockSignal.manualLockRequested = true
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
        // v4-I: remote app update. Content updates need NO APK
        // (the Yemen sync is a data sync); this section is ONLY
        // about updating the app binary itself. The check runs on
        // launch and every 24h (AppUpdateWorker); this button
        // is the manual re-check. A sideloaded app cannot
        // install itself silently — install always opens the
        // system installer with one tap (see AppUpdate.kt).
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_update_title),
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.app_update_current,
                installedVersion(context).versionName.ifBlank { "—" },
            ),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
        // v5 P5: merged label + switch, as above.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Text(
                text = stringResource(R.string.app_update_auto_check),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = settings.updateCheckEnabled,
                onCheckedChange = { v ->
                    scope.launch { store.setUpdateCheckEnabled(v) }
                },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        val updateRepo = remember(context) { AppUpdateRepository(context) }
        val updateState by AppUpdateSession.state.collectAsState()
        AppUpdateCard(
            state = updateState,
            fontScale = fontScale,
            onCheck = { scope.launch { updateRepo.check() } },
            onDownload = { pending ->
                scope.launch { updateRepo.download(pending) }
            },
            onInstall = {
                // U-04 fix: never hand an unvalidated file to the
                // installer. installValidated re-checks the pending
                // record, the file's SHA-256 and the version code,
                // and deletes a stale/corrupt file instead.
                scope.launch { updateRepo.installValidated(context) }
            },
            onDismiss = { pending ->
                scope.launch { updateRepo.dismiss(pending) }
            },
        )
    }
}
