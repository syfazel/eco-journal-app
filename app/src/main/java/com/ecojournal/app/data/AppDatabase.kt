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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.Flow

/**
 * Phase 4 (v5.3): Yemen-only database.
 *
 * The non-Yemen sections (feed, market, war room, bundles,
 * nightly, channel, bookmarks) were removed from the app, so
 * their fourteen tables are dropped by MIGRATION_8_9. What
 * remains is the generic `meta` key/value table (seed state,
 * Yemen sync state, SyncTrace) plus the five Yemen tables from
 * v5.2, unchanged. The old migrations 1→8 stay in the chain
 * verbatim so an install on any older version can still walk
 * up to 9; MIGRATION_1_2's Kotlin backfill keeps its three
 * tiny helpers here (copied from the deleted ArticleParsing.kt)
 * because nothing else uses them any more.
 *
 * Bookmarks are NOT migrated: they pointed at articles that no
 * longer exist in the app (decision D21). The invite gate and
 * the Yemen cache survive the upgrade untouched.
 */

@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * Yemen tab (v5.2, audit §4.3). Entities carry [provenance] and
 * [sourceNote] from day one (P1-26 lesson: article entities lost
 * them at the Room boundary). Identity is [appKey] ("y-" + 20 hex
 * of SHA-256(name_fa + "|" + governorate_fa)); the dashboard
 * numeric id is kept as [externalId] and never used for identity.
 * [contentHash] covers the control-bearing fields, so a faction
 * change is detectable as a control change on the next sync.
 */
@Entity(
    tableName = "yemen_places",
    indices = [
        Index(value = ["appKey"], unique = true),
        Index(value = ["faction"]),
        Index(value = ["governorateFa"]),
    ],
)
data class YemenPlaceEntity(
    val externalId: Int,
    @PrimaryKey val appKey: String,
    val nameFa: String,
    val nameEn: String,
    val governorateFa: String,
    val lat: Double,
    val lon: Double,
    val faction: String,
    val controlLabel: String,
    val confidence: String,
    val note: String,
    val provenance: String,
    val sourceNote: String,
    val updatedAt: String?,
    val contentHash: String?,
)

@Entity(
    tableName = "yemen_events",
    indices = [
        Index(value = ["appKey"], unique = true),
        Index(value = ["eventDate"]),
    ],
)
data class YemenEventEntity(
    val externalId: Int,
    @PrimaryKey val appKey: String,
    val eventDate: String,
    val titleFa: String,
    val detailFa: String,
    val origin: String,
    val corroboration: Int,
    val families: String?,
    val sourceName: String,
    val sourceUrl: String,
)

/** Yemen frontline: waypoints + sources stored as verbatim JSON
 * (same pattern as the old ChannelItemEntity's JSON columns), so a
 * malformed sub-object degrades one frontline, never the table. */
@Entity(tableName = "yemen_frontlines")
data class YemenFrontlineEntity(
    @PrimaryKey val id: String,
    val nameFa: String,
    val nameEn: String,
    val statusKind: String,
    val statusFa: String,
    val traceKind: String,
    val waypointsJson: String,
    val sideAFa: String,
    val sideBFa: String,
    val confidenceFa: String,
    val summaryFa: String,
    val governoratesFa: String,
    val movementFa: String,
    val traceNoteFa: String,
    val sourcesJson: String,
)

/** Aden verdict, single row (id = "aden"). Ring, timeline, layers
 * and the rest are verbatim JSON columns parsed at render time. */
@Entity(tableName = "yemen_aden")
data class YemenAdenEntity(
    @PrimaryKey val id: String,
    val verdictTitleFa: String,
    val verdictBodyFa: String,
    val verdictConfidenceFa: String,
    val ringJson: String,
    val timelineJson: String,
    val keyPointsJson: String,
    val sourcesJson: String,
    val layersJson: String,
    val contradictionsJson: String,
    val channelsMethodFa: String,
    val crossCheckSummaryFa: String,
    val seenNoteFa: String,
    val taizNoteFa: String,
    val openQuestionsJson: String,
)

