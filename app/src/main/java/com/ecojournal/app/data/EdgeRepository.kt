package com.ecojournal.app.data

import android.content.Context
import androidx.room.Room
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * TODO (Yasin, before first real sync): set EDGE_BASE_URL to the
 * published edge URL, e.g. https://<user>.github.io/<edge-repo>/out
 * (no trailing slash). Until then sync() returns
 * Result.NotConfigured and the app shows only what is already cached
 * — it never invents content and never ships a placeholder feed.
 *
 * The edge is static JSON produced by edge/generate_edge.py; the app
 * talks to nothing else. No token, key or secret is used here.
 */
object EdgeConfig {
    const val BASE_URL: String = ""
}

sealed class SyncResult {
    data object NotConfigured : SyncResult()
    data class Ok(val newArticleIds: List<String>, val totalCached: Int) : SyncResult()
    data class Failed(val reason: String) : SyncResult()
}

private fun httpGet(url: String): String? {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Accept", "application/json")
    }
    return try {
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            null
        } else {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    } finally {
        conn.disconnect()
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key)

private fun JSONObject.stringList(key: String): List<String> {
    val arr = optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).map { arr.optString(it) }
}

class EdgeRepository(context: Context) {
    private val db: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "eco.db",
    ).build()
    val dao: ArticleDao = db.articleDao()
    private val settings = SettingsStore(context)

    private fun entityFromArticleJson(obj: JSONObject): ArticleEntity {
        val blocks = obj.stringList("body_blocks")
        return ArticleEntity(
            id = obj.getString("id"),
            kind = obj.optString("kind", "article"),
            title = obj.getString("title"),
            summary = obj.optString("summary", ""),
            bodyBlocks = blocks,
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
        // Short cards carry verbatim body_fa text as a single block.
        // They are rendered with the "گزیده" label, never as full articles.
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

    /** Full sync: index first, then only articles not yet cached. */
    suspend fun sync(): SyncResult {
        val base = EdgeConfig.BASE_URL
        if (base.isBlank()) return SyncResult.NotConfigured
        val indexText = try {
            httpGet("$base/index.json")
        } catch (e: Exception) {
            return SyncResult.Failed(e.message ?: "network error")
        } ?: return SyncResult.Failed("index.json not reachable")
        val index = try {
            JSONObject(indexText)
        } catch (e: Exception) {
            return SyncResult.Failed("bad index.json: ${e.message}")
        }
        val hash = index.optString("hash_sha256", "")
        if (hash.isNotBlank() && dao.indexHash() == hash) {
            return SyncResult.Ok(emptyList(), dao.allIds().size)
        }
        val entries = index.optJSONArray("entries") ?: JSONArray()
        val existing = dao.allIds().toSet()
        val toFetch = mutableListOf<JSONObject>()
        val shortIds = mutableListOf<String>()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val id = e.optString("id")
            if (id !in existing) {
                if (e.optString("kind") == "short") shortIds.add(id)
                else if (!e.isNull("file")) toFetch.add(e)
            }
        }
        val fresh = mutableListOf<ArticleEntity>()
        for (e in toFetch) {
            val file = e.optString("file")
            val text = try {
                httpGet("$base/$file")
            } catch (_: Exception) {
                null
            } ?: continue
            try {
                fresh.add(entityFromArticleJson(JSONObject(text)))
            } catch (_: Exception) {
                // Skip one corrupt file; never crash the whole sync.
                continue
            }
        }
        if (shortIds.isNotEmpty()) {
            val shortsText = try {
                httpGet("$base/short_cards.json")
            } catch (_: Exception) {
                null
            }
            if (shortsText != null) {
                try {
                    val arr = JSONArray(shortsText)
                    val wanted = shortIds.toSet()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.optString("id") in wanted) {
                            fresh.add(entityFromShortJson(o))
                        }
                    }
                } catch (_: Exception) {
                    // Short cards are additive; a bad file must not
                    // block full articles already fetched.
                }
            }
        }
        if (fresh.isNotEmpty()) dao.upsertAll(fresh)
        if (hash.isNotBlank()) {
            dao.upsertMeta(MetaEntity("index_hash", hash))
        }
        return SyncResult.Ok(fresh.map { it.id }, dao.allIds().size)
    }

    /** Fetch one article by id (used by FCM data messages). */
    suspend fun fetchOne(id: String): Boolean {
        val base = EdgeConfig.BASE_URL
        if (base.isBlank()) return false
        val indexText = try {
            httpGet("$base/index.json")
        } catch (_: Exception) {
            return false
        } ?: return false
        val entries = try {
            JSONObject(indexText).optJSONArray("entries") ?: return false
        } catch (_: Exception) {
            return false
        }
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            if (e.optString("id") == id && !e.isNull("file")) {
                val text = try {
                    httpGet("$base/${e.optString("file")}")
                } catch (_: Exception) {
                    return false
                } ?: return false
                return try {
                    dao.upsertAll(listOf(entityFromArticleJson(JSONObject(text))))
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
        return false
    }

    /** Fetch market.json (snapshots + active formulas) from the edge. */
    suspend fun fetchMarket(): Triple<MarketSnapshot?, List<MarketFormula>, Int> {
        val base = EdgeConfig.BASE_URL
        if (base.isBlank()) return Triple(null, emptyList(), 0)
        val text = try {
            httpGet("$base/market.json")
        } catch (_: Exception) {
            null
        } ?: return Triple(null, emptyList(), 0)
        return try {
            val obj = JSONObject(text)
            val snaps = obj.optJSONArray("snapshots") ?: JSONArray()
            var latest: MarketSnapshot? = null
            for (i in 0 until snaps.length()) {
                val s = snaps.getJSONObject(i)
                latest = MarketSnapshot(
                    tsTehran = s.optString("ts_tehran", ""),
                    brent = if (s.isNull("brent")) null else s.optDouble("brent"),
                    xauUsd = if (s.isNull("xau_usd")) null else s.optDouble("xau_usd"),
                    usdIrtFree = if (s.isNull("usd_irt_free")) null else s.optDouble("usd_irt_free"),
                    usdtIrt = if (s.isNull("usdt_irt")) null else s.optDouble("usdt_irt"),
                    sekkeEmami = if (s.isNull("sekke_emami")) null else s.optDouble("sekke_emami"),
                )
            }
            val forms = mutableListOf<MarketFormula>()
            val farr = obj.optJSONArray("formulas_active") ?: JSONArray()
            for (i in 0 until farr.length()) {
                val f = farr.getJSONObject(i)
                forms.add(
                    MarketFormula(
                        name = f.optString("name", ""),
                        version = f.optInt("version", 1),
                        definition = f.optString("definition", ""),
                        inputs = f.optStringOrNull("inputs"),
                        descriptionFa = f.optStringOrNull("description_fa"),
                    )
                )
            }
            Triple(latest, forms, snaps.length())
        } catch (_: Exception) {
            Triple(null, emptyList(), 0)
        }
    }

    fun entityToArticle(e: ArticleEntity): Article {        val points = try {
            val arr = JSONArray(e.pointsJson)
            (0 until arr.length()).map { arr.optString(it) }
        } catch (_: Exception) {
            emptyList()
        }
        return Article(
            id = e.id,
            kind = e.kind,
            title = e.title,
            summary = e.summary,
            bodyBlocks = e.bodyBlocks,
            points = points,
            whyMatters = e.whyMatters,
            category = e.category,
            categoryFa = e.categoryFa,
            source = e.source,
            originalUrl = e.originalUrl,
            publishedAt = e.publishedAt,
            channelMessageId = e.channelMessageId,
        )
    }
}
