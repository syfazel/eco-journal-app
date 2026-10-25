package com.ecojournal.app.data

import android.content.Context
import androidx.room.withTransaction
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Edge URL decision (docs/vnext-decisions.md §2, M5): the Pages
 * root https://syfazel.github.io/eco-journal-app/ — WITHOUT any
 * /out suffix, because .github/workflows/pages.yml publishes
 * edge/out/index.json as <root>/index.json. The repo's Pages
 * source was switched to "GitHub Actions" via the GitHub API in
 * M5, so the workflow now serves the edge. If Pages is not yet
 * serving (or the device is offline), sync() returns a fixed
 * error code and the app keeps showing only what is already
 * cached — it never invents content and never ships a
 * placeholder feed. The edge is static JSON produced by
 * edge/generate_edge.py; the app talks to nothing else. No token,
 * key or secret is used here. Callers must use [edgeBase] (the
 * URL without a trailing slash), never the raw constant, so
 * "$base/index.json" never becomes a double-slash URL.
 *
 * Sync rules (M2), replacing the old "hash on partial" behaviour:
 * - An article is fetched only if it is absent, or if its stored
 *   content hash differs from the index entry's hash. A changed
 *   body therefore replaces the old body in the same transaction
 *   that records the sync; an unchanged body is never refetched.
 *   Since M3 the generator emits content_hash, app_key and status
 *   in every index entry, so the skip-unchanged comparison and
 *   the tombstone path are live (previously the generator emitted
 *   none of them and the paths stayed dormant).
 * - A retracted entry becomes a tombstone: the local row keeps its
 *   bookmark link but leaves the feed (status = "retracted").
 * - The index hash is written ONLY after a transaction that
 *   fetched everything missing with zero corrupt entries. Any
 *   failure yields Partial, and the next sync retries exactly the
 *   missing set instead of declaring success.
 * - Per-entry corruption is counted and skipped, never a crash.
 */
object EdgeConfig {
    const val BASE_URL: String = "https://syfazel.github.io/eco-journal-app/"
}

/**
 * Fixed sync error codes (M5, P1-25). The UI maps each code to a
 * fixed Persian string; raw exception text and HTTP internals are
 * never shown to the user and never stored as free text.
 * Codes: "network" (offline/DNS/timeout), "http_404",
 * "http_403", "http_other" (any other non-200), "bad_index"
 * (index.json unparsable), "insecure_edge" (non-HTTPS base).
 */
sealed class SyncResult {
    data object NotConfigured : SyncResult()
    data class Ok(val newArticleAppKeys: List<String>, val totalCached: Int) : SyncResult()
    data class Partial(
        val newArticleAppKeys: List<String>,
        val missingCount: Int,
        val totalCached: Int,
    ) : SyncResult()
    data class Failed(val code: String) : SyncResult()
}

private sealed class HttpOutcome {
    data class Ok(val body: String) : HttpOutcome()
    data class HttpError(val status: Int) : HttpOutcome()
    data object NetworkError : HttpOutcome()
}

private fun httpGet(url: String): HttpOutcome {
    val conn = try {
        URL(url).openConnection() as HttpURLConnection
    } catch (_: Exception) {
        return HttpOutcome.NetworkError
    }
    conn.apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "ECO-JOURNAL-Android/1.0")
    }
    return try {
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            HttpOutcome.HttpError(conn.responseCode)
        } else {
            HttpOutcome.Ok(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    } catch (_: Exception) {
        HttpOutcome.NetworkError
    } finally {
        conn.disconnect()
    }
}

private fun HttpOutcome.toFailedCode(): String = when (this) {
    is HttpOutcome.HttpError -> when (status) {
        404 -> "http_404"
        403 -> "http_403"
        else -> "http_other"
    }
    HttpOutcome.NetworkError -> "network"
    is HttpOutcome.Ok -> "http_other"
}

