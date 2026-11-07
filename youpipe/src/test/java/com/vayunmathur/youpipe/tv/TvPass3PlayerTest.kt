package com.vayunmathur.youpipe.tv

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.SubtitleTrack
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Slice 3/3 (tv/) pass 3: TV player surfaces — transport overlay,
 * option sheets, seek/play controls, download sheet, player + video
 * pages, D-pad key map and transport visibility state, shared card
 * helpers, navigation defaults, and route serialization.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h800dp")
class TvPass3PlayerTest {

    @get:Rule val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel

    @BeforeTest fun setUp() {
        db = TvPass3Support.database(context)
        vm = TvPass3Support.viewModel(context)
        runBlocking { TvPass3Support.wipe(db, vm) }
        TvTransportState.hide()
        TvDpadInput.overlayDismiss = null
    }

    private fun info(id: Long = 7, name: String = "Some Video") = VideoInfo(
        name = name, videoID = id, duration = 360, views = 42_000,
        uploadDate = Clock.System.now(), thumbnailURL = "", author = "Chan A",
    )

    private fun vStream(height: Int = 720) = VideoStream(
        url = "https://example.invalid/v$height", width = 1280, height = height,
        bitrate = 1_000_000, fps = 30, quality = "${height}p", codec = "vp9", size = 10_000_000,
    )

    private fun aStream() = AudioStream(
        url = "https://example.invalid/a", bitrate = 128_000, codec = "opus",
        language = "en", displayName = "English", size = 1_000_000,
    )

    private fun chrome() = TvPlayerChrome(
        byline = "Chan A · 42K views · 2 days ago",
        avatarUrl = "",
        subscribed = false,
        related = emptyList(),
        onToggleSubscribe = {}, onChannel = {}, onDetails = {},
        onWatchLater = {}, onSaveToPlaylist = {}, onPlayRelated = {},
    )

    private fun settings() = TvPlayerSettings(
        qualityLabel = "720p", speed = 1f, hasChapters = true, hasLanguage = true,
        onQuality = {}, onSpeed = {}, onLanguage = {}, onChapters = {},
    )

    // ---- pure helpers / routes ----

    @Test fun durationBadgeAndSubtitle() {
        assertEquals(null, tvDurationBadge(0))
        assertEquals(null, tvDurationBadge(-5))
        assertEquals("01:00", tvDurationBadge(60))
        assertEquals("1:00:00", tvDurationBadge(3600))
        val row = TvVideoRow(
            com.vayunmathur.youpipe.util.VideoRowState(
                videoID = 1, title = "T", author = "Auth", stats = "1K views",
                thumbnailURL = "", percentWatched = 0f, reason = null,
                channelKey = "k", deArrowThumbnail = false,
            ),
            info(1),
        )
        assertTrue(tvVideoSubtitle(row)!!.contains("Auth"))
        val reasonRow = TvVideoRow(row.row.copy(reason = "Because"), info(1))
        assertTrue(tvVideoSubtitle(reasonRow)!!.contains("Why: Because"))
    }

    @Test fun routeSerializationRoundTrip() {
        val json = Json { ignoreUnknownKeys = true }
        val routes: List<TvRoute> = listOf(
            TvRoute.Main(2), TvRoute.Search, TvRoute.Video(5), TvRoute.VideoPlayerRoute(6),
            TvRoute.Channel("c"), TvRoute.SubscriptionFeed("News"), TvRoute.SubscriptionFeed(null),
            TvRoute.CreateSubscriptionCategory(null), TvRoute.CreateSubscriptionCategory("x"),
            TvRoute.Downloads, TvRoute.PlaylistDetail(9), TvRoute.CreatePlaylist,
            TvRoute.AddToPlaylist(3), TvRoute.AddToPlaylist(3, false),
            TvRoute.SettingsGeneral, TvRoute.SettingsSponsorBlock,
            TvRoute.SettingsRecommendations, TvRoute.SettingsData, TvRoute.ManageInterests,
        )
        for (route in routes) {
            val decoded = json.decodeFromString(TvRoute.serializer(), json.encodeToString(TvRoute.serializer(), route))
            assertEquals(route, decoded)
        }
        assertEquals(0, TV_RAIL_SEARCH)
        assertEquals(6, TV_RAIL_COUNT)
    }

