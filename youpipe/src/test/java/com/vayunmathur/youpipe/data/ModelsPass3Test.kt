package com.vayunmathur.youpipe.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.defaultBackStack
import com.vayunmathur.youpipe.nativeext.AudioStream
import com.vayunmathur.youpipe.nativeext.ChannelInfo
import com.vayunmathur.youpipe.nativeext.Comment
import com.vayunmathur.youpipe.nativeext.CommentsPage
import com.vayunmathur.youpipe.nativeext.ItemsPage
import com.vayunmathur.youpipe.nativeext.NativeExtractorException
import com.vayunmathur.youpipe.nativeext.PlaylistInfo
import com.vayunmathur.youpipe.nativeext.SearchResult
import com.vayunmathur.youpipe.nativeext.StreamInfo
import com.vayunmathur.youpipe.nativeext.StreamKind
import com.vayunmathur.youpipe.nativeext.Thumbnail
import com.vayunmathur.youpipe.nativeext.VideoStream
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.castRequestSize
import com.vayunmathur.youpipe.ui.formatTempo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: pure model/format/navigation behaviour outside the player UI. */
@RunWith(AndroidJUnit4::class)
class ModelsPass3Test {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun castRequestSize_evenDimensionsAndFallback() {
        assertEquals(1280 to 720, castRequestSize(uiStream(1280, 720), 1f))
        assertEquals(1278 to 718, castRequestSize(uiStream(1279, 719), 1f))
        assertEquals(2 to 2, castRequestSize(uiStream(1, 1), 1f))
        val (w, h) = castRequestSize(uiStream(0, 0), 16f / 9f)
        assertTrue(w > 0 && h > 0 && w % 2 == 0 && h % 2 == 0)
    }

    @Test
    fun formatTempo_wholeAndFractional() {
        assertEquals("2", formatTempo(2.0f))
        assertEquals("1", formatTempo(1.0f))
        assertEquals("1.5", formatTempo(1.5f))
        assertEquals("0.25", formatTempo(0.25f))
        assertEquals("1.75", formatTempo(1.75f))
    }

    @Test
    fun defaultBackStack_routesPerKey() {
        assertEquals(listOf(Route.Main(0)), defaultBackStack(null))
        assertEquals(listOf(Route.Main(0)), defaultBackStack("home"))
        assertEquals(listOf(Route.Main(0)), defaultBackStack("unknown-page"))
        assertEquals(listOf(Route.Main(1)), defaultBackStack("subscriptions"))
        assertEquals(listOf(Route.Main(2)), defaultBackStack("history"))
        assertEquals(listOf(Route.Main(3)), defaultBackStack("downloads"))
        assertEquals(listOf(Route.Main(3)), defaultBackStack("saved"))
        assertEquals(listOf(Route.Main(4)), defaultBackStack("settings"))
        // All-subscriptions seeds the subscriptions list under the feed so
        // Back returns to the list rather than exiting.
        assertEquals(
            listOf(Route.Main(1), Route.SubscriptionVideosPage(null)),
            defaultBackStack("all_subscriptions"),
        )
    }

    @Test
    fun route_valuesAreDistinct() {
        assertEquals(Route.VideoPage(7), Route.VideoPage(7))
        assertNotEquals(Route.VideoPage(7), Route.VideoPage(8))
        assertEquals(Route.Downloads, Route.Downloads)
        assertEquals("UC1", Route.ChannelPage("UC1").channelID)
        assertEquals(null, Route.SubscriptionVideosPage(null).category)
        assertEquals(3L, Route.PlaylistDetail(3).playlistId)
        assertEquals(9L, Route.AddToWatchLater(9).videoID)
        val add = Route.AddToPlaylist(5)
        assertEquals(5L, add.videoID)
        assertTrue(add.includeWatchLater)
    }

    @Test
    fun historyVideo_fromVideoDataStampsNow() {
        val info = VideoInfo(
            name = "v", videoID = 77, duration = 10, views = 1,
            uploadDate = Instant.fromEpochSeconds(1), thumbnailURL = "", author = "a",
        )
        val row = com.vayunmathur.youpipe.data.HistoryVideo.fromVideoData(info, progress = 4_000)
        assertEquals(77L, row.id)
        assertEquals(4_000L, row.progress)
        assertEquals(info, row.videoItem)
        assertTrue(row.timestamp > Instant.fromEpochSeconds(1))
    }

