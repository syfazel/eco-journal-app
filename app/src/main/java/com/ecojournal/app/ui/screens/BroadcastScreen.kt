package com.ecojournal.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
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
import com.ecojournal.app.data.BROADCAST_ENVELOPE_HEADER_V2
import com.ecojournal.app.data.BROADCAST_MAX_CHARS
import com.ecojournal.app.data.BROADCAST_MAX_MEMBERS
import com.ecojournal.app.data.BROADCAST_STALE_HOURS
import com.ecojournal.app.data.BROADCAST_TYPE_KEY_ANNOUNCE
import com.ecojournal.app.data.BROADCAST_TYPE_MEMBER_ADD
import com.ecojournal.app.data.BROADCAST_TYPE_MEMBER_REMOVE
import com.ecojournal.app.data.BROADCAST_TYPE_MSG
import com.ecojournal.app.data.BroadcastEnvelopeCodec
import com.ecojournal.app.data.BroadcastMemberEntity
import com.ecojournal.app.data.BroadcastMessageEntity
import com.ecojournal.app.data.BroadcastRules
import com.ecojournal.app.data.BroadcastSignedCodec
import com.ecojournal.app.data.DatabaseProvider
import com.ecojournal.app.data.InviteGate.sha256Hex
import com.ecojournal.app.data.Jalali
import com.ecojournal.app.data.Law15
import com.ecojournal.app.data.REJECT_BAD_SIGNATURE
import com.ecojournal.app.data.REJECT_MEMBER_FULL
import com.ecojournal.app.data.REJECT_OLD_LIST
import com.ecojournal.app.data.REJECT_REPLAY_SEQ
import com.ecojournal.app.data.REJECT_UNKNOWN_KEY
import com.ecojournal.app.data.Settings
import com.ecojournal.app.data.SettingsStore
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.util.Notifications
import com.ecojournal.app.util.toPersianDigits
import kotlinx.coroutines.launch