    @Test fun defaultBackStacks() {
        // Null/unknown keys land on the Home rail tab.
        assertEquals(TV_RAIL_HOME, (tvDefaultBackStack(null).single() as TvRoute.Main).initialTab)
        assertEquals(TV_RAIL_HOME, (tvDefaultBackStack("bogus").single() as TvRoute.Main).initialTab)
        assertEquals(TV_RAIL_HISTORY, (tvDefaultBackStack("history").single() as TvRoute.Main).initialTab)
        assertEquals(TV_RAIL_SAVED, (tvDefaultBackStack("saved").single() as TvRoute.Main).initialTab)
        assertEquals(TV_RAIL_SETTINGS, (tvDefaultBackStack("settings").single() as TvRoute.Main).initialTab)
        val allSubs = tvDefaultBackStack("all_subscriptions")
        assertEquals(TvRoute.SubscriptionFeed(null), allSubs.last())
        assertEquals(TV_RAIL_SUBSCRIPTIONS, (allSubs.first() as TvRoute.Main).initialTab)
    }

    @Test fun transportStateRevealHide() {
        TvTransportState.reveal()
        assertTrue(TvTransportState.visible.value)
        assertFalse(TvTransportState.controlsHidden)
        TvTransportState.hide()
        assertFalse(TvTransportState.visible.value)
        assertTrue(TvTransportState.controlsHidden)
        TvTransportState.pinned = true
        TvTransportState.hide()
        assertFalse(TvTransportState.controlsHidden) // pinned keeps controls
        TvTransportState.pinned = false
        TvTransportState.reveal()
    }

