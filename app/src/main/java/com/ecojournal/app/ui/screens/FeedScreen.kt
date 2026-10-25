package com.ecojournal.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.viewmodel.compose.viewModel
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
import androidx.compose.ui.unit.dp
import com.ecojournal.app.R
import com.ecojournal.app.data.ArticleEntity
import com.ecojournal.app.data.BundledSeed
import com.ecojournal.app.data.ChannelItemEntity
import com.ecojournal.app.data.ChannelLists
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.Formats
import com.ecojournal.app.data.SectionStates
import com.ecojournal.app.data.SyncSections
import com.ecojournal.app.data.PendingUpdate
import com.ecojournal.app.data.UpdateUiState
import com.ecojournal.app.data.Jalali
import com.ecojournal.app.data.NightLaw
import com.ecojournal.app.data.Slots
import com.ecojournal.app.data.SyncResult
import com.ecojournal.app.data.SyncTrace
import com.ecojournal.app.data.TickerTopics
import com.ecojournal.app.ui.components.AppUpdateCompactRow
import com.ecojournal.app.ui.components.ArticleCard
import com.ecojournal.app.ui.components.CompactSyncRow
import com.ecojournal.app.ui.components.Masthead
import com.ecojournal.app.ui.components.TypedCard
import com.ecojournal.app.ui.theme.EcoDesignTokens
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
 * v5 P3 feed digest (see FeedScreen): a manifest failure code,
 * plus failed sections in fixed order with their codes.
 * Presentation labels only — never a new section key.
 */
private data class FeedDigest(
    val manifestCode: String?,
    val failed: List<Pair<String, String>>,
)

