package com.ecojournal.app.ui.screens

import android.content.Intent
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
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.data.BROADCAST_MAX_CHARS
import com.ecojournal.app.data.BROADCAST_MAX_MEMBERS
import com.ecojournal.app.data.BroadcastEnvelopeCodec
import com.ecojournal.app.data.BroadcastMemberEntity
import com.ecojournal.app.data.BroadcastMessageEntity
import com.ecojournal.app.data.BroadcastRules
import com.ecojournal.app.data.DatabaseProvider
import com.ecojournal.app.data.InviteGate.sha256Hex
import com.ecojournal.app.data.Jalali
import com.ecojournal.app.data.Law15
import com.ecojournal.app.data.Settings
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.util.Notifications
import com.ecojournal.app.util.toPersianDigits
import kotlinx.coroutines.launch

/**
 * Phase 5 (v5.4) — broadcast tab. Design:
 * files/tarahi-payamresani-faz5.md.
 *
 * One-way broadcast to a closed group of up to 10 people. The app
 * makes NO network call here: the sender composes, the app stores
 * the message and builds a text envelope, and the SENDER shares
 * that envelope through a private channel they control (e.g. Bale).
 * A recipient pastes a received envelope into the import box.
 * Notifications follow Law 15 and never carry message content
 * (lock-screen safety, G-DIAG-2). Pruning (30 days / 200 messages)
 * runs when this tab opens — no worker, no polling (G-BATT-4).
 */
