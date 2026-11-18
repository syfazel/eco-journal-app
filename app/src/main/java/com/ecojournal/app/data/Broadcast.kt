package com.ecojournal.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.ecojournal.app.data.InviteGate.sha256Hex
import kotlinx.coroutines.flow.Flow

/**
 * Phase 5 (v5.4) — one-way broadcast messaging to a closed group
 * of up to 10 people. Design: files/tarahi-payamresani-faz5.md.
 *
 * The hard rules this file enforces:
 * - Messages live ONLY on the device (Room). The app makes NO
 *   network call for messaging: no FCM (G-PRIV-4), no message file
 *   on the public GitHub Pages edge (G-PRIV-3 — a public URL would
 *   leak private messages to anyone), no third-party service.
 * - Transport is MANUAL store-and-forward (design §7, option A):
 *   the sender shares a text envelope through a private channel
 *   THEY already control (e.g. Bale); the recipient pastes the
 *   envelope into the app. The app never sends anything itself.
 * - No DMs, no replies, no read receipts (G-PRIV-2). Broadcast
 *   only, to a closed member list that NEVER shares data with the
 *   ECO JOURNAL invite gate (separate hashes, separate list).
 * - Notifications follow Law 15 (G-PRIV-5): night (00-08 Tehran)
 *   only >= 85 is instant; 70-84 is held for the morning bundle;
 *   < 70 at night is silent. No new worker, no new polling
 *   (G-BATT-4): held messages are released on the next app launch
 *   after 08:00 Tehran.
 * - Caps (design §1): 10 active members, 2,000 chars per message,
 *   200 stored messages, 30-day retention. Pruning happens when
 *   the tab opens, never in a background worker.
 */

const val BROADCAST_MAX_MEMBERS = 10
const val BROADCAST_MAX_CHARS = 2000
const val BROADCAST_MAX_STORED = 200
const val BROADCAST_RETENTION_DAYS = 30L

/** Envelope header line. Any pasted text not starting with this
 * exact line is not a broadcast envelope and is rejected. */
const val BROADCAST_ENVELOPE_HEADER = "ECOJ-BCAST/1"

/** Message status: delivered (shown, notified or silent),
 * held (night 70-84, waiting for the morning bundle), read,
 * unsent (Phase 6: composed on this device, not yet shared —
 * the local outbox, "waiting for channel"). */
@Entity(tableName = "broadcast_messages")
data class BroadcastMessageEntity(
    /** SHA-256 of the canonical envelope content — identity and
     * dedup key. Two copies of the same envelope are one row. */
    @PrimaryKey val id: String,
    val senderName: String,
    val body: String,
    /** Urgency score 0-100, chosen by the sender. Law 15 reads it. */
    val urgencyScore: Int,
    val sentAtEpoch: Long,
    val receivedAtEpoch: Long,
    /** "local" (composed on this device) or "imported" (pasted in). */
    val origin: String,
    /** "delivered" | "held" | "read" | "unsent". */
    val status: String,
    /** Phase 6 (v5.5): v2 packet fields. v1 rows keep the
     * defaults (seq 0, keyVersion 0, packetType "msg",
     * signed 0 = unsigned). */
    val seq: Long = 0L,
    val keyVersion: Int = 0,
    val packetType: String = BROADCAST_TYPE_MSG,
    /** 1 = v2 packet whose HMAC verified, 0 = v1 unsigned. */
    val signed: Int = 0,
)

/** One member of the closed broadcast group. The raw membership
 * code is NEVER stored — only its SHA-256 (same rule as the
 * invite gate, M7). [memberId] IS that hash. */
@Entity(tableName = "broadcast_members")
data class BroadcastMemberEntity(
    @PrimaryKey val memberId: String,
    val displayName: String,
    val codeHash: String,
    /** "sender" or "recipient". */
    val role: String,
    /** 1 = active (counts toward the 10), 0 = removed. A removed
     * row is kept so its code hash can never be re-used. */
    val active: Int,
    val addedAtEpoch: Long,
)

