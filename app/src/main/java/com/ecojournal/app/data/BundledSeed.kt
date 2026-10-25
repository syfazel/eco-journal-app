package com.ecojournal.app.data

import android.content.Context
import androidx.room.Room
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline-first seed (setup task, Phase 1 follow-up).
 *
 * First launch populates the Room cache from the bundled snapshot in
 * assets/seed/ — a verbatim copy of edge/out/ produced by
 * edge/generate_edge.py from the real editorial databases. It runs
 * ONLY when the cache is empty, never overwrites existing rows, and
 * never invents text: every row here is a real approved/posted
 * extract (249 full articles) or an approved short card (32).
 *
 * Why this exists: the app must work with zero network and before
 * any GitHub Pages URL exists, so the pilot is not blocked on the
 * GitHub login / Pages enablement (see edge/PUSH_README.md). Once a
 * real edge URL is configured, normal sync takes over: this seed
 * also records the bundled index hash in meta, so a later edge
 * publish with a NEW hash triggers a delta sync, while the SAME
 * hash is correctly recognised as already-cached.
 *
 * Mapping mirrors EdgeRepository's parsers intentionally (kept
 * separate so this file is self-contained and never depends on
 * network code). Uses the same database file ("eco.db") as
 * EdgeRepository, so seeded rows are the rows the UI reads.
 */
object BundledSeed {

    private const val SEED_INDEX = "seed/index.json"
    private const val SEED_SHORTS = "seed/short_cards.json"

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key)

    private fun JSONObject.stringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    private fun entityFromArticleJson(obj: JSONObject): ArticleEntity {
        return ArticleEntity(
            id = obj.getString("id"),
            kind = obj.optString("kind", "article"),
            title = obj.getString("title"),
            summary = obj.optString("summary", ""),
            bodyBlocks = obj.stringList("body_blocks"),
            pointsJson = obj.optJSONArray("points")?.toString() ?: "[]",
            whyMatters = obj.optStringOrNull("why_matters"),
            category = obj.optString("category", "general"),
            categoryFa = obj.optString("category_fa", "عمومی"),
            source = obj.optString("source", ""),
            originalUrl = obj.optStringOrNull("original_url"),
            publishedAt = obj.optStringOrNull("published_at"),
            channelMessageId = if (obj.isNull("channel_message_id")) null
                else obj.optLong("channel_message_id"),
            cachedAt = System.currentTimeMillis(),
        )
    }

    private fun entityFromShortJson(obj: JSONObject): ArticleEntity {
        val text = obj.optString("text", "")
        return ArticleEntity(
            id = obj.getString("id"),
            kind = "short",
            title = obj.getString("title"),
            summary = if (text.length <= 280) text else text.take(277) + "…",
            bodyBlocks = listOf(text),
            pointsJson = "[]",
            whyMatters = null,
            category = "general",
            categoryFa = "عمومی",
            source = obj.optString("source", ""),
            originalUrl = obj.optStringOrNull("original_url"),
            publishedAt = obj.optStringOrNull("decided_at"),
            channelMessageId = null,
            cachedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Seeds the cache from assets if (and only if) it is empty.
     * Returns the number of rows inserted, or 0 if already seeded
     * or if the bundled seed is unreadable (never crashes startup).
     */
    suspend fun seedIfEmpty(context: Context): Int {
        val db = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "eco.db",
        ).build()
        try {
            val dao = db.articleDao()
            if (dao.allIds().isNotEmpty()) return 0
            val indexText = try {
                context.assets.open(SEED_INDEX).bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            } catch (_: Exception) {
                return 0
            }
            val index = try {
                JSONObject(indexText)
            } catch (_: Exception) {
                return 0
            }
            val hash = index.optString("hash_sha256", "")
            val entries = index.optJSONArray("entries") ?: JSONArray()
            val fresh = mutableListOf<ArticleEntity>()
            val shortIds = mutableListOf<String>()
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                val id = e.optString("id")
                if (e.optString("kind") == "short") {
                    shortIds.add(id)
                } else if (!e.isNull("file")) {
                    val file = e.optString("file")
                    try {
                        val text = context.assets.open("seed/$file")
                            .bufferedReader(Charsets.UTF_8).use { it.readText() }
                        fresh.add(entityFromArticleJson(JSONObject(text)))
                    } catch (_: Exception) {
                        // Skip one unreadable file; never block the seed.
                        continue
                    }
                }
            }
            if (shortIds.isNotEmpty()) {
                try {
                    val arr = JSONArray(
                        context.assets.open(SEED_SHORTS)
                            .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    )
                    val wanted = shortIds.toSet()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.optString("id") in wanted) {
                            fresh.add(entityFromShortJson(o))
                        }
                    }
                } catch (_: Exception) {
                    // Short cards are additive; a bad file must not
                    // block full articles already collected.
                }
            }
            if (fresh.isNotEmpty()) dao.upsertAll(fresh)
            if (hash.isNotBlank()) dao.upsertMeta(MetaEntity("index_hash", hash))
            return fresh.size
        } finally {
            db.close()
        }
    }
}
