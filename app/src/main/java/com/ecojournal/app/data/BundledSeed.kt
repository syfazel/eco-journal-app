package com.ecojournal.app.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline-first seed (M2 rewrite).
 *
 * The old seed had three permanent-failure modes: any non-empty
 * database disabled seeding forever, per-file failures were skipped
 * silently, and the index hash was written even on a partial load,
 * so a partial seed could never be repaired. This version is
 * resumable and verifiable:
 *
 * - It seeds only what is missing: expected ids from the bundled
 *   index minus ids already in Room. A kill mid-seed, a corrupt
 *   file, or an old partial seed all leave a well-defined missing
 *   set, and the next launch retries exactly that set.
 * - Articles, market snapshots and meta are applied in one Room
 *   transaction. The index hash and the seed version are written
 *   ONLY when nothing is missing. A partial run writes a missing
 *   count instead, never a success hash.
 * - Concurrent callers (Application onCreate and the feed's first
 *   composition) are serialised by a mutex; the second caller sees
 *   the first caller's result and does no duplicate work.
 * - Nothing is invented: every row is a real approved/posted
 *   extract (249 full articles) or an approved short card (32),
 *   plus the 164 bundled market snapshots.
 */
object BundledSeed {

    // v5.2: 2 — old installs backfill the Yemen seed on next
    // launch (the version bump is what re-runs the seed for
    // them; the missing-set logic below does the rest).
    const val BUNDLED_SEED_VERSION = 2

    private const val SEED_INDEX = "seed/index.json"
    private const val SEED_SHORTS = "seed/short_cards.json"
    private const val SEED_MARKET = "seed/market.json"
    private const val SEED_YEMEN = "seed/yemen.json"

    private val seedMutex = Mutex()

    data class SeedReport(
        val inserted: Int,
        val missing: Int,
        val marketInserted: Int,
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
        val dao = db.articleDao()
        val yemenDao = db.yemenDao()
        val indexText = readAsset(context, SEED_INDEX)
            ?: return SeedReport(0, 0, 0, false)
        val index = try {
            JSONObject(indexText)
        } catch (_: Exception) {
            return SeedReport(0, 0, 0, false)
        }
        val hash = index.optString("hash_sha256", "")
        val entries = index.optJSONArray("entries") ?: JSONArray()
        val storedVersion = dao.metaValue("seed_version")?.toIntOrNull() ?: 0
        val storedHash = dao.indexHash()

        // v5.2 Yemen seed state: the bundled seed file's SHA-256
        // is the seed hash, stored in yemen_meta only when the
        // Yemen seed is complete (missing-only, hash-only-when-
        // complete — the same resumable pattern as the articles).
        val yemenSeedText = readAsset(context, SEED_YEMEN)
        val yemenSeedHash = yemenSeedText?.let { sha256Hex(it) }
        val yemenMeta = yemenDao.meta()
        val yemenSeedComplete = yemenSeedHash != null &&
            yemenMeta?.yemenSeedHash == yemenSeedHash &&
            yemenDao.placeCount() > 0

        // Fast path: a complete seed for this exact bundle is already
        // recorded, and the market table and the formulas table are
        // populated. Anything else falls through to the missing-set
        // computation below. The formulaCount check matters for
        // installs that migrate from v2: they already have snapshots
        // but an empty formulas table, and without it they would
        // never backfill formulas.
        if (storedVersion >= BUNDLED_SEED_VERSION &&
            storedHash != null && storedHash == hash &&
            dao.marketCount() > 0 && dao.formulaCount() > 0 &&
            yemenSeedComplete
        ) {
            // v5.1: the fast path is still a completed seed
            // computation, so it still refreshes the seed report
            // (otherwise Diagnostics would show a stale report
            // from an older bundle after an update).
            SyncTrace.recordSeedReport(dao, 0, 0, true, BUNDLED_SEED_VERSION)
            return SeedReport(0, 0, 0, true)
        }

        val existingIds = dao.allIds().toSet()
        val fresh = mutableListOf<ArticleEntity>()
        val missingIds = mutableListOf<String>()
        val shortIds = mutableListOf<String>()
        val now = System.currentTimeMillis()

        for (i in 0 until entries.length()) {
            val entry = try {
                entries.getJSONObject(i)
            } catch (_: Exception) {
                // One corrupt index entry must not crash the seed,
                // but it is counted as missing, never as success.
                missingIds.add("index-entry-$i")
                continue
            }
            val id = entry.optString("id")
            if (id.isBlank()) {
                missingIds.add("index-entry-$i")
                continue
            }
            // A retracted entry is a tombstone, not a missing
            // article: the seed never counts it as missing and
            // never inserts it. Retraction of an already-cached
            // row is the edge sync's job (it flips the row's
            // status); a fresh seed simply has nothing to add.
            if (entry.optString("status", "approved") == "retracted") continue
            if (id in existingIds) continue
            if (entry.optString("kind") == "short") {
                shortIds.add(id)
            } else if (!entry.isNull("file")) {
                val file = entry.optString("file")
                val text = readAsset(context, "seed/$file")
                if (text == null) {
                    missingIds.add(id)
                } else {
                    try {
                        fresh.add(ArticleParsers.entityFromArticleJson(JSONObject(text), now))
                    } catch (_: Exception) {
                        missingIds.add(id)
                    }
                }
            } else {
                missingIds.add(id)
            }
        }

        if (shortIds.isNotEmpty()) {
            val shortsText = readAsset(context, SEED_SHORTS)
            if (shortsText == null) {
                missingIds.addAll(shortIds)
            } else {
                try {
                    val arr = JSONArray(shortsText)
                    val wanted = shortIds.toSet()
                    val found = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        val obj = try {
                            arr.getJSONObject(i)
                        } catch (_: Exception) {
                            continue
                        }
                        val id = obj.optString("id")
                        if (id in wanted) {
                            found.add(id)
                            try {
                                fresh.add(ArticleParsers.entityFromShortJson(obj, now))
                            } catch (_: Exception) {
                                missingIds.add(id)
                            }
                        }
                    }
                    missingIds.addAll(shortIds.filter { it !in found })
                } catch (_: Exception) {
                    missingIds.addAll(shortIds)
                }
            }
        }

