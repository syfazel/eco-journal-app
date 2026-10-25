package com.ecojournal.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.data.BundleEntity
import com.ecojournal.app.data.BundleKinds
import com.ecojournal.app.data.ChannelItemEntity
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.Jalali
import com.ecojournal.app.data.SectionSyncState
import com.ecojournal.app.data.Slots
import com.ecojournal.app.data.SyncSections
import com.ecojournal.app.ui.components.TypedCard
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.util.toLatinDigits
import com.ecojournal.app.util.toPersianDigits
import kotlinx.coroutines.launch

// Bundles section (spec section 8) + Morning Bundle page (spec 9).
// A bundle page is a single scrollable page in member order —
// never a filtered feed view — and bundle integrity outranks
// list dedup (spec 2.5): the page renders EXACTLY member_app_keys,
// even if a newer version of the same story exists elsewhere.
//
// Morning Bundle section order (spec 9.3), fixed: (1) night-held
// urgent items, (2) Night Desk card, (3) overnight slot/digest
// members, (4) overnight SITREP arc, (5) short-news members.
// Position tags («{position} از {total}») always refer to the
// member's position in the bundle as a whole, as shipped —
// cross-section numbers are NOT renumbered.
//
// Special Issues: frozen under its separate decision. It appears
// here only as the single line «ویژه‌نامه‌ها — متوقف است»,
// with no content, no rows, no navigation (spec 11.5).

private fun formatBundleDate(raw: String): String {
    val latin = raw.toLatinDigits()
    return latin.replace("-", "/").toPersianDigits()
}

