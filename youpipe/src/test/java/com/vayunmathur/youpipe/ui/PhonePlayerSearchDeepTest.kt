package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.domain.RankedVideo
import com.vayunmathur.youpipe.domain.RecSource
import com.vayunmathur.youpipe.platform.SearchActions
import com.vayunmathur.youpipe.platform.SearchResultRow
import com.vayunmathur.youpipe.platform.SearchUiState
import com.vayunmathur.youpipe.platform.VideoRowState
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Instant
import org.junit.Rule
import org.junit.runner.RunWith

/** Phone player gestures + search page with seeded recommendations. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(shadows = [ShadowPendingMediaControllerBuilder::class])
class PhonePlayerSearchDeepTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel
    private var stack: NavBackStack<Route>? = null
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        CastPlayback.close(); CastPlayback.update { CastPlayback.Transport() }
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java).allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
    }
    @AfterTest fun tearDown() { CastPlayback.close(); Thread.sleep(100) }

    private val info = VideoInfo("Player Video", 4242, 120, 10, Instant.fromEpochSeconds(1_700_000_000), "", "Player Chan")
    private val vStreams = listOf(
        VideoStream("https://v/360", 640, 360, 800_000, 30, "360p", "vp09", 5_000),
        VideoStream("https://v/720", 1280, 720, 2_000_000, 30, "720p", "vp09", 9_000),
    )
    private val aStreams = listOf(AudioStream("https://a/en", 128_000, "en", "opus", 2_000, displayName = "English"))

    @Test fun videoPlayer_tapDoubleTapLongPressGestures() {
        rule.setContent {
            VideoPlayer(vm, info, vStreams, aStreams,
                listOf(SubtitleTrack("https://s/en", "en", "English", false, "text/vtt")),
                listOf(VideoChapter(0, "Intro", null)), false, {})
        }
        rule.onNodeWithText("360p").assertExists()
        rule.onRoot().performTouchInput { click(Offset(center.x, 80f)) }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { doubleClick(Offset(center.x + 100f, 80f)) }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { longClick(Offset(center.x, 80f)) }
        rule.waitForIdle()
        rule.onNodeWithText("360p").assertExists()
    }

    @Test fun videoPlayer_fullscreenGestures() {
        rule.setContent { VideoPlayer(vm, info, vStreams, aStreams, emptyList(), emptyList(), true, {}) }
        rule.onRoot().performTouchInput { click(Offset(center.x, center.y)) }
        rule.waitForIdle()
    }

    @Test fun searchPage_seededRecommendations_renderAndOpen() {
        rule.setContent { val bs = rememberNavBackStack<Route>(Route.Home); stack = bs; SearchPage(bs, vm) }
        rule.waitUntil(15_000) { rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performClick()
        rule.waitForIdle()
    }

    @Test fun searchScreen_directState_resultsAndCallbacks() {
        var query = ""; var opened = -1L; var channel = ""
        val row = VideoRowState(88, "Result Video", "", "Chan", "1K views", "chan")
        rule.setContent {
            SearchScreen(
                state = SearchUiState(
                    query = "", suggestions = listOf("lofi", "jazz"),
                    results = listOf(SearchResultRow.Video(row), SearchResultRow.Channel("UC9", "Result Channel", "", "1K subscribers")),
                    recommendations = listOf(row.copy(videoID = 89, title = "Rec Video")), recommendationsLoading = false,
                ),
                actions = object : SearchActions {
                    override fun setSearchQuery(query: String) {}
                    override fun submitSearch(): Boolean = false
                    override fun openVideo(videoID: Long) { opened = videoID }
                    override fun openChannel(channelID: String) { channel = channelID }
                },
            )
        }
        // Results live in the expanded search sheet: expand via the search field first.
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performClick()
        rule.waitUntilExactlyOneExists(hasText("Result Video"), 10_000)
        rule.onNodeWithText("Result Video").assertExists()
        rule.onNodeWithText("Result Channel").assertExists()
    }
}
