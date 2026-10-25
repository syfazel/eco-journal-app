package com.ecojournal.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.data.ChannelLists
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.Jalali
import com.ecojournal.app.data.RadarSignalEntity
import com.ecojournal.app.data.SectionSyncState
import com.ecojournal.app.data.SyncSections
import com.ecojournal.app.ui.components.CrisisChip
import com.ecojournal.app.ui.components.TypedCard
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.util.toPersianDigits
import kotlinx.coroutines.launch

// War Room section (spec section 4 + 6). Top-level section, not a
// feed tab, reachable in 1 tap from the bottom bar. Order inside
// the screen is fixed (spec 6.1): (1) header block, (2) SITREP
// list, (3) radar block, (4) arc view. The urgent rail is NOT
// here — it lives on the feed (spec 5.3).
//
// Header data: the crisis level comes from the edge-shipped
// war_room object when present, else from the latest SITREP's
// crisis_level, else "not announced" — the app NEVER defaults to
// L1. The "why this level" line renders verbatim only when
// shipped; it is omitted, never generated. The level-change note
// is derived ONLY by comparing two shipped SITREP levels.

@Composable
fun WarRoomScreen(
    repo: EdgeRepository,
    onOpenChannel: (String) -> Unit,
    onOpenMarket: () -> Unit,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val visibleItems by repo.channelDao.visibleChannelFlow().collectAsState(initial = emptyList())
    val signals by repo.channelDao.radarFlow().collectAsState(initial = emptyList())
    val lastSyncAt by repo.dao.metaValueFlow("last_sync_at").collectAsState(initial = null)
    var warLevel by remember { mutableStateOf<String?>(null) }
    var warWhy by remember { mutableStateOf<String?>(null) }
    var radarInterval by remember { mutableStateOf<Int?>(null) }
    var sectionStates by remember { mutableStateOf(emptyList<SectionSyncState>()) }
    var busySection by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val meta = repo.warRoomMeta()
        warLevel = meta.first
        warWhy = meta.second
        radarInterval = meta.third
        sectionStates = repo.sectionStates()
    }

    val sitreps = visibleItems.filter { it.formatType == "SITREP" }
        .sortedByDescending { it.publishedAtEpoch }
    val todayKey = Jalali.shamsiDayKey(System.currentTimeMillis())
    val todaySitreps = sitreps.filter {
        Jalali.shamsiDayKey(it.publishedAtEpoch) == todayKey
    }
    val headerLevel = warLevel
        ?: sitreps.firstOrNull()?.crisisLevel
    val lastSitrep = sitreps.firstOrNull()
    // Level-change note: latest SITREP level vs the previous
    // SITREP's level, shipped values only.
    val levelChange: Pair<String, String>? = run {
        val withLevel = sitreps.filter { it.crisisLevel != null }
        if (withLevel.size >= 2 && withLevel[0].crisisLevel != withLevel[1].crisisLevel) {
            Pair(withLevel[1].crisisLevel!!, withLevel[0].crisisLevel!!)
        } else {
            null
        }
    }
    val radarState = sectionStates.firstOrNull { it.section == SyncSections.RADAR }
    val typedState = sectionStates.firstOrNull { it.section == SyncSections.CHANNEL_TYPED }

    fun openStory(storyId: String) {
        scope.launch {
            val versions = repo.channelDao.channelByStory(storyId)
            val latest = versions.filter { it.status != "retracted" }
                .maxByOrNull { it.publishedAtEpoch }
            if (latest != null) onOpenChannel(latest.appKey)
        }
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.war_room_title),
                    fontSize = 26.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Header block (spec 4.1), always first, always visible.
                if (headerLevel != null) {
                    Row {
                        Text(
                            text = stringResource(
                                R.string.war_room_level, headerLevel,
                            ),
                            fontSize = 18.sp * fontScale,
                            fontFamily = EcoFontFamily,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        CrisisChip(level = headerLevel)
                    }
                } else {
                    Text(
                        text = stringResource(R.string.war_room_level_missing),
                        fontSize = 18.sp * fontScale,
                        fontFamily = EcoFontFamily,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (warWhy != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = warWhy!!,
                        fontSize = 15.sp * fontScale,
                        fontFamily = EcoFontFamily,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (radarInterval != null) {
                        context.getString(
                            R.string.war_room_radar_cadence,
                            radarInterval.toString().toPersianDigits(),
                        )
                    } else {
                        stringResource(R.string.war_room_radar_cadence_missing)
                    },
                    fontSize = 14.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (lastSitrep != null) {
                        context.getString(
                            R.string.war_room_last_sitrep,
                            Jalali.formatShamsiDateTime(lastSitrep.publishedAtEpoch),
                        )
                    } else {
                        stringResource(R.string.war_room_no_sitrep)
                    },
                    fontSize = 14.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (levelChange != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = context.getString(
                            R.string.war_room_level_change,
                            levelChange.first, levelChange.second,
                        ),
                        fontSize = 14.sp * fontScale,
                        fontFamily = EcoFontFamily,
                        fontWeight = FontWeight.Bold,
                    )
                }
                val lastSyncText = lastSyncAt?.toLongOrNull()?.let {
                    Jalali.formatShamsiDateTime(it)
                }
                if (lastSyncText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = context.getString(R.string.feed_offline_line, lastSyncText),
                        fontSize = 12.sp,
                        fontFamily = EcoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (typedState?.state == "failed" || radarState?.state == "failed") {
                    Spacer(modifier = Modifier.height(8.dp))
                    for (s in listOfNotNull(typedState, radarState)) {
                        if (s.state != "failed") continue
                        Text(
                            text = context.getString(
                                R.string.section_error_line,
                                s.section,
                                s.lastErrorCode.ifBlank { "network" },
                            ),
                            fontSize = 13.sp,
                            fontFamily = EcoFontFamily,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            enabled = busySection == null,
                            onClick = {
                                scope.launch {
                                    busySection = s.section
                                    repo.syncOnly(s.section, forceRefresh = true)
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
                }
            }
        }
        // SITREP list (spec 4.3): newest first, FULL bodies.
        item {
            Text(
                text = stringResource(R.string.war_room_sitrep_title),
                fontSize = 20.sp * fontScale,
                fontFamily = EcoFontFamily,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (sitreps.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.war_room_no_sitrep),
                    fontFamily = EcoFontFamily,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            items(sitreps, key = { it.appKey }) { item ->
                TypedCard(
                    item = item,
                    onClick = { onOpenChannel(item.appKey) },
                    preview = false,
                    onOpenStory = { storyId -> openStory(storyId) },
                    onOpenMarket = onOpenMarket,
                    fontScale = fontScale,
                )
            }
        }
        // Radar block (spec 6).
        item {
            Spacer(modifier = Modifier.height(16.dp))
            RadarBlock(
                signals = signals,
                radarInterval = radarInterval,
                onOpenStory = { storyId -> openStory(storyId) },
                fontScale = fontScale,
            )
        }
        // Arc view (spec 4.4): today's SITREPs chronological.
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.war_room_arc_title),
                fontSize = 20.sp * fontScale,
                fontFamily = EcoFontFamily,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (todaySitreps.isEmpty()) {
                Text(
                    text = stringResource(R.string.war_room_arc_empty),
                    fontFamily = EcoFontFamily,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                val chronological = ChannelLists.storyTimeline(
                    todaySitreps.map { it },
                ).sortedBy { it.publishedAtEpoch }
                // Level-path strip: shipped SITREP levels in order.
                val path = chronological.mapNotNull { it.crisisLevel }
                val pathText = if (path.isEmpty()) {
                    null
                } else if (path.distinct().size == 1) {
                    "${path.first()} — ${stringResource(R.string.war_room_arc_no_change)}"
                } else {
                    path.joinToString(" → ")
                }
                if (pathText != null) {
                    Text(
                        text = pathText,
                        fontSize = 15.sp * fontScale,
                        fontFamily = EcoFontFamily,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    for (sitrep in chronological) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenChannel(sitrep.appKey) }
                                .padding(vertical = 6.dp),
                        ) {
                            Text(
                                text = Jalali.formatShamsiDateTime(sitrep.publishedAtEpoch),
                                fontSize = 14.sp * fontScale,
                                fontFamily = EcoFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                            if (sitrep.crisisLevel != null) {
                                CrisisChip(level = sitrep.crisisLevel)
                                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                            }
                            Text(
                                text = sitrep.headline,
                                fontSize = 14.sp * fontScale,
                                fontFamily = EcoFontFamily,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RadarBlock(
    signals: List<RadarSignalEntity>,
    radarInterval: Int?,
    onOpenStory: (String) -> Unit,
    fontScale: Float,
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val todayKey = Jalali.shamsiDayKey(now)
    val todayCount = signals.count {
        Jalali.shamsiDayKey(it.detectedAtEpoch) == todayKey
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.war_room_radar_title),
            fontSize = 20.sp * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = (if (radarInterval != null) {
                context.getString(
                    R.string.war_room_radar_short,
                    radarInterval.toString().toPersianDigits(),
                ) + " • "
            } else {
                ""
            }) + context.getString(
                R.string.war_room_radar_count,
                todayCount.toString().toPersianDigits(),
            ),
            fontSize = 13.sp,
            fontFamily = EcoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (signals.isEmpty()) {
            Text(
                text = stringResource(R.string.war_room_radar_empty),
                fontFamily = EcoFontFamily,
            )
            return
        }
        // Layers in numeric order; unclassified (layer 0) last.
        val layers = listOf(1, 2, 3, 0)
        for (layer in layers) {
            val inLayer = signals.filter { it.layer == layer }
            if (inLayer.isEmpty()) continue
            var expanded by remember(layer) { mutableStateOf(true) }
            Text(
                text = if (layer == 0) {
                    stringResource(R.string.radar_layer_unclassified)
                } else {
                    context.getString(
                        R.string.radar_layer_title,
                        layer.toString().toPersianDigits(),
                    )
                } + " (${inLayer.size.toString().toPersianDigits()})",
                fontSize = 16.sp * fontScale,
                fontFamily = EcoFontFamily,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { expanded = !expanded },
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (!expanded) continue
            val ordered = ChannelLists.radarOrder(inLayer)
            val (fresh, stale) = ordered.partition {
                !ChannelLists.isSignalStale(it, now, radarInterval)
            }
            for (signal in fresh) {
                RadarRow(signal = signal, stale = false, onOpenStory = onOpenStory, fontScale = fontScale)
            }
            if (stale.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.radar_stale_title),
                    fontSize = 14.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (signal in stale) {
                    RadarRow(signal = signal, stale = true, onOpenStory = onOpenStory, fontScale = fontScale)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RadarRow(
    signal: RadarSignalEntity,
    stale: Boolean,
    onOpenStory: (String) -> Unit,
    fontScale: Float,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row {
            if (signal.severity != null) {
                Text(
                    text = signal.severity,
                    fontSize = 13.sp,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (stale) {
                Text(
                    text = stringResource(R.string.radar_stale_tag),
                    fontSize = 12.sp,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            }
            Text(
                text = signal.title,
                fontSize = 15.sp * fontScale,
                fontFamily = EcoFontFamily,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        val byline = "${signal.sourceName} • " +
            Jalali.formatShamsiDateTime(signal.detectedAtEpoch)
        Text(
            text = byline,
            fontSize = 12.sp,
            fontFamily = EcoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (signal.linkedStoryId != null) {
            TextButton(onClick = { onOpenStory(signal.linkedStoryId) }) {
                Text(
                    text = stringResource(R.string.radar_open_story),
                    fontFamily = EcoFontFamily,
                )
            }
        }
    }
}
