package com.ecojournal.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.data.ArticleEntity
import com.ecojournal.app.data.BundledSeed
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.SyncResult
import com.ecojournal.app.ui.components.ArticleCard
import com.ecojournal.app.ui.components.NewspaperDoubleRule
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.util.escapeLikeQuery
import com.ecojournal.app.util.formatPersianInt
import com.ecojournal.app.util.toPersianDigits
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class Section(val key: String?, val label: String)

/**
 * Fixed, stable section order (M3, P1-10). Sections used to be
 * ordered by first appearance in the cache, so a new article could
 * reorder tabs and change heroes while the user watched. "همه" is
 * always first; known categories follow this fixed order; any
 * category the app has never seen is appended by label, so the
 * order can never silently reshuffle on a sync.
 */
private val CATEGORY_ORDER = listOf(
    "geopolitics",
    "energy",
    "wallstreet",
    "business",
    "rial",
    "general",
)

/**
 * Home front page, broadsheet style. Masthead first, then a
 * ScrollableTabRow of sections synced to a HorizontalPager.
 *
 * Sections are derived only from what is actually in the Room
 * cache: "همه" plus every distinct category present, in the fixed
 * order above. No section, label or article is invented here.
 *
 * Search runs offline over the Room cache (titles, summaries,
 * body text, key points, source and category label — M3, P1-04),
 * with a 300ms debounce, an escaped LIKE query, a result count
 * and a clear button. While a search query is present, the pager
 * is replaced by the flat search results list; clearing the query
 * restores the pager on exactly the page it was on, because the
 * pager state lives outside the search branch.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(
    repo: EdgeRepository,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    // Reactive feed (M2): the list is a Room Flow, so it updates
    // when the seed finishes, when a sync lands, or when a row is
    // retracted — it is never a single once-only read that can
    // permanently show an empty feed. The seed is also triggered
    // here (idempotent and resumable), not only from Application
    // onCreate, so the first read never races a fire-and-forget seed.
    val all by repo.dao.allFlow().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ArticleEntity>?>(null) }
    var syncState by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var syncFailed by remember { mutableStateOf(false) }
    var seeding by remember { mutableStateOf(true) }
    // Last successful sync (M5): reactive meta read, shown under
    // the sync row so "up to date" is always verifiable.
    val lastSyncAt by repo.dao.metaValueFlow("last_sync_at")
        .collectAsState(initial = null)

    LaunchedEffect(Unit) {
        BundledSeed.ensureSeed(context)
        seeding = false
    }

    // Offline search, debounced: restarting this effect on each
    // keystroke cancels the pending query, so typing never fires
    // one Room query per character (P1-04).
    LaunchedEffect(query) {
        if (query.isBlank()) {
            searchResults = null
        } else {
            delay(300)
            searchResults = repo.dao.search(escapeLikeQuery(query.trim()))
        }
    }

    val sections = remember(all) {
        val present = all.map { it.category to it.categoryFa }.distinct().toMap()
        val orderedKeys = CATEGORY_ORDER.filter { it in present } +
            (present.keys - CATEGORY_ORDER.toSet()).sortedBy { present[it] }
        listOf(Section(null, "همه")) +
            orderedKeys.map { Section(it, present[it] ?: it) }
    }
    val pagerState = rememberPagerState(pageCount = { sections.size })
    // Section-keyed scroll states (M3, P1-09): kept in a map that
    // outlives any one page composition, so swiping two sections
    // away and back restores the scroll position instead of
    // resetting to the top. Pager neighbours stay composed for
    // smoothness, but the map is what preserves position.
    val listStates = remember { mutableMapOf<String?, LazyListState>() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Masthead: ECO JOURNAL only. No other publication name appears.
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 32.sp * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 10.dp),
        )
        NewspaperDoubleRule(
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.search_label), fontFamily = EcoFontFamily) },
            // Theme typography is Vazirmatn (M3), but the field's
            // typed text gets it explicitly too: before M3 it fell
            // back to the system font while its label was Vazirmatn.
            textStyle = TextStyle(fontFamily = EcoFontFamily),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.clear_search),
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        // Hoisted out of the click handler: stringResource is a
        // composable call and cannot run inside a coroutine launch.
        val syncInProgressText = stringResource(R.string.sync_in_progress)
        // M5: one sync runner shared by the sync button and the
        // retry button, so a failed sync always has a retry path.
        // Fixed-string messages only (P1-25): the failure code is
        // mapped here, raw errors never reach the UI.
        val runSync: () -> Unit = {
            scope.launch {
                syncing = true
                syncFailed = false
                syncState = syncInProgressText
                syncState = when (val r = repo.sync()) {
                    is SyncResult.Ok -> {
                        if (r.newArticleAppKeys.isEmpty()) {
                            context.getString(
                                R.string.sync_up_to_date,
                                formatPersianInt(r.totalCached),
                            )
                        } else {
                            context.getString(
                                R.string.sync_new_articles,
                                formatPersianInt(r.newArticleAppKeys.size),
                            )
                        }
                    }
                    is SyncResult.Partial -> {
                        syncFailed = true
                        context.getString(
                            R.string.sync_partial,
                            formatPersianInt(r.missingCount),
                        )
                    }
                    is SyncResult.NotConfigured ->
                        context.getString(R.string.sync_not_configured)
                    is SyncResult.Failed -> {
                        syncFailed = true
                        when (r.code) {
                            "network" -> context.getString(R.string.sync_failed_offline)
                            "http_404", "http_403", "http_other" ->
                                context.getString(R.string.sync_failed_http)
                            "bad_index" -> context.getString(R.string.sync_failed_bad_index)
                            else -> context.getString(R.string.sync_failed)
                        }
                    }
                }
                syncing = false
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Button(
                enabled = !syncing,
                onClick = runSync,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.sync_button), fontFamily = EcoFontFamily)
            }
            if (syncing) {
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (syncState.isNotBlank()) {
                Text(
                    text = syncState,
                    fontFamily = EcoFontFamily,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            // Last successful sync (M5, from meta): a date when a
            // complete sync has been recorded, an honest "none yet"
            // line otherwise — never a fake "up to date".
            val lastSyncText = lastSyncAt?.toLongOrNull()?.let { epoch ->
                val formatted = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("fa"))
                    .format(Date(epoch)).toPersianDigits()
                context.getString(R.string.sync_last_success, formatted)
            } ?: context.getString(R.string.sync_never_succeeded)
            Text(
                text = lastSyncText,
                fontFamily = EcoFontFamily,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (syncFailed && !syncing) {
                TextButton(onClick = runSync) {
                    Text(stringResource(R.string.sync_retry), fontFamily = EcoFontFamily)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        val results = searchResults
        if (results != null) {
            // Search mode: flat newspaper rows, no pager.
            if (results.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_no_results),
                    fontFamily = EcoFontFamily,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = stringResource(
                            R.string.search_results_count,
                            formatPersianInt(results.size),
                        ),
                        fontFamily = EcoFontFamily,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    val titleCounts = results.groupingBy { it.title }.eachCount()
                    val seenTitles = mutableSetOf<String>()
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(results, key = { _, a -> a.appKey }) { _, a ->
                            val duplicate = (titleCounts[a.title] ?: 0) > 1 &&
                                !seenTitles.add(a.title)
                            ArticleCard(
                                article = a,
                                onClick = { onOpen(a.appKey) },
                                isDuplicateTitle = duplicate,
                                fontScale = fontScale,
                            )
                        }
                    }
                }
            }
        } else if (seeding && all.isEmpty()) {
            // Proper loading state: shown only while the seed is
            // actually running and nothing is cached yet. Once the
            // seed reports (even partially), an empty cache shows
            // the empty message below, never a permanent spinner.
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        } else if (all.isEmpty()) {
            Text(
                text = stringResource(R.string.feed_empty),
                fontFamily = EcoFontFamily,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            FrontPageTabs(
                sections = sections,
                pagerState = pagerState,
                all = all,
                onOpen = onOpen,
                fontScale = fontScale,
                listStateFor = { key ->
                    listStates.getOrPut(key) { LazyListState() }
                },
                onTabClick = { index ->
                    scope.launch { pagerState.animateScrollToPage(index) }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FrontPageTabs(
    sections: List<Section>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    all: List<ArticleEntity>,
    onOpen: (String) -> Unit,
    fontScale: Float,
    listStateFor: (String?) -> LazyListState,
    onTabClick: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            edgePadding = 16.dp,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            sections.forEachIndexed { index, section ->
                // M8: stringResource is a composable call, so the
                // description is resolved here, not inside the
                // non-composable semantics block below.
                val tabDescription = stringResource(R.string.section_label, section.label)
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { onTabClick(index) },
                    text = {
                        Text(
                            text = section.label,
                            fontFamily = EcoFontFamily,
                            fontWeight = if (pagerState.currentPage == index) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                        )
                    },
                    modifier = Modifier.semantics {
                        contentDescription = tabDescription
                    },
                )
            }
        }
        // Pager keeps neighbouring pages composed so swiping stays smooth.
        // NOTE: in this BOM (2024.09.00) the parameter is
        // beyondViewportPageCount, not the older beyondBoundsPageCount —
        // the old name breaks overload resolution and cascades into
        // "composable invocations only from a composable" errors.
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val section = sections[page]
            val shown = if (section.key == null) {
                all
            } else {
                all.filter { it.category == section.key }
            }
            if (shown.isEmpty()) {
                Text(
                    text = stringResource(R.string.section_empty),
                    fontFamily = EcoFontFamily,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                val titleCounts = shown.groupingBy { it.title }.eachCount()
                val seenTitles = mutableSetOf<String>()
                LazyColumn(
                    state = listStateFor(section.key),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // `shown` keeps the epoch order of `all`, so
                    // index 0 — the hero — is the newest article in
                    // this section, not the newest raw date string.
                    itemsIndexed(shown, key = { _, a -> a.appKey }) { index, a ->
                        val duplicate = (titleCounts[a.title] ?: 0) > 1 &&
                            !seenTitles.add(a.title)
                        ArticleCard(
                            article = a,
                            onClick = { onOpen(a.appKey) },
                            isHero = index == 0,
                            isDuplicateTitle = duplicate,
                            fontScale = fontScale,
                        )
                    }
                }
            }
        }
    }
}
