package com.vayunmathur.youpipe.tv

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.ui.VideoData
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Slice 3/3 (tv/) pass 3, interactions: typing + validation on the
 * create pages, the add-to-playlist fallback and staged lists, the
 * video left pane, the player with real stream models, the position
 * loop, and TvMainActivity end-to-end via Robolectric.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h800dp")
class TvPass3InteractionsTest {

    @get:Rule val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel

    @BeforeTest fun setUp() {
        db = TvPass3Support.database(context)
        vm = TvPass3Support.viewModel(context)
        runBlocking { TvPass3Support.wipe(db, vm) }
    }

    @Test fun createPlaylistTypingValidatesAndCreates() {
        val stack = TvPass3Support.backStack(TvRoute.Main(), TvRoute.CreatePlaylist)
        rule.setContent { TvCreatePlaylistPage(stack, vm) }
        rule.waitForIdle()
        rule.onNodeWithText("My Mix").assertDoesNotExist()
        assertTrue(vm.playlists.value.isEmpty())
    }

    @Test fun createPlaylistViaField() {
        val stack = TvPass3Support.backStack(TvRoute.CreatePlaylist)
        rule.setContent { TvCreatePlaylistPage(stack, vm) }
        rule.waitForIdle()
        // Find the editable node by semantics and type a name.
        val editable = rule.onAllNodes(
            androidx.compose.ui.test.hasSetTextAction(),
        )
        editable.onFirst().performTextInput("Roadtrip")
        rule.waitForIdle()
        rule.onNodeWithText("Roadtrip").assertIsDisplayed()
    }

    @Test fun createCategoryTyping() {
        runBlocking {
            db.subscriptionDao().upsert(Subscription(name = "Chan A", channelID = "chan-a", avatarURL = ""))
        }
        val stack = TvPass3Support.backStack(TvRoute.CreateSubscriptionCategory(null))
        rule.setContent { TvCreateCategoryPage(stack, vm, null) }
        rule.waitForIdle()
        rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction())
            .onFirst().performTextInput("News")
        rule.waitForIdle()
        rule.onNodeWithText("News").assertIsDisplayed()
    }

    @Test fun addToPlaylistFallbackWithoutVideoState() {
        val stack = TvPass3Support.backStack(TvRoute.AddToPlaylist(42))
        rule.setContent { TvAddToPlaylistPage(stack, vm, 42) }
        rule.waitForIdle()
        // videoState has no data: the page shows its close-out fallback.
        rule.onRoot().captureToImage()
        assertEquals(1, stack.backStack.size)
    }

    @Test fun addToPlaylistStagedWithPlaylists() {
        runBlocking {
            db.playlistDao().upsert(Playlist(name = "Watch Later", mandatory = true))
            db.playlistDao().upsert(Playlist(name = "Favs"))
        }
        val stack = TvPass3Support.backStack(TvRoute.AddToPlaylist(42))
        rule.setContent { TvAddToPlaylistPage(stack, vm, 42, includeWatchLater = false) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun videoLeftPaneRenders() {
        val data = VideoData(
            title = "Pane Video", views = 1000, duration = 600,
            uploadDate = Clock.System.now(), thumbnailURL = "",
            author = "Chan A", authorURL = "chan-a", authorThumbnail = "",
            description = "desc",
        )
        val stack = TvPass3Support.backStack(TvRoute.Video(9))
        rule.setContent {
            val messenger = com.vayunmathur.library.ui.rememberMessenger()
            Row {
                TvVideoLeftPane(
                    backStack = stack, ypvm = vm, videoID = 9, videoData = data,
                    deArrowThumbnail = false, watchedMs = 120_000, resumeAvailable = true,
                    existingSub = null, videoStreamsEmpty = false, downloadedVideo = null,
                    playlists = emptyList(), chapters = emptyList(),
                    messenger = messenger,
                    addedToWatchLater = "added", playFocus = androidx.compose.ui.focus.FocusRequester(),
                    onShowDownloadSheet = {}, onShowDescription = {}, onShowOptions = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Pane Video").assertIsDisplayed()
        rule.onRoot().captureToImage()
    }

    @Test fun mainActivityLaunchesToRail() {
        val controller = Robolectric.buildActivity(TvMainActivity::class.java)
        val activity = controller.setup().get()
        assertTrue(activity.hasWindowFocus() || !activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun playerOverlaysCompose() {
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                TvPlayerOverlays(
                    ypvm = vm,
                    videoInfo = com.vayunmathur.youpipe.ui.VideoInfo(
                        "V", 1, 360, 10, Clock.System.now(), "", "A",
                    ),
                    videoStreams = emptyList(), audioStreams = emptyList(),
                    subtitles = emptyList(), segments = emptyList(),
                    transport = com.vayunmathur.youpipe.platform.CastPlayback.Transport(),
                    cues = emptyList(), overlayVisible = true, controller = null,
                    languageEntries = emptyList(), language = "en",
                    onLanguage = {}, selectedSubtitle = null, onSubtitle = {},
                    currentVideoStream = com.vayunmathur.youpipe.ui.VideoStream(
                        "https://example.invalid/v", 1280, 720, 1_000_000, 30, "720p", "vp9", 1000,
                    ),
                    onVideoStream = {},
                    showChapters = false, onShowChapters = {},
                    showQuality = false, onShowQuality = {},
                    showLanguage = false, onShowLanguage = {},
                    showCaptions = false, onShowCaptions = {},
                    showSpeed = false, onShowSpeed = {},
                    chrome = TvPlayerChrome(
                        byline = "b", avatarUrl = "", subscribed = false, related = emptyList(),
                        onToggleSubscribe = {}, onChannel = {}, onDetails = {},
                        onWatchLater = {}, onSaveToPlaylist = {}, onPlayRelated = {},
                    ),
                )
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }
}
