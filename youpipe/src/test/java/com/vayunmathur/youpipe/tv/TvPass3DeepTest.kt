package com.vayunmathur.youpipe.tv

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.media3.common.Player
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.Comment
import com.vayunmathur.youpipe.ui.SubtitleTrack
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoData
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Slice 3/3 (tv/) pass 3, deep paths: pages fed by a seeded
 * [YouPipeViewModel.VideoState] (via the internal mutable flow),
 * rememberTvPlayerChrome and its navigation/subscribe callbacks, the
 * full TvPlayer + overlays with sheets/cues open, downloads populated
 * from the DAO, video-support composables, and the D-pad map driving
 * a fake media3 Player through CastPlayback.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h800dp")
class TvPass3DeepTest {

    @get:Rule val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel

    @BeforeTest fun setUp() {
        db = TvPass3Support.database(context)
        vm = TvPass3Support.viewModel(context)
        runBlocking { TvPass3Support.wipe(db, vm) }
        CastPlayback.attachPlayer(null)
        TvDpadInput.overlayDismiss = null
    }

    private fun videoData() = VideoData(
        title = "Deep Video", views = 123_456, duration = 600,
        uploadDate = Clock.System.now(), thumbnailURL = "",
        author = "Chan A", authorURL = "chan-a", authorThumbnail = "",
        description = "A description with https://example.com inside",
    )

    private fun vStream(height: Int = 720) = VideoStream(
        url = "https://example.invalid/v$height", width = 1280, height = height,
        bitrate = 1_000_000, fps = 30, quality = "${height}p", codec = "vp9", size = 10_000_000,
    )

    private fun aStream() = AudioStream(
        url = "https://example.invalid/a", bitrate = 128_000, codec = "opus",
        language = "en", displayName = "English", size = 1_000_000,
    )

    private fun info(id: Long) = VideoInfo(
        name = "Video $id", videoID = id, duration = 300, views = 100,
        uploadDate = Clock.System.now(), thumbnailURL = "", author = "Chan A",
    )

    private fun seedVideoState() {
        vm.videoStateMutable.value = YouPipeViewModel.VideoState(
            data = videoData(),
            videoStreams = listOf(vStream(360), vStream(720)),
            audioStreams = listOf(aStream()),
            segments = listOf(VideoChapter(0, "Intro", null), VideoChapter(60, "Middle", null)),
            subtitles = listOf(
                SubtitleTrack("https://example.invalid/en", "en", "English", false, "text/vtt"),
            ),
            comments = listOf(Comment("Great!", "Viewer", 12, 0), Comment("Meh", "Critic", 1, 3)),
            relatedVideos = listOf(info(21), info(22)),
        )
    }

