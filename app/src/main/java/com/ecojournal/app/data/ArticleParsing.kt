package com.ecojournal.app.data

import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/**
 * Single parser module shared by the bundled seed and the edge sync.
 * Before M1/M2 each of those had its own copy of the same two parsers,
 * and they had already drifted apart (short-card truncation). There
 * is now exactly one place that turns JSON into a Room row.
 *
 * Identity rule (M1): the verbatim source id is kept as [ArticleEntity.id]
 * and is never used in a navigation route. Routes, bookmarks and
 * notifications use [ArticleEntity.appKey], a URL-safe surrogate
 * derived from the source id. It contains only lower-case hex after
 * a short prefix, so it is safe in a single-segment route with no
 * further encoding, although callers still encode defensively.
 */
object ArticleKeys {
    fun appKeyFor(externalId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(externalId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "a-" + hex.take(20)
    }
}

object ContentHashes {
    fun forArticle(
        title: String,
        summary: String,
        bodyBlocks: List<String>,
        pointsJson: String,
        whyMatters: String?,
        category: String,
        source: String,
        originalUrl: String?,
        publishedAt: String?,
    ): String {
        val canonical = listOf(
            title,
            summary,
            bodyBlocks.joinToString("\n"),
            pointsJson,
            whyMatters ?: "",
            category,
            source,
            originalUrl ?: "",
            publishedAt ?: "",
        ).joinToString("\u0001")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Date normalisation (M2, fixes the lexicographic feed order).
 *
 * The seed carries at least five raw shapes, all meaning an instant:
 * ISO-8601 with an offset, RFC-1123 with GMT, RFC-1123 with a numeric
 * offset, a weekday-less day-month-year form with a numeric offset,
 * and RFC-1123 with a named US zone (EST, seen twice, with a double
 * space before the time). Every one of them is parsed here into epoch
 * millis. Anything unparseable yields null, and the caller must sort
 * such rows last, never crash and never invent a date.
 */
object PublishedAtParser {
    private val namedZones = mapOf(
        "EST" to "-0500",
        "EDT" to "-0400",
        "CST" to "-0600",
        "CDT" to "-0500",
        "PST" to "-0800",
        "PDT" to "-0700",
    )

    private val rfcFormatter = DateTimeFormatter.ofPattern(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        Locale.US,
    )
    private val plainFormatter = DateTimeFormatter.ofPattern(
        "dd MMM yyyy HH:mm:ss Z",
        Locale.US,
    )

    fun epochMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val squashed = raw.trim().replace(Regex("\\s+"), " ")
        // ISO-8601 first: OffsetDateTime handles offsets and fractions.
        try {
            return OffsetDateTime.parse(squashed).toInstant().toEpochMilli()
        } catch (_: Exception) {
            // Fall through to the RFC shapes below.
        }
        var normalised = squashed
        if (normalised.endsWith(" GMT")) {
            normalised = normalised.removeSuffix(" GMT") + " +0000"
        } else {
            for ((name, offset) in namedZones) {
                if (normalised.endsWith(" $name")) {
                    normalised = normalised.removeSuffix(" $name") + " $offset"
                    break
                }
            }
        }
        for (formatter in listOf(rfcFormatter, plainFormatter)) {
            try {
                return OffsetDateTime.parse(normalised, formatter)
                    .toInstant().toEpochMilli()
            } catch (_: Exception) {
                // Try the next shape.
            }
        }
        return null
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key)

private fun JSONObject.stringList(key: String): List<String> {
    val arr = optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).map { arr.optString(it) }
}

object ArticleParsers {
    private fun statusOf(obj: JSONObject): String {
        return when (obj.optString("status", "approved")) {
            "approved", "posted", "retracted" -> obj.optString("status", "approved")
            else -> "approved"
        }
    }

    private fun updatedAtOf(obj: JSONObject, publishedEpoch: Long?, now: Long): Long {
        val explicit = if (obj.has("updated_at_epoch") && !obj.isNull("updated_at_epoch")) {
            obj.optLong("updated_at_epoch", 0L)
        } else {
            0L
        }
        return when {
            explicit > 0L -> explicit
            publishedEpoch != null -> publishedEpoch
            else -> now
        }
    }

    fun entityFromArticleJson(obj: JSONObject, now: Long = System.currentTimeMillis()): ArticleEntity {
        val id = obj.getString("id")
        val blocks = obj.stringList("body_blocks")
        val pointsJson = obj.optJSONArray("points")?.toString() ?: "[]"
        val publishedAt = obj.optStringOrNull("published_at")
        val publishedEpoch = PublishedAtParser.epochMillis(publishedAt)
        val explicitHash = obj.optStringOrNull("content_hash")?.takeIf { it.isNotBlank() }
        return ArticleEntity(
            id = id,
            appKey = obj.optStringOrNull("app_key")?.takeIf { it.isNotBlank() }
                ?: ArticleKeys.appKeyFor(id),
            kind = obj.optString("kind", "article"),
            title = obj.getString("title"),
            summary = obj.optString("summary", ""),
            bodyBlocks = blocks,
            pointsJson = pointsJson,
            whyMatters = obj.optStringOrNull("why_matters"),
            category = obj.optString("category", "general"),
            categoryFa = obj.optString("category_fa", "عمومی"),
            source = obj.optString("source", ""),
            originalUrl = obj.optStringOrNull("original_url"),
            publishedAt = publishedAt,
            publishedAtEpoch = publishedEpoch,
            contentHash = explicitHash ?: ContentHashes.forArticle(
                title = obj.getString("title"),
                summary = obj.optString("summary", ""),
                bodyBlocks = blocks,
                pointsJson = pointsJson,
                whyMatters = obj.optStringOrNull("why_matters"),
                category = obj.optString("category", "general"),
                source = obj.optString("source", ""),
                originalUrl = obj.optStringOrNull("original_url"),
                publishedAt = publishedAt,
            ),
            updatedAtEpoch = updatedAtOf(obj, publishedEpoch, now),
            status = statusOf(obj),
            retractedAtEpoch = if (obj.has("retracted_at_epoch") && !obj.isNull("retracted_at_epoch")) {
                obj.optLong("retracted_at_epoch", 0L).takeIf { it > 0L }
            } else {
                null
            },
            channelMessageId = if (obj.isNull("channel_message_id")) null
                else obj.optLong("channel_message_id"),
            cachedAt = now,
        )
    }

