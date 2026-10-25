package com.ecojournal.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One cached article. bodyBlocks is stored via StringListConverter.
 *
 * Identity (M1): [id] is the verbatim source id, kept exactly as the
 * edge ships it and never used in a navigation route. [appKey] is the
 * URL-safe surrogate used by routes, bookmarks and notifications.
 * [publishedAtEpoch] is the single sort key (M2): the raw
 * [publishedAt] string is kept for display/debugging only and is
 * never sorted on. [contentHash] lets sync tell an updated body
 * from an unchanged one without refetching by title. [status] is
 * "approved", "posted" or "retracted"; a retracted row stays in the
 * table as a tombstone so a bookmark on it does not silently vanish.
 */
@Entity(
    tableName = "articles",
    indices = [
        Index(value = ["appKey"], unique = true),
        Index(value = ["category"]),
        Index(value = ["publishedAtEpoch"]),
    ],
)
data class ArticleEntity(
    @PrimaryKey val id: String,
    val appKey: String,
    val kind: String,
    val title: String,
    val summary: String,
    val bodyBlocks: List<String>,
    val pointsJson: String,
    val whyMatters: String?,
    val category: String,
    val categoryFa: String,
    val source: String,
    val originalUrl: String?,
    val publishedAt: String?,
    val publishedAtEpoch: Long?,
    val contentHash: String?,
    val updatedAtEpoch: Long,
    val status: String,
    val retractedAtEpoch: Long?,
    val channelMessageId: Long?,
    val cachedAt: Long,
)

/**
 * Bookmarks are keyed by appKey (M1), not by the verbatim source id,
 * so a bookmark survives exactly the same identity a route uses.
 */
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val appKey: String,
    val savedAt: Long,
)

@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

// Phase 1 join-key mapping (guid <-> appKey <-> story_id), carried
// from the edge join_keys.json through sync. The guid is the source
// guid (data_core item_guid for articles, the draft id for shorts);
// story_id is "story-" + first 20 hex of SHA-256(guid), derived on
// the edge side and never from a headline. channelMessageId is null
// for every entry today and becomes non-null only when the bridge
// staging path (Phase 3) carries a real channel post through. A
// missing guid is stored as null, never invented from the appKey.
@Entity(tableName = "join_keys")
data class JoinKeyEntity(
    @PrimaryKey val appKey: String,
    val guid: String?,
    val storyId: String?,
    val channelMessageId: Long?,
)

/**
 * Phase 2 typed channel item (spec section 2.2). One row per typed
 * edge item. JSON sub-objects (prices, SITREP sections, watch-next)
 * are stored as raw JSON strings and parsed at render time, so a
 * malformed sub-object degrades one block, never the whole row.
 *
 * Night-law fields are written ONCE at first sync and never
 * recomputed (spec 5.2): [nightLawResult] is "instant" / "held" /
 * "feed_only"; [heldUntilEpoch] is the 08:00 Tehran release instant
 * for held items; [heldReleased] flips true on the first successful
 * sync at/after that instant; [wasHeld] stays true forever so the
 * Morning Bundle can tag the item «از شب گذشته». [notified]
 * dedups urgent notifications across re-syncs. A retracted row is
 * a tombstone (status = "retracted") and is excluded from every
 * list query below, exactly like article tombstones.
 */
@Entity(
    tableName = "channel_items",
    indices = [
        Index(value = ["storyId"]),
        Index(value = ["formatType"]),
        Index(value = ["publishedAtEpoch"]),
        Index(value = ["bundleId"]),
    ],
)
data class ChannelItemEntity(
    @PrimaryKey val appKey: String,
    val guid: String?,
    val storyId: String?,
    val channelMessageId: Long?,
    val formatType: String,
    val urgencyScore: Int?,
    val crisisLevel: String?,
    val headline: String,
    val body: String,
    val publishedAtEpoch: Long,
    val slot: String?,
    val bundleId: String?,
    val bundlePosition: Int?,
    val bundleTotal: Int?,
    val sourceName: String,
    val sourceLink: String?,
    val attribution: String?,
    val verdictStatus: String?,
    val verdictText: String?,
    val pricesJson: String?,
    val sitrepSectionsJson: String?,
    val watchNextJson: String?,
    val status: String,
    val contentHash: String?,
    val updatedAtEpoch: Long,
    val nightLawResult: String,
    val heldUntilEpoch: Long?,
    val heldReleased: Boolean,
    val wasHeld: Boolean,
    val notified: Boolean,
    val cachedAt: Long,
)

/** Phase 2 bundle header (spec section 2.3). */
@Entity(tableName = "bundles")
data class BundleEntity(
    @PrimaryKey val bundleId: String,
    val kind: String,
    val title: String,
    val memberAppKeys: List<String>,
    val bundleDateShamsi: String,
    val status: String,
    val contentHash: String?,
    val updatedAtEpoch: Long,
)

/** Phase 2 radar signal (spec section 2.4), extended in Phase 4
 * with the graded-signal fields (phases4-5 spec §4.5). Phase 4
 * fields default so Phase 2 rows keep compiling and keep their
 * cache: [severity] now carries the graded value high / medium /
 * low when the edge ships it (Phase 2 rows carry the opaque
 * shipped string or null); [storyId] is the guid-derived join key
 * (a signal without a resolvable story_id is held by the parser,
 * never published as an orphan); [linkedAppKey] is the story's
 * app article/short key when published in-app, null while
 * unpublished (row lists with the honest "not yet in app"
 * marker, never a dead link); [matchedTopics] are topic labels,
 * never the raw keyword list verbatim. [layer]/[severityRank]
 * are kept for Phase 2 fixture compatibility and are ignored by
 * the Phase 4 graded UI when a grade is present. */
