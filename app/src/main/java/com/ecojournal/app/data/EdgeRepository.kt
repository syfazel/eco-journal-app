package com.ecojournal.app.data

import android.content.Context
import androidx.room.withTransaction
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.ecojournal.app.util.Notifications

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
 * Fixed sync error codes (M5, P1-25; Phase 1 adds the v3 codes).
 * The UI maps each code to a fixed Persian string; raw exception
 * text and HTTP internals are never shown to the user and never
 * stored as free text.
 * Codes: "network" (offline/DNS/timeout), "http_404",
 * "http_403", "http_other" (any other non-200), "bad_index"
 * (index.json unparsable), "bad_manifest" (manifest.json fetched
 * but not parseable as v3), "bad_market" (market.json unparsable),
 * "invite_fetch" (invite/revocation lists unfetchable; the gate
 * re-check is skipped, never a lock-out), "partial" (a section
 * incomplete), "insecure_edge" (non-HTTPS base).
 */
sealed class SyncResult {
    data object NotConfigured : SyncResult()
    data class Ok(
        val newArticleAppKeys: List<String>,
        val totalCached: Int,
        val sections: List<SectionOutcome> = emptyList(),
    ) : SyncResult()
    data class Partial(
        val newArticleAppKeys: List<String>,
        val missingCount: Int,
        val totalCached: Int,
        val sections: List<SectionOutcome> = emptyList(),
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
    val channelDao: ChannelDao = db.channelDao()
    val phase4Dao: Phase4Dao = db.phase4Dao()
    private val appContext: Context = context.applicationContext
    private val settings = SettingsStore(context)

    /** Records an attempt (M5): attempt time on every real attempt,
     * and a fixed error code — "" only on a complete success, so
     * the feed can always show an honest last-sync state. */
    private suspend fun recordAttempt(code: String) {
        val now = System.currentTimeMillis()
        dao.upsertMeta(MetaEntity("last_sync_attempt_at", now.toString()))
        dao.upsertMeta(MetaEntity("last_sync_error_code", code))
    }

    // Phase 1 Update Engine: sync() fetches manifest.json (v3)
    // FIRST. A parseable v3 manifest takes the per-section path
    // below; a missing manifest (an older Pages deploy) falls back
    // to the legacy index path, which keeps the old behaviour
    // verbatim and does not update per-section states. A manifest
    // that fetches but does not parse reports bad_manifest and
    // touches nothing. Force-refresh bypasses the stored section
    // hashes (every section hash is re-checked) but never
    // re-downloads an unchanged body: per-item hash comparison
    // still skips unchanged items, and it never bypasses the
    // invite gate or the revocation re-check.
    suspend fun sync(forceRefresh: Boolean = false): SyncResult {
        val base = edgeBase() ?: return SyncResult.NotConfigured
        return when (val outcome = httpGet("$base/manifest.json")) {
            is HttpOutcome.Ok -> {
                val manifest = ManifestParser.parse(outcome.body)
                if (manifest == null) {
                    recordAttempt("bad_manifest")
                    SyncResult.Failed("bad_manifest")
                } else {
                    syncV3(
                        base, manifest, forceRefresh, null,
                        if (forceRefresh) "manual" else "periodic_or_foreground",
                    )
                }
            }
            else -> syncLegacy()
        }
    }

    // Per-section force-refresh from Diagnostics. Re-reads the
    // manifest and applies only the requested section; every other
    // section's cache and state are untouched. For a
    // not-yet-published or gated section this re-records its state
    // from the manifest, which is the honest outcome: there is
    // nothing to fetch for it yet.
    suspend fun syncOnly(sectionKey: String, forceRefresh: Boolean = true): SyncResult {
        if (sectionKey !in SyncSections.ALL) return SyncResult.Failed("unknown_section")
        val base = edgeBase() ?: return SyncResult.NotConfigured
        val outcome = httpGet("$base/manifest.json")
        val manifest = if (outcome is HttpOutcome.Ok) {
            ManifestParser.parse(outcome.body)
        } else {
            null
        }
        if (manifest == null) {
            val code = when (outcome) {
                is HttpOutcome.Ok -> "bad_manifest"
                else -> outcome.toFailedCode()
            }
            recordAttempt(code)
            return SyncResult.Failed(code)
        }
        return syncV3(base, manifest, forceRefresh, sectionKey, "manual")
    }

    // Per-section Diagnostics snapshot. Counts for articles, shorts
    // and market come from the live tables; counts for the other
    // sections come from the last recorded manifest values. Hash
    // values are never returned, only present/absent.
    suspend fun sectionStates(): List<SectionSyncState> {
        return SyncSections.ALL.map { key ->
            val count = when (key) {
                SyncSections.ARTICLES -> dao.articleKindCount("article")
                SyncSections.SHORTS -> dao.articleKindCount("short")
                SyncSections.MARKET -> dao.marketCount()
                SyncSections.CHANNEL_TYPED -> channelDao.channelCount()
                SyncSections.BUNDLES -> channelDao.allBundles().size
                SyncSections.RADAR -> channelDao.radarCount()
                else -> dao.metaValue(SyncSections.countKey(key))?.toIntOrNull() ?: 0
            }
            SectionSyncState(
                section = key,
                state = dao.metaValue(SyncSections.stateKey(key)) ?: SectionStates.UNKNOWN,
                lastSyncAt = dao.metaValue(SyncSections.lastSyncKey(key))?.toLongOrNull(),
                lastAttemptAt = dao.metaValue(SyncSections.lastAttemptKey(key))?.toLongOrNull(),
                lastErrorCode = dao.metaValue(SyncSections.errorKey(key)) ?: "",
                itemCount = count,
                hashPresent = !dao.metaValue(SyncSections.hashKey(key)).isNullOrBlank(),
                missingCount = dao.metaValue(SyncSections.missingKey(key))?.toIntOrNull() ?: 0,
            )
        }
    }

    private suspend fun recordSectionMeta(
        section: String,
        state: String,
        errorCode: String,
        count: Int?,
        missing: Int,
        attemptAt: Long,
        lastSyncAt: Long?,
        hashToStore: String?,
    ) {
        dao.upsertMeta(MetaEntity(SyncSections.stateKey(section), state))
        dao.upsertMeta(MetaEntity(SyncSections.errorKey(section), errorCode))
        dao.upsertMeta(MetaEntity(SyncSections.lastAttemptKey(section), attemptAt.toString()))
        if (count != null) {
            dao.upsertMeta(MetaEntity(SyncSections.countKey(section), count.toString()))
        }
        dao.upsertMeta(MetaEntity(SyncSections.missingKey(section), missing.toString()))
        if (lastSyncAt != null) {
            dao.upsertMeta(MetaEntity(SyncSections.lastSyncKey(section), lastSyncAt.toString()))
        }
        if (hashToStore != null) {
            dao.upsertMeta(MetaEntity(SyncSections.hashKey(section), hashToStore))
        }
    }

    private fun fetchJoinGuids(base: String, joinKeysFile: String?): Map<String, String> {
        val file = joinKeysFile?.takeIf { it.isNotBlank() } ?: "join_keys.json"
        val text = when (val outcome = httpGet("$base/$file")) {
            is HttpOutcome.Ok -> outcome.body
            else -> return emptyMap()
        }
        return try {
            val arr = JSONObject(text).optJSONArray("entries") ?: return emptyMap()
            val map = mutableMapOf<String, String>()
            for (i in 0 until arr.length()) {
                val obj = try {
                    arr.getJSONObject(i)
                } catch (_: Exception) {
                    continue
                }
                val appKey = obj.optString("app_key")
                val guid = obj.optString("guid")
                if (appKey.isNotBlank() && guid.isNotBlank()) map[appKey] = guid
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun fetchArticleFile(base: String, item: ManifestItem, now: Long): ArticleEntity? {
        val file = item.file ?: return null
        val text = when (val outcome = httpGet("$base/$file")) {
            is HttpOutcome.Ok -> outcome.body
            else -> return null
        }
        return try {
            ArticleParsers.entityFromArticleJson(JSONObject(text), now)
        } catch (_: Exception) {
            null
        }
    }

    // Manifest-v3 per-section sync. One Room transaction per
    // section, and a section hash is stored only on that section's
    // complete success, so a failed section retries exactly its
    // missing set next time. Nothing here deletes a cached row:
    // failures and corrupt items leave the cache untouched, and a
    // retracted item becomes a tombstone (row kept, bookmark kept).
    private suspend fun syncV3(
        base: String,
        manifest: EdgeManifest,
        forceRefresh: Boolean,
        onlySection: String?,
        pollPath: String = "periodic_or_foreground",
    ): SyncResult {
        val now = System.currentTimeMillis()
        val inviteLists = fetchInviteLists(base)
        val joinGuids = fetchJoinGuids(base, manifest.joinKeysFile)
        val existingByKey = dao.all().associate { it.appKey to it }
        val existingKeys = existingByKey.keys
        val storedHashes = existingByKey.mapValues { it.value.contentHash }
        val outcomes = mutableListOf<SectionOutcome>()
        val newKeys = mutableListOf<String>()
        var missingTotal = 0
        var anyIncomplete = false

        fun wants(section: String): Boolean = onlySection == null || onlySection == section

        // Articles section.
        if (wants(SyncSections.ARTICLES)) {
            val section = manifest.sections[SyncSections.ARTICLES]
            if (section == null) {
                outcomes.add(SectionOutcome(SyncSections.ARTICLES, SectionStates.UNKNOWN, 0))
            } else {
                val storedHash = dao.metaValue(SyncSections.hashKey(SyncSections.ARTICLES))
                if (DeltaPlanner.isUpToDate(storedHash, section.hash, forceRefresh)) {
                    val count = dao.articleKindCount("article")
                    recordSectionMeta(
                        SyncSections.ARTICLES, SectionStates.UP_TO_DATE, "",
                        count, 0, now, null, null,
                    )
                    outcomes.add(SectionOutcome(SyncSections.ARTICLES, SectionStates.UP_TO_DATE, 0))
                } else {
                    val plan = DeltaPlanner.plan(
                        section.items, storedHashes, existingKeys, requiresFile = true,
                    )
                    val fresh = mutableListOf<ArticleEntity>()
                    val missingKeys = mutableListOf<String>()
                    if (plan.toFetch.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            coroutineScope {
                                plan.toFetch.chunked(4).forEach { chunk ->
                                    val results = chunk.map { item ->
                                        async { item to fetchArticleFile(base, item, now) }
                                    }.awaitAll()
                                    for ((item, entity) in results) {
                                        if (entity != null) fresh.add(entity)
                                        else missingKeys.add(item.appKey)
                                    }
                                }
                            }
                        }
                    }
                    val tombstoneRows = plan.tombstones.mapNotNull { item ->
                        val stored = existingByKey[item.appKey] ?: return@mapNotNull null
                        stored.copy(
                            status = "retracted",
                            retractedAtEpoch = now,
                            updatedAtEpoch = now,
                        )
                    }
                    val complete = missingKeys.isEmpty() && plan.corruptCount == 0
                    val missing = missingKeys.size + plan.corruptCount
                    val joins = section.items.mapNotNull { item ->
                        val guid = joinGuids[item.appKey]
                        if (item.storyId == null && guid == null &&
                            item.channelMessageId == null
                        ) {
                            null
                        } else {
                            JoinKeyEntity(
                                item.appKey, guid, item.storyId, item.channelMessageId,
                            )
                        }
                    }
                    db.withTransaction {
                        if (fresh.isNotEmpty()) dao.upsertAll(fresh)
                        if (tombstoneRows.isNotEmpty()) dao.upsertAll(tombstoneRows)
                        if (joins.isNotEmpty()) dao.upsertJoinKeys(joins)
                        recordSectionMeta(
                            SyncSections.ARTICLES,
                            if (complete) SectionStates.UP_TO_DATE else SectionStates.PARTIAL,
                            if (complete) "" else "partial",
                            dao.articleKindCount("article"),
                            missing, now,
                            if (complete) now else null,
                            if (complete) section.hash else null,
                        )
                    }
                    newKeys.addAll(fresh.map { it.appKey })
                    missingTotal += missing
                    if (!complete) anyIncomplete = true
                    outcomes.add(
                        SectionOutcome(
                            SyncSections.ARTICLES,
                            if (complete) SectionStates.UP_TO_DATE else SectionStates.PARTIAL,
                            missing,
                        )
                    )
                }
            }
        }

        // Shorts section: resolved through short_cards.json, not
        // per-item files, so the plan runs with requiresFile false.
        if (wants(SyncSections.SHORTS)) {
            val section = manifest.sections[SyncSections.SHORTS]
            if (section == null) {
                outcomes.add(SectionOutcome(SyncSections.SHORTS, SectionStates.UNKNOWN, 0))
            } else {
                val storedHash = dao.metaValue(SyncSections.hashKey(SyncSections.SHORTS))
                if (DeltaPlanner.isUpToDate(storedHash, section.hash, forceRefresh)) {
                    val count = dao.articleKindCount("short")
                    recordSectionMeta(
                        SyncSections.SHORTS, SectionStates.UP_TO_DATE, "",
                        count, 0, now, null, null,
                    )
                    outcomes.add(SectionOutcome(SyncSections.SHORTS, SectionStates.UP_TO_DATE, 0))
                } else {
                    val plan = DeltaPlanner.plan(
                        section.items, storedHashes, existingKeys, requiresFile = false,
                    )
                    val fresh = mutableListOf<ArticleEntity>()
                    val missingKeys = mutableListOf<String>()
                    if (plan.toFetch.isNotEmpty()) {
                        val shortsText = when (val outcome = httpGet("$base/short_cards.json")) {
                            is HttpOutcome.Ok -> outcome.body
                            else -> null
                        }
                        if (shortsText == null) {
                            missingKeys.addAll(plan.toFetch.map { it.appKey })
                        } else {
                            try {
                                val arr = JSONArray(shortsText)
                                val wanted = plan.toFetch.map { it.id }.toSet()
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
                                    plan.toFetch.filter { it.id !in found }
                                        .map { it.appKey }
                                )
                            } catch (_: Exception) {
                                missingKeys.addAll(plan.toFetch.map { it.appKey })
                            }
                        }
                    }
                    val tombstoneRows = plan.tombstones.mapNotNull { item ->
                        val stored = existingByKey[item.appKey] ?: return@mapNotNull null
                        stored.copy(
                            status = "retracted",
                            retractedAtEpoch = now,
                            updatedAtEpoch = now,
                        )
                    }
                    val complete = missingKeys.isEmpty() && plan.corruptCount == 0
                    val missing = missingKeys.size + plan.corruptCount
                    val joins = section.items.mapNotNull { item ->
                        val guid = joinGuids[item.appKey]
                        if (item.storyId == null && guid == null &&
                            item.channelMessageId == null
                        ) {
                            null
                        } else {
                            JoinKeyEntity(
                                item.appKey, guid, item.storyId, item.channelMessageId,
                            )
                        }
                    }
                    db.withTransaction {
                        if (fresh.isNotEmpty()) dao.upsertAll(fresh)
                        if (tombstoneRows.isNotEmpty()) dao.upsertAll(tombstoneRows)
                        if (joins.isNotEmpty()) dao.upsertJoinKeys(joins)
                        recordSectionMeta(
                            SyncSections.SHORTS,
                            if (complete) SectionStates.UP_TO_DATE else SectionStates.PARTIAL,
                            if (complete) "" else "partial",
                            dao.articleKindCount("short"),
                            missing, now,
                            if (complete) now else null,
                            if (complete) section.hash else null,
                        )
                    }
                    newKeys.addAll(fresh.map { it.appKey })
                    missingTotal += missing
                    if (!complete) anyIncomplete = true
                    outcomes.add(
                        SectionOutcome(
                            SyncSections.SHORTS,
                            if (complete) SectionStates.UP_TO_DATE else SectionStates.PARTIAL,
                            missing,
                        )
                    )
                }
            }
        }

        // Market section: one file, one transaction. A market
        // failure is that section's failure only; articles synced
        // in this same pass keep their success.
        if (wants(SyncSections.MARKET)) {
            val section = manifest.sections[SyncSections.MARKET]
            if (section == null) {
                outcomes.add(SectionOutcome(SyncSections.MARKET, SectionStates.UNKNOWN, 0))
            } else {
                val storedHash = dao.metaValue(SyncSections.hashKey(SyncSections.MARKET))
                if (DeltaPlanner.isUpToDate(storedHash, section.hash, forceRefresh)) {
                    recordSectionMeta(
                        SyncSections.MARKET, SectionStates.UP_TO_DATE, "",
                        dao.marketCount(), 0, now, null, null,
                    )
                    outcomes.add(SectionOutcome(SyncSections.MARKET, SectionStates.UP_TO_DATE, 0))
                } else {
                    val marketOutcome = httpGet("$base/${section.file ?: "market.json"}")
                    val marketText = if (marketOutcome is HttpOutcome.Ok) {
                        marketOutcome.body
                    } else {
                        null
                    }
                    if (marketText == null) {
                        val code = marketOutcome.toFailedCode()
                        recordSectionMeta(
                            SyncSections.MARKET, SectionStates.FAILED, code,
                            null, 0, now, null, null,
                        )
                        anyIncomplete = true
                        outcomes.add(SectionOutcome(SyncSections.MARKET, SectionStates.FAILED, 0))
                    } else {
                        var marketFresh = emptyList<MarketSnapshotEntity>()
                        var formulasFresh = emptyList<MarketFormulaEntity>()
                        var corruptSnaps = 0
                        var marketFailed = false
                        // Phase 4: admin thresholds from the edge
                        // market_alerts object (spec §4.1). Null =
                        // OFF. They are read here, never defaulted
                        // and never taken from help-text examples.
                        var marketThresholds: MarketThresholds? = null
                        try {
                            val marketObj = JSONObject(marketText)
                            val snaps = marketObj.optJSONArray("snapshots") ?: JSONArray()
                            val parsed = mutableListOf<MarketSnapshotEntity>()
                            for (i in 0 until snaps.length()) {
                                try {
                                    parsed.add(
                                        ArticleParsers.marketEntityFromJson(snaps.getJSONObject(i))
                                    )
                                } catch (_: Exception) {
                                    corruptSnaps++
                                }
                            }
                            marketFresh = parsed
                            val farr = marketObj.optJSONArray("formulas_active") ?: JSONArray()
                            val parsedFormulas = mutableListOf<MarketFormulaEntity>()
                            for (i in 0 until farr.length()) {
                                try {
                                    val formula = MarketFormulas.formulaFromJson(
                                        farr.getJSONObject(i)
                                    ) ?: continue
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
                            val alertsObj = marketObj.optJSONObject("market_alerts")
                            marketThresholds = MarketThresholds(
                                bubblePct = alertsObj?.let {
                                    if (it.isNull("bubble_threshold_pct")) null
                                    else it.optDouble("bubble_threshold_pct")
                                        .takeIf { v -> v.isFinite() }
                                },
                                spreadPct = alertsObj?.let {
                                    if (it.isNull("spread_threshold_pct")) null
                                    else it.optDouble("spread_threshold_pct")
                                        .takeIf { v -> v.isFinite() }
                                },
                            )
                        } catch (_: Exception) {
                            marketFailed = true
                        }
                        if (marketFailed) {
                            recordSectionMeta(
                                SyncSections.MARKET, SectionStates.FAILED, "bad_market",
                                null, 0, now, null, null,
                            )
                            anyIncomplete = true
                            outcomes.add(
                                SectionOutcome(SyncSections.MARKET, SectionStates.FAILED, 0)
                            )
                        } else {
                            val complete = corruptSnaps == 0
                            db.withTransaction {
                                if (marketFresh.isNotEmpty()) dao.upsertMarket(marketFresh)
                                if (formulasFresh.isNotEmpty()) {
                                    dao.deleteAllFormulas()
                                    dao.upsertFormulas(formulasFresh)
                                }
                                // Phase 4 thresholds: stored in meta
                                // on every successful market sync, so
                                // the banner/alert logic always reads
                                // the edge value, never a stale one.
                                // Absent object = all null = OFF.
                                val thresholds = marketThresholds ?: MarketThresholds(null, null)
                                dao.upsertMeta(
                                    MetaEntity(
                                        "p4_market_alerts_bubble",
                                        thresholds.bubblePct?.toString() ?: "",
                                    )
                                )
                                dao.upsertMeta(
                                    MetaEntity(
                                        "p4_market_alerts_spread",
                                        thresholds.spreadPct?.toString() ?: "",
                                    )
                                )
                                recordSectionMeta(
                                    SyncSections.MARKET,
                                    if (complete) SectionStates.UP_TO_DATE
                                    else SectionStates.PARTIAL,
                                    if (complete) "" else "partial",
                                    dao.marketCount(),
                                    corruptSnaps, now,
                                    if (complete) now else null,
                                    if (complete) section.hash else null,
                                )
                            }
                            // Phase 4 alert evaluation (§4.6): on
                            // every market-section sync completion
                            // (and on manual market refresh, which
                            // runs this same block via syncOnly).
                            // No separate alert poll, no push.
                            evaluateMarketAlerts(now)
                            if (!complete) anyIncomplete = true
                            outcomes.add(
                                SectionOutcome(
                                    SyncSections.MARKET,
                                    if (complete) SectionStates.UP_TO_DATE
                                    else SectionStates.PARTIAL,
                                    corruptSnaps,
                                )
                            )
                        }
                    }
                }
            }
        }

        // Channel refs: no local table; the section state records
        // reachability and the manifest count, so Diagnostics can
        // still show it honestly. A fetch failure is a failed
        // section, never an empty feed.
        if (wants(SyncSections.CHANNEL_REFS)) {
            val section = manifest.sections[SyncSections.CHANNEL_REFS]
            if (section == null) {
                outcomes.add(SectionOutcome(SyncSections.CHANNEL_REFS, SectionStates.UNKNOWN, 0))
            } else {
                val storedHash = dao.metaValue(SyncSections.hashKey(SyncSections.CHANNEL_REFS))
                if (DeltaPlanner.isUpToDate(storedHash, section.hash, forceRefresh)) {
                    recordSectionMeta(
                        SyncSections.CHANNEL_REFS, SectionStates.UP_TO_DATE, "",
                        section.count, 0, now, null, null,
                    )
                    outcomes.add(
                        SectionOutcome(SyncSections.CHANNEL_REFS, SectionStates.UP_TO_DATE, 0)
                    )
                } else {
                    val refsOutcome = httpGet("$base/${section.file ?: "channel_refs.json"}")
                    if (refsOutcome is HttpOutcome.Ok) {
                        db.withTransaction {
                            recordSectionMeta(
                                SyncSections.CHANNEL_REFS, SectionStates.UP_TO_DATE, "",
                                section.count, 0, now, now, section.hash,
                            )
                        }
                        outcomes.add(
                            SectionOutcome(
                                SyncSections.CHANNEL_REFS, SectionStates.UP_TO_DATE, 0
                            )
                        )
                    } else {
                        val code = refsOutcome.toFailedCode()
                        recordSectionMeta(
                            SyncSections.CHANNEL_REFS, SectionStates.FAILED, code,
                            null, 0, now, null, null,
                        )
                        anyIncomplete = true
                        outcomes.add(
                            SectionOutcome(SyncSections.CHANNEL_REFS, SectionStates.FAILED, 0)
                        )
                    }
                }
            }
        }

        // Invites + revocations: a fetch failure skips the re-check
        // entirely (offline must never lock a reader out) but still
        // records failed section states, so Diagnostics stays honest.
        if (wants(SyncSections.INVITES) || wants(SyncSections.REVOCATIONS)) {
            val invSection = manifest.sections[SyncSections.INVITES]
            val revSection = manifest.sections[SyncSections.REVOCATIONS]
            if (inviteLists != null) {
                db.withTransaction {
                    dao.upsertMeta(
                        MetaEntity(
                            "edge_invite_hashes", JSONArray(inviteLists.first).toString()
                        )
                    )
                    dao.upsertMeta(
                        MetaEntity(
                            "edge_revocations", JSONArray(inviteLists.second).toString()
                        )
                    )
                    if (wants(SyncSections.INVITES)) {
                        recordSectionMeta(
                            SyncSections.INVITES, SectionStates.UP_TO_DATE, "",
                            inviteLists.first.size, 0, now, now,
                            invSection?.hash?.takeIf { it.isNotBlank() },
                        )
                    }
                    if (wants(SyncSections.REVOCATIONS)) {
                        recordSectionMeta(
                            SyncSections.REVOCATIONS, SectionStates.UP_TO_DATE, "",
                            inviteLists.second.size, 0, now, now,
                            revSection?.hash?.takeIf { it.isNotBlank() },
                        )
                    }
                }
                if (wants(SyncSections.INVITES)) {
                    outcomes.add(SectionOutcome(SyncSections.INVITES, SectionStates.UP_TO_DATE, 0))
                }
                if (wants(SyncSections.REVOCATIONS)) {
                    outcomes.add(
                        SectionOutcome(SyncSections.REVOCATIONS, SectionStates.UP_TO_DATE, 0)
                    )
                }
            } else {
                if (wants(SyncSections.INVITES)) {
                    recordSectionMeta(
                        SyncSections.INVITES, SectionStates.FAILED, "invite_fetch",
                        null, 0, now, null, null,
                    )
                    anyIncomplete = true
                    outcomes.add(SectionOutcome(SyncSections.INVITES, SectionStates.FAILED, 0))
                }
                if (wants(SyncSections.REVOCATIONS)) {
                    recordSectionMeta(
                        SyncSections.REVOCATIONS, SectionStates.FAILED, "invite_fetch",
                        null, 0, now, null, null,
                    )
                    anyIncomplete = true
                    outcomes.add(
                        SectionOutcome(SyncSections.REVOCATIONS, SectionStates.FAILED, 0)
                    )
                }
            }
        }

        // Phase 2 sections: typed channel items, bundles, radar.
        // Same Phase 1 rules (manifest-first, per-section hash,
        // one transaction per section, hash stored only on complete
        // success, failed/corrupt section never empties its cache).
        // No new sync mechanism is invented here.
        val phase2Anomalies = AnomalyBag()
        if (wants(SyncSections.CHANNEL_TYPED)) {
            outcomes.add(syncChannelTypedSection(base, manifest, forceRefresh, now, phase2Anomalies, pollPath).also {
                if (it.state == SectionStates.FAILED || it.state == SectionStates.PARTIAL) {
                    anyIncomplete = true
                }
                missingTotal += it.missingCount
            })
        }
        if (wants(SyncSections.BUNDLES)) {
            outcomes.add(syncBundlesSection(base, manifest, forceRefresh, now, phase2Anomalies).also {
                if (it.state == SectionStates.FAILED || it.state == SectionStates.PARTIAL) {
                    anyIncomplete = true
                }
                missingTotal += it.missingCount
            })
        }
        if (wants(SyncSections.RADAR)) {
            outcomes.add(syncRadarSection(base, manifest, forceRefresh, now, phase2Anomalies).also {
                if (it.state == SectionStates.FAILED || it.state == SectionStates.PARTIAL) {
                    anyIncomplete = true
                }
                missingTotal += it.missingCount
            })
        }
        // Computed (overwrite-for-today) Phase 2 anomaly codes:
        // caps, bundle-shape flags. Parse anomalies (above) are
        // accumulated for the day instead.
        persistPhase2Anomalies(now, phase2Anomalies, computePhase2Computed(now))
        if (onlySection == null) {
            dispatchPhase4Notifications(now)
        }

        // Not-yet-published and gated sections: state comes from the
        // manifest, no fetch is attempted, nothing is invented.
        for (key in listOf(
            SyncSections.NIGHTLY, SyncSections.VOICES, SyncSections.SPECIAL_ISSUES,
        )) {
            if (!wants(key)) continue
            val section = manifest.sections[key]
            val state = when (section?.status) {
                "gated_frozen" -> SectionStates.GATED_FROZEN
                "not_yet_published" -> SectionStates.NOT_YET_PUBLISHED
                "" -> SectionStates.NOT_YET_PUBLISHED
                null -> SectionStates.UNKNOWN
                else -> SectionStates.UNKNOWN
            }
            recordSectionMeta(
                key, state, "", section?.count ?: 0, 0, now, null, null,
            )
            outcomes.add(SectionOutcome(key, state, 0))
        }

        // Legacy global metas, kept for the feed's last-sync line:
        // last_sync_at advances only on a fully complete pass.
        db.withTransaction {
            dao.upsertMeta(MetaEntity("last_sync_attempt_at", now.toString()))
            if (!anyIncomplete && onlySection == null) {
                dao.upsertMeta(MetaEntity("last_sync_at", now.toString()))
                dao.upsertMeta(MetaEntity("last_sync_error_code", ""))
            } else if (onlySection == null) {
                dao.upsertMeta(MetaEntity("last_sync_error_code", "partial"))
            }
        }

        // Invite re-check (M7 rule, unchanged): only a successfully
        // fetched list can re-lock, and cached rows are never deleted.
        if (inviteLists != null) {
            val storedHash = settings.settings.first().inviteHash
            if (storedHash != null &&
                !InviteGate.stillValid(storedHash, inviteLists.first, inviteLists.second)
            ) {
                settings.setInviteOk(false)
            }
        }

        val total = dao.allIds().size
        if (onlySection != null) {
            val only = outcomes.firstOrNull { it.key == onlySection }
            return when (only?.state) {
                SectionStates.UP_TO_DATE -> SyncResult.Ok(newKeys, total, outcomes)
                SectionStates.PARTIAL -> SyncResult.Partial(newKeys, missingTotal, total, outcomes)
                SectionStates.FAILED -> SyncResult.Failed(
                    dao.metaValue(SyncSections.errorKey(onlySection)) ?: "network"
                )
                else -> SyncResult.Ok(newKeys, total, outcomes)
            }
        }
        return if (anyIncomplete) {
            SyncResult.Partial(newKeys, missingTotal, total, outcomes)
        } else {
            SyncResult.Ok(newKeys, total, outcomes)
        }
    }

    // ---- Phase 2 section sync (channel_typed, bundles, radar) ----

    private suspend fun syncChannelTypedSection(
        base: String,
        manifest: EdgeManifest,
        forceRefresh: Boolean,
        now: Long,
        anomalies: AnomalyBag,
        pollPath: String = "periodic_or_foreground",
    ): SectionOutcome {
        val key = SyncSections.CHANNEL_TYPED
        val section = manifest.sections[key]
            ?: return SectionOutcome(key, SectionStates.UNKNOWN, 0)
        val storedHash = dao.metaValue(SyncSections.hashKey(key))
        if (DeltaPlanner.isUpToDate(storedHash, section.hash, forceRefresh)) {
            recordSectionMeta(
                key, SectionStates.UP_TO_DATE, "",
                channelDao.channelCount(), 0, now, null, null,
            )
            // Held release is a time rule, not a new-content rule:
            // an up-to-date section still releases items whose
            // 08:00 has passed on a successful sync.
            channelDao.releaseHeld(now)
            return SectionOutcome(key, SectionStates.UP_TO_DATE, 0)
        }
        val existing = channelDao.allChannelItems().associateBy { it.appKey }
        val storedHashes = existing.mapValues { it.value.contentHash }
        val plan = DeltaPlanner.plan(
            section.items, storedHashes, existing.keys, requiresFile = false,
        )
        val fresh = mutableListOf<ChannelItemEntity>()
        var latestChain: EdgeChain? = null
        val missingKeys = mutableListOf<String>()
        if (plan.toFetch.isNotEmpty()) {
            val text = when (val outcome = httpGet("$base/${section.file ?: "channel_typed.json"}")) {
                is HttpOutcome.Ok -> outcome.body
                else -> null
            }
            if (text == null) {
                missingKeys.addAll(plan.toFetch.map { it.appKey })
            } else {
                try {
                    val arr = JSONObject(text).optJSONArray("items") ?: JSONArray()
                    val byKey = mutableMapOf<String, JSONObject>()
                    for (i in 0 until arr.length()) {
                        try {
                            val obj = arr.getJSONObject(i)
                            val k = obj.optString("app_key")
                            if (k.isNotBlank()) byKey[k] = obj
                        } catch (_: Exception) {
                            continue
                        }
                    }
                    for (item in plan.toFetch) {
                        val obj = byKey[item.appKey]
                        if (obj == null) {
                            missingKeys.add(item.appKey)
                        } else {
                            val parsed = ChannelParsers.parseChannelItem(
                                obj, existing[item.appKey], now, anomalies,
                            )
                            if (parsed != null) {
                                fresh.add(parsed)
                                val chain = ChainRecord.parseEdgeChain(item.appKey, obj)
                                if (chain != null) {
                                    val prev = latestChain
                                    if (prev == null ||
                                        (chain.edgeGeneratedEpoch ?: 0L) >=
                                            (prev.edgeGeneratedEpoch ?: 0L)
                                    ) {
                                        latestChain = chain
                                    }
                                }
                            } else missingKeys.add(item.appKey)
                        }
                    }
                } catch (_: Exception) {
                    missingKeys.addAll(plan.toFetch.map { it.appKey })
                }
            }
        }
        val tombstoneRows = plan.tombstones.mapNotNull { item ->
            existing[item.appKey]?.copy(status = "retracted", updatedAtEpoch = now)
        }
        val complete = missingKeys.isEmpty() && plan.corruptCount == 0
        val missing = missingKeys.size + plan.corruptCount
        db.withTransaction {
            if (fresh.isNotEmpty()) channelDao.upsertChannelItems(fresh)
            if (tombstoneRows.isNotEmpty()) channelDao.upsertChannelItems(tombstoneRows)
            channelDao.releaseHeld(now)
            recordSectionMeta(
                key,
                if (complete) SectionStates.UP_TO_DATE else SectionStates.PARTIAL,
                if (complete) "" else "partial",
                channelDao.channelCount(),
                missing, now,
                if (complete) now else null,
                if (complete) section.hash else null,
            )
        }
        // Phase 3 chain record (spec 3.2): the latest chain-carrying
        // item applied by this sync becomes the Diagnostics chain.
        // app_manifest_seen_epoch is approximated by the sync start
        // (the manifest is fetched first, at sync start); the
        // applied epoch is the post-transaction instant. A
        // re-sync of the same item keeps an already-recorded
        // notification time (it is never re-stamped).
        val chain = latestChain
        if (chain != null && fresh.isNotEmpty()) {
            val stored = ChainRecord.fromMetaJson(dao.metaValue(ChainRecord.META_KEY))
            val notified = if (stored?.appKey == chain.appKey) {
                stored.appNotifiedEpoch
            } else {
                null
            }
            dao.upsertMeta(
                MetaEntity(
                    ChainRecord.META_KEY,
                    chain.copy(
                        appManifestSeenEpoch = now / 1000,
                        appItemAppliedEpoch = System.currentTimeMillis() / 1000,
                        appNotifiedEpoch = notified,
                        pollPath = pollPath,
                    ).toMetaJson(),
                )
            )
        }
        return SectionOutcome(
            key,
            if (complete) SectionStates.UP_TO_DATE else SectionStates.PARTIAL,
            missing,
        )
    }

    private suspend fun syncBundlesSection(
        base: String,
        manifest: EdgeManifest,
        forceRefresh: Boolean,
        now: Long,
        anomalies: AnomalyBag,
    ): SectionOutcome {
        val key = SyncSections.BUNDLES
        val section = manifest.sections[key]
            ?: return SectionOutcome(key, SectionStates.UNKNOWN, 0)
        val storedHash = dao.metaValue(SyncSections.hashKey(key))
        if (DeltaPlanner.isUpToDate(storedHash, section.hash, forceRefresh)) {
            recordSectionMeta(
                key, SectionStates.UP_TO_DATE, "",
                channelDao.allBundles().size, 0, now, null, null,
            )
            return SectionOutcome(key, SectionStates.UP_TO_DATE, 0)
        }
        val bundleOutcome = httpGet("$base/${section.file ?: "bundles.json"}")
        val text = when (bundleOutcome) {
            is HttpOutcome.Ok -> bundleOutcome.body
            else -> null
        }
        if (text == null) {
            recordSectionMeta(
                key, SectionStates.FAILED, bundleOutcome.toFailedCode(),
                null, 0, now, null, null,
            )
            return SectionOutcome(key, SectionStates.FAILED, 0)
        }
        val parsed = mutableListOf<BundleEntity>()
        var corrupt = 0
        try {
            val arr = JSONObject(text).optJSONArray("bundles") ?: JSONArray()
            for (i in 0 until arr.length()) {
                try {
                    val bundle = ChannelParsers.parseBundle(arr.getJSONObject(i), anomalies)
                    if (bundle != null) parsed.add(bundle)
                } catch (_: Exception) {
                    corrupt++
                }
            }
        } catch (_: Exception) {
            recordSectionMeta(key, SectionStates.FAILED, "bad_section", null, 0, now, null, null)
            return SectionOutcome(key, SectionStates.FAILED, 0)
        }
        val complete = corrupt == 0
        db.withTransaction {
            if (parsed.isNotEmpty()) channelDao.upsertBundles(parsed)
            recordSectionMeta(
                key,
                if (complete) SectionStates.UP_TO_DATE else SectionStates.PARTIAL,
                if (complete) "" else "partial",
                channelDao.allBundles().size,
                corrupt, now,
                if (complete) now else null,
                if (complete) section.hash else null,
            )
        }
        return SectionOutcome(
            key,
            if (complete) SectionStates.UP_TO_DATE else SectionStates.PARTIAL,
            corrupt,
        )
    }

    private suspend fun syncRadarSection(
        base: String,
        manifest: EdgeManifest,
        forceRefresh: Boolean,
        now: Long,
        anomalies: AnomalyBag,
    ): SectionOutcome {
        val key = SyncSections.RADAR
        val section = manifest.sections[key]
            ?: return SectionOutcome(key, SectionStates.UNKNOWN, 0)
        val storedHash = dao.metaValue(SyncSections.hashKey(key))
        if (DeltaPlanner.isUpToDate(storedHash, section.hash, forceRefresh)) {
            recordSectionMeta(
                key, SectionStates.UP_TO_DATE, "",
                channelDao.radarCount(), 0, now, null, null,
            )
            return SectionOutcome(key, SectionStates.UP_TO_DATE, 0)
        }
        val radarOutcome = httpGet("$base/${section.file ?: "radar.json"}")
        val text = when (radarOutcome) {
            is HttpOutcome.Ok -> radarOutcome.body
            else -> null
        }
        if (text == null) {
            recordSectionMeta(
                key, SectionStates.FAILED, radarOutcome.toFailedCode(),
                null, 0, now, null, null,
            )
            return SectionOutcome(key, SectionStates.FAILED, 0)
        }
        val parsed = mutableListOf<RadarSignalEntity>()
        var corrupt = 0
        var interval: Int? = null
        try {
            val root = JSONObject(text)
            val warRoom = root.optJSONObject("war_room")
            if (warRoom != null) {
                dao.upsertMeta(MetaEntity("p2_war_level", warRoom.optString("crisis_level")))
                dao.upsertMeta(MetaEntity("p2_war_why", warRoom.optString("why_level")))
                interval = if (warRoom.isNull("radar_interval_minutes")) {
                    null
                } else {
                    warRoom.optInt("radar_interval_minutes")
                }
                dao.upsertMeta(
                    MetaEntity("p2_radar_interval", interval?.toString() ?: "")
                )
            } else {
                dao.upsertMeta(MetaEntity("p2_war_level", ""))
                dao.upsertMeta(MetaEntity("p2_war_why", ""))
                dao.upsertMeta(MetaEntity("p2_radar_interval", ""))
            }
            val arr = root.optJSONArray("signals") ?: JSONArray()
            for (i in 0 until arr.length()) {
                try {
                    val signal = ChannelParsers.parseRadarSignal(arr.getJSONObject(i), anomalies)
                    if (signal != null) parsed.add(signal)
                } catch (_: Exception) {
                    corrupt++
                }
            }
        } catch (_: Exception) {
            recordSectionMeta(key, SectionStates.FAILED, "bad_section", null, 0, now, null, null)
            return SectionOutcome(key, SectionStates.FAILED, 0)
        }
        if (interval == null) {
            anomalies.add(Phase2Anomalies.RADAR_INTERVAL_MISSING)
        }
        val complete = corrupt == 0
        db.withTransaction {
            if (parsed.isNotEmpty()) channelDao.upsertRadarSignals(parsed)
            recordSectionMeta(
                key,
                if (complete) SectionStates.UP_TO_DATE else SectionStates.PARTIAL,
                if (complete) "" else "partial",
                channelDao.radarCount(),
                corrupt, now,
                if (complete) now else null,
                if (complete) section.hash else null,
            )
        }
        return SectionOutcome(
            key,
            if (complete) SectionStates.UP_TO_DATE else SectionStates.PARTIAL,
            corrupt,
        )
    }

    // ---- Phase 2 Diagnostics data (counts only, never content) ----

    private fun latinDigits(raw: String): String = buildString {
        for (c in raw) {
            append(
                when (c) {
                    in '۰'..'۹' -> ('0' + (c - '۰'))
                    in '٠'..'٩' -> ('0' + (c - '٠'))
                    else -> c
                }
            )
        }
    }

    /** Computed codes are overwritten for today; parse codes accumulate. */
    private suspend fun persistPhase2Anomalies(
        now: Long,
        accumulated: AnomalyBag,
        computed: Map<String, Int>,
    ) {
        val dayKey = Jalali.shamsiDayKey(now)
        val metaKey = "p2_anomalies_$dayKey"
        val existing = try {
            val obj = JSONObject(dao.metaValue(metaKey) ?: "{}")
            val map = mutableMapOf<String, Int>()
            for (k in obj.keys()) map[k] = obj.optInt(k)
            map
        } catch (_: Exception) {
            mutableMapOf()
        }
        for ((code, n) in accumulated.counts) {
            existing[code] = (existing[code] ?: 0) + n
        }
        for ((code, n) in computed) {
            existing[code] = n
        }
        val out = JSONObject()
        for ((code, n) in existing) out.put(code, n)
        dao.upsertMeta(MetaEntity(metaKey, out.toString()))
    }

    private suspend fun computePhase2Computed(now: Long): Map<String, Int> {
        val dayKey = Jalali.shamsiDayKey(now)
        val (start, end) = Jalali.tehranDayRangeEpoch(now)
        val items = channelDao.allChannelItems().filter {
            it.status != "retracted" && it.publishedAtEpoch in start until end
        }
        val computed = mutableMapOf<String, Int>()
        var excess = 0
        for ((format, list) in items.groupBy { it.formatType }) {
            val cap = Formats.ALL[format]?.dailyCap
            if (cap != null && list.size > cap) excess += list.size - cap
        }
        computed[Phase2Anomalies.FORMAT_OVER_CAP] = excess
        val flashCount = items.count { it.formatType == "FLASH" }
        computed[Phase2Anomalies.FLASH_CAP_HIT] =
            if (flashCount >= 999) 1 else 0
        val bundles = channelDao.allBundles().filter {
            it.status != "retracted" && latinDigits(it.bundleDateShamsi) == dayKey
        }
        val byKind = bundles.groupBy { it.kind }
        val shortNews = byKind[BundleKinds.SHORT_NEWS] ?: emptyList()
        computed[Phase2Anomalies.SHORT_NEWS_TWICE] =
            if (shortNews.size > 1) shortNews.size - 1 else 0
        computed[Phase2Anomalies.SHORT_NEWS_SMALL] =
            shortNews.count { it.memberAppKeys.size < 3 }
        val morning = byKind[BundleKinds.MORNING_BUNDLE] ?: emptyList()
        computed[Phase2Anomalies.MORNING_BUNDLE_TWICE] =
            if (morning.size > 1) morning.size - 1 else 0
        computed[Phase2Anomalies.NIGHT_DESK_RANGE] =
            (byKind[BundleKinds.NIGHT_DESK] ?: emptyList())
                .count { it.memberAppKeys.size !in 12..30 }
        val allItems = channelDao.allChannelItems().associateBy { it.appKey }
        var mismatch = 0
        for (bundle in bundles) {
            var bad = false
            for ((index, memberKey) in bundle.memberAppKeys.withIndex()) {
                val item = allItems[memberKey]
                if (item != null && item.bundlePosition != null &&
                    item.bundlePosition != index + 1
                ) {
                    bad = true
                    break
                }
            }
            if (bad) mismatch++
        }
        computed[Phase2Anomalies.BUNDLE_POSITION_MISMATCH] = mismatch
        return computed
    }

    /** Today's Phase 2 anomaly counts (non-zero lines only, in UI). */
    suspend fun anomalyCountsToday(): Map<String, Int> {
        val dayKey = Jalali.shamsiDayKey(System.currentTimeMillis())
        return try {
            val obj = JSONObject(dao.metaValue("p2_anomalies_$dayKey") ?: "{}")
            val map = mutableMapOf<String, Int>()
            for (k in obj.keys()) {
                val n = obj.optInt(k)
                if (n > 0) map[k] = n
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Per-format counts for today (Tehran day) against daily caps. */
    suspend fun formatCountsToday(): Map<String, Int> {
        val now = System.currentTimeMillis()
        val (start, end) = Jalali.tehranDayRangeEpoch(now)
        return channelDao.allChannelItems()
            .filter { it.status != "retracted" && it.publishedAtEpoch in start until end }
            .groupingBy { it.formatType }
            .eachCount()
    }

    /** War Room header fields shipped by the edge, if any. */
    suspend fun warRoomMeta(): Triple<String?, String?, Int?> {
        val level = dao.metaValue("p2_war_level")?.takeIf { it.isNotBlank() }
        val why = dao.metaValue("p2_war_why")?.takeIf { it.isNotBlank() }
        val interval = dao.metaValue("p2_radar_interval")?.toIntOrNull()
        return Triple(level, why, interval)
    }

    private suspend fun readMetaStringSet(key: String): Set<String> {
        return try {
            val arr = JSONArray(dao.metaValue(key) ?: "[]")
            (0 until arr.length()).mapNotNull {
                arr.optString(it).takeIf { s -> s.isNotBlank() }
            }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private suspend fun writeMetaStringSet(key: String, values: Set<String>) {
        dao.upsertMeta(MetaEntity(key, JSONArray(values.toList()).toString()))
    }

    suspend fun markBundleOpened(bundleId: String) {
        val opened = readMetaStringSet("p2_opened_bundles") + bundleId
        writeMetaStringSet("p2_opened_bundles", opened)
    }

    /**
     * Phase 2 notification dispatch, full syncs only. Urgent:
     * one notification per newly synced INSTANT item, deduped by
     * the stored notified flag — re-syncs never re-notify, and
     * an item that arrived while the toggle was off is marked
     * notified without firing (no retro-notification on toggle).
     * Morning Bundle: exactly one default-channel notification
     * per bundle, never before 08:00 Tehran, suppressed when the
     * bundle was already opened (spec 9.5).
     */
    /**
     * Phase 3: stamp app_notified_epoch on the stored chain when
     * its item actually fires an urgent notification. Items that
     * arrive while the toggle is off are marked notified without
     * firing, so their chain keeps app_notified_epoch null (no
     * notification was due/fired -- a valid value, not a missing
     * measurement, per spec 3.1).
     */
    private suspend fun markChainNotified(appKey: String, now: Long) {
        val stored = ChainRecord.fromMetaJson(dao.metaValue(ChainRecord.META_KEY))
            ?: return
        if (stored.appKey != appKey || stored.appNotifiedEpoch != null) return
        dao.upsertMeta(
            MetaEntity(
                ChainRecord.META_KEY,
                stored.copy(appNotifiedEpoch = now / 1000).toMetaJson(),
            )
        )
    }

    // ---- Phase 4: thresholds, alert evaluation, dispatcher ----

    /** Edge thresholds as last stored by a market sync; null = OFF. */
    suspend fun marketThresholds(): MarketThresholds {
        val bubble = dao.metaValue("p4_market_alerts_bubble")
            ?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val spread = dao.metaValue("p4_market_alerts_spread")
            ?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        return MarketThresholds(bubble, spread)
    }

    suspend fun dismissedBannerSnapshot(metric: AlertMetric): String? {
        val key = if (metric == AlertMetric.BUBBLE) {
            "p4_banner_dismissed_bubble"
        } else {
            "p4_banner_dismissed_spread"
        }
        return dao.metaValue(key)?.takeIf { it.isNotBlank() }
    }

    suspend fun dismissMarketBanner(metric: AlertMetric, snapshotId: String) {
        val key = if (metric == AlertMetric.BUBBLE) {
            "p4_banner_dismissed_bubble"
        } else {
            "p4_banner_dismissed_spread"
        }
        dao.upsertMeta(MetaEntity(key, snapshotId))
    }

    private suspend fun recordNotificationHistory(
        type: NotificationType,
        title: String,
        targetKey: String?,
        now: Long,
        deferredFromEpoch: Long?,
        suppressedReason: SuppressedReason?,
    ) {
        phase4Dao.insertNotificationHistory(
            NotificationHistoryEntity(
                type = type.key,
                title = title,
                targetKey = targetKey,
                firedAtEpoch = now,
                deferredFromEpoch = deferredFromEpoch,
                suppressedReason = suppressedReason?.key,
            )
        )
        // Spec §4.1 prune: newest 200 rows or 90 days, whichever
        // is smaller (both applied; the smaller set survives).
        phase4Dao.pruneNotificationHistoryTo200()
        phase4Dao.pruneNotificationHistoryOlderThan(now - 90L * 24L * 60L * 60L * 1000L)
    }

    /** Phase 4 Diagnostics data (counts only, never content). */
    suspend fun radarCounts(): Triple<Int, Int, Int> {
        val all = channelDao.allRadar().filter { it.status != "retracted" }
        return Triple(
            all.size,
            all.count { it.status == "active" },
            all.count { it.status == "resolved" },
        )
    }

    suspend fun notificationStatsToday(): Pair<Map<String, Int>, Map<String, Int>> {
        val (start, _) = Jalali.tehranDayRangeEpoch(System.currentTimeMillis())
        val fired = phase4Dao.firedCountsByTypeSince(start)
            .associate { it.type to it.count }
        val suppressed = phase4Dao.suppressedCountsByReasonSince(start)
            .associate { it.suppressedReason to it.count }
        return fired to suppressed
    }

    /**
     * Phase 4 market-alert evaluation (§4.6). Runs on every
     * market-section sync completion (see the market block) and
     * therefore on manual market refresh too. No separate alert
     * poll, no push; alert latency = sync latency.
     *
     * Banner state itself is NOT stored: the Market tab derives
     * it from the latest snapshot + thresholds on every render,
     * so a banner can never outlive the snapshot it describes.
     * Event rows ARE stored (one per metric per snapshot): with
     * notified = 1 only when a notification was actually posted
     * — the cooldown timestamp is written only in that same
     * branch, so a failed/suppressed post never consumes the
     * cooldown (the channel code's defect, not copied).
     */
    private suspend fun evaluateMarketAlerts(now: Long) {
        val thresholds = marketThresholds()
        if (!thresholds.anySet) return
        val latestEntity = dao.marketLatest() ?: return
        val latest = ArticleParsers.marketSnapshotFromEntity(latestEntity)
        val previous = dao.marketPrevious(latestEntity.tsEpoch)?.let {
            ArticleParsers.marketSnapshotFromEntity(it)
        }
        val formulas = dao.formulas().map {
            MarketFormula(
                name = it.name,
                version = it.version,
                definition = it.definition,
                inputs = it.inputs,
                descriptionFa = it.descriptionFa,
            )
        }
        val currentSettings = settings.settings.first()
        val prefs = currentSettings.toNotificationPrefs()
        val lastBubble = currentSettings.marketAlertLastBubbleMs
        val lastSpread = currentSettings.marketAlertLastSpreadMs
        val evals = MarketAlerts.evaluateAll(
            latest, previous, formulas, thresholds,
            lastBubble, lastSpread, now,
        )
        for ((metric, ev) in evals) {
            if (!ev.bannerOn || ev.value == null || ev.threshold == null) continue
            val decision = NotificationPolicy.decide(
                prefs, NotificationType.MARKET_ALERTS, now,
                cooldownOk = ev.cooldownOk,
            )
            var posted = false
            if (ev.shouldNotify && decision.fire) {
                val label = if (metric == AlertMetric.BUBBLE) {
                    "حباب سکه"
                } else {
                    "اسپرد تتر/دلار"
                }
                val valueFa = com.ecojournal.app.util.formatPersianDouble(ev.value, 1)
                val thrFa = com.ecojournal.app.util.formatPersianDouble(ev.threshold, 1)
                Notifications.showMarketAlert(
                    appContext,
                    metric = if (metric == AlertMetric.BUBBLE) "bubble" else "spread",
                    snapshotId = latest.snapshotId,
                    title = "🔔 هشدار بازار: $label",
                    text = "$label به $valueFa٪ رسید (آستانه: $thrFa٪) — ${latest.tsTehran}",
                )
                settings.setMarketAlertLastNotified(metric, now)
                posted = true
                recordNotificationHistory(
                    NotificationType.MARKET_ALERTS, "هشدار بازار: $label",
                    latest.snapshotId, now, null, null,
                )
            } else if (ev.shouldNotify && !decision.fire) {
                // A fresh crossing that was suppressed is a
                // diagnosable candidate (first failing check).
                // A merely-above-threshold snapshot with no fresh
                // crossing is NOT a candidate and records nothing.
                recordNotificationHistory(
                    NotificationType.MARKET_ALERTS, "هشدار بازار",
                    latest.snapshotId, now, null, decision.suppressedReason,
                )
            }
            phase4Dao.upsertAlertEvent(
                MarketAlertEventEntity(
                    eventId = MarketAlerts.eventId(metric, latest.snapshotId),
                    metric = if (metric == AlertMetric.BUBBLE) "bubble" else "spread",
                    snapshotId = latest.snapshotId,
                    valuePct = ev.value,
                    thresholdPct = ev.threshold,
                    firedAtEpoch = now,
                    notified = if (posted) 1 else 0,
                )
            )
        }
    }

    /**
     * Phase 4 notification dispatcher (full syncs only, as before).
     * Replaces the Phase 2 dispatcher with the §4.7 check order
     * (master → per-service toggle → Law 15 → quiet hours →
     * cooldown) and the §4.8 precedence table. Phase 2 rules kept:
     * an item that arrived while its toggle was off is marked
     * notified WITHOUT firing (no retro-notification on toggle);
     * Morning Bundle fires at most once per bundle, never before
     * 08:00 Tehran, suppressed when already opened.
     *
     * Producer coverage, stated plainly: urgent, Morning Bundle,
     * and market alerts (evaluated in the market block) are the
     * wired producers. SITREP / nightly / special-issues / radar
     * toggles are stored and shown in Settings, but no per-item
     * producer fires for them in Phase 4 — Phase 2 deliberately
     * fires no radar notification (P2-A8), SITREP cards arrive
     * via the typed feed without a per-card notification, and
     * the nightly edition is Phase 5. Wiring a producer for any
     * of them is that phase's work, not a silent Phase 4 addition.
     */
    private suspend fun dispatchPhase4Notifications(now: Long) {
        val prefs = settings.settings.first().toNotificationPrefs()
        val isNight = Jalali.isNightTehran(now)
        val inQuiet = prefs.quietHoursEnabled && NotificationPolicy.isInsideQuietHours(
            NotificationPolicy.minuteOfDayTehran(now),
            prefs.quietStartMin, prefs.quietEndMin,
        )

        // Deferred summary flush: first sync after quiet-hours end
        // (and outside night), one summary for all deferred
        // bundle/nightly items. If the user has since turned
        // bundles off, the deferred set is dropped silently —
        // the user's current choice wins over a burst.
        val deferred = readMetaStringSet("p4_deferred_bundles")
        if (deferred.isNotEmpty() && !isNight && !inQuiet) {
            if (prefs.masterEnabled && prefs.notifyBundles) {
                Notifications.showDeferredSummary(appContext, deferred.size)
                recordNotificationHistory(
                    NotificationType.BUNDLES, "خلاصهٔ موارد به‌تعویق‌افتاده",
                    null, now,
                    dao.metaValue("p4_deferred_since")?.toLongOrNull(), null,
                )
            }
            writeMetaStringSet("p4_deferred_bundles", emptySet())
            dao.upsertMeta(MetaEntity("p4_deferred_since", ""))
        }

        for (item in channelDao.unnotifiedInstant()) {
            // unnotifiedInstant returns nightLawResult = 'instant'
            // rows only, so Law 15 eligibility holds by
            // construction; decide() still applies master, toggle,
            // and quiet-hours (with the urgent bypass) in order.
            val decision = NotificationPolicy.decide(
                prefs, NotificationType.URGENT, now, isLaw15Instant = true,
            )
            if (decision.fire) {
                Notifications.showUrgent(
                    appContext,
                    articleId = item.appKey,
                    title = item.headline,
                    text = item.body,
                )
                markChainNotified(item.appKey, now)
                recordNotificationHistory(
                    NotificationType.URGENT, item.headline, item.appKey, now, null, null,
                )
            } else {
                recordNotificationHistory(
                    NotificationType.URGENT, item.headline, item.appKey, now, null,
                    decision.suppressedReason,
                )
            }
            channelDao.markNotified(item.appKey)
        }

        val todayKey = Jalali.shamsiDayKey(now)
        val notified = readMetaStringSet("p2_notified_bundles")
        val opened = readMetaStringSet("p2_opened_bundles")
        val freshNotified = notified.toMutableSet()
        val freshDeferred = readMetaStringSet("p4_deferred_bundles").toMutableSet()
        val suppressedNight = readMetaStringSet("p4_bundle_suppressed_night").toMutableSet()
        for (bundle in channelDao.allBundles()) {
            if (bundle.kind != BundleKinds.MORNING_BUNDLE) continue
            if (bundle.status == "retracted") continue
            if (bundle.bundleId in notified || bundle.bundleId in opened) continue
            val bundleDay = latinDigits(bundle.bundleDateShamsi)
            val pastDay = bundleDay < todayKey
            if (!pastDay && bundleDay != todayKey) continue
            if (!pastDay && isNight) {
                // Night law: no instant bundle notification, ever.
                // Recorded once per bundle (not every night sync);
                // the 08:00 Morning Bundle notification is the
                // deferred presentation, not a second summary.
                if (bundle.bundleId !in suppressedNight) {
                    suppressedNight.add(bundle.bundleId)
                    recordNotificationHistory(
                        NotificationType.BUNDLES, "بستهٔ صبح",
                        bundle.bundleId, now, null, SuppressedReason.NIGHT_LAW,
                    )
                }
                continue
            }
            val decision = NotificationPolicy.decide(
                prefs, NotificationType.BUNDLES, now,
            )
            if (!decision.fire) {
                if (decision.suppressedReason == SuppressedReason.NIGHT_LAW) {
                    // Night-suppressed bundles stay PENDING (not
                    // marked notified) so they can fire at 08:00;
                    // recorded once per bundle, not every sync.
                    if (bundle.bundleId !in suppressedNight) {
                        suppressedNight.add(bundle.bundleId)
                        recordNotificationHistory(
                            NotificationType.BUNDLES, "بستهٔ صبح",
                            bundle.bundleId, now, null, SuppressedReason.NIGHT_LAW,
                        )
                    }
                    continue
                }
                recordNotificationHistory(
                    NotificationType.BUNDLES, "بستهٔ صبح",
                    bundle.bundleId, now, null, decision.suppressedReason,
                )
                if (decision.deferToSummary) {
                    if (freshDeferred.isEmpty()) {
                        dao.upsertMeta(MetaEntity("p4_deferred_since", now.toString()))
                    }
                    freshDeferred.add(bundle.bundleId)
                }
                // Suppressed-by-choice bundles are still marked
                // notified (no retro burst when toggled back on);
                // quiet-deferred bundles are marked too — the
                // deferred summary is their presentation.
                freshNotified.add(bundle.bundleId)
                continue
            }
            val heldCount = bundle.memberAppKeys.count { key ->
                channelDao.channelByAppKey(key)?.wasHeld == true
            }
            val n = if (heldCount > 0) heldCount else bundle.memberAppKeys.size
            Notifications.showMorningBundle(appContext, bundle.bundleId, n)
            recordNotificationHistory(
                NotificationType.BUNDLES, "بستهٔ صبح",
                bundle.bundleId, now, null, null,
            )
            freshNotified.add(bundle.bundleId)
        }
        if (freshNotified != notified) {
            writeMetaStringSet("p2_notified_bundles", freshNotified)
        }
        if (freshDeferred != readMetaStringSet("p4_deferred_bundles")) {
            writeMetaStringSet("p4_deferred_bundles", freshDeferred)
        }
        if (suppressedNight != readMetaStringSet("p4_bundle_suppressed_night")) {
            writeMetaStringSet("p4_bundle_suppressed_night", suppressedNight)
        }
    }

    /** Legacy full sync: index first, then only missing or changed items. */
    private suspend fun syncLegacy(): SyncResult {
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

        // M7 invite re-check: fetch the edge invite + revocation
        // lists in the same sync pass. A fetch failure yields null
        // and the re-check is skipped entirely — offline must never
        // lock a reader out. Only a successfully fetched list can
        // re-lock, and it does so after the content transaction.
        val inviteLists = fetchInviteLists(base)

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
            // M7: store the fetched edge invite lists (hashes only)
            // in the same transaction, so a later code entry can be
            // checked against the edge list without a new fetch.
            if (inviteLists != null) {
                dao.upsertMeta(MetaEntity("edge_invite_hashes",
                    JSONArray(inviteLists.first).toString()))
                dao.upsertMeta(MetaEntity("edge_revocations",
                    JSONArray(inviteLists.second).toString()))
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
        // M7 sync-time re-check: a revoked (or list-dropped) hash
        // re-locks the feed. Cached rows are NOT deleted — only the
        // gate closes, so a re-grant restores them without download.
        if (inviteLists != null) {
            val storedHash = settings.settings.first().inviteHash
            if (storedHash != null &&
                !InviteGate.stillValid(storedHash, inviteLists.first, inviteLists.second)
            ) {
                settings.setInviteOk(false)
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
     * Fetch the edge invite + revocation lists (hashes only).
     * Returns null when either file cannot be fetched or parsed —
     * callers must then skip the re-check, never lock on a failure.
     */
    private fun fetchInviteLists(base: String): Pair<List<String>, List<String>>? {
        fun hashes(url: String): List<String>? {
            val text = when (val outcome = httpGet(url)) {
                is HttpOutcome.Ok -> outcome.body
                else -> return null
            }
            return try {
                val arr = JSONObject(text).optJSONArray("hashes") ?: return null
                (0 until arr.length()).mapNotNull {
                    arr.optString(it).takeIf { s -> s.isNotBlank() }
                }
            } catch (_: Exception) {
                null
            }
        }
        val invites = hashes("$base/invite_hashes.json") ?: return null
        val revocations = hashes("$base/revocations.json") ?: return null
        return invites to revocations
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
