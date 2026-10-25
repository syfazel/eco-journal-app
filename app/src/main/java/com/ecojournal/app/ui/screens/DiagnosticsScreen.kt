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
import com.ecojournal.app.data.SyncResult
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
                    ClipData.newPlainText("ECO JOURNAL diagnostics", lines.joinToString("\n"))
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
