package com.ecojournal.app.data

import com.ecojournal.app.util.toPersianDigits
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * Phase 3 near-real-time chain record (spec 3.1 + 3.2).
 *
 * Edge fields are UTC epoch SECONDS (suffix _epoch), consistent
 * with Phase 1's updated_at_epoch, and displayed in Asia/Tehran
 * time. Durations are derived (later minus earlier), never stored
 * as the only record.
 *
 * The edge item carries only the edge-side chain object
 * (channel_posted .. edge_pushed). App-side fields
 * (app_manifest_seen, app_item_applied, app_notified) are recorded
 * locally by EdgeRepository at sync time and stored in the meta
 * table under "p3_latest_chain" together with the edge object --
 * they are NEVER shipped on the edge. pages_first_seen is lab-side
 * only (check_pages.py) and is never on the phone either: the
 * Diagnostics Pages line therefore always shows the lab note.
 *
 * The chain object carries no content, no invite code, no token,
 * no device id, no URL with parameters -- timestamps and decision
 * labels only (diagnostics-only, local-first telemetry rule).
 *
 * No Room schema change: the latest chain lives in the existing
 * meta table, so this feature needs no migration. A missing step
 * renders as "not recorded" and marks the chain incomplete -- it
 * is never rendered as zero or as the previous step's time.
 */
data class EdgeChain(
    val appKey: String,
    val channelMessageId: Long?,
    val channelPostedEpoch: Long?,
    val stagedEpoch: Long?,
    val gateDecidedEpoch: Long?,
    val gateDecision: String?,
    val gateProvenance: String?,
    val edgeGeneratedEpoch: Long?,
    val edgePushedEpoch: Long?,
    val appManifestSeenEpoch: Long?,
    val appItemAppliedEpoch: Long?,
    val appNotifiedEpoch: Long?,
    val pollPath: String?,
) {
    /** One display step: kind + epoch seconds (null = not recorded). */
    data class Step(val kind: String, val epochSec: Long?, val gapSec: Long?)

    /** Ordered steps for the Diagnostics chain view (spec 3.2). */
    fun steps(): List<Step> {
        val ordered = listOf(
            "channel_post" to channelPostedEpoch,
            "staged" to stagedEpoch,
            "gate" to gateDecidedEpoch,
            "edge_generated" to edgeGeneratedEpoch,
            "edge_pushed" to edgePushedEpoch,
            "pages_first_seen" to null,
            "app_manifest_seen" to appManifestSeenEpoch,
            "app_item_applied" to appItemAppliedEpoch,
            "app_notified" to appNotifiedEpoch,
        )
        var previous: Long? = null
        return ordered.map { (kind, epoch) ->
            val gap = if (epoch != null && previous != null) epoch - previous else null
            if (epoch != null) previous = epoch
            Step(kind, epoch, gap)
        }
    }

    /** Complete = every step present except pages (lab-only) and
     *  app_notified, which may legitimately be null (no
     *  notification due). edge_pushed may be null in Tier A
     *  (dry-run: nothing pushed) -- that makes a Tier A chain
     *  incomplete by design, and Diagnostics must say so. */
    val isComplete: Boolean
        get() = channelPostedEpoch != null &&
            stagedEpoch != null &&
            gateDecidedEpoch != null &&
            edgeGeneratedEpoch != null &&
            edgePushedEpoch != null &&
            appManifestSeenEpoch != null &&
            appItemAppliedEpoch != null

    /** True when an app-side time precedes an edge-side time: the
     *  device clock disagrees with edge times, so app legs are
     *  indicative only (spec 3.2 honesty line). Clock skew is
     *  never silently corrected. */
    val hasClockSkew: Boolean
        get() {
            val edgeLast = edgeGeneratedEpoch ?: return false
            val appFirst = appManifestSeenEpoch ?: return false
            return appFirst < edgeLast - 60
        }

    /** End-to-end post -> app, seconds, when both ends are known. */
    val endToEndSec: Long?
        get() = if (channelPostedEpoch != null && appItemAppliedEpoch != null) {
            appItemAppliedEpoch - channelPostedEpoch
        } else {
            null
        }

    fun toMetaJson(): String {
        val obj = JSONObject()
        obj.put("app_key", appKey)
        obj.put("channel_message_id", channelMessageId ?: JSONObject.NULL)
        obj.put("channel_posted_epoch", channelPostedEpoch ?: JSONObject.NULL)
        obj.put("staged_epoch", stagedEpoch ?: JSONObject.NULL)
        obj.put("gate_decided_epoch", gateDecidedEpoch ?: JSONObject.NULL)
        obj.put("gate_decision", gateDecision ?: JSONObject.NULL)
        obj.put("gate_provenance", gateProvenance ?: JSONObject.NULL)
        obj.put("edge_generated_epoch", edgeGeneratedEpoch ?: JSONObject.NULL)
        obj.put("edge_pushed_epoch", edgePushedEpoch ?: JSONObject.NULL)
        obj.put("app_manifest_seen_epoch", appManifestSeenEpoch ?: JSONObject.NULL)
        obj.put("app_item_applied_epoch", appItemAppliedEpoch ?: JSONObject.NULL)
        obj.put("app_notified_epoch", appNotifiedEpoch ?: JSONObject.NULL)
        obj.put("poll_path", pollPath ?: JSONObject.NULL)
        return obj.toString()
    }
}

