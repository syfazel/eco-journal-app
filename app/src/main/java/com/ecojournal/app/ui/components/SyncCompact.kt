package com.ecojournal.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.ui.theme.EcoFontFamily

/**
 * v4-H (V12): the compact sync row. Yasin's complaint was exact —
 * the old sync block (button + status line + last-sync line +
 * offline line + spacers) ate the page and shrank the reading
 * view. This row is ONE line: an icon sync button, a one-line
 * status, and, only on failure, a retry. Nothing about sync
 * behaviour changes: same runner, same cadence, same codes.
 *
 * Nothing is hidden that matters: failures still show (with
 * retry), the last-sync time stays on the line when known, and
 * the full per-section state, force-refresh, and error codes
 * stay exactly where they were — in Diagnostics, one tap away
 * via [onOpenDiagnostics] when the caller wires it. A compact
 * row that swallowed failures would be a regression, not a fix.
 */
@Composable
fun CompactSyncRow(
    syncing: Boolean,
    syncFailed: Boolean,
    syncState: String,
    lastSyncText: String?,
    onSync: () -> Unit,
    onRetry: () -> Unit,
    onOpenDiagnostics: (() -> Unit)?,
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    // v4.0.1 (fix plan X4): a blank status must never read as
    // "up to date". Before this fix a fresh launch with no sync
    // ever run showed the up-to-date string next to an empty feed.
    val statusDesc = when {
        syncing -> stringResource(R.string.sync_compact_syncing)
        syncState.isNotBlank() -> syncState
        else -> stringResource(R.string.sync_compact_idle)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = statusDesc },
    ) {
        IconButton(
            onClick = onSync,
            enabled = !syncing,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Sync,
                contentDescription = stringResource(R.string.sync_button),
            )
        }
        if (syncing) {
            Spacer(modifier = Modifier.width(4.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = statusDesc,
            fontFamily = EcoFontFamily,
            fontSize = 12.sp * fontScale,
            color = if (syncFailed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!lastSyncText.isNullOrBlank() && !syncing) {
            // Shown whenever not syncing, including the honest
            // "never succeeded" line — the row must never imply a
            // sync happened when none has.
            Text(
                text = lastSyncText,
                fontFamily = EcoFontFamily,
                fontSize = 11.sp * fontScale,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (syncFailed && !syncing) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sync_retry), fontFamily = EcoFontFamily)
            }
        }
        if (onOpenDiagnostics != null) {
            TextButton(onClick = onOpenDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sync_details), fontFamily = EcoFontFamily)
            }
        }
    }
}
