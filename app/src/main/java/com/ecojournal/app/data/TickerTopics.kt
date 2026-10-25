package com.ecojournal.app.data

/**
 * v4-H: CNN-style ticker topics + Story Arc Strip data (pure logic,
 * no Android dependency, so the rules are testable without a phone).
 *
 * Ticker rule: ONLY live war-room topics — instant typed items
 * (nightLawResult == "instant"), newest first, deduped by story_id
 * (falling back to appKey when a story_id is absent), capped at 10.
 * Held, feed-only, and retracted items NEVER reach the ticker: a
 * held night item appearing in the ticker would break Law 15, and
 * a retracted item would advertise content the reader can no longer
 * open. An empty list is an honest empty state, never a fabricated
 * topic and never a recycled old headline.
 *
 * Story Arc rule (H2): nodes are the article itself plus the typed
 * items sharing its story_id, oldest first. Retracted versions get
 * NO node (a node must always open something real). A story with a
 * single version gets NO strip at all — the strip is a navigation
 * aid over versions, not decoration on a single article.
 */
object TickerTopics {
    const val MAX_TOPICS = 10

    fun topics(items: List<ChannelItemEntity>): List<String> {
        val seen = mutableSetOf<String>()
        return items
            .filter { it.status != "retracted" && it.nightLawResult == "instant" }
            .sortedByDescending { it.publishedAtEpoch }
            .filter { item ->
                val key = item.storyId?.takeIf { s -> s.isNotBlank() } ?: item.appKey
                seen.add(key)
            }
            .mapNotNull { it.headline.takeIf { h -> h.isNotBlank() } }
            .take(MAX_TOPICS)
    }

    /** Joined marquee text; empty when there is no live topic. */
    fun marqueeText(topics: List<String>): String =
        topics.joinToString(separator = "   •   ")
}

/** One node of a story's arc. kind: "article" | "typed". */
data class ArcNode(
    val appKey: String,
    val title: String,
    val epoch: Long,
    val kind: String,
    val formatType: String?,
)

object StoryArc {
    /**
     * Article + typed items sharing its story_id, oldest first,
     * retracted rows dropped. Returns empty when fewer than 2
     * nodes survive — the caller renders no strip in that case.
     */
    fun nodes(
        article: ArticleEntity,
        typed: List<ChannelItemEntity>,
    ): List<ArcNode> {
        val articleEpoch = article.publishedAtEpoch ?: 0L
        val typedNodes = typed
            .filter { it.status != "retracted" }
            .map {
                ArcNode(
                    appKey = it.appKey,
                    title = it.headline,
                    epoch = it.publishedAtEpoch,
                    kind = "typed",
                    formatType = it.formatType,
                )
            }
        val all = typedNodes + ArcNode(
            appKey = article.appKey,
            title = article.title,
            epoch = articleEpoch,
            kind = "article",
            formatType = null,
        )
        val sorted = all.sortedWith(
            compareBy<ArcNode> { it.epoch }.thenBy { it.appKey }
        )
        return if (sorted.size < 2) emptyList() else sorted
    }
}
