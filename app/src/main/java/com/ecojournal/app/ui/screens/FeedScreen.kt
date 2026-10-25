package com.ecojournal.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.semantics.heading
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
import com.ecojournal.app.data.ChannelItemEntity
import com.ecojournal.app.data.ChannelLists
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.Formats
import com.ecojournal.app.data.Jalali
import com.ecojournal.app.data.NightLaw
import com.ecojournal.app.data.Slots
import com.ecojournal.app.data.SyncResult
import com.ecojournal.app.ui.components.ArticleCard
import com.ecojournal.app.ui.components.NewspaperDoubleRule
import com.ecojournal.app.ui.components.TypedCard
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
// Phase 2 feed integration (spec 3.2, 5.3, 7). The urgent rail
// sits at the top of the feed, above all slot groups, regardless
// of the format filter. The format filter is a single-select chip
// row, session-only (leaving the feed resets to «همه»), and it
// filters the FEED list only — never War Room, Radar, Bundles or
// the Morning Bundle page. On the «همه» page, typed items render
// as a slot-grouped day view above the article rows; articles
// carry no slot field in vNext data, so their rows keep their
// existing section order below the typed groups (documented in
// the Phase 2 report as the feed-integration decision).

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(
    repo: EdgeRepository,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
    onOpenChannel: (String) -> Unit = {},
    onOpenBundle: (String) -> Unit = {},
    onOpenMarket: () -> Unit = {},
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
    // Phase 2 flows: visible typed items (held items excluded by
    // the query itself), instant items for the rail, bundles for
    // the «بسته» tags on feed cards.
    val typedItems by repo.channelDao.visibleChannelFlow().collectAsState(initial = emptyList())
    val instantItems by repo.channelDao.instantChannelFlow().collectAsState(initial = emptyList())
    val bundles by repo.channelDao.bundlesFlow().collectAsState(initial = emptyList())
    val bundleIds = remember(bundles) { bundles.map { it.bundleId }.toSet() }
    var selectedFormat by remember { mutableStateOf<String?>(null) }
    var showUrgentAll by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ArticleEntity>?>(null) }
    var typedSearchResults by remember { mutableStateOf<List<ChannelItemEntity>>(emptyList()) }
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
    // one Room query per character (P1-04). Typed results join
    // the article results (badges shown, not format-filtered,
    // spec 3.2); held items are excluded by the query itself.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            searchResults = null
            typedSearchResults = emptyList()
        } else {
            delay(300)
            val trimmed = query.trim()
            searchResults = repo.dao.search(escapeLikeQuery(trimmed))
            typedSearchResults = repo.channelDao.searchChannel(escapeLikeQuery(trimmed))
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
        // Phase 7: headings were plain bold text, so TalkBack
        // heading navigation found nothing on any screen.
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 32.sp * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 10.dp)
                .semantics { heading() },
        )
        NewspaperDoubleRule(
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        // Urgent rail (spec 5.3): pinned at the top of the feed,
        // visible regardless of the format filter selection.
        val railNow = System.currentTimeMillis()
        val railOrdered = remember(instantItems, railNow) {
            val ordered = ChannelLists.railOrder(instantItems)
            if (ordered.isEmpty()) {
                ordered
            } else {
                val first = ordered[0]
                if (NightLaw.isPinExpired(first.publishedAtEpoch, railNow)) {
                    // The pinned card leaves the pinned position
                    // after 6h: the rest re-sort, the expired card
                    // moves to the end of the rail (still visible,
                    // never hidden). [SPEC DECISION] on the
                    // expired card's landing position.
                    ordered.drop(1) + first
                } else {
                    ordered
                }
            }
        }
        if (showUrgentAll) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                TextButton(onClick = { showUrgentAll = false }) {
                    Text(stringResource(R.string.article_back), fontFamily = EcoFontFamily)
                }
                Text(
                    text = stringResource(R.string.urgent_rail_title),
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(railOrdered, key = { _, item -> item.appKey }) { _, item ->
                    TypedCard(
                        item = item,
                        onClick = { onOpenChannel(item.appKey) },
                        onOpenMarket = onOpenMarket,
                        fontScale = fontScale,
                        expiredTag = NightLaw.isPinExpired(item.publishedAtEpoch, railNow),
                    )
                }
            }
            return@Column
        }
        if (railOrdered.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 0.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = "🔴 ${stringResource(R.string.urgent_rail_title)}",
                        fontFamily = EcoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp * fontScale,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                for (item in railOrdered.take(5)) {
                    TypedCard(
                        item = item,
                        onClick = { onOpenChannel(item.appKey) },
                        onOpenMarket = onOpenMarket,
                        fontScale = fontScale,
                        expiredTag = NightLaw.isPinExpired(item.publishedAtEpoch, railNow),
                    )
                }
                if (railOrdered.size > 5) {
                    TextButton(
                        onClick = { showUrgentAll = true },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.urgent_rail_more,
                                (railOrdered.size - 5).toString().toPersianDigits(),
                            ),
                            fontFamily = EcoFontFamily,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
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
                // Phase 7: the status text used to have no weight,
                // so a long Persian sync message at 1.3x could push
                // past the row edge.
                Text(
                    text = syncState,
                    fontFamily = EcoFontFamily,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
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
        if (syncFailed && !syncing) {
            // Offline state (spec 11): cached content + honest line.
            val offlineSyncText = lastSyncAt?.toLongOrNull()?.let { epoch ->
                Jalali.formatShamsiDateTime(epoch)
            } ?: stringResource(R.string.diagnostics_no_value)
            Text(
                text = stringResource(R.string.feed_offline_line, offlineSyncText),
                fontFamily = EcoFontFamily,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        // Format filter chips (spec 3.2): single-select, feed
        // only, session-only. Selecting a format shows ONLY that
        // format's typed cards (slot-grouped); «همه» restores the
        // full feed. The filter never touches War Room, Radar,
        // Bundles or the Morning Bundle page.
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            val filterChips = listOf(null) + Formats.ALL.keys.toList()
            for (chip in filterChips) {
                val label = if (chip == null) {
                    stringResource(R.string.filter_all)
                } else {
                    Formats.badgeText(chip)
                }
                val selected = selectedFormat == chip
                FilterChip(
                    selected = selected,
                    onClick = { selectedFormat = chip },
                    label = {
                        Text(text = label, fontFamily = EcoFontFamily)
                    },
                    // Phase 7: M3 chips sit at ~32dp; give the
                    // format filter a 48dp touch target.
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .heightIn(min = 48.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        val activeFilter = selectedFormat
        if (activeFilter != null && query.isBlank()) {
            val filtered = remember(typedItems, activeFilter) {
                ChannelLists.dedupLatest(
                    typedItems.filter { it.formatType == activeFilter }
                )
            }
            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.feed_typed_empty),
                    fontFamily = EcoFontFamily,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                TypedDaySlotGroups(
                    items = filtered,
                    onOpenChannel = onOpenChannel,
                    onOpenBundle = onOpenBundle,
                    bundleIds = bundleIds,
                    onOpenMarket = onOpenMarket,
                    fontScale = fontScale,
                )
            }
            return@Column
        }

        val results = searchResults
        if (results != null) {
            // Search mode: flat newspaper rows, no pager. Typed
            // results join below the article results, badges shown,
            // not format-filtered (spec 3.2).
            if (results.isEmpty() && typedSearchResults.isEmpty()) {
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
                            formatPersianInt(results.size + typedSearchResults.size),
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
                        items(
                            typedSearchResults,
                            key = { item -> item.appKey },
                        ) { item ->
                            TypedCard(
                                item = item,
                                onClick = { onOpenChannel(item.appKey) },
                                onOpenMarket = onOpenMarket,
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
        } else if (all.isEmpty() && typedItems.isEmpty()) {
            Text(
                text = stringResource(R.string.feed_empty),
                fontFamily = EcoFontFamily,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            // Typed day/slot groups for the «همه» page (spec 7):
            // newest day first, slots in fixed order within a day,
            // deduped by story_id. Computed here, rendered inside
            // the page's single LazyColumn by FrontPageTabs.
            val typedGroups = remember(typedItems) {
                groupTypedByDaySlot(typedItems)
            }
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
                typedGroups = typedGroups,
                onOpenChannel = onOpenChannel,
                onOpenBundle = onOpenBundle,
                bundleIds = bundleIds,
                onOpenMarket = onOpenMarket,
            )
        }
    }
}

/** One day's slot groups for the «همه» page (spec 7). */
private data class DaySlotGroup(
    val dayKey: String,
    val slot: String?,
    val items: List<ChannelItemEntity>,
)

/** Dedup by story_id, newest day first, slots in fixed order. */
private fun groupTypedByDaySlot(
    items: List<ChannelItemEntity>,
): List<DaySlotGroup> {
    val deduped = ChannelLists.dedupLatest(items)
    val byDay = deduped.groupBy { Jalali.shamsiDayKey(it.publishedAtEpoch) }
    val groups = mutableListOf<DaySlotGroup>()
    for (dayKey in byDay.keys.sortedDescending()) {
        val dayItems = byDay[dayKey] ?: continue
        val bySlot = dayItems.groupBy { it.slot }
        val slotOrder = bySlot.keys.sortedBy { Slots.orderIndex(it) }
        for (slot in slotOrder) {
            val slotItems = (bySlot[slot] ?: emptyList())
                .sortedByDescending { it.publishedAtEpoch }
            groups.add(DaySlotGroup(dayKey, slot, slotItems))
        }
    }
    return groups
}

/**
 * Slot-grouped typed list (used by the format-filter view):
 * day headers + slot group headers + typed cards, one LazyColumn.
 */
@Composable
private fun TypedDaySlotGroups(
    items: List<ChannelItemEntity>,
    onOpenChannel: (String) -> Unit,
    onOpenBundle: (String) -> Unit,
    bundleIds: Set<String>,
    onOpenMarket: () -> Unit,
    fontScale: Float,
    modifier: Modifier = Modifier,
) {
    val groups = remember(items) { groupTypedByDaySlot(items) }
    var lastDay: String? = null
    LazyColumn(modifier = modifier.fillMaxSize()) {
        for (group in groups) {
            if (group.dayKey != lastDay) {
                lastDay = group.dayKey
                item(key = "day-${group.dayKey}") {
                    Text(
                        text = group.dayKey.toPersianDigits().replace("-", "/"),
                        fontFamily = EcoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp * fontScale,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            item(key = "slot-${group.dayKey}-${group.slot}") {
                Text(
                    text = stringResource(
                        R.string.slot_group_count,
                        Slots.header(group.slot),
                        group.items.size.toString().toPersianDigits(),
                    ),
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp * fontScale,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            items(group.items, key = { it.appKey }) { item ->
                TypedCard(
                    item = item,
                    onClick = { onOpenChannel(item.appKey) },
                    bundleTag = if (item.bundleId != null && item.bundleId in bundleIds) {
                        { onOpenBundle(item.bundleId) }
                    } else {
                        null
                    },
                    onOpenMarket = onOpenMarket,
                    fontScale = fontScale,
                )
            }
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
    typedGroups: List<DaySlotGroup>? = null,
    onOpenChannel: (String) -> Unit = {},
    onOpenBundle: (String) -> Unit = {},
    bundleIds: Set<String> = emptySet(),
    onOpenMarket: () -> Unit = {},
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
            // «همه» page with typed items: slot-grouped day view
            // first, then the article rows, in ONE LazyColumn so
            // the page scrolls as a single feed (spec 7).
            if (section.key == null && !typedGroups.isNullOrEmpty()) {
                var lastDay: String? = null
                LazyColumn(
                    state = listStateFor(section.key),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    for (group in typedGroups) {
                        if (group.dayKey != lastDay) {
                            lastDay = group.dayKey
                            item(key = "day-${group.dayKey}") {
                                Text(
                                    text = group.dayKey.toPersianDigits().replace("-", "/"),
                                    fontFamily = EcoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp * fontScale,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                        item(key = "slot-${group.dayKey}-${group.slot}") {
                            Text(
                                text = stringResource(
                                    R.string.slot_group_count,
                                    Slots.header(group.slot),
                                    group.items.size.toString().toPersianDigits(),
                                ),
                                fontFamily = EcoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp * fontScale,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                        items(group.items, key = { it.appKey }) { item ->
                            TypedCard(
                                item = item,
                                onClick = { onOpenChannel(item.appKey) },
                                bundleTag = if (item.bundleId != null && item.bundleId in bundleIds) {
                                    { onOpenBundle(item.bundleId) }
                                } else {
                                    null
                                },
                                onOpenMarket = onOpenMarket,
                                fontScale = fontScale,
                            )
                        }
                    }
                    val titleCounts = shown.groupingBy { it.title }.eachCount()
                    val seenTitles = mutableSetOf<String>()
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
            } else if (shown.isEmpty()) {
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