/**
 * Phase 6 (v5.5) — signed broadcast tab. Design:
 * files/tarahi-faz6.md.
 *
 * One-way broadcast to a closed group of up to 10 people. The app
 * makes NO network call here: the sender composes, the app stores
 * the message and builds a text envelope, and the SENDER shares
 * that envelope through a private channel they control (e.g. Bale).
 * A recipient pastes a received envelope into the import box.
 * Phase 6 signs envelopes (`ECOJ-BCAST/2`, HMAC-SHA256 with a
 * shared password the user types in Settings); v1 envelopes are
 * still read, unsigned, with an "unsigned" badge. A packet with
 * a bad signature, an unknown key, or a replayed seq is rejected
 * with a reason and counted (counts only, never content).
 * Member lists move by signed control packets (member-add /
 * member-remove) with a listVersion. Notifications follow Law 15
 * and never carry message content (lock-screen safety, G-DIAG-2).
 * Pruning (30 days / 200 messages) runs when this tab opens —
 * no worker, no polling (G-BATT-4). Locally composed messages
 * sit in the outbox as "unsent — waiting for channel" until the
 * sender marks them sent.
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
    val store = remember(context) { SettingsStore(context) }
    val messages by dao.messagesFlow().collectAsState(initial = emptyList())
    val members by dao.activeMembersFlow().collectAsState(initial = emptyList())

    // Phase 6 (v5.5): staleness + offline labels (design §1.1,
    // §2.4). Offline is a LOCAL connectivity check only — the
    // app still makes no network call for messaging.
    val connectivityManager = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    val offline = connectivityManager.activeNetwork == null
    val newestSent = messages.maxOfOrNull { it.sentAtEpoch }
    val staleHours = newestSent?.let {
        (System.currentTimeMillis() - it) / 3_600_000L
    }

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
        // Phase 6 (v5.5): staleness label in the tab header —
        // always visible, never silent about age (design §1.1).
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = when {
                newestSent == null ->
                    stringResource(R.string.broadcast_stale_none)
                staleHours != null && staleHours >= BROADCAST_STALE_HOURS ->
                    stringResource(
                        R.string.broadcast_stale_old,
                        staleHours.toString().toPersianDigits(),
                    )
                else -> stringResource(
                    R.string.broadcast_stale_last,
                    Jalali.formatShamsiDateTime(newestSent),
                    (staleHours ?: 0L).toString().toPersianDigits(),
                )
            },
            fontFamily = EcoFontFamily,
            fontSize = 14.sp * fontScale,
        )
        if (offline) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.broadcast_offline),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
        }
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
                    scope.launch {
                        val keyHex = settings.broadcastKeyHex
                        val keyVersion = settings.broadcastKeyVersion
                        val seq = BroadcastRules.nextSeq(dao, senderName)
                        if (keyHex != null && keyVersion >= 1) {
                            // Phase 6: signed v2 packet.
                            val built = BroadcastSignedCodec.encodeMsg(
                                senderName = senderName,
                                body = body,
                                score = scoreInt,
                                sentAtEpoch = now,
                                seq = seq,
                                keyVersion = keyVersion,
                                keyHex = keyHex,
                            )
                            if (built == null) return@launch
                            dao.insertMessage(
                                BroadcastMessageEntity(
                                    id = built.id,
                                    senderName = senderName,
                                    body = body,
                                    urgencyScore = scoreInt,
                                    sentAtEpoch = now,
                                    receivedAtEpoch = now,
                                    origin = "local",
                                    // Unsent until the sender
                                    // marks it sent (design §2.4).
                                    status = "unsent",
                                    seq = seq,
                                    keyVersion = keyVersion,
                                    packetType = BROADCAST_TYPE_MSG,
                                    signed = 1,
                                ),
                            )
                            BroadcastRules.acceptSeq(dao, senderName, seq, now)
                            composeBody = ""
                            shareEnvelope(context, built.text)
                        } else {
                            // No signing key yet: a v2 packet
                            // cannot be signed, so build v1 and
                            // say so honestly (Phase 6 report).
                            val envelope = BroadcastEnvelopeCodec.encode(
                                senderName, body, scoreInt, now,
                            )
                            val id = BroadcastEnvelopeCodec.contentId(
                                senderName, body, scoreInt, now,
                            )
                            dao.insertMessage(
                                BroadcastMessageEntity(
                                    id = id,
                                    senderName = senderName,
                                    body = body,
                                    urgencyScore = scoreInt,
                                    sentAtEpoch = now,
                                    receivedAtEpoch = now,
                                    origin = "local",
                                    status = "unsent",
                                ),
                            )
                            composeBody = ""
                            notice = context.getString(R.string.broadcast_no_key_note)
                            shareEnvelope(context, envelope)
                        }
                    }
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
                    val pasted = importText
                    val firstLine = pasted.trim().lineSequence().firstOrNull()?.trim()
                    if (firstLine == BROADCAST_ENVELOPE_HEADER_V2) {
                        val result = importSignedPacket(context, dao, store, settings, pasted)
                        notice = result
                        // Clear the box on any accepted packet
                        // (message or control); keep a rejected
                        // packet in the box so the user can see it.
                        if (!result.startsWith("پاکت رد شد")) {
                            importText = ""
                        }
                    } else {
                        when (val parsed = BroadcastEnvelopeCodec.parse(pasted)) {
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
                ) + " — " +
                stringResource(
                    R.string.broadcast_members_version,
                    settings.broadcastListVersion.toString().toPersianDigits(),
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
                    scope.launch {
                        dao.deactivateMember(member.memberId)
                        // Phase 6: the sender also sends a signed
                        // member-remove control packet so the
                        // other phones drop the member too.
                        val keyHex = settings.broadcastKeyHex
                        val keyVersion = settings.broadcastKeyVersion
                        if (settings.broadcastSender && keyHex != null && keyVersion >= 1) {
                            val senderName = settings.senderDisplayName.ifBlank { "یاسین" }
                            val now = System.currentTimeMillis()
                            val newListVersion = settings.broadcastListVersion + 1
                            val seq = BroadcastRules.nextSeq(dao, senderName)
                            val built = BroadcastSignedCodec.encodeMemberRemove(
                                senderName = senderName,
                                codeHash = member.codeHash,
                                listVersion = newListVersion,
                                sentAtEpoch = now,
                                seq = seq,
                                keyVersion = keyVersion,
                                keyHex = keyHex,
                            )
                            if (built != null) {
                                store.setBroadcastListVersion(newListVersion)
                                BroadcastRules.acceptSeq(dao, senderName, seq, now)
                                notice = context.getString(R.string.broadcast_control_shared)
                                shareEnvelope(context, built.text)
                            }
                        }
                    }
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
                    // Phase 6: the sender also sends a signed
                    // member-add control packet so the other
                    // phones add the member too (design §1.3).
                    val keyHex = settings.broadcastKeyHex
                    val keyVersion = settings.broadcastKeyVersion
                    if (settings.broadcastSender && keyHex != null && keyVersion >= 1) {
                        val senderName = settings.senderDisplayName.ifBlank { "یاسین" }
                        val now = System.currentTimeMillis()
                        val newListVersion = settings.broadcastListVersion + 1
                        val seq = BroadcastRules.nextSeq(dao, senderName)
                        val built = BroadcastSignedCodec.encodeMemberAdd(
                            senderName = senderName,
                            memberName = name,
                            codeHash = hash,
                            listVersion = newListVersion,
                            sentAtEpoch = now,
                            seq = seq,
                            keyVersion = keyVersion,
                            keyHex = keyHex,
                        )
                        if (built != null) {
                            store.setBroadcastListVersion(newListVersion)
                            BroadcastRules.acceptSeq(dao, senderName, seq, now)
                            notice = context.getString(R.string.broadcast_control_shared)
                            shareEnvelope(context, built.text)
                            return@launch
                        }
                    }
                    notice = context.getString(R.string.broadcast_member_added)
                }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(text = stringResource(R.string.broadcast_add_member), fontFamily = EcoFontFamily)
        }

        // ---- Key rotation announcement (Phase 6, design §3.4) ----
        // Sender only, and only once a signing key exists: build
        // a key-announce packet (signed with the CURRENT key)
        // for version + 1. The new password itself is never in
        // the packet — it travels out-of-band via Bale.
        if (settings.broadcastSender &&
            settings.broadcastKeyHex != null && settings.broadcastKeyVersion >= 1
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.broadcast_key_announce_note),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    val keyHex = settings.broadcastKeyHex ?: return@launch
                    val keyVersion = settings.broadcastKeyVersion
                    val senderName = settings.senderDisplayName.ifBlank { "یاسین" }
                    val now = System.currentTimeMillis()
                    val seq = BroadcastRules.nextSeq(dao, senderName)
                    val built = BroadcastSignedCodec.encodeKeyAnnounce(
                        senderName = senderName,
                        newKeyVersion = keyVersion + 1,
                        sentAtEpoch = now,
                        seq = seq,
                        keyVersion = keyVersion,
                        keyHex = keyHex,
                    )
                    if (built != null) {
                        BroadcastRules.acceptSeq(dao, senderName, seq, now)
                        notice = context.getString(R.string.broadcast_control_shared)
                        shareEnvelope(context, built.text)
                    }
                }
            }) {
                Text(text = stringResource(R.string.broadcast_key_announce_button), fontFamily = EcoFontFamily)
            }
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
                            message.status == "unsent" ->
                                stringResource(R.string.broadcast_unsent_badge)
                            message.status == "held" ->
                                stringResource(R.string.broadcast_held_badge)
                            message.origin == "imported" && message.signed == 0 ->
                                stringResource(R.string.broadcast_unsigned_badge)
                            message.origin == "imported" ->
                                stringResource(R.string.broadcast_imported_badge)
                            else -> stringResource(R.string.broadcast_local_badge)
                        },
                        fontFamily = EcoFontFamily,
                        fontSize = 12.sp * fontScale,
                    )
                    if (message.origin == "local" && message.status == "unsent") {
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(onClick = {
                            scope.launch { dao.setMessageStatus(message.id, "delivered") }
                        }) {
                            Text(text = stringResource(R.string.broadcast_mark_sent), fontFamily = EcoFontFamily)
                        }
                    }
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

/**
 * Phase 6 (v5.5) — hand a built envelope to the system share
 * sheet. The app never sends anything itself: the sender picks
 * the private channel (Bale, ...) in the sheet.
 */
