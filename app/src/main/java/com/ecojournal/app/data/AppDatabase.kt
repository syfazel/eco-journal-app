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

    @Query(
        "SELECT * FROM articles WHERE category = :category " +
            "AND status != 'retracted' " +
            "ORDER BY publishedAtEpoch DESC, publishedAt DESC"
    )
    suspend fun byCategory(category: String): List<ArticleEntity>

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

    @Query("SELECT id FROM articles")
    suspend fun allIds(): List<String>

    @Query("SELECT appKey FROM articles")
    suspend fun allAppKeys(): List<String>

    @Query("SELECT * FROM market_snapshots ORDER BY tsEpoch DESC LIMIT 1")
    fun marketLatestFlow(): Flow<MarketSnapshotEntity?>

    @Query("SELECT * FROM market_snapshots ORDER BY tsEpoch DESC LIMIT 1")
    suspend fun marketLatest(): MarketSnapshotEntity?

    @Query("SELECT COUNT(*) FROM market_snapshots")
    suspend fun marketCount(): Int

    @Query("SELECT COUNT(*) FROM market_snapshots")
    fun marketCountFlow(): Flow<Int>
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
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(StringListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun articleDao(): ArticleDao
}