/** Yemen sync bookkeeping, single row (key = "yemen").
 * [yemenHash] is the manifest section hash, stored ONLY after a
 * fully successful apply. [yemenSeedHash] is the SHA-256 of the
 * bundled seed file, stored ONLY when the seed is complete.
 * [controlChanges] counts places whose faction changed on the
 * last successful sync. */
@Entity(tableName = "yemen_meta")
data class YemenMetaEntity(
    @PrimaryKey val key: String,
    val yemenHash: String?,
    val yemenGeneratedAt: String?,
    val yemenSeedHash: String?,
    val lastYemenSyncAt: Long?,
    val lastYemenErrorCode: String?,
    val controlChanges: Int,
)

/**
 * Minimal meta DAO (Phase 4). The old ArticleDao's article /
 * market / bookmark / search queries are gone with their
 * tables; only the key/value meta access survives — the seed,
 * the Yemen sync and SyncTrace all read/write meta keys.
 */
@Dao
interface MetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: MetaEntity)

    @Query("SELECT value FROM meta WHERE `key` = :key LIMIT 1")
    suspend fun metaValue(key: String): String?

    @Query("SELECT value FROM meta WHERE `key` = :key LIMIT 1")
    fun metaValueFlow(key: String): Flow<String?>
}

@Dao
interface YemenDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaces(places: List<YemenPlaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvents(events: List<YemenEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFrontlines(frontlines: List<YemenFrontlineEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAden(aden: YemenAdenEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: YemenMetaEntity)

    @Query("SELECT * FROM yemen_places ORDER BY nameFa ASC")
    fun placesFlow(): Flow<List<YemenPlaceEntity>>

    @Query("SELECT * FROM yemen_places ORDER BY nameFa ASC")
    suspend fun places(): List<YemenPlaceEntity>

    @Query("SELECT * FROM yemen_events ORDER BY eventDate DESC, titleFa ASC")
    fun eventsFlow(): Flow<List<YemenEventEntity>>

    @Query("SELECT * FROM yemen_events ORDER BY eventDate DESC, titleFa ASC")
    suspend fun events(): List<YemenEventEntity>

    @Query("SELECT * FROM yemen_frontlines ORDER BY nameFa ASC")
    fun frontlinesFlow(): Flow<List<YemenFrontlineEntity>>

    @Query("SELECT * FROM yemen_frontlines ORDER BY nameFa ASC")
    suspend fun frontlines(): List<YemenFrontlineEntity>

    @Query("SELECT * FROM yemen_aden WHERE id = 'aden' LIMIT 1")
    fun adenFlow(): Flow<YemenAdenEntity?>

    @Query("SELECT * FROM yemen_aden WHERE id = 'aden' LIMIT 1")
    suspend fun aden(): YemenAdenEntity?

    @Query("SELECT * FROM yemen_meta WHERE `key` = 'yemen' LIMIT 1")
    suspend fun meta(): YemenMetaEntity?

    @Query("SELECT * FROM yemen_meta WHERE `key` = 'yemen' LIMIT 1")
    fun metaFlow(): Flow<YemenMetaEntity?>

    @Query("SELECT COUNT(*) FROM yemen_places")
    suspend fun placeCount(): Int

    @Query("SELECT COUNT(*) FROM yemen_events")
    suspend fun eventCount(): Int

    @Query("SELECT appKey FROM yemen_places")
    suspend fun allPlaceAppKeys(): List<String>

    @Query("SELECT faction, COUNT(*) AS c FROM yemen_places GROUP BY faction")
    suspend fun factionCounts(): List<YemenFactionCount>

    @Query("DELETE FROM yemen_places")
    suspend fun deleteAllPlaces()

    @Query("DELETE FROM yemen_events")
    suspend fun deleteAllEvents()

    @Query("DELETE FROM yemen_frontlines")
    suspend fun deleteAllFrontlines()
}

/** GROUP BY result holder for yemen_places faction counts. */
data class YemenFactionCount(
    val faction: String,
    val c: Int,
)

// Version 8 -> 9 migration (Phase 4, v5.3 removal). Drops the
// fourteen non-Yemen tables and their indexes. The `meta` table
// and the five Yemen tables are untouched, so the invite gate,
// the Yemen cache and the seed state survive the upgrade. The
// dropped tables held only edge cache (re-fetchable in the old
// app, unneeded in the Yemen-only app) and article bookmarks
// (D21: not migrated — their articles no longer exist). A fresh
// install never runs this migration; it creates v9 directly.
val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `articles`")
        db.execSQL("DROP TABLE IF EXISTS `bookmarks`")
        db.execSQL("DROP TABLE IF EXISTS `join_keys`")
        db.execSQL("DROP TABLE IF EXISTS `channel_items`")
        db.execSQL("DROP TABLE IF EXISTS `bundles`")
        db.execSQL("DROP TABLE IF EXISTS `radar_signals`")
        db.execSQL("DROP TABLE IF EXISTS `market_alert_events`")
        db.execSQL("DROP TABLE IF EXISTS `notification_history`")
        db.execSQL("DROP TABLE IF EXISTS `nightly_editions`")
        db.execSQL("DROP TABLE IF EXISTS `nightly_items`")
        db.execSQL("DROP TABLE IF EXISTS `dossiers`")
        db.execSQL("DROP TABLE IF EXISTS `bundle_bookmarks`")
        db.execSQL("DROP TABLE IF EXISTS `market_formulas`")
        db.execSQL("DROP TABLE IF EXISTS `market_snapshots`")
    }
}

