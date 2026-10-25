package com.ecojournal.app.data

import org.json.JSONArray
import org.json.JSONObject

// Phase 5 — Nightly big blog update: pure domain rules + edge parsing.
//
// Spec: docs/big-update/phases4-5-market-nightly-spec.md §5.
// Status: LAB ONLY. The nightly generator runs in dry-run only
// during the freeze (spec §5.16); a dry-run edition is a lab file
// and must NEVER appear in the app as published content. The app
// syncs ONLY editions the manifest nightly section publishes
// (status published/corrected, never draft).
//
// U4 — ENTRY GATE, STILL BLOCKING FOR REAL DATA (see
// docs/phase5-report.md): no tier_a, brief-score, or ownrewrite
// field exists in current edge data or in the production
// databases (article_extracts / items / drafts carry no such
// column; the keypoints rule-score in yasineco_bot/keypoints.py
// is computed at selection time and never stored, so it cannot
// be sourced from data). The generator therefore HOLDS every
// Tier-A / brief / ownrewrite slot whose selection is not
// supplied by an explicit LAB selection file, with reasons
// tier_flag_missing / brief_score_missing /
// ownrewrite_score_missing — never a guessed score, never a
// silent substitution (urgency_score is the Law 15 score for
// typed items only; it is NOT the brief score, and the keypoints
// rule-score is NOT the brief score either — the spec warns
// never to run two meanings of "≥70" at once). Complete
// component-1 editions exist ONLY in lab fixtures, where the
// selection labels are explicit fixture data, labelled as such.
// A real edition gated on guessed selection must not ship.
//
// Edition identity: edition_id = "edition-" + Shamsi date
// (Latin digits, e.g. edition-1405-08-04). Corrections create
// version 2+ of the SAME edition_id; the edge keeps every
// version file (v1 is the audit trail). The app keeps only the
// latest version row per edition (edge keeps all versions —
// recorded deviation from the spec's Room note, which says one
// row per edition_id+version with a latest flag; the app's
// latest-only storage is equivalent for display because the
// correction notes travel inside the latest version's
// component 6, and the edge v1 file remains the audit trail).
// Dossier identity: dossier-<shamsi-year>-W<week>-<topic-slug>;
// week = Shamsi week Saturday–Friday (spec §5.8).

/** Component states (spec §5.6): never silently absent. */
object NightlyComponentStates {
    const val PRESENT = "present"
    const val NONE_TONIGHT = "none_tonight"
    const val HELD_WITH_REASONS = "held_with_reasons"
    const val PAUSED = "paused"
}

/** Edition statuses (spec §5.12). The app NEVER syncs "draft". */
object NightlyEditionStatuses {
    const val DRAFT = "draft"
    const val PUBLISHED = "published"
    const val CORRECTED = "corrected"
}

/** Held reasons for selection slots (U4). Never a score. */
object NightlyHeldReasons {
    const val TIER_FLAG_MISSING = "tier_flag_missing"
    const val BRIEF_SCORE_MISSING = "brief_score_missing"
    const val OWNREWRITE_SCORE_MISSING = "ownrewrite_score_missing"
    const val FLOOR_FULL = "floor_full_800"
    const val FLOOR_ARTICLE = "floor_article_300"
    const val FLOOR_SUMMARY = "floor_summary_300"
    const val FRESH_TEXT = "fresh_text_lt_60"
    const val ANALYSIS_STRUCTURE = "analysis_structure"
    const val ANALYSIS_BAND = "analysis_band"
}

/** Word counting (spec §5.4): whitespace split over normalized
 * text. Half-space (ZWNJ, U+200C) JOINS a word (می‌کنم is one
 * word), so it is removed, not turned into a space — turning it
 * into a space would inflate counts. Persian/Arabic digits are
 * unified to Latin for counting only; display keeps Persian
 * digits. Headlines, bylines, source lines and «اصلاح شد» notes
 * never count (callers pass the BODY only). An emoji counts as
 * a token (the redalert word-count lesson). */
object NightlyWords {
    private val arabicDigits = mapOf(
        '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
        '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
        '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
        '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9',
    )

