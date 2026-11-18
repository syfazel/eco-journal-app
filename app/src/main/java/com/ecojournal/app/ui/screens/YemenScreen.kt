package com.ecojournal.app.ui.screens

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ecojournal.app.R
import com.ecojournal.app.data.AppUpdateRepository
import com.ecojournal.app.data.AppUpdateSession
import com.ecojournal.app.data.BundledSeed
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.Jalali
import com.ecojournal.app.data.SyncResult
import com.ecojournal.app.data.YemenAden
import com.ecojournal.app.data.YemenDoc
import com.ecojournal.app.data.YemenFrontline
import com.ecojournal.app.data.YemenParsers
import com.ecojournal.app.data.YemenPlace
import com.ecojournal.app.data.toDomain
import com.ecojournal.app.ui.components.AppUpdateCompactRow
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.util.toPersianDigits
import java.time.Instant
import kotlinx.coroutines.launch
import org.json.JSONArray

// v5.2 Yemen tab (audit §4.4). Six sub-tabs mirroring dashboard
// v5: status+map, conflict borders, Aden encirclement,
// dossiers+analysis, timeline+sources, archive. Schematic Canvas
// map only — no map SDK, no tiles, no images. The ±10 km warning
// always sits under the map. Every Text uses EcoFontFamily
// explicitly (P2-03). Nothing claim-only is ever labelled
// confirmed: claims render under their own layer label, and the
// filter chips select the layer, never re-label it.

private const val STALE_AFTER_HOURS = 24L

private fun factionColor(faction: String): Color = when (faction) {
    "houthi" -> Color(0xFFB3261E)
    "govt" -> Color(0xFF1565C0)
    "contested" -> Color(0xFFEF6C00)
    "aqap" -> Color(0xFF424242)
    "stc" -> Color(0xFF6A1B9A)
    else -> Color(0xFF757575)
}

@Composable
private fun factionLabel(faction: String): String = when (faction) {
    "houthi" -> stringResource(R.string.yemen_faction_houthi)
    "govt" -> stringResource(R.string.yemen_faction_govt)
    "contested" -> stringResource(R.string.yemen_faction_contested)
    "aqap" -> stringResource(R.string.yemen_faction_aqap)
    "stc" -> stringResource(R.string.yemen_faction_stc)
    else -> stringResource(R.string.yemen_faction_unknown)
}