/** Persian label for a sync-section key, for the digest line. */
private fun sectionLabelFa(key: String): String = when (key) {
    SyncSections.ARTICLES -> "مقالات"
    SyncSections.SHORTS -> "گزیده‌ها"
    SyncSections.MARKET -> "بازار"
    SyncSections.CHANNEL_REFS -> "ارجاعات کانال"
    SyncSections.CHANNEL_TYPED -> "خبرهای کانال"
    SyncSections.BUNDLES -> "بسته‌ها"
    SyncSections.RADAR -> "رادار"
    SyncSections.NIGHTLY -> "نسخهٔ شبانه"
    SyncSections.VOICES -> "صداها"
    SyncSections.SPECIAL_ISSUES -> "ویژه‌نامه‌ها"
    SyncSections.INVITES -> "دعوت‌ها"
    SyncSections.REVOCATIONS -> "ابطال‌ها"
    else -> key
}

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
    // v4-H: masthead ticker toggle + Diagnostics entry for the
    // compact sync row (details live in Diagnostics, not on the
    // page). Both default to the pre-H behaviour when unwired.
    tickerEnabled: Boolean = true,
    // v5 P2: persistent reduced-motion setting, passed straight
    // through to the masthead ticker. Feed branching, sync, and
    // queries are untouched by this parameter.
    reducedMotion: Boolean = false,
    onOpenDiagnostics: (() -> Unit)? = null,
    // v5 P3: sections-hub entry for the empty-state section
    // index (plan §2). Null when unwired (previews): the index
    // names still render, only the button is hidden.
    onOpenSections: (() -> Unit)? = null,
    // v4-I: remote app-update banner state, shared with the
    // Settings card through the session flow. All default to
    // "no banner" so unwired callers (previews) stay unchanged.
    updateState: UpdateUiState = UpdateUiState.Idle,
    onUpdateDownload: (PendingUpdate) -> Unit = {},
    onUpdateInstall: () -> Unit = {},
    onUpdateDismiss: (PendingUpdate) -> Unit = {},
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
    // v5.1 shockproof — retained holder (FeedViewModel, single
    // source of truth for what the feed shows). The Room flows
    // above start at emptyList() on every (re)composition and
    // can emit an empty list transiently; the held lists are
    // replaced ONLY by a non-empty emission, never cleared by
    // an empty one, so a populated feed cannot flip to empty
    // on a loading flag, a sync pass, an invite re-check, or an
    // Activity recreation (rotation: the ViewModel survives;
    // process kill: the holder dies and Room + seed restore).
    // Every emptiness decision below reads the display lists,
    // never the raw flows.
    val feedVm: FeedViewModel = viewModel()
    val heldArticles by feedVm.heldArticles.collectAsState()
    val heldTyped by feedVm.heldTyped.collectAsState()
    val heldInstant by feedVm.heldInstant.collectAsState()
    LaunchedEffect(all) { if (all.isNotEmpty()) feedVm.retainArticles(all) }
    LaunchedEffect(typedItems) { if (typedItems.isNotEmpty()) feedVm.retainTyped(typedItems) }
    LaunchedEffect(instantItems) { if (instantItems.isNotEmpty()) feedVm.retainInstant(instantItems) }
    // Cache-first display lists: held wins whenever non-empty,
    // otherwise the live Room emission.
    val displayArticles = if (heldArticles.isNotEmpty()) heldArticles else all
    val displayTyped = if (heldTyped.isNotEmpty()) heldTyped else typedItems
    val displayInstant = if (heldInstant.isNotEmpty()) heldInstant else instantItems
    // Retained-cache indicator: the article or typed list on
    // screen is held while the live Room read is currently empty.
    val articlesRetained = heldArticles.isNotEmpty() && all.isEmpty()
    val typedRetained = heldTyped.isNotEmpty() && typedItems.isEmpty()
    val showingRetained = articlesRetained || typedRetained
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
    // v4.0.1 (fix plan X1/X2): seed failure is state, not a stuck
    // spinner; auto-sync runs once when the feed is still empty
    // after the seed, so a dead worker can never strand the user.
    var seedFailed by remember { mutableStateOf(false) }
    var autoSyncAttempted by remember { mutableStateOf(false) }
    val syncProgress by repo.syncProgress.collectAsState(initial = null)
    // Last successful sync (M5): reactive meta read, shown under
    // the sync row so "up to date" is always verifiable.
    val lastSyncAt by repo.dao.metaValueFlow("last_sync_at")
        .collectAsState(initial = null)
    val lastSyncError by repo.dao.metaValueFlow("last_sync_error_code")
        .collectAsState(initial = null)
    // v5 P3 feed digest (plan §2 "sync truth on the feed"): a
    // one-line digest of the P1 sync trace, shown under the
    // sync row when anything is wrong, with Diagnostics one
    // tap away. It never replaces Diagnostics, and it
    // disappears (not a fake "everything is fine" banner)
    // when nothing has failed. Derivation, all from the
    // existing trace — no new sync behaviour, no new query:
    // latest trace entry per section; "failed" sections in
    // SyncSections.ALL order; a manifest failure wins over
    // section failures because it blocks everything. The
    // "worst code" is the manifest code, else the first
    // failed section's code in that fixed order. States
    // "up_to_date" / "partial" / "not_yet_published" /
    // "unknown" never produce a digest line on their own:
    // a partial section is reported by the sync row + the
    // empty state, and an unpublished section is not a
    // failure (that is the Phase 4 A2 story, unchanged).
    val traceJson by repo.dao.metaValueFlow(SyncTrace.TRACE_KEY)
        .collectAsState(initial = null)
    val feedDigest = remember(traceJson) {
        val latest = mutableMapOf<String, SyncTrace.TraceEntry>()
        for (entry in SyncTrace.parseTrace(traceJson)) {
            latest[entry.section] = entry
        }
        val manifestEntry = latest["manifest"]
        val manifestCode = if (manifestEntry?.state == SectionStates.FAILED) {
            manifestEntry.code.ifBlank { "failed" }
        } else {
            null
        }
        val failed = SyncSections.ALL.mapNotNull { key ->
            val entry = latest[key]
            if (entry?.state == SectionStates.FAILED) {
                key to entry.code.ifBlank { "failed" }
            } else {
                null
            }
        }
        if (manifestCode == null && failed.isEmpty()) null
        else FeedDigest(manifestCode, failed)
    }
    // v4-H RED path: article appKey -> story_id (join_keys), then
    // story_id -> newest typed format (visible typed items). An
    // article whose story has a typed item IS a red-alert item.
    val joinKeys by repo.dao.joinKeysFlow().collectAsState(initial = emptyList())
    val storyByAppKey = remember(joinKeys) {
        joinKeys.mapNotNull { j -> j.storyId?.let { j.appKey to it } }.toMap()
    }
    val typedFormatByStory = remember(displayTyped) {
        displayTyped
            .filter { it.status != "retracted" && it.storyId != null }
            .sortedByDescending { it.publishedAtEpoch }
            .associate { it.storyId!! to it.formatType }
    }
    val typedFormatFor: (ArticleEntity) -> String? = { a ->
        storyByAppKey[a.appKey]?.let { typedFormatByStory[it] }
    }
    // v4-H ticker topics: live war-room topics only (see
    // TickerTopics for the inclusion rule).
    val tickerTopics = remember(displayInstant) { TickerTopics.topics(displayInstant) }

    // v4.0.1 (fix plan X1): the seed must always resolve. Before
    // this fix a throwing seed left `seeding` true forever (an
    // eternal spinner) or crashed startup from Application.
    LaunchedEffect(Unit) {
        try {
            BundledSeed.ensureSeed(context)
        } catch (_: Exception) {
            seedFailed = true
            repo.recordSeedFailure()
        } finally {
            seeding = false
        }
    }

    // v5 P1 feed-branch trace (gate G1): the rendered region is
    // recorded on every transition (branch + article/typed counts),
    // so a phone report can prove whether feed rows were ever
    // shown and then vanished (front_page -> empty, count falling)
    // or never shown at all (empty throughout — the flash was not
    // feed rows). The filter/search/urgent-all regions sit ABOVE
    // the feed branch and return early, so they are branches here
    // too: a filter-empty page with cached articles is not a feed
    // failure. Count keys are included because rows land in one
    // transaction per section, so count changes are transitions,
    // not a per-row log flood. Best-effort; never shown on the
    // feed itself, only on Diagnostics.
    // v5.1: an urgent-all page whose rail has emptied falls back
    // to the held feed instead of sitting on an empty list (the
    // guard on the urgent-all page + the reset below). The
    // branch therefore requires a non-empty rail — expressed
    // here via the retained instant list, which is exactly
    // equivalent: railOrder only reorders (and the pin-expiry
    // step only reorders), it never filters, so an empty rail
    // is exactly an empty retained instant list. A new
    // "retained" branch records the held-cache state (live Room
    // read empty, held list on screen) so Diagnostics can prove
    // a flash was retention, not a vanish. Loading / empty now
    // read the DISPLAY lists: loading never clears a held list,
    // and empty requires held empty AND Room empty (display
    // empty implies both), i.e. a genuinely empty DB.
    val feedBranch = when {
        showUrgentAll && displayInstant.isNotEmpty() -> "urgent_all"
        selectedFormat != null && query.isBlank() -> "filter"
        searchResults != null -> "search"
        showingRetained -> "retained"
        seeding && displayArticles.isEmpty() && displayTyped.isEmpty() -> "loading"
        displayArticles.isEmpty() && displayTyped.isEmpty() -> "empty"
        else -> "front_page"
    }
    // Effect keys are the v5 P1 pins (raw flow sizes + branch):
    // a display list only ever changes on a raw emission, and a
    // retained transition always changes the branch, so these
    // keys fire on every transition the trace must record. The
    // RECORDED counts are the display sizes (what the user saw).
    LaunchedEffect(feedBranch, all.size, typedItems.size) {
        SyncTrace.recordFeedBranch(repo.dao, feedBranch, displayArticles.size, displayTyped.size)
    }
    LaunchedEffect(showUrgentAll, displayInstant.isEmpty()) {
        if (showUrgentAll && displayInstant.isEmpty()) showUrgentAll = false
    }

    // Hoisted sync runner (moved up from below the search field so
    // the auto-sync effect can use it): fixed-string messages only
    // (P1-25). v4.0.1 (fix plan X3): the manual button and retry
    // run a FORCE refresh — every section hash is re-checked on a
    // tap, while per-item hash comparison still skips unchanged
    // bodies, so a tap can never silently do nothing.
    val syncInProgressText = stringResource(R.string.sync_in_progress)
    val runSync: () -> Unit = {
        scope.launch {
            syncing = true
            syncFailed = false
            syncState = syncInProgressText
            syncState = when (val r = repo.sync(forceRefresh = true)) {
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
                        "bad_manifest" -> context.getString(R.string.sync_failed_bad_manifest)
                        else -> context.getString(R.string.sync_failed)
                    }
                }
            }
            syncing = false
        }
    }

    // v4.0.1 (fix plan X2): one foreground auto-sync when the feed
    // is still empty after the seed. Guarded by state so it runs
    // once per feed lifetime, never on every recomposition, and
    // never when articles already exist (periodic cadence unchanged).
    LaunchedEffect(seeding, all.isEmpty()) {
        if (!seeding && all.isEmpty() && !autoSyncAttempted) {
            autoSyncAttempted = true
            runSync()
        }
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
            val roomResults = repo.dao.search(escapeLikeQuery(trimmed))
            val roomTyped = repo.channelDao.searchChannel(escapeLikeQuery(trimmed))
            // v5.1: a Room search over a transiently empty read
            // must not report "no results" while a held list
            // exists — fall back to an in-memory substring
            // search over the held lists (display only; the
            // Room query above stays the authority whenever
            // it returns anything).
            searchResults = if (roomResults.isEmpty() && heldArticles.isNotEmpty()) {
                heldArticles.filter { a ->
                    a.title.contains(trimmed, ignoreCase = true) ||
                        a.summary.contains(trimmed, ignoreCase = true) ||
                        a.bodyBlocks.any { it.contains(trimmed, ignoreCase = true) }
                }
            } else {
                roomResults
            }
            typedSearchResults = if (roomTyped.isEmpty() && heldTyped.isNotEmpty()) {
                heldTyped.filter { t ->
                    t.headline.contains(trimmed, ignoreCase = true) ||
                        t.body.contains(trimmed, ignoreCase = true)
                }
            } else {
                roomTyped
            }
        }
    }

    val sections = remember(displayArticles) {
        val present = displayArticles.map { it.category to it.categoryFa }.distinct().toMap()
        val orderedKeys = CATEGORY_ORDER.filter { it in present } +
            (present.keys - CATEGORY_ORDER.toSet()).sortedBy { present[it] }
        listOf(Section(null, "همه")) +
            orderedKeys.map { Section(it, present[it] ?: it) }
    }
    val pagerState = rememberPagerState(pageCount = { sections.size })
    // v4-H (V16) improved tabs: the selected tab is tracked by
    // section KEY, not by index. Baseline behaviour: a new
    // category inserted before the current tab shifted every
    // index and the pager jumped to a different section. Now the
    // key is remembered on every page change and restored
    // whenever the section list changes, so a sync can never
    // silently move the reader to another tab. Swipe/tap stay in
    // sync through the same pagerState; per-tab scroll states
    // (listStates) are unchanged.
    var selectedSectionKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pagerState.currentPage) {
        selectedSectionKey = sections.getOrNull(pagerState.currentPage)?.key
    }
    LaunchedEffect(sections) {
        val idx = sections.indexOfFirst { it.key == selectedSectionKey }
        if (selectedSectionKey != null && idx >= 0 && idx != pagerState.currentPage) {
            pagerState.animateScrollToPage(idx)
        }
    }
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
        // v4-H masthead: small script ECO JOURNAL wordmark +
        // CNN-style red ticker under the app name, laid out
        // together as one unit. No other publication name appears.
        Masthead(
            topics = tickerTopics,
            tickerEnabled = tickerEnabled,
            fontScale = fontScale,
            reducedMotion = reducedMotion,
        )
        Spacer(modifier = Modifier.height(12.dp))
        // v4-I: compact update banner, directly under the
        // masthead. Only Available/Downloading/Ready/Failed
        // render anything; Idle/Checking stay invisible so the
        // feed is never pushed down by a non-state.
        AppUpdateCompactRow(
            state = updateState,
            fontScale = fontScale,
            onDownload = onUpdateDownload,
            onInstall = onUpdateInstall,
            onDismiss = onUpdateDismiss,
            modifier = Modifier.padding(horizontal = EcoDesignTokens.Spacing.PageMargin),
        )
        // Urgent rail (spec 5.3): pinned at the top of the feed,
        // visible regardless of the format filter selection.
        // v5.1: the rail reads the retained instant list, and
        // the urgent-all page below requires a non-empty rail,
        // so an emptied rail falls through to the held feed
        // instead of an empty page. The pinned-card expiry rule
        // itself is unchanged.
        val railNow = System.currentTimeMillis()
        val railOrdered = remember(displayInstant, railNow) {
            val ordered = ChannelLists.railOrder(displayInstant)
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
        if (showUrgentAll && railOrdered.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = EcoDesignTokens.Spacing.PageMargin),
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
                    modifier = Modifier.padding(horizontal = EcoDesignTokens.Spacing.PageMargin),
                ) {
                    Text(
                        text = "🔴 ${stringResource(R.string.urgent_rail_title)}",
                        fontFamily = EcoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = EcoDesignTokens.Type.RowHeadline * fontScale,
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
                        modifier = Modifier.padding(horizontal = EcoDesignTokens.Spacing.PageMargin),
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
            Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
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
                .padding(horizontal = EcoDesignTokens.Spacing.PageMargin),
        )
        Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
        // v4-H (V12): ONE compact sync line instead of the old
        // button + status + last-sync + offline block. Same runner
        // (runSync), same messages, same retry; failures still
        // show in red with retry, and full per-section state +
        // error codes stay in Diagnostics via the details button.
        // Last-sync keeps its honest "none yet" when no complete
        // sync has ever been recorded — never a fake "up to date".
        val lastSyncCompact = lastSyncAt?.toLongOrNull()?.let { epoch ->
            val formatted = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("fa"))
                .format(Date(epoch)).toPersianDigits()
            context.getString(R.string.sync_last_compact, formatted)
        } ?: context.getString(R.string.sync_never_succeeded)
        // v4.0.1 (fix plan X5): while the articles fetch runs, the
        // sync line shows fetched/total instead of a bare spinner.
        val progress = syncProgress
        val shownSyncState = if (syncing && progress != null) {
            context.getString(
                R.string.sync_progress_articles,
                formatPersianInt(progress.first),
                formatPersianInt(progress.second),
            )
        } else {
            syncState
        }
        CompactSyncRow(
            syncing = syncing,
            syncFailed = syncFailed,
            syncState = shownSyncState,
            lastSyncText = lastSyncCompact,
            onSync = runSync,
            onRetry = runSync,
            onOpenDiagnostics = onOpenDiagnostics,
            fontScale = fontScale,
        )
        // v5 P3 digest line (see the feedDigest computation
        // above): visible only while NOT syncing and only
        // when the trace records a failure. Exactly one next
        // step — Diagnostics — because retry already lives
        // on the sync row itself; a second retry here would
        // be a second step on the same state.
        val digest = feedDigest
        if (!syncing && digest != null) {
            val digestText = when {
                digest.manifestCode != null ->
                    if (digest.manifestCode == "network") {
                        context.getString(
                            R.string.v5_state_offline,
                            digest.manifestCode,
                        )
                    } else {
                        context.getString(
                            R.string.v5_feed_digest_manifest,
                            digest.manifestCode,
                        )
                    }
                digest.failed.size == 1 ->
                    context.getString(
                        R.string.v5_state_section_failed,
                        sectionLabelFa(digest.failed[0].first),
                        digest.failed[0].second,
                    )
                else -> context.getString(
                    R.string.v5_feed_digest_failed,
                    formatPersianInt(digest.failed.size),
                    digest.failed[0].second,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    horizontal = EcoDesignTokens.Spacing.PageMargin,
                ),
            ) {
                Text(
                    text = digestText,
                    fontFamily = EcoFontFamily,
                    fontSize = EcoDesignTokens.Type.Meta * fontScale,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                if (onOpenDiagnostics != null) {
                    TextButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(R.string.sync_details),
                            fontFamily = EcoFontFamily,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
        // Format filter chips (spec 3.2): single-select, feed
        // only, session-only. Selecting a format shows ONLY that
        // format's typed cards (slot-grouped); «همه» restores the
        // full feed. The filter never touches War Room, Radar,
        // Bundles or the Morning Bundle page.
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = EcoDesignTokens.Spacing.PageMargin),
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
        Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
        val activeFilter = selectedFormat
        if (activeFilter != null && query.isBlank()) {
            // v5.1: the filter reads the retained typed list, so
            // a transiently empty Room read cannot turn a
            // populated filter view into a filter-empty page.
            val filtered = remember(displayTyped, activeFilter) {
                ChannelLists.dedupLatest(
                    displayTyped.filter { it.formatType == activeFilter }
                )
            }
            if (filtered.isEmpty()) {
                // v5 P3 honest filter empty (plan §1 F3): the
                // old line ("هنوز خبری منتشر نشده است") read
                // exactly like a cache empty. This line names
                // the filter as the cause, and its exactly one
                // next step clears the filter (session-only,
                // same as tapping the «همه» chip).
                // v5 P5: this short state sits in the feed's
                // non-scrolling root Column, so at 1.3x it must
                // scroll on its own or its button clips.
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(EcoDesignTokens.Spacing.PageMargin),
                ) {
                    Text(
                        text = stringResource(R.string.v5_feed_filter_empty),
                        fontFamily = EcoFontFamily,
                    )
                    TextButton(onClick = { selectedFormat = null }) {
                        Text(
                            stringResource(R.string.v5_feed_filter_clear),
                            fontFamily = EcoFontFamily,
                        )
                    }
                }
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
                // v5 P3: the search empty keeps its own string
                // (never confusable with the feed empty — plan
                // §1 F4) and gains exactly one next step:
                // clearing the query restores the pager.
                // v5 P5: same scroll rule as the filter empty
                // above — a short state in a non-scrolling root.
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(EcoDesignTokens.Spacing.PageMargin),
                ) {
                    Text(
                        text = stringResource(R.string.search_no_results),
                        fontFamily = EcoFontFamily,
                    )
                    TextButton(onClick = { query = "" }) {
                        Text(
                            stringResource(R.string.v5_feed_search_clear),
                            fontFamily = EcoFontFamily,
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = stringResource(
                            R.string.search_results_count,
                            formatPersianInt(results.size + typedSearchResults.size),
                        ),
                        fontFamily = EcoFontFamily,
                        fontSize = EcoDesignTokens.Type.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = EcoDesignTokens.Spacing.PageMargin, vertical = EcoDesignTokens.Spacing.ElementGap),
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
                                typedFormatType = typedFormatFor(a),
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
        } else if (seeding && displayArticles.isEmpty() && displayTyped.isEmpty()) {
            // Proper loading state: shown only while the seed is
            // actually running and nothing is cached yet. Once the
            // seed reports (even partially), an empty cache shows
            // the empty message below, never a permanent spinner.
            // v5 P5: the bare indicator announced nothing to
            // TalkBack; it now carries the seeding description
            // (precomputed: stringResource cannot run inside
            // the semantics block).
            val seedingDesc = stringResource(R.string.v5_feed_seeding)
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(EcoDesignTokens.Spacing.PageMargin)
                    .semantics { contentDescription = seedingDesc },
            )
        } else if (displayArticles.isEmpty() && displayTyped.isEmpty()) {
            // v5.1: this branch is reachable ONLY when the held
            // lists are empty AND the live Room read is empty
            // (display empty implies both) — i.e. the DB is
            // genuinely empty, not merely mid-sync, loading, or
            // mid-re-check. The cause lines below are unchanged
            // (seed failure / offline / error code / no sync
            // ever); the single recovery step is the retry, with
            // Diagnostics and the section index as navigation
            // (both also reachable from the sync row / Settings).
            // v5 P3 empty state (plan §2 language): what
            // happened + the code + the way forward, and a
            // static section index so an empty cache still
            // teaches the sections (plan §1 F5 / §2). First
            // line by cause: seed failure keeps its own line;
            // an offline code uses the fixed offline string;
            // any other recorded code keeps the existing
            // error line; with no sync ever and no code, the
            // fixed no-sync string (not the old feed_empty
            // text, which implied an edge setup step that no
            // longer applies). The old feed_empty string is
            // kept only for the narrow case of an empty cache
            // AFTER a sync was recorded — a state the digest
            // + Diagnostics must then explain.
            // v5 P5: the empty state is the longest short state
            // (first line + two buttons + section index + hub
            // button) in the feed's non-scrolling root Column;
            // at 1.3x on a small phone its tail clipped. It now
            // scrolls on its own, like every other screen's
            // short state.
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(EcoDesignTokens.Spacing.PageMargin),
            ) {
                val errorCode = lastSyncError?.takeIf { it.isNotBlank() }
                if (seedFailed) {
                    Text(
                        text = stringResource(R.string.feed_empty_seed_failed),
                        fontFamily = EcoFontFamily,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (errorCode == "network") {
                    Text(
                        text = stringResource(R.string.v5_state_offline, errorCode),
                        fontFamily = EcoFontFamily,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (errorCode != null) {
                    Text(
                        text = stringResource(R.string.feed_empty_error, errorCode),
                        fontFamily = EcoFontFamily,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = EcoDesignTokens.Spacing.ElementGap),
                    )
                } else if (lastSyncAt == null) {
                    Text(
                        text = stringResource(R.string.v5_state_empty_no_sync),
                        fontFamily = EcoFontFamily,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.feed_empty),
                        fontFamily = EcoFontFamily,
                    )
                }
                TextButton(onClick = runSync) {
                    Text(stringResource(R.string.sync_retry), fontFamily = EcoFontFamily)
                }
                if (onOpenDiagnostics != null) {
                    TextButton(onClick = onOpenDiagnostics) {
                        Text(stringResource(R.string.sync_details), fontFamily = EcoFontFamily)
                    }
                }
                // Static section index (plan §2): the data-
                // driven tabs cannot exist on an empty cache
                // (exactly one "همه" tab), so the empty state
                // itself lists the sections. These are the
                // sections-hub destinations (P2); gated
                // sections (voices, special issues) are NOT
                // listed here — they stay gated, never
                // advertised as destinations.
                Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.BlockGap))
                Text(
                    text = stringResource(R.string.v5_feed_section_index_title),
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = EcoDesignTokens.Type.SectionHeader * fontScale,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
                Text(
                    text = stringResource(R.string.v5_feed_section_index_names),
                    fontFamily = EcoFontFamily,
                    fontSize = EcoDesignTokens.Type.Meta * fontScale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onOpenSections != null) {
                    TextButton(onClick = onOpenSections) {
                        Text(
                            stringResource(R.string.v5_feed_section_index_open),
                            fontFamily = EcoFontFamily,
                        )
                    }
                }
            }
        } else {
            // Typed day/slot groups for the «همه» page (spec 7):
            // newest day first, slots in fixed order within a day,
            // deduped by story_id. Computed here, rendered inside
            // the page's single LazyColumn by FrontPageTabs.
            val typedGroups = remember(displayTyped) {
                groupTypedByDaySlot(displayTyped)
            }
            // v5.1 retained-cache status: one honest line above
            // the held list (what happened + code) with exactly
            // one next step (retry). It is NOT a "up to date"
            // claim and it never clears the list.
            val retainedCode = lastSyncError?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.diagnostics_no_value)
            val retainedLine = if (showingRetained) {
                stringResource(R.string.v51_feed_retained, retainedCode)
            } else {
                null
            }
            FrontPageTabs(
                sections = sections,
                pagerState = pagerState,
                all = displayArticles,
                retainedLine = retainedLine,
                onRetainedRetry = runSync,
                onOpen = onOpen,
                fontScale = fontScale,
                typedFormatFor = typedFormatFor,
                listStateFor = { key ->
                    listStates.getOrPut(key) { LazyListState() }
                },
                onTabClick = { index ->
                    scope.launch { pagerState.animateScrollToPage(index) }
                },
                onSync = runSync,
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
                        fontSize = EcoDesignTokens.Type.RowHeadline * fontScale,
                        modifier = Modifier.padding(horizontal = EcoDesignTokens.Spacing.PageMargin, vertical = EcoDesignTokens.Spacing.ElementGap),
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
                    fontSize = EcoDesignTokens.Type.SectionHeader * fontScale,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = EcoDesignTokens.Spacing.PageMargin, vertical = 6.dp),
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
    // v5 P3: the per-section empty's exactly one next step
    // is a sync retry (a section tab cannot be empty while
    // another tab is populated unless the cache or the last
    // sync is the cause — retry is the honest step).
    onSync: () -> Unit = {},
    // v4-H RED path: article -> newest typed format for its story.
    typedFormatFor: (ArticleEntity) -> String? = { null },
    // v5.1 retained-cache status (see the caller): a non-blocking
    // line above the tabs, with exactly one next step (retry).
    // Null when the live Room read is healthy — the line must
    // never appear next to a fresh list.
    retainedLine: String? = null,
    onRetainedRetry: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (retainedLine != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    horizontal = EcoDesignTokens.Spacing.PageMargin,
                ),
            ) {
                Text(
                    text = retainedLine,
                    fontFamily = EcoFontFamily,
                    fontSize = EcoDesignTokens.Type.Meta * fontScale,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onRetainedRetry,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(R.string.sync_retry),
                        fontFamily = EcoFontFamily,
                    )
                }
            }
        }
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            edgePadding = EcoDesignTokens.Spacing.PageMargin,
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
                                    fontSize = EcoDesignTokens.Type.RowHeadline * fontScale,
                                    modifier = Modifier.padding(horizontal = EcoDesignTokens.Spacing.PageMargin, vertical = EcoDesignTokens.Spacing.ElementGap),
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
                                fontSize = EcoDesignTokens.Type.SectionHeader * fontScale,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = EcoDesignTokens.Spacing.PageMargin, vertical = 6.dp),
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
                            typedFormatType = typedFormatFor(a),
                        )
                    }
                }
            } else if (shown.isEmpty()) {
                // v5 P3: a section tab with no rows names
                // itself as the scope ("in this section") —
                // it is never the feed-empty text — and
                // offers exactly one next step (sync retry).
                Column(modifier = Modifier.padding(EcoDesignTokens.Spacing.PageMargin)) {
                    Text(
                        text = stringResource(R.string.section_empty),
                        fontFamily = EcoFontFamily,
                    )
                    TextButton(onClick = onSync) {
                        Text(
                            stringResource(R.string.sync_retry),
                            fontFamily = EcoFontFamily,
                        )
                    }
                }
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
                            typedFormatType = typedFormatFor(a),
                        )
                    }
                }
            }
        }
    }
}
