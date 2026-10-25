package com.ecojournal.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import com.ecojournal.app.R
import com.ecojournal.app.data.AlertMetric
import com.ecojournal.app.data.ComputedFormula
import com.ecojournal.app.data.Jalali
import com.ecojournal.app.data.MarketFormula
import com.ecojournal.app.data.MarketHistory
import com.ecojournal.app.data.MarketRange
import com.ecojournal.app.data.MarketSeries
import com.ecojournal.app.data.MarketSnapshot
import com.ecojournal.app.data.MarketThresholds
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.util.formatPersianDouble
import com.ecojournal.app.util.formatPersianInt
import com.ecojournal.app.util.toPersianDigits

/**
 * Market board (read-only display), M4.
 *
 * All 15 snapshot fields are shown (snapshot id, timestamp and the
 * 13 prices), with "—" for a null price — a missing field is never
 * silently omitted. "Latest" is the Room row with max tsEpoch
 * (the DAO orders by tsEpoch, not by array position), and change
 * is computed against the previous distinct timestamp, only where
 * both snapshots carry the field. Formulas are COMPUTED here from
 * their shipped definitions (MarketFormulas.evaluate); a value
 * that cannot be computed shows "—" with its inputs listed, so the
 * reason is visible. Staleness uses a 24h threshold: the bundled
 * seed's newest snapshot is dated 2026-10-24, so offline first
 * runs honestly say stale until a sync lands (M5 wires the edge).
 * States are distinct: still seeding (loading), seed done with no
 * snapshot (error + retry, which re-runs the resumable seed), and
 * ready. Nothing is invented: every number is a bundled/cached
 * snapshot value or a formula evaluated from one.
 */
private data class PriceField(
    val label: String,
    val value: Double?,
    val previous: Double?,
    /** Decimals for the price itself; change uses the same decimals. */
    val decimals: Int,
)

private fun ageText(epoch: Long?): String? {
    if (epoch == null) return null
    val ageMs = System.currentTimeMillis() - epoch
    if (ageMs < 0L) return "تازه"
    if (ageMs < 60L * 60L * 1000L) return "کمتر از یک ساعت پیش"
    val hours = ageMs / (60L * 60L * 1000L)
    if (hours < 24L) return "${formatPersianInt(hours)} ساعت پیش"
    val days = hours / 24L
    return "${formatPersianInt(days)} روز پیش"
}

private fun formatPrice(value: Double, decimals: Int): String {
    return if (decimals == 0) {
        formatPersianInt(value.toLong())
    } else {
        formatPersianDouble(value, decimals)
    }
}

/** Change vs the previous snapshot, or null where not computable. */
private fun changeText(now: Double, prev: Double?, decimals: Int): String? {
    if (prev == null || prev == 0.0) return null
    val delta = now - prev
    // M8: a zero delta returns null; the caller renders the
    // market_no_change string resource instead, so no user-facing
    // sentence stays hard-coded here.
    if (delta == 0.0) return null
    val arrow = if (delta > 0) "▲" else "▼"
    val sign = if (delta > 0) "+" else "−"
    val pct = kotlin.math.abs(delta / prev * 100.0)
    return "$arrow $sign${formatPrice(kotlin.math.abs(delta), decimals)} " +
        "(${formatPersianDouble(pct, 2)}٪)"
}

private fun formulaValueText(formula: ComputedFormula): String {
    val value = formula.value ?: return "—"
    return when (formula.unit) {
        "٪" -> "${formatPersianDouble(value, 2)}٪"
        "تومان" -> "${formatPersianInt(value.toLong())} تومان"
        else -> formatPersianDouble(value, 2)
    }
}

/**
 * Input display: large whole values (toman amounts) as integers
 * with separators; smaller/fractional values with 2 decimals.
 * This is display formatting only; the evaluated value itself
 * always uses full double precision.
 */
private fun inputValueText(value: Double): String {
    return if (kotlin.math.abs(value) >= 10_000.0 || value == kotlin.math.floor(value)) {
        formatPersianInt(value.toLong())
    } else {
        formatPersianDouble(value, 2)
    }
}

