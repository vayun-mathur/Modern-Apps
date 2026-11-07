package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import org.junit.Rule
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: full VideoPlayer menus, lock and fullscreen wiring. */
@OptIn(ExperimentalTestApi::class)
@org.robolectric.annotation.Config(shadows = [ShadowPendingMediaControllerBuilder::class])
@RunWith(AndroidJUnit4::class)
class PlayerMenusPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        CastPlayback.close()
        CastPlayback.update { CastPlayback.Transport() }
        if (!::db.isInitialized) {
            db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java)
                .allowMainThreadQueries().build()
            vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
        }
    }

    @AfterTest
    fun tearDown() {
        CastPlayback.close()
        CastPlayback.update { CastPlayback.Transport() }
    }

    private val info = VideoInfo(
        name = "Menu Video", videoID = 4243, duration = 120, views = 10,
        uploadDate = Instant.fromEpochSeconds(1_700_000_000), thumbnailURL = "", author = "Chan",
    )
    private val videoStreams = listOf(
        VideoStream("https://v/360", 640, 360, 800_000, 30, "360p", "vp09", 5_000),
        VideoStream("https://v/720", 1280, 720, 2_000_000, 30, "720p", "vp09", 9_000),
    )
    private val audioStreams = listOf(
        AudioStream("https://a/en", 128_000, "en", "opus", 2_000, displayName = "English"),
        AudioStream("https://a/es", 96_000, "es", "opus", 1_500, displayName = "Spanish"),
    )

    private fun composePlayer(fullscreen: Boolean = false, onFullscreenChange: (Boolean) -> Unit = {}) {
        rule.setContent {
            VideoPlayer(
                ypvm = vm, videoInfo = info,
                videoStreams = videoStreams, audioStreams = audioStreams,
                subtitles = listOf(SubtitleTrack("https://s/en", "en", "English subs", false, "text/vtt")),
                segments = listOf(VideoChapter(0, "Intro Part", null), VideoChapter(60, "Middle Part", null)),
                isFullscreen = fullscreen,
                onFullscreenChange = onFullscreenChange,
            )
        }
        rule.onNodeWithText("360p").assertExists()
    }

    /** Index of the clickable node at the bottom of the player, [fromRight] steps in from the right edge. */
    private fun bottomClickableIndex(fromRight: Int): Int {
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val bottom = nodes.withIndex().filter { it.value.boundsInRoot.top > root.top + root.height * 0.4f }
        val sorted = bottom.sortedByDescending { it.value.boundsInRoot.right }
        return sorted[fromRight].index
    }

    @Test
    fun qualityMenu_selectsAnotherStream() {
        composePlayer()
        rule.onNodeWithText("360p").performClick()
        rule.waitForIdle()
        // Menu items read "720p (30fps) vp9"; the chip alone reads "720p" afterwards.
        rule.onAllNodes(hasText("720p", substring = true))[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("720p").assertExists()
    }

    @Test
    fun languageMenu_selectsSpanish() {
        composePlayer()
        rule.onNodeWithText("English").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Spanish").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Spanish").assertExists()
    }

    @Test
    fun speedMenu_resetWritesSpeedToCastTransport() {
        CastPlayback.update { it.copy(speed = 1.5f) }
        composePlayer()
        rule.onNodeWithText("1.5x", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Reset").performClick()
        rule.waitForIdle()
        assertEquals(1f, CastPlayback.transport.value.speed)
    }

    @Test
    fun fullscreenButton_reportsChange() {
        var fullscreen: Boolean? = null
        composePlayer { fullscreen = it }
        rule.onAllNodes(hasClickAction())[bottomClickableIndex(0)].performClick()
        rule.waitForIdle()
        assertEquals(true, fullscreen)
    }

    @Test
    fun lockButton_hidesControls() {
        composePlayer(fullscreen = true)
        rule.onAllNodes(hasClickAction())[bottomClickableIndex(1)].performClick()
        rule.waitForIdle()
        // Locked: the whole controls overlay (quality chip included) is gone.
        rule.onNodeWithText("360p").assertDoesNotExist()
    }

}