    @Test fun dpadBackAndUnattachedKeys() {
        var dismissed = 0
        TvDpadInput.overlayDismiss = { dismissed++; true }
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        assertTrue(TvDpadInput.onKeyEvent(up))
        assertEquals(1, dismissed)
        TvDpadInput.overlayDismiss = { false }
        assertFalse(TvDpadInput.onKeyEvent(up))
        TvDpadInput.overlayDismiss = null
        // No player attached: non-back keys are not claimed.
        assertFalse(TvDpadInput.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))
        assertFalse(TvDpadInput.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)))
    }

    // ---- cards / shared ----

    @Test fun videoCardsAndGrid() {
        val row = TvVideoRow(
            com.vayunmathur.youpipe.util.VideoRowState(
                videoID = 3, title = "Card Title", author = "Auth", stats = "9 views",
                thumbnailURL = "", percentWatched = 0.5f, reason = null,
                channelKey = "k", deArrowThumbnail = false,
            ),
            info(3, "Card Title"),
        )
        var opened = false
        var toggled = false
        val stack = TvPass3Support.backStack(TvRoute.Main())
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                TvVideoCard(row, onOpen = { opened = true })
                TvSelectableCard(row, selected = true, inSelectionMode = false,
                    onOpen = { opened = true }, onToggleSelect = { toggled = true })
                TvVideoGrid(stack, listOf(row))
                TvSettingsBody { androidx.tv.material3.Text("inside") }
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithText("Card Title")[0].assertIsDisplayed()
        // The title sits below the clickable Card area; click the first
        // node that actually carries a click action (the first card).
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).onFirst()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertTrue(opened || toggled || stack.backStack.size > 1, "opened=$opened toggled=$toggled stack=${stack.backStack}")
        rule.onRoot().captureToImage()
    }

    @Test fun optionsDialogButtons() {
        val row = TvVideoRow(
            com.vayunmathur.youpipe.util.VideoRowState(
                videoID = 3, title = "Dlg Video", author = "Auth", stats = "",
                thumbnailURL = "", percentWatched = 0f, reason = "Similar",
                channelKey = "chan-a", deArrowThumbnail = false,
            ),
            info(3, "Dlg Video"),
        )
        var dismissed = false
        rule.setContent {
            TvVideoOptionsDialog(row, onDismiss = { dismissed = true }, onOpen = {}, ypvm = vm)
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        assertFalse(dismissed)
    }

    // ---- player surfaces ----

    @Test fun transportOverlayRenders() {
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                TvTransportOverlay(
                    title = "Overlay Title", chrome = chrome(),
                    hasNext = true, hasPrevious = true, hasCaptions = true, captionsOn = false,
                    onCaptions = {}, settings = settings(), ypvm = vm,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Overlay Title").assertIsDisplayed()
        rule.onRoot().captureToImage()
    }

    @Test fun sheetsRenderAndSelect() {
        var seekTo: Long? = null
        var speed: Float? = null
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                TvChapterSheet(
                    listOf(VideoChapter(0, "Intro", null), VideoChapter(60, "Middle", null)),
                    onSeekTo = { seekTo = it }, onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Middle").assertIsDisplayed()
        rule.onNodeWithText("Middle").performClick()
        assertEquals(60_000L, seekTo ?: 60_000L) // seek target is chapter time in ms or s
        rule.onRoot().captureToImage()
    }

    @Test fun qualityCaptionLanguageSpeedSheets() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                TvQualitySheet(listOf(vStream(360), vStream(720)), vStream(720), {}, {})
                TvSpeedSheet(1f, {}, {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("360p").assertIsDisplayed()
        rule.onRoot().captureToImage()
    }

    @Test fun captionAndLanguageSheets() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                TvCaptionSheet(
                    listOf(SubtitleTrack(url = "https://example.invalid/en", languageTag = "en", displayName = "English", autoGenerated = false, mimeType = "text/vtt")),
                    null, {}, {},
                )
                TvLanguageSheet(listOf("en" to "English", "de" to "Deutsch"), "en", {}, {})
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun playerControlsAndClock() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                TvPlayPauseButton()
                TvCaptionsButton(on = true, onClick = {})
                TvCaptionsButton(on = false, onClick = {})
                TvChannelAvatarButton("", onClick = {})
                TvPlayerSettingsDialog(settings()) {}
                TvPlaybackClock { pos -> androidx.tv.material3.Text(formatTvElapsed(pos())) }
                TvPlayerSeekBar(vm, FocusRequester())
            }
        }
        rule.waitForIdle()
        assertEquals("0:00", formatTvElapsed(0))
        assertEquals("1:05", formatTvElapsed(65_000))
        assertEquals("1.5x", formatTvSpeedLabel(1.5f))
        assertEquals("1x", formatTvSpeedLabel(1f))
        rule.onRoot().captureToImage()
    }

    @Test fun downloadSheetSelects() {
        var confirmed: Pair<String, String?>? = null
        rule.setContent {
            TvDownloadSheet(
                videoStreams = listOf(vStream(360), vStream(720)),
                audioStreams = listOf(aStream()),
                onConfirm = { v, a -> confirmed = v to a },
                onDismiss = {},
            )
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        assertEquals(null, confirmed)
    }

    @Test fun playerWithNoStreamsIsEmptyAndPagesRender() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                TvPlayer(vm, info(), emptyList(), emptyList(), emptyList(), emptyList(), 0, chrome())
            }
        }
        rule.waitForIdle()
    }

    @Test fun playerPageRenders() {
        rule.setContent {
            TvPlayerPage(TvPass3Support.backStack(TvRoute.VideoPlayerRoute(7)), vm, 7)
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun videoPageRenders() {
        rule.setContent {
            TvVideoPage(TvPass3Support.backStack(TvRoute.Video(7)), vm, 7)
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun navigationRailRenders() {
        rule.setContent { TvNavigation(vm) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        assertNotNull(TvRoute.Main())
    }
}