@Composable
fun BroadcastScreen(
    settings: Settings,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember(context) { DatabaseProvider.get(context).broadcastDao() }
    val messages by dao.messagesFlow().collectAsState(initial = emptyList())
    val members by dao.activeMembersFlow().collectAsState(initial = emptyList())

    var composeBody by remember { mutableStateOf("") }
    var score by remember { mutableFloatStateOf(50f) }
    var importText by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var memberName by remember { mutableStateOf("") }
    var memberCode by remember { mutableStateOf("") }

    // Prune on open: messages older than 30 days, and over-cap
    // read messages. No worker — the tab itself does it.
    LaunchedEffect(Unit) {
        val pruned = BroadcastRules.prune(dao, System.currentTimeMillis())
        if (pruned > 0) {
            notice = context.getString(R.string.broadcast_pruned, pruned.toString().toPersianDigits())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tab_broadcast),
            fontFamily = EcoFontFamily,
            fontSize = 20.sp * fontScale,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.broadcast_honest_note),
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
        notice?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
        }

        // ---- Compose (sender mode only) ----
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.broadcast_compose_heading),
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
            modifier = Modifier.semantics { heading() },
        )
        if (!settings.broadcastSender) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.broadcast_not_sender),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
        } else {
            Spacer(modifier = Modifier.height(6.dp))
            TextField(
                value = composeBody,
                onValueChange = { if (it.length <= BROADCAST_MAX_CHARS) composeBody = it },
                placeholder = {
                    Text(text = stringResource(R.string.broadcast_body_hint), fontFamily = EcoFontFamily)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.broadcast_score_label) + ": " +
                    score.toInt().toString().toPersianDigits() +
                    if (score >= 70) " — " + stringResource(R.string.broadcast_score_urgent) else "",
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
            Slider(
                value = score,
                onValueChange = { score = it },
                valueRange = 0f..100f,
            )
            Button(
                onClick = {
                    val body = composeBody.trim()
                    if (body.isEmpty()) return@Button
                    val now = System.currentTimeMillis()
                    val senderName = settings.senderDisplayName.ifBlank { "یاسین" }
                    val scoreInt = score.toInt()
                    val envelope = BroadcastEnvelopeCodec.encode(senderName, body, scoreInt, now)
                    val id = BroadcastEnvelopeCodec.contentId(senderName, body, scoreInt, now)
                    scope.launch {
                        dao.insertMessage(
                            BroadcastMessageEntity(
                                id = id,
                                senderName = senderName,
                                body = body,
                                urgencyScore = scoreInt,
                                sentAtEpoch = now,
                                receivedAtEpoch = now,
                                origin = "local",
                                status = "delivered",
                            ),
                        )
                        composeBody = ""
                    }
                    // The app never sends the envelope itself: the
                    // system share sheet hands it to the channel
                    // the sender chooses (Bale, ...).
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, envelope)
                    }
                    context.startActivity(
                        Intent.createChooser(share, null),
                    )
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.broadcast_share_button), fontFamily = EcoFontFamily)
            }
        }

        // ---- Import ----
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.broadcast_import_heading),
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(6.dp))
        TextField(
            value = importText,
            onValueChange = { importText = it },
            placeholder = {
                Text(text = stringResource(R.string.broadcast_import_hint), fontFamily = EcoFontFamily)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                scope.launch {
                    when (val parsed = BroadcastEnvelopeCodec.parse(importText)) {
                        is BroadcastEnvelopeCodec.ParseResult.Failure -> {
                            notice = parsed.reason
                        }
                        is BroadcastEnvelopeCodec.ParseResult.Ok -> {
                            val env = parsed.envelope
                            val existing = dao.messages().any { it.id == env.id }
                            if (existing) {
                                notice = context.getString(R.string.broadcast_import_duplicate)
                            } else {
                                val now = System.currentTimeMillis()
                                val isNight = Jalali.isNightTehran(now)
                                val decision = Law15.decide(env.urgencyScore, isNight)
                                val status = when (decision) {
                                    Law15.Decision.NOTIFY_NOW -> "delivered"
                                    Law15.Decision.HOLD_FOR_MORNING -> "held"
                                    Law15.Decision.SILENT -> "delivered"
                                }
                                dao.insertMessage(
                                    BroadcastMessageEntity(
                                        id = env.id,
                                        senderName = env.senderName,
                                        body = env.body,
                                        urgencyScore = env.urgencyScore,
                                        sentAtEpoch = env.sentAtEpoch,
                                        receivedAtEpoch = now,
                                        origin = "imported",
                                        status = status,
                                    ),
                                )
                                importText = ""
                                notice = context.getString(R.string.broadcast_import_ok)
                                if (settings.notificationsEnabled &&
                                    settings.broadcastNotifications &&
                                    decision == Law15.Decision.NOTIFY_NOW
                                ) {
                                    Notifications.showBroadcast(
                                        context,
                                        env.senderName,
                                        env.urgencyScore >= 70,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(text = stringResource(R.string.broadcast_import_button), fontFamily = EcoFontFamily)
        }

        // ---- Members ----
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.broadcast_members_heading) + " — " +
                stringResource(
                    R.string.broadcast_members_count,
                    members.size.toString().toPersianDigits(),
                ),
            fontFamily = EcoFontFamily,
            fontSize = 16.sp * fontScale,
            modifier = Modifier.semantics { heading() },
        )
        for (member in members) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = member.displayName,
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    scope.launch { dao.deactivateMember(member.memberId) }
                }) {
                    Text(text = stringResource(R.string.broadcast_remove_member), fontFamily = EcoFontFamily)
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        TextField(
            value = memberName,
            onValueChange = { memberName = it },
            placeholder = {
                Text(text = stringResource(R.string.broadcast_member_name_hint), fontFamily = EcoFontFamily)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(6.dp))
        TextField(
            value = memberCode,
            onValueChange = { memberCode = it },
            placeholder = {
                Text(text = stringResource(R.string.broadcast_member_code_hint), fontFamily = EcoFontFamily)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                scope.launch {
                    val name = memberName.trim()
                    val code = memberCode.trim()
                    if (name.isEmpty() || code.isEmpty()) {
                        notice = context.getString(R.string.broadcast_member_invalid)
                        return@launch
                    }
                    if (dao.activeMemberCount() >= BROADCAST_MAX_MEMBERS) {
                        notice = context.getString(R.string.broadcast_member_full)
                        return@launch
                    }
                    // Only the hash is stored; the raw code is
                    // never persisted, never logged.
                    val hash = sha256Hex(code)
                    if (dao.memberById(hash) != null) {
                        notice = context.getString(R.string.broadcast_member_code_used)
                        return@launch
                    }
                    dao.insertMember(
                        BroadcastMemberEntity(
                            memberId = hash,
                            displayName = name.take(100),
                            codeHash = hash,
                            role = "recipient",
                            active = 1,
                            addedAtEpoch = System.currentTimeMillis(),
                        ),
                    )
                    memberName = ""
                    memberCode = ""
                    notice = context.getString(R.string.broadcast_member_added)
                }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(text = stringResource(R.string.broadcast_add_member), fontFamily = EcoFontFamily)
        }

        // ---- Messages ----
        Spacer(modifier = Modifier.height(16.dp))
        if (messages.isEmpty()) {
            Text(
                text = stringResource(R.string.broadcast_empty),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
        } else {
            OutlinedButton(onClick = {
                scope.launch { dao.deleteAllMessages() }
            }) {
                Text(text = stringResource(R.string.broadcast_delete_all), fontFamily = EcoFontFamily)
            }
        }
        for (message in messages) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = message.senderName,
                            fontFamily = EcoFontFamily,
                            fontSize = 14.sp * fontScale,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = Jalali.formatShamsiDateTime(message.sentAtEpoch),
                            fontFamily = EcoFontFamily,
                            fontSize = 12.sp * fontScale,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message.body,
                        fontFamily = EcoFontFamily,
                        fontSize = 15.sp * fontScale,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            message.status == "held" ->
                                stringResource(R.string.broadcast_held_badge)
                            message.origin == "imported" ->
                                stringResource(R.string.broadcast_imported_badge)
                            else -> stringResource(R.string.broadcast_local_badge)
                        },
                        fontFamily = EcoFontFamily,
                        fontSize = 12.sp * fontScale,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(onClick = {
                        scope.launch { dao.deleteMessage(message.id) }
                    }) {
                        Text(text = stringResource(R.string.broadcast_delete), fontFamily = EcoFontFamily)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
