package com.ecojournal.app.data

/**
 * Article model shared by the edge JSON, Room cache and UI.
 * Field names match the edge JSON files exactly (see edge/generate_edge.py).
 * Body text is copied verbatim from the source databases; this layer
 * never rewrites, summarises or invents article text.
 */
data class Article(
    val id: String,
    val appKey: String = "",
    val kind: String, // "article" (full) or "short" (labelled گزیده)
    val title: String,
    val summary: String,
    val bodyBlocks: List<String> = emptyList(),
    val points: List<String> = emptyList(),
    val whyMatters: String? = null,
    val category: String,
    val categoryFa: String,
    val source: String,
    val sourceSlug: String? = null,
    val originalUrl: String? = null,    val publishedAt: String? = null,
    val publishedAtEpoch: Long? = null,
    val contentHash: String? = null,
    val status: String = "approved",
    val channelMessageId: Long? = null,
    val publishStatus: String? = null,
    val provenance: String? = null,
    val file: String? = null,
)

data class IndexEntry(
    val id: String,
    val kind: String,
    val title: String,
    val summary: String,
    val category: String,
    val categoryFa: String,
    val source: String,
    val publishedAt: String?,
    val file: String?,
    val channelMessageId: Long?,
)

data class EdgeIndex(
    val version: Int,
    val hashSha256: String,
    val counts: Map<String, Int>,
    val entries: List<IndexEntry>,
)

/**
 * Market snapshot (M4): every field the bundled/edge market.json
 * carries, not the 5-field subset M2/M3 showed. The Room entity
 * already stored all 13 prices; only this UI model lagged behind,
 * so the board silently dropped 8 fields. A null price means the
 * snapshot genuinely has no value for it (early snapshots predate
 * several series) and the UI must render "—", never omit the row.
 * Field order here follows market.json, not display order.
 */
data class MarketSnapshot(
    val snapshotId: String = "",
    val tsTehran: String,
    val tsEpoch: Long? = null,
    val brent: Double?,
    val xauUsd: Double?,
    val dxy: Double?,
    val usdtUsd: Double?,
    val usdIrtFree: Double?,
    val usdtIrt: Double?,
    val gold18kGram: Double?,
    val sekkeEmami: Double?,
    val sekkeBahar: Double?,
    val nimSekke: Double?,
    val robSekke: Double?,
    val vix: Double?,
    val sp500: Double?,
)

data class MarketFormula(
    val name: String,
    val version: Int,
    val definition: String,
    val inputs: String?,
    val descriptionFa: String?,
)
