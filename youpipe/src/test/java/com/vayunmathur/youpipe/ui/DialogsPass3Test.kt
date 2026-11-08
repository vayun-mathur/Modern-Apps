package com.vayunmathur.youpipe.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.VideoDetailActions
import com.vayunmathur.youpipe.util.VideoDetailUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: the download dialog, its dropdowns, and the detail screen tabs. */
@RunWith(AndroidJUnit4::class)
class DialogsPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val app: android.app.Application
        get() = ApplicationProvider.getApplicationContext()

    private class RecordingActions : VideoDetailActions {
        var shared = 0
        var saved = 0
        var channelOpened = 0
        var videoOpened = -1L
        var downloads = mutableListOf<Pair<String, String?>>()
        var cancels = 0
        var deletes = 0
        override fun share() { shared++ }
        override fun addToPlaylist() { saved++ }
        override fun openChannel() { channelOpened++ }
        override fun openVideo(videoID: Long) { videoOpened = videoID }
        override fun download(videoUrl: String, audioUrl: String?) { downloads += videoUrl to audioUrl }
        override fun cancelDownload() { cancels++ }
        override fun deleteDownload() { deletes++ }
    }

    private val videoStreams = listOf(
        VideoStream("https://v/360", 640, 360, 800_000, 30, "360p", "vp09", 5_000),
        VideoStream("https://v/720", 1280, 720, 2_000_000, 30, "720p", "av01", 9_000),
    )
    private val audioStreams = listOf(
        AudioStream("https://a/en", 128_000, "en", "opus", 2_000, displayName = "English"),
        AudioStream("https://a/es", 96_000, "es", "opus", 1_500, displayName = "Spanish"),
    )

    private fun detailState(
        downloaded: Boolean = false,
        progress: Float? = null,
    ) = VideoDetailUiState(
        loaded = true, title = "Detail Title", byline = "Chan | 1M views",
        description = "the description",
        comments = listOf(Comment("Nice one", "Fan", 3, 0)),
        relatedVideos = listOf(
            com.vayunmathur.youpipe.util.VideoRowState(9, "Related Vid", "", "Chan", "1 view", null, "chan"),
        ),
        videoStreams = videoStreams, audioStreams = audioStreams,
        downloaded = downloaded, downloadProgress = progress,
    )

    @Test
    fun downloadDialog_picksStreamsAndDownloads() {
        val actions = RecordingActions()
        rule.setContent { VideoDetails(detailState(), actions) }
        rule.onNodeWithContentDescription("Download").performClick()
        rule.onNodeWithText(app.getString(R.string.download_options)).assertIsDisplayed()
        // Quality menu: open and pick the other stream.
        rule.onNodeWithText("720p", substring = true).performClick()
        rule.onNodeWithText("360p (vp9)", substring = true).performClick()
        // Language menu: pick Spanish, which re-picks the best Spanish audio.
        rule.onNodeWithText("English").performClick()
        rule.onNodeWithText("Spanish").performClick()
        rule.onNodeWithText(app.getString(R.string.download)).performClick()
        assertEquals(listOf<Pair<String, String?>>("https://v/360" to "https://a/es"), actions.downloads)
    }

    @Test
    fun downloadDialog_cancelDismisses() {
        val actions = RecordingActions()
        rule.setContent { VideoDetails(detailState(), actions) }
        rule.onNodeWithContentDescription("Download").performClick()
        rule.onNodeWithText(app.getString(R.string.download_options)).assertIsDisplayed()
        rule.onNodeWithText(
            app.getString(com.vayunmathur.library.ui.R.string.cancel),
        ).performClick()
        rule.onNodeWithText(app.getString(R.string.download_options)).assertDoesNotExist()
        assertTrue(actions.downloads.isEmpty())
    }

    @Test
    fun actionRow_shareSaveDeleteAndCancelStates() {
        val actions = RecordingActions()
        rule.setContent { VideoDetails(detailState(downloaded = true), actions) }
        rule.onNodeWithContentDescription("Share").performClick()
        rule.onNodeWithContentDescription("Save").performClick()
        rule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(1, actions.shared)
        assertEquals(1, actions.saved)
        assertEquals(1, actions.deletes)
    }

    @Test
    fun actionRow_downloadingShowsCancel() {
        val actions = RecordingActions()
        rule.setContent { VideoDetails(detailState(progress = 0.5f), actions) }
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, actions.cancels)
    }

    @Test
    fun detailScreen_tabsBetweenCommentsRelatedDescription() {
        val actions = RecordingActions()
        rule.setContent {
            VideoDetailScreen(
                state = detailState(), actions = actions,
                player = { androidx.compose.material3.Text("PLAYER-SLOT") },
            )
        }
        rule.onNodeWithText("PLAYER-SLOT").assertIsDisplayed()
        rule.onNodeWithText("Detail Title").assertIsDisplayed()
        rule.onNodeWithText("Nice one").assertIsDisplayed()
        // Related tab.
        rule.onNodeWithText(app.getString(R.string.label_related_videos)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Related Vid").assertIsDisplayed()
        // Description tab.
        rule.onNodeWithText(app.getString(R.string.label_description)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("the description").assertIsDisplayed()
        assertNull(actions.videoOpened.takeIf { it >= 0 })
    }

    @Test
    fun detailScreen_unloadedShowsNothing() {
        rule.setContent {
            VideoDetailScreen(
                state = VideoDetailUiState(loaded = false), actions = RecordingActions(),
                player = { androidx.compose.material3.Text("PLAYER-SLOT") },
            )
        }
        rule.onNodeWithText("PLAYER-SLOT").assertDoesNotExist()
        rule.onNodeWithText("Detail Title").assertDoesNotExist()
    }

    @Test
    fun topControls_languageCaptionAndSpeedMenus() {
        var language = ""
        var subtitle: SubtitleTrack? = SubtitleTrack("u", "en", "English", false, "text/vtt")
        var speed = 0f
        var unhook: Boolean? = null
        rule.setContent {
            VideoPlayerTopControls(
                hasAudio = true,
                languages = listOf("en", "es"),
                languageEntries = listOf("en" to "English", "es" to "Spanish"),
                language = "en",
                onLanguageChange = { language = it },
                isVideoMenuExpanded = false,
                onVideoMenuExpandedChange = {},
                isLanguageMenuExpanded = true,
                onLanguageMenuExpandedChange = {},
                isCaptionMenuExpanded = true,
                onCaptionMenuExpandedChange = {},
                isSpeedMenuExpanded = true,
                onSpeedMenuExpandedChange = {},
                currentVideoStream = videoStreams[1],
                onVideoStreamChange = {},
                videoStreams = videoStreams,
                subtitles = listOf(
                    SubtitleTrack("https://s/en", "en", "English", false, "text/vtt"),
                    SubtitleTrack("https://s/es", "es", "Spanish", true, "text/vtt"),
                ),
                selectedSubtitle = subtitle,
                onSubtitleChange = { subtitle = it },
                segments = listOf(VideoChapter(0, "Intro", null)),
                onChapterMenuVisibleChange = {},
                playbackSpeed = 1.5f,
                onSpeedChange = { speed = it },
                playbackPitch = 1.5f,
                onPitchChange = {},
                unhookPitch = false,
                onUnhookPitchChange = { unhook = it },
                isCasting = true,
                onCastClick = {},
            )
        }
        // "Spanish" exists in both the language and the caption menus.
        rule.onAllNodesWithText("Spanish")[0].performClick()
        assertEquals("es", language)
        // Speed menu is composed: its reset affordance resets tempo to 1x.
        rule.onNodeWithText(
            app.getString(com.vayunmathur.library.ui.R.string.reset),
        ).performClick()
        assertEquals(1f, speed)
        assertEquals(false, unhook)
    }
}