    fun normalizeForCount(text: String): String {
        val joined = text.replace("\u200C", "")
        val digits = joined.map { arabicDigits[it] ?: it }.joinToString("")
        return digits.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    fun count(text: String): Int {
        val normalized = normalizeForCount(text)
        if (normalized.isBlank()) return 0
        return normalized.split(" ").size
    }

    /** Freshness normalization (spec §5.5): count normalization
     * plus punctuation stripped and Latin tokens lowercased. */
    fun normalizeForFreshness(text: String): List<String> {
        val counted = normalizeForCount(text)
        val stripped = counted.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        return stripped.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    fun ngrams(words: List<String>, n: Int): Set<String> {
        if (words.size < n) return emptySet()
        return (0..words.size - n).map { words.subList(it, it + n).joinToString(" ") }.toSet()
    }

    /** Freshness = 1 − shared-5-grams / article-5-grams (§5.5).
     * An article too short to form a 5-gram cannot prove
     * freshness: freshness 0.0 (fails the gate, never passes by
     * default). */
    fun freshness(articleBody: String, channelPostBody: String): Double {
        val articleGrams = ngrams(normalizeForFreshness(articleBody), 5)
        if (articleGrams.isEmpty()) return 0.0
        val postGrams = ngrams(normalizeForFreshness(channelPostBody), 5)
        val shared = articleGrams.intersect(postGrams).size
        return 1.0 - shared.toDouble() / articleGrams.size.toDouble()
    }
}

/** Floor decisions (spec §5.4). Held, never padded. */
data class FloorDecision(val passed: Boolean, val reason: String?)

object NightlyFloors {
    const val ARTICLE_MIN = 300
    const val FULL_MIN = 800
    const val SUMMARY_MIN = 300
    const val SUMMARY_SOFT_MAX = 500
    const val FRESH_MIN = 0.60

    /** Full article: ≥800 words AND freshness ≥0.60. */
    fun fullArticle(wordCount: Int, freshness: Double?): FloorDecision {
        if (wordCount < FULL_MIN) return FloorDecision(false, NightlyHeldReasons.FLOOR_FULL)
        if (freshness != null && freshness < FRESH_MIN) {
            return FloorDecision(false, NightlyHeldReasons.FRESH_TEXT)
        }
        return FloorDecision(true, null)
    }

    /** Article (any kind): ≥300 words. */
    fun article(wordCount: Int): FloorDecision =
        if (wordCount >= ARTICLE_MIN) FloorDecision(true, null)
        else FloorDecision(false, NightlyHeldReasons.FLOOR_ARTICLE)

    /** Summary: 300–500 words. Over 500 is NOT a failure — the
     * caller re-classes: ≥800 + fresh → full article, else held
     * for editing (never truncated to fit). */
    fun summary(wordCount: Int): FloorDecision =
        if (wordCount >= SUMMARY_MIN) FloorDecision(true, null)
        else FloorDecision(false, NightlyHeldReasons.FLOOR_SUMMARY)
}

object NightlyKeys {
    fun editionIdForShamsiDayKey(dayKey: String): String = "edition-$dayKey"

    fun editionKey(editionId: String, storyId: String): String = "$editionId+$storyId"

    /** Shamsi week id (Saturday–Friday). The caller supplies the
     * week-start day key; this function only formats the id, it
     * never derives a week boundary from a timestamp. */
    fun dossierId(weekStartDayKey: String, topicSlug: String): String {
        val year = weekStartDayKey.take(4)
        val week = weekStartDayKey
        return "dossier-$year-W$week-$topicSlug"
    }
}

// ---- Parsed edge shapes (app-side; the generator writes these) ----

data class NightlyItemParsed(
    val editionKey: String,
    val storyId: String,
    val appKey: String?,
    val kind: String,
    val title: String,
    val body: String,
    val wordCount: Int,
    val freshness: Double?,
    val sourceName: String,
    val sourceUrl: String?,
    val verbatimBadge: String?,
    val formatType: String?,
    val crisisLevel: String?,
    val category: String?,
    val categoryFa: String?,
    val slot: String?,
    val bundleId: String?,
    val position: Int?,
)

data class NightlyEditionParsed(
    val editionId: String,
    val version: Int,
    val shamsiDate: String,
    val tehranDate: String,
    val status: String,
    val generatedAtTehran: String,
    val publishedAtTehran: String?,
    val cutTehran: String,
    val contentHash: String?,
    val articlesState: String,
    val articlesJson: String,
    val marketCloseState: String,
    val marketCloseJson: String,
    val digestsState: String,
    val digestsJson: String,
    val analysisState: String,
    val analysisJson: String,
    val specialIssuesState: String,
    val correctionsState: String,
    val correctionsJson: String,
    val dossierLinksJson: String,
    val items: List<NightlyItemParsed>,
)

/** Parses one edition file. Returns null on ANY structural
 * problem (bad edition_id, bad version, draft status, missing
 * components object, article without title/body/story_id) — a
 * corrupt edition is counted and skipped, never partially
 * applied (Phase 1 corrupt-item rule). Draft editions are
 * rejected here even if a manifest ever listed one: the app
 * never syncs a draft. */
object NightlyParsing {
    fun parseEdition(obj: JSONObject): NightlyEditionParsed? {
        return try {
            val editionId = obj.optString("edition_id")
            if (editionId.isBlank() || !editionId.startsWith("edition-")) return null
            val status = obj.optString("status")
            if (status == NightlyEditionStatuses.DRAFT) return null
            if (status != NightlyEditionStatuses.PUBLISHED &&
                status != NightlyEditionStatuses.CORRECTED
            ) return null
            val components = obj.optJSONObject("components") ?: return null
            val articlesObj = components.optJSONObject("articles") ?: return null
            val marketObj = components.optJSONObject("market_close")
            val digestsObj = components.optJSONObject("digests")
            val analysisObj = components.optJSONObject("analysis")
            val specialObj = components.optJSONObject("special_issues")
            val correctionsObj = components.optJSONObject("corrections")
            // Special issues while frozen must be "paused" with
            // NO content — a frozen edition carrying special-issues
            // items is corrupt, not publishable (spec §5.10).
            if (specialObj != null) {
                val state = specialObj.optString("state")
                val items = specialObj.optJSONArray("items")
                if (state != NightlyComponentStates.PAUSED && items != null && items.length() > 0) {
                    return null
                }
            }
            val items = mutableListOf<NightlyItemParsed>()
            val arr = articlesObj.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val it = try {
                    arr.getJSONObject(i)
                } catch (_: Exception) {
                    return null
                }
                val storyId = it.optString("story_id")
                val title = it.optString("title")
                val body = it.optString("body")
                if (storyId.isBlank() || title.isBlank() || body.isBlank()) return null
                // Verbatim badge is allow-listed: only
                // Donya-e Eqtesad + Tabnak may ever be verbatim
                // (Phase 2 AttributionRules). A badge naming any
                // other source makes the item corrupt.
                val badge = it.optString("verbatim_badge").takeIf { s -> s.isNotBlank() }
                if (badge != null && !badge.contains("دنیای اقتصاد") && !badge.contains("تابناک")) {
                    return null
                }
                // A source_url must be http(s) and never a blog
                // link (BLOG_LIVE=False); anything else is dropped
                // (not fatal — the item still renders, source-name
                // only, like freewire).
                val rawUrl = it.optString("source_url").takeIf { s -> s.isNotBlank() }
                val url = if (rawUrl != null &&
                    (rawUrl.startsWith("http://") || rawUrl.startsWith("https://"))
                ) rawUrl else null
                items.add(
                    NightlyItemParsed(
                        editionKey = it.optString("edition_key").takeIf { s -> s.isNotBlank() }
                            ?: NightlyKeys.editionKey(editionId, storyId),
                        storyId = storyId,
                        appKey = it.optString("app_key").takeIf { s -> s.isNotBlank() },
                        kind = it.optString("kind", "summary"),
                        title = title,
                        body = body,
                        wordCount = it.optInt("word_count", NightlyWords.count(body)),
                        freshness = if (it.isNull("freshness")) null else it.optDouble("freshness"),
                        sourceName = it.optString("source_name"),
                        sourceUrl = url,
                        verbatimBadge = badge,
                        formatType = it.optString("format_type").takeIf { s -> s.isNotBlank() },
                        crisisLevel = it.optString("crisis_level").takeIf { s -> s.isNotBlank() },
                        category = it.optString("category").takeIf { s -> s.isNotBlank() },
                        categoryFa = it.optString("category_fa").takeIf { s -> s.isNotBlank() },
                        slot = it.optString("slot").takeIf { s -> s.isNotBlank() },
                        bundleId = it.optString("bundle_id").takeIf { s -> s.isNotBlank() },
                        position = if (it.isNull("position")) null else it.optInt("position"),
                    )
                )
            }
            // A published edition whose articles component claims
            // "present" but carries zero items is inconsistent —
            // unless it is a held/zero-article edition, whose state
            // must say so (spec §5.4: zero-article editions are
            // honest only with their other components present).
            val articlesState = articlesObj.optString("state")
            if (articlesState == NightlyComponentStates.PRESENT && items.isEmpty()) return null
            NightlyEditionParsed(
                editionId = editionId,
                version = obj.optInt("version", 1),
                shamsiDate = obj.optString("shamsi_date"),
                tehranDate = obj.optString("tehran_date"),
                status = status,
                generatedAtTehran = obj.optString("generated_at_tehran"),
                publishedAtTehran = obj.optString("published_at_tehran").takeIf { it.isNotBlank() },
                cutTehran = obj.optString("cut_tehran"),
                contentHash = obj.optString("content_hash").takeIf { it.isNotBlank() },
                articlesState = articlesState,
                articlesJson = articlesObj.toString(),
                marketCloseState = marketObj?.optString("state") ?: NightlyComponentStates.NONE_TONIGHT,
                marketCloseJson = marketObj?.toString() ?: "{}",
                digestsState = digestsObj?.optString("state") ?: NightlyComponentStates.NONE_TONIGHT,
                digestsJson = digestsObj?.toString() ?: "{}",
                analysisState = analysisObj?.optString("state") ?: NightlyComponentStates.NONE_TONIGHT,
                analysisJson = analysisObj?.toString() ?: "{}",
                specialIssuesState = specialObj?.optString("state") ?: NightlyComponentStates.PAUSED,
                correctionsState = correctionsObj?.optString("state") ?: NightlyComponentStates.NONE_TONIGHT,
                correctionsJson = correctionsObj?.toString() ?: "{}",
                dossierLinksJson = obj.optJSONArray("dossier_links")?.toString() ?: "[]",
                items = items,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Dossier object from nightly.json (full dossier, not a link). */
    fun parseDossier(obj: JSONObject): Triple<String, String, JSONObject>? {
        val id = obj.optString("dossier_id")
        if (id.isBlank()) return null
        val title = obj.optString("topic_title_fa")
        if (title.isBlank()) return null
        return Triple(id, title, obj)
    }
}
