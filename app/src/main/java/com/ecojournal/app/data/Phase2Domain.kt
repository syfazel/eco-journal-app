package com.ecojournal.app.data

// Phase 2 domain rules (spec: phase2-channel-surfaces-spec.md).
// Pure functions over Room entities, no Android UI dependency, so
// the rules can be walked through against fixtures without a phone.
//
// Cross-phase rule (Yasin, 2026-10-25, applies to every later
// phase): Red Alert / War Room is its OWN SEPARATE CATEGORY
// inside the app — NOT a parallel service, NOT a fully separate
// tab. War-room/red-alert items AND energy-domain items get
// distinct category treatment with a yellow or red visual bar on
// feed cards and reader headers, while staying inside the same
// feed/tabs system. Bar colours [SPEC DECISION — the spec fixes
// badge colours but no bar colours, so this file fixes them]:
//   RED (#C62828) for war-room / red-alert items (every typed
//     channel item, any format — the red-alert category);
//   YELLOW (#F9A825) for energy-domain items (ordinary articles
//     whose category is "energy").
// A typed item is always RED even if its topic is energy: the
// red-alert category treatment outranks the domain colour. The
// bar label is the format badge text for typed items and «انرژی»
// for energy articles, so the category is distinct from ordinary
// categories by label as well as by colour, never by colour alone.

enum class DomainBar { NONE, RED_WAR, YELLOW_ENERGY }

object DomainBars {
    const val RED_ARGB = 0xFFC62828L
    const val YELLOW_ARGB = 0xFFF9A825L

    /** Typed channel items are the red-alert category: always RED. */
    fun forChannelItem(): DomainBar = DomainBar.RED_WAR

    /** Ordinary articles: YELLOW only for the energy category. */
    fun forArticle(article: ArticleEntity): DomainBar =
        if (article.category == "energy") DomainBar.YELLOW_ENERGY else DomainBar.NONE
}

// ---- Formats (spec section 3) ----

data class FormatInfo(
    val badgeText: String,
    /** ARGB colour; the UI converts to a Compose Color. */
    val badgeArgb: Long,
    /** Daily volume cap (items/day), null = no cap supplied. */
    val dailyCap: Int?,
)

// Badge colours are [SPEC DECISION] in the spec (fixed hex values).
// Caps are DAILY VOLUME caps, not word budgets (spec 3.1): FLASH
// 999 is a sentinel ceiling, not a target; WIRE has NO cap supplied
// (open item O-1) and must not inherit another format's cap.
object Formats {
    val ALL: Map<String, FormatInfo> = mapOf(
        "FLASH" to FormatInfo("فوری", 0xFFC62828L, 999),
        "WATCH" to FormatInfo("تحت نظر", 0xFFE65100L, 60),
        "GEOPOL" to FormatInfo("ژئوپلیتیک", 0xFF00695CL, 60),
        "MARKET" to FormatInfo("بازار", 0xFF2E7D32L, 30),
        "VERIFY" to FormatInfo("راستی‌آزمایی", 0xFF6A1B9AL, 40),
        "SITREP" to FormatInfo("گزارش وضعیت", 0xFF1565C0L, 12),
        "WIRE" to FormatInfo("وایر", 0xFF616161L, null),
    )

    fun info(formatType: String): FormatInfo? = ALL[formatType]
    fun badgeText(formatType: String): String = ALL[formatType]?.badgeText ?: formatType
    fun isKnown(formatType: String): Boolean = formatType in ALL
}

// Crisis-level chip colours [SPEC DECISION, spec 4.1].
object CrisisLevels {
    val COLORS: Map<String, Long> = mapOf(
        "L1" to 0xFF616161L,
        "L2" to 0xFFF9A825L,
        "L3" to 0xFFE65100L,
        "L4" to 0xFFC62828L,
    )

    fun isKnown(level: String?): Boolean = level in COLORS
}

// ---- Slots (spec section 7) ----

object Slots {
    const val MORNING = "morning"
    const val MIDDAY = "midday"
    const val EVENING = "evening"
    const val NIGHT = "night"

    /** Fixed slot order; NULL slot sorts last, as «بدون بازه». */
    fun orderIndex(slot: String?): Int = when (slot) {
        MORNING -> 0
        MIDDAY -> 1
        EVENING -> 2
        NIGHT -> 3
        else -> 4
    }

    fun header(slot: String?): String = when (slot) {
        MORNING -> "صبح"
        MIDDAY -> "ظهر"
        EVENING -> "عصر"
        NIGHT -> "شب"
        else -> "بدون بازه"
    }
}

// ---- Bundle kinds (spec section 8) ----

