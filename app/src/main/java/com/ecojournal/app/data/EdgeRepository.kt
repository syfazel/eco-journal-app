package com.ecojournal.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.withTransaction
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Phase 4 (v5.3): Yemen-only edge repository.
 *
 * The old multi-section sync (articles / shorts / market /
 * channel_typed / bundles / radar / nightly + the legacy
 * index.json path) is gone with those sections. The app talks
 * to exactly one edge file pair now: `manifest.json` (for the
 * `sections.yemen` hash) and `yemen.json`, plus the invite /
 * revocation lists for the gate re-check. Nothing else is
 * fetched, ever. A Yemen failure never touches any global clock
 * (there is no global clock any more — the Yemen clock lives
 * in yemen_meta, P4 both directions).
 *
 * Edge URL (M5): the Pages root
 * https://syfazel.github.io/eco-journal-app/ — WITHOUT any
 * /out suffix. The edge is static JSON; the app talks to
 * nothing else. No token, key or secret is used here.
 */
object EdgeConfig {
    const val BASE_URL: String = "https://syfazel.github.io/eco-journal-app/"
}

/**
 * Fixed sync error codes (M5, P1-25). The UI maps each code to
 * a fixed Persian string; raw exception text and HTTP internals
 * are never shown to the user and never stored as free text.
 * Codes: "network", "http_404", "http_403", "http_other",
 * "bad_manifest", "bad_yemen", "invite_fetch", "partial",
 * "section_locked" (kept for callers that still pass it),
 * "insecure_edge" (non-HTTPS base).
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

/**
 * v5.1 per-section retry: NETWORK errors only, with a short
 * in-app backoff (500 ms, then 1000 ms). An HTTP error — 404,
 * 403, anything else — is returned immediately and is NEVER
 * retried: retrying a 404 cannot help.
 */
