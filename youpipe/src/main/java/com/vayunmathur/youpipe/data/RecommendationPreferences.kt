package com.vayunmathur.youpipe.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * User-facing recommendation controls. A single row (`id = 0`) holds all
 * feed-mix dials, source toggles, and content filters. Defaults reproduce
 * today's behavior so the feature is opt-in.
 *
 * The three dials are floats in [0, 1]:
 *  - [discoveryFamiliar]: 0 = mostly familiar/subscriptions, 1 = maximum discovery.
 *  - [freshEvergreen]: 0 = evergreen-friendly, 1 = strongly favor fresh uploads.
 *  - [focusedDiverse]: 0 = focused (fewer creators), 1 = maximally diverse.
 */
@Entity
data class RecommendationPreferences(
    @PrimaryKey val id: Int = 0,
    val preset: String = "BALANCED",
    val discoveryFamiliar: Float = 0.5f,
    val freshEvergreen: Float = 0.5f,
    val focusedDiverse: Float = 0.5f,
    val sourceRelated: Boolean = true,
    val sourceTrending: Boolean = true,
    val sourceSubscription: Boolean = true,
    val sourceTopChannel: Boolean = true,
    val sourceSearch: Boolean = true,
    val hideShorts: Boolean = false,
    val hideLive: Boolean = false,
    val hidePaid: Boolean = false,
    val minDurationSec: Long = 0,
    val maxDurationSec: Long = 0,
)

/** Contract for recommendation preferences dao. */
@Dao
interface RecommendationPreferencesDao {
    @Query("SELECT * FROM RecommendationPreferences WHERE id = 0")
    fun getFlow(): Flow<RecommendationPreferences?>

    @Query("SELECT * FROM RecommendationPreferences WHERE id = 0")
    suspend fun get(): RecommendationPreferences?

    @Upsert
    suspend fun upsert(value: RecommendationPreferences)

    @Query("DELETE FROM RecommendationPreferences")
    suspend fun clearAll()
}