/** Trailing-slash-free base, or null when the edge is not usable. */
private fun edgeBase(): String? {
    val base = EdgeConfig.BASE_URL.trimEnd('/')
    if (base.isBlank() || !base.startsWith("https://")) return null
    return base
}

class EdgeRepository(context: Context) {
    private val db: AppDatabase = DatabaseProvider.get(context)
    val dao: ArticleDao = db.articleDao()
    private val settings = SettingsStore(context)

    /** Records an attempt (M5): attempt time on every real attempt,
     * and a fixed error code — "" only on a complete success, so
     * the feed can always show an honest last-sync state. */
    private suspend fun recordAttempt(code: String) {
        val now = System.currentTimeMillis()
        dao.upsertMeta(MetaEntity("last_sync_attempt_at", now.toString()))
        dao.upsertMeta(MetaEntity("last_sync_error_code", code))
    }

    /** Full sync: index first, then only missing or changed items. */
    suspend fun sync(): SyncResult {
        val base = edgeBase() ?: return SyncResult.NotConfigured
        val indexOutcome = httpGet("$base/index.json")
        val indexText = when (indexOutcome) {
            is HttpOutcome.Ok -> indexOutcome.body
            else -> {
                val code = indexOutcome.toFailedCode()
                recordAttempt(code)
                return SyncResult.Failed(code)
            }
        }
        val index = try {
            JSONObject(indexText)
        } catch (_: Exception) {
            recordAttempt("bad_index")
            return SyncResult.Failed("bad_index")
        }
        val hash = index.optString("hash_sha256", "")
        val entries = index.optJSONArray("entries") ?: JSONArray()
        val now = System.currentTimeMillis()

        // Existing rows keyed by appKey, with their stored hashes.
        val existingByKey = dao.all().associate { it.appKey to it }
        val existingKeys = existingByKey.keys

        val toFetch = mutableListOf<JSONObject>()
        val shortIds = mutableListOf<String>()
        val tombstones = mutableListOf<JSONObject>()
        var corruptEntries = 0

        for (i in 0 until entries.length()) {
            val entry = try {
                entries.getJSONObject(i)
            } catch (_: Exception) {
                corruptEntries++
                continue
            }
            val id = entry.optString("id")
            if (id.isBlank()) {
                corruptEntries++
                continue
            }
            val appKey = entry.optStringOrNull("app_key")?.takeIf { it.isNotBlank() }
                ?: ArticleKeys.appKeyFor(id)
            val status = entry.optString("status", "approved")
            if (status == "retracted") {
                if (appKey in existingKeys) tombstones.add(entry)
                continue
            }
            val stored = existingByKey[appKey]
            val entryHash = entry.optStringOrNull("content_hash")
            if (stored != null && entryHash != null && stored.contentHash == entryHash) {
                continue
            }
            if (entry.optString("kind") == "short") {
                shortIds.add(id)
            } else if (!entry.isNull("file")) {
                toFetch.add(entry)
            } else {
                corruptEntries++
            }
        }

        val fresh = mutableListOf<ArticleEntity>()
        val missingKeys = mutableListOf<String>()
        for (entry in toFetch) {
            val file = entry.optString("file")
            val appKey = entry.optStringOrNull("app_key")?.takeIf { it.isNotBlank() }
                ?: ArticleKeys.appKeyFor(entry.optString("id"))
            val text = when (val outcome = httpGet("$base/$file")) {
                is HttpOutcome.Ok -> outcome.body
                else -> null
            }
            if (text == null) {
                missingKeys.add(appKey)
                continue
            }
            try {
                fresh.add(ArticleParsers.entityFromArticleJson(JSONObject(text), now))
            } catch (_: Exception) {
                missingKeys.add(appKey)
            }
        }

        if (shortIds.isNotEmpty()) {
            val shortsText = when (val outcome = httpGet("$base/short_cards.json")) {
                is HttpOutcome.Ok -> outcome.body
                else -> null
            }
            if (shortsText == null) {
                missingKeys.addAll(shortIds.map { ArticleKeys.appKeyFor(it) })
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
                                missingKeys.add(ArticleKeys.appKeyFor(id))
                            }
                        }
                    }
                    missingKeys.addAll(
                        shortIds.filter { it !in found }.map { ArticleKeys.appKeyFor(it) }
                    )
                } catch (_: Exception) {
                    missingKeys.addAll(shortIds.map { ArticleKeys.appKeyFor(it) })
                }
            }
        }

        // Tombstones: keep the row, flip only its status, so a
        // bookmark on it survives and the feed stops showing it.
        val tombstoneRows = tombstones.mapNotNull { entry ->
            val id = entry.optString("id")
            val appKey = entry.optStringOrNull("app_key")?.takeIf { it.isNotBlank() }
                ?: ArticleKeys.appKeyFor(id)
            val stored = existingByKey[appKey] ?: return@mapNotNull null
            stored.copy(
                status = "retracted",
                retractedAtEpoch = entry.optLong("retracted_at_epoch", 0L).takeIf { it > 0L } ?: now,
                updatedAtEpoch = now,
            )
        }

        // Market travels in the same manifest-gated pass when the
        // edge serves it; a market failure makes the sync Partial,
        // never a silent success with stale market data. Formulas
        // travel with it (M4): a non-empty formulas_active replaces
        // the cached set, so a deactivated formula disappears
        // instead of lingering; an empty/missing array leaves the
        // cached set untouched rather than deleting it on a guess.
        var marketFresh = emptyList<MarketSnapshotEntity>()
        var formulasFresh = emptyList<MarketFormulaEntity>()
        var marketFailed = false
        val marketText = when (val outcome = httpGet("$base/market.json")) {
            is HttpOutcome.Ok -> outcome.body
            else -> null
        }
        if (marketText == null) {
            marketFailed = true
        } else {
            try {
                val marketObj = JSONObject(marketText)
                val snaps = marketObj.optJSONArray("snapshots") ?: JSONArray()
                val parsed = mutableListOf<MarketSnapshotEntity>()
                for (i in 0 until snaps.length()) {
                    try {
                        parsed.add(ArticleParsers.marketEntityFromJson(snaps.getJSONObject(i)))
                    } catch (_: Exception) {
                        continue
                    }
                }
                marketFresh = parsed
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
            } catch (_: Exception) {
                marketFailed = true
            }
        }

        val complete = missingKeys.isEmpty() && corruptEntries == 0 && !marketFailed
        val newKeys = fresh.map { it.appKey }
        db.withTransaction {
            if (fresh.isNotEmpty()) dao.upsertAll(fresh)
            if (tombstoneRows.isNotEmpty()) dao.upsertAll(tombstoneRows)
            if (marketFresh.isNotEmpty()) dao.upsertMarket(marketFresh)
            if (formulasFresh.isNotEmpty()) {
                dao.deleteAllFormulas()
                dao.upsertFormulas(formulasFresh)
            }
            dao.upsertMeta(MetaEntity("last_sync_attempt_at", now.toString()))
            if (complete) {
                if (hash.isNotBlank()) dao.upsertMeta(MetaEntity("index_hash", hash))
                dao.upsertMeta(MetaEntity("last_sync_at", now.toString()))
                dao.upsertMeta(MetaEntity("last_sync_error_code", ""))
            } else {
                dao.upsertMeta(MetaEntity("last_sync_error_code", "partial"))
            }
        }
        val total = dao.allIds().size
        return if (complete) {
            SyncResult.Ok(newKeys, total)
        } else {
            SyncResult.Partial(newKeys, missingKeys.size + corruptEntries, total)
        }
    }

    /**
     * Fetch one article by appKey, falling back to the verbatim
     * source id for older deep links. Short cards are fetchable
     * through the same path (they have no per-article file).
     */
    suspend fun fetchOne(appKeyOrId: String): Boolean {
        val base = edgeBase() ?: return false
        val indexText = when (val outcome = httpGet("$base/index.json")) {
            is HttpOutcome.Ok -> outcome.body
            else -> return false
        }
        val entries = try {
            JSONObject(indexText).optJSONArray("entries") ?: return false
        } catch (_: Exception) {
            return false
        }
        for (i in 0 until entries.length()) {
            val entry = try {
                entries.getJSONObject(i)
            } catch (_: Exception) {
                continue
            }
            val id = entry.optString("id")
            val appKey = entry.optStringOrNull("app_key")?.takeIf { it.isNotBlank() }
                ?: ArticleKeys.appKeyFor(id)
            if (appKey != appKeyOrId && id != appKeyOrId) continue
            if (entry.optString("kind") == "short") {
                val shortsText = when (val outcome = httpGet("$base/short_cards.json")) {
                    is HttpOutcome.Ok -> outcome.body
                    else -> return false
                }
                return try {
                    val arr = JSONArray(shortsText)
                    for (j in 0 until arr.length()) {
                        val obj = arr.getJSONObject(j)
                        if (obj.optString("id") == id) {
                            dao.upsertAll(listOf(ArticleParsers.entityFromShortJson(obj)))
                            return true
                        }
                    }
                    false
                } catch (_: Exception) {
                    false
                }
            }
            if (entry.isNull("file")) return false
            val text = when (val outcome = httpGet("$base/${entry.optString("file")}")) {
                is HttpOutcome.Ok -> outcome.body
                else -> return false
            }
            return try {
                dao.upsertAll(listOf(ArticleParsers.entityFromArticleJson(JSONObject(text))))
                true
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    /** Fetch market.json (snapshots + active formulas) from the edge. */
    suspend fun fetchMarket(): Triple<MarketSnapshot?, List<MarketFormula>, Int> {
        val base = edgeBase() ?: return Triple(null, emptyList(), 0)
        val text = when (val outcome = httpGet("$base/market.json")) {
            is HttpOutcome.Ok -> outcome.body
            else -> return Triple(null, emptyList(), 0)
        }
        return try {
            val obj = JSONObject(text)
            val snaps = obj.optJSONArray("snapshots") ?: JSONArray()
            var latest: MarketSnapshot? = null
            var latestEpoch: Long? = null
            for (i in 0 until snaps.length()) {
                val entity = ArticleParsers.marketEntityFromJson(snaps.getJSONObject(i))
                val snap = ArticleParsers.marketSnapshotFromEntity(entity)
                // Latest means max timestamp, not last array element.
                if (latest == null || (snap.tsEpoch ?: Long.MIN_VALUE) > (latestEpoch ?: Long.MIN_VALUE)) {
                    latest = snap
                    latestEpoch = snap.tsEpoch
                }
            }
            val forms = mutableListOf<MarketFormula>()
            val farr = obj.optJSONArray("formulas_active") ?: JSONArray()
            for (i in 0 until farr.length()) {
                try {
                    MarketFormulas.formulaFromJson(farr.getJSONObject(i))?.let { forms.add(it) }
                } catch (_: Exception) {
                    continue
                }
            }
            Triple(latest, forms, snaps.length())
        } catch (_: Exception) {
            Triple(null, emptyList(), 0)
        }
    }

    fun entityToArticle(e: ArticleEntity): Article {
        val points = try {
            val arr = JSONArray(e.pointsJson)
            (0 until arr.length()).map { arr.optString(it) }
        } catch (_: Exception) {
            emptyList()
        }
        return Article(
            id = e.id,
            appKey = e.appKey,
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
            publishedAtEpoch = e.publishedAtEpoch,
            contentHash = e.contentHash,
            status = e.status,
            channelMessageId = e.channelMessageId,
        )
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key)