@Composable
fun BundlesScreen(
    repo: EdgeRepository,
    onOpenBundle: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bundles by repo.channelDao.bundlesFlow().collectAsState(initial = emptyList())
    var sectionStates by remember { mutableStateOf(emptyList<SectionSyncState>()) }
    var busySection by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        sectionStates = repo.sectionStates()
    }
    val bundlesState = sectionStates.firstOrNull { it.section == SyncSections.BUNDLES }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.bundles_title),
                    fontSize = 26.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Frozen Special Issues: the single line, nothing else.
                Text(
                    text = stringResource(R.string.special_issues_frozen),
                    fontSize = 15.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (bundlesState?.state == "failed") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = context.getString(
                            R.string.section_error_line,
                            "bundles",
                            bundlesState.lastErrorCode.ifBlank { "network" },
                        ),
                        fontSize = 13.sp,
                        fontFamily = EcoFontFamily,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        enabled = busySection == null,
                        onClick = {
                            scope.launch {
                                busySection = SyncSections.BUNDLES
                                repo.syncOnly(SyncSections.BUNDLES, forceRefresh = true)
                                sectionStates = repo.sectionStates()
                                busySection = null
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.sync_retry),
                            fontFamily = EcoFontFamily,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        if (bundles.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.bundles_empty),
                    fontFamily = EcoFontFamily,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            items(bundles, key = { it.bundleId }) { bundle ->
                Column(
                    modifier = Modifier
                        .clickable { onOpenBundle(bundle.bundleId) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = bundle.title,
                        fontSize = 18.sp * fontScale,
                        fontFamily = EcoFontFamily,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${BundleKinds.label(bundle.kind)} • " +
                            context.getString(
                                R.string.bundle_day_count,
                                bundle.memberAppKeys.size.toString().toPersianDigits(),
                            ) + " • ${formatBundleDate(bundle.bundleDateShamsi)}",
                        fontSize = 13.sp,
                        fontFamily = EcoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
fun BundlePageScreen(
    repo: EdgeRepository,
    bundleId: String,
    onOpenChannel: (String) -> Unit,
    onOpenMarket: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bundle by remember { mutableStateOf<BundleEntity?>(null) }
    var members by remember { mutableStateOf<List<ChannelItemEntity>>(emptyList()) }
    var missing by remember { mutableStateOf(false) }
    var allBundles by remember { mutableStateOf<List<BundleEntity>>(emptyList()) }
    var bookmarked by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val lastSyncAt by repo.dao.metaValueFlow("last_sync_at").collectAsState(initial = null)

    LaunchedEffect(bundleId) {
        val found = repo.channelDao.bundleById(bundleId)
        bundle = found
        if (found != null) {
            repo.markBundleOpened(bundleId)
            bookmarked = repo.channelDao.isBundleBookmarked(bundleId)
            val resolved = mutableListOf<ChannelItemEntity>()
            var anyMissing = false
            for (key in found.memberAppKeys) {
                val item = repo.channelDao.channelByAppKey(key)
                if (item == null || item.status == "retracted") {
                    anyMissing = true
                } else {
                    resolved.add(item)
                }
            }
            members = resolved
            missing = anyMissing
            allBundles = repo.channelDao.allBundles()
        }
        loaded = true
    }

    fun openStory(storyId: String) {
        scope.launch {
            val versions = repo.channelDao.channelByStory(storyId)
            val latest = versions.filter { it.status != "retracted" }
                .maxByOrNull { it.publishedAtEpoch }
            if (latest != null) onOpenChannel(latest.appKey)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (!loaded) {
            return@Column
        }
        val b = bundle
        if (b == null || b.status == "retracted") {
            Text(
                text = stringResource(R.string.bundle_not_found),
                fontFamily = EcoFontFamily,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBack) {
                Text(text = stringResource(R.string.article_back), fontFamily = EcoFontFamily)
            }
            return@Column
        }
        val isMorning = b.kind == BundleKinds.MORNING_BUNDLE
        Text(
            text = if (isMorning) {
                context.getString(
                    R.string.morning_bundle_title,
                    formatBundleDate(b.bundleDateShamsi),
                )
            } else {
                b.title
            },
            fontSize = 24.sp * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${BundleKinds.label(b.kind)} • " +
                context.getString(
                    R.string.bundle_day_count,
                    b.memberAppKeys.size.toString().toPersianDigits(),
                ),
            fontSize = 14.sp * fontScale,
            fontFamily = EcoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isMorning) {
            val syncText = lastSyncAt?.toLongOrNull()?.let {
                Jalali.formatShamsiDateTime(it)
            } ?: stringResource(R.string.diagnostics_no_value)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = context.getString(
                    R.string.morning_bundle_availability,
                    formatBundleDate(b.bundleDateShamsi),
                    syncText,
                ),
                fontSize = 13.sp,
                fontFamily = EcoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = {
            scope.launch {
                if (bookmarked) {
                    repo.channelDao.removeBundleBookmark(b.bundleId)
                    bookmarked = false
                } else {
                    repo.channelDao.addBundleBookmark(
                        com.ecojournal.app.data.BundleBookmarkEntity(
                            b.bundleId, System.currentTimeMillis(),
                        )
                    )
                    bookmarked = true
                }
            }
        }) {
            Text(
                text = stringResource(
                    if (bookmarked) R.string.article_unbookmark else R.string.article_bookmark
                ),
                fontFamily = EcoFontFamily,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (b.memberAppKeys.isEmpty()) {
            Text(
                text = stringResource(R.string.bundle_page_empty),
                fontFamily = EcoFontFamily,
            )
            return@Column
        }
        if (missing) {
            Text(
                text = stringResource(R.string.bundle_missing_members),
                fontSize = 14.sp * fontScale,
                fontFamily = EcoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        val orderIndex = b.memberAppKeys.withIndex()
            .associate { it.value to it.index }
        fun positionTag(item: ChannelItemEntity): String {
            val index = orderIndex[item.appKey] ?: 0
            return context.getString(
                R.string.bundle_position_tag,
                (index + 1).toString().toPersianDigits(),
                b.memberAppKeys.size.toString().toPersianDigits(),
            )
        }
        if (!isMorning) {
            for (key in b.memberAppKeys) {
                val item = members.firstOrNull { it.appKey == key } ?: continue
                TypedCard(
                    item = item,
                    onClick = { onOpenChannel(item.appKey) },
                    positionTag = positionTag(item),
                    onOpenStory = { storyId -> openStory(storyId) },
                    onOpenMarket = onOpenMarket,
                    fontScale = fontScale,
                )
            }
            return@Column
        }
        // Morning Bundle sections (spec 9.3), in fixed order.
        val memberSet = members.toSet()
        val held = members.filter { it.wasHeld }.sortedWith(
            compareByDescending<ChannelItemEntity> { it.urgencyScore ?: Int.MIN_VALUE }
                .thenByDescending { it.publishedAtEpoch }
                .thenBy { it.appKey }
        )
        var remaining = memberSet - held.toSet()
        // (2) Night Desk card(s) for the same date, in bundle order.
        val nightDeskBundles = allBundles.filter {
            it.kind == BundleKinds.NIGHT_DESK &&
                it.bundleDateShamsi.toLatinDigits() == b.bundleDateShamsi.toLatinDigits() &&
                it.bundleId != b.bundleId
        }
        val nightDeskItems = mutableListOf<ChannelItemEntity>()
        for (nd in nightDeskBundles) {
            for (key in nd.memberAppKeys) {
                val item = members.firstOrNull { it.appKey == key }
                if (item != null && item !in held) nightDeskItems.add(item)
            }
        }
        remaining = remaining - nightDeskItems.toSet()
        // (4) SITREP arc members (chronological) — classified before
        // short-news so a SITREP never lands in section 5.
        val sitrepMembers = remaining.filter { it.formatType == "SITREP" }
            .sortedBy { it.publishedAtEpoch }
        remaining = remaining - sitrepMembers.toSet()
        // (5) Short-news members: morning members also listed by a
        // short-news bundle for the same date, in morning order.
        val shortNewsKeys = allBundles.filter {
            it.kind == BundleKinds.SHORT_NEWS &&
                it.bundleDateShamsi.toLatinDigits() == b.bundleDateShamsi.toLatinDigits()
        }.flatMap { it.memberAppKeys }.toSet()
        val shortNewsMembers = remaining.filter { it.appKey in shortNewsKeys }
            .sortedBy { orderIndex[it.appKey] ?: Int.MAX_VALUE }
        remaining = remaining - shortNewsMembers.toSet()
        // (3) Overnight slot/digest members: slot order, then position.
        val slotMembers = remaining.sortedWith(
            compareBy<ChannelItemEntity> { Slots.orderIndex(it.slot) }
                .thenBy { orderIndex[it.appKey] ?: Int.MAX_VALUE }
        )
        for (item in held) {
            TypedCard(
                item = item,
                onClick = { onOpenChannel(item.appKey) },
                positionTag = positionTag(item),
                heldTag = true,
                onOpenStory = { storyId -> openStory(storyId) },
                onOpenMarket = onOpenMarket,
                fontScale = fontScale,
            )
        }
        for (nd in nightDeskBundles) {
            val ndItems = nd.memberAppKeys.mapNotNull { key ->
                members.firstOrNull { it.appKey == key }
            }.filter { it !in held }
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.night_desk_title),
                    fontSize = 18.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = context.getString(
                        R.string.night_desk_subtitle,
                        nd.memberAppKeys.size.toString().toPersianDigits(),
                    ),
                    fontSize = 13.sp,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (ndItems.isEmpty()) {
                    Text(
                        text = stringResource(R.string.night_desk_empty),
                        fontFamily = EcoFontFamily,
                    )
                } else {
                    for (ndItem in ndItems) {
                        Text(
                            text = "• ${ndItem.headline}",
                            fontSize = 15.sp * fontScale,
                            fontFamily = EcoFontFamily,
                            modifier = Modifier
                                .clickable { onOpenChannel(ndItem.appKey) }
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
        for (item in slotMembers) {
            TypedCard(
                item = item,
                onClick = { onOpenChannel(item.appKey) },
                positionTag = positionTag(item),
                onOpenStory = { storyId -> openStory(storyId) },
                onOpenMarket = onOpenMarket,
                fontScale = fontScale,
            )
        }
        for ((index, item) in sitrepMembers.withIndex()) {
            if (index == sitrepMembers.lastIndex) {
                Text(
                    text = stringResource(R.string.channel_latest_update),
                    fontSize = 13.sp,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TypedCard(
                item = item,
                onClick = { onOpenChannel(item.appKey) },
                preview = false,
                positionTag = positionTag(item),
                onOpenStory = { storyId -> openStory(storyId) },
                onOpenMarket = onOpenMarket,
                fontScale = fontScale,
            )
        }
        for (item in shortNewsMembers) {
            TypedCard(
                item = item,
                onClick = { onOpenChannel(item.appKey) },
                positionTag = positionTag(item),
                onOpenStory = { storyId -> openStory(storyId) },
                onOpenMarket = onOpenMarket,
                fontScale = fontScale,
            )
        }
    }
}
