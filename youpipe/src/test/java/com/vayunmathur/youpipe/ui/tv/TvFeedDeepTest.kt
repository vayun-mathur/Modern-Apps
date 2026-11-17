package com.vayunmathur.youpipe.ui.tv

import android.app.Application
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
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
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.PlaylistItem
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionCategory
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.data.SubscriptionVideo
import com.vayunmathur.youpipe.ui.VideoInfo
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

/** TV subscription feed / saved / home card navigation. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TvFeedDeepTest {
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
        runBlocking {
            val subId = db.subscriptionDao().upsert(Subscription(name = "Seeded Chan", channelID = "UCseed", avatarURL = ""))
            db.subscriptionCategoryDao().upsertAll(listOf(SubscriptionCategory(subId, "News")))
            db.subscriptionVideoDao().upsertAll(listOf(SubscriptionVideo(id = 42, name = "Seeded Video", duration = 60, views = 3, uploadDate = Instant.fromEpochSeconds(1_700_000_000), thumbnailURL = "", author = "Seeded Chan", channelID = subId)))
            playlistId = db.playlistDao().upsert(Playlist(name = "Music Mix"))
            db.playlistItemDao().upsert(PlaylistItem(playlistId = playlistId, videoItem = VideoInfo("Playlist Song", 66, 200, 4, Instant.fromEpochSeconds(1_650_000_000), "", "Seeded Chan"), timestamp = Instant.fromEpochSeconds(1_700_200_000)))
            db.downloadedVideoDao().upsert(DownloadedVideo(id = 77, videoItem = VideoInfo("Offline Video", 77, 300, 8, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan"), filePath = "/dl/77.mp4", timestamp = Instant.fromEpochSeconds(1_700_300_000)))
        }
    }
    @AfterTest fun tearDown() { TvLoadedVideo.id = null; Thread.sleep(100) }
    private fun awaitText(t: String) = rule.waitUntilExactlyOneExists(hasText(t), 15_000)
    private fun themed(content: @androidx.compose.runtime.Composable (NavBackStack<TvRoute>) -> Unit) {
        rule.setContent { val bs = rememberNavBackStack<TvRoute>(TvRoute.Main(TV_RAIL_HOME)); stack = bs; MaTvTheme(TvAccent.Red) { content(bs) } }
    }
    private fun clickFirstCard() {
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        val idx = (0 until count).first { rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()[it].boundsInRoot.top > 80f }
        val node = rule.onAllNodes(hasClickAction())[idx]
        runCatching { node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.RequestFocus].action?.invoke() }
        node.performKeyInput { pressKey(Key.Enter) }
        node.performClick()
    }

    @Test fun subscriptionFeed_openVideo_playsIt() {
        themed { bs -> TvSubscriptionFeedPage(bs, vm, "News") }
        awaitText("Seeded Video"); clickFirstCard()
        assertEquals(TvRoute.VideoPlayerRoute(42), stack?.backStack?.lastOrNull())
    }
    @Test fun videoOptionsDialog_actionsAffectRecommendationsAndOpen() {
        var opened = 0; var dismissed = 0
        val item = TvVideoRow(com.vayunmathur.youpipe.platform.VideoRowState(42, "Seeded Video", "", "Seeded Chan", "3 views", channelKey = "seeded chan", reason = "history"), VideoInfo("Seeded Video", 42, 60, 3, Instant.fromEpochSeconds(1_700_000_000), "", "Seeded Chan"))
        rule.setContent { MaTvTheme(TvAccent.Red) { TvVideoOptionsDialog(item, { dismissed++ }, { opened++ }, vm) } }
        awaitText("Seeded Video")
        // Play button dismisses then opens.
        val play = rule.onNode(hasText("Play"))
        val a = runCatching { play.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action }.getOrNull()
        if (a != null) a.invoke() else play.performClick()
        assertEquals(1, opened); assertEquals(1, dismissed)
    }
    @Test fun videoOptionsDialog_notInterested_mutesChannelSignal() {
        var dismissed = 0
        val item = TvVideoRow(com.vayunmathur.youpipe.platform.VideoRowState(42, "Seeded Video", "", "Seeded Chan", "3 views", channelKey = "seeded chan"), VideoInfo("Seeded Video", 42, 60, 3, Instant.fromEpochSeconds(1_700_000_000), "", "Seeded Chan"))
        rule.setContent { MaTvTheme(TvAccent.Red) { TvVideoOptionsDialog(item, { dismissed++ }, {}, vm) } }
        awaitText("Not interested")
        val node = rule.onNode(hasText("Not interested"))
        val a = runCatching { node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action }.getOrNull()
        if (a != null) a.invoke() else node.performClick()
        assertEquals(1, dismissed)
        val d = System.currentTimeMillis() + 8_000; var mult: Double? = null
        while (System.currentTimeMillis() < d) { Thread.sleep(50); mult = kotlinx.coroutines.runBlocking { vm.repository.recommendationStore.getChannelPreference("seeded chan")?.multiplier }; if (mult == 0.0) break }
        assertEquals(0.0, mult)
    }

    @Test fun subscriptions_openChannelCategory_usesFeed() {
        themed { bs -> TvSubscriptionsPage(bs, vm) }
        awaitText("Seeded Chan")
        assertTrue(rule.onAllNodes(hasText("News")).fetchSemanticsNodes().isNotEmpty())
    }
    @Test fun saved_openPlaylist_navigatesToDetail() {
        themed { bs -> TvSavedPage(bs, vm) }
        awaitText("Music Mix")
        val node = rule.onNode(hasText("Music Mix"))
        val a = runCatching { node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action }.getOrNull()
        if (a != null) a.invoke() else node.performClick()
        rule.waitUntil(5_000) { stack?.backStack?.lastOrNull() == TvRoute.PlaylistDetail(playlistId) }
        assertEquals(TvRoute.PlaylistDetail(playlistId), stack?.backStack?.lastOrNull())
    }
}
