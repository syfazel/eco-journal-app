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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import com.ecojournal.app.R
import com.ecojournal.app.data.EdgeConfig
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.SectionSyncState
import com.ecojournal.app.data.SyncResult
import com.ecojournal.app.data.SyncSections
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.util.toPersianDigits
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
    val lastSync by repo.dao.metaValueFlow("last_sync_at").collectAsState(initial = null)
    val lastAttempt by repo.dao.metaValueFlow("last_sync_attempt_at").collectAsState(initial = null)
    val lastError by repo.dao.metaValueFlow("last_sync_error_code").collectAsState(initial = null)
    val indexHash by repo.dao.metaValueFlow("index_hash").collectAsState(initial = null)
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
    LaunchedEffect(Unit) {
        sectionStates = repo.sectionStates()
        anomalyCounts = repo.anomalyCountsToday()
        formatCounts = repo.formatCountsToday()
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
    val lines = listOf(
        stringResource(R.string.diagnostics_version, versionName, versionCode),
        stringResource(R.string.diagnostics_articles, articles.toString().toPersianDigits()),
        stringResource(R.string.diagnostics_market, marketCount.toString().toPersianDigits()),
        stringResource(R.string.diagnostics_formulas, formulas.size.toString().toPersianDigits()),
        stringResource(R.string.diagnostics_bookmarks, bookmarks.toString().toPersianDigits()),
        stringResource(
            R.string.diagnostics_seed_version,
            (seedVersion ?: noValue).toPersianDigits(),
        ),
        stringResource(R.string.diagnostics_edge_url, EdgeConfig.BASE_URL),
        stringResource(
            R.string.diagnostics_last_sync,
            formatEpoch(lastSync) ?: noValue,
        ),
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
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.diagnostics_title),
            fontFamily = EcoFontFamily,
            fontSize = 22.sp * fontScale,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        for (line in lines) {
            Text(
                text = line,
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        if (syncResult.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
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
            val name = sectionNames[s.section] ?: s.section
            val stateLabel = stateNames[s.state] ?: s.state
            Text(
                text = context.getString(
                    R.string.diagnostics_section_line,
                    name,
                    stateLabel,
                    s.itemCount.toString().toPersianDigits(),
                ),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
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
                fontSize = 16.sp * fontScale,
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
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? ClipboardManager
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText(
                        "ECO JOURNAL diagnostics",
                        (lines + sectionLines + phase2Lines).joinToString("\n"),
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
            Spacer(modifier = Modifier.height(8.dp))
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
