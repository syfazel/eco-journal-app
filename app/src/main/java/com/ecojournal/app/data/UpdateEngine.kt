package com.ecojournal.app.data

import java.security.MessageDigest
import org.json.JSONObject

// Phase 1 Update Engine (big-update plan section 3).
//
// The engine is manifest-gated and per-section: the app fetches
// manifest.json (schema v3) FIRST, compares each section hash with
// the stored hash, and fetches only changed sections. A failed or
// corrupt section never empties that section's cache, and a partial
// section is reported as partial for that section only, never as a
// global failure and never as a silent success. All error codes are
// fixed strings; raw exception text and HTTP internals are never
// shown to the user and never stored as free text.
//
// Section states are stored in the meta table (no new table needed
// for states): sync_section_<key>_hash, _state, _last_sync_at,
// _last_attempt_at, _last_error_code, _count, _missing. The join-key
// mapping itself lives in the join_keys table (Room version 4).
// Hash VALUES are never shown in the UI; Diagnostics shows only
// present/absent, exactly like the legacy index_hash line.

// Fixed per-section states. Stored verbatim in meta.
object SectionStates {
    const val UP_TO_DATE = "up_to_date"
    const val PARTIAL = "partial"
    const val FAILED = "failed"
    const val NOT_YET_PUBLISHED = "not_yet_published"
    const val GATED_FROZEN = "gated_frozen"
    const val UNKNOWN = "unknown"
}

// Manifest section keys, exactly as edge/generate_edge.py emits them.
object SyncSections {
    const val ARTICLES = "articles"
    const val SHORTS = "shorts"
    const val MARKET = "market"
    const val CHANNEL_REFS = "channel_refs"
    const val CHANNEL_TYPED = "channel_typed"
    const val BUNDLES = "bundles"
    const val RADAR = "radar"
    const val NIGHTLY = "nightly"
    const val VOICES = "voices"
    const val SPECIAL_ISSUES = "special_issues"
    const val INVITES = "invites"
    const val REVOCATIONS = "revocations"

    val ALL: List<String> = listOf(
        ARTICLES, SHORTS, MARKET, CHANNEL_REFS, CHANNEL_TYPED, BUNDLES, RADAR,
        NIGHTLY, VOICES, SPECIAL_ISSUES, INVITES, REVOCATIONS,
    )

    // Sections with real edge files the app fetches and caches.
    // Phase 5 makes NIGHTLY fetchable (spec §5.12): per-edition
    // files + nightly.json index, section hash over full edition
    // contents (R13), never the entry list alone. VOICES and
    // SPECIAL_ISSUES stay gated/not-yet-published.
    val FETCHABLE: Set<String> = setOf(
        ARTICLES, SHORTS, MARKET, CHANNEL_REFS, CHANNEL_TYPED, BUNDLES,
        RADAR, NIGHTLY, INVITES, REVOCATIONS,
    )

    fun hashKey(section: String) = "sync_section_${section}_hash"
    fun stateKey(section: String) = "sync_section_${section}_state"
    fun lastSyncKey(section: String) = "sync_section_${section}_last_sync_at"
    fun lastAttemptKey(section: String) = "sync_section_${section}_last_attempt_at"
    fun errorKey(section: String) = "sync_section_${section}_last_error_code"
    fun countKey(section: String) = "sync_section_${section}_count"
    fun missingKey(section: String) = "sync_section_${section}_missing"
}

// One row of the per-section Diagnostics view. No hash value, no
// content, no token, no invite code ever travels in this object.
data class SectionSyncState(
    val section: String,
    val state: String,
    val lastSyncAt: Long?,
    val lastAttemptAt: Long?,
    val lastErrorCode: String,
    val itemCount: Int,
    val hashPresent: Boolean,
    val missingCount: Int,
)

// Per-section outcome attached to a SyncResult, so the feed line
// can stay short while Diagnostics shows the honest detail.
data class SectionOutcome(
    val key: String,
    val state: String,
    val missingCount: Int,
)

// One manifest item. storyId is the Phase 1 join key: derived from
// the source guid on the edge side, never from a headline hash.
// channelMessageId is null for every entry today (verified against
// the real edge snapshot and the production database); it becomes
// non-null only when the bridge staging path (Phase 3) carries a
// real channel post through. A null here is honest, never a guess.
data class ManifestItem(
    val id: String,
    val appKey: String,
    val contentHash: String?,
    val updatedAtEpoch: Long,
    val status: String,
    val file: String?,
    val channelMessageId: Long?,
    val storyId: String?,
)

data class ManifestSection(
    val key: String,
    val hash: String,
    val count: Int,
    val status: String,
    val items: List<ManifestItem>,
    val file: String?,
)

data class EdgeManifest(
    val version: Int,
    val generatedAtUtc: String,
    val sections: Map<String, ManifestSection>,
    val joinKeysFile: String?,
)

