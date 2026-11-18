package com.ecojournal.app.data

import com.ecojournal.app.data.InviteGate.sha256Hex
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 6 (v5.5) — signed broadcast envelopes `ECOJ-BCAST/2`.
 * Design: files/tarahi-faz6.md §1.2, §3.4 (Q6-1 transport A:
 * signed-manual, Q6-3 shared-password HMAC, Q6-4 key rotation,
 * Q6-5 member control packets).
 *
 * What this file does and does not do:
 * - The shared password is NEVER stored, logged, or written into
 *   any packet. Only the PBKDF2-derived key (hex) is stored, in
 *   SettingsStore, after the user types the password on their
 *   own phone (H-2). The password itself lives only in the
 *   text field until the save tap, then the field is cleared.
 * - Signing is HMAC-SHA256 over a canonical field string with
 *   the derived key. A v2 packet with a bad signature is
 *   rejected, never stored, and the rejection is counted (reason
 *   code only — never packet content, G-DIAG-2).
 * - Replay protection: `seq` is a per-sender counter starting
 *   at 1. A packet whose seq is <= the last seen seq from the
 *   same sender is rejected.
 * - v1 packets (`ECOJ-BCAST/1`) are still READ (unsigned, with
 *   an "unsigned" badge) but never built any more once a key
 *   is set; without a key the sender still builds v1 with an
 *   explicit "unsigned" warning, because a v2 packet cannot be
 *   signed without a key (decision recorded in the Phase 6
 *   report).
 * - NO network here: no FCM, no worker, no polling, no web
 *   calls at all. Packets travel only through the private
 *   channel the sender controls, exactly as in Phase 5.
 */

const val BROADCAST_ENVELOPE_HEADER_V2 = "ECOJ-BCAST/2"

/** PBKDF2-HMAC-SHA256, 100,000 iterations (design §3.4). Salt is
 * the fixed prefix plus the key version, so rotating the key
 * (keyVersion + 1) derives a different key from the same text. */
const val BROADCAST_PBKDF2_ITERATIONS = 100_000
const val BROADCAST_PBKDF2_SALT_PREFIX = "ECOJ-BCAST"

/** Packet size cap (Q6-13): measured, not guessed. A signed
 * packet with a 2,000-char Persian body is 4,157 bytes; the
 * absolute worst case (2,000 x 4-byte chars) is 8,157 bytes, so
 * 8,192 bytes holds every legal packet. See Phase 6 report. */
const val BROADCAST_MAX_PACKET_BYTES = 8192

/** Old signing key is kept this long after rotation, for packets
 * already in flight (design §3.4). */
const val BROADCAST_KEY_GRACE_DAYS = 7L

/** Staleness threshold for the broadcast tab header (design
 * §1.1, U-6): same 24h as the Yemen tab. */
const val BROADCAST_STALE_HOURS = 24L

/** Packet types (design §1.2). */
const val BROADCAST_TYPE_MSG = "msg"
const val BROADCAST_TYPE_MEMBER_ADD = "member-add"
const val BROADCAST_TYPE_MEMBER_REMOVE = "member-remove"
const val BROADCAST_TYPE_KEY_ANNOUNCE = "key-announce"

/** Fixed rejection reason codes. Only these codes are stored /
 * shown in Diagnostics — never packet content, never a name,
 * never a code, never a password or a derived key. */
const val REJECT_BAD_FORMAT = "bad_format"
const val REJECT_TOO_LARGE = "too_large"
const val REJECT_BAD_SIGNATURE = "bad_signature"
const val REJECT_UNKNOWN_KEY = "unknown_key"
const val REJECT_REPLAY_SEQ = "replay_seq"
const val REJECT_OLD_LIST = "old_list"
const val REJECT_MEMBER_FULL = "member_full"

fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }

