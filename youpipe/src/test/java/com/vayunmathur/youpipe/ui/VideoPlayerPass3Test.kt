package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithText
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.util.SponsorSegment
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Slice 2/3 pass 3: the full [VideoPlayer] composition without a media
 * session - the controller never connects under Robolectric, so the player
 * draws its no-controller surface and the whole controls stack around it.
 */
@org.robolectric.annotation.Config(shadows = [ShadowPendingMediaControllerBuilder::class])
@RunWith(AndroidJUnit4::class)
class VideoPlayerPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        CastPlayback.close()
        // One database (and ViewModel) for the whole class: a ViewModel's
        // flow collectors outlive any single test on the paused main looper,
        // and closing the database under a zombie collector crashes whichever
        // test happens to idle the looper next.
        if (!::db.isInitialized) {
            db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java)
                .allowMainThreadQueries().build()
            vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
        }
    }

    @AfterTest
    fun tearDown() {
        CastPlayback.close()
    }

    private val info = VideoInfo(
        name = "Player Video", videoID = 4242, duration = 120, views = 10,
        uploadDate = Instant.fromEpochSeconds(1_700_000_000),
        thumbnailURL = "", author = "Player Chan",
    )

    private val videoStreams = listOf(
        VideoStream("https://v/360", 640, 360, 800_000, 30, "360p", "vp09", 5_000),
        VideoStream("https://v/720", 1280, 720, 2_000_000, 30, "720p", "vp09", 9_000),
        VideoStream("https://v/2160", 3840, 2160, 8_000_000, 30, "2160p", "av01", 40_000),
    )

    private val audioStreams = listOf(
        AudioStream("https://a/en-low", 64_000, "en", "opus", 1_000, displayName = "English"),
        AudioStream("https://a/en", 128_000, "en", "opus", 2_000, displayName = "English"),
        AudioStream("https://a/es", 96_000, "es", "opus", 1_500, displayName = "Spanish"),
    )

    @Test
    fun videoPlayer_rendersControlsAroundMissingController() {
        var fullscreen = false
        rule.setContent {
            VideoPlayer(
                ypvm = vm, videoInfo = info,
                videoStreams = videoStreams, audioStreams = audioStreams,
                subtitles = listOf(SubtitleTrack("https://s/en", "en", "English", false, "text/vtt")),
                segments = listOf(VideoChapter(0, "Intro", null), VideoChapter(60, "Middle", null)),
                isFullscreen = false,
                onFullscreenChange = { fullscreen = it },
            )
        }
        // The default stream is the best at-or-below the (Robolectric) display
        // height; whichever chip that is, the quality control is composed.
        rule.onNodeWithText("360p").assertIsDisplayed()
    }

    @Test
    fun videoPlayer_emptyStreamsRenderNothing() {
        rule.setContent {
            VideoPlayer(
                ypvm = vm, videoInfo = info,
                videoStreams = emptyList(), audioStreams = emptyList(),
                subtitles = emptyList(), segments = emptyList(),
                isFullscreen = false, onFullscreenChange = {},
            )
        }
        // Early return: no quality chip is ever composed.
        rule.onNodeWithText("360p").assertDoesNotExist()
    }

    @Test
    fun playbackState_selectsStreamsAndLanguages() {
        lateinit var state: VideoPlayerPlaybackState
        rule.setContent {
            state = rememberVideoPlayerPlaybackState(
                ypvm = vm, videoInfo = info,
                videoStreams = videoStreams, audioStreams = audioStreams,
                subtitles = emptyList(),
            )
        }
        // Robolectric's smaller display edge picks at-or-below it (360p here,
        // but the contract asserted is only: a real stream from the list).
        assertTrue(state.currentVideoStream in videoStreams)
        assertEquals(listOf("en", "es"), state.languages)
        assertEquals("English", state.languageEntries.first { it.first == "en" }.second)
        // Best English audio is picked by default.
        assertEquals(128_000, state.currentAudioStream?.bitrate)
        assertEquals("en", state.language)
        assertEquals(2, state.audioStreamOptions.size)
        assertEquals(0L, state.currentPosition)
        assertEquals(false, state.isPlaying)
    }

    @Test
    fun selectDefaultStream_capRules() {
        assertEquals(null, selectDefaultVideoStream(emptyList(), 1080))
        assertEquals("360p", selectDefaultVideoStream(videoStreams, 0)?.quality) // first(), per the code
        assertEquals("720p", selectDefaultVideoStream(videoStreams, 720)?.quality)
        assertEquals("360p", selectDefaultVideoStream(videoStreams, 400)?.quality)
        assertEquals("360p", selectDefaultVideoStream(videoStreams, 100)?.quality)
        assertEquals("2160p", selectDefaultVideoStream(videoStreams, 5000)?.quality)
    }

    @Test
    fun transportState_flowsThroughCastPlayback() {
        CastPlayback.update {
            it.copy(positionMs = 12_345, durationMs = 120_000, playing = true, speed = 1.5f)
        }
        val transport = CastPlayback.transport.value
        assertEquals(12_345L, transport.positionMs)
        assertEquals(120_000L, transport.durationMs)
        assertTrue(transport.playing)
        assertEquals(1.5f, transport.speed)
        assertEquals(CastPlayback.State.Idle, CastPlayback.state.value)

        CastPlayback.markConnecting()
        assertEquals(CastPlayback.State.Connecting, CastPlayback.state.value)
        CastPlayback.close()
        assertEquals(CastPlayback.State.Idle, CastPlayback.state.value)
        // Close leaves the transport snapshot alone except next/previous.
        assertEquals(12_345L, CastPlayback.transport.value.positionMs)
        assertEquals(false, CastPlayback.transport.value.hasNext)
        CastPlayback.update { CastPlayback.Transport() }
    }

    @Test
    fun videoPlayer_tapTogglesControls() {
        // Controls only auto-hide while playing; paused keeps them up by design.
        CastPlayback.update { it.copy(playing = true) }
        rule.setContent {
            VideoPlayer(
                ypvm = vm, videoInfo = info,
                videoStreams = videoStreams, audioStreams = audioStreams,
                subtitles = emptyList(), segments = emptyList(),
                isFullscreen = false, onFullscreenChange = {},
            )
        }
        rule.onNodeWithText("360p").assertIsDisplayed()
        rule.onRoot().performTouchInput { click(center) }
        rule.waitForIdle()
        rule.onNodeWithText("360p").assertDoesNotExist()
        rule.onRoot().performTouchInput { click(center) }
        rule.waitForIdle()
        rule.onNodeWithText("360p").assertIsDisplayed()
        CastPlayback.update { CastPlayback.Transport() }
    }

    @Test
    fun videoPlayer_fullscreenVariantRenders() {
        var fullscreen = true
        rule.setContent {
            VideoPlayer(
                ypvm = vm, videoInfo = info,
                videoStreams = videoStreams, audioStreams = audioStreams,
                subtitles = emptyList(), segments = emptyList(),
                isFullscreen = fullscreen,
                onFullscreenChange = { fullscreen = it },
            )
        }
        rule.onNodeWithText("360p").assertIsDisplayed()
    }

    @Test
    fun sponsorSegments_filterByEnabledCategories() {
        val vm2 = vm
        lateinit var state: VideoPlayerPlaybackState
        // SponsorBlock is on by default for the default categories; segments
        // outside them must not survive into the state.
        kotlinx.coroutines.runBlocking {
            com.vayunmathur.library.util.DataStoreUtils.getInstance(app)
                .addStringToSetIfAbsent("sponsorblock_categories", "sponsor")
        }
        vm2.videoStateMutable.value = vm2.videoState.value.copy(
            sponsorSegments = listOf(
                SponsorSegment("sponsor", listOf(1f, 2f), "in"),
                SponsorSegment("unknowncat", listOf(3f, 4f), "out"),
            ),
        )
        rule.setContent {
            state = rememberVideoPlayerPlaybackState(
                ypvm = vm, videoInfo = info,
                videoStreams = videoStreams, audioStreams = audioStreams,
                subtitles = emptyList(),
            )
        }
        assertEquals(listOf("in"), state.sponsorSegments.map { it.uuid })
    }
}

/**
 * A MediaController.Builder whose connect never lands: under Robolectric the
 * simulated bind hands the controller a null ComponentName and it NPEs inside
 * its own callback, which is a shadow artifact, not player behaviour.
 */
@org.robolectric.annotation.Implements(androidx.media3.session.MediaController.Builder::class)
class ShadowPendingMediaControllerBuilder {

    @org.robolectric.annotation.Implementation
    fun buildAsync(): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> =
        com.google.common.util.concurrent.SettableFuture.create()
}
