package com.ecojournal.app.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Offline-first seed (M2 rewrite), Phase 4 (v5.3) Yemen-only.
 *
 * The old seed bundled 249 articles + 32 short cards + 164
 * market snapshots from `seed/index.json` etc. Those assets
 * are gone with their sections. The only bundled seed left is
 * `seed/yemen.json` — the same resumable, verifiable pattern:
 * only missing places are inserted; events/frontlines/Aden
 * seed whenever their tables are empty; the seed hash is
 * written only when the whole Yemen seed is complete. A
 * missing or unparsable seed file marks Yemen incomplete
 * (retried next launch) but never crashes startup.
 *
 * BUNDLED_SEED_VERSION stays 2: the Yemen seed itself did
 * not change in v5.3, so installs that already seeded Yemen
 * need no re-seed; the fast path below recognises them.
 *
 * v5.6: 3 — the seed now carries the Aden investigation
 * sections (images / cross-check / official channels / social
 * posts / amplifiers) and the Aden row is re-seeded whenever
 * the bundle hash changes, so a v11 install that already has
 * an Aden row still gets the new sections on the next launch.
 */
object BundledSeed {

    // v5.2: 2 — old installs backfill the Yemen seed on next
    // launch (the version bump is what re-runs the seed for
    // them; the missing-set logic below does the rest).
    // v5.6: 3 — Aden investigation sections added to the seed.
    const val BUNDLED_SEED_VERSION = 3

    private const val SEED_YEMEN = "seed/yemen.json"

    private val seedMutex = Mutex()

    data class SeedReport(
        val inserted: Int,
        val missing: Int,
        val marketInserted: Int = 0,
        val complete: Boolean,
        val formulasInserted: Int = 0,
        val yemenInserted: Int = 0,
        val yemenMissing: Int = 0,
    )

    private fun readAsset(context: Context, path: String): String? {
        return try {
            context.assets.open(path).bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun ensureSeed(context: Context): SeedReport {
        return seedMutex.withLock { ensureSeedLocked(context) }
    }

    private suspend fun ensureSeedLocked(context: Context): SeedReport {
        val db = DatabaseProvider.get(context)
        val dao = db.metaDao()
        val yemenDao = db.yemenDao()

        // The bundled seed file's SHA-256 is the seed hash,
        // stored in yemen_meta only when the Yemen seed is
        // complete (missing-only, hash-only-when-complete).
        val yemenSeedText = readAsset(context, SEED_YEMEN)
        val yemenSeedHash = yemenSeedText?.let { sha256Hex(it) }
        val yemenMeta = yemenDao.meta()
        val yemenSeedComplete = yemenSeedHash != null &&
            yemenMeta?.yemenSeedHash == yemenSeedHash &&
            yemenDao.placeCount() > 0
        val storedVersion = dao.metaValue("seed_version")?.toIntOrNull() ?: 0

        // Fast path: a complete seed for this exact bundle is already
        // recorded. It still refreshes the seed report (otherwise
        // Diagnostics would show a stale report from an older bundle
        // after an update).
        if (storedVersion >= BUNDLED_SEED_VERSION && yemenSeedComplete) {
            SyncTrace.recordSeedReport(dao, 0, 0, true, BUNDLED_SEED_VERSION)
            return SeedReport(0, 0, 0, true)
        }

        var yemenFreshPlaces = emptyList<YemenPlaceEntity>()
        var yemenFreshEvents = emptyList<YemenEventEntity>()
        var yemenFreshFrontlines = emptyList<YemenFrontlineEntity>()
        var yemenFreshAden: YemenAdenEntity? = null
        var yemenDoc: YemenDoc? = null
        var yemenMissing = false
        if (!yemenSeedComplete) {
            yemenDoc = yemenSeedText?.let { YemenParsers.parseDoc(it) }
            if (yemenDoc == null || yemenDoc.places.isEmpty()) {
                yemenMissing = true
            } else {
                val existingYemenKeys = yemenDao.allPlaceAppKeys().toSet()
                yemenFreshPlaces = yemenDoc.places
                    .filter { it.appKey !in existingYemenKeys }
                    .map { it.toEntity() }
                if (yemenDao.eventCount() == 0) {
                    yemenFreshEvents = yemenDoc.events.map { it.toEntity() }
                }
                if (yemenDao.frontlines().isEmpty()) {
                    yemenFreshFrontlines = yemenDoc.frontlines.map { it.toEntity() }
                }
                // v5.6: the Aden row is re-seeded whenever the
                // bundle is re-parsed (not only when the row is
                // missing) — upsert is REPLACE, so an older row
                // gains the new investigation sections instead
                // of keeping its pre-5.6 empty columns.
                yemenFreshAden = yemenDoc.aden.toEntity()
            }
        }
        val yemenComplete = !yemenMissing && yemenSeedHash != null

        db.withTransaction {
            if (yemenFreshPlaces.isNotEmpty()) yemenDao.upsertPlaces(yemenFreshPlaces)
            if (yemenFreshEvents.isNotEmpty()) yemenDao.upsertEvents(yemenFreshEvents)
            if (yemenFreshFrontlines.isNotEmpty()) yemenDao.upsertFrontlines(yemenFreshFrontlines)
            if (yemenFreshAden != null) yemenDao.upsertAden(yemenFreshAden)
            if (yemenDoc != null && yemenSeedText != null) {
                dao.upsertMeta(MetaEntity("yemen_doc_json", yemenSeedText))
            }
            dao.upsertMeta(MetaEntity("seed_missing_count", if (yemenMissing) "1" else "0"))
            if (yemenComplete) {
                dao.upsertMeta(MetaEntity("seed_version", BUNDLED_SEED_VERSION.toString()))
                val oldMeta = yemenDao.meta()
                yemenDao.upsertMeta(
                    YemenMetaEntity(
                        key = "yemen",
                        yemenHash = oldMeta?.yemenHash,
                        yemenGeneratedAt = yemenDoc?.generatedAtUtc
                            ?: oldMeta?.yemenGeneratedAt,
                        yemenSeedHash = yemenSeedHash,
                        lastYemenSyncAt = oldMeta?.lastYemenSyncAt,
                        lastYemenErrorCode = oldMeta?.lastYemenErrorCode,
                        controlChanges = oldMeta?.controlChanges ?: 0,
                    ),
                )
            }
        }
        // v5.1: seed report AFTER the transaction, never inside
        // it (diagnostics contract: swallowed, outside
        // transactions). The seed reads assets only, so this runs
        // to completion with zero network, and the Yemen tab is
        // populated from seed alone when the edge is unreachable.
        SyncTrace.recordSeedReport(
            dao, yemenFreshPlaces.size, if (yemenMissing) 1 else 0,
            yemenComplete, BUNDLED_SEED_VERSION,
        )
        return SeedReport(
            inserted = yemenFreshPlaces.size,
            missing = if (yemenMissing) 1 else 0,
            complete = yemenComplete,
            yemenInserted = yemenFreshPlaces.size,
            yemenMissing = if (yemenMissing) 1 else 0,
        )
    }

    private fun sha256Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Backwards-compatible entry point used by older callers: returns
     * the number of rows inserted by this run, or 0 if nothing was
     * missing. It deliberately does NOT return 0 as a success signal
     * for a partial run; callers that need the distinction should use
     * [ensureSeed] and read [SeedReport.complete].
     */
    suspend fun seedIfEmpty(context: Context): Int {
        return ensureSeed(context).inserted
    }
}
