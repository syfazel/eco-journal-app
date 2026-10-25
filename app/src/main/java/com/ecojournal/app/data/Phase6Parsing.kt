package com.ecojournal.app.data

import org.json.JSONObject

// Phase 6 parsers (spec §2, §3, §5). Rules, not generosity:
// any structural problem, any gated type, any probability key,
// any blog URL, any non-allow-listed verbatim badge, any vote
// field → the item is HELD (returned as held, never rendered,
// never padded, never auto-filled). The parsers never create a
// voice row or a special-issue page: voices parse only the
// §3.6 placeholder state, and special issues parse only the
// frozen state. A future post-gate builder fills key_voices
// from Yasin's final registry version, not from these parsers.

data class AnalysisSection(val key: String, val heading: String, val body: String)

data class ConsensusRow(
    val outlet: String,
    val position: String,
    val sourceUrl: String,
    val date: String,
    val isQuote: Boolean,
)

data class ConsensusMatrix(
    val topic: String,
    val asOfShamsi: String,
    val agreement: List<ConsensusRow>,
    val divergence: List<ConsensusRow>,
)

data class AnalysisItemParsed(
    val storyId: String,
    val appKey: String?,
    val title: String,
    val analysisType: String,
    val thesisLine: String?,
    val sections: List<AnalysisSection>,
    val wordCount: Int,
    val publishedShamsi: String,
    val byline: String,
    val sourceLinks: List<String>,
    val correctionCount: Int,
    val formatType: String?,
    val category: String?,
    val consensus: ConsensusMatrix?,
)

data class HeldAnalysisItem(val storyId: String, val reason: String)

data class AnalysisComponentParsed(
    val items: List<AnalysisItemParsed>,
    val held: List<HeldAnalysisItem>,
)

data class TopStoryParsed(val title: String, val storyId: String, val appKey: String?, val formatType: String?, val dateShamsi: String, val source: String)

data class TopStoriesCardParsed(
    val weekRange: String,
    val selectionBasis: String,
    val label: String,
    val stories: List<TopStoryParsed>,
)

private fun hasProbabilityKey(obj: JSONObject): Boolean {
    val keys = obj.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        if (k.contains("probab") || k == "forecast_direction") return true
        val v = try {
            obj.get(k)
        } catch (_: Exception) {
            null
        }
        if (v is JSONObject && hasProbabilityKey(v)) return true
    }
    return false
}

private fun parseConsensus(obj: JSONObject): ConsensusMatrix? {
    val topic = obj.optString("topic")
    val asOf = obj.optString("as_of_shamsi")
    if (topic.isBlank() || asOf.isBlank()) return null
    fun rows(key: String): List<ConsensusRow>? {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        val out = mutableListOf<ConsensusRow>()
        for (i in 0 until arr.length()) {
            val r = try {
                arr.getJSONObject(i)
            } catch (_: Exception) {
                return null
            }
            val outlet = r.optString("outlet")
            val position = r.optString("position")
            val url = r.optString("source_url")
            val date = r.optString("date")
            // A row whose source link is missing is dropped from
            // the matrix (spec §2.3) — but a dropped row that
            // leaves a single-outlet matrix still holds it below.
            if (outlet.isBlank() || position.isBlank() || date.isBlank()) return null
            if (!Phase6Validation.isHttpSourceUrl(url)) continue
            out.add(
                ConsensusRow(
                    outlet = outlet,
                    position = position,
                    sourceUrl = url,
                    date = date,
                    isQuote = r.optBoolean("is_quote", false),
                ),
            )
        }
        return out
    }
    val agreement = rows("agreement") ?: return null
    val divergence = rows("divergence") ?: return null
    val outlets = (agreement + divergence).map { it.outlet }.toSet()
    if (Phase6Validation.consensusHeldReason(outlets.size) != null) return null
    return ConsensusMatrix(topic = topic, asOfShamsi = asOf, agreement = agreement, divergence = divergence)
}

object Phase6Parsing {
    /** Parses one analysis item; returns null when it must be HELD (reason via heldReasonOf). */
    fun heldReasonOf(item: JSONObject): String? {
        val type = item.optString("analysis_type")
        val sectionsObj = item.optJSONObject("sections")
        val present = mutableSetOf<String>()
        if (sectionsObj != null) {
            for (key in AnalysisStructure.ORDER) {
                if (sectionsObj.optString(key).isNotBlank()) present.add(key)
            }
        }
        val body = sectionsObj?.let { s ->
            AnalysisStructure.ORDER.joinToString(" ") { s.optString(it) }
        } ?: item.optString("body")
        val wc = if (item.has("word_count")) item.optInt("word_count") else NightlyWords.count(body)
        val base = Phase6Validation.analysisHeldReason(
            type = type,
            wordCount = wc,
            sectionsPresent = present,
            hasProbabilityKey = hasProbabilityKey(item),
        )
        if (base != null) return base
        val consensusObj = item.optJSONObject("consensus_matrix")
        if (consensusObj != null && parseConsensus(consensusObj) == null) {
            return "consensus_invalid"
        }
        val badge = item.optString("verbatim_badge").takeIf { it.isNotBlank() }
        if (!Phase6Validation.isVerbatimAllowed(badge)) return AnalysisHeldReasons.VERBATIM_NOT_ALLOWED
        for (url in splitLinks(item.optString("source_links"))) {
            if (!Phase6Validation.isHttpSourceUrl(url)) return AnalysisHeldReasons.BAD_SOURCE_URL
        }
        return null
    }