private suspend fun httpGetRetried(url: String, attempts: Int = 3): HttpOutcome {
    var last: HttpOutcome = HttpOutcome.NetworkError
    repeat(attempts) { i ->
        when (val outcome = httpGet(url)) {
            is HttpOutcome.Ok -> return outcome
            is HttpOutcome.HttpError -> return outcome
            is HttpOutcome.NetworkError -> {
                last = outcome
                if (i < attempts - 1) delay(500L shl i)
            }
        }
    }
    return last
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
    companion object {
        // v5.1 process-wide sync mutex. Every EdgeRepository
        // instance is a fresh object, so an instance mutex would
        // serialise nothing. The companion mutex serialises ALL
        // Yemen sync passes in this process — launch sync,
        // periodic worker and manual refresh — so two passes can
        // never interleave transactions. A caller that loses the
        // race simply waits; the lock is held for at most one
        // bounded sync pass (300 s), never indefinitely.
        private val syncMutex = Mutex()
    }

    private val db: AppDatabase = DatabaseProvider.get(context)
    val dao: MetaDao = db.metaDao()
    val yemenDao: YemenDao = db.yemenDao()
    val broadcastDao: BroadcastDao = db.broadcastDao()
    private val appContext: Context = context.applicationContext
    private val settings = SettingsStore(context)

    /** Records an attempt (M5): attempt time on every real attempt,
     * and a fixed error code — "" only on a complete success. */
    private suspend fun recordAttempt(code: String) {
        val now = System.currentTimeMillis()
        dao.upsertMeta(MetaEntity("last_sync_attempt_at", now.toString()))
        dao.upsertMeta(MetaEntity("last_sync_error_code", code))
    }

    // v4.0.1 (fix plan X1): a seed that throws must leave a trace,
    // so Diagnostics and the empty state can tell "seed failed"
    // from "seed empty". Failures here are swallowed on purpose:
    // recording a failure must never itself crash the tab.
    suspend fun recordSeedFailure() {
        try {
            dao.upsertMeta(MetaEntity("seed_error", "seed_failed"))
        } catch (_: Exception) {
        }
    }

    // v5 P1 trace helper: best-effort, never throws, never changes
    // a sync outcome (SyncTrace swallows all failures). Section
    // keys, fixed codes, counts and states ONLY — never content.
    // Callers keep trace writes OUTSIDE Room transactions.
    private suspend fun trace(
        section: String,
        code: String,
        fetched: Int,
        missing: Int,
        state: String,
    ) {
        SyncTrace.record(dao, section, code, fetched, missing, state)
    }

    // v5.1 diagnostics-contract writer. Call sites sit next to
    // the existing trace() calls, i.e. OUTSIDE Room transactions,
    // and the write itself is swallowed — a diagnostics failure
    // can never change a section outcome.
    private suspend fun recordV51(
        section: String,
        code: String,
        fetched: Int,
        missing: Int,
        http: Int,
    ) {
        SyncTrace.recordSectionOutcome(dao, section, code, fetched, missing, http)
    }

    // v5.1 offline probe. Uses ONLY the declared
    // ACCESS_NETWORK_STATE permission (manifest) — no new
    // permission is invented. A null ConnectivityManager (cannot
    // prove offline) reads as online so sync still attempts the
    // fetch and reports its real outcome; a null active network
    // reads as offline so sync returns the fixed "network" code
    // immediately instead of hanging on connect timeouts.
    private fun isOnline(): Boolean {
        return try {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return true
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    // v5.1 process-death state. Written before a pass starts and
    // cleared in a NonCancellable finally when the pass ends
    // (success, failure or timeout). A kill mid-sync therefore
    // leaves "1:<startedEpoch>" behind — never a success hash:
    // section hashes are still stored only on that section's
    // complete success, inside its own transaction. The next pass
    // overwrites the stale flag; it is diagnosis state, not a
    // lock, so a stale flag can never block a later sync.
    private suspend fun markSyncStart(now: Long) {
        try {
            dao.upsertMeta(MetaEntity(SyncTrace.SYNC_IN_PROGRESS_KEY, "1:$now"))
        } catch (_: Exception) {
        }
    }

    private suspend fun markSyncEnd() {
        withContext(NonCancellable) {
            try {
                dao.upsertMeta(MetaEntity(SyncTrace.SYNC_IN_PROGRESS_KEY, "0"))
            } catch (_: Exception) {
        }
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

    /**
     * v5.2 Yemen-only sync (audit §4.3), Phase 4 direct path.
     * The single entry point for Yemen content: manifest-gated,
     * one transaction, hash stored only on complete success.
     * Used by YemenSyncWorker (24h), the launch one-time and
     * the Yemen tab's manual refresh. A Yemen failure never
     * touches any other clock (there is no other clock left).
     * The 300 s bound covers the whole pass; on timeout the
     * sections that already completed keep their transactions
     * and their success hashes, and the result is Partial —
     * never a half-marked success.
     */
    suspend fun syncYemen(forceRefresh: Boolean = false): SyncResult {
        return syncMutex.withLock {
            val base = edgeBase() ?: return@withLock SyncResult.NotConfigured
            val now = System.currentTimeMillis()
            markSyncStart(now)
            try {
                if (!isOnline()) {
                    recordAttempt("network")
                    SyncTrace.recordNetworkState(dao, false, "network")
                    trace("manifest", "network", -1, -1, "failed")
                    return@withLock SyncResult.Failed("network")
                }
                SyncTrace.recordNetworkState(dao, true, "")
                val outcome = httpGetRetried("$base/manifest.json")
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
                    trace("manifest", code, -1, -1, "failed")
                    return@withLock SyncResult.Failed(code)
                }
                trace("manifest", "ok", -1, -1, "ok")
                val res = withTimeoutOrNull(300_000L) {
                    syncYemenPass(base, manifest, forceRefresh, now)
                }
                if (res != null) return@withLock res
                recordAttempt("partial")
                trace("sync", "timeout", -1, -1, "partial")
                SyncResult.Partial(emptyList(), 0, yemenDao.placeCount(), emptyList())
            } finally {
                markSyncEnd()
            }
        }
    }

    private suspend fun syncYemenPass(
        base: String,
        manifest: EdgeManifest,
        forceRefresh: Boolean,
        now: Long,
    ): SyncResult {
        val inviteLists = fetchInviteLists(base)
        val yemenOutcome = try {
            syncYemenSection(base, manifest, forceRefresh, now)
        } catch (_: Exception) {
            try {
                recordSectionMeta(
                    SyncSections.YEMEN, SectionStates.FAILED, "section_error",
                    null, 0, now, null, null,
                )
            } catch (_: Exception) {
            }
            trace(SyncSections.YEMEN, "section_error", -1, -1, SectionStates.FAILED)
            recordV51(SyncSections.YEMEN, "section_error", -1, 0, -1)
            SectionOutcome(SyncSections.YEMEN, SectionStates.FAILED, 0)
        }
        // Invite re-check (M7 rule, unchanged): only a successfully
        // fetched list can re-lock, and cached rows are never deleted.
        if (inviteLists != null) {
            val storedHash = settings.settings.first().inviteHash
            if (storedHash != null &&
                !InviteGate.stillValid(storedHash, inviteLists.first, inviteLists.second)
            ) {
                settings.setInviteOk(false)
                trace("invite_gate", "invite_relock", -1, -1, "relocked")
            }
        }
        val total = yemenDao.placeCount()
        return when (yemenOutcome.state) {
            SectionStates.UP_TO_DATE -> SyncResult.Ok(emptyList(), total, listOf(yemenOutcome))
            SectionStates.PARTIAL -> SyncResult.Partial(
                emptyList(), yemenOutcome.missingCount, total, listOf(yemenOutcome),
            )
            SectionStates.FAILED -> SyncResult.Failed(
                dao.metaValue(SyncSections.errorKey(SyncSections.YEMEN)) ?: "network"
            )
            else -> SyncResult.Ok(emptyList(), total, listOf(yemenOutcome))
        }
    }

    /** Yemen meta for the tab header (staleness, error code). */
    suspend fun yemenMeta(): YemenMetaEntity? = yemenDao.meta()

    // Diagnostics snapshot for the single remaining section.
    // Hash values are never returned, only present/absent.
    suspend fun sectionStates(): List<SectionSyncState> {
        val key = SyncSections.YEMEN
        return listOf(
            SectionSyncState(
                section = key,
                state = dao.metaValue(SyncSections.stateKey(key)) ?: SectionStates.UNKNOWN,
                lastSyncAt = dao.metaValue(SyncSections.lastSyncKey(key))?.toLongOrNull(),
                lastAttemptAt = dao.metaValue(SyncSections.lastAttemptKey(key))?.toLongOrNull(),
                lastErrorCode = dao.metaValue(SyncSections.errorKey(key)) ?: "",
                itemCount = yemenDao.placeCount(),
                hashPresent = !dao.metaValue(SyncSections.hashKey(key)).isNullOrBlank(),
                missingCount = dao.metaValue(SyncSections.missingKey(key))?.toIntOrNull() ?: 0,
            )
        )
    }

    // ---- v5.2 Yemen section sync (audit §4.3) ----
    //
    // Manifest-gated single-file section: if sections.yemen.hash
    // equals the stored hash, zero fetch. Otherwise one fetch of
    // yemen.json, applied in ONE transaction; the hash is stored
    // only after that transaction succeeds. Control changes are
    // detected by comparing each place's faction with the stored
    // row before the replace. A failure never empties the cache
    // and records a fixed error code in yemen_meta + the section
    // meta, so the tab can show an honest staleness line.

    private suspend fun syncYemenSection(
        base: String,
        manifest: EdgeManifest,
        forceRefresh: Boolean,
        now: Long,
    ): SectionOutcome {
        val key = SyncSections.YEMEN
        val section = manifest.sections[key]
        if (section == null) {
            recordSectionMeta(key, SectionStates.UNKNOWN, "no_section", null, 0, now, null, null)
            trace(key, "no_section", -1, -1, SectionStates.UNKNOWN)
            recordV51(key, "no_section", -1, -1, -1)
            return SectionOutcome(key, SectionStates.UNKNOWN, 0)
        }
        val storedMeta = yemenDao.meta()
        val storedHash = storedMeta?.yemenHash
            ?: dao.metaValue(SyncSections.hashKey(key))
        val placeCount = yemenDao.placeCount()
        val hashUpToDate = DeltaPlanner.isUpToDate(storedHash, section.hash, forceRefresh) &&
            !(placeCount == 0 && section.count > 0)
        if (hashUpToDate) {
            recordSectionMeta(key, SectionStates.UP_TO_DATE, "", placeCount, 0, now, now, null)
            trace(key, "", 0, 0, SectionStates.UP_TO_DATE)
            recordV51(key, "", 0, 0, -1)
            return SectionOutcome(key, SectionStates.UP_TO_DATE, 0)
        }
        val file = section.file?.takeIf { it.isNotBlank() } ?: "yemen.json"
        val outcome = httpGetRetried("$base/$file", attempts = 3)
        val body = when (outcome) {
            is HttpOutcome.Ok -> outcome.body
            else -> {
                val code = outcome.toFailedCode()
                recordYemenFailure(code, now)
                recordSectionMeta(key, SectionStates.FAILED, code, null, 0, now, null, null)
                trace(key, code, -1, -1, SectionStates.FAILED)
                recordV51(key, code, -1, 0, -1)
                return SectionOutcome(key, SectionStates.FAILED, 0)
            }
        }
        val doc = YemenParsers.parseDoc(body)
        if (doc == null || doc.places.isEmpty()) {
            recordYemenFailure("bad_yemen", now)
            recordSectionMeta(key, SectionStates.FAILED, "bad_yemen", null, 0, now, null, null)
            trace(key, "bad_yemen", -1, -1, SectionStates.FAILED)
            recordV51(key, "bad_yemen", -1, 0, -1)
            return SectionOutcome(key, SectionStates.FAILED, 0)
        }
        // Control-change detection, before the replace.
        val oldFactions = yemenDao.places().associate { it.appKey to it.faction }
        var controlChanges = 0
        for (place in doc.places) {
            val old = oldFactions[place.appKey]
            if (old != null && old != place.faction) controlChanges++
        }
        db.withTransaction {
            yemenDao.deleteAllPlaces()
            yemenDao.deleteAllEvents()
            yemenDao.deleteAllFrontlines()
            yemenDao.upsertPlaces(doc.places.map { it.toEntity() })
            yemenDao.upsertEvents(doc.events.map { it.toEntity() })
            yemenDao.upsertFrontlines(doc.frontlines.map { it.toEntity() })
            yemenDao.upsertAden(doc.aden.toEntity())
            // The full document verbatim, for the supplementary
            // sections (arrows, units, forces, claims, human
            // stats, sources, map reading, snapshots) that have
            // no dedicated table — written in the SAME
            // transaction, so the doc and the tables can never
            // disagree about which dashboard state they show.
            dao.upsertMeta(MetaEntity("yemen_doc_json", body))
            yemenDao.upsertMeta(
                YemenMetaEntity(
                    key = "yemen",
                    yemenHash = section.hash,
                    yemenGeneratedAt = doc.generatedAtUtc,
                    yemenSeedHash = storedMeta?.yemenSeedHash,
                    lastYemenSyncAt = now,
                    lastYemenErrorCode = "",
                    controlChanges = controlChanges,
                ),
            )
        }
        recordSectionMeta(
            key, SectionStates.UP_TO_DATE, "", doc.places.size, 0, now, now, section.hash,
        )
        trace(key, "", doc.places.size, 0, SectionStates.UP_TO_DATE)
        recordV51(key, "", doc.places.size, 0, -1)
        return SectionOutcome(key, SectionStates.UP_TO_DATE, 0)
    }

    /** Record a Yemen failure without touching the stored hash or
     * the cached rows: the next run retries exactly this section. */
    private suspend fun recordYemenFailure(code: String, now: Long) {
        try {
            val old = yemenDao.meta()
            yemenDao.upsertMeta(
                YemenMetaEntity(
                    key = "yemen",
                    yemenHash = old?.yemenHash,
                    yemenGeneratedAt = old?.yemenGeneratedAt,
                    yemenSeedHash = old?.yemenSeedHash,
                    lastYemenSyncAt = old?.lastYemenSyncAt,
                    lastYemenErrorCode = code,
                    controlChanges = old?.controlChanges ?: 0,
                ),
            )
            dao.upsertMeta(MetaEntity("last_yemen_attempt_at", now.toString()))
        } catch (_: Exception) {
        }
    }

    private suspend fun fetchInviteLists(base: String): Pair<List<String>, List<String>>? {
        suspend fun hashes(url: String): List<String>? {
            // v5.1: 2 attempts, network errors only. A failure
            // still yields null and the re-check is skipped —
            // offline must never lock a reader out.
            val text = when (val outcome = httpGetRetried(url, attempts = 2)) {
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
}
