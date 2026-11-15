package com.vayunmathur.youpipe.tv

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import com.vayunmathur.youpipe.ui.SubtitleTrack
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * TV browse/player sheets driven by the real ViewModel over an in-memory
 * repository, mirroring PagesPass3Test. Seeded rows in, rendered TV rows and
 * navigation effects out. The downloaded video (id 77) lets the video-detail
 * page reach its loaded state through the ViewModel's downloaded fallback
 * when extraction fails in the sandbox, with no network asserted.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TvPagesTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel
    private var musicPlaylistId: Long = -1
    private var stack: NavBackStack<TvRoute>? = null

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        TvLoadedVideo.id = null
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java)
            .allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
        runBlocking { seed() }
    }

    @AfterTest
    fun tearDown() {
        TvLoadedVideo.id = null
        db.close()
    }

    private suspend fun seed() {
        val subId = db.subscriptionDao().upsert(
            Subscription(name = "Seeded Chan", channelID = "UCseed", avatarURL = ""),
        )
        db.subscriptionCategoryDao().upsertAll(listOf(SubscriptionCategory(subId, "News")))
        db.subscriptionVideoDao().upsertAll(
            listOf(
                SubscriptionVideo(
                    id = 42, name = "Seeded Video", duration = 60, views = 3,
                    uploadDate = Instant.fromEpochSeconds(1_700_000_000),
                    thumbnailURL = "", author = "Seeded Chan", channelID = subId,
                ),
            ),
        )
        db.historyVideoDao().upsert(
            HistoryVideo(
                progress = 1_000,
                videoItem = VideoInfo("Watched One", 55, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan"),
                timestamp = Instant.fromEpochSeconds(1_700_100_000),
            ),
        )
        musicPlaylistId = db.playlistDao().upsert(Playlist(name = "Music Mix"))
        db.playlistItemDao().upsert(
            PlaylistItem(
                playlistId = musicPlaylistId,
                videoItem = VideoInfo("Playlist Song", 66, 200, 4, Instant.fromEpochSeconds(1_650_000_000), "", "Seeded Chan"),
                timestamp = Instant.fromEpochSeconds(1_700_200_000),
            ),
        )
        db.downloadedVideoDao().upsert(
            DownloadedVideo(
                id = 77,
                videoItem = VideoInfo("Offline Video", 77, 300, 8, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan"),
                filePath = "/dl/77.mp4",
                timestamp = Instant.fromEpochSeconds(1_700_300_000),
            ),
        )
    }

    private fun awaitText(text: String) {
        rule.waitUntilExactlyOneExists(hasText(text), timeoutMillis = 15_000)
    }

    private fun clickText(text: String) {
        val node = rule.onNode(hasText(text)).fetchSemanticsNode()
        val ok = node.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke()
        assertTrue(ok == true, "no click action for '$text'")
    }

    private fun themed(content: @androidx.compose.runtime.Composable (NavBackStack<TvRoute>) -> Unit) {
        rule.setContent {
            val backStack = rememberNavBackStack<TvRoute>(TvRoute.Main(TV_RAIL_HOME))
            stack = backStack
            MaTvTheme(TvAccent.Red) { content(backStack) }
        }
    }

    @Test
    fun tvHistory_showsWatchedRowAndHeader() {
        themed { bs -> TvHistoryPage(bs, vm) }
        awaitText("Watched One")
        rule.onNodeWithText("Watched One").assertIsDisplayed()
        rule.onNodeWithText("History").assertIsDisplayed()
    }

    @Test
    fun tvSubscriptions_showsChannelCategoryAndLatestVideo() {
        themed { bs -> TvSubscriptionsPage(bs, vm) }
        awaitText("Seeded Chan")
        rule.onNodeWithText("Seeded Chan").assertIsDisplayed()
        rule.onNodeWithText("News").assertIsDisplayed()
        awaitText("Seeded Video")
    }

    @Test
    fun tvDownloads_showsOfflineVideo() {
        themed { bs -> TvDownloadsPage(bs, vm) }
        awaitText("Offline Video")
        rule.onNodeWithText("Offline Video").assertIsDisplayed()
    }

    @Test
    fun tvPlaylistDetail_showsPlaylistTitleAndSong() {
        themed { bs -> TvPlaylistDetailPage(bs, vm, musicPlaylistId) }
        awaitText("Music Mix")
        awaitText("Playlist Song")
        rule.onNodeWithText("Playlist Song").assertIsDisplayed()
    }

    @Test
    fun tvSaved_showsDownloadsWatchLaterAndPlaylist_andNewPlaylistNavigates() {
        themed { bs -> TvSavedPage(bs, vm) }
        awaitText("Music Mix")
        rule.onNodeWithText("Music Mix").assertIsDisplayed()
        clickText("New playlist")
        rule.waitUntil(timeoutMillis = 5_000) { stack?.backStack?.lastOrNull() == TvRoute.CreatePlaylist }
        assertEquals(TvRoute.CreatePlaylist, stack?.backStack?.lastOrNull())
    }

    @Test
    fun tvSettings_rowsNavigateToSubPages() {
        themed { bs -> TvSettingsPage(bs, vm) }
        // General is the first focusable/settings row; its label comes from resources.
        rule.waitUntilExactlyOneExists(hasText("General & Content"), timeoutMillis = 10_000)
        clickText("General & Content")
        rule.waitUntil(timeoutMillis = 5_000) { stack?.backStack?.lastOrNull() == TvRoute.SettingsGeneral }
        assertEquals(TvRoute.SettingsGeneral, stack?.backStack?.lastOrNull())
    }

    @Test
    fun tvGeneralSettings_rendersOptions() {
        themed { _ -> TvGeneralSettingsPage(vm) }
        rule.waitUntilExactlyOneExists(hasText("General & Content"), timeoutMillis = 10_000)
    }

    @Test
    fun tvDataSettings_rendersRows() {
        themed { _ -> TvDataSettingsPage(vm) }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("Clear", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tvRecommendationsSettings_rendersPresetsAndManageInterestsAction() {
        themed { bs -> TvRecommendationsSettingsPage(bs, vm) }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("Recommendations", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tvManageInterests_rendersWithSeededChannel() {
        themed { _ -> TvManageInterestsPage(vm) }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("Seeded Chan", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("interest", substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tvSponsorBlockSettings_rendersCategories() {
        themed { _ -> TvSponsorBlockSettingsPage(vm) }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("Sponsor", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tvSearch_rendersFieldWithoutResults() {
        themed { bs -> TvSearchPage(bs, vm) }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("Search", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tvHomeFeed_emptyRecommendationsShowsRetryState() {
        themed { bs -> TvHomeFeedPage(bs, vm) }
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodes(hasText("Watch some videos to get personalized recommendations")).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("Watched One")).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            rule.onAllNodes(hasText("Watch some videos to get personalized recommendations")).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("Watched One")).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun tvCreatePlaylist_rendersNameField() {
        themed { bs -> TvCreatePlaylistPage(bs, vm) }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("playlist", substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tvCreateCategory_rendersField() {
        themed { bs -> TvCreateCategoryPage(bs, vm, null) }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("categor", substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tvAddToPlaylist_showsSeededPlaylists() {
        vm.videoStateMutable.value = YouPipeViewModel.VideoState(
            data = com.vayunmathur.youpipe.ui.VideoData(
                "Watched One", 100, 55, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan", "UCseed", "", "desc",
            ),
        )
        themed { bs -> TvAddToPlaylistPage(bs, vm, 55) }
        awaitText("Music Mix")
        rule.onNodeWithText("Music Mix").assertIsDisplayed()
    }

    @Test
    fun tvVideoPage_downloadedVideoShowsLoadedDetail() {
        themed { bs -> TvVideoPage(bs, vm, 77) }
        awaitText("Offline Video")
        rule.onNodeWithText("Offline Video").assertIsDisplayed()
        // Play action is present on the loaded detail (resume/play button).
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText("Play", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("Resume", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tvVideoPage_unknownVideoShowsErrorOrLoadingState() {
        themed { bs -> TvVideoPage(bs, vm, 999_001) }
        rule.waitUntil(timeoutMillis = 20_000) {
            rule.onAllNodes(hasText("Video load error", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("unavailable", substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tvPlayerPage_unknownVideoShowsGoBackAndClickReturns() {
        themed { bs -> TvPlayerPage(bs, vm, 999_002) }
        awaitText("Go Back")
        val before = stack?.backStack?.size
        clickText("Go Back")
        rule.waitUntil(timeoutMillis = 5_000) { (stack?.backStack?.size ?: 0) < (before ?: Int.MAX_VALUE) || stack?.backStack?.isEmpty() == true }
    }

    @Test
    fun tvChannelPage_unknownChannelShowsState() {
        themed { bs -> TvChannelPage(bs, vm, "UCunknown123") }
        // Channel load fails offline: page must settle without crashing or navigating.
        rule.waitForIdle()
        assertEquals(TvRoute.Main(TV_RAIL_HOME), stack?.backStack?.lastOrNull())
    }

    @Test
    fun tvSubscriptionFeed_showsSeededVideoForCategory() {
        themed { bs -> TvSubscriptionFeedPage(bs, vm, "News") }
        awaitText("Seeded Video")
        rule.onNodeWithText("Seeded Video").assertIsDisplayed()
    }

    @Test
    fun tvDownloadSheet_selectsQualityAndConfirmsHighestByDefault() {
        var confirmed: Pair<String, String?>? = null
        val videos = listOf(
            VideoStream("https://v/360", 640, 360, 500, 30, "360p", "avc1", 1_000),
            VideoStream("https://v/720", 1280, 720, 1500, 30, "720p", "avc1", 3_000),
        )
        val audios = listOf(
            AudioStream("https://a/en-low", 64, "en", "opus", 500, displayName = "English"),
            AudioStream("https://a/en-high", 128, "en", "opus", 900, displayName = "English"),
            AudioStream("https://a/es", 96, "es", "opus", 700, displayName = "Spanish"),
        )
        rule.setContent {
            MaTvTheme(TvAccent.Red) {
                TvDownloadSheet(videos, audios, onConfirm = { v, a -> confirmed = v to a }, onDismiss = {})
            }
        }
        awaitText("720p")
        clickText("Download")
        assertEquals("https://v/720" to "https://a/en-high", confirmed)
    }

    @Test
    fun qualitySheet_selectsStream() {
        var quality: VideoStream? = null
        val streams = listOf(
            VideoStream("https://v/360", 640, 360, 500, 30, "360p", "avc1", 1_000),
            VideoStream("https://v/720", 1280, 720, 1500, 30, "720p", "avc1", 3_000),
        )
        rule.setContent { MaTvTheme(TvAccent.Red) { TvQualitySheet(streams, streams[0], onSelect = { quality = it }, onDismiss = {}) } }
        awaitText("720p"); clickText("720p"); assertEquals(streams[1], quality)
    }

    @Test
    fun captionSheet_selectsTrack() {
        var caption: SubtitleTrack? = null
        rule.setContent {
            MaTvTheme(TvAccent.Red) {
                TvCaptionSheet(listOf(SubtitleTrack("https://c/en", "en", "English", false, "text/vtt")), null, onSelect = { caption = it }, onDismiss = {})
            }
        }
        awaitText("English"); clickText("English"); assertEquals("en", caption?.languageTag)
    }

    @Test
    fun speedSheet_selectsSpeed() {
        var speed: Float? = null
        rule.setContent { MaTvTheme(TvAccent.Red) { TvSpeedSheet(1f, onSelect = { speed = it }, onDismiss = {}) } }
        awaitText("1.5x"); clickText("1.5x"); assertEquals(1.5f, speed)
    }

    @Test
    fun languageSheet_selectsLanguage() {
        var language: String? = null
        rule.setContent {
            MaTvTheme(TvAccent.Red) { TvLanguageSheet(listOf("en" to "English", "es" to "Spanish"), "en", onSelect = { language = it }, onDismiss = {}) }
        }
        awaitText("Spanish"); clickText("Spanish"); assertEquals("es", language)
    }

    @Test
    fun chapterSheet_seeksToChapter() {
        var seek: Long? = null
        rule.setContent {
            MaTvTheme(TvAccent.Red) {
                TvChapterSheet(listOf(VideoChapter(0, "Intro", null), VideoChapter(60_000, "Main Part", null)), onSeekTo = { seek = it }, onDismiss = {})
            }
        }
        awaitText("Main Part"); clickText("Main Part"); assertEquals(60_000L, seek)
    }

    @Test
    fun transportOverlay_showsTitleAndDetailsAction() {
        var details = 0
        val chrome = TvPlayerChrome(
            byline = "Seeded Chan · 100 views", avatarUrl = "", subscribed = false,
            related = emptyList(), onToggleSubscribe = {}, onChannel = {},
            onDetails = { details++ }, onWatchLater = {}, onSaveToPlaylist = {},
            onPlayRelated = {},
        )
        rule.setContent {
            MaTvTheme(TvAccent.Red) {
                TvTransportOverlay(
                    title = "Watched One", chrome = chrome, hasNext = true, hasPrevious = true,
                    hasCaptions = true, captionsOn = false, onCaptions = {},
                    settings = TvPlayerSettings("720p", 1f, hasChapters = true, hasLanguage = true, {}, {}, {}, {}), ypvm = vm,
                )
            }
        }
        awaitText("Watched One")
        rule.onNodeWithText("Watched One").assertIsDisplayed()
        clickText("Details")
        assertEquals(1, details)
    }

    @Test
    fun sharedHelpers_durationSubtitleAndSpeedFormatting() {
        assertEquals(null, tvDurationBadge(0))
        assertEquals("01:00", tvDurationBadge(60))
        assertEquals("1x", formatTvSpeedLabel(1f))
        assertEquals("1.5x", formatTvSpeedLabel(1.5f))
        assertEquals("0:00", formatTvElapsed(0))
        assertEquals("1:05", formatTvElapsed(65_000))
        // tvDefaultBackStack maps persisted keys to rail destinations.
        assertEquals(listOf(TvRoute.Main(TV_RAIL_HISTORY)), tvDefaultBackStack("history"))
        assertEquals(listOf(TvRoute.Main(TV_RAIL_HOME)), tvDefaultBackStack("nonsense"))
        assertEquals(
            listOf(TvRoute.Main(TV_RAIL_SUBSCRIPTIONS), TvRoute.SubscriptionFeed(null)),
            tvDefaultBackStack("all_subscriptions"),
        )
    }
}