object ChainRecord {
    const val META_KEY = "p3_latest_chain"

    /** Parse the edge-side chain object of one typed item. */
    fun parseEdgeChain(appKey: String, item: JSONObject): EdgeChain? {
        // optJSONObject returns null when "chain" is missing, null, or
        // not an object, so this single line covers the isNull case
        // too — and gives chain a non-null type, which the local
        // optLong/optString helpers below need (smart cast does not
        // survive into local fun declarations).
        val chain = item.optJSONObject("chain") ?: return null
        fun optLong(key: String): Long? =
            if (chain.isNull(key)) null else chain.optLong(key)
        fun optString(key: String): String? =
            if (chain.isNull(key)) null else chain.optString(key).takeIf { it.isNotBlank() }
        return EdgeChain(
            appKey = appKey,
            channelMessageId = optLong("channel_message_id"),
            channelPostedEpoch = optLong("channel_posted_epoch"),
            stagedEpoch = optLong("staged_epoch"),
            gateDecidedEpoch = optLong("gate_decided_epoch"),
            gateDecision = optString("gate_decision"),
            gateProvenance = optString("gate_provenance"),
            edgeGeneratedEpoch = optLong("edge_generated_epoch"),
            edgePushedEpoch = optLong("edge_pushed_epoch"),
            appManifestSeenEpoch = null,
            appItemAppliedEpoch = null,
            appNotifiedEpoch = null,
            pollPath = null,
        )
    }

    /** Parse a stored meta JSON (edge + app-side fields). */
    fun fromMetaJson(text: String?): EdgeChain? {
        if (text.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(text)
            fun optLong(key: String): Long? =
                if (obj.isNull(key)) null else obj.optLong(key)
            fun optString(key: String): String? =
                if (obj.isNull(key)) null else obj.optString(key).takeIf { it.isNotBlank() }
            EdgeChain(
                appKey = obj.optString("app_key"),
                channelMessageId = optLong("channel_message_id"),
                channelPostedEpoch = optLong("channel_posted_epoch"),
                stagedEpoch = optLong("staged_epoch"),
                gateDecidedEpoch = optLong("gate_decided_epoch"),
                gateDecision = optString("gate_decision"),
                gateProvenance = optString("gate_provenance"),
                edgeGeneratedEpoch = optLong("edge_generated_epoch"),
                edgePushedEpoch = optLong("edge_pushed_epoch"),
                appManifestSeenEpoch = optLong("app_manifest_seen_epoch"),
                appItemAppliedEpoch = optLong("app_item_applied_epoch"),
                appNotifiedEpoch = optLong("app_notified_epoch"),
                pollPath = optString("poll_path"),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Tehran wall-clock rendering of epoch seconds (Persian digits). */
    fun formatTehran(epochSec: Long?): String? {
        if (epochSec == null) return null
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("Asia/Tehran")
            fmt.format(Date(epochSec * 1000)).toPersianDigits()
        } catch (_: Exception) {
            null
        }
    }

    /** Gap rendering: seconds, minutes, or hours (Persian digits). */
    fun formatGap(gapSec: Long?): String? {
        if (gapSec == null || gapSec < 0) return null
        val text = when {
            gapSec < 60 -> "$gapSec ثانیه"
            gapSec < 3600 -> "${gapSec / 60} دقیقه ${gapSec % 60} ثانیه"
            else -> "${gapSec / 3600} ساعت ${(gapSec % 3600) / 60} دقیقه"
        }
        return text.toPersianDigits()
    }
}
