package com.vayunmathur.youpipe.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * A record that a video was shown in the recommendation feed. Used to drive the
 * feedback loop: rarely-shown channels earn an exploration bonus, while channels
 * shown many times but never watched are demoted, and recently-shown videos are
 * suppressed on refresh. [source] is stored as the [com.vayunmathur.youpipe.domain.RecSource]
 * name.
 */
@Entity
data class RecommendationImpression(
    @PrimaryKey val videoID: Long,
    val channelKey: String,
    val source: String,
    val shownCount: Int,
    val firstShownAt: Instant,
    val lastShownAt: Instant,
)

/** Contract for recommendation impression dao. */
@Dao
interface RecommendationImpressionDao {
    @Query("SELECT * FROM RecommendationImpression")
    suspend fun getAll(): List<RecommendationImpression>

    @Query("SELECT * FROM RecommendationImpression")
    fun getAllFlow(): Flow<List<RecommendationImpression>>

    @Query("SELECT * FROM RecommendationImpression WHERE videoID = :videoID")
    suspend fun getById(videoID: Long): RecommendationImpression?

    /**
     * Records an impression for [videoID], incrementing [shownCount] and updating
     * [lastShownAt] when a row already exists, otherwise inserting a new row.
     */
    @Query(
        """
        INSERT INTO RecommendationImpression (videoID, channelKey, source, shownCount, firstShownAt, lastShownAt)
        VALUES (:videoID, :channelKey, :source, 1, :now, :now)
        ON CONFLICT(videoID) DO UPDATE SET
            shownCount = shownCount + 1,
            lastShownAt = :now,
            channelKey = :channelKey,
            source = :source
        """
    )
    suspend fun recordImpression(videoID: Long, channelKey: String, source: String, now: Instant)

    @Query("DELETE FROM RecommendationImpression WHERE lastShownAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Instant)

    @Query("DELETE FROM RecommendationImpression")
    suspend fun clearAll()
}
