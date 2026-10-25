package com.ecojournal.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
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
import com.ecojournal.app.data.MarketFormula
import com.ecojournal.app.data.MarketSnapshot
import com.ecojournal.app.data.SettingsStore
import com.ecojournal.app.ui.screens.ArticleScreen
import com.ecojournal.app.ui.screens.BookmarksScreen
import com.ecojournal.app.ui.screens.CategoriesScreen
import com.ecojournal.app.ui.screens.FeedScreen
import com.ecojournal.app.ui.screens.MarketScreen
import com.ecojournal.app.ui.screens.SettingsScreen
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.ui.theme.EcoTheme
import com.ecojournal.app.util.Notifications

private data class Tab(val route: String, val label: String)

private val TABS = listOf(
    Tab("feed", "فید"),
    Tab("categories", "دسته‌ها"),
    Tab("market", "بازار"),
    Tab("bookmarks", "ذخیره‌شده‌ها"),
    Tab("settings", "تنظیمات"),
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
                            nav.navigate("article/$pendingId")
                        }
                    }
                    Scaffold(
                        bottomBar = { BottomBar(nav) },
                    ) { inner ->
                        val pad = Modifier.padding(inner)
                        var marketLatest by remember { mutableStateOf<MarketSnapshot?>(null) }
                        var marketFormulas by remember { mutableStateOf<List<MarketFormula>>(emptyList()) }
                        var marketCount by remember { mutableStateOf(0) }
                        LaunchedEffect(Unit) {
                            val (latest, formulas, count) = repo.fetchMarket()
                            marketLatest = latest
                            marketFormulas = formulas
                            marketCount = count
                        }
                        NavHost(
                            navController = nav,
                            startDestination = "feed",
                            modifier = pad,
                        ) {
                            composable("feed") {
                                FeedScreen(repo = repo, onOpen = { id ->
                                    nav.navigate("article/$id")
                                })
                            }
                            composable("article/{id}") { back ->
                                val id = back.arguments?.getString("id") ?: ""
                                ArticleScreen(
                                    repo = repo,
                                    articleId = id,
                                    fontScale = settings.fontScale,
                                )
                            }
                            composable("categories") {
                                CategoriesScreen(repo = repo, onOpen = { id ->
                                    nav.navigate("article/$id")
                                })
                            }
                            composable("market") {
                                MarketScreen(
                                    latest = marketLatest,
                                    formulas = marketFormulas,
                                    snapshotCount = marketCount,
                                )
                            }
                            composable("bookmarks") {
                                BookmarksScreen(repo = repo, onOpen = { id ->
                                    nav.navigate("article/$id")
                                })
                            }
                            composable("settings") {
                                SettingsScreen(settings = settings, store = store)
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
    NavigationBar {
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
                icon = { Text("•", fontFamily = EcoFontFamily) },
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