@Entity(
    tableName = "radar_signals",
    indices = [Index(value = ["layer"]), Index(value = ["detectedAtEpoch"])],
)
data class RadarSignalEntity(
    @PrimaryKey val signalId: String,
    val title: String,
    val layer: Int,
    val severity: String?,
    val severityRank: Int?,
    val sourceName: String,
    val sourceLink: String?,
    val linkedStoryId: String?,
    val detectedAtEpoch: Long,
    val status: String,
    val contentHash: String?,
    val updatedAtEpoch: Long,
    val storyId: String? = null,
    val matchedTopics: List<String> = emptyList(),
    val firstSeenTehran: String? = null,
    val lastSeenTehran: String? = null,
    val linkedAppKey: String? = null,
)

/**
 * Phase 4 market alert event (spec §4.1). One row per metric per
 * snapshot at most (dedup by eventId = "bubble|snapshotId" /
 * "spread|snapshotId"). [notified] is 1 ONLY when a notification
 * was actually posted (spec §4.6: the channel code records the
 * cooldown BEFORE attempting the post, so a failed post still
 * consumes it — the app must not copy that defect). Banner
 * dismissal is per latest snapshot, stored in meta, not here.
 */
@Entity(
    tableName = "market_alert_events",
    indices = [Index(value = ["metric"]), Index(value = ["snapshotId"])],
)
data class MarketAlertEventEntity(
    @PrimaryKey val eventId: String,
    val metric: String,
    val snapshotId: String,
    val valuePct: Double,
    val thresholdPct: Double,
    val firedAtEpoch: Long,
    val notified: Int,
)

/**
 * Phase 4 notification history (spec §4.1). LOCAL ONLY: never
 * synced, never in copy-diagnostics beyond its own shown lines,
 * pruned to the newest 200 rows or 90 days, whichever is smaller.
 * [targetKey] is an appKey / snapshotId / edition_id / bundle_id
 * (nullable); [suppressedReason] is null for fired rows and one
 * of quiet_hours / night_law / toggle_off / cooldown / master_off
 * for suppressed rows (first failing check, spec §4.7).
 */
@Entity(
    tableName = "notification_history",
    indices = [Index(value = ["firedAtEpoch"]), Index(value = ["type"])],
)
data class NotificationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val title: String,
    val targetKey: String?,
    val firedAtEpoch: Long,
    val deferredFromEpoch: Long?,
    val suppressedReason: String?,
)

/**
 * Phase 5 nightly editions (spec §5.12). One row per edition_id +
 * version; [isLatest] flags the displayed version. The app keeps
 * only the latest version's full component JSON; older version
 * files stay on the edge as the audit trail (see Nightly.kt).
 * Component JSON is stored verbatim and parsed at render time,
 * so a malformed sub-object degrades one component, never the
 * whole edition. [bookmarked] pins the edition against the
 * 30-edition prune (spec §5.13): a bookmarked edition is never
 * pruned, and its items survive with it (orphan discipline, E5).
 * Market-close alert history inside [marketCloseJson] is the
 * Phase 4 market_alert_events rows as shipped in the edition —
 * the app NEVER re-derives alert history from snapshots here
 * (spec §5.9 / Phase 4 report §7.3).
 */
@Entity(
    tableName = "nightly_editions",
    primaryKeys = ["editionId", "version"],
    indices = [Index(value = ["tehranDate"]), Index(value = ["isLatest"])],
)
data class NightlyEditionEntity(
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
    val isLatest: Boolean,
    val bookmarked: Boolean,
    val cachedAt: Long,
)

/** Phase 5 nightly edition article, keyed by edition_key
 * (edition_id + story_id, spec §5.5). One story = one row per
 * edition; the same story in another edition is another row. */
