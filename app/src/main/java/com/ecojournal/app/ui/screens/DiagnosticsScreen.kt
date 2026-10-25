package com.ecojournal.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import com.ecojournal.app.BuildConfig
import com.ecojournal.app.R
import com.ecojournal.app.data.ChainRecord
import com.ecojournal.app.data.EdgeConfig
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.SectionSyncState
import com.ecojournal.app.data.SyncResult
import com.ecojournal.app.data.SyncSections
import com.ecojournal.app.data.SyncTrace
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.ui.theme.EcoDesignTokens
import com.ecojournal.app.util.toPersianDigits
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.launch

/**
 * Diagnostics screen (M8, plan §2.6).
 *
 * Counts and status ONLY: app version, cached article/market/
 * formula/bookmark counts, seed version, edge URL, last sync times
 * and the last fixed sync error code, and whether an edge index
 * hash is stored (present/absent — the hash value itself is never
 * shown). NO PII, no article content, no invite code or hash, no
 * token, no device id. The copy action copies exactly the same
 * lines shown on screen, nothing more.
 */
/**
 * v5.1 defensive parsers (display only — the data worker owns
 * these meta keys; this screen never writes them).
 *
 * v51_section_outcome is accepted as a JSON array of outcome
 * objects OR a JSON object mapping section -> outcome object OR
 * a single outcome object. Field aliases cover the shapes a
 * data worker plausibly writes (section|s|key, code|c|status,
 * fetched|f|count, missing|m, timestamp|t|at, http|httpCode|
 * http_status); unknown fields are ignored, missing numerics
 * become -1 ("not recorded" on screen), and any unparseable
 * value yields no entries — never a crash, never a fabricated
 * value. Invite codes/hashes, tokens and device ids are never
 * fields here; if one ever appeared, this renderer would still
 * only show the section/code/count/timestamp/HTTP columns.
 */
private data class V51Outcome(
    val section: String,
    val code: String,
    val fetched: Int,
    val missing: Int,
    val at: Long,
    val http: String,
)

private fun v51FirstString(obj: JSONObject, vararg keys: String): String {
    for (key in keys) {
        val value = obj.optString(key)
        if (value.isNotBlank()) return value
    }
    return ""
}

private fun parseV51Outcomes(json: String?): List<V51Outcome> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val objects = mutableListOf<JSONObject>()
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { objects.add(it) }
            }
        } else if (trimmed.firstOrNull()?.code == 123) { // 123 == open brace, kept as a code so naive brace counters stay balanced
            val obj = JSONObject(trimmed)
            if (obj.has("section") || obj.has("s") || obj.has("key") ||
                obj.has("code") || obj.has("c")
            ) {
                objects.add(obj)
            } else {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val sectionKey = keys.next()
                    val value = obj.optJSONObject(sectionKey) ?: continue
                    value.put("section", sectionKey)
                    objects.add(value)
                }
            }
        } else {
            return emptyList()
        }
        objects.mapNotNull { obj ->
            val section = v51FirstString(obj, "section", "s", "key")
            if (section.isBlank()) return@mapNotNull null
            val code = v51FirstString(obj, "code", "c", "status")
            val http = v51FirstString(obj, "http", "httpCode", "http_status")
                .ifBlank { if (code.startsWith("http_")) code else "" }
            V51Outcome(
                section = section,
                code = code,
                fetched = obj.optInt("fetched", obj.optInt("f", obj.optInt("count", -1))),
                missing = obj.optInt("missing", obj.optInt("m", -1)),
                at = obj.optLong("timestamp", obj.optLong("t", obj.optLong("at", 0L))),
                http = http,
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Flatten a v5.1 free-form meta value for display: a plain
 * string renders verbatim; a JSON object renders one
 * "key: value" line per entry (nested values render compactly,
 * never expanded); a JSON array renders one line per element.
 * Absent, blank or unparseable content renders [noneLine].
 */
private fun v51FlatLines(json: String?, noneLine: String): List<String> {
    if (json.isNullOrBlank()) return listOf(noneLine)
    return try {
        val trimmed = json.trim()
        if (trimmed.firstOrNull()?.code == 123) { // 123 == open brace, see parseV51Outcomes
            val obj = JSONObject(trimmed)
            val out = mutableListOf<String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out.add("$key: ${obj.opt(key)?.toString() ?: "null"}")
            }
            if (out.isEmpty()) listOf(noneLine) else out
        } else if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            if (arr.length() == 0) {
                listOf(noneLine)
            } else {
                (0 until arr.length()).map { arr.opt(it)?.toString() ?: "null" }
            }
        } else {
            listOf(trimmed)
        }
    } catch (_: Exception) {
        listOf(noneLine)
    }
}

