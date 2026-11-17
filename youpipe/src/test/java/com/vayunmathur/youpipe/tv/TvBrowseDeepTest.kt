package com.vayunmathur.youpipe.ui.tv

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.library.tv.MaTvTheme
import com.vayunmathur.library.tv.TvAccent
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.PlaylistItem
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionCategory
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.data.SubscriptionVideo
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.Comment
import com.vayunmathur.youpipe.ui.SubtitleTrack
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoData
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows

/** Deep TV browse: loaded video detail actions, history clear, downloads open, channel/playlist. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TvBrowseDeepTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel
    private var stack: NavBackStack<TvRoute>? = null
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var playlistId: Long = -1

    @BeforeTest fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        TvLoadedVideo.id = null
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java).allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
        runBlocking { seed() }
    }
    @AfterTest fun tearDown() { TvLoadedVideo.id = null; Thread.sleep(100) }

    private suspend fun seed() {
        val subId = db.subscriptionDao().upsert(Subscription(name = "Seeded Chan", channelID = "UCseed", avatarURL = ""))
        db.subscriptionCategoryDao().upsertAll(listOf(SubscriptionCategory(subId, "News")))
        db.subscriptionVideoDao().upsertAll(listOf(SubscriptionVideo(id = 42, name = "Seeded Video", duration = 60, views = 3, uploadDate = Instant.fromEpochSeconds(1_700_000_000), thumbnailURL = "", author = "Seeded Chan", channelID = subId)))
        db.historyVideoDao().upsert(HistoryVideo(progress = 30_000, videoItem = VideoInfo("Watched One", 55, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan"), timestamp = Instant.fromEpochSeconds(1_700_100_000)))
        playlistId = db.playlistDao().upsert(Playlist(name = "Music Mix"))
        db.playlistItemDao().upsert(PlaylistItem(playlistId = playlistId, videoItem = VideoInfo("Playlist Song", 66, 200, 4, Instant.fromEpochSeconds(1_650_000_000), "", "Seeded Chan"), timestamp = Instant.fromEpochSeconds(1_700_200_000)))
        db.downloadedVideoDao().upsert(DownloadedVideo(id = 77, videoItem = VideoInfo("Offline Video", 77, 300, 8, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan"), filePath = "/dl/77.mp4", timestamp = Instant.fromEpochSeconds(1_700_300_000)))
    }
    private fun awaitText(t: String) = rule.waitUntilExactlyOneExists(hasText(t), 15_000)
    private fun clickText(t: String) {
        val node = rule.onNode(hasText(t))
        val cfg = node.fetchSemanticsNode().config
        val a = runCatching { cfg[androidx.compose.ui.semantics.SemanticsActions.OnClick].action }.getOrNull()
        if (a != null) assertTrue(a.invoke() == true, "click $t") else node.performClick()
    }
    private fun clickNodeAt(idx: Int) {
        val node = rule.onAllNodes(hasClickAction())[idx]
        runCatching { node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.RequestFocus].action?.invoke() }
        node.performKeyInput { pressKey(Key.Enter) }
        node.performClick()
    }
    private fun clickCard() {
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        val idx = (0 until count).first { rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()[it].boundsInRoot.top > 80f }
        clickNodeAt(idx)
    }
    private fun clickLastCard() {
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        clickNodeAt(count - 1)
    }
    private fun themed(content: @androidx.compose.runtime.Composable (NavBackStack<TvRoute>) -> Unit) {
        rule.setContent { val bs = rememberNavBackStack<TvRoute>(TvRoute.Main(TV_RAIL_HOME)); stack = bs; MaTvTheme(TvAccent.Red) { content(bs) } }
    }
    private fun presetLoaded() {
        TvLoadedVideo.id = 77
        vm.videoStateMutable.value = YouPipeViewModel.VideoState(
            data = VideoData("Offline Video", 300, 77, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan", "UCseed", "", "A long description"),
            videoStreams = listOf(VideoStream("/dl/77.mp4", 1920, 1080, 0, 30, "Downloaded", "avc", 0)),
            audioStreams = listOf(AudioStream("/dl/77.m4a", 128, "en", "opus", 0, displayName = "English")),
            segments = listOf(VideoChapter(0, "Intro", null), VideoChapter(60_000, "Main Part", null)),
            subtitles = listOf(SubtitleTrack("https://c/en", "en", "English", false, "text/vtt")),
            comments = listOf(Comment("Great clip", "Viewer One", 12, 0)),
            relatedVideos = listOf(VideoInfo("Related One", 88, 90, 10, Instant.fromEpochSeconds(1_650_000_000), "", "Seeded Chan")),
        )
    }

    @Test fun tvVideo_loaded_playNavigatesAndSectionsRender() {
        presetLoaded(); themed { bs -> TvVideoPage(bs, vm, 77) }
        awaitText("Offline Video")
        clickText("Play")
        rule.waitUntil(5_000) { stack?.backStack?.lastOrNull() == TvRoute.VideoPlayerRoute(77) }
        assertEquals(TvRoute.VideoPlayerRoute(77), stack?.backStack?.lastOrNull())
    }
    @Test fun tvVideo_loaded_descriptionDialogOpensAndCloses() {
        presetLoaded(); themed { bs -> TvVideoPage(bs, vm, 77) }
        awaitText("Offline Video"); clickText("Description"); awaitText("Close")
        clickText("Close"); rule.waitForIdle()
    }
    @Test fun tvVideo_loaded_goToChannelAndAddToPlaylistNavigate() {
        presetLoaded(); themed { bs -> TvVideoPage(bs, vm, 77) }
        awaitText("Offline Video"); clickText("Go to channel")
        assertEquals(TvRoute.Channel("UCseed"), stack?.backStack?.lastOrNull())
    }
    @Test fun tvVideo_loaded_addToPlaylistNavigates() {
        presetLoaded(); themed { bs -> TvVideoPage(bs, vm, 77) }
        awaitText("Offline Video"); clickText("Add to playlist")
        assertEquals(TvRoute.AddToPlaylist(77, false), stack?.backStack?.lastOrNull())
    }
    @Test fun tvVideo_loaded_relatedCardPlaysRelated() {
        presetLoaded(); themed { bs -> TvVideoPage(bs, vm, 77) }
        awaitText("Related One"); clickLastCard()
        assertEquals(TvRoute.VideoPlayerRoute(88), stack?.backStack?.lastOrNull())
    }
    @Test fun tvVideo_loaded_subscribeTogglesSubscription() {
        presetLoaded(); themed { bs -> TvVideoPage(bs, vm, 77) }
        awaitText("Offline Video")
        // Seeded Chan already subscribed (UCseed) -> button is Unsubscribe; clicking removes it.
        clickText("Unsubscribe")
        val d = System.currentTimeMillis() + 8_000; var names = listOf<String>()
        while (System.currentTimeMillis() < d) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(30); names = runBlocking { db.subscriptionDao().getAll().map { it.channelID } }; if ("UCseed" !in names) break }
        assertTrue("UCseed" !in names, names.toString())
    }
    @Test fun tvHistory_clearAllDialogClearsHistory() {
        themed { bs -> TvHistoryPage(bs, vm) }
        awaitText("Watched One"); clickText("Clear History")
        clickText("Clear")
        val d = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < d) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(30); if (runBlocking { db.historyVideoDao().getAll() }.isEmpty()) break }
        assertTrue(runBlocking { db.historyVideoDao().getAll() }.isEmpty())
    }
    @Test fun tvHistory_openCardPlaysVideo() {
        themed { bs -> TvHistoryPage(bs, vm) }
        awaitText("Watched One"); clickCard()
        assertEquals(TvRoute.VideoPlayerRoute(55), stack?.backStack?.lastOrNull())
    }
    @Test fun tvDownloads_openDownloadedPlaysVideo() {
        themed { bs -> TvDownloadsPage(bs, vm) }
        awaitText("Offline Video"); clickCard()
        assertEquals(TvRoute.VideoPlayerRoute(77), stack?.backStack?.lastOrNull())
    }
    @Test fun tvPlaylistDetail_openSongPlays() {
        themed { bs -> TvPlaylistDetailPage(bs, vm, playlistId) }
        awaitText("Playlist Song"); clickCard()
        assertEquals(TvRoute.VideoPlayerRoute(66), stack?.backStack?.lastOrNull())
    }
    @Test fun tvDefaultBackStack_allKeys() {
        assertEquals(listOf(TvRoute.Main(TV_RAIL_SUBSCRIPTIONS)), tvDefaultBackStack("subscriptions"))
        assertEquals(listOf(TvRoute.Main(TV_RAIL_HISTORY)), tvDefaultBackStack("history"))
        assertEquals(listOf(TvRoute.Main(TV_RAIL_SAVED)), tvDefaultBackStack("downloads"))
        assertEquals(listOf(TvRoute.Main(TV_RAIL_SAVED)), tvDefaultBackStack("saved"))
        assertEquals(listOf(TvRoute.Main(TV_RAIL_SETTINGS)), tvDefaultBackStack("settings"))
        assertEquals(listOf(TvRoute.Main(TV_RAIL_HOME)), tvDefaultBackStack(null))
        assertEquals(listOf(TvRoute.Main(TV_RAIL_HOME)), tvDefaultBackStack("home"))
    }
    @Test fun tvSharedHelpers_subtitleAndDuration() {
        assertEquals(null, tvDurationBadge(-1)); assertEquals("10:00", tvDurationBadge(600))
        val row = TvVideoRow(com.vayunmathur.youpipe.platform.VideoRowState(1, "T", "", "Chan", "1K views", "chan"), VideoInfo("T", 1, 60, 1000, Instant.fromEpochSeconds(1_600_000_000), "", "Chan"))
        assertTrue(tvVideoSubtitle(row)?.contains("Chan") == true)
    }
}