@Dao
interface BroadcastDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: BroadcastMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMember(member: BroadcastMemberEntity): Long

    @Query("SELECT * FROM broadcast_messages ORDER BY sentAtEpoch DESC")
    fun messagesFlow(): Flow<List<BroadcastMessageEntity>>

    @Query("SELECT * FROM broadcast_messages ORDER BY sentAtEpoch DESC")
    suspend fun messages(): List<BroadcastMessageEntity>

    @Query("SELECT COUNT(*) FROM broadcast_messages")
    suspend fun messageCount(): Int

    @Query("SELECT COUNT(*) FROM broadcast_messages WHERE status = 'held'")
    suspend fun heldCount(): Int

    @Query("SELECT * FROM broadcast_messages WHERE status = 'held' ORDER BY sentAtEpoch ASC")
    suspend fun heldMessages(): List<BroadcastMessageEntity>

    @Query("UPDATE broadcast_messages SET status = :status WHERE id = :id")
    suspend fun setMessageStatus(id: String, status: String)

    @Query("DELETE FROM broadcast_messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query("DELETE FROM broadcast_messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM broadcast_messages WHERE sentAtEpoch < :olderThanEpoch")
    suspend fun deleteOlderThan(olderThanEpoch: Long): Int

    @Query("DELETE FROM broadcast_messages WHERE id IN (SELECT id FROM broadcast_messages WHERE status = 'read' ORDER BY sentAtEpoch ASC LIMIT :excess)")
    suspend fun deleteOldestRead(excess: Int): Int

    @Query("SELECT * FROM broadcast_members WHERE active = 1 ORDER BY displayName ASC")
    fun activeMembersFlow(): Flow<List<BroadcastMemberEntity>>

    @Query("SELECT * FROM broadcast_members ORDER BY displayName ASC")
    suspend fun allMembers(): List<BroadcastMemberEntity>

    @Query("SELECT COUNT(*) FROM broadcast_members WHERE active = 1")
    suspend fun activeMemberCount(): Int

    @Query("SELECT * FROM broadcast_members WHERE memberId = :memberId LIMIT 1")
    suspend fun memberById(memberId: String): BroadcastMemberEntity?

    @Query("UPDATE broadcast_members SET active = 0 WHERE memberId = :memberId")
    suspend fun deactivateMember(memberId: String)

    /** Phase 6: re-activate a previously removed member (its
     * code hash was kept so it can never be re-used by another
     * row). Used when a member-add control packet names a hash
     * that was removed earlier. */
    @Query("UPDATE broadcast_members SET active = 1 WHERE memberId = :memberId")
    suspend fun activateMember(memberId: String)

    /** Phase 6 (v5.5): per-sender replay state — the highest
     * accepted v2 `seq` from this sender. A packet with seq <=
     * this is a replay and is rejected. */
    @Query("SELECT * FROM broadcast_sender_state WHERE senderName = :name LIMIT 1")
    suspend fun senderState(name: String): BroadcastSenderStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSenderState(state: BroadcastSenderStateEntity)

    /** Phase 6 (v5.5): rejected-packet counts by FIXED reason
     * code only — never packet content (G-DIAG-2). */
    @Query("SELECT * FROM broadcast_reject_counts ORDER BY reason ASC")
    suspend fun rejectCounts(): List<BroadcastRejectCountEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRejectIgnore(row: BroadcastRejectCountEntity): Long

    @Query("UPDATE broadcast_reject_counts SET rejectCount = rejectCount + 1 WHERE reason = :reason")
    suspend fun incrementReject(reason: String)
}

/** Phase 6 (v5.5): last accepted v2 seq per sender (replay
 * protection, design §1.2). */
@Entity(tableName = "broadcast_sender_state")
data class BroadcastSenderStateEntity(
    @PrimaryKey val senderName: String,
    val lastSeq: Long,
    val lastSeenEpoch: Long,
)

/** Phase 6 (v5.5): how many packets were rejected, per fixed
 * reason code. Counts only — no content, no names. */
