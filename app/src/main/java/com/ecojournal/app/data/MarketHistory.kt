package com.ecojournal.app.data

/**
 * Phase 4 — Market history, charts (as point series with honest
 * gaps), and bubble/spread alert evaluation. Pure Kotlin: no
 * Android dependency, so the rules here are mirror-testable in
 * the lab fixture verifier.
 *
 * Honesty rules (phases4-5 spec §4.2/§4.3, binding):
 * - History reuses market_snapshots; no second snapshot table.
 * - Ordering is ALWAYS tsEpoch, never array position and never
 *   tsTehran string order. Rows with tsEpoch == null are stored
 *   but EXCLUDED from latest/previous/charts (they cannot be
 *   ordered honestly). Callers must filter them out; the DAO
 *   history queries below do so with `tsEpoch IS NOT NULL`.
 * - A null price/derived value is a GAP, never 0 and never carried
 *   forward. Charts never interpolate, never smooth, and never draw
 *   a line across a gap longer than the range's nominal step.
 * - Derived series use the SAME MarketFormulas engine as the M4
 *   board — never a second formula implementation. A missing input
 *   yields null, rendered as "—" in tables and as a gap in charts.
 * - Alert comparison is SIGNED (spec §4.6, verbatim from the
 *   channel rule): a negative spread never fires a positive
 *   threshold. No abs() anywhere in this file.
 * - Thresholds are null = OFF. There are no default threshold
 *   values anywhere in this file or in the app: the /setalert help
 *   examples (bubble 15, spread 2) are EXAMPLES, not values, and
 *   must never appear as defaults (spec R7/U1). A null threshold
 *   means no banner, no notification, no event row.
 */

/** UI ranges (spec §4.2, SPEC DECISION — UI ranges, not recovered facts). */
enum class MarketRange(val rangeMs: Long?, val nominalStepMs: Long) {
    /** Last 24h; nominal step 1h (intraday density when it resumes). */
    R24H(24L * 60L * 60L * 1000L, 60L * 60L * 1000L),
    /** Last 7 days; nominal step 1 day (frozen history is ~daily). */
    D7(7L * 24L * 60L * 60L * 1000L, 24L * 60L * 60L * 1000L),
    /** Last 30 days; nominal step 1 day. */
    D30(30L * 24L * 60L * 60L * 1000L, 24L * 60L * 60L * 1000L),
    /** Every stored snapshot with non-null tsEpoch; step 7 days. */
    ALL(null, 7L * 24L * 60L * 60L * 1000L),
}

/** One selectable series: a price field or a derived formula. */
data class SeriesDef(
    val key: String,
    val labelFa: String,
    val decimals: Int,
    val isDerived: Boolean,
)

object MarketSeries {
    val PRICE_SERIES: List<SeriesDef> = listOf(
        SeriesDef("brent", "برنت (دلار)", 2, false),
        SeriesDef("xau_usd", "انس طلا (دلار)", 2, false),
        SeriesDef("dxy", "شاخص دلار", 2, false),
        SeriesDef("usdt_usd", "تتر (دلار)", 5, false),
        SeriesDef("usd_irt_free", "دلار آزاد (تومان)", 0, false),
        SeriesDef("usdt_irt", "تتر (تومان)", 0, false),
        SeriesDef("gold_18k_gram", "طلای ۱۸ عیار هر گرم (تومان)", 0, false),
        SeriesDef("sekke_emami", "سکهٔ امامی (تومان)", 0, false),
        SeriesDef("sekke_bahar", "سکهٔ بهار آزادی (تومان)", 0, false),
        SeriesDef("nim_sekke", "نیم‌سکه (تومان)", 0, false),
        SeriesDef("rob_sekke", "ربع‌سکه (تومان)", 0, false),
        SeriesDef("vix", "شاخص ترس (VIX)", 2, false),
        SeriesDef("sp500", "شاخص S&P 500", 2, false),
    )

    val DERIVED_SERIES: List<SeriesDef> = listOf(
        SeriesDef("implied_usd", "دلار ضمنی (تومان)", 0, true),
        SeriesDef("intrinsic_emami", "ارزش ذاتی سکهٔ امامی (تومان)", 0, true),
        SeriesDef("bubble_pct", "حباب سکهٔ امامی (٪)", 2, true),
        SeriesDef("spread_pct", "اسپرد تتر/دلار (٪)", 2, true),
    )