    private fun splitLinks(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun parseAnalysisItem(item: JSONObject): AnalysisItemParsed? {
        if (heldReasonOf(item) != null) return null
        val storyId = item.optString("story_id")
        val title = item.optString("title")
        if (storyId.isBlank() || title.isBlank()) return null
        val sectionsObj = item.optJSONObject("sections") ?: return null
        val sections = AnalysisStructure.ORDER.map { key ->
            AnalysisSection(
                key = key,
                heading = AnalysisStructure.HEADINGS[key] ?: key,
                body = sectionsObj.optString(key),
            )
        }
        val body = sections.joinToString(" ") { it.body }
        val wc = if (item.has("word_count")) item.optInt("word_count") else NightlyWords.count(body)
        val consensusObj = item.optJSONObject("consensus_matrix")
        val consensus = consensusObj?.let { parseConsensus(it) }
        // A matrix object that fails its own gate holds the item
        // (a broken matrix must not ship attached to a good article).
        if (consensusObj != null && consensus == null) return null
        val sourceLinks = mutableListOf<String>()
        val linksArr = item.optJSONArray("source_links")
        if (linksArr != null) {
            for (i in 0 until linksArr.length()) {
                val u = linksArr.optString(i)
                if (u.isNotBlank()) sourceLinks.add(u)
            }
        } else {
            sourceLinks.addAll(splitLinks(item.optString("source_links")))
        }
        return AnalysisItemParsed(
            storyId = storyId,
            appKey = item.optString("app_key").takeIf { it.isNotBlank() },
            title = title,
            analysisType = item.optString("analysis_type"),
            thesisLine = item.optString("thesis_line").takeIf { it.isNotBlank() },
            sections = sections,
            wordCount = wc,
            publishedShamsi = item.optString("published_shamsi"),
            byline = "تحریریهٔ ECO JOURNAL",
            sourceLinks = sourceLinks,
            correctionCount = item.optInt("correction_count", 0),
            formatType = item.optString("format_type").takeIf { it.isNotBlank() },
            category = item.optString("category").takeIf { it.isNotBlank() },
            consensus = consensus,
        )
    }

    /** Parses an edition's analysis component (its "items" array). */
    fun parseAnalysisComponent(componentJson: String): AnalysisComponentParsed {
        val items = mutableListOf<AnalysisItemParsed>()
        val held = mutableListOf<HeldAnalysisItem>()
        val root = try {
            JSONObject(componentJson)
        } catch (_: Exception) {
            return AnalysisComponentParsed(items, held)
        }
        val arr = root.optJSONArray("items") ?: return AnalysisComponentParsed(items, held)
        for (i in 0 until arr.length()) {
            val obj = try {
                arr.getJSONObject(i)
            } catch (_: Exception) {
                continue
            }
            val storyId = obj.optString("story_id").ifBlank { "unknown-$i" }
            val parsed = parseAnalysisItem(obj)
            if (parsed != null) {
                items.add(parsed)
            } else {
                held.add(HeldAnalysisItem(storyId, heldReasonOf(obj) ?: "held"))
            }
        }
        return AnalysisComponentParsed(items, held)
    }

    /** Top-stories card parser: any vote field, missing week range, or unknown basis → null. */
    fun parseTopStoriesCard(obj: JSONObject): TopStoriesCardParsed? {
        var hasVote = false
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.contains("vote") || k.contains("percentage")) hasVote = true
        }
        val weekRange = obj.optString("week_range")
        val basis = obj.optString("selection_basis")
        val arr = obj.optJSONArray("stories") ?: return null
        val stories = mutableListOf<TopStoryParsed>()
        for (i in 0 until arr.length()) {
            val s = try {
                arr.getJSONObject(i)
            } catch (_: Exception) {
                return null
            }
            stories.add(
                TopStoryParsed(
                    title = s.optString("title"),
                    storyId = s.optString("story_id"),
                    appKey = s.optString("app_key").takeIf { it.isNotBlank() },
                    formatType = s.optString("format_type").takeIf { it.isNotBlank() },
                    dateShamsi = s.optString("date_shamsi"),
                    source = s.optString("source"),
                ),
            )
        }
        val held = Phase6Validation.topStoriesHeldReason(weekRange, basis, stories.size, hasVote)
        if (held != null) return null
        return TopStoriesCardParsed(
            weekRange = weekRange,
            selectionBasis = basis,
            label = TopStoriesRules.labelFor(basis) ?: return null,
            stories = stories,
        )
    }
}