@Entity(tableName = "broadcast_reject_counts")
data class BroadcastRejectCountEntity(
    @PrimaryKey val reason: String,
    val rejectCount: Int,
)

/**
 * Law 15 (G-PRIV-5) as a pure function, so it is testable without
 * Android: night is 00:00-08:00 Tehran, decided by the caller from
 * [Jalali.isNightTehran].
 */
object Law15 {
    enum class Decision { NOTIFY_NOW, HOLD_FOR_MORNING, SILENT }

    fun decide(score: Int, isNight: Boolean): Decision {
        val s = score.coerceIn(0, 100)
        return when {
            !isNight -> Decision.NOTIFY_NOW
            s >= 85 -> Decision.NOTIFY_NOW
            s >= 70 -> Decision.HOLD_FOR_MORNING
            else -> Decision.SILENT
        }
    }
}

/** A parsed broadcast envelope. [id] is the content hash, so the
 * same envelope pasted twice is the same message. Phase 6 adds
 * the v2 fields (defaults describe a v1 unsigned envelope). */
data class BroadcastEnvelope(
    val id: String,
    val senderName: String,
    val body: String,
    val urgencyScore: Int,
    val sentAtEpoch: Long,
    val packetVersion: Int = 1,
    val seq: Long = 0L,
    val keyVersion: Int = 0,
    val packetType: String = BROADCAST_TYPE_MSG,
    val signed: Boolean = false,
    /** Phase 6: the v2 HMAC and canonical string, carried so the
     * caller can verify the signature. Null for v1 envelopes. */
    val hmac: String? = null,
    val canonical: String? = null,
)

object BroadcastEnvelopeCodec {
    /**
     * Build the shareable text envelope for a composed message.
     * Plain text only — it travels through whatever private
     * channel the sender chooses (Bale, ...). The app itself
     * never sends it anywhere.
     */
    fun encode(senderName: String, body: String, score: Int, sentAtEpoch: Long): String {
        val cleanName = senderName.trim().take(100).ifBlank { "یاسین" }
        val cleanBody = body.trim().take(BROADCAST_MAX_CHARS)
        val cleanScore = score.coerceIn(0, 100)
        return buildString {
            appendLine(BROADCAST_ENVELOPE_HEADER)
            appendLine("from: $cleanName")
            appendLine("sent: $sentAtEpoch")
            appendLine("score: $cleanScore")
            appendLine()
            append(cleanBody)
        }
    }

    fun contentId(senderName: String, body: String, score: Int, sentAtEpoch: Long): String {
        return sha256Hex(
            "ECOJ-BCAST/1|$senderName|$sentAtEpoch|$score|$body",
        )
    }

    /** Parse pasted text. Returns null with a reason in
     * [ParseFailure.reason] — never throws, never stores a bad
     * envelope. */
    sealed class ParseResult {
        data class Ok(val envelope: BroadcastEnvelope) : ParseResult()
        data class Failure(val reason: String) : ParseResult()
    }

