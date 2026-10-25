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
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import com.ecojournal.app.data.AppUpdateSession
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.MarketSnapshot
import com.ecojournal.app.data.UpdateUiState
import com.ecojournal.app.data.SettingsStore
import com.ecojournal.app.ui.screens.AnalysisScreen
import com.ecojournal.app.ui.screens.ArticleScreen
import com.ecojournal.app.ui.screens.BookmarksScreen // M6: now in its own file, moved verbatim from the deleted CategoriesScreen.kt
import com.ecojournal.app.ui.screens.BundlePageScreen
import com.ecojournal.app.ui.screens.BundlesScreen
import com.ecojournal.app.ui.screens.ChannelReaderScreen
import com.ecojournal.app.ui.screens.DiagnosticsScreen
import com.ecojournal.app.ui.screens.FeedScreen
import com.ecojournal.app.ui.screens.InviteScreen
import com.ecojournal.app.ui.screens.DossierPageScreen
import com.ecojournal.app.ui.screens.EditionPageScreen
import com.ecojournal.app.ui.screens.MarketScreen
import com.ecojournal.app.ui.screens.MethodScreen
import com.ecojournal.app.ui.screens.NightlyScreen
import com.ecojournal.app.ui.screens.SettingsScreen
import com.ecojournal.app.ui.screens.SpecialIssuesScreen
import com.ecojournal.app.ui.screens.VoicesScreen
import com.ecojournal.app.ui.screens.WarRoomScreen
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.ui.theme.EcoTheme
import com.ecojournal.app.util.Notifications

