package com.vayunmathur.youpipe.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import com.vayunmathur.library.util.DefaultConverters
import kotlinx.coroutines.flow.Flow

val MIGRATION_1_2 = Migration(1, 2) {
    it.execSQL("""
        CREATE TABLE IF NOT EXISTS `DownloadedVideo` (
            `id` INTEGER NOT NULL, 
            `name` TEXT NOT NULL, 
            `videoID` INTEGER NOT NULL, 
            `duration` INTEGER NOT NULL, 
            `views` INTEGER NOT NULL, 
            `uploadDate` INTEGER NOT NULL, 
            `thumbnailURL` TEXT NOT NULL, 
            `author` TEXT NOT NULL, 
            `filePath` TEXT NOT NULL, 
            `audioPath` TEXT, 
            `timestamp` INTEGER NOT NULL, 
            PRIMARY KEY(`id`)
        )
    """.trimIndent())
}

val MIGRATION_2_3 = Migration(2, 3) {
    it.execSQL("""
        CREATE TABLE IF NOT EXISTS `CachedRelatedVideo` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `sourceVideoID` INTEGER NOT NULL,
            `name` TEXT NOT NULL,
            `videoID` INTEGER NOT NULL,
            `duration` INTEGER NOT NULL,
            `views` INTEGER NOT NULL,
            `uploadDate` INTEGER NOT NULL,
            `thumbnailURL` TEXT NOT NULL,
            `author` TEXT NOT NULL,
            `cachedAt` INTEGER NOT NULL
        )
    """.trimIndent())
}

val MIGRATION_3_4 = Migration(3, 4) {
    it.execSQL("""
        CREATE TABLE IF NOT EXISTS `RecommendationImpression` (
            `videoID` INTEGER NOT NULL,
            `channelKey` TEXT NOT NULL,
            `source` TEXT NOT NULL,
            `shownCount` INTEGER NOT NULL,
            `firstShownAt` INTEGER NOT NULL,
            `lastShownAt` INTEGER NOT NULL,
            PRIMARY KEY(`videoID`)
        )
    """.trimIndent())
    it.execSQL("""
        CREATE TABLE IF NOT EXISTS `RecommendationPreferences` (
            `id` INTEGER NOT NULL,
            `preset` TEXT NOT NULL,
            `discoveryFamiliar` REAL NOT NULL,
            `freshEvergreen` REAL NOT NULL,
            `focusedDiverse` REAL NOT NULL,
            `sourceRelated` INTEGER NOT NULL,
            `sourceTrending` INTEGER NOT NULL,
            `sourceSubscription` INTEGER NOT NULL,
            `sourceTopChannel` INTEGER NOT NULL,
            `sourceSearch` INTEGER NOT NULL,
            `hideShorts` INTEGER NOT NULL,
            `hideLive` INTEGER NOT NULL,
            `minDurationSec` INTEGER NOT NULL,
            `maxDurationSec` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
    """.trimIndent())
    it.execSQL("""
        CREATE TABLE IF NOT EXISTS `ChannelPreference` (
            `channelKey` TEXT NOT NULL,
            `multiplier` REAL NOT NULL,
            `blocked` INTEGER NOT NULL,
            `pinned` INTEGER NOT NULL,
            PRIMARY KEY(`channelKey`)
        )
    """.trimIndent())
    it.execSQL("""
        CREATE TABLE IF NOT EXISTS `KeywordPreference` (
            `keyword` TEXT NOT NULL,
            `muted` INTEGER NOT NULL,
            PRIMARY KEY(`keyword`)
        )
    """.trimIndent())
}

@Dao
interface HistoryVideoDao {
    @Query("SELECT * FROM HistoryVideo")
    fun getAllFlow(): Flow<List<HistoryVideo>>

    @Query("SELECT * FROM HistoryVideo WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<HistoryVideo?>

    @Query("SELECT * FROM HistoryVideo")
    suspend fun getAll(): List<HistoryVideo>

    @Upsert
    suspend fun upsert(value: HistoryVideo): Long

    @Upsert
    suspend fun upsertAll(values: List<HistoryVideo>)

    @Delete
    suspend fun delete(value: HistoryVideo)

    @Query("DELETE FROM HistoryVideo WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM HistoryVideo")
    suspend fun clearAll()
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM Subscription")
    fun getAllFlow(): Flow<List<Subscription>>

    @Query("SELECT * FROM Subscription WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Subscription?>

    @Query("SELECT * FROM Subscription")
    suspend fun getAll(): List<Subscription>

    @Query("DELETE FROM Subscription")
    suspend fun clearAll()

    @Upsert
    suspend fun upsert(value: Subscription): Long

    @Upsert
    suspend fun upsertAll(values: List<Subscription>)

    @Delete
    suspend fun delete(value: Subscription): Int
}

@Dao
interface SubscriptionVideoDao {
    @Query("SELECT * FROM SubscriptionVideo")
    fun getAllFlow(): Flow<List<SubscriptionVideo>>

    @Query("SELECT * FROM SubscriptionVideo WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<SubscriptionVideo?>

    @Query("SELECT * FROM SubscriptionVideo")
    suspend fun getAll(): List<SubscriptionVideo>

    @Upsert
    suspend fun upsertAll(values: List<SubscriptionVideo>)
}

@TypeConverters(DefaultConverters::class)
@Database(entities = [Subscription::class, SubscriptionVideo::class, HistoryVideo::class, SubscriptionCategory::class, DownloadedVideo::class, CachedRelatedVideo::class, RecommendationImpression::class, RecommendationPreferences::class, ChannelPreference::class, KeywordPreference::class], version = 4, exportSchema = false)
abstract class SubscriptionDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun subscriptionVideoDao(): SubscriptionVideoDao
    abstract fun historyVideoDao(): HistoryVideoDao
    abstract fun subscriptionCategoryDao(): SubscriptionCategoryDao
    abstract fun downloadedVideoDao(): DownloadedVideoDao
    abstract fun cachedRelatedVideoDao(): CachedRelatedVideoDao
    abstract fun recommendationImpressionDao(): RecommendationImpressionDao
    abstract fun recommendationPreferencesDao(): RecommendationPreferencesDao
    abstract fun channelPreferenceDao(): ChannelPreferenceDao
    abstract fun keywordPreferenceDao(): KeywordPreferenceDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