private fun shareEnvelope(context: Context, text: String) {
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(share, null))
}

private fun signingKeysOf(settings: Settings): BroadcastSignedCodec.SigningKeys =
    BroadcastSignedCodec.SigningKeys(
        currentHex = settings.broadcastKeyHex,
        currentVersion = settings.broadcastKeyVersion,
        prevHex = settings.broadcastPrevKeyHex,
        prevVersion = settings.broadcastPrevKeyVersion,
        prevUntilEpoch = settings.broadcastPrevKeyUntil ?: 0L,
    )

/**
 * Phase 6 (v5.5) — import one pasted v2 packet. Returns the
 * notice to show. Order of checks (design §1.2, §3.4):
 * structure -> size -> known key -> HMAC -> replay seq -> dedup
 * -> apply. Every rejection is counted by fixed reason code
 * (never content) and the packet is never stored.
 */
private suspend fun importSignedPacket(
    context: Context,
    dao: com.ecojournal.app.data.BroadcastDao,
    store: SettingsStore,
    settings: Settings,
    pasted: String,
): String {
    val now = System.currentTimeMillis()
    val keys = signingKeysOf(settings)
    return when (val parsed = BroadcastSignedCodec.parse(pasted)) {
        is BroadcastSignedCodec.ParseResult.Failure -> {
            BroadcastRules.recordReject(dao, parsed.reason)
            "پاکت رد شد: ${parsed.message}"
        }
        is BroadcastSignedCodec.ParseResult.Msg -> {
            val env = parsed.envelope
            val canonical = env.canonical
            val hmac = env.hmac
            if (canonical == null || hmac == null) {
                BroadcastRules.recordReject(dao, REJECT_BAD_SIGNATURE)
                return "پاکت رد شد: امضا نامعتبر است."
            }
            val known = BroadcastSignedCodec.isKnownKeyVersion(env.keyVersion, keys, now)
            val matched = BroadcastSignedCodec.verify(canonical, hmac, env.keyVersion, keys, now)
            if (matched == null) {
                if (!known) {
                    BroadcastRules.recordReject(dao, REJECT_UNKNOWN_KEY)
                    return "پاکت رد شد: کلیدِ این پاکت روی گوشی نیست — گذرواژهٔ تازه لازم است."
                }
                BroadcastRules.recordReject(dao, REJECT_BAD_SIGNATURE)
                return "پاکت رد شد: امضا نامعتبر است."
            }
            val lastSeq = dao.senderState(env.senderName)?.lastSeq ?: 0L
            if (env.seq <= lastSeq) {
                BroadcastRules.recordReject(dao, REJECT_REPLAY_SEQ)
                return "پاکت رد شد: شمارهٔ ترتیبی تکراری است."
            }
            if (dao.messages().any { it.id == env.id }) {
                return context.getString(R.string.broadcast_import_duplicate)
            }
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
                    seq = env.seq,
                    keyVersion = env.keyVersion,
                    packetType = BROADCAST_TYPE_MSG,
                    signed = 1,
                ),
            )
            BroadcastRules.acceptSeq(dao, env.senderName, env.seq, now)
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
            context.getString(R.string.broadcast_import_ok)
        }
        is BroadcastSignedCodec.ParseResult.Control -> {
            val pkt = parsed.packet
            val known = BroadcastSignedCodec.isKnownKeyVersion(pkt.keyVersion, keys, now)
            val matched = BroadcastSignedCodec.verify(pkt.canonical, pkt.hmac, pkt.keyVersion, keys, now)
            if (matched == null) {
                if (!known) {
                    BroadcastRules.recordReject(dao, REJECT_UNKNOWN_KEY)
                    return "پاکت رد شد: کلیدِ این پاکت روی گوشی نیست — گذرواژهٔ تازه لازم است."
                }
                BroadcastRules.recordReject(dao, REJECT_BAD_SIGNATURE)
                return "پاکت رد شد: امضا نامعتبر است."
            }
            val lastSeq = dao.senderState(pkt.from)?.lastSeq ?: 0L
            if (pkt.seq <= lastSeq) {
                BroadcastRules.recordReject(dao, REJECT_REPLAY_SEQ)
                return "پاکت رد شد: شمارهٔ ترتیبی تکراری است."
            }
            BroadcastRules.acceptSeq(dao, pkt.from, pkt.seq, now)
            when (pkt.type) {
                BROADCAST_TYPE_MEMBER_ADD -> {
                    val listVersion = pkt.listVersion ?: 0L
                    val codeHash = pkt.codeHash ?: ""
                    if (listVersion <= settings.broadcastListVersion) {
                        BroadcastRules.recordReject(dao, REJECT_OLD_LIST)
                        return "پاکت رد شد: نسخهٔ فهرست قدیمی‌تر است."
                    }
                    val existing = dao.memberById(codeHash)
                    if (existing == null) {
                        if (dao.activeMemberCount() >= BROADCAST_MAX_MEMBERS) {
                            BroadcastRules.recordReject(dao, REJECT_MEMBER_FULL)
                            return context.getString(R.string.broadcast_member_full)
                        }
                        dao.insertMember(
                            BroadcastMemberEntity(
                                memberId = codeHash,
                                displayName = (pkt.memberName ?: "").take(100),
                                codeHash = codeHash,
                                role = "recipient",
                                active = 1,
                                addedAtEpoch = now,
                            ),
                        )
                    } else if (existing.active == 0) {
                        dao.activateMember(codeHash)
                    }
                    store.setBroadcastListVersion(listVersion)
                    context.getString(R.string.broadcast_member_added)
                }
                BROADCAST_TYPE_MEMBER_REMOVE -> {
                    val listVersion = pkt.listVersion ?: 0L
                    val codeHash = pkt.codeHash ?: ""
                    if (listVersion <= settings.broadcastListVersion) {
                        BroadcastRules.recordReject(dao, REJECT_OLD_LIST)
                        return "پاکت رد شد: نسخهٔ فهرست قدیمی‌تر است."
                    }
                    // Unknown hash = already removed (or never
                    // added): still advance the version so lists
                    // stay in sync; the member is simply absent.
                    dao.memberById(codeHash)?.let { dao.deactivateMember(codeHash) }
                    store.setBroadcastListVersion(listVersion)
                    "عضو حذف شد."
                }
                BROADCAST_TYPE_KEY_ANNOUNCE -> {
                    val newVersion = pkt.newKeyVersion ?: 0
                    store.setBroadcastAnnouncedKeyVersion(newVersion)
                    context.getString(
                        R.string.broadcast_key_announced,
                        newVersion.toString().toPersianDigits(),
                    )
                }
                else -> "پاکت رد شد: نوعِ پاکت نامعتبر است."
            }
        }
    }
}