object BundleKinds {
    const val SLOT_BUNDLE = "slot_bundle"
    const val SHORT_NEWS = "short_news"
    const val MARGINALIA = "marginalia"
    const val HOURLY_DIGEST = "hourly_digest"
    const val MORNING_BUNDLE = "morning_bundle"
    const val NIGHT_DESK = "night_desk"
    const val MORNING_DIGEST = "morning_digest"

    val KNOWN: Set<String> = setOf(
        SLOT_BUNDLE, SHORT_NEWS, MARGINALIA, HOURLY_DIGEST,
        MORNING_BUNDLE, NIGHT_DESK,
    )

    fun label(kind: String): String = when (kind) {
        SLOT_BUNDLE -> "بسته بازه"
        SHORT_NEWS -> "خبرهای کوتاه"
        MARGINALIA -> "حاشیه‌ها"
        HOURLY_DIGEST -> "گزیده ساعتی"
        MORNING_BUNDLE -> "بسته صبح"
        NIGHT_DESK -> "جامانده‌های دیشب"
        else -> "بسته"
    }
}

// ---- Attribution (spec 2.6) ----

enum class Attribution { VERBATIM, TRANSLATED, WIRE, NORMAL }

// ONLY Donya-e Eqtesad and Tabnak may ever be verbatim (spec 2.6).
// Matching is on the source name as shipped; a verbatim claim from
// any other source renders as NORMAL and counts
// `verbatim_not_allowed` (see Phase2Anomalies). The edge must ship
// `attribution` explicitly ("verbatim" / "translated"); the app
// never infers verbatim from a source name alone.
object AttributionRules {
    private fun normalized(source: String): String =
        source.trim().lowercase()

    fun isVerbatimSource(sourceName: String): Boolean {
        val s = normalized(sourceName)
        return s.contains("دنیای اقتصاد") || s.contains("تابناک") ||
            s.contains("donya-e eqtesad") || s.contains("tabnak")
    }

    fun resolve(
        formatType: String,
        attributionField: String?,
        sourceName: String,
    ): Attribution {
        if (formatType == "WIRE") return Attribution.WIRE
        return when (attributionField) {
            "verbatim" -> if (isVerbatimSource(sourceName)) {
                Attribution.VERBATIM
            } else {
                Attribution.NORMAL
            }
            "translated" -> Attribution.TRANSLATED
            else -> Attribution.NORMAL
        }
    }

    fun sourceLine(attribution: Attribution, sourceName: String): String {
        val display = sourceName.ifBlank { "منبع نامشخص" }
        return when (attribution) {
            Attribution.VERBATIM -> "بازنشر از $display"
            Attribution.TRANSLATED -> "ترجمهٔ فارسی تحریریه"
            Attribution.WIRE -> display
            Attribution.NORMAL -> display
        }
    }
}

// ---- Verdicts (spec 3.6) ----

object Verdicts {
    fun chipText(status: String?): String? = when (status) {
        "pending" -> "حکم اتاق جنگ: ⏳ در حال بررسی"
        "confirmed" -> "تأیید شد"
        "refuted" -> "رد شد"
        else -> null
    }
}

// ---- Fixed Phase 2 Diagnostics anomaly codes (spec section 10) ----
// Codes added by this implementation for cases the spec leaves
// unnamed are marked; all others are verbatim from the spec.
object Phase2Anomalies {
    const val FORMAT_OVER_CAP = "format_over_cap"
    const val FORMAT_INCOMPLETE = "format_incomplete"
    const val BAD_FORMAT_TYPE = "bad_format_type"
    const val BAD_URGENCY_SCORE = "bad_urgency_score"
    const val BAD_BUNDLE_MEMBERSHIP = "bad_bundle_membership"
    const val BAD_BUNDLE_KIND = "bad_bundle_kind"
    const val WIRE_LINK_DROPPED = "wire_link_dropped"
    const val MARKET_CARD_MISMATCH = "market_card_mismatch"
    const val VERIFY_STATUS_MISSING = "verify_status_missing"
    const val SITREP_SECTION_COUNT = "sitrep_section_count"
    const val SITREP_WATCH_NEXT_COUNT = "sitrep_watch_next_count"
    const val BUNDLE_TITLE_MISSING = "bundle_title_missing"
    const val BUNDLE_POSITION_MISMATCH = "bundle_position_mismatch"
    const val SHORT_NEWS_TWICE = "short_news_twice"
    const val SHORT_NEWS_SMALL = "short_news_small"
    const val MORNING_DIGEST_BLOCKED = "morning_digest_blocked"
    const val MORNING_BUNDLE_TWICE = "morning_bundle_twice"
    const val NIGHT_DESK_RANGE = "night_desk_range"
    const val RADAR_INTERVAL_MISSING = "radar_interval_missing"
    const val BAD_RADAR_FIELD = "bad_radar_field"
    const val SOURCE_NAME_MISSING = "source_name_missing"
    const val FLASH_CAP_HIT = "flash_cap_hit"
    // Implementation additions (spec leaves these cases unnamed):
    const val BAD_HEADLINE = "bad_headline" // spec 2.2 names it
    const val BAD_PUBLISHED_AT = "bad_published_at"
    const val BAD_STATUS = "bad_status"
    const val BAD_SITREP_EMPTY = "bad_sitrep_empty"
    const val VERBATIM_NOT_ALLOWED = "verbatim_not_allowed"