@Entity(
    tableName = "nightly_items",
    indices = [Index(value = ["editionId"]), Index(value = ["storyId"])],
)
data class NightlyItemEntity(
    @PrimaryKey val editionKey: String,
    val editionId: String,
    val version: Int,
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

/** Phase 5 dossier page (spec §5.8): ONE living page per topic
 * per Shamsi week. The dossier LIVES (it updates as its topic
 * develops); past editions do NOT change when it updates — an
 * edition links to the dossier as it stood at cut time, and the
 * reader sees a "updated since this edition" line when it has
 * moved on. Duplicate = shared canon story family, NEVER mere
 * keyword overlap. */
@Entity(tableName = "dossiers")
data class DossierEntity(
    @PrimaryKey val dossierId: String,
    val topicTitleFa: String,
    val storyIdsJson: String,
    val firstSeenTehran: String,
    val lastUpdatedTehran: String,
    val summary: String,
    val editionIdsJson: String,
)

/** Bundle bookmarks (spec 8.3): a bundle header bookmark is
 * independent of member item bookmarks, like article bookmarks. */
@Entity(tableName = "bundle_bookmarks")
data class BundleBookmarkEntity(
    @PrimaryKey val bundleId: String,
    val savedAt: Long,
)

/**
 * Active market formulas as shipped by the edge/bundled market.json
 * (M4). The definition is the shipped S-expression string, stored
 * verbatim; MarketFormulas evaluates it at display time, so a
 * definition change propagates without an app update. Seeded from
 * the bundled asset (so formulas work offline) and replaced by the
 * edge sync's formulas_active in the same manifest-gated pass.
 */
@Entity(tableName = "market_formulas")
data class MarketFormulaEntity(
    @PrimaryKey val name: String,
    val version: Int,
    val definition: String,
    val inputs: String?,
    val descriptionFa: String?,
)

@Entity(
    tableName = "market_snapshots",
    indices = [Index(value = ["tsEpoch"])],
)
data class MarketSnapshotEntity(
    @PrimaryKey val snapshotId: String,
    val tsTehran: String,
    val tsEpoch: Long?,
    val brent: Double?,
    val xauUsd: Double?,
    val dxy: Double?,
    val usdtUsd: Double?,
    val usdIrtFree: Double?,
    val usdtIrt: Double?,
    val gold18kGram: Double?,
    val sekkeEmami: Double?,
    val sekkeBahar: Double?,
    val nimSekke: Double?,
    val robSekke: Double?,
    val vix: Double?,
    val sp500: Double?,
)

class StringListConverter {
    @TypeConverter
    fun fromList(value: List<String>): String =
        value.joinToString("\u0001") { it.replace("\u0001", " ") }

    @TypeConverter
    fun toList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split("\u0001")
}

@Dao
interface ArticleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(articles: List<ArticleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: MetaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMarket(snapshots: List<MarketSnapshotEntity>)

    @Query("SELECT value FROM meta WHERE `key` = :key LIMIT 1")
    suspend fun metaValue(key: String): String?

    /** Reactive meta read (M5): the feed shows last-sync time live. */
    @Query("SELECT value FROM meta WHERE `key` = :key LIMIT 1")
    fun metaValueFlow(key: String): Flow<String?>

    @Query("SELECT value FROM meta WHERE `key` = 'index_hash'")
    suspend fun indexHash(): String?

    /**
     * The feed source of truth (M2). Retracted rows are excluded
     * here; they are tombstones, not feed items. Ordering is by the
     * normalised epoch, never by the raw date string. Rows with no
     * parseable date sort last, because SQLite treats NULL as the
     * smallest value and DESC therefore puts it last.
     */
    @Query("SELECT * FROM articles WHERE status != 'retracted' ORDER BY publishedAtEpoch DESC, publishedAt DESC")
    fun allFlow(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE status != 'retracted' ORDER BY publishedAtEpoch DESC, publishedAt DESC")
    suspend fun all(): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ArticleEntity?

    @Query("SELECT * FROM articles WHERE appKey = :appKey LIMIT 1")
    suspend fun byAppKey(appKey: String): ArticleEntity?

    // M6: byCategory() deleted with CategoriesScreen.kt. It had zero
    // callers (grep verified); section filtering happens in the feed
    // from dao.allFlow(), not from a per-category query.

    /**
     * Offline search (M3, P1-04). Searches titles, summaries, body
     * text (stored as a unit-separated string), key points, source
     * and category label — not titles and summaries only. Callers
     * MUST pass an already-escaped query (see
     * util.escapeLikeQuery): '%' and '_' in a typed query are
     * literals, not wildcards. ESCAPE '\' makes that binding stick.
     */
    @Query(
        "SELECT * FROM articles WHERE (title LIKE '%' || :q || '%' ESCAPE '\\' " +
            "OR summary LIKE '%' || :q || '%' ESCAPE '\\' " +
            "OR bodyBlocks LIKE '%' || :q || '%' ESCAPE '\\' " +
            "OR pointsJson LIKE '%' || :q || '%' ESCAPE '\\' " +
            "OR source LIKE '%' || :q || '%' ESCAPE '\\' " +
            "OR categoryFa LIKE '%' || :q || '%' ESCAPE '\\') " +
            "AND status != 'retracted' " +
            "ORDER BY publishedAtEpoch DESC, publishedAt DESC"
    )
    suspend fun search(q: String): List<ArticleEntity>

    /**
     * Bookmarks are NOT filtered by status: a retracted bookmark
     * must stay visible (flagged by its status in a later milestone)
     * instead of silently disappearing.
     */
    @Query("SELECT * FROM articles WHERE appKey IN (SELECT appKey FROM bookmarks) ORDER BY publishedAtEpoch DESC, publishedAt DESC")
    fun bookmarkedFlow(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE appKey IN (SELECT appKey FROM bookmarks) ORDER BY publishedAtEpoch DESC, publishedAt DESC")
    suspend fun bookmarked(): List<ArticleEntity>

    /**
     * Orphan bookmarks (M3): bookmark rows whose article is gone
     * from the cache. The old bookmark list silently dropped these
     * (the join simply had no row); the M3 list shows them as
     * "no longer available" rows with a prune action instead.
     */
    @Query("SELECT * FROM bookmarks WHERE appKey NOT IN (SELECT appKey FROM articles) ORDER BY savedAt DESC")
    fun orphanBookmarksFlow(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE appKey = :appKey")
    suspend fun removeBookmark(appKey: String)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE appKey = :appKey)")
    suspend fun isBookmarked(appKey: String): Boolean

    // M8 diagnostics: query-only counts, no schema change, so no
    // migration is needed for these two methods.
    @Query("SELECT COUNT(*) FROM articles WHERE status != 'retracted'")
    fun articleCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM bookmarks")
    fun bookmarkCountFlow(): Flow<Int>

    @Query("SELECT id FROM articles")
    suspend fun allIds(): List<String>

    @Query("SELECT appKey FROM articles")
    suspend fun allAppKeys(): List<String>

    /**
     * Phase 4 note on the null-tsEpoch rule (spec §4.3): a row
     * whose tsTehran cannot be parsed has tsEpoch = null and must
     * NEVER become "latest". SQLite ORDER BY ... DESC already puts
     * NULLs last (verified in the lab against SQLite itself), but
     * these queries now also filter `tsEpoch IS NOT NULL`
     * explicitly, so the rule holds regardless of engine nuance
     * and the Phase 4 fixture test proves it literally.
     */
    @Query("SELECT * FROM market_snapshots WHERE tsEpoch IS NOT NULL ORDER BY tsEpoch DESC LIMIT 1")
    fun marketLatestFlow(): Flow<MarketSnapshotEntity?>

    @Query("SELECT * FROM market_snapshots WHERE tsEpoch IS NOT NULL ORDER BY tsEpoch DESC LIMIT 1")
    suspend fun marketLatest(): MarketSnapshotEntity?

    @Query("SELECT COUNT(*) FROM market_snapshots")
    suspend fun marketCount(): Int

    @Query("SELECT COUNT(*) FROM market_snapshots")
    fun marketCountFlow(): Flow<Int>

    /** Phase 4 history: every orderable snapshot, oldest first. */
    @Query("SELECT * FROM market_snapshots WHERE tsEpoch IS NOT NULL ORDER BY tsEpoch ASC")
    fun snapshotsOrderedFlow(): Flow<List<MarketSnapshotEntity>>

    @Query("SELECT * FROM market_snapshots WHERE tsEpoch IS NOT NULL ORDER BY tsEpoch ASC")
    suspend fun snapshotsOrdered(): List<MarketSnapshotEntity>

    /**
     * The snapshot before the latest one: greatest timestamp
     * strictly below [latestEpoch]. Used for change-vs-previous.
     * A null latest epoch (no parseable timestamp at all) yields
     * no previous, never a fabricated comparison.
     */
    @Query(
        "SELECT * FROM market_snapshots WHERE tsEpoch IS NOT NULL AND tsEpoch < :latestEpoch " +
            "ORDER BY tsEpoch DESC LIMIT 1"
    )
    suspend fun marketPrevious(latestEpoch: Long?): MarketSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFormulas(formulas: List<MarketFormulaEntity>)

    @Query("DELETE FROM market_formulas")
    suspend fun deleteAllFormulas()

    @Query("SELECT COUNT(*) FROM market_formulas")
    suspend fun formulaCount(): Int

    @Query("SELECT * FROM market_formulas ORDER BY name")
    fun formulasFlow(): Flow<List<MarketFormulaEntity>>

    @Query("SELECT * FROM market_formulas ORDER BY name")
    suspend fun formulas(): List<MarketFormulaEntity>

    // Phase 1: join-key mapping and kind-specific counts for the
    // per-section Diagnostics view. Counts exclude retracted rows,
    // matching the feed's tombstone rule.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJoinKeys(rows: List<JoinKeyEntity>)

    @Query("SELECT COUNT(*) FROM join_keys")
    suspend fun joinKeyCount(): Int

    @Query("SELECT * FROM join_keys WHERE appKey = :appKey LIMIT 1")
    suspend fun joinKeyByAppKey(appKey: String): JoinKeyEntity?

    @Query("SELECT COUNT(*) FROM articles WHERE kind = :kind AND status != 'retracted'")
    suspend fun articleKindCount(kind: String): Int
}

@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannelItems(items: List<ChannelItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBundles(bundles: List<BundleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRadarSignals(signals: List<RadarSignalEntity>)

    /** Visible feed items: posted, and not (held and unreleased). */
    @Query(
        "SELECT * FROM channel_items WHERE status != 'retracted' " +
            "AND NOT (nightLawResult = 'held' AND heldReleased = 0) " +
            "ORDER BY publishedAtEpoch DESC, appKey ASC"
    )
    fun visibleChannelFlow(): Flow<List<ChannelItemEntity>>

    @Query(
        "SELECT * FROM channel_items WHERE status != 'retracted' " +
            "AND NOT (nightLawResult = 'held' AND heldReleased = 0) " +
            "ORDER BY publishedAtEpoch DESC, appKey ASC"
    )
    suspend fun visibleChannel(): List<ChannelItemEntity>

    @Query("SELECT * FROM channel_items ORDER BY publishedAtEpoch DESC, appKey ASC")
    suspend fun allChannelItems(): List<ChannelItemEntity>

    @Query("SELECT * FROM channel_items WHERE appKey = :appKey LIMIT 1")
    suspend fun channelByAppKey(appKey: String): ChannelItemEntity?

    @Query(
        "SELECT * FROM channel_items WHERE storyId = :storyId " +
            "AND status != 'retracted' ORDER BY publishedAtEpoch ASC, appKey ASC"
    )
    suspend fun channelByStory(storyId: String): List<ChannelItemEntity>

    /** Instant items for the urgent rail (spec 5.3). */
    @Query(
        "SELECT * FROM channel_items WHERE status != 'retracted' " +
            "AND nightLawResult = 'instant' ORDER BY publishedAtEpoch DESC, appKey ASC"
    )
    fun instantChannelFlow(): Flow<List<ChannelItemEntity>>

    @Query(
        "SELECT * FROM channel_items WHERE status != 'retracted' " +
            "AND nightLawResult = 'held' AND heldReleased = 0"
    )
    suspend fun heldChannelItems(): List<ChannelItemEntity>

    @Query("SELECT COUNT(*) FROM channel_items WHERE status != 'retracted' AND nightLawResult = 'held' AND heldReleased = 0")
    fun heldCountFlow(): Flow<Int>

    @Query(
        "SELECT * FROM channel_items WHERE status != 'retracted' " +
            "AND nightLawResult != 'held' " +
            "AND (headline LIKE '%' || :q || '%' ESCAPE '\\' " +
            "OR body LIKE '%' || :q || '%' ESCAPE '\\' " +
            "OR sourceName LIKE '%' || :q || '%' ESCAPE '\\') " +
            "ORDER BY publishedAtEpoch DESC, appKey ASC"
    )
    suspend fun searchChannel(q: String): List<ChannelItemEntity>

    @Query(
        "SELECT * FROM channel_items WHERE status != 'retracted' " +
            "AND nightLawResult = 'instant' AND notified = 0 " +
            "ORDER BY publishedAtEpoch DESC, appKey ASC"
    )
    suspend fun unnotifiedInstant(): List<ChannelItemEntity>

    @Query("UPDATE channel_items SET notified = 1 WHERE appKey = :appKey")
    suspend fun markNotified(appKey: String)

    @Query(
        "UPDATE channel_items SET heldReleased = 1 WHERE nightLawResult = 'held' " +
            "AND heldReleased = 0 AND heldUntilEpoch IS NOT NULL AND heldUntilEpoch <= :now"
    )
    suspend fun releaseHeld(now: Long)

    @Query("SELECT COUNT(*) FROM channel_items WHERE status != 'retracted'")
    suspend fun channelCount(): Int

    @Query("SELECT COUNT(*) FROM channel_items WHERE status != 'retracted' AND formatType = :format")
    suspend fun channelFormatCount(format: String): Int

    @Query(
        "SELECT * FROM bundles WHERE status != 'retracted' " +
            "ORDER BY bundleDateShamsi DESC, title ASC"
    )
    fun bundlesFlow(): Flow<List<BundleEntity>>

    @Query("SELECT * FROM bundles WHERE bundleId = :bundleId LIMIT 1")
    suspend fun bundleById(bundleId: String): BundleEntity?

    @Query("SELECT * FROM bundles WHERE status != 'retracted' ORDER BY bundleDateShamsi DESC, title ASC")
    suspend fun allBundles(): List<BundleEntity>

    @Query(
        "SELECT * FROM radar_signals WHERE status != 'retracted' " +
            "ORDER BY layer ASC, detectedAtEpoch DESC, signalId ASC"
    )
    fun radarFlow(): Flow<List<RadarSignalEntity>>

    @Query("SELECT * FROM radar_signals WHERE status != 'retracted' ORDER BY detectedAtEpoch DESC")
    suspend fun allRadar(): List<RadarSignalEntity>

    @Query("SELECT COUNT(*) FROM radar_signals WHERE status != 'retracted'")
    suspend fun radarCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBundleBookmark(bookmark: BundleBookmarkEntity)

    @Query("DELETE FROM bundle_bookmarks WHERE bundleId = :bundleId")
    suspend fun removeBundleBookmark(bundleId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM bundle_bookmarks WHERE bundleId = :bundleId)")
    suspend fun isBundleBookmarked(bundleId: String): Boolean

    @Query("SELECT * FROM bundle_bookmarks ORDER BY savedAt DESC")
    suspend fun bundleBookmarks(): List<BundleBookmarkEntity>

    @Query(
        "SELECT * FROM channel_items WHERE appKey IN (SELECT appKey FROM bookmarks) " +
            "AND status != 'retracted' ORDER BY publishedAtEpoch DESC, appKey ASC"
    )
    suspend fun bookmarkedChannelItems(): List<ChannelItemEntity>
}

@Dao
interface Phase4Dao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlertEvent(event: MarketAlertEventEntity)

    @Query("SELECT * FROM market_alert_events WHERE metric = :metric ORDER BY firedAtEpoch DESC LIMIT 1")
    suspend fun latestAlertEvent(metric: String): MarketAlertEventEntity?

    @Query("SELECT * FROM market_alert_events ORDER BY firedAtEpoch DESC")
    suspend fun allAlertEvents(): List<MarketAlertEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotificationHistory(row: NotificationHistoryEntity)

    @Query("SELECT * FROM notification_history ORDER BY firedAtEpoch DESC LIMIT 200")
    suspend fun recentNotificationHistory(): List<NotificationHistoryEntity>

    /** Prune to newest 200 rows (spec §4.1: 200 rows or 90 days, whichever is smaller). */
    @Query(
        "DELETE FROM notification_history WHERE id NOT IN " +
            "(SELECT id FROM notification_history ORDER BY firedAtEpoch DESC LIMIT 200)"
    )
    suspend fun pruneNotificationHistoryTo200()

    @Query("DELETE FROM notification_history WHERE firedAtEpoch < :olderThanEpoch")
    suspend fun pruneNotificationHistoryOlderThan(olderThanEpoch: Long)

    @Query("SELECT COUNT(*) FROM notification_history WHERE suppressedReason IS NULL AND firedAtEpoch >= :fromEpoch")
    suspend fun firedCountSince(fromEpoch: Long): Int

    @Query(
        "SELECT type, COUNT(*) AS count FROM notification_history " +
            "WHERE suppressedReason IS NULL AND firedAtEpoch >= :fromEpoch GROUP BY type"
    )
    suspend fun firedCountsByTypeSince(fromEpoch: Long): List<NotificationTypeCount>

    @Query(
        "SELECT suppressedReason, COUNT(*) AS count FROM notification_history " +
            "WHERE suppressedReason IS NOT NULL AND firedAtEpoch >= :fromEpoch GROUP BY suppressedReason"
    )
    suspend fun suppressedCountsByReasonSince(fromEpoch: Long): List<NotificationReasonCount>
}

@Dao
interface NightlyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEdition(edition: NightlyEditionEntity)

    @Query("UPDATE nightly_editions SET isLatest = 0 WHERE editionId = :editionId AND version != :version")
    suspend fun demoteOlderVersions(editionId: String, version: Int)

    @Query("SELECT * FROM nightly_editions WHERE isLatest = 1 AND status != 'retracted' ORDER BY tehranDate DESC, editionId DESC")
    fun latestEditionsFlow(): Flow<List<NightlyEditionEntity>>

    @Query("SELECT * FROM nightly_editions WHERE editionId = :editionId AND isLatest = 1 LIMIT 1")
    suspend fun latestEditionById(editionId: String): NightlyEditionEntity?

    /** Retraction tombstone: the edition leaves every list, its
     * items are dropped, but the row (and its bookmark flag)
     * survives — the E5 orphan discipline, applied to editions. */
    @Query("UPDATE nightly_editions SET status = 'retracted' WHERE editionId = :editionId")
    suspend fun retractEdition(editionId: String)

    @Query("SELECT * FROM nightly_editions WHERE editionId = :editionId ORDER BY version DESC LIMIT 1")
    suspend fun anyEditionById(editionId: String): NightlyEditionEntity?

    @Query("SELECT COUNT(*) FROM nightly_editions WHERE isLatest = 1")
    suspend fun latestEditionCount(): Int

    @Query("UPDATE nightly_editions SET bookmarked = :bookmarked WHERE editionId = :editionId AND isLatest = 1")
    suspend fun setEditionBookmarked(editionId: String, bookmarked: Boolean)

    @Query("DELETE FROM nightly_editions WHERE editionId = :editionId")
    suspend fun deleteEdition(editionId: String)

    /** Prune to the newest 30 non-bookmarked latest editions;
     * bookmarked editions and their items always survive. */
    @Query(
        "SELECT editionId FROM nightly_editions WHERE isLatest = 1 AND bookmarked = 0 " +
            "ORDER BY tehranDate DESC, editionId DESC LIMIT -1 OFFSET 30"
    )
    suspend fun prunableEditionIds(): List<String>

    @Query("DELETE FROM nightly_items WHERE editionId = :editionId")
    suspend fun deleteItemsForEdition(editionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<NightlyItemEntity>)

    @Query("SELECT * FROM nightly_items WHERE editionId = :editionId ORDER BY kind DESC, title ASC")
    suspend fun itemsForEdition(editionId: String): List<NightlyItemEntity>

    @Query("SELECT * FROM nightly_items WHERE editionId = :editionId AND editionKey = :editionKey LIMIT 1")
    suspend fun itemByKey(editionId: String, editionKey: String): NightlyItemEntity?

    /** Offline edition search (spec §5.13): title/body/source,
     * optionally scoped to one edition_id facet. */
    @Query(
        "SELECT * FROM nightly_items WHERE (title LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%' " +
            "OR sourceName LIKE '%' || :query || '%') AND (:editionId IS NULL OR editionId = :editionId) " +
            "ORDER BY editionId DESC, title ASC"
    )
    suspend fun searchItems(query: String, editionId: String?): List<NightlyItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDossier(dossier: DossierEntity)

    @Query("SELECT * FROM dossiers ORDER BY lastUpdatedTehran DESC, dossierId ASC")
    fun dossiersFlow(): Flow<List<DossierEntity>>

    @Query("SELECT * FROM dossiers WHERE dossierId = :dossierId LIMIT 1")
    suspend fun dossierById(dossierId: String): DossierEntity?
}

/** GROUP BY result holder for notification_history counts by type. */
data class NotificationTypeCount(
    val type: String,
    val count: Int,
)

/** GROUP BY result holder for notification_history counts by reason. */
data class NotificationReasonCount(
    val suppressedReason: String,
    val count: Int,
)

// Version 6 -> 7 migration (Phase 5). Additive only: three new
// tables (nightly_editions, nightly_items, dossiers). No existing
// table is altered, so a v6 install keeps its full cache.
// NOTE on the spec text: it says "Room v5 → v6" because it was
// written against the Phase 1 baseline; Phase 2 moved the
// database to v5 and Phase 4 to v6, so Phase 5 is v6 → v7 — the
// tables are exactly the spec's tables, only the version number
// differs (same deviation pattern as Phase 4, recorded here).
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `nightly_editions` (" +
                "`editionId` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                "`shamsiDate` TEXT NOT NULL, `tehranDate` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `generatedAtTehran` TEXT NOT NULL, " +
                "`publishedAtTehran` TEXT, `cutTehran` TEXT NOT NULL, " +
                "`contentHash` TEXT, `articlesState` TEXT NOT NULL, " +
                "`articlesJson` TEXT NOT NULL, `marketCloseState` TEXT NOT NULL, " +
                "`marketCloseJson` TEXT NOT NULL, `digestsState` TEXT NOT NULL, " +
                "`digestsJson` TEXT NOT NULL, `analysisState` TEXT NOT NULL, " +
                "`analysisJson` TEXT NOT NULL, `specialIssuesState` TEXT NOT NULL, " +
                "`correctionsState` TEXT NOT NULL, `correctionsJson` TEXT NOT NULL, " +
                "`dossierLinksJson` TEXT NOT NULL, `isLatest` INTEGER NOT NULL, " +
                "`bookmarked` INTEGER NOT NULL, `cachedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`editionId`, `version`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_nightly_editions_tehranDate` ON `nightly_editions` (`tehranDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_nightly_editions_isLatest` ON `nightly_editions` (`isLatest`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `nightly_items` (" +
                "`editionKey` TEXT NOT NULL, `editionId` TEXT NOT NULL, " +
                "`version` INTEGER NOT NULL, `storyId` TEXT NOT NULL, " +
                "`appKey` TEXT, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`body` TEXT NOT NULL, `wordCount` INTEGER NOT NULL, " +
                "`freshness` REAL, `sourceName` TEXT NOT NULL, `sourceUrl` TEXT, " +
                "`verbatimBadge` TEXT, `formatType` TEXT, `crisisLevel` TEXT, " +
                "`category` TEXT, `categoryFa` TEXT, `slot` TEXT, `bundleId` TEXT, " +
                "`position` INTEGER, PRIMARY KEY(`editionKey`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_nightly_items_editionId` ON `nightly_items` (`editionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_nightly_items_storyId` ON `nightly_items` (`storyId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `dossiers` (" +
                "`dossierId` TEXT NOT NULL, `topicTitleFa` TEXT NOT NULL, " +
                "`storyIdsJson` TEXT NOT NULL, `firstSeenTehran` TEXT NOT NULL, " +
                "`lastUpdatedTehran` TEXT NOT NULL, `summary` TEXT NOT NULL, " +
                "`editionIdsJson` TEXT NOT NULL, PRIMARY KEY(`dossierId`))"
        )
    }
}

