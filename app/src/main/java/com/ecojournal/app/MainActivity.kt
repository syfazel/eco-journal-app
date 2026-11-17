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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.SettingsStore
import com.ecojournal.app.data.SyncTrace
import com.ecojournal.app.ui.screens.DiagnosticsScreen
import com.ecojournal.app.ui.screens.InviteScreen
import com.ecojournal.app.ui.screens.SettingsScreen
import com.ecojournal.app.ui.screens.YemenScreen
import com.ecojournal.app.ui.theme.EcoDesignTokens
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.ui.theme.EcoTheme

private data class Tab(
    val route: String,
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/**
 * v5.1 invite-gate flip guard (UI only; the gate itself and the
 * hash handling are untouched).
 *
 * The SettingsScreen manual re-lock button sets this flag before
 * clearing the invite (see SettingsScreen). MainActivity reads
 * it to tell an explicit user lock apart from a transient
 * settings emission that lost the flag AND the hash (e.g. a
 * corrupt DataStore read falling back to empty preferences):
 * the latter must never flip a signed-in, populated app back to
 * the code-entry screen. The flag is cleared as soon as a valid
 * invite is observed again, so an old manual lock can never
 * poison a later transient failure. A genuine revocation needs
 * no flag: EdgeRepository clears only the flag and KEEPS the
 * stored hash, so it is recognised by the hash still being
 * present (see the gate below) and always locks.
 */
object InviteLockSignal {
    @Volatile
    var manualLockRequested: Boolean = false
}

// Phase 4 (v5.3): the app is Yemen-only. The bottom bar is TWO
// destinations — Yemen and Settings. Every other section (feed,
// war room, market, bookmarks, bundles, nightly, analysis,
// method, voices, special issues) was REMOVED in v5.3, not
// locked: their screens, data and sync are deleted (see
// files/archive-sections-5.3/). Diagnostics stays a settings
// detail screen, reached from Settings.
private val TABS = listOf(
    Tab("yemen", R.string.tab_yemen, Icons.Filled.LocationOn),
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
                    // M7 real invite gate: the Yemen tab stays locked
                    // until a valid code is entered (InviteScreen).
                    // v5.1 shockproof — the re-check must NEVER
                    // flip a signed-in app back to an empty /
                    // locked screen on a transient failure. The
                    // data layer already guarantees this for
                    // fetch failures (EdgeRepository skips the
                    // re-check entirely when the edge lists are
                    // unfetchable, and only a successfully
                    // fetched list can re-lock, with the stored
                    // hash kept). This gate closes the remaining
                    // UI-level flip: a settings emission that
                    // lost BOTH the flag and the hash after this
                    // process already saw a valid invite is a
                    // corrupt/transient read, not a revocation,
                    // so the session is kept and the suppression
                    // is traced (branch "gate_held", visible on
                    // Diagnostics). It locks when: the flag is
                    // false and the hash is still present
                    // (genuine revocation), the user tapped
                    // re-lock (InviteLockSignal), or no invite
                    // was ever seen in this process (fresh
                    // install). A kept session is re-resolved by
                    // the next successful sync re-check: a truly
                    // revoked hash then locks via the hash-kept
                    // path. A kept session is never presented
                    // as an empty tab — the held tab stays.
                    var gateEverOk by remember { mutableStateOf(false) }
                    LaunchedEffect(settings.inviteOk) {
                        if (settings.inviteOk) {
                            gateEverOk = true
                            InviteLockSignal.manualLockRequested = false
                        }
                    }
                    val gateHeld = !settings.inviteOk &&
                        settings.inviteHash == null &&
                        !InviteLockSignal.manualLockRequested &&
                        gateEverOk
                    LaunchedEffect(gateHeld) {
                        if (gateHeld) {
                            SyncTrace.recordFeedBranch(
                                repo.dao, "gate_held", -1, -1,
                            )
                        }
                    }
                    if (!settings.inviteOk && !gateHeld) {
                        InviteScreen(
                            repo = repo,
                            store = store,
                            fontScale = settings.fontScale,
                        )
                        return@EcoTheme
                    }
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
                        NavHost(
                            navController = nav,
                            startDestination = "yemen",
                            modifier = pad,
                        ) {
                            composable("yemen") {
                                YemenScreen(
                                    repo = repo,
                                    fontScale = settings.fontScale,
                                )
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
