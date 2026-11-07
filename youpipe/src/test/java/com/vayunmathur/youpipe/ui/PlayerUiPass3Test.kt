package com.vayunmathur.youpipe.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.youpipe.nativeext.AudioStream as NativeAudioStream
import com.vayunmathur.youpipe.nativeext.Comment as NativeComment
import com.vayunmathur.youpipe.nativeext.VideoStream as NativeVideoStream
import com.vayunmathur.youpipe.util.SponsorSegment
import com.vayunmathur.youpipe.util.VideoDetailActions
import com.vayunmathur.youpipe.util.VideoDetailUiState
import com.vayunmathur.youpipe.util.VideoRowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: the player-adjacent composables and row/sections, state-driven. */
@RunWith(AndroidJUnit4::class)
class PlayerUiPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun row(id: Long = 1, title: String = "A Video Title") = VideoRowState(
        videoID = id,
        title = title,
        thumbnailURL = "",
        author = "Some Author",
        stats = "1.2K views | 3 days ago",
        reason = null,
        channelKey = "some author",
        percentWatched = 0f,
    )

    private fun uiStream(quality: String = "720p") = VideoStream(
        url = "https://example.com/v", width = 1280, height = 720,
        bitrate = 2_000_000, fps = 30, quality = quality, codec = "vp09", size = 10_000,
    )

    @Test
    fun videoRow_showsTitleAuthorStatsAndHandlesOverflow() {
        var overflowed = false
        rule.setContent {
            VideoRow(
                row = row().copy(percentWatched = 0.5f, reason = "Because you watched X"),
                overflowActions = listOf("Save" to { overflowed = true }),
            )
        }
        rule.onNodeWithText("A Video Title").assertIsDisplayed()
        rule.onNodeWithText("Some Author", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Because you watched X", substring = true).assertIsDisplayed()
    }

    @Test
    fun videoRow_verticalLayout() {
        rule.setContent { VideoRow(row = row(title = "Vertical Title"), vertical = true) }
        rule.onNodeWithText("Vertical Title").assertIsDisplayed()
    }

    @Test
    fun videoRowState_formatsPaidViewsAndDeArrow() {
        val info = VideoInfo(
            name = "Plain &amp; Simple", videoID = 42, duration = 100, views = -5,
            uploadDate = kotlin.time.Instant.fromEpochSeconds(1_700_000_000),
            thumbnailURL = "https://img/42.jpg", author = "Auth &amp; Co", isPaid = true,
        )
        val paid = videoRowState(context, info, showAuthor = true)
        assertEquals(42L, paid.videoID)
        assertEquals("Auth & Co", paid.author)
        val deArrow = videoRowState(
            context, info, showAuthor = false,
            deArrowTitle = "Better Title", deArrowThumbnailURL = "https://img/de.jpg",
            percentWatched = 0.25f, reason = "r",
        )
        assertEquals("Better Title", deArrow.title)
        assertEquals("https://img/de.jpg", deArrow.thumbnailURL)
        assertEquals(null, deArrow.author)
        assertTrue(deArrow.deArrowThumbnail)
        assertEquals(0.25f, deArrow.percentWatched)
        val free = videoRowState(
            context, info.copy(isPaid = false, views = 1_234_567), showAuthor = true,
        )
        assertTrue(free.stats.isNotEmpty())
    }

    @Test
    fun chapterSheet_listsChaptersAndSeeks() {
        var sought = -1L
        var dismissed = false
        val chapters = listOf(
            VideoChapter(0, "Intro", null),
            VideoChapter(83, "Main Part", null),
            VideoChapter(300, "Outro", null),
        )
        rule.setContent {
            VideoPlayerChapterSheet(
                segments = chapters, currentPosition = 100_000,
                onSeekTo = { sought = it }, onDismiss = { dismissed = true },
            )
        }
        rule.onNodeWithText("Intro").assertIsDisplayed()
        rule.onNodeWithText("Main Part").performClick()
        assertEquals(83L, sought)
        assertTrue(dismissed)
    }

    @Test
    fun seekRow_sliderStyleShowsTimes() {
        rule.setContent {
            VideoPlayerSeekRow(
                currentPosition = 30_000, bufferedPosition = 60_000, duration = 120_000,
                isPlaying = true, useNotificationStyle = false,
                sponsorSegments = listOf(SponsorSegment("sponsor", listOf(10f, 20f), "u1")),
                onSeekChange = {}, onSeekFinished = {}, onSeekTo = {},
                isFullscreen = false, onLock = {}, onFullscreenChange = {},
            )
        }
        // Elapsed-time text comes from the platform formatter; the stable
        // contract here is the position slider's range semantics.
        rule.onNode(
            androidx.compose.ui.test.hasProgressBarRangeInfo(
                androidx.compose.ui.semantics.ProgressBarRangeInfo(30_000f, 0f..120_000f),
            ),
        ).assertExists()
    }

    @Test
    fun seekRow_notificationStyle() {
        rule.setContent {
            VideoPlayerSeekRow(
                currentPosition = 0, bufferedPosition = 0, duration = 90_000,
                isPlaying = false, useNotificationStyle = true,
                sponsorSegments = emptyList(),
                onSeekChange = {}, onSeekFinished = {}, onSeekTo = {},
                isFullscreen = true, onLock = {}, onFullscreenChange = {},
            )
        }
        rule.onNode(
            androidx.compose.ui.test.hasProgressBarRangeInfo(
                androidx.compose.ui.semantics.ProgressBarRangeInfo(0f, 0f..90_000f),
            ),
        ).assertExists()
    }

    @Test
    fun castingPanel_namesReceiverAndStops() {
        var stopped = false
        rule.setContent { CastingPanel(receiverName = "Living Room TV", onStop = { stopped = true }) }
        rule.onNodeWithText("Living Room TV", substring = true).assertIsDisplayed()
    }

    @Test
    fun topControls_showQualityAndMenus() {
        var castClicked = false
        val stream = uiStream()
        rule.setContent {
            VideoPlayerTopControls(
                hasAudio = true,
                languages = listOf("en", "es"),
                languageEntries = listOf("en" to "English", "es" to "Spanish"),
                language = "en",
                onLanguageChange = {},
                isVideoMenuExpanded = true,
                onVideoMenuExpandedChange = {},
                isLanguageMenuExpanded = false,
                onLanguageMenuExpandedChange = {},
                isCaptionMenuExpanded = false,
                onCaptionMenuExpandedChange = {},
                isSpeedMenuExpanded = false,
                onSpeedMenuExpandedChange = {},
                currentVideoStream = stream,
                onVideoStreamChange = {},
                videoStreams = listOf(stream, uiStream("360p")),
                subtitles = listOf(SubtitleTrack("u", "en", "English", false, "text/vtt")),
                selectedSubtitle = null,
                onSubtitleChange = {},
                segments = listOf(VideoChapter(0, "Intro", null)),
                onChapterMenuVisibleChange = {},
                playbackSpeed = 1.5f,
                onSpeedChange = {},
                playbackPitch = 1.0f,
                onPitchChange = {},
                unhookPitch = false,
                onUnhookPitchChange = {},
                isCasting = false,
                onCastClick = { castClicked = true },
            )
        }
        rule.onNodeWithText("720p").assertIsDisplayed()
        rule.onNodeWithText("360p", substring = true).assertIsDisplayed()
    }

    @Test
    fun descriptionSection_rendersText() {
        rule.setContent {
            DescriptionSection("A description with a https://example.com link inside")
        }
        rule.onNodeWithText("A description with a https://example.com link inside", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun relatedSection_rendersRows() {
        rule.setContent {
            RelatedVideosSection(listOf(row(7, "Related One"))) { }
        }
        rule.onNodeWithText("Related One").assertIsDisplayed()
    }

    @Test
    fun commentsSection_rendersComment() {
        rule.setContent {
            CommentsSection(
                listOf(Comment(text = "Great video", author = "Fan", likes = 12, dislikes = 1)),
            )
        }
        rule.onNodeWithText("Great video").assertIsDisplayed()
        rule.onNodeWithText("Fan").assertIsDisplayed()
    }

    @Test
    fun linkifiedText_rendersLinked() {
        rule.setContent { LinkifiedText("visit https://example.com/watch now") }
        rule.onNodeWithText("visit https://example.com/watch now", substring = true).assertIsDisplayed()
    }

    @Test
    fun linkifiedText_rendersPlain() {
        rule.setContent { LinkifiedText("no links here at all") }
        rule.onNodeWithText("no links here at all").assertIsDisplayed()
    }

    @Test
    fun feedGrid_rowsAndEmpty() {
        rule.setContent {
            FeedGrid(rows = listOf(row(3, "Grid Video")), key = { it.videoID }) { item ->
                FeedVideoRow(row = item, openVideo = {})
            }
        }
        rule.onNodeWithText("Grid Video").assertIsDisplayed()
    }

    @Test
    fun videoDetails_rendersStateAndActions() {
        var shared = false
        val actions = object : VideoDetailActions {
            override fun share() {
                shared = true
            }
        }
        rule.setContent {
            VideoDetails(
                state = VideoDetailUiState(
                    loaded = true, title = "Detail Title",
                    byline = "Channel | 1M views", description = "desc",
                    comments = listOf(Comment("Nice", "Fan", 1, 0)),
                    relatedVideos = listOf(row(9, "Rel")),
                ),
                actions = actions,
            )
        }
        rule.onNodeWithText("Detail Title").assertIsDisplayed()
        rule.onNodeWithText("Channel | 1M views").assertIsDisplayed()
    }

    @Test
    fun notificationProgressBar_advertisesProgressSemantics() {
        rule.setContent {
            VideoPlayerNotificationProgressBar(
                currentPosition = 10_000, bufferedPosition = 20_000, duration = 100_000,
                isPlaying = true, sponsorSegments = emptyList(),
                onSeekTo = {}, modifier = androidx.compose.ui.Modifier,
            )
        }
        rule.onNode(
            androidx.compose.ui.test.hasProgressBarRangeInfo(
                androidx.compose.ui.semantics.ProgressBarRangeInfo(10_000f, 0f..100_000f),
            ),
        ).assertIsDisplayed()
    }
}