/**
 * Phase 4 additions (below the M4 board, never replacing it):
 * alert banners (current STATE from the latest snapshot +
 * edge thresholds; null threshold = OFF, never a fake "all
 * clear") and history (one series at a time, gap-broken chart,
 * tap/prev/next snapshot card). The board above is untouched.
 * New params default so older call sites keep compiling; the
 * MainActivity market route passes the full set.
 */
@Composable
fun MarketScreen(
    latest: MarketSnapshot?,
    previous: MarketSnapshot?,
    formulas: List<ComputedFormula>,
    snapshotCount: Int,
    seedDone: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
    historySnapshots: List<MarketSnapshot> = emptyList(),
    formulaDefs: List<MarketFormula> = emptyList(),
    thresholds: MarketThresholds? = null,
    dismissedBubbleSnapshotId: String? = null,
    dismissedSpreadSnapshotId: String? = null,
    onDismissBanner: (AlertMetric) -> Unit = {},
    onRefreshMarket: () -> Unit = {},
) {
    // Phase 4 history state, hoisted ABOVE the early returns below:
    // remember calls must not sit behind a conditional return.
    var historyRange by remember { mutableStateOf(MarketRange.ALL) }
    var historySeries by remember { mutableStateOf(MarketSeries.ALL_SERIES.first { it.key == "xau_usd" }) }
    var seriesMenuOpen by remember { mutableStateOf(false) }
    var selectedSnapshotId by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.market_title),
            fontFamily = EcoFontFamily,
            fontSize = 20.sp * fontScale,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (!seedDone) {
            Text(
                text = stringResource(R.string.market_loading),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
            return@Column
        }
        if (latest == null) {
            Text(
                text = stringResource(R.string.market_unavailable),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.market_unavailable_detail),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.market_retry),
                    fontFamily = EcoFontFamily,
                    fontSize = 16.sp * fontScale,
                )
            }
            return@Column
        }
        val stale = latest.tsEpoch?.let {
            System.currentTimeMillis() - it > 24L * 60L * 60L * 1000L
        } ?: true
        Text(
            text = stringResource(R.string.market_latest_snapshot, latest.tsTehran),
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
        )
        ageText(latest.tsEpoch)?.let {
            Text(
                text = stringResource(R.string.market_last_update, it),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (latest.snapshotId.isNotBlank()) {
            Text(
                text = stringResource(R.string.market_snapshot_id, latest.snapshotId),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (stale) {
            Text(
                text = stringResource(R.string.market_stale),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
        }
        previous?.let {
            Text(
                text = stringResource(R.string.market_compare_previous, it.tsTehran),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        val fields = listOf(
            PriceField("برنت (دلار)", latest.brent, previous?.brent, 2),
            PriceField("انس طلا (دلار)", latest.xauUsd, previous?.xauUsd, 2),
            PriceField("شاخص دلار", latest.dxy, previous?.dxy, 2),
            PriceField("تتر (دلار)", latest.usdtUsd, previous?.usdtUsd, 5),
            PriceField("دلار آزاد (تومان)", latest.usdIrtFree, previous?.usdIrtFree, 0),
            PriceField("تتر (تومان)", latest.usdtIrt, previous?.usdtIrt, 0),
            PriceField("طلای ۱۸ عیار هر گرم (تومان)", latest.gold18kGram, previous?.gold18kGram, 0),
            PriceField("سکهٔ امامی (تومان)", latest.sekkeEmami, previous?.sekkeEmami, 0),
            PriceField("سکهٔ بهار آزادی (تومان)", latest.sekkeBahar, previous?.sekkeBahar, 0),
            PriceField("نیم‌سکه (تومان)", latest.nimSekke, previous?.nimSekke, 0),
            PriceField("ربع‌سکه (تومان)", latest.robSekke, previous?.robSekke, 0),
            PriceField("شاخص ترس (VIX)", latest.vix, previous?.vix, 2),
            PriceField("شاخص S&P 500", latest.sp500, previous?.sp500, 2),
        )
        for (field in fields) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${field.label}: " +
                    if (field.value == null) "—" else formatPrice(field.value, field.decimals),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
            if (field.value != null) {
                if (field.previous != null && field.value == field.previous) {
                    Text(
                        text = stringResource(R.string.market_no_change),
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    changeText(field.value, field.previous, field.decimals)?.let { change ->
                        Text(
                            text = stringResource(R.string.market_change_vs_previous, change),
                            fontFamily = EcoFontFamily,
                            fontSize = 14.sp * fontScale,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.market_snapshot_count, formatPersianInt(snapshotCount)),
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.market_formulas_title),
            fontFamily = EcoFontFamily,
            fontSize = 18.sp * fontScale,
            fontWeight = FontWeight.Bold,
        )
        if (formulas.isEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.market_formulas_empty),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
        }
        for (f in formulas) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${f.name} (v${f.version})",
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.market_formula_value, formulaValueText(f)),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
            if (f.value == null) {
                Text(
                    text = stringResource(R.string.market_formula_uncomputable),
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            f.descriptionFa?.let {
                Text(
                    text = it,
                    fontFamily = EcoFontFamily,
                    fontSize = 16.sp * fontScale,
                )
            }
            if (f.inputs.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.market_inputs),
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for ((label, inputValue) in f.inputs) {
                    Text(
                        text = "$label: " +
                            if (inputValue == null) "—" else inputValueText(inputValue),
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // ---- Phase 4: alert banners (current state, not events) ----
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.market_alert_banner_title),
            fontFamily = EcoFontFamily,
            fontSize = 18.sp * fontScale,
            fontWeight = FontWeight.Bold,
        )
        val bubbleValue = formulas.firstOrNull { it.name == "bubble_pct" }?.value
        val spreadValue = formulas.firstOrNull { it.name == "spread_pct" }?.value
        // Signed comparison, as in the dispatcher: a negative
        // spread never banners against a positive threshold.
        val bubbleOn = bubbleValue != null && thresholds?.bubblePct != null &&
            bubbleValue >= thresholds.bubblePct &&
            dismissedBubbleSnapshotId != latest.snapshotId
        val spreadOn = spreadValue != null && thresholds?.spreadPct != null &&
            spreadValue >= thresholds.spreadPct &&
            dismissedSpreadSnapshotId != latest.snapshotId
        if (thresholds == null || !thresholds.anySet) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.market_alert_off),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
            Text(
                text = stringResource(R.string.market_alert_off_detail),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            if (bubbleOn) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.market_alert_banner_bubble,
                        formatPersianDouble(bubbleValue!!, 1),
                        formatPersianDouble(thresholds.bubblePct!!, 1),
                    ),
                    fontFamily = EcoFontFamily,
                    fontSize = 16.sp * fontScale,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.market_alert_banner_snapshot, latest.tsTehran),
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onDismissBanner(AlertMetric.BUBBLE) }) {
                    Text(
                        text = stringResource(R.string.market_alert_banner_dismiss),
                        fontFamily = EcoFontFamily,
                    )
                }
            }
            if (spreadOn) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.market_alert_banner_spread,
                        formatPersianDouble(spreadValue!!, 1),
                        formatPersianDouble(thresholds.spreadPct!!, 1),
                    ),
                    fontFamily = EcoFontFamily,
                    fontSize = 16.sp * fontScale,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.market_alert_banner_snapshot, latest.tsTehran),
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onDismissBanner(AlertMetric.SPREAD) }) {
                    Text(
                        text = stringResource(R.string.market_alert_banner_dismiss),
                        fontFamily = EcoFontFamily,
                    )
                }
            }
            if (!bubbleOn && !spreadOn) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.market_alert_off_detail),
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.market_alert_latency_note),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // ---- Phase 4: history (below the board, never replacing it) ----
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.market_history_title),
            fontFamily = EcoFontFamily,
            fontSize = 18.sp * fontScale,
            fontWeight = FontWeight.Bold,
        )
        val nowMs = System.currentTimeMillis()
        val history = MarketHistory.seriesForRange(
            historySnapshots, historySeries, formulaDefs, historyRange, nowMs,
        )
        val orderedHistory = historySnapshots
            .filter { it.tsEpoch != null }.sortedBy { it.tsEpoch }
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            for (range in MarketRange.entries) {
                val label = when (range) {
                    MarketRange.R24H -> stringResource(R.string.market_history_range_24h)
                    MarketRange.D7 -> stringResource(R.string.market_history_range_7d)
                    MarketRange.D30 -> stringResource(R.string.market_history_range_30d)
                    MarketRange.ALL -> stringResource(R.string.market_history_range_all)
                }
                if (range == historyRange) {
                    Button(onClick = { historyRange = range }) {
                        Text(text = label, fontFamily = EcoFontFamily)
                    }
                } else {
                    OutlinedButton(onClick = { historyRange = range }) {
                        Text(text = label, fontFamily = EcoFontFamily)
                    }
                }
                Spacer(modifier = Modifier.padding(horizontal = 2.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { seriesMenuOpen = true }) {
            Text(
                text = stringResource(R.string.market_history_series_label, historySeries.labelFa),
                fontFamily = EcoFontFamily,
            )
        }
        DropdownMenu(
            expanded = seriesMenuOpen,
            onDismissRequest = { seriesMenuOpen = false },
        ) {
            for (series in MarketSeries.ALL_SERIES) {
                DropdownMenuItem(
                    text = { Text(text = series.labelFa, fontFamily = EcoFontFamily) },
                    onClick = {
                        historySeries = series
                        seriesMenuOpen = false
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (history.snapshotsInRange.isEmpty()) {
            Text(
                text = stringResource(R.string.market_history_empty),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
            if (orderedHistory.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.market_history_empty_coverage,
                        Jalali.formatShamsiDateTime(orderedHistory.first().tsEpoch!!),
                        Jalali.formatShamsiDateTime(orderedHistory.last().tsEpoch!!),
                    ),
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (historyRange != MarketRange.ALL) {
                Button(onClick = { historyRange = MarketRange.ALL }) {
                    Text(
                        text = stringResource(R.string.market_history_range_all),
                        fontFamily = EcoFontFamily,
                    )
                }
            }
        } else {
            Text(
                text = stringResource(
                    R.string.market_history_coverage,
                    history.points.size.toString().toPersianDigits(),
                    Jalali.formatShamsiDateTime(history.firstSnapshot!!.tsEpoch!!),
                    Jalali.formatShamsiDateTime(history.lastSnapshot!!.tsEpoch!!),
                ),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val includesLatest = history.lastSnapshot?.snapshotId == latest.snapshotId
            if (includesLatest && stale) {
                Text(
                    text = stringResource(R.string.market_stale),
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                )
            }
            // Gap-broken line chart: segments from MarketHistory;
            // a tap selects the nearest point by x position.
            val segments = MarketHistory.segments(history)
            val minEpoch = history.entries.first().tsEpoch
            val maxEpoch = history.entries.last().tsEpoch
            val values = history.points.map { it.value!! }
            val minV = values.minOrNull() ?: 0.0
            val maxV = values.maxOrNull() ?: 0.0
            val spanV = if (maxV > minV) maxV - minV else 1.0
            val lineColor = MaterialTheme.colorScheme.primary
            val pointColor = MaterialTheme.colorScheme.onSurface
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .pointerInput(history) {
                        detectTapGestures { offset ->
                            val span = (maxEpoch - minEpoch).coerceAtLeast(1L)
                            val frac = (offset.x / size.width).coerceIn(0f, 1f)
                            val target = minEpoch + (span * frac).toLong()
                            val nearest = history.points.minByOrNull {
                                kotlin.math.abs(it.tsEpoch - target)
                            }
                            if (nearest != null) selectedSnapshotId = nearest.snapshot.snapshotId
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                val xFor: (Long) -> Float = { epoch ->
                    if (maxEpoch == minEpoch) w / 2f
                    else ((epoch - minEpoch).toFloat() / (maxEpoch - minEpoch).toFloat()) * w
                }
                val yFor: (Double) -> Float = { v ->
                    h - ((v - minV) / spanV).toFloat() * h
                }
                for (segment in segments) {
                    for (i in 0 until segment.size - 1) {
                        drawLine(
                            color = lineColor,
                            start = Offset(xFor(segment[i].tsEpoch), yFor(segment[i].value!!)),
                            end = Offset(xFor(segment[i + 1].tsEpoch), yFor(segment[i + 1].value!!)),
                            strokeWidth = 4f,
                            cap = StrokeCap.Round,
                        )
                    }
                    for (point in segment) {
                        drawCircle(
                            color = pointColor,
                            radius = 8f,
                            center = Offset(xFor(point.tsEpoch), yFor(point.value!!)),
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.market_history_gap_note),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Selected snapshot card: all 15 fields + formulas +
            // change vs previous history snapshot where computable.
            val selected = history.points.firstOrNull {
                it.snapshot.snapshotId == selectedSnapshotId
            }?.snapshot ?: history.points.lastOrNull()?.snapshot
            if (selected != null) {
                val selectedPrev = MarketHistory.previousOf(selected, orderedHistory)
                val selectedFormulas = if (formulaDefs.isNotEmpty()) {
                    com.ecojournal.app.data.MarketFormulas.evaluate(selected, formulaDefs)
                } else {
                    emptyList()
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.market_history_point_title),
                    fontFamily = EcoFontFamily,
                    fontSize = 16.sp * fontScale,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${Jalali.formatShamsiDateTime(selected.tsEpoch ?: 0L)} • ${selected.snapshotId}",
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.market_history_point_value,
                        MarketSeries.seriesValue(selected, historySeries.key, formulaDefs)
                            ?.let { formatPersianDouble(it, historySeries.decimals) } ?: "—",
                    ),
                    fontFamily = EcoFontFamily,
                    fontSize = 16.sp * fontScale,
                )
                val detailFields = listOf(
                    PriceField("برنت (دلار)", selected.brent, selectedPrev?.brent, 2),
                    PriceField("انس طلا (دلار)", selected.xauUsd, selectedPrev?.xauUsd, 2),
                    PriceField("شاخص دلار", selected.dxy, selectedPrev?.dxy, 2),
                    PriceField("تتر (دلار)", selected.usdtUsd, selectedPrev?.usdtUsd, 5),
                    PriceField("دلار آزاد (تومان)", selected.usdIrtFree, selectedPrev?.usdIrtFree, 0),
                    PriceField("تتر (تومان)", selected.usdtIrt, selectedPrev?.usdtIrt, 0),
                    PriceField("طلای ۱۸ عیار هر گرم (تومان)", selected.gold18kGram, selectedPrev?.gold18kGram, 0),
                    PriceField("سکهٔ امامی (تومان)", selected.sekkeEmami, selectedPrev?.sekkeEmami, 0),
                    PriceField("سکهٔ بهار آزادی (تومان)", selected.sekkeBahar, selectedPrev?.sekkeBahar, 0),
                    PriceField("نیم‌سکه (تومان)", selected.nimSekke, selectedPrev?.nimSekke, 0),
                    PriceField("ربع‌سکه (تومان)", selected.robSekke, selectedPrev?.robSekke, 0),
                    PriceField("شاخص ترس (VIX)", selected.vix, selectedPrev?.vix, 2),
                    PriceField("شاخص S&P 500", selected.sp500, selectedPrev?.sp500, 2),
                )
                for (field in detailFields) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val changeCell = if (field.value == null) {
                        "—"
                    } else if (field.previous == null) {
                        "—"
                    } else {
                        changeText(field.value, field.previous, field.decimals)
                            ?: if (field.value == field.previous) {
                                stringResource(R.string.market_no_change)
                            } else {
                                "—"
                            }
                    }
                    Text(
                        text = "${field.label}: " +
                            if (field.value == null) "—" else formatPrice(field.value, field.decimals) +
                            " ($changeCell)",
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                    )
                }
                for (f in selectedFormulas) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${f.name}: ${formulaValueText(f)}",
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                    )
                }
                Row {
                    val idx = history.points.indexOfFirst { it.snapshot.snapshotId == selected.snapshotId }
                    if (idx > 0) {
                        TextButton(onClick = {
                            selectedSnapshotId = history.points[idx - 1].snapshot.snapshotId
                        }) {
                            Text(text = "◀ قبلی", fontFamily = EcoFontFamily)
                        }
                    }
                    if (idx >= 0 && idx < history.points.size - 1) {
                        TextButton(onClick = {
                            selectedSnapshotId = history.points[idx + 1].snapshot.snapshotId
                        }) {
                            Text(text = "بعدی ▶", fontFamily = EcoFontFamily)
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onRefreshMarket,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                text = stringResource(R.string.market_history_refresh),
                fontFamily = EcoFontFamily,
            )
        }
    }
}