// Version 5 -> 6 migration (Phase 4). Additive only: two new
// tables + five new nullable/defaulted columns on radar_signals.
// No existing table is altered in shape otherwise, so a v5 install
// keeps its full cache. NOTE on the spec text: it says "Room v4 →
// v5" because it was written against the Phase 1 baseline; Phase 2
// already moved the database to v5, so Phase 4 is v5 → v6 — the
// tables are exactly the spec's tables, only the version number
// differs. A v4 install cannot jump here without MIGRATION_4_5,
// which stays in the chain.
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `market_alert_events` (" +
                "`eventId` TEXT NOT NULL, `metric` TEXT NOT NULL, " +
                "`snapshotId` TEXT NOT NULL, `valuePct` REAL NOT NULL, " +
                "`thresholdPct` REAL NOT NULL, `firedAtEpoch` INTEGER NOT NULL, " +
                "`notified` INTEGER NOT NULL, PRIMARY KEY(`eventId`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_market_alert_events_metric` ON `market_alert_events` (`metric`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_market_alert_events_snapshotId` ON `market_alert_events` (`snapshotId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `notification_history` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`type` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`targetKey` TEXT, `firedAtEpoch` INTEGER NOT NULL, " +
                "`deferredFromEpoch` INTEGER, `suppressedReason` TEXT)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_history_firedAtEpoch` ON `notification_history` (`firedAtEpoch`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_history_type` ON `notification_history` (`type`)")
        db.execSQL("ALTER TABLE `radar_signals` ADD COLUMN `storyId` TEXT")
        db.execSQL("ALTER TABLE `radar_signals` ADD COLUMN `matchedTopics` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `radar_signals` ADD COLUMN `firstSeenTehran` TEXT")
        db.execSQL("ALTER TABLE `radar_signals` ADD COLUMN `lastSeenTehran` TEXT")
        db.execSQL("ALTER TABLE `radar_signals` ADD COLUMN `linkedAppKey` TEXT")
    }
}