    val ALL_SERIES: List<SeriesDef> = PRICE_SERIES + DERIVED_SERIES

    fun priceValue(snapshot: MarketSnapshot, key: String): Double? = when (key) {
        "brent" -> snapshot.brent
        "xau_usd" -> snapshot.xauUsd
        "dxy" -> snapshot.dxy
        "usdt_usd" -> snapshot.usdtUsd
        "usd_irt_free" -> snapshot.usdIrtFree
        "usdt_irt" -> snapshot.usdtIrt
        "gold_18k_gram" -> snapshot.gold18kGram
        "sekke_emami" -> snapshot.sekkeEmami
        "sekke_bahar" -> snapshot.sekkeBahar
        "nim_sekke" -> snapshot.nimSekke
        "rob_sekke" -> snapshot.robSekke
        "vix" -> snapshot.vix
        "sp500" -> snapshot.sp500
        else -> null
    }

    /** Value of [key] on [snapshot]; derived keys go through MarketFormulas. */
    fun seriesValue(
        snapshot: MarketSnapshot,
        key: String,
        formulas: List<MarketFormula>,
    ): Double? {
        val price = priceValue(snapshot, key)
        if (price != null || PRICE_SERIES.any { it.key == key }) return price
        if (formulas.isEmpty()) return null
        return MarketFormulas.evaluate(snapshot, formulas)
            .firstOrNull { it.name == key }?.value
    }
}

/** One history entry: a stored snapshot in range, value nullable (= gap). */
data class HistoryPoint(
    val snapshot: MarketSnapshot,
    val tsEpoch: Long,
    val value: Double?,
)

/** Coverage + points for one series in one range. */
data class HistorySeries(
    val range: MarketRange,
    val series: SeriesDef,
    /** Snapshots in range with non-null tsEpoch (the honest denominator). */
    val snapshotsInRange: List<MarketSnapshot>,
    /** Plottable points (non-null value), in tsEpoch order. */
    val points: List<HistoryPoint>,
    /** All in-range entries in tsEpoch order, nulls kept as gaps. */
    val entries: List<HistoryPoint>,
) {
    val firstSnapshot: MarketSnapshot? get() = snapshotsInRange.firstOrNull()
    val lastSnapshot: MarketSnapshot? get() = snapshotsInRange.lastOrNull()
}

object MarketHistory {
    fun seriesForRange(
        snapshots: List<MarketSnapshot>,
        series: SeriesDef,
        formulas: List<MarketFormula>,
        range: MarketRange,
        nowMs: Long,
    ): HistorySeries {
        val ordered = snapshots
            .filter { it.tsEpoch != null }
            .sortedBy { it.tsEpoch }
        val inRange = if (range.rangeMs == null) {
            ordered
        } else {
            val from = nowMs - range.rangeMs
            ordered.filter { it.tsEpoch!! in from..nowMs }
        }
        val entries = inRange.map { snap ->
            HistoryPoint(
                snapshot = snap,
                tsEpoch = snap.tsEpoch!!,
                value = MarketSeries.seriesValue(snap, series.key, formulas),
            )
        }
        return HistorySeries(
            range = range,
            series = series,
            snapshotsInRange = inRange,
            points = entries.filter { it.value != null },
            entries = entries,
        )
    }

