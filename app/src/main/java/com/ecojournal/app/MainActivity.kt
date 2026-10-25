package com.ecojournal.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.MarketSnapshot
import com.ecojournal.app.data.SettingsStore
import com.ecojournal.app.ui.screens.ArticleScreen
import com.ecojournal.app.ui.screens.BookmarksScreen
import com.ecojournal.app.ui.screens.FeedScreen
import com.ecojournal.app.ui.screens.MarketScreen
import com.ecojournal.app.ui.screens.SettingsScreen
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.ui.theme.EcoTheme
import com.ecojournal.app.util.Notifications

private data class Tab(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

// M3 (P1-12): real icons, not four identical bullets. The label
// itself is the content description, so TalkBack announces the tab.
private val TABS = listOf(
    Tab("feed", "صفحه اصلی", Icons.Filled.Home),
    Tab("market", "بازار", Icons.Filled.ShowChart),
    Tab("bookmarks", "ذخیره‌شده‌ها", Icons.Filled.Bookmark),
    Tab("settings", "تنظیمات", Icons.Filled.Settings),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = EdgeRepository(this)
        val store = SettingsStore(this)
        setContent {
            val settings by store.settings.collectAsState(
                initial = com.ecojournal.app.data.Settings()
            )
            val darkSystem = isSystemInDarkTheme()
            // Whole app is RTL-first (Persian UI).
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                EcoTheme(theme = settings.theme, darkSystem = darkSystem) {
                    val nav = rememberNavController()
                    RequestNotificationPermission()
                    // Notification deep link: open the tapped article.
                    val pendingId = Notifications.openArticleId(intent)
                    LaunchedEffect(pendingId) {
                        if (pendingId != null) {
                            nav.navigate("article/${Uri.encode(pendingId)}")
                        }
                    }
                    // The reader has its own top bar (M3, P1-08);
                    // the bottom bar is hidden there so no tab
                    // sits selected for a route that is not a tab.
                    val backEntry by nav.currentBackStackEntryAsState()
                    val onReader = backEntry?.destination?.route
                        ?.substringBefore("/") == "article"
                    Scaffold(
                        bottomBar = { if (!onReader) BottomBar(nav) },
                    ) { inner ->
                        val pad = Modifier.padding(inner)
                        // Market board from Room (M2, M4): the bundled
                        // seed populates market_snapshots AND
                        // market_formulas on first run, so the board
                        // — including computed formulas — works
                        // offline. The edge is not consulted here:
                        // BASE_URL stays unwired until the M5
                        // decision, and the sync pass (not this
                        // screen) is what refreshes Room from the
                        // edge. Seed-done state is tracked so the
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
                                )
                            }
                            // Old Categories destination, kept harmless:
                            // sections now live as tabs on the home front
                            // page, so this route delegates to it instead
                            // of being deleted (other code may reference it).
                            composable("categories") {
                                FeedScreen(
                                    repo = repo,
                                    onOpen = { appKey ->
                                        nav.navigate("article/${Uri.encode(appKey)}")
                                    },
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("market") {
                                MarketScreen(
                                    latest = marketLatest,
                                    previous = marketPrevious,
                                    formulas = marketComputed,
                                    snapshotCount = marketCount,
                                    seedDone = marketSeedDone,
                                    onRetry = { marketRetry++ },
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("bookmarks") {
                                BookmarksScreen(
                                    repo = repo,
                                    onOpen = { appKey ->
                                        nav.navigate("article/${Uri.encode(appKey)}")
                                    },
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("settings") {
                                SettingsScreen(
                                    settings = settings,
                                    store = store,
                                    fontScale = settings.fontScale,
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
                        contentDescription = tab.label,
                    )
                },
                label = { Text(tab.label, fontFamily = EcoFontFamily) },
            )
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not: polling fallback still works offline */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