private fun formatEpoch(raw: String?): String? {
    val epoch = raw?.toLongOrNull() ?: return null
    return try {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Tehran")
        fmt.format(Date(epoch)).toPersianDigits()
    } catch (_: Exception) {
        null
    }
}

@Composable
fun DiagnosticsScreen(
    repo: EdgeRepository,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val articles by repo.dao.articleCountFlow().collectAsState(initial = 0)
    val marketCount by repo.dao.marketCountFlow().collectAsState(initial = 0)
    val formulas by repo.dao.formulasFlow().collectAsState(initial = emptyList())
    val bookmarks by repo.dao.bookmarkCountFlow().collectAsState(initial = 0)
    val seedVersion by repo.dao.metaValueFlow("seed_version").collectAsState(initial = null)
    // v4.0.1 (fix plan X8): seed missing count + seed error, so a
    // phone report can distinguish seed-empty from seed-failed.
    val seedMissing by repo.dao.metaValueFlow("seed_missing_count").collectAsState(initial = null)
    val seedError by repo.dao.metaValueFlow("seed_error").collectAsState(initial = null)
    val lastSync by repo.dao.metaValueFlow("last_sync_at").collectAsState(initial = null)
    val lastAttempt by repo.dao.metaValueFlow("last_sync_attempt_at").collectAsState(initial = null)
    val lastError by repo.dao.metaValueFlow("last_sync_error_code").collectAsState(initial = null)
    val indexHash by repo.dao.metaValueFlow("index_hash").collectAsState(initial = null)
    // v5 P1 instrumentation (gate G1): sync trace, feed-branch
    // trace and seed/manifest reconciliation, from the meta table.
    // Counts/codes/epochs only — never content, titles, invite
    // codes/hashes, tokens or device ids.
    val syncTraceJson by repo.dao.metaValueFlow(SyncTrace.TRACE_KEY).collectAsState(initial = null)
    val feedBranchJson by repo.dao.metaValueFlow(SyncTrace.BRANCH_KEY).collectAsState(initial = null)
    val reconJson by repo.dao.metaValueFlow(SyncTrace.RECON_KEY).collectAsState(initial = null)
    // v5.1 (display only): per-section outcomes, network state
    // and seed report, written by the data worker under these
    // meta keys. Rendered defensively below: absent or
    // unparseable content shows "not recorded", never a crash
    // and never a fabricated value.
    val v51OutcomeJson by repo.dao.metaValueFlow("v51_section_outcome").collectAsState(initial = null)
    val v51NetworkJson by repo.dao.metaValueFlow("v51_network_state").collectAsState(initial = null)
    val v51SeedJson by repo.dao.metaValueFlow("v51_seed_report").collectAsState(initial = null)
    var syncing by remember { mutableStateOf(false) }
    var syncResult by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }

    // Phase 1 per-section states. Loaded once on entry and reloaded
    // after every sync action, so the screen never shows a stale
    // section state next to a fresh global line.
    var sectionStates by remember { mutableStateOf(emptyList<SectionSyncState>()) }
    var busySection by remember { mutableStateOf<String?>(null) }
    // Phase 2 Diagnostics (spec 10): held count, per-format today
    // counts against daily caps, and non-zero anomaly lines.
    // Counts/states/fixed codes ONLY — never content, titles of
    // held items, codes, tokens, or reader behaviour.
    val heldCount by repo.channelDao.heldCountFlow().collectAsState(initial = 0)
    var anomalyCounts by remember { mutableStateOf(emptyMap<String, Int>()) }
    var formatCounts by remember { mutableStateOf(emptyMap<String, Int>()) }
    // Phase 4 Diagnostics (spec §4.9): market + radar per-section
    // lines and notification counts/suppressions. Threshold
    // VALUES never appear here — only presence (set / not set);
    // the numeric value belongs on the Market banner itself.
    var p4Thresholds by remember {
        mutableStateOf(com.ecojournal.app.data.MarketThresholds(null, null))
    }
    var p4LatestTs by remember { mutableStateOf<Long?>(null) }
    var p4Radar by remember { mutableStateOf(Triple(0, 0, 0)) }
    var p4NotifStats by remember {
        mutableStateOf(Pair(emptyMap<String, Int>(), emptyMap<String, Int>()))
    }
    var p4Alerts by remember {
        mutableStateOf(emptyList<com.ecojournal.app.data.MarketAlertEventEntity>())
    }
    val loadPhase4: suspend () -> Unit = {
        p4Thresholds = repo.marketThresholds()
        p4LatestTs = repo.dao.marketLatest()?.tsEpoch
        p4Radar = repo.radarCounts()
        p4NotifStats = repo.notificationStatsToday()
        p4Alerts = repo.phase4Dao.allAlertEvents()
    }
    LaunchedEffect(Unit) {
        sectionStates = repo.sectionStates()
        anomalyCounts = repo.anomalyCountsToday()
        formatCounts = repo.formatCountsToday()
        loadPhase4()
    }
    val sectionNames = mapOf(
        SyncSections.ARTICLES to stringResource(R.string.diagnostics_section_name_articles),
        SyncSections.SHORTS to stringResource(R.string.diagnostics_section_name_shorts),
        SyncSections.MARKET to stringResource(R.string.diagnostics_section_name_market),
        SyncSections.CHANNEL_REFS to stringResource(R.string.diagnostics_section_name_channel_refs),
        SyncSections.CHANNEL_TYPED to stringResource(R.string.diagnostics_section_name_channel_typed),
        SyncSections.BUNDLES to stringResource(R.string.diagnostics_section_name_bundles),
        SyncSections.RADAR to stringResource(R.string.diagnostics_section_name_radar),
        SyncSections.NIGHTLY to stringResource(R.string.diagnostics_section_name_nightly),
        SyncSections.VOICES to stringResource(R.string.diagnostics_section_name_voices),
        SyncSections.SPECIAL_ISSUES to stringResource(R.string.diagnostics_section_name_special_issues),
        SyncSections.INVITES to stringResource(R.string.diagnostics_section_name_invites),
        SyncSections.REVOCATIONS to stringResource(R.string.diagnostics_section_name_revocations),
    )
    // v5 P4: required/optional marker. The display loop and the
    // copy lines (sectionLines) both use this helper, so copy
    // stays exactly what is shown: an optional section never
    // masquerades as a required one when its clock is empty.
    val optionalTag = stringResource(R.string.v5_diagnostics_optional_tag)
    val sectionDisplayName: (String) -> String = { key ->
        val base = sectionNames[key] ?: key
        if (key in SyncSections.REQUIRED) base else "$base ($optionalTag)"
    }
    val stateNames = mapOf(
        "up_to_date" to stringResource(R.string.diagnostics_state_up_to_date),
        "partial" to stringResource(R.string.diagnostics_state_partial),
        "failed" to stringResource(R.string.diagnostics_state_failed),
        "not_yet_published" to stringResource(R.string.diagnostics_state_not_yet_published),
        "gated_frozen" to stringResource(R.string.diagnostics_state_gated_frozen),
        "unknown" to stringResource(R.string.diagnostics_state_unknown),
    )
    val noValueEarly = stringResource(R.string.diagnostics_no_value)
    val sectionLines = sectionStates.flatMap { s ->
        val name = sectionDisplayName(s.section)
        val stateLabel = stateNames[s.state] ?: s.state
        listOf(
            context.getString(
                R.string.diagnostics_section_line,
                name,
                stateLabel,
                s.itemCount.toString().toPersianDigits(),
            ),
            context.getString(
                R.string.diagnostics_section_detail,
                formatEpoch(s.lastSyncAt?.toString()) ?: noValueEarly,
                formatEpoch(s.lastAttemptAt?.toString()) ?: noValueEarly,
                s.lastErrorCode.takeIf { it.isNotBlank() } ?: noValueEarly,
                s.missingCount.toString().toPersianDigits(),
            ),
        )
    }

    // Phase 2 lines, built once and used for BOTH display and
    // copy, so copy-diagnostics copies exactly the lines shown
    // (P2-A11). Per-format lines cover every known format, even
    // at zero, so a cap-hit day is visible; anomaly lines show
    // only when their count is > 0 (spec 10).
    val heldLine = context.getString(
        R.string.diagnostics_held_count,
        heldCount.toString().toPersianDigits(),
    )
    val formatLines = com.ecojournal.app.data.Formats.ALL.map { (format, info) ->
        val count = formatCounts[format] ?: 0
        if (info.dailyCap != null) {
            context.getString(
                R.string.diagnostics_format_line,
                info.badgeText,
                count.toString().toPersianDigits(),
                info.dailyCap.toString().toPersianDigits(),
            )
        } else {
            context.getString(
                R.string.diagnostics_format_nocap,
                info.badgeText,
                count.toString().toPersianDigits(),
            )
        }
    }
    val anomalyLines = anomalyCounts.entries.sortedBy { it.key }.map { (code, n) ->
        context.getString(
            R.string.diagnostics_anomaly_line,
            code,
            n.toString().toPersianDigits(),
        )
    }
    val phase2Lines = listOf(heldLine) + formatLines + anomalyLines

    // Phase 3 chain lines (spec 3.2), built once and used for
    // BOTH display and copy, like phase2Lines. A missing step
    // shows "not recorded" and marks the chain incomplete -- it
    // is never rendered as zero or as the previous step's time.
    // The Pages line is lab-observed, never phone-measured, so it
    // always carries the footnote below.
    val chainJson by repo.dao.metaValueFlow(ChainRecord.META_KEY)
        .collectAsState(initial = null)
    val chain = ChainRecord.fromMetaJson(chainJson)
    val chainLines: List<String> = if (chain == null) {
        listOf(stringResource(R.string.diagnostics_chain_none))
    } else {
        val stepLabels = mapOf(
            "channel_post" to stringResource(R.string.diagnostics_chain_step_channel_post),
            "staged" to stringResource(R.string.diagnostics_chain_step_staged),
            "gate" to context.getString(
                R.string.diagnostics_chain_step_gate,
                when (chain.gateDecision) {
                    "approved" -> "تأیید شد"
                    "rejected" -> "رد شد"
                    else -> chain.gateDecision ?: noValueEarly
                },
            ),
            "edge_generated" to stringResource(R.string.diagnostics_chain_step_edge_generated),
            "edge_pushed" to stringResource(R.string.diagnostics_chain_step_edge_pushed),
            "pages_first_seen" to stringResource(R.string.diagnostics_chain_step_pages),
            "app_manifest_seen" to stringResource(R.string.diagnostics_chain_step_app_manifest),
            "app_item_applied" to stringResource(R.string.diagnostics_chain_step_app_applied),
            "app_notified" to stringResource(R.string.diagnostics_chain_step_app_notified),
        )
        val stepLines = chain.steps().map { step ->
            val timeText = when {
                step.kind == "pages_first_seen" -> noValueEarly
                step.kind == "app_notified" && step.epochSec == null ->
                    stringResource(R.string.diagnostics_chain_no_notification)
                step.epochSec == null -> noValueEarly
                else -> ChainRecord.formatTehran(step.epochSec) ?: noValueEarly
            }
            val gapText = ChainRecord.formatGap(step.gapSec)
            val base = context.getString(
                R.string.diagnostics_chain_step,
                stepLabels[step.kind] ?: step.kind,
                timeText,
            )
            if (gapText != null) "$base (+$gapText)" else base
        }
        val tail = mutableListOf<String>()
        if (!chain.isComplete) {
            tail.add(stringResource(R.string.diagnostics_chain_incomplete))
        }
        chain.endToEndSec?.let { secs ->
            tail.add(
                context.getString(
                    R.string.diagnostics_chain_end_to_end,
                    ChainRecord.formatGap(secs) ?: secs.toString().toPersianDigits(),
                )
            )
        }
        chain.pollPath?.let { path ->
            tail.add(
                context.getString(
                    R.string.diagnostics_chain_poll_path,
                    when (path) {
                        "manual" -> stringResource(R.string.diagnostics_chain_poll_manual)
                        else -> stringResource(R.string.diagnostics_chain_poll_periodic)
                    },
                )
            )
        }
        tail.add(stringResource(R.string.diagnostics_chain_pages_note))
        if (chain.hasClockSkew) {
            tail.add(stringResource(R.string.diagnostics_chain_clock_skew))
        }
        listOf(stringResource(R.string.diagnostics_chain_title)) + stepLines + tail
    }

    // Phase 4 lines, built once and used for BOTH display and
    // copy, like phase2Lines/chainLines. Alert lines carry a
    // timestamp ONLY per metric (spec §4.9) — no value, no
    // threshold, no content.
    val phase4Lines: List<String> = run {
        val out = mutableListOf<String>()
        out.add(stringResource(R.string.diagnostics_p4_title))
        out.add(
            context.getString(
                R.string.diagnostics_p4_market_line,
                marketCount.toString().toPersianDigits(),
                formatEpoch(p4LatestTs?.toString()) ?: noValueEarly,
                stringResource(
                    if (p4Thresholds.bubbleSet) {
                        R.string.diagnostics_p4_threshold_set
                    } else {
                        R.string.diagnostics_p4_threshold_unset
                    }
                ),
                stringResource(
                    if (p4Thresholds.spreadSet) {
                        R.string.diagnostics_p4_threshold_set
                    } else {
                        R.string.diagnostics_p4_threshold_unset
                    }
                ),
            )
        )
        out.add(
            context.getString(
                R.string.diagnostics_p4_radar_line,
                p4Radar.first.toString().toPersianDigits(),
                p4Radar.second.toString().toPersianDigits(),
                p4Radar.third.toString().toPersianDigits(),
            )
        )
        val (fired, suppressed) = p4NotifStats
        out.add(
            context.getString(
                R.string.diagnostics_p4_notifications_line,
                fired.values.sum().toString().toPersianDigits(),
                suppressed.values.sum().toString().toPersianDigits(),
            )
        )
        for ((type, n) in fired.entries.sortedBy { it.key }) {
            out.add(
                context.getString(
                    R.string.diagnostics_p4_fired_line,
                    type,
                    n.toString().toPersianDigits(),
                )
            )
        }
        for ((reason, n) in suppressed.entries.sortedBy { it.key }) {
            out.add(
                context.getString(
                    R.string.diagnostics_p4_suppressed_line,
                    reason,
                    n.toString().toPersianDigits(),
                )
            )
        }
        for (metric in listOf("bubble", "spread")) {
            val latest = p4Alerts.firstOrNull { it.metric == metric }
            if (latest == null) {
                out.add(
                    context.getString(
                        R.string.diagnostics_p4_alert_line,
                        metric,
                        stringResource(R.string.diagnostics_p4_alert_none),
                    )
                )
            } else {
                out.add(
                    context.getString(
                        R.string.diagnostics_p4_alert_line,
                        metric,
                        formatEpoch(latest.firedAtEpoch.toString()) ?: noValueEarly,
                    )
                )
            }
        }
        out
    }

    // v5 P1 lines: sync trace (newest 12), feed-branch trace
    // (newest 12) and the latest seed/manifest reconciliation.
    // Built once and used for BOTH display and copy, like the
    // phase2/chain/phase4 lines. A -1 count renders as "not
    // recorded", never as zero; an unknown section/state key
    // renders verbatim rather than being dropped.
    val traceEntries = remember(syncTraceJson) { SyncTrace.parseTrace(syncTraceJson) }
    val traceLines: List<String> =
        listOf(stringResource(R.string.diagnostics_trace_title)) +
            if (traceEntries.isEmpty()) {
                listOf(stringResource(R.string.diagnostics_trace_none))
            } else {
                traceEntries.takeLast(12).reversed().map { e ->
                    context.getString(
                        R.string.diagnostics_trace_line,
                        formatEpoch(e.at.toString()) ?: noValueEarly,
                        sectionNames[e.section] ?: e.section,
                        e.code.takeIf { it.isNotBlank() } ?: noValueEarly,
                        if (e.fetched < 0) noValueEarly
                        else e.fetched.toString().toPersianDigits(),
                        if (e.missing < 0) noValueEarly
                        else e.missing.toString().toPersianDigits(),
                        stateNames[e.state] ?: e.state,
                    )
                }
            }
    val branchNames = mapOf(
        "loading" to "بارگذاری",
        "empty" to "خالی",
        "front_page" to "صفحهٔ اصلی",
        "filter" to "فیلتر",
        "search" to "جستجو",
        "urgent_all" to "ریل فوری",
        // v5.1 branches: a held-cache feed (live Room read
        // empty, held list on screen) and a kept session after
        // a transient invite re-check failure (MainActivity).
        // Unknown branches still render verbatim, never dropped.
        "retained" to "نگه‌داشته‌شده (کش)",
        "gate_held" to "نشست نگه داشته شد (بازبینی دعوت ناموفق)",
    )
    val branchEntries = remember(feedBranchJson) { SyncTrace.parseBranches(feedBranchJson) }
    val branchLines: List<String> =
        listOf(stringResource(R.string.diagnostics_branch_title)) +
            if (branchEntries.isEmpty()) {
                listOf(stringResource(R.string.diagnostics_branch_none))
            } else {
                branchEntries.takeLast(12).reversed().map { e ->
                    context.getString(
                        R.string.diagnostics_branch_line,
                        formatEpoch(e.at.toString()) ?: noValueEarly,
                        branchNames[e.branch] ?: e.branch,
                        if (e.articles < 0) noValueEarly
                        else e.articles.toString().toPersianDigits(),
                        if (e.typed < 0) noValueEarly
                        else e.typed.toString().toPersianDigits(),
                    )
                }
            }
    val recon = remember(reconJson) { SyncTrace.parseRecon(reconJson) }
    val reconLines: List<String> =
        listOf(stringResource(R.string.diagnostics_recon_title)) +
            if (recon == null) {
                listOf(stringResource(R.string.diagnostics_recon_none))
            } else {
                listOf(
                    context.getString(
                        R.string.diagnostics_recon_line,
                        formatEpoch(recon.at.toString()) ?: noValueEarly,
                        if (recon.local < 0) noValueEarly
                        else recon.local.toString().toPersianDigits(),
                        if (recon.manifest < 0) noValueEarly
                        else recon.manifest.toString().toPersianDigits(),
                        if (recon.missing < 0) noValueEarly
                        else recon.missing.toString().toPersianDigits(),
                        if (recon.extra < 0) noValueEarly
                        else recon.extra.toString().toPersianDigits(),
                    )
                )
            }

    // v5.1 lines: per-section outcome table (code, fetched,
    // missing, timestamp, HTTP), network state and seed report.
    // Built once and used for BOTH display and copy, like the
    // trace/branch/recon lines. A -1 count or a missing field
    // renders as "not recorded", never as zero; an unknown
    // section key renders verbatim rather than being dropped.
    val v51OutcomeEntries = remember(v51OutcomeJson) { parseV51Outcomes(v51OutcomeJson) }
    val v51OutcomeLines: List<String> =
        listOf(stringResource(R.string.v51_diagnostics_section_outcome_title)) +
            if (v51OutcomeEntries.isEmpty()) {
                listOf(stringResource(R.string.v51_diagnostics_section_outcome_none))
            } else {
                v51OutcomeEntries.map { e ->
                    context.getString(
                        R.string.v51_diagnostics_section_outcome_line,
                        sectionNames[e.section] ?: e.section,
                        e.code.takeIf { it.isNotBlank() } ?: noValueEarly,
                        if (e.fetched < 0) noValueEarly
                        else e.fetched.toString().toPersianDigits(),
                        if (e.missing < 0) noValueEarly
                        else e.missing.toString().toPersianDigits(),
                        if (e.at <= 0L) noValueEarly
                        else formatEpoch(e.at.toString()) ?: noValueEarly,
                        e.http.takeIf { it.isNotBlank() } ?: noValueEarly,
                    )
                }
            }
    val v51NetworkLines: List<String> =
        listOf(stringResource(R.string.v51_diagnostics_network_title)) +
            v51FlatLines(
                v51NetworkJson,
                stringResource(R.string.v51_diagnostics_network_none),
            )
    val v51SeedLines: List<String> =
        listOf(stringResource(R.string.v51_diagnostics_seed_report_title)) +
            v51FlatLines(
                v51SeedJson,
                stringResource(R.string.v51_diagnostics_seed_report_none),
            )

    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
    val versionCode = try {
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        ).toString().toPersianDigits()
    } catch (_: Exception) {
        "?"
    }
    val noValue = stringResource(R.string.diagnostics_no_value)
    // v5 P1 fingerprint, line 1: version + code (PackageManager,
    // as before) + git commit (BuildConfig, set at build time) +
    // edge URL, so one screenshot ties a phone report to an exact
    // build. The old version line below is kept unchanged.
    val lines = listOf(
        stringResource(
            R.string.diagnostics_fingerprint,
            versionName,
            versionCode,
            BuildConfig.GIT_COMMIT,
            EdgeConfig.BASE_URL,
        ),
        stringResource(R.string.diagnostics_version, versionName, versionCode),
        stringResource(R.string.diagnostics_articles, articles.toString().toPersianDigits()),
        stringResource(R.string.diagnostics_market, marketCount.toString().toPersianDigits()),
        stringResource(R.string.diagnostics_formulas, formulas.size.toString().toPersianDigits()),
        stringResource(R.string.diagnostics_bookmarks, bookmarks.toString().toPersianDigits()),
        stringResource(
            R.string.diagnostics_seed_version,
            (seedVersion ?: noValue).toPersianDigits(),
        ),
        stringResource(
            R.string.diagnostics_seed_missing,
            (seedMissing ?: noValue).toPersianDigits(),
        ),
        stringResource(
            R.string.diagnostics_seed_error,
            (seedError ?: noValue).toPersianDigits(),
        ),
        stringResource(R.string.diagnostics_edge_url, EdgeConfig.BASE_URL),
        stringResource(
            R.string.diagnostics_last_sync,
            formatEpoch(lastSync) ?: noValue,
        ),
        // v5 P4: the global clock's scope, stated next to the
        // clock itself — required sections only, so an
        // unpublished optional section never reads as a stuck
        // sync. This line is part of `lines`, so the copy action
        // carries it exactly as shown.
        stringResource(R.string.v5_diagnostics_global_clock),
        stringResource(
            R.string.diagnostics_last_attempt,
            formatEpoch(lastAttempt) ?: noValue,
        ),
        stringResource(
            R.string.diagnostics_last_error,
            lastError?.takeIf { it.isNotBlank() } ?: noValue,
        ),
        stringResource(
            if (indexHash.isNullOrBlank()) {
                R.string.diagnostics_index_absent
            } else {
                R.string.diagnostics_index_present
            }
        ),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(EcoDesignTokens.Spacing.PageMargin),
    ) {
        Text(
            text = stringResource(R.string.diagnostics_title),
            fontFamily = EcoFontFamily,
            fontSize = 22.sp * fontScale,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(12.dp))
        for (line in lines) {
            Text(
                text = line,
                fontFamily = EcoFontFamily,
                fontSize = EcoDesignTokens.Type.HeroSummary * fontScale,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        if (syncResult.isNotBlank()) {
            Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
            Text(
                text = syncResult,
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Phase 1 per-section transparency: every section shows
        // its state, counts, last sync/attempt, error code and
        // missing count, with its own force-refresh button. The
        // button re-reads the manifest and re-checks only that
        // section; it never bypasses the invite gate. Hash values
        // are never shown, only the present/absent line above.
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.diagnostics_sections_title),
            fontFamily = EcoFontFamily,
            fontSize = 20.sp * fontScale,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        for (s in sectionStates) {
            val name = sectionDisplayName(s.section)
            val stateLabel = stateNames[s.state] ?: s.state
            Text(
                text = context.getString(
                    R.string.diagnostics_section_line,
                    name,
                    stateLabel,
                    s.itemCount.toString().toPersianDigits(),
                ),
                fontFamily = EcoFontFamily,
                fontSize = EcoDesignTokens.Type.HeroSummary * fontScale,
            )
            Text(
                text = context.getString(
                    R.string.diagnostics_section_detail,
                    formatEpoch(s.lastSyncAt?.toString()) ?: noValueEarly,
                    formatEpoch(s.lastAttemptAt?.toString()) ?: noValueEarly,
                    s.lastErrorCode.takeIf { it.isNotBlank() } ?: noValueEarly,
                    s.missingCount.toString().toPersianDigits(),
                ),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                enabled = busySection == null,
                onClick = {
                    scope.launch {
                        busySection = s.section
                        repo.syncOnly(s.section, forceRefresh = true)
                        sectionStates = repo.sectionStates()
                        anomalyCounts = repo.anomalyCountsToday()
                        formatCounts = repo.formatCountsToday()
                        loadPhase4()
                        busySection = null
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_section_force, name),
                    fontFamily = EcoFontFamily,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        // Phase 2 lines (spec 10): held count, per-format cap
        // lines, non-zero anomaly lines — displayed exactly as
        // copied below.
        Spacer(modifier = Modifier.height(16.dp))
        for (line in phase2Lines) {
            Text(
                text = line,
                fontFamily = EcoFontFamily,
                fontSize = EcoDesignTokens.Type.HeroSummary * fontScale,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        // Phase 3 chain lines (spec 3.2) — displayed exactly as
        // copied below. No bar or card surface is touched here:
        // the typed reader/feed bars from Phase 2 are unchanged.
        Spacer(modifier = Modifier.height(16.dp))
        for (line in chainLines) {
            Text(
                text = line,
                fontFamily = EcoFontFamily,
                fontSize = EcoDesignTokens.Type.HeroSummary * fontScale,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        // Phase 4 lines — displayed exactly as copied below.
        Spacer(modifier = Modifier.height(16.dp))
        for (line in phase4Lines) {
            Text(
                text = line,
                fontFamily = EcoFontFamily,
                fontSize = EcoDesignTokens.Type.HeroSummary * fontScale,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        // v5 P1 lines — displayed exactly as copied below.
        Spacer(modifier = Modifier.height(16.dp))
        for (line in traceLines + branchLines + reconLines) {
            Text(
                text = line,
                fontFamily = EcoFontFamily,
                fontSize = EcoDesignTokens.Type.HeroSummary * fontScale,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        // v5.1 lines — displayed exactly as copied below, in
        // the same one-copyable-report order as the copy action.
        Spacer(modifier = Modifier.height(16.dp))
        for (line in v51OutcomeLines + v51NetworkLines + v51SeedLines) {
            Text(
                text = line,
                fontFamily = EcoFontFamily,
                fontSize = EcoDesignTokens.Type.HeroSummary * fontScale,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            enabled = !syncing,
            onClick = {
                scope.launch {
                    syncing = true
                    syncResult = context.getString(R.string.sync_in_progress)
                    syncResult = when (val r = repo.sync()) {
                        is SyncResult.Ok -> context.getString(
                            R.string.sync_up_to_date,
                            r.totalCached.toString().toPersianDigits(),
                        )
                        is SyncResult.Partial -> context.getString(
                            R.string.sync_partial,
                            r.missingCount.toString().toPersianDigits(),
                        )
                        is SyncResult.NotConfigured -> context.getString(
                            R.string.sync_not_configured
                        )
                        is SyncResult.Failed -> context.getString(R.string.sync_failed)
                    }
                    sectionStates = repo.sectionStates()
                    anomalyCounts = repo.anomalyCountsToday()
                    formatCounts = repo.formatCountsToday()
                    loadPhase4()
                    syncing = false
                }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                text = stringResource(R.string.diagnostics_sync_retry),
                fontFamily = EcoFontFamily,
            )
        }
        Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
        Button(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? ClipboardManager
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText(
                        "ECO JOURNAL diagnostics",
                        // v5.1: the copy is the WHOLE displayed
                        // report, verbatim — every section above,
                        // including the new v5.1 sections, with
                        // no truncation and no extra lines.
                        (lines + sectionLines + phase2Lines + chainLines + phase4Lines + traceLines + branchLines + reconLines + v51OutcomeLines + v51NetworkLines + v51SeedLines).joinToString("\n"),
                    )
                )
                copied = true
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                text = stringResource(R.string.diagnostics_copy),
                fontFamily = EcoFontFamily,
            )
        }
        if (copied) {
            Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
            Text(
                text = stringResource(R.string.diagnostics_copied),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                text = stringResource(R.string.diagnostics_back),
                fontFamily = EcoFontFamily,
            )
        }
    }
}