    val ALL: List<String> = listOf(
        FORMAT_OVER_CAP, FORMAT_INCOMPLETE, BAD_FORMAT_TYPE,
        BAD_URGENCY_SCORE, BAD_BUNDLE_MEMBERSHIP, BAD_BUNDLE_KIND,
        WIRE_LINK_DROPPED, MARKET_CARD_MISMATCH, VERIFY_STATUS_MISSING,
        SITREP_SECTION_COUNT, SITREP_WATCH_NEXT_COUNT,
        BUNDLE_TITLE_MISSING, BUNDLE_POSITION_MISMATCH,
        SHORT_NEWS_TWICE, SHORT_NEWS_SMALL, MORNING_DIGEST_BLOCKED,
        MORNING_BUNDLE_TWICE, NIGHT_DESK_RANGE, RADAR_INTERVAL_MISSING,
        BAD_RADAR_FIELD, SOURCE_NAME_MISSING, FLASH_CAP_HIT,
        BAD_HEADLINE, BAD_PUBLISHED_AT, BAD_STATUS, BAD_SITREP_EMPTY, VERBATIM_NOT_ALLOWED,
    )
}

// ---- List rules (spec 2.5, 5.3, 6.3, 7) ----

object ChannelLists {
    /**
     * One story = one card: keep only the latest published item
     * per story_id. Items with a null story_id are never merged
     * (no id, no dedup). Ties break by appKey ascending, so the
     * result is deterministic, never insertion-ordered.
     */
    fun dedupLatest(items: List<ChannelItemEntity>): List<ChannelItemEntity> {
        val byStory = mutableMapOf<String, ChannelItemEntity>()
        val noStory = mutableListOf<ChannelItemEntity>()
        for (item in items) {
            val story = item.storyId
            if (story.isNullOrBlank()) {
                noStory.add(item)
                continue
            }
            val prev = byStory[story]
            if (prev == null || item.publishedAtEpoch > prev.publishedAtEpoch ||
                (item.publishedAtEpoch == prev.publishedAtEpoch &&
                    item.appKey < prev.appKey)
            ) {
                byStory[story] = item
            }
        }
        return (byStory.values + noStory)
            .sortedWith(
                compareByDescending<ChannelItemEntity> { it.publishedAtEpoch }
                    .thenBy { it.appKey }
            )
    }

    /** Reader timeline for one story: chronological, oldest first. */
    fun storyTimeline(items: List<ChannelItemEntity>): List<ChannelItemEntity> =
        items.sortedWith(
            compareBy<ChannelItemEntity> { it.publishedAtEpoch }.thenBy { it.appKey }
        )

    /**
     * Urgent rail order (spec 5.3): FLASH first (newest first),
     * then other instant items by score descending (null scores
     * last — only exemption instant items can lack a score),
     * then published time descending, then appKey ascending.
     */
    fun railOrder(items: List<ChannelItemEntity>): List<ChannelItemEntity> {
        val (flash, rest) = items.partition { it.formatType == "FLASH" }
        val flashSorted = flash.sortedWith(
            compareByDescending<ChannelItemEntity> { it.publishedAtEpoch }
                .thenBy { it.appKey }
        )
        val restSorted = rest.sortedWith(
            compareByDescending<ChannelItemEntity> { it.urgencyScore ?: Int.MIN_VALUE }
                .thenByDescending { it.publishedAtEpoch }
                .thenBy { it.appKey }
        )
        return flashSorted + restSorted
    }

    /**
     * Radar ordering within a layer (spec 6.3): ranked severity
     * first (rank descending, as shipped), then recency, then
     * signal id. Unranked signals sort after all ranked signals.
     */
    fun radarOrder(signals: List<RadarSignalEntity>): List<RadarSignalEntity> =
        signals.sortedWith(
            compareByDescending<RadarSignalEntity> { it.severityRank ?: Int.MIN_VALUE }
                .thenByDescending { it.detectedAtEpoch }
                .thenBy { it.signalId }
        )

    /** Stale = older than one full radar interval (spec 6.4). */
    fun isSignalStale(
        signal: RadarSignalEntity,
        now: Long,
        radarIntervalMinutes: Int?,
    ): Boolean {
        if (radarIntervalMinutes == null) return false
        return now - signal.detectedAtEpoch > radarIntervalMinutes * 60L * 1000L
    }
}
