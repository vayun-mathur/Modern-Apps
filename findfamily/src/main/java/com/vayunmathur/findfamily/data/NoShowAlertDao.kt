package com.vayunmathur.findfamily.data

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoShowAlertDao {
    @Query("SELECT * FROM NoShowAlert")
    fun getAllFlow(): Flow<List<NoShowAlert>>

    @Query("SELECT * FROM NoShowAlert WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<NoShowAlert?>

    @Query("SELECT * FROM NoShowAlert")
    suspend fun getAll(): List<NoShowAlert>

    @Query("SELECT * FROM NoShowAlert WHERE id = :id")
    suspend fun get(id: Long): NoShowAlert?

    /**
     * Alerts whose deadline ([expectedAt] + [grace]) has passed and which have
     * not fired yet. Cutoffs are epoch seconds / whole milliseconds, matching
     * the database-wide column converters.
     */
    @Query(
        "SELECT * FROM NoShowAlert WHERE fired = 0 " +
            "AND (expectedAt + (grace / 1000)) <= :nowEpochSeconds"
    )
    suspend fun getDue(nowEpochSeconds: Long): List<NoShowAlert>

    @Upsert
    suspend fun upsert(value: NoShowAlert): Long

    @Delete
    suspend fun delete(value: NoShowAlert): Int

    @Query("DELETE FROM NoShowAlert WHERE watchedUserId = :userId")
    suspend fun deleteByWatchedUserId(userId: Long)

    @Query("DELETE FROM NoShowAlert WHERE waypointId = :waypointId")
    suspend fun deleteByWaypointId(waypointId: Long)

    /**
     * Mark fired only if the row has not fired yet, so two concurrent
     * scheduler runs cannot both claim it. Returns rows updated (0 or 1).
     */
    @Query("UPDATE NoShowAlert SET fired = 1 WHERE id = :id AND fired = 0")
    suspend fun markFired(id: Long): Int

    @Query("UPDATE NoShowAlert SET fired = 0 WHERE id = :id")
    suspend fun rearm(id: Long): Int
}
