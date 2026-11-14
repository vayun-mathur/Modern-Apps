package com.vayunmathur.games.hub.data.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.vayunmathur.games.hub.data.entities.PlayerProfileEntity
import kotlinx.coroutines.flow.Flow

/** Contract for profile dao. */
@Dao
interface ProfileDao {

    @Query("SELECT * FROM player_profile WHERE id = 0 LIMIT 1")
    fun flowProfile(): Flow<PlayerProfileEntity?>

    @Query("SELECT * FROM player_profile WHERE id = 0 LIMIT 1")
    suspend fun getProfile(): PlayerProfileEntity?

    @Upsert
    suspend fun upsert(profile: PlayerProfileEntity)
}
