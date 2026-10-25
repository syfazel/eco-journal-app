package com.ecojournal.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/** One cached article. bodyBlocks is stored via StringListConverter. */
@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val id: String,
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
    val channelMessageId: Long?,
    val cachedAt: Long,
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val articleId: String,
    val savedAt: Long,
)

@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
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

    @Query("SELECT value FROM meta WHERE `key` = 'index_hash'")
    suspend fun indexHash(): String?

    @Query("SELECT * FROM articles ORDER BY publishedAt DESC")
    suspend fun all(): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ArticleEntity?

    @Query(
        "SELECT * FROM articles WHERE category = :category " +
            "ORDER BY publishedAt DESC"
    )
    suspend fun byCategory(category: String): List<ArticleEntity>

    /** Offline search over cached titles and summaries only. */
    @Query(
        "SELECT * FROM articles WHERE title LIKE '%' || :q || '%' " +
            "OR summary LIKE '%' || :q || '%' ORDER BY publishedAt DESC"
    )
    suspend fun search(q: String): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE id IN (SELECT articleId FROM bookmarks) ORDER BY publishedAt DESC")
    suspend fun bookmarked(): List<ArticleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE articleId = :id")
    suspend fun removeBookmark(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE articleId = :id)")
    suspend fun isBookmarked(id: String): Boolean

    @Query("SELECT id FROM articles")
    suspend fun allIds(): List<String>
}

@Database(
    entities = [ArticleEntity::class, BookmarkEntity::class, MetaEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(StringListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun articleDao(): ArticleDao
}