        // Market (M4): snapshots and formulas are seeded
        // independently. Snapshots seed only when the table is empty;
        // formulas seed whenever their table is empty — including on
        // a migrated v2 install whose snapshots already exist. A
        // missing formulas file is retried on the next launch because
        // the fast path above requires formulaCount > 0; it does not
        // block the article seed's completeness, since a market-only
        // failure must not mark articles incomplete.
        var marketFresh = emptyList<MarketSnapshotEntity>()
        var formulasFresh = emptyList<MarketFormulaEntity>()
        var marketMissing = false
        val needSnapshots = dao.marketCount() == 0
        val needFormulas = dao.formulaCount() == 0
        if (needSnapshots || needFormulas) {
            val marketText = readAsset(context, SEED_MARKET)
            if (marketText == null) {
                if (needSnapshots) marketMissing = true
            } else {
                try {
                    val marketObj = JSONObject(marketText)
                    if (needSnapshots) {
                        val snaps = marketObj.optJSONArray("snapshots") ?: JSONArray()
                        val parsed = mutableListOf<MarketSnapshotEntity>()
                        for (i in 0 until snaps.length()) {
                            try {
                                parsed.add(ArticleParsers.marketEntityFromJson(snaps.getJSONObject(i)))
                            } catch (_: Exception) {
                                // One bad snapshot does not poison the rest.
                                continue
                            }
                        }
                        marketFresh = parsed
                        if (parsed.isEmpty()) marketMissing = true
                    }
                    if (needFormulas) {
                        val farr = marketObj.optJSONArray("formulas_active") ?: JSONArray()
                        val parsedFormulas = mutableListOf<MarketFormulaEntity>()
                        for (i in 0 until farr.length()) {
                            try {
                                val formula = MarketFormulas.formulaFromJson(farr.getJSONObject(i))
                                    ?: continue
                                parsedFormulas.add(
                                    MarketFormulaEntity(
                                        name = formula.name,
                                        version = formula.version,
                                        definition = formula.definition,
                                        inputs = formula.inputs,
                                        descriptionFa = formula.descriptionFa,
                                    )
                                )
                            } catch (_: Exception) {
                                continue
                            }
                        }
                        formulasFresh = parsedFormulas
                    }
                } catch (_: Exception) {
                    if (needSnapshots) marketMissing = true
                }
            }
        }

        // Yemen (v5.2): seeded from the bundled copy of yemen.json
        // with the same resumable pattern — only missing places
        // are inserted; events/frontlines/Aden seed whenever
        // their tables are empty; the seed hash is written only
        // when the whole Yemen seed is complete. A missing or
        // unparsable seed file marks Yemen incomplete (retried
        // next launch) but never blocks the article seed's own
        // completeness — the two seeds have separate hashes.
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
                if (yemenDao.aden() == null) {
                    yemenFreshAden = yemenDoc.aden.toEntity()
                }
            }
        }
        val yemenComplete = !yemenMissing && yemenSeedHash != null

        val complete = missingIds.isEmpty() && !marketMissing
        db.withTransaction {
            if (fresh.isNotEmpty()) dao.upsertAll(fresh)
            if (marketFresh.isNotEmpty()) dao.upsertMarket(marketFresh)
            if (formulasFresh.isNotEmpty()) dao.upsertFormulas(formulasFresh)
            if (yemenFreshPlaces.isNotEmpty()) yemenDao.upsertPlaces(yemenFreshPlaces)
            if (yemenFreshEvents.isNotEmpty()) yemenDao.upsertEvents(yemenFreshEvents)
            if (yemenFreshFrontlines.isNotEmpty()) yemenDao.upsertFrontlines(yemenFreshFrontlines)
            if (yemenFreshAden != null) yemenDao.upsertAden(yemenFreshAden)
            if (yemenDoc != null && yemenSeedText != null) {
                dao.upsertMeta(MetaEntity("yemen_doc_json", yemenSeedText))
            }
            dao.upsertMeta(MetaEntity("seed_missing_count", missingIds.size.toString()))
            if (complete) {
                if (hash.isNotBlank()) dao.upsertMeta(MetaEntity("index_hash", hash))
                dao.upsertMeta(MetaEntity("seed_version", BUNDLED_SEED_VERSION.toString()))
            }
            if (yemenComplete) {
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
        // to completion with zero network, and the feed is
        // populated from seed alone when the edge is unreachable.
        SyncTrace.recordSeedReport(
            dao, fresh.size, missingIds.size, complete, BUNDLED_SEED_VERSION,
        )
        return SeedReport(
            inserted = fresh.size,
            missing = missingIds.size,
            marketInserted = marketFresh.size,
            complete = complete,
            formulasInserted = formulasFresh.size,
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