fun hexToBytesOrNull(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return try {
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Derive the signing key from the shared password. The password
 * itself is never returned, never stored, never logged — only
 * this hex string leaves this function.
 */
fun deriveSigningKeyHex(password: String, keyVersion: Int): String {
    val salt = (BROADCAST_PBKDF2_SALT_PREFIX + keyVersion)
        .toByteArray(Charsets.UTF_8)
    val spec = PBEKeySpec(
        password.toCharArray(),
        salt,
        BROADCAST_PBKDF2_ITERATIONS,
        256,
    )
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    return bytesToHex(factory.generateSecret(spec).encoded)
}

fun hmacSha256Hex(keyHex: String, canonical: String): String? {
    val keyBytes = hexToBytesOrNull(keyHex) ?: return null
    return try {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        bytesToHex(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
    } catch (_: Exception) {
        null
    }
}

/**
 * Canonical field string the HMAC covers (design §3.4): header
 * + type + seq + keyVersion + sender + sent epoch + score (empty
 * for control packets) + payload. Built from parsed field
 * values, never by re-splitting packet text, so a payload that
 * contains newlines cannot change what is signed.
 */
fun canonicalPacketString(
    type: String,
    seq: Long,
    keyVersion: Int,
    from: String,
    sentAtEpoch: Long,
    score: Int?,
    payload: String,
): String = buildString {
    appendLine(BROADCAST_ENVELOPE_HEADER_V2)
    appendLine("type=$type")
    appendLine("seq=$seq")
    appendLine("key=$keyVersion")
    appendLine("from=$from")
    appendLine("sent=$sentAtEpoch")
    appendLine("score=${score?.toString() ?: ""}")
    append("payload=$payload")
}

/** Full-content SHA-256 over the canonical string: the message
 * identity / dedup key for v2 packets. */
fun packetContentId(canonical: String): String = sha256Hex(canonical)

/** A built v2 packet: the shareable text plus its content id. */
data class BuiltPacket(val text: String, val id: String)

private fun buildPacketText(
    type: String,
    seq: Long,
    keyVersion: Int,
    from: String,
    sentAtEpoch: Long,
    score: Int?,
    payload: String,
    keyHex: String,
): BuiltPacket? {
    val canonical = canonicalPacketString(
        type, seq, keyVersion, from, sentAtEpoch, score, payload,
    )
    val hmac = hmacSha256Hex(keyHex, canonical) ?: return null
    val text = buildString {
        appendLine(BROADCAST_ENVELOPE_HEADER_V2)
        appendLine("type: $type")
        appendLine("seq: $seq")
        appendLine("key: $keyVersion")
        appendLine("from: $from")
        appendLine("sent: $sentAtEpoch")
        if (score != null) appendLine("score: $score")
        appendLine("hmac: $hmac")
        appendLine()
        append(payload)
    }
    return BuiltPacket(text = text, id = packetContentId(canonical))
}

object BroadcastSignedCodec {
    fun encodeMsg(
        senderName: String,
        body: String,
        score: Int,
        sentAtEpoch: Long,
        seq: Long,
        keyVersion: Int,
        keyHex: String,
    ): BuiltPacket? {
        val cleanName = senderName.trim().take(100).ifBlank { "یاسین" }
        val cleanBody = body.trim().take(BROADCAST_MAX_CHARS)
        if (cleanBody.isEmpty()) return null
        return buildPacketText(
            type = BROADCAST_TYPE_MSG,
            seq = seq,
            keyVersion = keyVersion,
            from = cleanName,
            sentAtEpoch = sentAtEpoch,
            score = score.coerceIn(0, 100),
            payload = cleanBody,
            keyHex = keyHex,
        )
    }

    fun encodeMemberAdd(
        senderName: String,
        memberName: String,
        codeHash: String,
        listVersion: Long,
        sentAtEpoch: Long,
        seq: Long,
        keyVersion: Int,
        keyHex: String,
    ): BuiltPacket? = buildPacketText(
        type = BROADCAST_TYPE_MEMBER_ADD,
        seq = seq,
        keyVersion = keyVersion,
        from = senderName.trim().take(100).ifBlank { "یاسین" },
        sentAtEpoch = sentAtEpoch,
        score = null,
        payload = "name: ${memberName.trim().take(100)}\n" +
            "codehash: $codeHash\nlist: $listVersion",
        keyHex = keyHex,
    )

    fun encodeMemberRemove(
        senderName: String,
        codeHash: String,
        listVersion: Long,
        sentAtEpoch: Long,
        seq: Long,
        keyVersion: Int,
        keyHex: String,
    ): BuiltPacket? = buildPacketText(
        type = BROADCAST_TYPE_MEMBER_REMOVE,
        seq = seq,
        keyVersion = keyVersion,
        from = senderName.trim().take(100).ifBlank { "یاسین" },
        sentAtEpoch = sentAtEpoch,
        score = null,
        payload = "codehash: $codeHash\nlist: $listVersion",
        keyHex = keyHex,
    )

    /** key-announce (design §3.4): signed with the OLD key,
     * announces that keyVersion [newKeyVersion] is coming. The
     * new password itself is never in the packet — it travels
     * out-of-band via Bale. */
    fun encodeKeyAnnounce(
        senderName: String,
        newKeyVersion: Int,
        sentAtEpoch: Long,
        seq: Long,
        keyVersion: Int,
        keyHex: String,
    ): BuiltPacket? = buildPacketText(
        type = BROADCAST_TYPE_KEY_ANNOUNCE,
        seq = seq,
        keyVersion = keyVersion,
        from = senderName.trim().take(100).ifBlank { "یاسین" },
        sentAtEpoch = sentAtEpoch,
        score = null,
        payload = "newkey: $newKeyVersion",
        keyHex = keyHex,
    )

    /** A parsed v2 control packet (member-add / member-remove /
     * key-announce). */
    data class ControlPacket(
        val type: String,
        val seq: Long,
        val keyVersion: Int,
        val from: String,
        val sentAtEpoch: Long,
        val hmac: String,
        val canonical: String,
        val id: String,
        val memberName: String?,
        val codeHash: String?,
        val listVersion: Long?,
        val newKeyVersion: Int?,
    )

    sealed class ParseResult {
        data class Msg(val envelope: BroadcastEnvelope) : ParseResult()
        data class Control(val packet: ControlPacket) : ParseResult()
        data class Failure(val reason: String, val message: String) : ParseResult()
    }

    /**
     * Parse pasted v2 text. Structural validation only — the
     * signature is checked by [verify] with the stored keys, and
     * the seq replay check is the caller's job (it needs the
     * sender-state table). Returns null with a fixed reason in
     * [ParseResult.Failure] — never throws, never stores a bad
     * packet.
     */
    fun parse(raw: String?): ParseResult {
        if (raw.isNullOrBlank()) {
            return ParseResult.Failure(
                REJECT_BAD_FORMAT, "متنِ خالی چسبانده شده است.",
            )
        }
        if (raw.toByteArray(Charsets.UTF_8).size > BROADCAST_MAX_PACKET_BYTES) {
            return ParseResult.Failure(
                REJECT_TOO_LARGE,
                "پاکت بزرگ‌تر از ۸ کیلوبایت است و رد شد.",
            )
        }
        val lines = raw.trim().lines()
        if (lines.isEmpty() || lines[0].trim() != BROADCAST_ENVELOPE_HEADER_V2) {
            return ParseResult.Failure(
                REJECT_BAD_FORMAT,
                "این متن، پاکتِ پخشِ نسخهٔ ۲ نیست — باید با «$BROADCAST_ENVELOPE_HEADER_V2» شروع شود.",
            )
        }
        var type: String? = null
        var seq: Long? = null
        var keyVersion: Int? = null
        var from: String? = null
        var sentAt: Long? = null
        var score: Int? = null
        var hmac: String? = null
        var i = 1
        while (i < lines.size && lines[i].isNotBlank()) {
            val line = lines[i]
            when {
                line.startsWith("type: ") -> type = line.removePrefix("type: ").trim()
                line.startsWith("seq: ") -> seq = line.removePrefix("seq: ").trim().toLongOrNull()
                line.startsWith("key: ") -> keyVersion = line.removePrefix("key: ").trim().toIntOrNull()
                line.startsWith("from: ") -> from = line.removePrefix("from: ").trim()
                line.startsWith("sent: ") -> sentAt = line.removePrefix("sent: ").trim().toLongOrNull()
                line.startsWith("score: ") -> score = line.removePrefix("score: ").trim().toIntOrNull()
                line.startsWith("hmac: ") -> hmac = line.removePrefix("hmac: ").trim().lowercase()
                else -> return ParseResult.Failure(
                    REJECT_BAD_FORMAT, "سرفصلِ ناشناخته در پاکت: «$line»",
                )
            }
            i++
        }
        if (type == null || type !in setOf(
                BROADCAST_TYPE_MSG,
                BROADCAST_TYPE_MEMBER_ADD,
                BROADCAST_TYPE_MEMBER_REMOVE,
                BROADCAST_TYPE_KEY_ANNOUNCE,
            )
        ) {
            return ParseResult.Failure(REJECT_BAD_FORMAT, "نوعِ پاکت نامعتبر است.")
        }
        if (seq == null || seq <= 0) {
            return ParseResult.Failure(REJECT_BAD_FORMAT, "شمارهٔ ترتیبیِ پاکت نامعتبر است.")
        }
        if (keyVersion == null || keyVersion < 1) {
            return ParseResult.Failure(REJECT_BAD_FORMAT, "نسخهٔ کلیدِ پاکت نامعتبر است.")
        }
        if (from.isNullOrBlank() || from.length > 100) {
            return ParseResult.Failure(REJECT_BAD_FORMAT, "پاکت نامِ فرستندهٔ معتبر ندارد.")
        }
        if (sentAt == null || sentAt <= 0) {
            return ParseResult.Failure(REJECT_BAD_FORMAT, "پاکت زمانِ معتبر ندارد.")
        }
        if (hmac.isNullOrBlank() || hmac.length != 64 || hexToBytesOrNull(hmac) == null) {
            return ParseResult.Failure(REJECT_BAD_FORMAT, "امضایِ پاکت نامعتبر است.")
        }
        if (type == BROADCAST_TYPE_MSG && (score == null || score !in 0..100)) {
            return ParseResult.Failure(
                REJECT_BAD_FORMAT, "امتیازِ فوریتِ پاکت باید بین ۰ تا ۱۰۰ باشد.",
            )
        }
        // Skip the blank separator line(s).
        while (i < lines.size && lines[i].isBlank()) i++
        val payload = lines.drop(i).joinToString("\n").trim()
        if (payload.isEmpty()) {
            return ParseResult.Failure(REJECT_BAD_FORMAT, "پاکت بارِ خالی دارد.")
        }
        val canonical = canonicalPacketString(
            type, seq, keyVersion, from, sentAt, score, payload,
        )
        val id = packetContentId(canonical)
        if (type == BROADCAST_TYPE_MSG) {
            if (payload.length > BROADCAST_MAX_CHARS) {
                return ParseResult.Failure(
                    REJECT_BAD_FORMAT,
                    "متنِ پیام ${payload.length} نویسه است؛ سقف $BROADCAST_MAX_CHARS نویسه است.",
                )
            }
            return ParseResult.Msg(
                BroadcastEnvelope(
                    id = id,
                    senderName = from.take(100),
                    body = payload,
                    urgencyScore = score ?: 0,
                    sentAtEpoch = sentAt,
                    packetVersion = 2,
                    seq = seq,
                    keyVersion = keyVersion,
                    packetType = BROADCAST_TYPE_MSG,
                    signed = true,
                    hmac = hmac,
                    canonical = canonical,
                ),
            )
        }
        // Control packets: parse the payload lines.
        var memberName: String? = null
        var codeHash: String? = null
        var listVersion: Long? = null
        var newKeyVersion: Int? = null
        for (line in payload.lines()) {
            when {
                line.startsWith("name: ") -> memberName = line.removePrefix("name: ").trim()
                line.startsWith("codehash: ") -> codeHash = line.removePrefix("codehash: ").trim().lowercase()
                line.startsWith("list: ") -> listVersion = line.removePrefix("list: ").trim().toLongOrNull()
                line.startsWith("newkey: ") -> newKeyVersion = line.removePrefix("newkey: ").trim().toIntOrNull()
                else -> return ParseResult.Failure(
                    REJECT_BAD_FORMAT, "بارِ کنترلیِ ناشناخته در پاکت: «$line»",
                )
            }
        }
        when (type) {
            BROADCAST_TYPE_MEMBER_ADD -> {
                if (memberName.isNullOrBlank() || codeHash.isNullOrBlank() ||
                    codeHash.length != 64 || hexToBytesOrNull(codeHash) == null ||
                    listVersion == null || listVersion < 1
                ) {
                    return ParseResult.Failure(
                        REJECT_BAD_FORMAT, "پاکتِ افزودنِ عضو ناقص است.",
                    )
                }
            }
            BROADCAST_TYPE_MEMBER_REMOVE -> {
                if (codeHash.isNullOrBlank() ||
                    codeHash.length != 64 || hexToBytesOrNull(codeHash) == null ||
                    listVersion == null || listVersion < 1
                ) {
                    return ParseResult.Failure(
                        REJECT_BAD_FORMAT, "پاکتِ حذفِ عضو ناقص است.",
                    )
                }
            }
            BROADCAST_TYPE_KEY_ANNOUNCE -> {
                if (newKeyVersion == null || newKeyVersion < 1) {
                    return ParseResult.Failure(
                        REJECT_BAD_FORMAT, "پاکتِ اعلامِ کلید ناقص است.",
                    )
                }
            }
        }
        return ParseResult.Control(
            ControlPacket(
                type = type,
                seq = seq,
                keyVersion = keyVersion,
                from = from,
                sentAtEpoch = sentAt,
                hmac = hmac,
                canonical = canonical,
                id = id,
                memberName = memberName,
                codeHash = codeHash,
                listVersion = listVersion,
                newKeyVersion = newKeyVersion,
            ),
        )
    }

    /** Keys the device currently holds (from SettingsStore).
     * Either may be null — a null key simply never matches. */
    data class SigningKeys(
        val currentHex: String?,
        val currentVersion: Int,
        val prevHex: String?,
        val prevVersion: Int,
        val prevUntilEpoch: Long,
    )

    /**
     * Verify a parsed packet's HMAC. Returns "current" if the
     * current key matched, "previous" if the old key (still
     * inside its 7-day grace) matched, null otherwise. A packet
     * whose keyVersion matches neither held key is
     * "unknown_key", not "bad_signature" — the distinction is
     * what tells the user a fresh password is needed.
     */
    fun verify(
        canonical: String,
        hmac: String,
        keyVersion: Int,
        keys: SigningKeys,
        nowEpoch: Long,
    ): String? {
        if (keys.currentHex != null && keyVersion == keys.currentVersion) {
            val expected = hmacSha256Hex(keys.currentHex, canonical)
            if (expected != null && expected.equals(hmac, ignoreCase = true)) {
                return "current"
            }
            return null
        }
        if (keys.prevHex != null && keyVersion == keys.prevVersion &&
            nowEpoch <= keys.prevUntilEpoch
        ) {
            val expected = hmacSha256Hex(keys.prevHex, canonical)
            if (expected != null && expected.equals(hmac, ignoreCase = true)) {
                return "previous"
            }
            return null
        }
        return null
    }

    /** True when the packet's keyVersion is one the device holds
     * (current, or previous inside grace). Used to tell
     * "unknown key — a fresh password is needed" apart from a
     * genuinely bad signature. */
    fun isKnownKeyVersion(keyVersion: Int, keys: SigningKeys, nowEpoch: Long): Boolean {
        if (keyVersion == keys.currentVersion && keys.currentHex != null) return true
        if (keyVersion == keys.prevVersion && keys.prevHex != null &&
            nowEpoch <= keys.prevUntilEpoch
        ) return true
        return false
    }
}
