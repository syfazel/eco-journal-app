package com.ecojournal.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ecojournal.app.R
import com.ecojournal.app.data.AppUpdateRepository
import com.ecojournal.app.data.BroadcastRules
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.SettingsStore
import com.ecojournal.app.ui.screens.BroadcastScreen
import com.ecojournal.app.ui.screens.DiagnosticsScreen
import com.ecojournal.app.ui.screens.InviteScreen
import com.ecojournal.app.ui.screens.SettingsScreen
import com.ecojournal.app.ui.screens.YemenScreen
import com.ecojournal.app.ui.theme.EcoDesignTokens
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.ui.theme.EcoTheme
import com.ecojournal.app.util.Notifications

private data class Tab(
    val route: String,
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/**
 * v5.1 invite-gate flip guard (UI only; the gate itself and the
 * hash handling are untouched). Kept for the Settings manual
 * re-lock button, which sets this flag before clearing the
 * invite (see SettingsScreen). v5.6: the whole-app gate that
 * read this flag is gone — the gate now guards only the
 * Broadcast tab — so the flag is a marker for the re-lock
 * action itself, not a flip guard.
 */
object InviteLockSignal {
    @Volatile
    var manualLockRequested: Boolean = false
}

// Phase 5 (v5.4): the app is Yemen + broadcast messaging. The
// bottom bar is THREE destinations — Yemen, Messaging, Settings.
// Broadcast is a separate tab, never mixed into Yemen (design §1);
// it has its own Room tables, no network, no FCM, no worker.
private val TABS = listOf(
    Tab("yemen", R.string.tab_yemen, Icons.Filled.LocationOn),
    Tab("broadcast", R.string.tab_broadcast, Icons.Filled.Message),
    Tab("settings", R.string.tab_settings, Icons.Filled.Settings),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = EdgeRepository(this)
        val store = SettingsStore(this)
        val updateRepo = AppUpdateRepository(this)
        setContent {
            // M8 gate loading fix: initial = null, so "no value
            // yet" is distinct from "stored inviteOk=false". The
            // gate is only shown after a real DataStore emission;
            // before that, a loading state is shown instead of
            // flashing the code-entry screen at a returning reader
            // (the known M7 limitation this fixes).
            val settingsOrNull by store.settings.collectAsState(initial = null)
            val darkSystem = isSystemInDarkTheme()
            // Whole app is RTL-first (Persian UI).
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                EcoTheme(theme = settingsOrNull?.theme ?: "system", darkSystem = darkSystem) {
                    val settings = settingsOrNull
                    if (settings == null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.gate_loading),
                                    fontFamily = EcoFontFamily,
                                )
                            }
                        }
                        return@EcoTheme
                    }
                    // v5.6 (R2): the invite gate NO LONGER locks
                    // the whole app. The Yemen tab is public
                    // dashboard data (no PII) and is readable
                    // WITHOUT an invite — a fresh install, or a
                    // revoked code, must never hide Yemen behind
                    // a code-entry screen again. The gate now
                    // guards ONLY the Broadcast tab (below): the
                    // broadcast destination shows InviteScreen
                    // until a valid code is entered, while Yemen
                    // and Settings stay open. The old whole-app
                    // gate (M7, with its gate_held flip guard)
                    // is deleted with this change; revocation
                    // still re-locks broadcast via the sync
                    // re-check in EdgeRepository, and the
                    // Settings manual re-lock still clears the
                    // invite (InviteLockSignal, kept for that
                    // button). A kept Yemen cache is never
                    // hidden by the gate.
                    // v4-I: one update check per launch (only
                    // when the toggle is on) + restore of the
                    // stored pending record, so the Settings card
                    // can never disagree with the launch check.
                    // The periodic 24h check lives in
                    // AppUpdateWorker (scheduled in EcoJournalApp).
                    // The session state lives in AppUpdateSession,
                    // read by Settings.
                    LaunchedEffect(settings.updateCheckEnabled) {
                        updateRepo.restorePending()
                        if (settings.updateCheckEnabled) {
                            updateRepo.check()
                        }
                    }
                    // v5.4 morning bundle (Law 15, no worker):
                    // night-held (70-84) broadcast messages are
                    // released on the first launch after 08:00
                    // Tehran, with ONE bundled notification. No
                    // WorkManager, no polling (G-BATT-4).
                    val launchContext = LocalContext.current
                    LaunchedEffect(Unit) {
                        val held = BroadcastRules.releaseMorningBundle(
                            repo.broadcastDao,
                            System.currentTimeMillis(),
                        )
                        if (held.isNotEmpty() && settings.notificationsEnabled &&
                            settings.broadcastNotifications
                        ) {
                            Notifications.showMorningBundle(launchContext, held.size)
                        }
                    }
                    val nav = rememberNavController()
                    // M5 (P2-16): notification permission is NOT
                    // requested blindly on launch any more. It is
                    // requested from Settings with a rationale, only
                    // when notifications are ON and the permission
                    // is missing (see SettingsScreen).
                    // The bottom bar is hidden on diagnostics so
                    // no tab sits selected for a route that is not
                    // a tab.
                    val backEntry by nav.currentBackStackEntryAsState()
                    val hideBar = backEntry?.destination?.route
                        ?.substringBefore("/") in setOf("diagnostics")
                    Scaffold(
                        bottomBar = { if (!hideBar) BottomBar(nav) },
                    ) { inner ->
                        val pad = Modifier.padding(inner)
                        // BundledSeed.ensureSeed populates the
                        // Yemen seed on first composition,
                        // idempotent and resumable, as well as
                        // from Application onCreate — so the Yemen
                        // tab is populated from seed alone when
                        // the edge is unreachable.
                        val context = LocalContext.current
                        LaunchedEffect(Unit) {
                            com.ecojournal.app.data.BundledSeed.ensureSeed(context)
                        }
                        // Broadcast notifications deep-link here:
                        // "broadcast" only when the launch intent
                        // asked for it, otherwise the Yemen tab.
                        val startTab =
                            intent.getStringExtra("open_tab")
                                ?.takeIf { it == "broadcast" } ?: "yemen"
                        NavHost(
                            navController = nav,
                            startDestination = startTab,
                            modifier = pad,
                        ) {
                            composable("yemen") {
                                YemenScreen(
                                    repo = repo,
                                    fontScale = settings.fontScale,
                                    onDiagnostics = { nav.navigate("diagnostics") },
                                )
                            }
                            composable("broadcast") {
                                // v5.6 (R2): the invite gate lives
                                // HERE now, on this tab only. No
                                // valid code -> the code-entry
                                // screen; a valid code -> the
                                // messaging tab. Yemen and
                                // Settings are never gated.
                                if (!settings.inviteOk) {
                                    InviteScreen(
                                        repo = repo,
                                        store = store,
                                        fontScale = settings.fontScale,
                                    )
                                } else {
                                    BroadcastScreen(
                                        settings = settings,
                                        fontScale = settings.fontScale,
                                    )
                                }
                            }
                            composable("settings") {
                                SettingsScreen(
                                    settings = settings,
                                    store = store,
                                    fontScale = settings.fontScale,
                                    onDiagnostics = { nav.navigate("diagnostics") },
                                )
                            }
                            // M8 diagnostics: counts/status only,
                            // no PII; reached from Settings.
                            composable("diagnostics") {
                                DiagnosticsScreen(
                                    repo = repo,
                                    fontScale = settings.fontScale,
                                    onBack = { nav.popBackStack() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController) {
    val back by nav.currentBackStackEntryAsState()
    val current = back?.destination?.route?.substringBefore("/")
    // Paper-coloured bar (M3, P2-02), not the M3 tonal default.
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        for (tab in TABS) {
            NavigationBarItem(
                selected = current == tab.route,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo("yemen") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = stringResource(tab.labelRes),
                    )
                },
                // v5 P2: 12sp token label, one line — the 14sp
                // default is what wrapped labels on the old
                // eight-item bar (plan §1 M3). The bar itself has
                // no fontScale parameter, so the token size is
                // used as-is; the 0.85x–1.3x font-scale check
                // belongs to the P5 device gate (G2/G5).
                label = {
                    Text(
                        stringResource(tab.labelRes),
                        fontFamily = EcoFontFamily,
                        fontSize = EcoDesignTokens.Type.BottomBarLabel,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}
