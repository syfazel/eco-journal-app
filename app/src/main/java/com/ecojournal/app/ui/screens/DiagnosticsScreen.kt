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
 * Diagnostics screen (M8, plan §2.6), Phase 4 (v5.3) Yemen-only.
 *
 * Counts and status ONLY: app version, cached Yemen counts
 * (places / events / frontlines), seed version, edge URL, the
 * Yemen sync state and the last fixed Yemen sync error code,
 * and whether a Yemen hash is stored (present/absent — the hash
 * value itself is never shown). NO PII, no article content, no
 * invite code or hash, no token, no device id. The copy action
 * copies exactly the same lines shown on screen, nothing more.
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
    val places by repo.yemenDao.placesFlow().collectAsState(initial = emptyList())
    val events by repo.yemenDao.eventsFlow().collectAsState(initial = emptyList())
    val frontlines by repo.yemenDao.frontlinesFlow().collectAsState(initial = emptyList())
    val yemenMeta by repo.yemenDao.metaFlow().collectAsState(initial = null)
    val seedVersion by repo.dao.metaValueFlow("seed_version").collectAsState(initial = null)
    // v4.0.1 (fix plan X8): seed missing count + seed error, so a
    // phone report can distinguish seed-empty from seed-failed.
    val seedMissing by repo.dao.metaValueFlow("seed_missing_count").collectAsState(initial = null)
    val seedError by repo.dao.metaValueFlow("seed_error").collectAsState(initial = null)
    val lastAttempt by repo.dao.metaValueFlow("last_sync_attempt_at").collectAsState(initial = null)
    val lastError by repo.dao.metaValueFlow("last_sync_error_code").collectAsState(initial = null)
    // v5 P1 instrumentation (gate G1): sync trace and feed-branch
    // trace, from the meta table. Counts/codes/epochs only —
    // never content, titles, invite codes/hashes, tokens or
    // device ids.
    val syncTraceJson by repo.dao.metaValueFlow(SyncTrace.TRACE_KEY).collectAsState(initial = null)
    val feedBranchJson by repo.dao.metaValueFlow(SyncTrace.BRANCH_KEY).collectAsState(initial = null)
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

    // The single remaining section. Loaded once on entry and
    // reloaded after every sync action, so the screen never
    // shows a stale section state next to a fresh line.
    var sectionStates by remember { mutableStateOf(emptyList<SectionSyncState>()) }
    LaunchedEffect(Unit) {
        sectionStates = repo.sectionStates()
    }
    val sectionNames = mapOf(
        SyncSections.YEMEN to stringResource(R.string.tab_yemen),
    )
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
        val name = sectionNames[s.section] ?: s.section
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

    // v5 P1 lines: sync trace (newest 12) and feed-branch trace
    // (newest 12). Built once and used for BOTH display and copy.
    // A -1 count renders as "not recorded", never as zero; an
    // unknown section/state key renders verbatim rather than
    // being dropped.
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
        // v5.1 branches: a kept session after a transient invite
        // re-check failure (MainActivity). Unknown branches still
        // render verbatim, never dropped.
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

    // v5.1 lines: per-section outcome table (code, fetched,
    // missing, timestamp, HTTP), network state and seed report.
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
        stringResource(
            R.string.diagnostics_yemen_places,
            places.size.toString().toPersianDigits(),
        ),
        stringResource(
            R.string.diagnostics_yemen_events,
            events.size.toString().toPersianDigits(),
        ),
        stringResource(
            R.string.diagnostics_yemen_frontlines,
            frontlines.size.toString().toPersianDigits(),
        ),
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
            R.string.diagnostics_yemen_last_sync,
            yemenMeta?.lastYemenSyncAt?.let { formatEpoch(it.toString()) } ?: noValue,
        ),
        stringResource(
            R.string.diagnostics_yemen_error,
            yemenMeta?.lastYemenErrorCode?.takeIf { it.isNotBlank() } ?: noValue,
        ),
        stringResource(
            R.string.diagnostics_last_attempt,
            formatEpoch(lastAttempt) ?: noValue,
        ),
        stringResource(
            R.string.diagnostics_last_error,
            lastError?.takeIf { it.isNotBlank() } ?: noValue,
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
        // The single remaining section: its state, counts, last
        // sync/attempt, error code and missing count. Hash values
        // are never shown, only present/absent (in the section
        // state itself, via sectionStates()).
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.diagnostics_sections_title),
            fontFamily = EcoFontFamily,
            fontSize = 20.sp * fontScale,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        for (line in sectionLines) {
            Text(
                text = line,
                fontFamily = EcoFontFamily,
                fontSize = EcoDesignTokens.Type.HeroSummary * fontScale,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                if (syncing) return@Button
                syncing = true
                syncResult = context.getString(R.string.sync_in_progress)
                scope.launch {
                    try {
                        syncResult = when (val r = repo.syncYemen(forceRefresh = true)) {
                            is SyncResult.Ok -> context.getString(
                                R.string.diagnostics_sync_result,
                                context.getString(R.string.diagnostics_state_up_to_date),
                            )
                            is SyncResult.Partial -> context.getString(
                                R.string.diagnostics_sync_result,
                                context.getString(R.string.diagnostics_state_partial),
                            )
                            is SyncResult.Failed -> context.getString(
                                R.string.diagnostics_sync_result,
                                r.code,
                            )
                            is SyncResult.NotConfigured -> context.getString(
                                R.string.diagnostics_sync_result,
                                context.getString(R.string.diagnostics_state_unknown),
                            )
                        }
                        sectionStates = repo.sectionStates()
                    } finally {
                        syncing = false
                    }
                }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                text = stringResource(R.string.diagnostics_sync_retry),
                fontFamily = EcoFontFamily,
            )
        }
        for (group in listOf(traceLines, branchLines, v51OutcomeLines, v51NetworkLines, v51SeedLines)) {
            Spacer(modifier = Modifier.height(16.dp))
            for (line in group) {
                Text(
                    text = line,
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val text = (lines + sectionLines + traceLines + branchLines +
                    v51OutcomeLines + v51NetworkLines + v51SeedLines).joinToString("\n")
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("diagnostics", text))
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
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.diagnostics_copied),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
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
