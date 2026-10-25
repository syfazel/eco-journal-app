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
                    syncV3(base, manifest, forceRefresh, null)
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
        return syncV3(base, manifest, forceRefresh, sectionKey)
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
            outcomes.add(syncChannelTypedSection(base, manifest, forceRefresh, now, phase2Anomalies).also {
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
            dispatchPhase2Notifications(now)
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
                            if (parsed != null) fresh.add(parsed)
                            else missingKeys.add(item.appKey)
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
    private suspend fun dispatchPhase2Notifications(now: Long) {
        val enabled = settings.settings.first().notificationsEnabled
        for (item in channelDao.unnotifiedInstant()) {
            if (enabled) {
                Notifications.showUrgent(
                    appContext,
                    articleId = item.appKey,
                    title = item.headline,
                    text = item.body,
                )
            }
            channelDao.markNotified(item.appKey)
        }
        val todayKey = Jalali.shamsiDayKey(now)
        val afterEight = !Jalali.isNightTehran(now)
        val notified = readMetaStringSet("p2_notified_bundles")
        val opened = readMetaStringSet("p2_opened_bundles")
        val freshNotified = notified.toMutableSet()
        for (bundle in channelDao.allBundles()) {
            if (bundle.kind != BundleKinds.MORNING_BUNDLE) continue
            if (bundle.status == "retracted") continue
            if (bundle.bundleId in notified || bundle.bundleId in opened) continue
            val bundleDay = latinDigits(bundle.bundleDateShamsi)
            val pastDay = bundleDay < todayKey
            if (!pastDay && bundleDay != todayKey) continue
            if (!pastDay && !afterEight) continue
            val heldCount = bundle.memberAppKeys.count { key ->
                channelDao.channelByAppKey(key)?.wasHeld == true
            }
            val n = if (heldCount > 0) heldCount else bundle.memberAppKeys.size
            if (enabled) {
                Notifications.showMorningBundle(appContext, bundle.bundleId, n)
            }
            freshNotified.add(bundle.bundleId)
        }
        if (freshNotified != notified) {
            writeMetaStringSet("p2_notified_bundles", freshNotified)
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
