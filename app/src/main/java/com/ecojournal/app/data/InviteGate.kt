package com.ecojournal.app.data

import android.content.Context
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real invite gate (M7, P0-13 / P1-21 / P1-44), replacing the
 * open-by-default gate of phase 2.
 *
 * Rules, all hash-only (docs/vnext-decisions.md §1):
 * - The entered code is SHA-256 hashed ON THE DEVICE and only the
 *   hash is compared or stored. The code itself never leaves the
 *   device, never appears in logs or telemetry, and is never
 *   embedded in the APK.
 * - First-run authority is the bundled seed list
 *   (assets/seed/invite_hashes.json, generated from the pilot file
 *   at build time in the lab). When an edge sync has stored a newer
 *   list in Room meta, that list governs instead.
 * - Revocation is checked against BOTH the bundled and the edge
 *   revocation lists: a hash in either list is refused, and a
 *   sync-time re-check (EdgeRepository) re-locks a feed whose hash
 *   was revoked or dropped from the edge list. Cached articles are
 *   hidden, not deleted, so a re-grant (re-entering a valid code)
 *   restores them without re-download.
 * - Honest limit, stated in the plan (§2.5): this is a
 *   pilot-distribution control, not a security boundary against a
 *   determined APK holder (a debug APK can be decompiled).
 */
object InviteGate {

    fun sha256Hex(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(code.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readAsset(context: Context, path: String): String? {
        return try {
            context.assets.open(path).bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun hashesFrom(text: String?): List<String> {
        if (text == null) return emptyList()
        return try {
            val obj = JSONObject(text)
            val arr = obj.optJSONArray("hashes") ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Bundled (seed) invite + revocation lists, or empty lists. */
    fun bundledLists(context: Context): Pair<List<String>, List<String>> {
        val invites = hashesFrom(readAsset(context, "seed/invite_hashes.json"))
        val revocations = hashesFrom(readAsset(context, "seed/revocations.json"))
        return invites to revocations
    }

    /**
     * Verify an entered code. [edgeInvites]/[edgeRevocations] are
     * the lists stored by the last successful edge sync (empty
     * when no sync has stored one). Returns the hash on success,
     * null on any failure — with no hint about which code is valid.
     */
    fun verifyCode(
        context: Context,
        code: String,
        edgeInvites: List<String>,
        edgeRevocations: List<String>,
    ): String? {
        val hash = sha256Hex(code)
        val (bundledInvites, bundledRevocations) = bundledLists(context)
        if (hash in edgeRevocations || hash in bundledRevocations) return null
        val invites = edgeInvites.ifEmpty { bundledInvites }
        if (invites.isEmpty()) return null
        return if (hash in invites) hash else null
    }

    /**
     * Sync-time re-check (called by EdgeRepository only when an
     * edge invite/revocation list was actually fetched). Returns
     * true when the stored hash is still valid, false when it must
     * re-lock. A fetch failure must NOT call this — offline must
     * never lock a reader out.
     */
    fun stillValid(
        storedHash: String?,
        edgeInvites: List<String>,
        edgeRevocations: List<String>,
    ): Boolean {
        if (storedHash.isNullOrBlank()) return false
        if (storedHash in edgeRevocations) return false
        if (edgeInvites.isNotEmpty() && storedHash !in edgeInvites) return false
        return true
    }

    /** Parse a stored meta JSON array back to a hash list. */
    fun metaHashes(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