// Manifest v3 parser. Returns null on ANY structural problem; the
// caller then falls back to the legacy index path or reports
// bad_manifest. It never throws, so a corrupt manifest cannot crash
// a sync, and it never invents a missing section.
object ManifestParser {
    fun parse(text: String): EdgeManifest? {
        return try {
            val root = JSONObject(text)
            if (root.optInt("version", 0) != 3) return null
            val sectionsObj = root.optJSONObject("sections") ?: return null
            val sections = mutableMapOf<String, ManifestSection>()
            for (key in SyncSections.ALL) {
                val obj = sectionsObj.optJSONObject(key) ?: continue
                val items = mutableListOf<ManifestItem>()
                val arr = obj.optJSONArray("items")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val e = try {
                            arr.getJSONObject(i)
                        } catch (_: Exception) {
                            continue
                        }
                        val id = e.optString("id")
                        if (id.isBlank()) continue
                        val appKey = e.optString("app_key").takeIf { it.isNotBlank() }
                            ?: ArticleKeys.appKeyFor(id)
                        items.add(
                            ManifestItem(
                                id = id,
                                appKey = appKey,
                                contentHash = e.optString("content_hash")
                                    .takeIf { it.isNotBlank() },
                                updatedAtEpoch = e.optLong("updated_at_epoch", 0L),
                                status = e.optString("status", "approved"),
                                file = e.optString("file").takeIf { it.isNotBlank() },
                                channelMessageId = if (e.isNull("channel_message_id")) {
                                    null
                                } else {
                                    e.optLong("channel_message_id")
                                },
                                storyId = e.optString("story_id").takeIf { it.isNotBlank() },
                            )
                        )
                    }
                }
                sections[key] = ManifestSection(
                    key = key,
                    hash = obj.optString("hash", ""),
                    count = obj.optInt("count", items.size),
                    status = obj.optString("status", ""),
                    items = items,
                    file = obj.optString("file").takeIf { it.isNotBlank() },
                )
            }
            EdgeManifest(
                version = 3,
                generatedAtUtc = root.optString("generated_at_utc", ""),
                sections = sections,
                joinKeysFile = root.optString("join_keys_file")
                    .takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            null
        }
    }
}

// Pure delta planning: given a manifest item list and the stored
// content hashes keyed by appKey, split items into to-fetch,
// retracted (tombstone) and up-to-date sets. No I/O, no Android
// dependency, so the rule can be walked through against the real
// edge snapshot without a device: an unchanged hash is never
// refetched, a changed or missing hash is fetched, a retracted row
// that exists locally becomes a tombstone.
object DeltaPlanner {
    data class Plan(
        val toFetch: List<ManifestItem>,
        val tombstones: List<ManifestItem>,
        val corruptCount: Int,
    )

    // requiresFile = true for articles (a null file is corrupt);
    // false for shorts, whose items carry no per-item file and are
    // resolved through short_cards.json by the caller instead.
    fun plan(
        items: List<ManifestItem>,
        storedHashes: Map<String, String?>,
        existingKeys: Set<String>,
        requiresFile: Boolean = true,
    ): Plan {
        val toFetch = mutableListOf<ManifestItem>()
        val tombstones = mutableListOf<ManifestItem>()
        var corrupt = 0
        for (item in items) {
            if (item.status == "retracted") {
                if (item.appKey in existingKeys) tombstones.add(item)
                continue
            }
            val stored = storedHashes[item.appKey]
            if (stored != null && item.contentHash != null && stored == item.contentHash) {
                continue
            }
            if (requiresFile && item.file == null) {
                corrupt++
                continue
            }
            toFetch.add(item)
        }
        return Plan(toFetch, tombstones, corrupt)
    }

    // A section is up to date when its stored hash equals the
    // manifest hash and this is not a force-refresh. A blank
    // manifest hash never counts as up to date: an edge that
    // cannot state its hash must be re-checked, not trusted.
    fun isUpToDate(storedHash: String?, manifestHash: String, forceRefresh: Boolean): Boolean {
        if (forceRefresh) return false
        if (manifestHash.isBlank()) return false
        return storedHash != null && storedHash == manifestHash
    }
}

// Exponential backoff for manual/section retries. WorkManager
// already backs off exponentially for periodic work; this helper
// gives the same shape to any in-app retry scheduling so the two
// paths cannot drift apart: 15 min, 30 min, 1 h, 2 h, capped at 4 h.
object SyncBackoff {
    private const val BASE_MS = 15L * 60L * 1000L
    private const val CAP_MS = 240L * 60L * 1000L

    fun delayMs(attempt: Int): Long {
        val clamped = attempt.coerceIn(0, 4)
        var delay = BASE_MS
        repeat(clamped) { delay *= 2 }
        return minOf(delay, CAP_MS)
    }
}

// Join-key derivation, byte-identical to edge/generate_edge.py:
// "story-" + first 20 hex of SHA-256(guid). The app never derives
// a story id from a headline; headline hashing (story_key) is the
// defect this migration replaces, and it stays out of the app.
object JoinKeys {
    fun storyIdFor(guid: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(guid.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "story-" + hex.take(20)
    }
}