// Version 4 -> 5 migration (Phase 2). Additive only: three new
// tables + bundle bookmarks. No existing table is altered, so a
// v4 install keeps its full cache; typed sections backfill on the
// next v3 sync that publishes them.
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `channel_items` (" +
                "`appKey` TEXT NOT NULL, `guid` TEXT, `storyId` TEXT, " +
                "`channelMessageId` INTEGER, `formatType` TEXT NOT NULL, " +
                "`urgencyScore` INTEGER, `crisisLevel` TEXT, " +
                "`headline` TEXT NOT NULL, `body` TEXT NOT NULL, " +
                "`publishedAtEpoch` INTEGER NOT NULL, `slot` TEXT, " +
                "`bundleId` TEXT, `bundlePosition` INTEGER, " +
                "`bundleTotal` INTEGER, `sourceName` TEXT NOT NULL, " +
                "`sourceLink` TEXT, `attribution` TEXT, " +
                "`verdictStatus` TEXT, `verdictText` TEXT, " +
                "`pricesJson` TEXT, `sitrepSectionsJson` TEXT, " +
                "`watchNextJson` TEXT, `status` TEXT NOT NULL, " +
                "`contentHash` TEXT, `updatedAtEpoch` INTEGER NOT NULL, " +
                "`nightLawResult` TEXT NOT NULL, `heldUntilEpoch` INTEGER, " +
                "`heldReleased` INTEGER NOT NULL, `wasHeld` INTEGER NOT NULL, " +
                "`notified` INTEGER NOT NULL, `cachedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`appKey`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_channel_items_storyId` ON `channel_items` (`storyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_channel_items_formatType` ON `channel_items` (`formatType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_channel_items_publishedAtEpoch` ON `channel_items` (`publishedAtEpoch`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_channel_items_bundleId` ON `channel_items` (`bundleId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bundles` (" +
                "`bundleId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `memberAppKeys` TEXT NOT NULL, " +
                "`bundleDateShamsi` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`contentHash` TEXT, `updatedAtEpoch` INTEGER NOT NULL, " +
                "PRIMARY KEY(`bundleId`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `radar_signals` (" +
                "`signalId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`layer` INTEGER NOT NULL, `severity` TEXT, " +
                "`severityRank` INTEGER, `sourceName` TEXT NOT NULL, " +
                "`sourceLink` TEXT, `linkedStoryId` TEXT, " +
                "`detectedAtEpoch` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                "`contentHash` TEXT, `updatedAtEpoch` INTEGER NOT NULL, " +
                "PRIMARY KEY(`signalId`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_radar_signals_layer` ON `radar_signals` (`layer`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_radar_signals_detectedAtEpoch` ON `radar_signals` (`detectedAtEpoch`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bundle_bookmarks` (" +
                "`bundleId` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`bundleId`))"
        )
    }
}