// Version 7 -> 8 migration (v5.2 Yemen tab, audit §4.3). Additive
// only: five new tables (yemen_places, yemen_events,
// yemen_frontlines, yemen_aden, yemen_meta). No existing table is
// altered, so a v7 install keeps its full cache; Yemen content
// backfills from the bundled seed (BUNDLED_SEED_VERSION 2) on the
// next launch and from the 24h YemenSyncWorker after that.
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `yemen_places` (" +
                "`externalId` INTEGER NOT NULL, `appKey` TEXT NOT NULL, " +
                "`nameFa` TEXT NOT NULL, `nameEn` TEXT NOT NULL, " +
                "`governorateFa` TEXT NOT NULL, `lat` REAL NOT NULL, " +
                "`lon` REAL NOT NULL, `faction` TEXT NOT NULL, " +
                "`controlLabel` TEXT NOT NULL, `confidence` TEXT NOT NULL, " +
                "`note` TEXT NOT NULL, `provenance` TEXT NOT NULL, " +
                "`sourceNote` TEXT NOT NULL, `updatedAt` TEXT, " +
                "`contentHash` TEXT, PRIMARY KEY(`appKey`))"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_yemen_places_appKey` ON `yemen_places` (`appKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_yemen_places_faction` ON `yemen_places` (`faction`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_yemen_places_governorateFa` ON `yemen_places` (`governorateFa`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `yemen_events` (" +
                "`externalId` INTEGER NOT NULL, `appKey` TEXT NOT NULL, " +
                "`eventDate` TEXT NOT NULL, `titleFa` TEXT NOT NULL, " +
                "`detailFa` TEXT NOT NULL, `origin` TEXT NOT NULL, " +
                "`corroboration` INTEGER NOT NULL, `families` TEXT, " +
                "`sourceName` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, " +
                "PRIMARY KEY(`appKey`))"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_yemen_events_appKey` ON `yemen_events` (`appKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_yemen_events_eventDate` ON `yemen_events` (`eventDate`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `yemen_frontlines` (" +
                "`id` TEXT NOT NULL, `nameFa` TEXT NOT NULL, " +
                "`nameEn` TEXT NOT NULL, `statusKind` TEXT NOT NULL, " +
                "`statusFa` TEXT NOT NULL, `traceKind` TEXT NOT NULL, " +
                "`waypointsJson` TEXT NOT NULL, `sideAFa` TEXT NOT NULL, " +
                "`sideBFa` TEXT NOT NULL, `confidenceFa` TEXT NOT NULL, " +
                "`summaryFa` TEXT NOT NULL, `governoratesFa` TEXT NOT NULL, " +
                "`movementFa` TEXT NOT NULL, `traceNoteFa` TEXT NOT NULL, " +
                "`sourcesJson` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `yemen_aden` (" +
                "`id` TEXT NOT NULL, `verdictTitleFa` TEXT NOT NULL, " +
                "`verdictBodyFa` TEXT NOT NULL, " +
                "`verdictConfidenceFa` TEXT NOT NULL, " +
                "`ringJson` TEXT NOT NULL, `timelineJson` TEXT NOT NULL, " +
                "`keyPointsJson` TEXT NOT NULL, `sourcesJson` TEXT NOT NULL, " +
                "`layersJson` TEXT NOT NULL, `contradictionsJson` TEXT NOT NULL, " +
                "`channelsMethodFa` TEXT NOT NULL, " +
                "`crossCheckSummaryFa` TEXT NOT NULL, `seenNoteFa` TEXT NOT NULL, " +
                "`taizNoteFa` TEXT NOT NULL, `openQuestionsJson` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `yemen_meta` (" +
                "`key` TEXT NOT NULL, `yemenHash` TEXT, " +
                "`yemenGeneratedAt` TEXT, `yemenSeedHash` TEXT, " +
                "`lastYemenSyncAt` INTEGER, `lastYemenErrorCode` TEXT, " +
                "`controlChanges` INTEGER NOT NULL, PRIMARY KEY(`key`))"
        )
    }
}

// Version 6 -> 7 migration (Phase 5). Additive only: three new
// tables (nightly_editions, nightly_items, dossiers).
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
// tables + bundle bookmarks.
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
// join_keys table.
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `join_keys` (" +
                "`appKey` TEXT NOT NULL, `guid` TEXT, `storyId` TEXT, " +
                "`channelMessageId` INTEGER, PRIMARY KEY(`appKey`))"
        )
    }
}

// Version 2 -> 3 migration (M4). Additive only: a new
// market_formulas table.
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

// Helpers kept ONLY for MIGRATION_1_2 below (copied verbatim
// from the deleted ArticleParsing.kt, Phase 4). Nothing else
// in the app uses them any more.
object ArticleKeys {
    fun appKeyFor(externalId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(externalId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "a-" + hex.take(20)
    }
}

object ContentHashes {
    fun forArticle(
        title: String,
        summary: String,
        bodyBlocks: List<String>,
        pointsJson: String,
        whyMatters: String?,
        category: String,
        source: String,
        originalUrl: String?,
        publishedAt: String?,
    ): String {
        val canonical = listOf(
            title,
            summary,
            bodyBlocks.joinToString("\n"),
            pointsJson,
            whyMatters ?: "",
            category,
            source,
            originalUrl ?: "",
            publishedAt ?: "",
        ).joinToString("\u0001")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

object PublishedAtParser {
    private val namedZones = mapOf(
        "EST" to "-0500",
        "EDT" to "-0400",
        "CST" to "-0600",
        "CDT" to "-0500",
        "PST" to "-0800",
        "PDT" to "-0700",
    )

    private val rfcFormatter = DateTimeFormatter.ofPattern(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        Locale.US,
    )
    private val plainFormatter = DateTimeFormatter.ofPattern(
        "dd MMM yyyy HH:mm:ss Z",
        Locale.US,
    )

    fun epochMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val squashed = raw.trim().replace(Regex("\\s+"), " ")
        try {
            return OffsetDateTime.parse(squashed).toInstant().toEpochMilli()
        } catch (_: Exception) {
        }
        var normalised = squashed
        if (normalised.endsWith(" GMT")) {
            normalised = normalised.removeSuffix(" GMT") + " +0000"
        } else {
            for ((name, offset) in namedZones) {
                if (normalised.endsWith(" $name")) {
                    normalised = normalised.removeSuffix(" $name") + " $offset"
                    break
                }
            }
        }
        for (formatter in listOf(rfcFormatter, plainFormatter)) {
            try {
                return OffsetDateTime.parse(normalised, formatter)
                    .toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
        }
        return null
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
        MetaEntity::class,
        YemenPlaceEntity::class,
        YemenEventEntity::class,
        YemenFrontlineEntity::class,
        YemenAdenEntity::class,
        YemenMetaEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun metaDao(): MetaDao
    abstract fun yemenDao(): YemenDao
}
