package com.ecojournal.app

import android.net.Uri
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Warning
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
import com.ecojournal.app.ui.screens.ArticleScreen
import com.ecojournal.app.ui.screens.BundlePageScreen
import com.ecojournal.app.ui.screens.ChannelReaderScreen
import com.ecojournal.app.ui.screens.DiagnosticsScreen
import com.ecojournal.app.ui.screens.InviteScreen
import com.ecojournal.app.ui.screens.DossierPageScreen
import com.ecojournal.app.ui.screens.EditionPageScreen
import com.ecojournal.app.ui.screens.LockedSectionScreen
import com.ecojournal.app.ui.screens.SectionsHubScreen
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

// M3 (P1-12): real icons, not four identical bullets. The label
// itself is the content description, so TalkBack announces the tab.
// v5 P2 (plan §2 navigation): the bar is FIVE destinations only
// (feed, warroom, market, bookmarks, settings). Bundles, Nightly,
// Analysis, and Method moved to the sections hub (route
// "sections", reached from Settings) — the eight-item bar's
// wrapped/split labels were plan §1 M3. War Room is a SECTION,
// not a feed tab, and red-alert items stay inside it with bar +
// badge (bar rule §0); nothing here creates a parallel service
// or a separate red-alert tab. The old routes still exist as
// hub destinations; only the bar changed.
// v5.2 Yemen-only mode (audit §4.4/§4.5 option A): a SIXTH tab,
// "yemen", is added LAST (settings stays fifth). Every other
// section is locked (see the locked routes below) — the bar
// keeps its five old destinations so the lock is visible and
// honest instead of hidden. Static fit check (B-17, no phone):
// 360dp / 6 tabs = 60dp per item; the "یمن" label is 2 chars at
// the 12sp token (≈20dp even at 1.3x font) beside a 24dp icon,
// so the bar fits on paper — the on-phone 360dp/1.3x check is
// still open (see the phase report).
private val TABS = listOf(
    Tab("feed", R.string.tab_feed, Icons.Filled.Home),
    Tab("warroom", R.string.tab_warroom, Icons.Filled.Warning),
    Tab("market", R.string.tab_market, Icons.Filled.ShowChart),
    Tab("bookmarks", R.string.tab_bookmarks, Icons.Filled.Bookmark),
    Tab("settings", R.string.tab_settings, Icons.Filled.Settings),
    Tab("yemen", R.string.tab_yemen, Icons.Filled.LocationOn),
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
                    // M7 real invite gate: the feed stays locked
                    // until a valid code is entered (InviteScreen).
                    // The gate replaces the old unconditional
                    // startDestination = "feed".
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
                    // as an empty feed — the held feed stays.
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
                    // stored pending record, so the feed banner
                    // and the Settings card can never disagree.
                    // The periodic 24h check lives in
                    // AppUpdateWorker (scheduled in EcoJournalApp).
                    // v5.2: the feed route is locked, so the feed
                    // update banner is gone; the update check still
                    // runs here and the Settings card is the update
                    // surface (AppUpdateCard). The session state
                    // lives in AppUpdateSession, read by Settings.
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
                    // Notification deep links: article, typed
                    // channel item (urgent), or Morning Bundle.
                    // A retracted target opens its reader, which
                    // shows the honest "no longer available" line.
                    val pendingId = Notifications.openArticleId(intent)
                    val pendingChannelId = Notifications.openChannelId(intent)
                    val pendingBundleId = Notifications.openBundleId(intent)
                    // Phase 4: a market-alert tap opens the Market
                    // tab (the banner snapshot is the latest one
                    // the banner describes; the tab shows it).
                    val pendingMarketId = Notifications.openMarketSnapshotId(intent)
                    // Phase 5: a nightly notification tap opens
                    // the edition page by edition_id.
                    val pendingNightlyId = Notifications.openNightlyEditionId(intent)
                    LaunchedEffect(pendingId, pendingChannelId, pendingBundleId, pendingMarketId, pendingNightlyId) {
                        if (pendingId != null) {
                            nav.navigate("article/${Uri.encode(pendingId)}")
                        }
                        if (pendingChannelId != null) {
                            nav.navigate("channel/${Uri.encode(pendingChannelId)}")
                        }
                        if (pendingBundleId != null) {
                            nav.navigate("bundle/${Uri.encode(pendingBundleId)}")
                        }
                        if (pendingMarketId != null) {
                            nav.navigate("market")
                        }
                        if (pendingNightlyId != null) {
                            nav.navigate("edition/${Uri.encode(pendingNightlyId)}")
                        }
                    }
                    // The reader has its own top bar (M3, P1-08),
                    // and diagnostics (M8) is a settings detail
                    // screen; the bottom bar is hidden on both so
                    // no tab sits selected for a route that is not
                    // a tab. Channel readers and bundle pages hide
                    // it for the same reason.
                    val backEntry by nav.currentBackStackEntryAsState()
                    val hideBar = backEntry?.destination?.route
                        ?.substringBefore("/") in setOf(
                        "article", "diagnostics", "channel", "bundle",
                        "edition", "dossier",
                    )
                    Scaffold(
                        bottomBar = { if (!hideBar) BottomBar(nav) },
                    ) { inner ->
                        val pad = Modifier.padding(inner)
                        // v5.2 Yemen-only mode: the Market tab is
                        // locked, so the whole market board state
                        // above is gone. Only the seed trigger
                        // stays: BundledSeed.ensureSeed populates
                        // the Yemen seed (and the legacy tables)
                        // on first composition, idempotent and
                        // resumable, as well as from Application
                        // onCreate — so the Yemen tab is populated
                        // from seed alone when the edge is
                        // unreachable.
                        val context = LocalContext.current
                        LaunchedEffect(Unit) {
                            com.ecojournal.app.data.BundledSeed.ensureSeed(context)
                        }
                        NavHost(
                            navController = nav,
                            startDestination = "yemen",
                            modifier = pad,
                        ) {
                            // v5.2 Yemen-only mode (audit §4.5
                            // option A, "locked with reason"):
                            // feed, warroom, market, bookmarks,
                            // bundles, nightly, analysis, method,
                            // voices and special issues all render
                            // the fixed locked line and run NO sync.
                            // The invite gate above is untouched.
                            composable("feed") {
                                LockedSectionScreen(
                                    titleRes = R.string.tab_feed,
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("yemen") {
                                YemenScreen(
                                    repo = repo,
                                    fontScale = settings.fontScale,
                                )
                            }
                            // Phase 2: War Room section (top-level,
                            // not a feed tab) and Bundles section.
                            composable("warroom") {
                                LockedSectionScreen(
                                    titleRes = R.string.tab_warroom,
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("bundles") {
                                LockedSectionScreen(
                                    titleRes = R.string.tab_bundles,
                                    fontScale = settings.fontScale,
                                )
                            }
                            // Phase 5: Nightly section + edition
                            // page + dossier page (spec §5.14).
                            composable("nightly") {
                                LockedSectionScreen(
                                    titleRes = R.string.tab_nightly,
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("edition/{editionId}") { back ->
                                val id = back.arguments?.getString("editionId") ?: ""
                                EditionPageScreen(
                                    repo = repo,
                                    editionId = id,
                                    onOpenDossier = { dossierId ->
                                        nav.navigate("dossier/${Uri.encode(dossierId)}")
                                    },
                                    onOpenEdition = { editionId ->
                                        nav.navigate("edition/${Uri.encode(editionId)}")
                                    },
                                    onBack = { nav.popBackStack() },
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("dossier/{dossierId}") { back ->
                                val id = back.arguments?.getString("dossierId") ?: ""
                                DossierPageScreen(
                                    repo = repo,
                                    dossierId = id,
                                    onOpenEdition = { editionId ->
                                        nav.navigate("edition/${Uri.encode(editionId)}")
                                    },
                                    onBack = { nav.popBackStack() },
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("bundle/{bundleId}") { back ->
                                val id = back.arguments?.getString("bundleId") ?: ""
                                BundlePageScreen(
                                    repo = repo,
                                    bundleId = id,
                                    onOpenChannel = { appKey ->
                                        nav.navigate("channel/${Uri.encode(appKey)}")
                                    },
                                    onOpenMarket = { nav.navigate("market") },
                                    onBack = { nav.popBackStack() },
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("channel/{appKey}") { back ->
                                val id = back.arguments?.getString("appKey") ?: ""
                                ChannelReaderScreen(
                                    repo = repo,
                                    channelId = id,
                                    onOpenChannel = { appKey ->
                                        nav.navigate("channel/${Uri.encode(appKey)}")
                                    },
                                    onOpenMarket = { nav.navigate("market") },
                                    onBack = { nav.popBackStack() },
                                    fontScale = settings.fontScale,
                                )
                            }
                            // Route argument is an appKey (M1), never
                            // the verbatim source id. Uri.encode is
                            // defensive: appKey itself is hex+dash.
                            composable("article/{appKey}") { back ->
                                val id = back.arguments?.getString("appKey") ?: ""
                                ArticleScreen(
                                    repo = repo,
                                    articleId = id,
                                    fontScale = settings.fontScale,
                                    onBack = { nav.popBackStack() },
                                    // v4-H H2: arc nodes open their
                                    // channel reader.
                                    onOpenChannel = { appKey ->
                                        nav.navigate("channel/${Uri.encode(appKey)}")
                                    },
                                )
                            }
                            // M6: the old fake "categories" route is
                            // deleted, not delegated. Sections live as
                            // tabs on the home front page, and a route
                            // that just re-rendered the feed was pure
                            // duplication. No code referenced it (grep
                            // verified at deletion time).
                            composable("market") {
                                LockedSectionScreen(
                                    titleRes = R.string.tab_market,
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("bookmarks") {
                                LockedSectionScreen(
                                    titleRes = R.string.tab_bookmarks,
                                    fontScale = settings.fontScale,
                                )
                            }
                            // Phase 6: Analysis section + Method
                            // page + gated placeholders (voices /
                            // special issues render placeholders
                            // only until their own gates open).
                            composable("analysis") {
                                LockedSectionScreen(
                                    titleRes = R.string.tab_analysis,
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("method") {
                                LockedSectionScreen(
                                    titleRes = R.string.method_title,
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("voices") {
                                LockedSectionScreen(
                                    titleRes = R.string.voices_title,
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("specialissues") {
                                LockedSectionScreen(
                                    titleRes = R.string.special_issues_title,
                                    fontScale = settings.fontScale,
                                )
                            }
                            // v5 P2: sections hub (not a tab).
                            composable("sections") {
                                SectionsHubScreen(
                                    onOpenBundles = { nav.navigate("bundles") },
                                    onOpenNightly = { nav.navigate("nightly") },
                                    onOpenAnalysis = { nav.navigate("analysis") },
                                    onOpenMethod = { nav.navigate("method") },
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("settings") {
                                SettingsScreen(
                                    settings = settings,
                                    store = store,
                                    fontScale = settings.fontScale,
                                    onDiagnostics = { nav.navigate("diagnostics") },
                                    onMethod = { nav.navigate("method") },
                                    onVoices = { nav.navigate("voices") },
                                    onSpecialIssues = { nav.navigate("specialissues") },
                                    onSections = { nav.navigate("sections") },
                                    // v5.2: the Market tab is locked,
                                    // so no market thresholds are
                                    // presented (presence only — never
                                    // the value).
                                    marketThresholdSet = false,
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