    @Test
    fun playlist_withPositionCopies() {
        val item = PlaylistItem(
            playlistId = 3,
            videoItem = VideoInfo("v", 1, 1, 1, Instant.fromEpochSeconds(0), "", "a"),
            position = 1.0,
            timestamp = Instant.fromEpochSeconds(0),
        )
        val moved = item.withPosition(2.5)
        assertEquals(2.5, moved.position)
        assertEquals(3L, moved.playlistId)
        val playlist = Playlist(name = "p", position = 0.0, mandatory = true)
        assertEquals(4.0, playlist.withPosition(4.0).position)
        assertTrue(playlist.withPosition(4.0).mandatory)
    }

    @Test
    fun nativeModels_constructCopyCompare() {
        val thumb = Thumbnail("https://i/1.jpg", 120, 90)
        assertEquals(thumb, thumb.copy())
        assertEquals("https://i/1.jpg", thumb.component1())

        val video = VideoStream(url = "u", itag = 137, codec = "av01", width = 1920, height = 1080)
        assertEquals(137L, video.itag)
        assertEquals(video, video.copy())
        assertNotEquals(video, video.copy(itag = 138))

        val audio = AudioStream(url = "a", codec = "opus", sampleRate = 48_000, trackId = "en.4")
        assertEquals("en.4", audio.trackId)
        assertEquals(2L, audio.channels)

        val streamInfo = StreamInfo(
            id = "abc", url = "https://youtu.be/abc", name = "T",
            videoStreams = listOf(video), audioStreams = listOf(audio),
        )
        assertEquals("abc", streamInfo.id)
        assertEquals(listOf(video), streamInfo.videoStreams)

        val channel = ChannelInfo(id = "UC", url = "u", name = "Chan", verified = true)
        assertTrue(channel.verified)
        val playlist = PlaylistInfo(id = "PL", url = "u", name = "List", streamCount = 12)
        assertEquals(12L, playlist.streamCount)

        val comment = Comment(id = "c1", text = "hi", authorName = "Fan", likeCount = 5)
        assertEquals(5L, comment.likeCount)
        val page = CommentsPage(comments = listOf(comment), nextPageToken = "tok")
        assertEquals("tok", page.nextPageToken)

        val search = SearchResult(items = emptyList(), nextPageToken = null)
        assertTrue(search.items.isEmpty())
        val items = ItemsPage(items = emptyList())
        assertNull(items.nextPageToken)

        assertEquals("kaput", NativeExtractorException("kaput").message)
        assertEquals(
            listOf(StreamKind.VIDEO, StreamKind.LIVE, StreamKind.POST_LIVE),
            StreamKind.entries,
        )
    }

    @Test
    fun subscription_serializationRoundTrips() {
        val sub = Subscription(id = 3, name = "Chan", channelID = "UC3", avatarURL = "https://a")
        val json = Json.encodeToString(Subscription.serializer(), sub)
        assertEquals(sub, Json.decodeFromString(Subscription.serializer(), json))
        val cat = SubscriptionCategory(subscriptionID = 3, category = "News", id = 9)
        assertEquals(9L, cat.id)
    }

    @Test
    fun remoteImageFetchers_rejectNonHttpWithoutNetwork() = runTest {
        // None of these may touch the network: non-HTTP inputs answer null.
        assertNull(RemoteImageFetchers.network("ftp://example.com/x.jpg", context))
        assertNull(RemoteImageFetchers.network("relative/path.jpg", context))
        assertNull(RemoteImageFetchers.network(12345, context))
        assertNull(RemoteImageFetchers.network(null, context))
        assertNull(
            RemoteImageFetchers.network(android.net.Uri.parse("file:///sdcard/x.jpg"), context),
        )
        assertNull(
            RemoteImageFetchers.network(android.net.Uri.parse("content://media/1"), context),
        )
    }
}

private fun uiStream(width: Int, height: Int) = com.vayunmathur.youpipe.ui.VideoStream(
    url = "u", width = width, height = height, bitrate = 1, fps = 30,
    quality = "720p", codec = "vp09", size = 1,
)
