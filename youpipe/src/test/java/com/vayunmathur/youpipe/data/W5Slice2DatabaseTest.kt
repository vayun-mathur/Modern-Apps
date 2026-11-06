package com.vayunmathur.youpipe.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Slice 2/3 (w5): SubscriptionDatabase DAOs in the A-L slice — DownloadedVideo,
 * CachedRelatedVideo, HistoryVideo, ChannelPreference, KeywordPreference.
 * Robolectric in-memory database. Asserts upsert/get/delete/clear flows.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice2DatabaseTest {

    private lateinit var db: SubscriptionDatabase

    private fun video(id: Long, name: String = "v$id") = VideoInfo(
        name = name,
        videoID = id,
        duration = 100,
        views = 1000,
        uploadDate = Instant.fromEpochSeconds(1_700_000_000L),
        thumbnailURL = "https://example.com/$id.jpg",
        author = "author",
    )

    @BeforeTest
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SubscriptionDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @AfterTest
    fun close() {
        db.close()
    }

    @Test
    fun downloadedVideo_upsertGetDelete() = runTest {
        val dao = db.downloadedVideoDao()
        val row = DownloadedVideo(
            id = 7, videoItem = video(7), filePath = "/dl/7.mp4",
            timestamp = Instant.fromEpochSeconds(10),
        )
        dao.upsert(row)
        assertEquals("/dl/7.mp4", dao.getByIdFlow(7).first()?.filePath)
        assertEquals(listOf(7L), dao.getAllFlow().first().map { it.id })
        assertEquals(1, dao.delete(row))
        assertEquals(emptyList(), dao.getAllFlow().first())
    }

    @Test
    fun cachedRelated_upsertAllGetDeleteOlder() = runTest {
        val dao = db.cachedRelatedVideoDao()
        val old = Instant.fromEpochSeconds(100)
        val fresh = Instant.fromEpochSeconds(9_999_999_999L)
        dao.upsertAll(
            listOf(
                CachedRelatedVideo(sourceVideoID = 1, videoItem = video(11), cachedAt = old),
                CachedRelatedVideo(sourceVideoID = 1, videoItem = video(12), cachedAt = fresh),
            ),
        )
        assertEquals(2, dao.getAll().size)
        dao.deleteOlderThan(Instant.fromEpochSeconds(1000))
        val remaining = dao.getAll()
        assertEquals(1, remaining.size)
        assertEquals(12L, remaining[0].videoItem.videoID)
        assertEquals(1, dao.getAllFlow().first().size)
    }

    @Test
    fun historyVideo_crud() = runTest {
        val dao = db.historyVideoDao()
        val row = HistoryVideo.fromVideoData(video(21), progress = 5_000)
        assertEquals(21L, row.id)
        assertEquals(5_000L, row.progress)
        dao.upsert(row)
        dao.upsertAll(listOf(HistoryVideo.fromVideoData(video(22), 0)))
        assertEquals(2, dao.getAll().size)
        assertEquals(2, dao.getAllFlow().first().size)
        assertEquals(5_000L, dao.getByIdFlow(21).first()?.progress)
        dao.deleteByIds(listOf(21))
        assertEquals(listOf(22L), dao.getAll().map { it.id })
        dao.delete(dao.getAll().first())
        assertEquals(emptyList(), dao.getAll())
        dao.upsert(row)
        dao.clearAll()
        assertEquals(emptyList(), dao.getAll())
    }

    @Test
    fun channelPreference_crud() = runTest {
        val dao = db.channelPreferenceDao()
        assertNull(dao.get("somechannel"))
        dao.upsert(ChannelPreference("somechannel", multiplier = 2.0, pinned = true))
        assertEquals(2.0, dao.get("somechannel")!!.multiplier, 1e-9)
        assertEquals(1, dao.getAll().size)
        dao.upsert(ChannelPreference("somechannel", multiplier = 0.5, blocked = true))
        assertTrue(dao.get("somechannel")!!.blocked)
        dao.delete("somechannel")
        assertNull(dao.get("somechannel"))
        dao.upsert(ChannelPreference("a"))
        dao.clearAll()
        assertEquals(emptyList(), dao.getAll())
    }

    @Test
    fun keywordPreference_crud() = runTest {
        val dao = db.keywordPreferenceDao()
        dao.upsert(KeywordPreference("spoiler"))
        dao.upsert(KeywordPreference("ad", muted = false))
        assertEquals(2, dao.getAll().size)
        dao.delete("spoiler")
        assertEquals(listOf("ad"), dao.getAll().map { it.keyword })
        assertEquals(false, dao.getAll().first().muted)
        dao.clearAll()
        assertEquals(emptyList(), dao.getAll())
    }
}
