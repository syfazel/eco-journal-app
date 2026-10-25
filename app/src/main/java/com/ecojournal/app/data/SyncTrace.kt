package com.ecojournal.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * v5 Phase 1 instrumentation ONLY (docs/v5/v5-program-plan.md, gate G1).
 *
 * Purpose: make defect A1 (feed flashes content for ~1 second on
 * refresh, then returns to the empty page) diagnosable from a phone
 * report, after Phase 0 static review disproved the obvious
 * mechanisms (no article-deletion path in sync, single Room
 * instance, feed branch cannot remove rows).
 *
 * What is recorded, and where:
 * - Sync trace: one entry per manifest fetch and per section
 *   outcome, with timestamp, section key, fixed code, fetched
 *   count, missing count and outcome state. Stored in the existing
 *   meta table under [TRACE_KEY] — NO schema change, no new table.
 * - Feed-branch trace: one entry per rendered-region transition
 *   on the feed (loading / empty / front_page / filter / search /
 *   urgent_all) with article and typed-item counts, under
 *   [BRANCH_KEY]. This discriminates "articles were shown, then
 *   the rows vanished" (front_page -> empty with a falling count)
 *   from "articles were never shown" (empty throughout, so the
 *   1-second flash was not feed rows at all).
 * - Seed/manifest reconciliation: one snapshot per syncV3 run
 *   comparing cached article appKeys against manifest article
 *   appKeys (local / manifest / missing / extra), under
 *   [RECON_KEY]. A key-derivation mismatch (H4) shows up as
 *   large missing AND extra counts; it can duplicate rows, never
 *   hide them, so a vanish with missing == extra == 0 rules H4 out.
 *
 * Privacy / behaviour rules (binding):
 * - Counts, section keys, fixed codes and epochs ONLY. Never
 *   titles, bodies, URLs, invite codes/hashes, tokens, device ids.
 * - Every write is best-effort and swallowed: a trace failure must
 *   never change a sync outcome, crash the feed, or block a
 *   transaction. Callers keep trace writes OUTSIDE Room
 *   transactions.
 * - Fetched/missing use -1 for "not applicable" (e.g. a manifest
 *   fetch has no item counts); Diagnostics renders -1 as "not
 *   recorded", never as zero.
 * - Entries are capped at [MAX_ENTRIES] (oldest dropped first), so
 *   the trace cannot grow without bound on a phone that syncs
 *   often. This is diagnosis state, not user data, and it is never
 *   sent anywhere — it is only shown on Diagnostics and copied
 *   verbatim by the existing copy-diagnostics action.
 */
object SyncTrace {
    const val TRACE_KEY = "v5_sync_trace"
    const val BRANCH_KEY = "v5_feed_branch_trace"
    const val RECON_KEY = "v5_seed_recon"
    const val MAX_ENTRIES = 40

    data class TraceEntry(
        val at: Long,
        val section: String,
        val code: String,
        val fetched: Int,
        val missing: Int,
        val state: String,
    )

    data class BranchEntry(
        val at: Long,
        val branch: String,
        val articles: Int,
        val typed: Int,
    )

    data class Recon(
        val at: Long,
        val local: Int,
        val manifest: Int,
        val missing: Int,
        val extra: Int,
    )

    suspend fun record(
        dao: ArticleDao,
        section: String,
        code: String,
        fetched: Int,
        missing: Int,
        state: String,
    ) {
        try {
            val arr = try {
                JSONArray(dao.metaValue(TRACE_KEY) ?: "[]")
            } catch (_: Exception) {
                JSONArray()
            }
            arr.put(
                JSONObject()
                    .put("t", System.currentTimeMillis())
                    .put("s", section)
                    .put("c", code)
                    .put("f", fetched)
                    .put("m", missing)
                    .put("st", state)
            )
            while (arr.length() > MAX_ENTRIES) arr.remove(0)
            dao.upsertMeta(MetaEntity(TRACE_KEY, arr.toString()))
        } catch (_: Exception) {
        }
    }

    suspend fun recordFeedBranch(
        dao: ArticleDao,
        branch: String,
        articles: Int,
        typed: Int,
    ) {
        try {
            val arr = try {
                JSONArray(dao.metaValue(BRANCH_KEY) ?: "[]")
            } catch (_: Exception) {
                JSONArray()
            }
            arr.put(
                JSONObject()
                    .put("t", System.currentTimeMillis())
                    .put("b", branch)
                    .put("a", articles)
                    .put("ty", typed)
            )
            while (arr.length() > MAX_ENTRIES) arr.remove(0)
            dao.upsertMeta(MetaEntity(BRANCH_KEY, arr.toString()))
        } catch (_: Exception) {
        }
    }

    suspend fun recordRecon(
        dao: ArticleDao,
        local: Int,
        manifest: Int,
        missing: Int,
        extra: Int,
    ) {
        try {
            val obj = JSONObject()
                .put("t", System.currentTimeMillis())
                .put("local", local)
                .put("manifest", manifest)
                .put("missing", missing)
                .put("extra", extra)
            dao.upsertMeta(MetaEntity(RECON_KEY, obj.toString()))
        } catch (_: Exception) {
        }
    }

    fun parseTrace(json: String?): List<TraceEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    TraceEntry(
                        at = o.optLong("t", 0L),
                        section = o.optString("s"),
                        code = o.optString("c"),
                        fetched = o.optInt("f", -1),
                        missing = o.optInt("m", -1),
                        state = o.optString("st"),
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseBranches(json: String?): List<BranchEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    BranchEntry(
                        at = o.optLong("t", 0L),
                        branch = o.optString("b"),
                        articles = o.optInt("a", -1),
                        typed = o.optInt("ty", -1),
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseRecon(json: String?): Recon? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            Recon(
                at = o.optLong("t", 0L),
                local = o.optInt("local", -1),
                manifest = o.optInt("manifest", -1),
                missing = o.optInt("missing", -1),
                extra = o.optInt("extra", -1),
            )
        } catch (_: Exception) {
            null
        }
    }
}