    fun parse(raw: String?): ParseResult {
        if (raw.isNullOrBlank()) {
            return ParseResult.Failure("متنِ خالی چسبانده شده است.")
        }
        val lines = raw.trim().lines()
        if (lines.isEmpty() || lines[0].trim() != BROADCAST_ENVELOPE_HEADER) {
            return ParseResult.Failure(
                "این متن، پاکتِ پخش نیست — باید با «$BROADCAST_ENVELOPE_HEADER» شروع شود.",
            )
        }
        var senderName: String? = null
        var sentAt: Long? = null
        var score: Int? = null
        var i = 1
        while (i < lines.size && lines[i].isNotBlank()) {
            val line = lines[i]
            when {
                line.startsWith("from: ") -> senderName = line.removePrefix("from: ").trim()
                line.startsWith("sent: ") -> sentAt = line.removePrefix("sent: ").trim().toLongOrNull()
                line.startsWith("score: ") -> score = line.removePrefix("score: ").trim().toIntOrNull()
                else -> return ParseResult.Failure("سرفصلِ ناشناخته در پاکت: «$line»")
            }
            i++
        }
        if (senderName.isNullOrBlank()) {
            return ParseResult.Failure("پاکت نامِ فرستنده ندارد.")
        }
        if (sentAt == null || sentAt <= 0) {
            return ParseResult.Failure("پاکت زمانِ معتبر ندارد.")
        }
        if (score == null || score !in 0..100) {
            return ParseResult.Failure("امتیازِ فوریتِ پاکت باید بین ۰ تا ۱۰۰ باشد.")
        }
        // Skip the blank separator line(s).
        while (i < lines.size && lines[i].isBlank()) i++
        val body = lines.drop(i).joinToString("\n").trim()
        if (body.isEmpty()) {
            return ParseResult.Failure("پاکت متنِ پیام ندارد.")
        }
        if (body.length > BROADCAST_MAX_CHARS) {
            return ParseResult.Failure(
                "متنِ پیام ${body.length} نویسه است؛ سقف $BROADCAST_MAX_CHARS نویسه است.",
            )
        }
        return ParseResult.Ok(
            BroadcastEnvelope(
                id = contentId(senderName, body, score, sentAt),
                senderName = senderName.take(100),
                body = body,
                urgencyScore = score,
                sentAtEpoch = sentAt,
            ),
        )
    }
}

/**
 * Small repository of the non-UI rules: pruning and the morning
 * bundle release. No network, no worker — both run only when the
 * user opens the app / the tab.
 */
object BroadcastRules {
    /** Delete messages older than 30 days and, if still over the
     * 200 cap, the oldest read ones. Returns how many were pruned. */
    suspend fun prune(dao: BroadcastDao, nowEpoch: Long): Int {
        val cutoff = nowEpoch - BROADCAST_RETENTION_DAYS * 24L * 60L * 60L * 1000L
        var pruned = dao.deleteOlderThan(cutoff)
        val count = dao.messageCount()
        if (count > BROADCAST_MAX_STORED) {
            pruned += dao.deleteOldestRead(count - BROADCAST_MAX_STORED)
        }
        return pruned
    }

    /**
     * Phase 6 (v5.5): count one rejected packet by fixed reason
     * code. Two steps (insert-if-absent, then increment) because
     * SQLite UPSERT needs API 30+ and minSdk is 26. Counts only.
     */
    suspend fun recordReject(dao: BroadcastDao, reason: String) {
        dao.insertRejectIgnore(BroadcastRejectCountEntity(reason, 0))
        dao.incrementReject(reason)
    }

    /**
     * Phase 6 (v5.5): next seq for a sender — last accepted seq
     * + 1, starting at 1. Used for packets this device builds.
     */
    suspend fun nextSeq(dao: BroadcastDao, senderName: String): Long {
        val state = dao.senderState(senderName)
        return (state?.lastSeq ?: 0L) + 1L
    }

    /**
     * Phase 6 (v5.5): record an accepted v2 seq for a sender
     * (replay protection). Only ever moves forward.
     */
    suspend fun acceptSeq(dao: BroadcastDao, senderName: String, seq: Long, nowEpoch: Long) {
        val state = dao.senderState(senderName)
        val last = state?.lastSeq ?: 0L
        if (seq > last) {
            dao.upsertSenderState(
                BroadcastSenderStateEntity(
                    senderName = senderName,
                    lastSeq = seq,
                    lastSeenEpoch = nowEpoch,
                ),
            )
        }
    }

    /**
     * Morning bundle (Law 15, no worker): on app launch after
     * 08:00 Tehran, held (70-84, night) messages become delivered
     * and are returned so the caller can fire ONE bundle
     * notification. Before 08:00, or with nothing held, returns
     * an empty list and notifies nothing.
     */
    suspend fun releaseMorningBundle(dao: BroadcastDao, nowEpoch: Long): List<BroadcastMessageEntity> {
        if (Jalali.isNightTehran(nowEpoch)) return emptyList()
        val held = dao.heldMessages()
        if (held.isEmpty()) return emptyList()
        for (message in held) {
            dao.setMessageStatus(message.id, "delivered")
        }
        return held
    }
}
