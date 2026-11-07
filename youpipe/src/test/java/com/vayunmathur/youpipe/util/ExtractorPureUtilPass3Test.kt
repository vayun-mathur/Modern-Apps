package com.vayunmathur.youpipe.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
class ExtractorPureUtilPass3Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun initWork() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
    }

    private fun video(id: Long, name: String = "v$id") = VideoInfo(
        name = name, videoID = id, duration = 60, views = 100,
        uploadDate = Instant.fromEpochSeconds(1_700_000_000L),
        thumbnailURL = "https://example.com/$id.jpg", author = "author",
    )

    @Test fun videoIdCodecRoundTrips() {
        assertEquals("dQw4w9WgXcQ", decodeVideoID(encodeVideoID("dQw4w9WgXcQ")))
        val id = encodeVideoID("dQw4w9WgXcQ")
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", videoIDtoURL(id))
        assertEquals(id, videoURLtoID("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(id, videoURLtoID("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"))
        assertFailsWith<Exception> { videoURLtoID("https://www.youtube.com/feed") }
    }
    @Test fun parseSharedVideoIdForms() {
        val expected = encodeVideoID("dQw4w9WgXcQ")
        assertEquals(expected, parseSharedVideoId("check this https://www.youtube.com/watch?v=dQw4w9WgXcQ lol"))
        assertEquals(expected, parseSharedVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(expected, parseSharedVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(expected, parseSharedVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertNull(parseSharedVideoId("no video here"))
        assertNull(parseSharedVideoId("https://example.com/watch?v=short"))
        assertNull(parseSharedVideoId(""))
    }
    @Test fun channelUrlHelpers() {
        assertEquals("UC12345", channelURLtoID("https://www.youtube.com/channel/UC12345"))
        assertEquals("@handle", channelURLtoID("https://www.youtube.com/@handle"))
        assertEquals("https://www.youtube.com/channel/UC12345", channelIDtoURL("UC12345"))
        assertEquals("https://www.youtube.com/@handle", channelIDtoURL("@handle"))
    }
    @Test fun decodeHtmlStripsTagsAndEntities() {
        assertEquals("Hello & welcome", "Hello &amp; <b>welcome</b>".decodeHtml().trim())
        assertEquals("plain", "plain".decodeHtml())
        assertTrue("a<br>b".decodeHtml().contains("a"))
    }
    @Test fun sponsorSegmentBoundsTruncate() {
        val seg = SponsorSegment("sponsor", listOf(1.5f, 9.999f), "u")
        assertEquals(1500L, seg.start)
        assertEquals(9999L, seg.end)
        assertEquals("sponsor", seg.category)
    }
    @Test fun downloadManagerLifecycleWithWorkManager() {
        val info = video(987001L)
        DownloadManager.enqueueDownload(context, info, "https://example.com/v.mp4", null)
        assertEquals(0.0, DownloadManager.activeDownloads.value[987001L]?.progress)
        DownloadManager.updateProgress(987001L, 0.4)
        assertEquals(0.4, DownloadManager.activeDownloads.value[987001L]?.progress)
        // work was enqueued under the unique name (not run: WorkManager stays queued)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("download_987001").get()
        assertTrue(infos.isNotEmpty())
        DownloadManager.finishDownload(987001L)
        assertNull(DownloadManager.activeDownloads.value[987001L])
        // cancel on a fresh entry removes it and cancels the work
        DownloadManager.enqueueDownload(context, video(987002L), "https://example.com/a.mp4", "https://example.com/a.m4a")
        DownloadManager.cancelDownload(context, 987002L)
        assertNull(DownloadManager.activeDownloads.value[987002L])
        assertEquals(DownloadStatus(info, 0.0).videoInfo, info)
    }
    @Test fun subscriptionBackupDecodeVariants() {
        assertNull(decodeSubscriptionBackup("not json at all"))
        assertNull(decodeSubscriptionBackup(""))
        assertEquals(emptyList(), decodeSubscriptionBackup("[]"))
        val subs = decodeSubscriptionBackup(
            """[{"id":1,"name":"A","channelID":"UC1","avatarURL":""},{"id":2,"name":"B","channelID":"UC2","avatarURL":"https://x"}]""",
        )
        assertEquals(listOf("A", "B"), subs!!.map { it.name })
        assertEquals(Subscription(name = "A", channelID = "UC1", avatarURL = "").channelID, subs[0].channelID)
    }
    @Test fun myDownloaderFailsFastOnClosedLoopback() = runTest {
        val downloader = MyDownloader()
        // 127.0.0.1:1 is never bound: the request fails without touching the real network.
        val error = runCatching {
            downloader.getStreaming("http://127.0.0.1:1/", null, null)
        }.exceptionOrNull()
        assertNotNull(error)
    }
    @Test fun uiStateDefaultsAndRows() {
        val search = SearchUiState()
        assertEquals("", search.query)
        assertTrue(search.results.isEmpty())
        assertTrue(!search.recommendationsLoading)
        val feed = SubscriptionFeedUiState()
        assertEquals(-1f, feed.fetchProgress)
        assertTrue(!feed.isLoading)
        val detail = VideoDetailUiState()
        assertTrue(!detail.loaded)
        assertNull(detail.downloadProgress)
        val row = VideoRowState(videoID = 7, title = "T", author = "Chan", channelKey = "chan", percentWatched = 0.5f)
        assertEquals(0.5f, row.percentWatched)
        assertTrue(!row.deArrowThumbnail)
        val videoRow = SearchResultRow.Video(row)
        assertEquals(row, videoRow.video)
        val channelRow = SearchResultRow.Channel("UC1", "Name", "https://a", "1K subscribers")
        assertEquals("UC1", channelRow.channelID)
        // actions Noop companions are inert
        SearchActions.Noop.setSearchQuery("x")
        assertTrue(!SearchActions.Noop.submitSearch())
        SubscriptionFeedActions.Noop.openVideo(1)
        VideoDetailActions.Noop.openChannel(); VideoDetailActions.Noop.download("v", null)
        assertEquals(SearchActions.Noop, SearchActions.Noop)
    }
}
