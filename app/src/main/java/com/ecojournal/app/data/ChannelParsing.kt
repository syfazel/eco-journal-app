package com.ecojournal.app.data

import org.json.JSONArray
import org.json.JSONObject

// Phase 2 edge parsing (spec section 2). Edge file shapes —
// channel_typed.json {"items": [...]}, bundles.json {"bundles":
// [...]}, radar.json {"signals": [...]} — are fixed by the Phase 2
// fixture builder (edge/phase2_fixtures.py) and this parser together;
// the Phase 3 publisher must ship the same shapes, and the first
// real typed item re-verifies them (P2-A1–P2-A4).
//
// Corrupt items are counted and skipped, never a crash, and the
// rest of the section still applies (Phase 1 rule). Incomplete
// format blocks are NOT invented: the item renders with the
// spec 3.3 incomplete line instead. Night-law decisions are
// written once, at first delivery, from shipped fields only; an
// already-cached item keeps its stored decision even if its row
// is re-downloaded (spec 5.2).

/** Mutable per-sync anomaly accumulator (counts only, no content). */
class AnomalyBag {
    val counts: MutableMap<String, Int> = mutableMapOf()

    fun add(code: String, n: Int = 1) {
        if (n <= 0) return
        counts[code] = (counts[code] ?: 0) + n
    }

    fun addAll(other: Map<String, Int>) {
        for ((code, n) in other) add(code, n)
    }
}

object ChannelParsers {
    private fun optLongOrNull(obj: JSONObject, key: String): Long? =
        if (obj.isNull(key)) null else obj.optLong(key)

    private fun optIntOrNull(obj: JSONObject, key: String): Int? =
        if (obj.isNull(key)) null else obj.optInt(key)

    private fun optStringOrNull(obj: JSONObject, key: String): String? =
        if (obj.isNull(key)) null else obj.optString(key)?.takeIf { it.isNotBlank() }