private data class Tab(
    val route: String,
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

// M3 (P1-12): real icons, not four identical bullets. The label
// itself is the content description, so TalkBack announces the tab.
// Phase 2 adds War Room and Bundles as top-level tabs (spec 4,
// 8.1): War Room is a SECTION, not a feed tab, and Bundles is
// its own top-level entry — neither is a parallel service.
private val TABS = listOf(
    Tab("feed", R.string.tab_feed, Icons.Filled.Home),
    Tab("warroom", R.string.tab_warroom, Icons.Filled.Warning),
    Tab("bundles", R.string.tab_bundles, Icons.Filled.List),
    // Phase 5: Nightly section, top-level alongside Feed /
    // Market / War Room / Bundles (spec §5.14). It is the
    // edition section, not a parallel service and not a
    // separate tab for war-room/red-alert (bar rule §0).
    Tab("nightly", R.string.tab_nightly, Icons.Filled.DarkMode),
    // Phase 6: Analysis is a standalone section (spec §2), and
    // the nightly edition links to the same story_id — two views
    // of one item, never two copies. Not a war-room tab: red-alert
    // items stay inside it with bar + badge (bar rule §0).
    Tab("analysis", R.string.tab_analysis, Icons.Filled.Article),
    Tab("market", R.string.tab_market, Icons.Filled.ShowChart),
    Tab("bookmarks", R.string.tab_bookmarks, Icons.Filled.Bookmark),
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
                    // M7 real invite gate: the feed stays locked
                    // until a valid code is entered (InviteScreen).
                    // The gate replaces the old unconditional
                    // startDestination = "feed".
                    if (!settings.inviteOk) {
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
                    val updateState by AppUpdateSession.state.collectAsState()
                    val updateScope = rememberCoroutineScope()
                    val updateContext = LocalContext.current
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
                        // Market board from Room (M2, M4): the bundled
                        // seed populates market_snapshots AND
                        // market_formulas on first run, so the board
                        // — including computed formulas — works
                        // offline. The edge is not consulted here:
                        // the M5-wired edge URL is used by the sync
                        // pass (not this screen), which is what
                        // refreshes Room from the edge. Seed-done
                        // state is tracked so the
                        // screen can tell "still seeding" (loading)
                        // from "seed ran, market still empty"
                        // (error with retry) instead of guessing
                        // from a null flow value.
                        val context = LocalContext.current
                        val marketEntity by repo.dao.marketLatestFlow()
                            .collectAsState(initial = null)
                        val marketCount by repo.dao.marketCountFlow()
                            .collectAsState(initial = 0)
                        val marketFormulaEntities by repo.dao.formulasFlow()
                            .collectAsState(initial = emptyList())
                        // Phase 4: full orderable history, edge
                        // thresholds (presence via meta flows),
                        // and banner dismissals for the Market tab.
                        val marketHistoryEntities by repo.dao.snapshotsOrderedFlow()
                            .collectAsState(initial = emptyList())
                        val p4BubbleMeta by repo.dao.metaValueFlow("p4_market_alerts_bubble")
                            .collectAsState(initial = null)
                        val p4SpreadMeta by repo.dao.metaValueFlow("p4_market_alerts_spread")
                            .collectAsState(initial = null)
                        val p4DismissBubble by repo.dao.metaValueFlow("p4_banner_dismissed_bubble")
                            .collectAsState(initial = null)
                        val p4DismissSpread by repo.dao.metaValueFlow("p4_banner_dismissed_spread")
                            .collectAsState(initial = null)
                        var marketRefreshTick by remember { mutableStateOf(0) }
                        // Phase 7: the Market tab used to show no
                        // failed-sync surface at all (failures were
                        // visible only in Diagnostics), and the
                        // refresh button's result was discarded.
                        var marketSyncFailed by remember { mutableStateOf(false) }
                        var marketSyncError by remember { mutableStateOf("") }
                        LaunchedEffect(marketRefreshTick) {
                            if (marketRefreshTick > 0) {
                                repo.syncOnly(
                                    com.ecojournal.app.data.SyncSections.MARKET,
                                    forceRefresh = true,
                                )
                            }
                            val st = repo.sectionStates().firstOrNull {
                                it.section == com.ecojournal.app.data.SyncSections.MARKET
                            }
                            marketSyncFailed = st?.state == "failed"
                            marketSyncError = st?.lastErrorCode.orEmpty()
                        }
                        var marketPreviousEntity by remember {
                            mutableStateOf<com.ecojournal.app.data.MarketSnapshotEntity?>(null)
                        }
                        LaunchedEffect(marketEntity) {
                            marketPreviousEntity = marketEntity?.let {
                                repo.dao.marketPrevious(it.tsEpoch)
                            }
                        }
                        var marketSeedDone by remember { mutableStateOf(false) }
                        var marketRetry by remember { mutableStateOf(0) }
                        LaunchedEffect(marketRetry) {
                            com.ecojournal.app.data.BundledSeed.ensureSeed(context)
                            marketSeedDone = true
                        }
                        val marketLatest: MarketSnapshot? = marketEntity?.let {
                            com.ecojournal.app.data.ArticleParsers.marketSnapshotFromEntity(it)
                        }
                        val marketPrevious: MarketSnapshot? = marketPreviousEntity?.let {
                            com.ecojournal.app.data.ArticleParsers.marketSnapshotFromEntity(it)
                        }
                        val marketComputed = marketLatest?.let { latest ->
                            val formulas = marketFormulaEntities.map {
                                com.ecojournal.app.data.MarketFormula(
                                    name = it.name,
                                    version = it.version,
                                    definition = it.definition,
                                    inputs = it.inputs,
                                    descriptionFa = it.descriptionFa,
                                )
                            }
                            com.ecojournal.app.data.MarketFormulas.evaluate(latest, formulas)
                        } ?: emptyList()
                        NavHost(
                            navController = nav,
                            startDestination = "feed",
                            modifier = pad,
                        ) {
                            composable("feed") {
                                FeedScreen(
                                    repo = repo,
                                    onOpen = { appKey ->
                                        nav.navigate("article/${Uri.encode(appKey)}")
                                    },
                                    fontScale = settings.fontScale,
                                    onOpenChannel = { appKey ->
                                        nav.navigate("channel/${Uri.encode(appKey)}")
                                    },
                                    onOpenBundle = { bundleId ->
                                        nav.navigate("bundle/${Uri.encode(bundleId)}")
                                    },
                                    onOpenMarket = { nav.navigate("market") },
                                    // v4-H: ticker toggle + Diagnostics
                                    // entry for the compact sync row.
                                    tickerEnabled = settings.tickerEnabled,
                                    onOpenDiagnostics = { nav.navigate("diagnostics") },
                                    // v4-I: update banner state, shared
                                    // with the Settings card via the
                                    // session flow (never two states).
                                    updateState = updateState,
                                    onUpdateDownload = { pending ->
                                        updateScope.launch { updateRepo.download(pending) }
                                    },
                                    onUpdateInstall = {
                                        val s = AppUpdateSession.state.value
                                        if (s is UpdateUiState.ReadyToInstall) {
                                            updateRepo.install(updateContext, s.file)
                                        } else {
                                            // Permission path: re-run
                                            // install so the settings
                                            // page opens for this
                                            // package only.
                                            val f = updateRepo.downloadFile()
                                            if (f.exists()) {
                                                updateRepo.install(updateContext, f)
                                            }
                                        }
                                    },
                                    onUpdateDismiss = { pending ->
                                        updateScope.launch { updateRepo.dismiss(pending) }
                                    },
                                )
                            }
                            // Phase 2: War Room section (top-level,
                            // not a feed tab) and Bundles section.
                            composable("warroom") {
                                WarRoomScreen(
                                    repo = repo,
                                    onOpenChannel = { appKey ->
                                        nav.navigate("channel/${Uri.encode(appKey)}")
                                    },
                                    onOpenMarket = { nav.navigate("market") },
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("bundles") {
                                BundlesScreen(
                                    repo = repo,
                                    onOpenBundle = { bundleId ->
                                        nav.navigate("bundle/${Uri.encode(bundleId)}")
                                    },
                                    fontScale = settings.fontScale,
                                )
                            }
                            // Phase 5: Nightly section + edition
                            // page + dossier page (spec §5.14).
                            composable("nightly") {
                                NightlyScreen(
                                    repo = repo,
                                    onOpenEdition = { editionId ->
                                        nav.navigate("edition/${Uri.encode(editionId)}")
                                    },
                                    onOpenDossier = { dossierId ->
                                        nav.navigate("dossier/${Uri.encode(dossierId)}")
                                    },
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
                                val marketScope = rememberCoroutineScope()
                                MarketScreen(
                                    latest = marketLatest,
                                    previous = marketPrevious,
                                    formulas = marketComputed,
                                    snapshotCount = marketCount,
                                    seedDone = marketSeedDone,
                                    onRetry = { marketRetry++ },
                                    fontScale = settings.fontScale,
                                    historySnapshots = marketHistoryEntities.map {
                                        com.ecojournal.app.data.ArticleParsers.marketSnapshotFromEntity(it)
                                    },
                                    formulaDefs = marketFormulaEntities.map {
                                        com.ecojournal.app.data.MarketFormula(
                                            name = it.name,
                                            version = it.version,
                                            definition = it.definition,
                                            inputs = it.inputs,
                                            descriptionFa = it.descriptionFa,
                                        )
                                    },
                                    thresholds = com.ecojournal.app.data.MarketThresholds(
                                        bubblePct = p4BubbleMeta?.takeIf { it.isNotBlank() }?.toDoubleOrNull(),
                                        spreadPct = p4SpreadMeta?.takeIf { it.isNotBlank() }?.toDoubleOrNull(),
                                    ),
                                    dismissedBubbleSnapshotId = p4DismissBubble?.takeIf { it.isNotBlank() },
                                    dismissedSpreadSnapshotId = p4DismissSpread?.takeIf { it.isNotBlank() },
                                    onDismissBanner = { metric ->
                                        marketScope.launch {
                                            repo.dismissMarketBanner(
                                                metric,
                                                marketLatest?.snapshotId ?: "",
                                            )
                                        }
                                    },
                                    onRefreshMarket = { marketRefreshTick++ },
                                    marketSyncFailed = marketSyncFailed,
                                    marketSyncErrorCode = marketSyncError,
                                )
                            }
                            composable("bookmarks") {
                                BookmarksScreen(
                                    repo = repo,
                                    onOpen = { appKey ->
                                        nav.navigate("article/${Uri.encode(appKey)}")
                                    },
                                    fontScale = settings.fontScale,
                                    onOpenChannel = { appKey ->
                                        nav.navigate("channel/${Uri.encode(appKey)}")
                                    },
                                    onOpenBundle = { bundleId ->
                                        nav.navigate("bundle/${Uri.encode(bundleId)}")
                                    },
                                )
                            }
                            // Phase 6: Analysis section + Method
                            // page + gated placeholders (voices /
                            // special issues render placeholders
                            // only until their own gates open).
                            composable("analysis") {
                                AnalysisScreen(
                                    repo = repo,
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("method") {
                                MethodScreen(fontScale = settings.fontScale)
                            }
                            composable("voices") {
                                VoicesScreen(fontScale = settings.fontScale)
                            }
                            composable("specialissues") {
                                SpecialIssuesScreen(fontScale = settings.fontScale)
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
                                    // Presence only — never the value.
                                    marketThresholdSet = !p4BubbleMeta.isNullOrBlank() ||
                                        !p4SpreadMeta.isNullOrBlank(),
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
                        popUpTo("feed") { saveState = true }
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
                label = { Text(stringResource(tab.labelRes), fontFamily = EcoFontFamily) },
            )
        }
    }
}
