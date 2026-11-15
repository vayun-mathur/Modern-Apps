package com.vayunmathur.youpipe.data

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** Immutable value describing cached related video. */
@Entity
data class CachedRelatedVideo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceVideoID: Long,
    @Embedded val videoItem: VideoInfo,
    val cachedAt: Instant
)

/** Contract for cached related video dao. */
@Dao
interface CachedRelatedVideoDao {
    @Upsert
    suspend fun upsertAll(values: List<CachedRelatedVideo>)

    @Query("SELECT * FROM CachedRelatedVideo")
    fun getAllFlow(): Flow<List<CachedRelatedVideo>>

    @Query("SELECT * FROM CachedRelatedVideo")
    suspend fun getAll(): List<CachedRelatedVideo>

    @Query("DELETE FROM CachedRelatedVideo WHERE cachedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Instant)
}
