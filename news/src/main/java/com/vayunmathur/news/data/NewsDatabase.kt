package com.vayunmathur.news.data

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "news-db"

/** Cached feed stub. [bookmarked] rows survive feed pruning. */
@Entity
data class CachedArticle(
    @PrimaryKey val url: String,
    val sourceId: String,
    val title: String,
    val publishedAtMillis: Long,
    val imageUrl: String? = null,
    val keywords: String = "",
    val fetchedAtMillis: Long = 0L,
    val bookmarked: Boolean = false,
)

/** Cached extracted body (blocks as JSON), so read articles open offline. */
@Entity
data class CachedBody(
    @PrimaryKey val url: String,
    val title: String,
    val byline: String? = null,
    val publishedAtMillis: Long? = null,
    val heroImageUrl: String? = null,
    val blocksJson: String = "[]",
    val fetchedAtMillis: Long = 0L,
)

@Dao
interface NewsDao {
    @Query("SELECT * FROM CachedArticle ORDER BY publishedAtMillis DESC")
    fun observeArticles(): Flow<List<CachedArticle>>

    @Query("SELECT * FROM CachedArticle WHERE bookmarked = 1 ORDER BY publishedAtMillis DESC")
    fun observeBookmarked(): Flow<List<CachedArticle>>

    @Query("SELECT * FROM CachedArticle WHERE url = :url")
    suspend fun article(url: String): CachedArticle?

    /** Bookmarked subset of [urls], in one query (publish must not query per article). */
    @Query("SELECT url FROM CachedArticle WHERE bookmarked = 1 AND url IN (:urls)")
    suspend fun bookmarkedUrls(urls: List<String>): List<String>

    @Query("SELECT * FROM CachedBody WHERE url = :url")
    suspend fun body(url: String): CachedBody?

    @Upsert
    suspend fun upsertArticles(articles: List<CachedArticle>)

    @Upsert
    suspend fun upsertBody(body: CachedBody)

    @Query("UPDATE CachedArticle SET bookmarked = :bookmarked WHERE url = :url")
    suspend fun setBookmarked(url: String, bookmarked: Boolean)

    /** Prunes unbookmarked stubs older than the newest [keep] rows. */
    @Query(
        "DELETE FROM CachedArticle WHERE bookmarked = 0 AND url NOT IN " +
            "(SELECT url FROM CachedArticle ORDER BY publishedAtMillis DESC LIMIT :keep)",
    )
    suspend fun pruneArticles(keep: Int)
}

@Database(entities = [CachedArticle::class, CachedBody::class], version = 1, exportSchema = true)
abstract class NewsDatabase : RoomDatabase() {
    abstract fun newsDao(): NewsDao
}