    @Test fun videoPageWithSeededState() {
        seedVideoState()
        rule.setContent { TvVideoPage(TvPass3Support.backStack(TvRoute.Video(7)), vm, 7) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun addToPlaylistWithSeededState() {
        runBlocking {
            db.playlistDao().upsert(Playlist(name = "Watch Later", mandatory = true))
            db.playlistDao().upsert(Playlist(name = "Favs"))
        }
        seedVideoState()
        val stack = TvPass3Support.backStack(TvRoute.AddToPlaylist(7))
        rule.setContent { TvAddToPlaylistPage(stack, vm, 7) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun chromeCallbacksNavigateAndSubscribe() {
        var chrome: TvPlayerChrome? = null
        val stack = TvPass3Support.backStack(TvRoute.VideoPlayerRoute(7))
        rule.setContent {
            chrome = rememberTvPlayerChrome(stack, vm, 7, videoData(), listOf(info(21)))
        }
        rule.waitForIdle()
        val c = chrome!!
        assertFalse(c.subscribed)
        assertTrue(c.byline.contains("Chan A"))
        c.onDetails()
        assertEquals(TvRoute.Video(7), stack.backStack.last())
        c.onChannel()
        assertEquals(TvRoute.Channel("chan-a"), stack.backStack.last())
        c.onSaveToPlaylist()
        assertEquals(TvRoute.AddToPlaylist(7, false), stack.backStack.last())
        c.onToggleSubscribe()
        var guard = 0
        while (vm.subscriptions.value.none { it.channelID == "chan-a" } && guard++ < 100) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            rule.waitForIdle()
            Thread.sleep(20)
        }
        assertTrue(vm.subscriptions.value.any { it.channelID == "chan-a" },
            "subs=${vm.subscriptions.value}")
        c.onWatchLater()
        c.onPlayRelated(21)
        assertEquals(TvRoute.VideoPlayerRoute(21), stack.backStack.last())
        // playVideo extension replaces/plays; playFromDetails too.
        stack.playVideo(33)
        assertEquals(TvRoute.VideoPlayerRoute(33), stack.backStack.last())
    }

    @Test fun overlaysWithSheetsAndCues() {
        seedVideoState()
        val state = vm.videoState.value
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                TvPlayerOverlays(
                    ypvm = vm, videoInfo = info(7),
                    videoStreams = state.videoStreams, audioStreams = state.audioStreams,
                    subtitles = state.subtitles, segments = state.segments,
                    transport = CastPlayback.Transport(positionMs = 30_000, durationMs = 600_000, playing = true),
                    cues = emptyList(), overlayVisible = true, controller = null,
                    languageEntries = listOf("en" to "English"), language = "en",
                    onLanguage = {}, selectedSubtitle = null, onSubtitle = {},
                    currentVideoStream = state.videoStreams.last(), onVideoStream = {},
                    showChapters = true, onShowChapters = {},
                    showQuality = true, onShowQuality = {},
                    showLanguage = true, onShowLanguage = {},
                    showCaptions = true, onShowCaptions = {},
                    showSpeed = true, onShowSpeed = {},
                    chrome = TvPlayerChrome(
                        byline = "b", avatarUrl = "", subscribed = false,
                        related = emptyList(), onToggleSubscribe = {}, onChannel = {},
                        onDetails = {}, onWatchLater = {}, onSaveToPlaylist = {}, onPlayRelated = {},
                    ),
                )
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun downloadsPopulated() {
        runBlocking {
            db.downloadedVideoDao().upsert(
                DownloadedVideo(11, info(11), "/tmp/v11.mp4", null, Clock.System.now()),
            )
            db.downloadedVideoDao().upsert(
                DownloadedVideo(12, info(12), "/tmp/v12.mp4", "/tmp/a12.m4a", Clock.System.now()),
            )
        }
        rule.setContent { TvDownloadsPage(TvPass3Support.backStack(TvRoute.Downloads), vm) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun videoSupportComposables() {
        var clicked = false
        var dismissed = false
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                TvChapterCard(VideoChapter(90, "Chapter One", null), onClick = { clicked = true })
                TvVideoComments(listOf(Comment("Nice video", "Alice", 5, 0)))
                TvDescriptionDialog("Title", "Desc text", onDismiss = { dismissed = true })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Chapter One").assertIsDisplayed()
        rule.onNodeWithText("Nice video", substring = true).assertIsDisplayed()
        // No WebView package under Robolectric: the probe must report it
        // unavailable rather than Ready.
        assertTrue(probeTvWebView(context) is TvWebViewStatus.Unavailable)
        assertFalse(clicked); assertFalse(dismissed)
        rule.onRoot().captureToImage()
    }

    @Test fun dpadDrivesFakePlayer() {
        val calls = mutableListOf<String>()
        val handler = InvocationHandler { _, method, args ->
            calls += method.name
            when (method.name) {
                "isPlaying" -> false
                "getCurrentPosition" -> 10_000L
                "getDuration" -> 600_000L
                else -> null
            }
        }
        val player = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(Player::class.java), handler,
        ) as Player
        CastPlayback.attachPlayer(player)
        CastPlayback.update { it.copy(positionMs = 10_000, durationMs = 600_000, playing = false) }
        TvTransportState.hide()

        // Media play key reaches the attached player.
        assertTrue(TvDpadInput.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)))
        assertTrue("play" in calls)
        // Hidden + CENTER reveals the transport.
        assertTrue(TvDpadInput.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))
        assertTrue(TvTransportState.visible.value)
        // Scrub right while hidden/seek: preview accumulates, commits on release.
        TvTransportState.hide()
        assertTrue(TvDpadInput.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertTrue(TvDpadInput.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)))
        CastPlayback.attachPlayer(null)
        assertEquals(null, CastPlayback.attachedPlayer)
    }
}
