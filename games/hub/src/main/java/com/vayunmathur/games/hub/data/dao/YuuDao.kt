package com.vayunmathur.games.hub.data.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.vayunmathur.games.hub.data.entities.YuuEntity
import kotlinx.coroutines.flow.Flow

/** Contract for yuu dao. */
@Dao
interface YuuDao {

    @Upsert
    suspend fun upsert(yuu: YuuEntity): Long

    @Query("SELECT * FROM yuus WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): YuuEntity?

    @Query("SELECT * FROM yuus ORDER BY updatedAt DESC")
    fun flowAll(): Flow<List<YuuEntity>>

    @Query("SELECT * FROM yuus WHERE isProfile = 1 LIMIT 1")
    fun flowProfile(): Flow<YuuEntity?>

    @Query("SELECT * FROM yuus WHERE isProfile = 1 LIMIT 1")
    suspend fun getProfile(): YuuEntity?

    @Query("DELETE FROM yuus WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE yuus SET isProfile = 0")
    suspend fun clearProfileFlag()

    @Query("UPDATE yuus SET isProfile = 1 WHERE id = :id")
    suspend fun markProfile(id: Long)

    /** At most one row holds `isProfile`; clear-then-set runs atomically. */
    @Transaction
    suspend fun setProfile(id: Long) {
        clearProfileFlag()
        markProfile(id)
    }

    @Query("DELETE FROM yuus")
    suspend fun clearAll()
}
