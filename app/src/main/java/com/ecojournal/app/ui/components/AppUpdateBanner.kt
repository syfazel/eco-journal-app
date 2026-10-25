package com.ecojournal.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.data.PendingUpdate
import com.ecojournal.app.data.UpdateUiState
import com.ecojournal.app.ui.theme.EcoFontFamily

/**
 * v4-I update surfaces. Two shapes, one state machine:
 *
 * - [AppUpdateCard]: the full Settings section card — version,
 *   changelog, mandatory badge, download progress, install,
 *   dismiss (optional only), and the fixed-string error line.
 * - [AppUpdateCompactRow]: the feed's one-line banner — version
 *   + a single action button. It never shows progress detail
 *   beyond the bar, and it never hides a failure: a failed
 *   download collapses to the compact error line with retry.
 *
 * The honest limit is shown, not hidden: the install button
 * is labelled as opening the system installer, because the
 * app cannot install itself silently (see AppUpdate.kt).
 * A mandatory update has NO dismiss button at all — the
 * button is not rendered, not merely disabled, so TalkBack
 * never offers a dead action.
 */
@Composable
fun AppUpdateCard(
    state: UpdateUiState,
    fontScale: Float,
    onCheck: () -> Unit,
    onDownload: (PendingUpdate) -> Unit,
    onInstall: () -> Unit,
    onDismiss: (PendingUpdate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.app_update_title),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            when (state) {
                is UpdateUiState.Idle -> {
                    Text(
                        text = stringResource(R.string.app_update_idle),
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onCheck, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(text = stringResource(R.string.app_update_check), fontFamily = EcoFontFamily)
                    }
                }
                is UpdateUiState.Checking -> {
                    Text(
                        text = stringResource(R.string.app_update_checking),
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                    )
                }
                is UpdateUiState.Available -> UpdateOffer(
                    pending = state.pending,
                    fontScale = fontScale,
                    onDownload = onDownload,
                    onDismiss = onDismiss,
                )
                is UpdateUiState.Downloading -> {
                    Text(
                        text = stringResource(R.string.app_update_downloading, state.pending.versionName),
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription =
                                    "پیشرفت دانلود نسخهٔ ${state.pending.versionName}"
                            },
                    )
                }
                is UpdateUiState.ReadyToInstall -> {
                    Text(
                        text = stringResource(R.string.app_update_ready, state.pending.versionName),
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onInstall, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(text = stringResource(R.string.app_update_install), fontFamily = EcoFontFamily)
                    }
                }
                is UpdateUiState.NeedsInstallPermission -> {
                    Text(
                        text = stringResource(R.string.app_update_need_permission),
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onInstall, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(text = stringResource(R.string.app_update_open_settings), fontFamily = EcoFontFamily)
                    }
                }
                is UpdateUiState.Failed -> {
                    Text(
                        text = stringResource(R.string.app_update_failed, state.code),
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onCheck, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(text = stringResource(R.string.app_update_retry), fontFamily = EcoFontFamily)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateOffer(
    pending: PendingUpdate,
    fontScale: Float,
    onDownload: (PendingUpdate) -> Unit,
    onDismiss: (PendingUpdate) -> Unit,
) {
    Text(
        text = stringResource(R.string.app_update_available, pending.versionName),
        fontFamily = EcoFontFamily,
        fontSize = 14.sp * fontScale,
    )
    if (pending.mandatory) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.app_update_mandatory),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
    }
    if (pending.changelogFa.isNotBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = pending.changelogFa,
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row {
        Button(
            onClick = { onDownload(pending) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(text = stringResource(R.string.app_update_download), fontFamily = EcoFontFamily)
        }
        if (!pending.mandatory) {
            TextButton(
                onClick = { onDismiss(pending) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.app_update_dismiss), fontFamily = EcoFontFamily)
            }
        }
    }
}

@Composable
fun AppUpdateCompactRow(
    state: UpdateUiState,
    fontScale: Float,
    onDownload: (PendingUpdate) -> Unit,
    onInstall: () -> Unit,
    onDismiss: (PendingUpdate) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is UpdateUiState.Available -> {
            Card(modifier = modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.app_update_available, state.pending.versionName),
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { onDownload(state.pending) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(text = stringResource(R.string.app_update_download), fontFamily = EcoFontFamily)
                    }
                    if (!state.pending.mandatory) {
                        TextButton(
                            onClick = { onDismiss(state.pending) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(text = stringResource(R.string.app_update_dismiss), fontFamily = EcoFontFamily)
                        }
                    }
                }
            }
        }
        is UpdateUiState.Downloading -> {
            Card(modifier = modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.app_update_downloading, state.pending.versionName),
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        is UpdateUiState.ReadyToInstall -> {
            Card(modifier = modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.app_update_ready, state.pending.versionName),
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(text = stringResource(R.string.app_update_install), fontFamily = EcoFontFamily)
                    }
                }
            }
        }
        is UpdateUiState.Failed -> {
            Card(modifier = modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.app_update_failed, state.code),
                        fontFamily = EcoFontFamily,
                        fontSize = 14.sp * fontScale,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        else -> {
            // Idle / Checking / NeedsInstallPermission: nothing on
            // the feed. The full card in Settings covers those.
        }
    }
}
