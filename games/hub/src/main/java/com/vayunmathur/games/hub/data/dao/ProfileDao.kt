package com.vayunmathur.games.hub.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.vayunmathur.games.hub.data.entities.PlayerProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM player_profile WHERE id = 0 LIMIT 1")
    fun flowProfile(): Flow<PlayerProfileEntity?>

    @Query("SELECT * FROM player_profile WHERE id = 0 LIMIT 1")
    suspend fun getProfile(): PlayerProfileEntity?

    @Upsert
    suspend fun upsert(profile: PlayerProfileEntity)
}