    private fun rawJsonString(obj: JSONObject, key: String): String? {
        if (obj.isNull(key)) return null
        return when (val v = obj.opt(key)) {
            is JSONObject -> v.toString()
            is JSONArray -> v.toString()
            is String -> v.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun sitrepSectionCount(raw: String?): Int? {
        if (raw == null) return null
        return try {
            JSONArray(raw).length()
        } catch (_: Exception) {
            null
        }
    }

    private fun watchNextCount(raw: String?): Int? {
        if (raw == null) return null
        return try {
            JSONArray(raw).length()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses one typed item. Returns null (with anomalies) when
     * the item is corrupt and must be skipped entirely. [existing]
     * is the cached row for this appKey, if any: when present, its
     * night-law fields are preserved verbatim (never recomputed).
     */
    fun parseChannelItem(
        obj: JSONObject,
        existing: ChannelItemEntity?,
        now: Long,
        anomalies: AnomalyBag,
    ): ChannelItemEntity? {
        val appKey = obj.optString("app_key").takeIf { it.isNotBlank() } ?: return null
        val formatType = obj.optString("format_type")
        if (!Formats.isKnown(formatType)) {
            anomalies.add(Phase2Anomalies.BAD_FORMAT_TYPE)
            return null
        }
        val headline = obj.optString("headline")
        if (headline.isBlank()) {
            anomalies.add(Phase2Anomalies.BAD_HEADLINE)
            return null
        }
        val status = obj.optString("status", "posted")
        if (status != "posted" && status != "retracted") {
            anomalies.add(Phase2Anomalies.BAD_STATUS)
            return null
        }
        val publishedAtEpoch = obj.optLong("published_at_epoch", 0L)
        if (publishedAtEpoch <= 0L) {
            anomalies.add(Phase2Anomalies.BAD_PUBLISHED_AT)
            return null
        }
        val body = obj.optString("body")
        val sitrepRaw = rawJsonString(obj, "sitrep_sections_json")
        if (formatType == "SITREP" && body.isBlank() &&
            (sitrepRaw == null || sitrepSectionCount(sitrepRaw) == 0)
        ) {
            // Silence rule (spec 3.7): no empty SITREP is published.
            anomalies.add(Phase2Anomalies.BAD_SITREP_EMPTY)
            return null
        }
        val sourceName = obj.optString("source_name")
        if (sourceName.isBlank()) {
            anomalies.add(Phase2Anomalies.SOURCE_NAME_MISSING)
        }
        var sourceLink = optStringOrNull(obj, "source_link")
        if (formatType == "WIRE" && sourceLink != null) {
            // Render-enforced, not trusted to the generator (2.2).
            sourceLink = null
            anomalies.add(Phase2Anomalies.WIRE_LINK_DROPPED)
        }
        // Bundle membership must pair bundle_id with position+total.
        var bundleId = optStringOrNull(obj, "bundle_id")
        var bundlePosition = optIntOrNull(obj, "bundle_position")
        var bundleTotal = optIntOrNull(obj, "bundle_total")
        if (bundleId != null && (bundlePosition == null || bundleTotal == null)) {
            anomalies.add(Phase2Anomalies.BAD_BUNDLE_MEMBERSHIP)
            bundleId = null
            bundlePosition = null
            bundleTotal = null
        }
        val urgencyScore = optIntOrNull(obj, "urgency_score")
        val crisisLevel = optStringOrNull(obj, "crisis_level")
        val attributionField = optStringOrNull(obj, "attribution")
        val attribution = AttributionRules.resolve(formatType, attributionField, sourceName)
        if (attributionField == "verbatim" && attribution != Attribution.VERBATIM &&
            formatType != "WIRE"
        ) {
            anomalies.add(Phase2Anomalies.VERBATIM_NOT_ALLOWED)
        }
        val verdictStatus = optStringOrNull(obj, "verdict_status")
        val verdictText = optStringOrNull(obj, "verdict_text")
        val pricesRaw = rawJsonString(obj, "prices_json")
        val watchRaw = rawJsonString(obj, "watch_next_json")
        if (formatType == "MARKET" && pricesRaw == null) {
            anomalies.add(Phase2Anomalies.FORMAT_INCOMPLETE)
        }
        if (formatType == "VERIFY") {
            if (verdictText == null && verdictStatus == null) {
                anomalies.add(Phase2Anomalies.FORMAT_INCOMPLETE)
            } else if (verdictText != null &&
                Verdicts.chipText(verdictStatus) == null
            ) {
                anomalies.add(Phase2Anomalies.VERIFY_STATUS_MISSING)
            }
        }
        if (formatType == "SITREP") {
            val sections = sitrepSectionCount(sitrepRaw)
            if (sections != null && sections != 5) {
                anomalies.add(Phase2Anomalies.SITREP_SECTION_COUNT)
            }
            if (sections == null) {
                anomalies.add(Phase2Anomalies.FORMAT_INCOMPLETE)
            }
            val watch = watchNextCount(watchRaw)
            if (watch != null && watch != 2) {
                anomalies.add(Phase2Anomalies.SITREP_WATCH_NEXT_COUNT)
            }
        }

        // Night law: new items are evaluated once; cached items
        // keep their stored decision. Out-of-range scores (the
        // thresholds presuppose 0-100) render feed-only, never
        // instant, and are counted (spec 2.2).
        val scoreInRange = urgencyScore == null || urgencyScore in 0..100
        if (!scoreInRange) {
            anomalies.add(Phase2Anomalies.BAD_URGENCY_SCORE)
        }
        val nightResult: NightLawResult
        val heldUntil: Long?
        val wasHeld: Boolean
        if (existing != null) {
            nightResult = when (existing.nightLawResult) {
                "instant" -> NightLawResult.INSTANT
                "held" -> NightLawResult.HELD
                else -> NightLawResult.FEED_ONLY
            }
            heldUntil = existing.heldUntilEpoch
            wasHeld = existing.wasHeld
        } else {
            val decided = if (!scoreInRange) {
                NightLawResult.FEED_ONLY
            } else {
                NightLaw.evaluate(formatType, urgencyScore, crisisLevel, publishedAtEpoch)
            }
            nightResult = decided
            heldUntil = if (decided == NightLawResult.HELD) {
                NightLaw.heldUntilEpoch(publishedAtEpoch)
            } else {
                null
            }
            wasHeld = decided == NightLawResult.HELD
        }
        val resultString = when (nightResult) {
            NightLawResult.INSTANT -> "instant"
            NightLawResult.HELD -> "held"
            NightLawResult.FEED_ONLY -> "feed_only"
        }
        return ChannelItemEntity(
            appKey = appKey,
            guid = optStringOrNull(obj, "guid"),
            storyId = optStringOrNull(obj, "story_id")
                ?: existing?.storyId
                ?: JoinKeys.storyIdFor(obj.optString("guid").ifBlank { appKey }),
            channelMessageId = optLongOrNull(obj, "channel_message_id"),
            formatType = formatType,
            urgencyScore = urgencyScore,
            crisisLevel = crisisLevel,
            headline = headline,
            body = body,
            publishedAtEpoch = publishedAtEpoch,
            slot = optStringOrNull(obj, "slot"),
            bundleId = bundleId,
            bundlePosition = bundlePosition,
            bundleTotal = bundleTotal,
            sourceName = sourceName,
            sourceLink = sourceLink,
            attribution = attribution.name,
            verdictStatus = verdictStatus,
            verdictText = verdictText,
            pricesJson = pricesRaw,
            sitrepSectionsJson = sitrepRaw,
            watchNextJson = watchRaw,
            status = status,
            contentHash = optStringOrNull(obj, "content_hash"),
            updatedAtEpoch = obj.optLong("updated_at_epoch", 0L),
            nightLawResult = resultString,
            heldUntilEpoch = heldUntil,
            heldReleased = existing?.heldReleased ?: false,
            wasHeld = wasHeld,
            notified = existing?.notified ?: false,
            cachedAt = now,
        )
    }

    /**
     * Parses one bundle header. Returns null when the header must
     * be held (disabled morning digest, or unknown kind): its
     * member items still render individually in feed (spec 2.3).
     */
    fun parseBundle(
        obj: JSONObject,
        anomalies: AnomalyBag,
    ): BundleEntity? {
        val bundleId = obj.optString("bundle_id").takeIf { it.isNotBlank() } ?: return null
        val kind = obj.optString("bundle_kind")
        if (kind == BundleKinds.MORNING_DIGEST) {
            anomalies.add(Phase2Anomalies.MORNING_DIGEST_BLOCKED)
            return null
        }
        if (kind !in BundleKinds.KNOWN) {
            anomalies.add(Phase2Anomalies.BAD_BUNDLE_KIND)
            return null
        }
        val rawTitle = obj.optString("title")
        val title = if (rawTitle.isBlank()) {
            anomalies.add(Phase2Anomalies.BUNDLE_TITLE_MISSING)
            "بسته"
        } else {
            rawTitle
        }
        val members = mutableListOf<String>()
        val arr = obj.optJSONArray("member_app_keys")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val key = arr.optString(i)
                if (key.isNotBlank()) members.add(key)
            }
        }
        return BundleEntity(
            bundleId = bundleId,
            kind = kind,
            title = title,
            memberAppKeys = members,
            bundleDateShamsi = obj.optString("bundle_date_shamsi"),
            status = obj.optString("status", "posted"),
            contentHash = optStringOrNull(obj, "content_hash"),
            updatedAtEpoch = obj.optLong("updated_at_epoch", 0L),
        )
    }

    /** Parses one radar signal. Unknown layer -> group 0 (unclassified tail). */
    fun parseRadarSignal(
        obj: JSONObject,
        anomalies: AnomalyBag,
    ): RadarSignalEntity? {
        val signalId = obj.optString("signal_id").takeIf { it.isNotBlank() } ?: return null
        val layerRaw = optIntOrNull(obj, "layer")
        val layer = if (layerRaw != null && layerRaw in 1..3) {
            layerRaw
        } else {
            anomalies.add(Phase2Anomalies.BAD_RADAR_FIELD)
            0
        }
        // Phase 4 graded shape (§4.5): severity is ONLY high /
        // medium / low; any other shipped value is kept verbatim
        // for display but never re-graded by the app. story_id is
        // the join key: a signal WITHOUT a resolvable story_id is
        // held (null), never published as an orphan. matched_topics
        // are topic labels, never the raw keyword list. linked_app_key
        // is null while the story is unpublished in-app — the row
        // then lists with the "not yet in app" marker, never a dead
        // link. Phase 2 fields (layer, severity_rank,
        // linked_story_id, detected_at_epoch) are still read so
        // Phase 2 fixture edges keep parsing unchanged.
        val grade = optStringOrNull(obj, "severity")
        // storyId comes ONLY from the spec §4.5 story_id key, so
        // it stays a reliable graded-shape marker: legacy Phase 2
        // signals (linked_story_id only) keep storyId == null and
        // keep their existing button behaviour (P2-A8).
        val storyId = optStringOrNull(obj, "story_id")
        // Orphan rule, scoped so it cannot regress Phase 2: it
        // applies ONLY to graded-shape signals (spec §4.5 rows,
        // recognised by their graded keys). A graded signal without
        // a resolvable story_id is held (null), never published as
        // an orphan. Legacy Phase 2 layer signals carry no story
        // id at all and keep parsing exactly as before.
        val gradedShape = obj.has("matched_topics") ||
            obj.has("first_seen_tehran") ||
            obj.has("linked_app_key") ||
            obj.has("story_id")
        if (gradedShape && storyId == null) return null
        val topics = mutableListOf<String>()
        val topicsArr = obj.optJSONArray("matched_topics")
        if (topicsArr != null) {
            for (i in 0 until topicsArr.length()) {
                topicsArr.optString(i).takeIf { it.isNotBlank() }?.let { topics.add(it) }
            }
        }
        val statusRaw = obj.optString("status", "active")
        return RadarSignalEntity(
            signalId = signalId,
            title = obj.optString("title"),
            layer = layer,
            severity = grade,
            severityRank = optIntOrNull(obj, "severity_rank"),
            sourceName = obj.optString("source_name"),
            sourceLink = optStringOrNull(obj, "source_link"),
            linkedStoryId = optStringOrNull(obj, "linked_story_id") ?: storyId,
            detectedAtEpoch = obj.optLong("detected_at_epoch", 0L),
            status = if (statusRaw == "active" || statusRaw == "resolved") statusRaw else "active",
            contentHash = optStringOrNull(obj, "content_hash"),
            updatedAtEpoch = obj.optLong("updated_at_epoch", 0L),
            storyId = storyId,
            matchedTopics = topics,
            firstSeenTehran = optStringOrNull(obj, "first_seen_tehran"),
            lastSeenTehran = optStringOrNull(obj, "last_seen_tehran"),
            linkedAppKey = optStringOrNull(obj, "linked_app_key"),
        )
    }
}