private fun asOfEpoch(asOf: String): Long? {
    return try {
        if (asOf.isBlank()) null else Instant.parse(asOf).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

@Composable
fun YemenScreen(
    repo: EdgeRepository,
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
    // v5.6 (R3): the empty state links to Diagnostics.
    onDiagnostics: () -> Unit = {},
) {
    val vm: YemenViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Live Room reads (start empty on every recomposition).
    val places by repo.yemenDao.placesFlow().collectAsState(initial = emptyList())
    val events by repo.yemenDao.eventsFlow().collectAsState(initial = emptyList())
    val frontlines by repo.yemenDao.frontlinesFlow().collectAsState(initial = emptyList())
    val adenEntity by repo.yemenDao.adenFlow().collectAsState(initial = null)
    val meta by repo.yemenDao.metaFlow().collectAsState(initial = null)
    val docJson by repo.dao.metaValueFlow("yemen_doc_json")
        .collectAsState(initial = null)

    // Retained holder (A1 pattern): replace only on non-empty.
    val heldPlaces by vm.heldPlaces.collectAsState()
    val heldEvents by vm.heldEvents.collectAsState()
    val heldFrontlines by vm.heldFrontlines.collectAsState()
    val heldAden by vm.heldAden.collectAsState()
    val heldDoc by vm.heldDoc.collectAsState()
    val heldMeta by vm.heldMeta.collectAsState()
    LaunchedEffect(places) { vm.retainPlaces(places.map { it.toDomain() }) }
    LaunchedEffect(events) { vm.retainEvents(events.map { it.toDomain() }) }
    LaunchedEffect(frontlines) { vm.retainFrontlines(frontlines.map { it.toDomain() }) }
    LaunchedEffect(adenEntity) { vm.retainAden(adenEntity?.toDomain()) }
    LaunchedEffect(meta) { vm.retainMeta(meta) }
    val doc = remember(docJson) {
        docJson?.let { YemenParsers.parseDoc(it) }
    }
    LaunchedEffect(doc) { vm.retainDoc(doc) }

    // Display lists: held wins whenever non-empty, otherwise the
    // live Room emission. Every emptiness decision reads these.
    val displayPlaces = if (heldPlaces.isNotEmpty()) heldPlaces else places.map { it.toDomain() }
    val displayEvents = if (heldEvents.isNotEmpty()) heldEvents else events.map { it.toDomain() }
    val displayFrontlines = if (heldFrontlines.isNotEmpty()) heldFrontlines else frontlines.map { it.toDomain() }
    val displayAden = heldAden ?: adenEntity?.toDomain()
    val displayDoc = heldDoc ?: doc
    val displayMeta = heldMeta ?: meta

    var selectedSub by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }
    // v5.6 (R6): epoch of the last successful manual refresh,
    // so the header can show "به‌روزرسانی شد: <Jalali>" instead
    // of leaving the user guessing whether the tap did anything.
    var lastRefreshOkAt by remember { mutableStateOf<Long?>(null) }
    // v5.6 (R3): the empty state's error sources — the seed
    // failure flag, the seed-missing count and the last sync
    // error code. A spinner with no code and no retry is the
    // trap this fixes.
    val seedError by repo.dao.metaValueFlow("seed_error")
        .collectAsState(initial = null)
    val seedMissing by repo.dao.metaValueFlow("seed_missing_count")
        .collectAsState(initial = null)
    val lastSyncError by repo.dao.metaValueFlow("last_sync_error_code")
        .collectAsState(initial = null)
    var retryingSeed by remember { mutableStateOf(false) }
    // v5.6 (R1): the in-app update banner lives ON the Yemen
    // tab now, not only in Settings — a phone on 5.1/5.5 must
    // not be able to miss that 5.6 exists. The check itself
    // still runs on launch + 24h (MainActivity / worker);
    // this row only surfaces the already-computed state.
    val updateRepo = remember(context) { AppUpdateRepository(context) }
    val updateState by AppUpdateSession.state.collectAsState()

    val subTabs = listOf(
        R.string.yemen_sub_status,
        R.string.yemen_sub_frontlines,
        R.string.yemen_sub_aden,
        R.string.yemen_sub_dossiers,
        R.string.yemen_sub_timeline,
        R.string.yemen_sub_archive,
    )

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.yemen_title),
            fontFamily = EcoFontFamily,
            fontSize = 20.sp * fontScale,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .semantics { heading() }
                .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
        // v5.6 (R1): update banner on the Yemen tab itself.
        // Available / Downloading / ReadyToInstall / Failed all
        // render; Idle / Checking render nothing (the full card
        // in Settings covers those). A mandatory 5.6 has no
        // dismiss button (see AppUpdateCompactRow).
        AppUpdateCompactRow(
            state = updateState,
            fontScale = fontScale,
            onDownload = { pending ->
                scope.launch { updateRepo.download(pending) }
            },
            onInstall = {
                scope.launch { updateRepo.installValidated(context) }
            },
            onDismiss = { pending ->
                scope.launch { updateRepo.dismiss(pending) }
            },
            modifier = Modifier.padding(
                start = 16.dp, end = 16.dp, top = 8.dp,
            ),
        )
        if (displayPlaces.isEmpty() && displayDoc == null) {
            // Genuinely empty (held empty AND Room empty): the
            // seed has not landed yet. Never a flicker to empty
            // from a populated tab — that path is retained above.
            // v5.6 (R3): this is no longer a bare infinite
            // spinner. It shows the last known error code (if
            // any), a retry button that re-runs the seed AND a
            // forced sync, and a link to Diagnostics.
            val emptyErrorCode = lastSyncError?.takeIf { it.isNotBlank() }
                ?: displayMeta?.lastYemenErrorCode?.takeIf { it.isNotBlank() }
                ?: seedError?.takeIf { it.isNotBlank() }
                ?: if (seedMissing == "1") "seed_missing" else null
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.yemen_empty),
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.yemen_empty_hint),
                    fontFamily = EcoFontFamily,
                    fontSize = 12.sp * fontScale,
                )
                if (emptyErrorCode != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.yemen_empty_error,
                            emptyErrorCode,
                        ),
                        fontFamily = EcoFontFamily,
                        fontSize = 12.sp * fontScale,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (!retryingSeed) {
                            retryingSeed = true
                            scope.launch {
                                try {
                                    BundledSeed.ensureSeed(context)
                                    repo.syncYemen(forceRefresh = true)
                                } finally {
                                    retryingSeed = false
                                }
                            }
                        }
                    },
                    enabled = !retryingSeed,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.yemen_empty_retry),
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDiagnostics,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.yemen_empty_diagnostics),
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                    )
                }
            }
            return@Column
        }
        YemenHeader(
            doc = displayDoc,
            meta = displayMeta,
            eventCount = displayEvents.size,
            refreshing = refreshing,
            syncError = syncError,
            lastRefreshOkAt = lastRefreshOkAt,
            fontScale = fontScale,
            onRefresh = {
                if (!refreshing) {
                    refreshing = true
                    syncError = null
                    scope.launch {
                        when (val r = repo.syncYemen(forceRefresh = true)) {
                            is SyncResult.Ok -> {
                                syncError = null
                                lastRefreshOkAt = System.currentTimeMillis()
                            }
                            is SyncResult.Partial -> syncError = "partial"
                            is SyncResult.Failed -> syncError = r.code
                            is SyncResult.NotConfigured -> syncError = "network"
                        }
                        refreshing = false
                    }
                }
            },
        )
        ScrollableTabRow(selectedTabIndex = selectedSub) {
            subTabs.forEachIndexed { index, res ->
                Tab(
                    selected = selectedSub == index,
                    onClick = { selectedSub = index },
                    text = {
                        Text(
                            text = stringResource(res),
                            fontFamily = EcoFontFamily,
                            fontSize = 13.sp * fontScale,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            when (selectedSub) {
                0 -> StatusMapTab(
                    doc = displayDoc,
                    places = displayPlaces,
                    frontlines = displayFrontlines,
                    fontScale = fontScale,
                )
                1 -> FrontlinesTab(
                    frontlines = displayFrontlines,
                    doc = displayDoc,
                    fontScale = fontScale,
                )
                2 -> AdenTab(
                    aden = displayAden,
                    doc = displayDoc,
                    fontScale = fontScale,
                )
                3 -> DossiersTab(doc = displayDoc, fontScale = fontScale)
                4 -> TimelineTab(doc = displayDoc, events = displayEvents, fontScale = fontScale)
                5 -> ArchiveTab(doc = displayDoc, repo = repo, fontScale = fontScale)
            }
        }
    }
}

@Composable
private fun YemenHeader(
    doc: YemenDoc?,
    meta: com.ecojournal.app.data.YemenMetaEntity?,
    eventCount: Int,
    refreshing: Boolean,
    syncError: String?,
    lastRefreshOkAt: Long?,
    fontScale: Float,
    onRefresh: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val asOf = doc?.asOf?.let { asOfEpoch(it) }
    val hoursAgo = asOf?.let { (now - it) / 3_600_000L }
    val stale = hoursAgo != null && hoursAgo > STALE_AFTER_HOURS
    val offline = meta?.lastYemenErrorCode == "network"
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        if (!doc?.headlineFa.isNullOrBlank()) {
            Text(
                text = doc!!.headlineFa,
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        // Faction-share bar over the 90 control points.
        val counts = doc?.factionCounts ?: emptyMap()
        val total = counts.values.sum()
        if (total > 0) {
            Row(modifier = Modifier.fillMaxWidth().height(10.dp)) {
                for (faction in listOf("houthi", "govt", "contested", "aqap", "stc", "unknown")) {
                    val c = counts[faction] ?: 0
                    if (c <= 0) continue
                    Box(
                        modifier = Modifier
                            .weight(c.toFloat())
                            .height(10.dp)
                            .background(factionColor(faction)),
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (faction in listOf("houthi", "govt", "contested", "aqap", "stc", "unknown")) {
                    val c = counts[faction] ?: 0
                    if (c <= 0) continue
                    Text(
                        text = "${factionLabel(faction)} ${c.toString().toPersianDigits()}",
                        fontFamily = EcoFontFamily,
                        fontSize = 11.sp * fontScale,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        Text(
            text = stringResource(R.string.yemen_events_count, eventCount.toString().toPersianDigits()) +
                " • " + stringResource(
                R.string.yemen_places_count,
                (doc?.counts?.get("places") ?: 0).toString().toPersianDigits(),
            ),
            fontFamily = EcoFontFamily,
            fontSize = 12.sp * fontScale,
        )
        if (asOf != null && hoursAgo != null) {
            Text(
                text = stringResource(
                    R.string.yemen_updated,
                    Jalali.formatShamsiDate(asOf),
                    hoursAgo.toString().toPersianDigits(),
                ),
                fontFamily = EcoFontFamily,
                fontSize = 12.sp * fontScale,
            )
        }
        if (stale) {
            Text(
                text = stringResource(R.string.yemen_stale),
                fontFamily = EcoFontFamily,
                fontSize = 12.sp * fontScale,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (offline) {
            Text(
                text = stringResource(R.string.yemen_offline),
                fontFamily = EcoFontFamily,
                fontSize = 12.sp * fontScale,
            )
        }
        if (syncError != null) {
            Text(
                text = stringResource(R.string.yemen_sync_failed, syncError),
                fontFamily = EcoFontFamily,
                fontSize = 12.sp * fontScale,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // v5.6 (R6): success is shown, not left to guess — the
        // Jalali date-time of the manual refresh that just
        // succeeded. A failure above + this button below is the
        // retry; there is no separate retry control.
        if (lastRefreshOkAt != null && syncError == null) {
            Text(
                text = stringResource(
                    R.string.yemen_refresh_success,
                    Jalali.formatShamsiDateTime(lastRefreshOkAt),
                ),
                fontFamily = EcoFontFamily,
                fontSize = 12.sp * fontScale,
            )
        }
        val changes = meta?.controlChanges ?: 0
        if (changes > 0) {
            Text(
                text = stringResource(
                    R.string.yemen_control_changes,
                    changes.toString().toPersianDigits(),
                ),
                fontFamily = EcoFontFamily,
                fontSize = 12.sp * fontScale,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // v5.6 (R6): the refresh is a prominent, always-visible,
        // full-width button labelled "به‌روزرسانی لحظه‌ای" — the
        // old small "به‌روزرسانی" button hid the fact that the
        // tap force-refreshes past the manifest hash gate
        // (EdgeRepository.syncYemen forceRefresh=true).
        Button(
            onClick = onRefresh,
            enabled = !refreshing,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(
                text = if (refreshing) {
                    stringResource(R.string.yemen_refreshing)
                } else {
                    stringResource(R.string.yemen_refresh)
                },
                fontFamily = EcoFontFamily,
                fontSize = 15.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun StatusMapTab(
    doc: YemenDoc?,
    places: List<YemenPlace>,
    frontlines: List<YemenFrontline>,
    fontScale: Float,
) {
    if (places.isNotEmpty()) {
        YemenSchematicMap(
            places = places,
            frontlines = frontlines,
            doc = doc,
            fontScale = fontScale,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (!doc?.frontlineWarningFa.isNullOrBlank()) {
        Text(
            text = doc!!.frontlineWarningFa,
            fontFamily = EcoFontFamily,
            fontSize = 12.sp * fontScale,
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
    if (!doc?.humanStats.isNullOrEmpty()) {
        SectionTitle(stringResource(R.string.yemen_human_stats), fontScale)
        for (stat in doc!!.humanStats) {
            Text(
                text = "${stat.label}: ${stat.value} (${stat.source})",
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
    if (!doc?.keyPoints.isNullOrEmpty()) {
        for (kp in doc!!.keyPoints) {
            Text(
                text = kp.name,
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = kp.status,
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
            if (kp.basis.isNotBlank()) {
                Text(
                    text = kp.basis,
                    fontFamily = EcoFontFamily,
                    fontSize = 12.sp * fontScale,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun YemenSchematicMap(
    places: List<YemenPlace>,
    frontlines: List<YemenFrontline>,
    doc: YemenDoc?,
    fontScale: Float,
) {
    // Schematic only: lat/lon normalised to the data bounds with
    // padding. No tiles, no SDK, no images. Frontline coordinates
    // are approximate (±10 km) — the warning below the map says so.
    // v5.6 (R5): this is now a real conflict scene, not bare
    // dots — triangular arrowheads with direction, the dashed
    // Aden ring (one 5-slot arc per ring segment, coloured by
    // its status), labels for Aden + key points, and a legend.
    val lats = places.map { it.lat } +
        frontlines.flatMap { f -> f.waypoints.map { it.lat } } +
        (doc?.advanceArrows?.flatMap { listOf(it.fromLat, it.toLat) } ?: emptyList()) +
        (doc?.unitPositions?.map { it.lat } ?: emptyList())
    val lons = places.map { it.lon } +
        frontlines.flatMap { f -> f.waypoints.map { it.lon } } +
        (doc?.advanceArrows?.flatMap { listOf(it.fromLon, it.toLon) } ?: emptyList()) +
        (doc?.unitPositions?.map { it.lon } ?: emptyList())
    val minLat = (lats.minOrNull() ?: 12.0) - 0.5
    val maxLat = (lats.maxOrNull() ?: 17.0) + 0.5
    val minLon = (lons.minOrNull() ?: 42.0) - 0.5
    val maxLon = (lons.maxOrNull() ?: 55.0) + 0.5
    val adenPlace = places.firstOrNull { it.nameFa == "شهر عدن" }
    val labelColor = MaterialTheme.colorScheme.onSurface.toArgb()
    // Labels: Aden + the dashboard key points that match a
    // control point by name (field-for-field, never invented).
    val labelPlaces = buildList {
        adenPlace?.let { add(it.nameFa to (it.lat to it.lon)) }
        for (kp in doc?.keyPoints ?: emptyList()) {
            val match = places.firstOrNull { it.nameFa == kp.name }
            if (match != null && match.nameFa != adenPlace?.nameFa) {
                add(match.nameFa to (match.lat to match.lon))
            }
        }
    }.take(7)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        fun x(lon: Double): Float =
            ((lon - minLon) / (maxLon - minLon) * size.width).toFloat()
        fun y(lat: Double): Float =
            ((maxLat - lat) / (maxLat - minLat) * size.height).toFloat()
        // Frontlines as polylines (only the in-line waypoints).
        for (front in frontlines) {
            val pts = front.waypoints.filter { it.inLine }
                .map { Offset(x(it.lon), y(it.lat)) }
            if (pts.size >= 2) {
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (p in pts.drop(1)) lineTo(p.x, p.y)
                }
                drawPath(
                    path = path,
                    color = Color(0xFFFF7A45),
                    style = Stroke(width = 3f),
                )
            }
            for (w in front.waypoints.filter { !it.inLine }) {
                drawCircle(
                    color = Color(0xFFFF7A45),
                    radius = 5f,
                    center = Offset(x(w.lon), y(w.lat)),
                )
            }
        }
        // Advance arrows: line + a real triangular arrowhead at
        // the tip, pointing along the direction of advance.
        for (arrow in doc?.advanceArrows ?: emptyList()) {
            val from = Offset(x(arrow.fromLon), y(arrow.fromLat))
            val to = Offset(x(arrow.toLon), y(arrow.toLat))
            val color = factionColor(arrow.side)
            drawLine(color = color, start = from, end = to, strokeWidth = 3f)
            val dx = to.x - from.x
            val dy = to.y - from.y
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len > 1f) {
                val ux = dx / len
                val uy = dy / len
                val px = -uy
                val py = ux
                val headLen = 18f
                val headHalf = 8f
                val head = Path().apply {
                    moveTo(to.x, to.y)
                    lineTo(to.x - ux * headLen + px * headHalf, to.y - uy * headLen + py * headHalf)
                    lineTo(to.x - ux * headLen - px * headHalf, to.y - uy * headLen - py * headHalf)
                    close()
                }
                drawPath(path = head, color = color)
            }
        }
        // Unit positions as squares.
        for (unit in doc?.unitPositions ?: emptyList()) {
            val c = Offset(x(unit.lon), y(unit.lat))
            drawRect(
                color = factionColor(unit.side),
                topLeft = Offset(c.x - 5f, c.y - 5f),
                size = Size(10f, 10f),
            )
        }
        // Control points as dots, coloured by faction.
        for (place in places) {
            drawCircle(
                color = factionColor(place.faction),
                radius = 6f,
                center = Offset(x(place.lon), y(place.lat)),
            )
        }
        // v5.6: the Aden ring — five dashed arcs around the city
        // of Aden, one per ring segment, coloured by that
        // segment's status (threat red, contested orange, open
        // green). Arc boundaries are the midpoints between slot
        // centres (east 0°, south 90°, west 180°, northwest
        // 225°, north 270°); the ring is schematic, like the
        // frontlines, and the ±10 km warning below covers it.
        if (adenPlace != null) {
            val cx = x(adenPlace.lon)
            val cy = y(adenPlace.lat)
            val rx = (0.9 / (maxLon - minLon) * size.width).toFloat()
            val ry = (0.9 / (maxLat - minLat) * size.height).toFloat()
            val slotArc = mapOf(
                "east" to (315f to 90f),
                "south" to (45f to 90f),
                "west" to (135f to 67.5f),
                "northwest" to (202.5f to 45f),
                "north" to (247.5f to 67.5f),
            )
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
            for (segment in doc?.aden?.ringSegments ?: emptyList()) {
                val (start, sweep) = slotArc[segment.slot] ?: continue
                val ringColor = when (segment.statusKind) {
                    "threat" -> Color(0xFFB3261E)
                    "contested" -> Color(0xFFEF6C00)
                    "open" -> Color(0xFF2E7D32)
                    else -> Color(0xFF757575)
                }
                drawArc(
                    color = ringColor,
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(cx - rx, cy - ry),
                    size = Size(rx * 2f, ry * 2f),
                    style = Stroke(width = 4f, pathEffect = dash),
                )
            }
            // Aden itself gets a larger ringed dot so the city
            // is findable at a glance.
            drawCircle(
                color = Color(0xFFB3261E),
                radius = 9f,
                center = Offset(cx, cy),
                style = Stroke(width = 3f),
            )
        }
        // Labels: Aden + key points, drawn above their dots.
        if (labelPlaces.isNotEmpty()) {
            drawIntoCanvas { canvas ->
                val paint = Paint().apply {
                    textSize = 34f
                    color = labelColor
                    textAlign = Paint.Align.CENTER
                    isFakeBoldText = true
                }
                for ((name, latLon) in labelPlaces) {
                    canvas.nativeCanvas.drawText(
                        name,
                        x(latLon.second),
                        y(latLon.first) - 16f,
                        paint,
                    )
                }
            }
        }
    }
    // Legend, always under the map (the ±10 km warning is
    // rendered by the caller directly under this map, never
    // anywhere else).
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.yemen_map_legend),
        fontFamily = EcoFontFamily,
        fontSize = 13.sp * fontScale,
        fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(4.dp))
    val legendFactions = listOf("houthi", "govt", "contested", "aqap", "stc", "unknown")
        .filter { faction -> places.any { it.faction == faction } }
    for (faction in legendFactions) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        factionColor(faction),
                        androidx.compose.foundation.shape.CircleShape,
                    ),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = factionLabel(faction),
                fontFamily = EcoFontFamily,
                fontSize = 12.sp * fontScale,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(3.dp)
                .background(Color(0xFFFF7A45)),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.yemen_map_frontline),
            fontFamily = EcoFontFamily,
            fontSize = 12.sp * fontScale,
        )
    }
    Spacer(modifier = Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(3.dp)
                .background(Color(0xFFB3261E)),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.yemen_map_arrow),
            fontFamily = EcoFontFamily,
            fontSize = 12.sp * fontScale,
        )
    }
    Spacer(modifier = Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(Color(0xFF1565C0)),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.yemen_map_unit),
            fontFamily = EcoFontFamily,
            fontSize = 12.sp * fontScale,
        )
    }
    Spacer(modifier = Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(3.dp)
                .background(Color(0xFF2E7D32)),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.yemen_map_aden_ring),
            fontFamily = EcoFontFamily,
            fontSize = 12.sp * fontScale,
        )
    }
}

@Composable
private fun FrontlinesTab(
    frontlines: List<YemenFrontline>,
    doc: YemenDoc?,
    fontScale: Float,
) {
    for (front in frontlines) {
        Text(
            text = front.nameFa,
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
            fontWeight = FontWeight.Bold,
        )
        if (front.statusFa.isNotBlank()) {
            Text(text = front.statusFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
        }
        if (front.summaryFa.isNotBlank()) {
            Text(text = front.summaryFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
        }
        if (front.movementFa.isNotBlank()) {
            Text(text = front.movementFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
        }
        if (front.confidenceFa.isNotBlank()) {
            Text(text = front.confidenceFa, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
        }
        if (front.traceKind != "line" && front.traceKind != "ring") {
            Text(
                text = stringResource(R.string.yemen_frontline_no_line),
                fontFamily = EcoFontFamily,
                fontSize = 12.sp * fontScale,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
    if (!doc?.advanceArrows.isNullOrEmpty()) {
        SectionTitle(stringResource(R.string.yemen_arrows), fontScale)
        for (arrow in doc!!.advanceArrows) {
            Text(text = arrow.labelFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale, fontWeight = FontWeight.Bold)
            if (arrow.noteFa.isNotBlank()) {
                Text(text = arrow.noteFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            }
            Text(
                text = "${arrow.dateFa} • ${arrow.confidenceFa}",
                fontFamily = EcoFontFamily,
                fontSize = 12.sp * fontScale,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    if (!doc?.unitPositions.isNullOrEmpty()) {
        SectionTitle(stringResource(R.string.yemen_units), fontScale)
        for (unit in doc!!.unitPositions) {
            Text(text = unit.nameFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale, fontWeight = FontWeight.Bold)
            Text(text = unit.locationFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            if (unit.noteFa.isNotBlank()) {
                Text(text = unit.noteFa, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AdenTab(
    aden: YemenAden?,
    doc: YemenDoc?,
    fontScale: Float,
) {
    if (aden == null) {
        Text(
            text = stringResource(R.string.yemen_empty),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
        return
    }
    Text(
        text = aden.verdictTitleFa,
        fontFamily = EcoFontFamily,
        fontSize = 18.sp * fontScale,
        fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(text = aden.verdictBodyFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
    Spacer(modifier = Modifier.height(8.dp))
    if (aden.verdictConfidenceFa.isNotBlank()) {
        Text(text = aden.verdictConfidenceFa, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
        Spacer(modifier = Modifier.height(8.dp))
    }
    // The verdict is dated: valid to the dashboard state date.
    val asOf = doc?.asOf?.let { asOfEpoch(it) }
    if (asOf != null) {
        Text(
            text = stringResource(R.string.yemen_aden_valid_to, Jalali.formatShamsiDate(asOf)),
            fontFamily = EcoFontFamily,
            fontSize = 12.sp * fontScale,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
    for (segment in aden.ringSegments) {
        Text(
            text = segment.directionFa,
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
            fontWeight = FontWeight.Bold,
        )
        Text(text = segment.statusFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
        Text(text = segment.bodyFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (aden.timeline.isNotEmpty()) {
        SectionTitle(stringResource(R.string.yemen_sub_timeline), fontScale)
        for (item in aden.timeline) {
            if (item.titleFa.isNotBlank()) {
                Text(text = item.titleFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale, fontWeight = FontWeight.Bold)
            }
            Text(text = item.bodyFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            Text(
                text = "${item.date} • ${item.sourceFa}",
                fontFamily = EcoFontFamily,
                fontSize = 12.sp * fontScale,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    if (aden.keyPoints.isNotEmpty()) {
        for (point in aden.keyPoints) {
            Text(text = point, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            Spacer(modifier = Modifier.height(4.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (aden.layers.confirmed.isNotEmpty() || aden.layers.officialClaims.isNotEmpty() || aden.layers.rumours.isNotEmpty()) {
        SectionTitle(stringResource(R.string.yemen_filter_all), fontScale)
        for (item in aden.layers.confirmed) {
            LayeredItem(labelRes = R.string.yemen_layer_confirmed, title = item.title, body = item.body, fontScale = fontScale)
        }
        for (item in aden.layers.officialClaims) {
            LayeredItem(labelRes = R.string.yemen_layer_official_claim, title = item.title, body = item.body, fontScale = fontScale)
        }
        for (item in aden.layers.rumours) {
            LayeredItem(labelRes = R.string.yemen_layer_rumour, title = item.title, body = item.body, fontScale = fontScale)
        }
    }
    if (aden.contradictions.isNotEmpty()) {
        SectionTitle(stringResource(R.string.yemen_contradictions), fontScale)
        for (item in aden.contradictions) {
            if (item.title.isNotBlank()) {
                Text(text = item.title, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale, fontWeight = FontWeight.Bold)
            }
            Text(text = item.body, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    if (aden.channelsMethodFa.isNotBlank()) {
        SectionTitle(stringResource(R.string.yemen_channels_method), fontScale)
        Text(text = aden.channelsMethodFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (aden.crossCheckSummaryFa.isNotBlank()) {
        Text(text = aden.crossCheckSummaryFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
        Spacer(modifier = Modifier.height(8.dp))
    }
    // v5.6 (R4): the notes that were parsed but never shown.
    if (aden.seenNoteFa.isNotBlank()) {
        SectionTitle(stringResource(R.string.yemen_aden_seen_note), fontScale)
        Text(text = aden.seenNoteFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (aden.taizNoteFa.isNotBlank()) {
        SectionTitle(stringResource(R.string.yemen_aden_taiz_note), fontScale)
        Text(text = aden.taizNoteFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
        Spacer(modifier = Modifier.height(8.dp))
    }
    // v5.6 (R4): the Aden investigation sections the app used
    // to drop entirely — images, cross-check table, official
    // channels, social posts, amplifiers. All verbatim from the
    // dashboard via yemen.json; nothing is fetched or rendered
    // as an image (names + descriptions only, no tiles).
    if (aden.images.isNotEmpty()) {
        SectionTitle(stringResource(R.string.yemen_aden_images), fontScale)
        for (image in aden.images) {
            Text(text = image.name, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale, fontWeight = FontWeight.Bold)
            if (image.originFa.isNotBlank()) {
                Text(text = image.originFa, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
            }
            if (image.showsFa.isNotBlank()) {
                Text(text = image.showsFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            }
            if (image.verdictFa.isNotBlank()) {
                Text(text = image.verdictFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            }
            if (image.url.isNotBlank()) {
                Text(text = image.url, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    if (aden.crossCheck.isNotEmpty()) {
        SectionTitle(stringResource(R.string.yemen_aden_cross_check), fontScale)
        for (row in aden.crossCheck) {
            Text(
                text = "${row.dateFa} • ${row.source}",
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
            Text(text = row.saysFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    if (aden.channelsOfficial.isNotEmpty()) {
        SectionTitle(stringResource(R.string.yemen_aden_channels_official), fontScale)
        for (channel in aden.channelsOfficial) {
            Text(
                text = "${channel.name} ${channel.handle}".trim(),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
            if (channel.kindFa.isNotBlank()) {
                Text(text = channel.kindFa, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
            }
            Text(text = channel.bodyFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            if (channel.verdictFa.isNotBlank()) {
                Text(text = channel.verdictFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    if (aden.socialPosts.isNotEmpty()) {
        SectionTitle(stringResource(R.string.yemen_aden_social_posts), fontScale)
        for (post in aden.socialPosts) {
            Text(text = post.name, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale, fontWeight = FontWeight.Bold)
            Text(
                text = "${post.kindFa} • ${post.dateFa}",
                fontFamily = EcoFontFamily,
                fontSize = 12.sp * fontScale,
            )
            Text(text = post.bodyFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            if (post.url.isNotBlank()) {
                Text(text = post.url, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    if (aden.amplifiers.isNotEmpty()) {
        SectionTitle(stringResource(R.string.yemen_aden_amplifiers), fontScale)
        for (amp in aden.amplifiers) {
            Text(text = amp.name, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale, fontWeight = FontWeight.Bold)
            if (amp.dateFa.isNotBlank()) {
                Text(text = amp.dateFa, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
            }
            Text(text = amp.bodyFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            if (amp.url.isNotBlank()) {
                Text(text = amp.url, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    // The Aden investigation's own source list (28) — the
    // Timeline tab shows only the document-level list (25).
    if (aden.sources.isNotEmpty()) {
        SectionTitle(stringResource(R.string.yemen_sources), fontScale)
        for (source in aden.sources) {
            Text(text = source.label, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
            if (source.url.isNotBlank()) {
                Text(text = source.url, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (aden.openQuestions.isNotEmpty()) {
        SectionTitle(stringResource(R.string.yemen_open_questions), fontScale)
        for (q in aden.openQuestions) {
            Text(text = q, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun DossiersTab(doc: YemenDoc?, fontScale: Float) {
    if (!doc?.forces.isNullOrEmpty()) {
        SectionTitle(stringResource(R.string.yemen_forces), fontScale)
        for (force in doc!!.forces) {
            Text(text = force.name, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale, fontWeight = FontWeight.Bold)
            if (force.status.isNotBlank()) {
                Text(text = force.status, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            }
            Text(text = force.body, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    if (!doc?.militaryFeatures.isNullOrEmpty()) {
        SectionTitle(stringResource(R.string.yemen_military_features), fontScale)
        for (feature in doc!!.militaryFeatures) {
            Text(text = feature.titleFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale, fontWeight = FontWeight.Bold)
            if (feature.kindFa.isNotBlank()) {
                Text(text = feature.kindFa, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
            }
            Text(text = feature.bodyFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    if (!doc?.mapReading.isNullOrEmpty()) {
        SectionTitle(stringResource(R.string.yemen_map_reading), fontScale)
        for (item in doc!!.mapReading) {
            if (item.title.isNotBlank()) {
                Text(text = item.title, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale, fontWeight = FontWeight.Bold)
            }
            Text(text = item.body, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    if (!doc?.disagreements.isNullOrEmpty()) {
        SectionTitle(stringResource(R.string.yemen_disagreements), fontScale)
        for (item in doc!!.disagreements) {
            if (item.title.isNotBlank()) {
                Text(text = item.title, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale, fontWeight = FontWeight.Bold)
            }
            Text(text = item.body, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    if (!doc?.openQuestions.isNullOrEmpty()) {
        SectionTitle(stringResource(R.string.yemen_open_questions), fontScale)
        for (q in doc!!.openQuestions) {
            Text(text = q, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun TimelineTab(
    doc: YemenDoc?,
    events: List<com.ecojournal.app.data.YemenEvent>,
    fontScale: Float,
) {
    var filter by remember { mutableStateOf(0) } // 0 all, 1 confirmed, 2 contested, 3 houthi-only
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val chips = listOf(
            R.string.yemen_filter_all to 0,
            R.string.yemen_filter_confirmed to 1,
            R.string.yemen_filter_contested to 2,
            R.string.yemen_filter_houthi_only to 3,
        )
        for ((res, value) in chips) {
            FilterChip(
                selected = filter == value,
                onClick = { filter = value },
                label = {
                    Text(
                        text = stringResource(res),
                        fontFamily = EcoFontFamily,
                        fontSize = 12.sp * fontScale,
                    )
                },
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    // Claims, each under its own layer label — a houthi-only
    // claim is never shown as confirmed.
    val claims = when (filter) {
        1 -> (doc?.claimsConfirmed ?: emptyList()).map { it to R.string.yemen_layer_confirmed }
        2 -> (doc?.claimsContested ?: emptyList()).map { it to R.string.yemen_layer_contested }
        3 -> (doc?.claimsHouthiOnly ?: emptyList()).map { it to R.string.yemen_layer_houthi_only }
        else -> (doc?.claimsConfirmed ?: emptyList()).map { it to R.string.yemen_layer_confirmed } +
            (doc?.claimsContested ?: emptyList()).map { it to R.string.yemen_layer_contested } +
            (doc?.claimsHouthiOnly ?: emptyList()).map { it to R.string.yemen_layer_houthi_only }
    }
    for ((claim, labelRes) in claims) {
        LayeredItem(labelRes = labelRes, title = claim.title, body = claim.body, fontScale = fontScale)
    }
    Spacer(modifier = Modifier.height(12.dp))
    for (event in events) {
        if (event.titleFa.isNotBlank()) {
            Text(text = event.titleFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale, fontWeight = FontWeight.Bold)
        }
        if (event.detailFa.isNotBlank()) {
            Text(text = event.detailFa, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
        }
        Text(
            text = "${event.eventDate} • ${event.sourceName} • " +
                stringResource(R.string.yemen_corroboration, event.corroboration.toString().toPersianDigits()),
            fontFamily = EcoFontFamily,
            fontSize = 12.sp * fontScale,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (!doc?.sources.isNullOrEmpty()) {
        SectionTitle(stringResource(R.string.yemen_sources), fontScale)
        for (source in doc!!.sources) {
            Text(text = source.label, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
            if (source.url.isNotBlank()) {
                Text(text = source.url, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ArchiveTab(
    doc: YemenDoc?,
    repo: EdgeRepository,
    fontScale: Float,
) {
    val scope = rememberCoroutineScope()
    // v5.6 (R4): local snapshots, from Room — create (optional
    // label), view frozen points, delete. The dashboard's own
    // snapshot list (from yemen.json) renders FIRST, above.
    val localSnapshots by repo.yemenDao.localSnapshotsFlow()
        .collectAsState(initial = emptyList())
    var label by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var createdNotice by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val expandedPlaces by repo.yemenDao
        .snapshotPlacesFlow(expandedId ?: -1L)
        .collectAsState(initial = emptyList())

    // Dashboard snapshots (from yemen.json): the v5 snapshot is
    // the first archive entry — label "نسخهٔ ۵ — ارتقای رابط
    // کاربری و آرشیو نسخه‌ها (۱۷ نوامبر ۲۰۲۶)", 90 places,
    // 24 events.
    SectionTitle(stringResource(R.string.yemen_archive_dashboard), fontScale)
    for (snap in doc?.snapshots ?: emptyList()) {
        if (snap.label.isNotBlank()) {
            Text(text = snap.label, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale, fontWeight = FontWeight.Bold)
        }
        Text(
            text = stringResource(
                R.string.yemen_snapshot_line,
                snap.createdAt,
                snap.placesCount.toString().toPersianDigits(),
                snap.eventsCount.toString().toPersianDigits(),
            ),
            fontFamily = EcoFontFamily,
            fontSize = 12.sp * fontScale,
        )
        // factionLabel is @Composable (it reads strings), so it
        // cannot be called inside a joinToString/map lambda. Build
        // the parts with a plain for loop in composable scope.
        val factionParts = mutableListOf<String>()
        for ((faction, count) in snap.counts) {
            factionParts.add("${factionLabel(faction)} ${count.toString().toPersianDigits()}")
        }
        val factionLine = factionParts.joinToString(" • ")
        if (factionLine.isNotBlank()) {
            Text(text = factionLine, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (doc?.snapshots.isNullOrEmpty()) {
        Text(
            text = stringResource(R.string.yemen_empty),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    // Dashboard version history (v1–v5, from the dashboard
    // artifact's own history — nothing invented).
    SectionTitle(stringResource(R.string.yemen_archive_versions), fontScale)
    for (res in listOf(
        R.string.yemen_archive_v1,
        R.string.yemen_archive_v2,
        R.string.yemen_archive_v3,
        R.string.yemen_archive_v4,
        R.string.yemen_archive_v5,
    )) {
        Text(
            text = stringResource(res),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
    Spacer(modifier = Modifier.height(12.dp))
    // Local snapshots: create / view frozen / delete.
    SectionTitle(stringResource(R.string.yemen_archive_local), fontScale)
    TextField(
        value = label,
        onValueChange = { label = it },
        label = {
            Text(
                text = stringResource(R.string.yemen_archive_label_hint),
                fontFamily = EcoFontFamily,
                fontSize = 12.sp * fontScale,
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = {
            if (!creating) {
                creating = true
                createdNotice = false
                scope.launch {
                    try {
                        val id = repo.createYemenSnapshot(label)
                        if (id >= 0) {
                            label = ""
                            createdNotice = true
                        }
                    } finally {
                        creating = false
                    }
                }
            }
        },
        enabled = !creating,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(
            text = stringResource(R.string.yemen_archive_create),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
    }
    if (createdNotice) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.yemen_archive_created),
            fontFamily = EcoFontFamily,
            fontSize = 12.sp * fontScale,
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    if (localSnapshots.isEmpty()) {
        Text(
            text = stringResource(R.string.yemen_archive_empty),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
    }
    for (snap in localSnapshots) {
        val title = snap.label.ifBlank {
            Jalali.formatShamsiDateTime(snap.createdAtEpoch)
        }
        Text(
            text = title,
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(
                R.string.yemen_snapshot_line,
                Jalali.formatShamsiDateTime(snap.createdAtEpoch),
                snap.placesCount.toString().toPersianDigits(),
                snap.eventsCount.toString().toPersianDigits(),
            ),
            fontFamily = EcoFontFamily,
            fontSize = 12.sp * fontScale,
        )
        val localFactionParts = mutableListOf<String>()
        for ((faction, count) in snapshotCounts(snap.countsJson)) {
            localFactionParts.add("${factionLabel(faction)} ${count.toString().toPersianDigits()}")
        }
        val localFactionLine = localFactionParts.joinToString(" • ")
        if (localFactionLine.isNotBlank()) {
            Text(text = localFactionLine, fontFamily = EcoFontFamily, fontSize = 12.sp * fontScale)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    expandedId = if (expandedId == snap.id) null else snap.id
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(
                        if (expandedId == snap.id) R.string.yemen_archive_hide
                        else R.string.yemen_archive_view,
                    ),
                    fontFamily = EcoFontFamily,
                    fontSize = 13.sp * fontScale,
                )
            }
            OutlinedButton(
                onClick = {
                    if (expandedId == snap.id) expandedId = null
                    scope.launch { repo.deleteYemenSnapshot(snap.id) }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.yemen_archive_delete),
                    fontFamily = EcoFontFamily,
                    fontSize = 13.sp * fontScale,
                )
            }
        }
        // Frozen view: the places as they were when the
        // snapshot was created — never re-read from live Room.
        if (expandedId == snap.id) {
            Spacer(modifier = Modifier.height(8.dp))
            for (place in expandedPlaces) {
                Text(
                    text = "${place.nameFa} — ${factionLabel(place.faction)}",
                    fontFamily = EcoFontFamily,
                    fontSize = 13.sp * fontScale,
                )
                if (place.governorateFa.isNotBlank()) {
                    Text(
                        text = place.governorateFa,
                        fontFamily = EcoFontFamily,
                        fontSize = 12.sp * fontScale,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

/** Parse a local snapshot's countsJson ([{"faction","count"}])
 * back to pairs. Malformed content degrades to empty, never a
 * crash — the counts line simply does not render. */
private fun snapshotCounts(json: String): List<Pair<String, Int>> {
    val out = mutableListOf<Pair<String, Int>>()
    try {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(o.optString("faction", "") to o.optInt("count", 0))
        }
    } catch (_: Exception) {
    }
    return out
}

@Composable
private fun SectionTitle(title: String, fontScale: Float) {
    Text(
        text = title,
        fontFamily = EcoFontFamily,
        fontSize = 16.sp * fontScale,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun LayeredItem(labelRes: Int, title: String, body: String, fontScale: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(labelRes),
            fontFamily = EcoFontFamily,
            fontSize = 11.sp * fontScale,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (title.isNotBlank()) {
            Text(
                text = title,
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    if (body.isNotBlank()) {
        Text(text = body, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
    }
    Spacer(modifier = Modifier.height(8.dp))
}