    /**
     * Drawable segments: consecutive plottable points are connected
     * ONLY when the time gap between them is at most the range's
     * nominal step. A null value breaks the segment; a longer gap
     * breaks it too. No interpolation, no carry-forward, no smoothing.
     */
    fun segments(history: HistorySeries): List<List<HistoryPoint>> {
        val out = mutableListOf<List<HistoryPoint>>()
        var current = mutableListOf<HistoryPoint>()
        var prev: HistoryPoint? = null
        for (entry in history.entries) {
            val value = entry.value
            if (value == null) {
                if (current.isNotEmpty()) {
                    out.add(current)
                    current = mutableListOf()
                }
                prev = null
                continue
            }
            if (prev != null && entry.tsEpoch - prev.tsEpoch > history.range.nominalStepMs) {
                out.add(current)
                current = mutableListOf()
            }
            current.add(entry)
            prev = entry
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    /** Previous snapshot by tsEpoch strictly below [snapshot] (null → null). */
    fun previousOf(
        snapshot: MarketSnapshot,
        orderedSnapshots: List<MarketSnapshot>,
    ): MarketSnapshot? {
        val epoch = snapshot.tsEpoch ?: return null
        return orderedSnapshots
            .filter { it.tsEpoch != null && it.tsEpoch < epoch }
            .maxByOrNull { it.tsEpoch!! }
    }
}

// ---- Bubble / spread alerts (spec §4.6) ----

/** Admin thresholds from the edge market_alerts object; null = OFF. */
data class MarketThresholds(
    val bubblePct: Double?,
    val spreadPct: Double?,
) {
    val bubbleSet: Boolean get() = bubblePct != null
    val spreadSet: Boolean get() = spreadPct != null
    val anySet: Boolean get() = bubbleSet || spreadSet
}

enum class AlertMetric { BUBBLE, SPREAD }

/** Evaluation of one metric on the latest snapshot. */
data class AlertEvaluation(
    val metric: AlertMetric,
    val value: Double?,
    val threshold: Double?,
    val bannerOn: Boolean,
    /** Fresh crossing (prev below, latest at/above) — the only notify-able shape. */
    val isFreshCrossing: Boolean,
    /** Cooldown satisfied for a notification at [nowMs] (false when OFF/null). */
    val cooldownOk: Boolean,
    val shouldNotify: Boolean,
) {
    val eventId: String? get() = null // set by the caller (needs snapshotId)
}

object MarketAlerts {
    const val COOLDOWN_MS: Long = 12L * 60L * 60L * 1000L

    fun eventId(metric: AlertMetric, snapshotId: String): String =
        (if (metric == AlertMetric.BUBBLE) "bubble" else "spread") + "|" + snapshotId

    fun evaluateMetric(
        metric: AlertMetric,
        latest: MarketSnapshot?,
        previous: MarketSnapshot?,
        formulas: List<MarketFormula>,
        threshold: Double?,
        lastNotifiedEpochMs: Long?,
        nowMs: Long,
    ): AlertEvaluation {
        if (latest == null || threshold == null) {
            return AlertEvaluation(metric, null, threshold, false, false, false, false)
        }
        val key = if (metric == AlertMetric.BUBBLE) "bubble_pct" else "spread_pct"
        val value = MarketSeries.seriesValue(latest, key, formulas)
        if (value == null) {
            return AlertEvaluation(metric, null, threshold, false, false, false, false)
        }
        // SIGNED comparison, verbatim from the channel rule (R5):
        // value >= threshold. A negative spread never fires.
        val bannerOn = value >= threshold
        // Previous null = treated as below (first computable value may fire).
        val prevValue = previous?.let { MarketSeries.seriesValue(it, key, formulas) }
        val isFreshCrossing = bannerOn && (prevValue == null || prevValue < threshold)
        val cooldownOk = lastNotifiedEpochMs == null ||
            nowMs - lastNotifiedEpochMs >= COOLDOWN_MS
        return AlertEvaluation(
            metric = metric,
            value = value,
            threshold = threshold,
            bannerOn = bannerOn,
            isFreshCrossing = isFreshCrossing,
            cooldownOk = cooldownOk,
            // A value sitting above threshold WITHOUT a fresh crossing
            // never notifies, even after cooldown expiry (spec §4.6).
            shouldNotify = isFreshCrossing && cooldownOk,
        )
    }

    fun evaluateAll(
        latest: MarketSnapshot?,
        previous: MarketSnapshot?,
        formulas: List<MarketFormula>,
        thresholds: MarketThresholds,
        lastNotifiedBubbleMs: Long?,
        lastNotifiedSpreadMs: Long?,
        nowMs: Long,
    ): Map<AlertMetric, AlertEvaluation> = mapOf(
        AlertMetric.BUBBLE to evaluateMetric(
            AlertMetric.BUBBLE, latest, previous, formulas,
            thresholds.bubblePct, lastNotifiedBubbleMs, nowMs,
        ),
        AlertMetric.SPREAD to evaluateMetric(
            AlertMetric.SPREAD, latest, previous, formulas,
            thresholds.spreadPct, lastNotifiedSpreadMs, nowMs,
        ),
    )
}