    // Phase 1: shorts carry an explicit "uncategorised" marker,
    // never a silent "general". The category is read from the
    // object when the edge ships one and defaults to the explicit
    // marker otherwise; the content hash is computed over the same
    // resolved category, so it stays byte-identical to the
    // generator's recomputation (edge/generate_edge.py). Note: the
    // first v3 sync refetches all 32 shorts because their hash
    // changes with the category — that is correct, not a bug.
    fun entityFromShortJson(obj: JSONObject, now: Long = System.currentTimeMillis()): ArticleEntity {
        val id = obj.getString("id")
        val text = obj.optString("text", "")
        val publishedAt = obj.optStringOrNull("decided_at")
        val publishedEpoch = PublishedAtParser.epochMillis(publishedAt)
        val category = obj.optString("category", "uncategorised")
            .takeIf { it.isNotBlank() } ?: "uncategorised"
        val categoryFa = obj.optString("category_fa", "دسته‌بندی‌نشده")
            .takeIf { it.isNotBlank() } ?: "دسته‌بندی‌نشده"
        return ArticleEntity(
            id = id,
            appKey = obj.optStringOrNull("app_key")?.takeIf { it.isNotBlank() }
                ?: ArticleKeys.appKeyFor(id),
            kind = "short",
            title = obj.getString("title"),
            summary = if (text.length <= 280) text else text.take(277) + "…",
            bodyBlocks = listOf(text),
            pointsJson = "[]",
            whyMatters = null,
            category = category,
            categoryFa = categoryFa,
            source = obj.optString("source", ""),
            originalUrl = obj.optStringOrNull("original_url"),
            publishedAt = publishedAt,
            publishedAtEpoch = publishedEpoch,
            contentHash = ContentHashes.forArticle(
                title = obj.getString("title"),
                summary = text,
                bodyBlocks = listOf(text),
                pointsJson = "[]",
                whyMatters = null,
                category = category,
                source = obj.optString("source", ""),
                originalUrl = obj.optStringOrNull("original_url"),
                publishedAt = publishedAt,
            ),
            updatedAtEpoch = updatedAtOf(obj, publishedEpoch, now),
            status = statusOf(obj),
            retractedAtEpoch = null,
            channelMessageId = if (obj.isNull("channel_message_id")) null
                else obj.optLong("channel_message_id"),
            cachedAt = now,
        )
    }

    fun marketEntityFromJson(obj: JSONObject): MarketSnapshotEntity {
        val tsTehran = obj.optString("ts_tehran", "")
        return MarketSnapshotEntity(
            snapshotId = obj.optString("snapshot_id", tsTehran),
            tsTehran = tsTehran,
            tsEpoch = PublishedAtParser.epochMillis(tsTehran),
            brent = if (obj.isNull("brent")) null else obj.optDouble("brent"),
            xauUsd = if (obj.isNull("xau_usd")) null else obj.optDouble("xau_usd"),
            dxy = if (obj.isNull("dxy")) null else obj.optDouble("dxy"),
            usdtUsd = if (obj.isNull("usdt_usd")) null else obj.optDouble("usdt_usd"),
            usdIrtFree = if (obj.isNull("usd_irt_free")) null else obj.optDouble("usd_irt_free"),
            usdtIrt = if (obj.isNull("usdt_irt")) null else obj.optDouble("usdt_irt"),
            gold18kGram = if (obj.isNull("gold_18k_gram")) null else obj.optDouble("gold_18k_gram"),
            sekkeEmami = if (obj.isNull("sekke_emami")) null else obj.optDouble("sekke_emami"),
            sekkeBahar = if (obj.isNull("sekke_bahar")) null else obj.optDouble("sekke_bahar"),
            nimSekke = if (obj.isNull("nim_sekke")) null else obj.optDouble("nim_sekke"),
            robSekke = if (obj.isNull("rob_sekke")) null else obj.optDouble("rob_sekke"),
            vix = if (obj.isNull("vix")) null else obj.optDouble("vix"),
            sp500 = if (obj.isNull("sp500")) null else obj.optDouble("sp500"),
        )
    }

    fun marketSnapshotFromEntity(e: MarketSnapshotEntity): MarketSnapshot {
        return MarketSnapshot(
            snapshotId = e.snapshotId,
            tsTehran = e.tsTehran,
            tsEpoch = e.tsEpoch,
            brent = e.brent,
            xauUsd = e.xauUsd,
            dxy = e.dxy,
            usdtUsd = e.usdtUsd,
            usdIrtFree = e.usdIrtFree,
            usdtIrt = e.usdtIrt,
            gold18kGram = e.gold18kGram,
            sekkeEmami = e.sekkeEmami,
            sekkeBahar = e.sekkeBahar,
            nimSekke = e.nimSekke,
            robSekke = e.robSekke,
            vix = e.vix,
            sp500 = e.sp500,
        )
    }
}