// Version 3 -> 4 migration (Phase 1). Additive only: a new
// join_keys table. No existing table is altered, so a v3 install
// keeps its full cache; the mapping backfills on the next v3 sync.
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `join_keys` (" +
                "`appKey` TEXT NOT NULL, `guid` TEXT, `storyId` TEXT, " +
                "`channelMessageId` INTEGER, PRIMARY KEY(`appKey`))"
        )
    }
}

/**
 * Version 2 -> 3 migration (M4). Additive only: a new
 * market_formulas table. Existing snapshots, articles and bookmarks
 * are untouched, so a v2 install keeps its cache and the seed
 * backfills formulas on the next launch (BundledSeed retries while
 * formulaCount is 0, even when snapshots already exist).
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `market_formulas` (" +
                "`name` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                "`definition` TEXT NOT NULL, `inputs` TEXT, " +
                "`descriptionFa` TEXT, PRIMARY KEY(`name`))"
        )
    }
}

/**
 * Version 1 -> 2 migration (M2). This is a real migration, not a
 * destructive fallback: existing articles are copied, then their
 * new columns are backfilled in Kotlin (appKey from the verbatim id,
 * epoch from the raw date string, content hash from the stored
 * fields), and bookmarks are remapped from verbatim ids to appKeys
 * through the articles table. Rows whose bookmark points at an
 * article that no longer exists are dropped by the join, which is
 * the same behaviour the old orphan rule produced (they vanished
 * from the bookmark list), but now explicitly, in one migration.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `articles_new` (" +
                "`id` TEXT NOT NULL, `appKey` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`summary` TEXT NOT NULL, `bodyBlocks` TEXT NOT NULL, " +
                "`pointsJson` TEXT NOT NULL, `whyMatters` TEXT, " +
                "`category` TEXT NOT NULL, `categoryFa` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, `originalUrl` TEXT, " +
                "`publishedAt` TEXT, `publishedAtEpoch` INTEGER, " +
                "`contentHash` TEXT, `updatedAtEpoch` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, `retractedAtEpoch` INTEGER, " +
                "`channelMessageId` INTEGER, `cachedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "INSERT INTO `articles_new` " +
                "(id, appKey, kind, title, summary, bodyBlocks, pointsJson, " +
                "whyMatters, category, categoryFa, source, originalUrl, " +
                "publishedAt, publishedAtEpoch, contentHash, updatedAtEpoch, " +
                "status, retractedAtEpoch, channelMessageId, cachedAt) " +
                "SELECT id, '', kind, title, summary, bodyBlocks, pointsJson, " +
                "whyMatters, category, categoryFa, source, originalUrl, " +
                "publishedAt, NULL, NULL, cachedAt, 'approved', NULL, " +
                "channelMessageId, cachedAt FROM `articles`"
        )
        val cursor = db.query(
            "SELECT id, title, summary, bodyBlocks, pointsJson, " +
                "whyMatters, category, source, originalUrl, publishedAt, cachedAt " +
                "FROM `articles_new`"
        )
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow("id")
            val titleIdx = it.getColumnIndexOrThrow("title")
            val summaryIdx = it.getColumnIndexOrThrow("summary")
            val blocksIdx = it.getColumnIndexOrThrow("bodyBlocks")
            val pointsIdx = it.getColumnIndexOrThrow("pointsJson")
            val whyIdx = it.getColumnIndexOrThrow("whyMatters")
            val catIdx = it.getColumnIndexOrThrow("category")
            val sourceIdx = it.getColumnIndexOrThrow("source")
            val urlIdx = it.getColumnIndexOrThrow("originalUrl")
            val pubIdx = it.getColumnIndexOrThrow("publishedAt")
            val cachedIdx = it.getColumnIndexOrThrow("cachedAt")
            while (it.moveToNext()) {
                val id = it.getString(idIdx)
                val title = it.getString(titleIdx)
                val summary = it.getString(summaryIdx)
                val blocksRaw = it.getString(blocksIdx) ?: ""
                val blocks = if (blocksRaw.isEmpty()) emptyList() else blocksRaw.split("\u0001")
                val pointsJson = it.getString(pointsIdx) ?: "[]"
                val why = if (it.isNull(whyIdx)) null else it.getString(whyIdx)
                val category = it.getString(catIdx) ?: "general"
                val source = it.getString(sourceIdx) ?: ""
                val url = if (it.isNull(urlIdx)) null else it.getString(urlIdx)
                val publishedAt = if (it.isNull(pubIdx)) null else it.getString(pubIdx)
                val cachedAt = it.getLong(cachedIdx)
                val epoch = PublishedAtParser.epochMillis(publishedAt)
                val hash = ContentHashes.forArticle(
                    title = title,
                    summary = summary,
                    bodyBlocks = blocks,
                    pointsJson = pointsJson,
                    whyMatters = why,
                    category = category,
                    source = source,
                    originalUrl = url,
                    publishedAt = publishedAt,
                )
                db.execSQL(
                    "UPDATE `articles_new` SET appKey = ?, publishedAtEpoch = ?, " +
                        "contentHash = ?, updatedAtEpoch = ? WHERE id = ?",
                    arrayOf<Any?>(
                        ArticleKeys.appKeyFor(id),
                        epoch,
                        hash,
                        epoch ?: cachedAt,
                        id,
                    ),
                )
            }
        }
        db.execSQL("DROP TABLE IF EXISTS `articles`")
        db.execSQL("ALTER TABLE `articles_new` RENAME TO `articles`")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_articles_appKey` ON `articles` (`appKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_category` ON `articles` (`category`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_publishedAtEpoch` ON `articles` (`publishedAtEpoch`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bookmarks_new` (" +
                "`appKey` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`appKey`))"
        )
        db.execSQL(
            "INSERT INTO `bookmarks_new` (appKey, savedAt) " +
                "SELECT a.appKey, b.savedAt FROM `bookmarks` b " +
                "JOIN `articles` a ON a.id = b.articleId"
        )
        db.execSQL("DROP TABLE IF EXISTS `bookmarks`")
        db.execSQL("ALTER TABLE `bookmarks_new` RENAME TO `bookmarks`")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `market_snapshots` (" +
                "`snapshotId` TEXT NOT NULL, `tsTehran` TEXT NOT NULL, " +
                "`tsEpoch` INTEGER, `brent` REAL, `xauUsd` REAL, `dxy` REAL, " +
                "`usdtUsd` REAL, `usdIrtFree` REAL, `usdtIrt` REAL, " +
                "`gold18kGram` REAL, `sekkeEmami` REAL, `sekkeBahar` REAL, " +
                "`nimSekke` REAL, `robSekke` REAL, `vix` REAL, `sp500` REAL, " +
                "PRIMARY KEY(`snapshotId`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_market_snapshots_tsEpoch` ON `market_snapshots` (`tsEpoch`)")
    }
}

@Database(
    entities = [
        ArticleEntity::class,
        BookmarkEntity::class,
        MetaEntity::class,
        MarketSnapshotEntity::class,
        MarketFormulaEntity::class,
        JoinKeyEntity::class,
        ChannelItemEntity::class,
        BundleEntity::class,
        RadarSignalEntity::class,
        BundleBookmarkEntity::class,
        MarketAlertEventEntity::class,
        NotificationHistoryEntity::class,
        NightlyEditionEntity::class,
        NightlyItemEntity::class,
        DossierEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
@TypeConverters(StringListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun articleDao(): ArticleDao
    abstract fun channelDao(): ChannelDao
    abstract fun phase4Dao(): Phase4Dao
    abstract fun nightlyDao(): NightlyDao
}
