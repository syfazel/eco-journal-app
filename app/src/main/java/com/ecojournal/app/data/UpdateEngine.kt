package com.ecojournal.app.data

import org.json.JSONObject

// Phase 4 (v5.3): Yemen-only update engine.
//
// The old multi-section engine (articles / shorts / market /
// channel_typed / bundles / radar / nightly / voices / special
// issues / invites / revocations) is gone with those sections.
// What remains is exactly what the Yemen sync needs: the fixed
// section states, the single Yemen section key, the manifest
// parser (Yemen section only — no per-item lists any more),
// and the hash up-to-date check. The manifest is still v3/v4
// (v4 = v3 + additive sections.yemen); unknown sections are
// ignored, so an old manifest shape cannot break the Yemen pass.

// Fixed per-section states. Stored verbatim in meta.
object SectionStates {
    const val UP_TO_DATE = "up_to_date"
    const val PARTIAL = "partial"
    const val FAILED = "failed"
    const val NOT_YET_PUBLISHED = "not_yet_published"
    const val GATED_FROZEN = "gated_frozen"
    const val UNKNOWN = "unknown"
}

// Manifest section keys. Only Yemen is fetched any more; the
// other sections live on the edge for old installs but the app
// never asks for them.
object SyncSections {
    /** v5.2 Yemen tab (audit §4.2): single-file section, 24h cadence. */
    const val YEMEN = "yemen"

    val ALL: List<String> = listOf(YEMEN)

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

// Per-section outcome attached to a SyncResult, so the Yemen tab
// can stay short while Diagnostics shows the honest detail.
data class SectionOutcome(
    val key: String,
    val state: String,
    val missingCount: Int,
)

data class ManifestSection(
    val key: String,
    val hash: String,
    val count: Int,
    val status: String,
    val file: String?,
)

data class EdgeManifest(
    val version: Int,
    val generatedAtUtc: String,
    val sections: Map<String, ManifestSection>,
)

// Manifest v3/v4 parser. Returns null on ANY structural problem;
// the caller then reports bad_manifest and touches nothing. It
// never throws, so a corrupt manifest cannot crash a sync, and
// it never invents a missing section. Only the Yemen section is
// read — every other section in the file is ignored.
object ManifestParser {
    fun parse(text: String): EdgeManifest? {
        return try {
            val root = JSONObject(text)
            // Manifest v4 is v3 plus the additive sections.yemen
            // entry (v5.2); old clients ignore the unknown section.
            // Accept both versions; the parsed version is carried
            // so callers can tell them apart.
            val version = root.optInt("version", 0)
            if (version != 3 && version != 4) return null
            val sectionsObj = root.optJSONObject("sections") ?: return null
            val sections = mutableMapOf<String, ManifestSection>()
            val obj = sectionsObj.optJSONObject(SyncSections.YEMEN)
                ?: return EdgeManifest(
                    version = version,
                    generatedAtUtc = root.optString("generated_at_utc", ""),
                    sections = emptyMap(),
                )
            sections[SyncSections.YEMEN] = ManifestSection(
                key = SyncSections.YEMEN,
                hash = obj.optString("hash", ""),
                count = obj.optInt("count", 0),
                status = obj.optString("status", ""),
                file = obj.optString("file").takeIf { it.isNotBlank() },
            )
            EdgeManifest(
                version = version,
                generatedAtUtc = root.optString("generated_at_utc", ""),
                sections = sections,
            )
        } catch (_: Exception) {
            null
        }
    }
}

object DeltaPlanner {
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
