package com.vayunmathur.youpipe.data

import kotlinx.coroutines.flow.Flow

/** Watch-history and downloaded-video persistence, split out of [SubscriptionRepository]. */
class HistoryStore(
    private val historyVideoDao: HistoryVideoDao,
    private val downloadedVideoDao: DownloadedVideoDao,
) {
    /** The history entry with [id] as a flow. */
    fun historyById(id: Long): Flow<HistoryVideo?> = historyVideoDao.getByIdFlow(id)

    /** The downloaded video with [id] as a flow. */
    fun downloadedById(id: Long): Flow<DownloadedVideo?> = downloadedVideoDao.getByIdFlow(id)

    /** All history entries. */
    suspend fun getAllHistoryVideos(): List<HistoryVideo> = historyVideoDao.getAll()

    /** Inserts or replaces one history entry. */
    suspend fun upsertHistoryVideo(value: HistoryVideo): Long = historyVideoDao.upsert(value)

    /** Inserts or replaces history entries. */
    suspend fun upsertHistoryVideos(values: List<HistoryVideo>) = historyVideoDao.upsertAll(values)

    /** Deletes history entries by id. */
    suspend fun deleteHistoryVideosByIds(ids: List<Long>) = historyVideoDao.deleteByIds(ids)

    /** Deletes one history entry. */
    suspend fun deleteHistoryVideo(value: HistoryVideo) = historyVideoDao.delete(value)

    /** Clears all history. */
    suspend fun clearAllHistory() = historyVideoDao.clearAll()

    /** Inserts or replaces one downloaded video. */
    suspend fun upsertDownloadedVideo(value: DownloadedVideo): Long = downloadedVideoDao.upsert(value)

    /** Deletes one downloaded video. */
    suspend fun deleteDownloadedVideo(value: DownloadedVideo): Int = downloadedVideoDao.delete(value)
}
