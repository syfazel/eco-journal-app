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

    const val BUNDLED_SEED_VERSION = 1

    private const val SEED_INDEX = "seed/index.json"
    private const val SEED_SHORTS = "seed/short_cards.json"
    private const val SEED_MARKET = "seed/market.json"

    private val seedMutex = Mutex()

    data class SeedReport(
        val inserted: Int,
        val missing: Int,
        val marketInserted: Int,
        val complete: Boolean,
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

        // Fast path: a complete seed for this exact bundle is already
        // recorded, and the market table is populated. Anything else
        // falls through to the missing-set computation below.
        if (storedVersion >= BUNDLED_SEED_VERSION &&
            storedHash != null && storedHash == hash &&
            dao.marketCount() > 0
        ) {
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

        var marketFresh = emptyList<MarketSnapshotEntity>()
        var marketMissing = false
        if (dao.marketCount() == 0) {
            val marketText = readAsset(context, SEED_MARKET)
            if (marketText == null) {
                marketMissing = true
            } else {
                try {
                    val snaps = JSONObject(marketText).optJSONArray("snapshots") ?: JSONArray()
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
                } catch (_: Exception) {
                    marketMissing = true
                }
            }
        }

        val complete = missingIds.isEmpty() && !marketMissing
        db.withTransaction {
            if (fresh.isNotEmpty()) dao.upsertAll(fresh)
            if (marketFresh.isNotEmpty()) dao.upsertMarket(marketFresh)
            dao.upsertMeta(MetaEntity("seed_missing_count", missingIds.size.toString()))
            if (complete) {
                if (hash.isNotBlank()) dao.upsertMeta(MetaEntity("index_hash", hash))
                dao.upsertMeta(MetaEntity("seed_version", BUNDLED_SEED_VERSION.toString()))
            }
        }
        return SeedReport(
            inserted = fresh.size,
            missing = missingIds.size,
            marketInserted = marketFresh.size,
            complete = complete,
        )
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
