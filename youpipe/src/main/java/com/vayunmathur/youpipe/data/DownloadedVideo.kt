package com.vayunmathur.youpipe.data

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** Immutable value describing downloaded video. */
@Entity
data class DownloadedVideo(
    @PrimaryKey override val id: Long, // video id
    @Embedded val videoItem: VideoInfo,
    val filePath: String,
    val audioPath: String? = null,
    val timestamp: Instant
): DatabaseItem

/** Contract for downloaded video dao. */
@Dao
interface DownloadedVideoDao {
    @Query("SELECT * FROM DownloadedVideo")
    fun getAllFlow(): Flow<List<DownloadedVideo>>

    @Query("SELECT * FROM DownloadedVideo WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<DownloadedVideo?>

    @Upsert
    suspend fun upsert(value: DownloadedVideo): Long

    @Delete
    suspend fun delete(value: DownloadedVideo): Int
}
