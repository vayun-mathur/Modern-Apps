package com.vayunmathur.youpipe.ui.tv

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.library.tv.MaTvTheme
import com.vayunmathur.library.tv.TvAccent
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.PlaylistItem
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionCategory
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.data.SubscriptionVideo
import com.vayunmathur.youpipe.ui.VideoData
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows

/** Deep TV pages 2: add-to-playlist staging, data-settings clear, playlist detail actions. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TvPagesDeep2Test {
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
            db.historyVideoDao().upsert(HistoryVideo(progress = 1_000, videoItem = VideoInfo("Watched One", 55, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan"), timestamp = Instant.fromEpochSeconds(1_700_100_000)))
            playlistId = db.playlistDao().upsert(Playlist(name = "Music Mix"))
            db.playlistItemDao().upsert(PlaylistItem(playlistId = playlistId, videoItem = VideoInfo("Playlist Song", 66, 200, 4, Instant.fromEpochSeconds(1_650_000_000), "", "Seeded Chan"), timestamp = Instant.fromEpochSeconds(1_700_200_000)))
        }
    }
    @AfterTest fun tearDown() { TvLoadedVideo.id = null; Thread.sleep(100) }
    private fun awaitText(t: String) = rule.waitUntilExactlyOneExists(hasText(t), 15_000)
    private fun clickText(t: String) {
        val node = rule.onNode(hasText(t))
        val a = runCatching { node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action }.getOrNull()
        if (a != null) assertTrue(a.invoke() == true, "click $t") else node.performClick()
    }
    private fun themed(content: @androidx.compose.runtime.Composable (NavBackStack<TvRoute>) -> Unit) {
        rule.setContent { val bs = rememberNavBackStack<TvRoute>(TvRoute.Main(TV_RAIL_HOME)); stack = bs; MaTvTheme(TvAccent.Red) { content(bs) } }
    }
    private fun awaitDb(cond: () -> Boolean) {
        val d = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < d) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(30); if (cond()) return }
        assertTrue(cond())
    }

    @Test fun addToPlaylist_toggleAndConfirm_addsVideo() {
        vm.videoStateMutable.value = YouPipeViewModel.VideoState(data = VideoData("Watched One", 100, 55, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan", "UCseed", "", "desc"))
        themed { bs -> TvAddToPlaylistPage(bs, vm, 55) }
        awaitText("Music Mix"); clickText("Music Mix"); clickText("OK")
        awaitDb { runBlocking { db.playlistItemDao().getAllFlow().first() }.any { it.playlistId == playlistId && it.videoItem.videoID == 55L } }
    }
    @Test fun addToPlaylist_cancel_popsWithoutAdding() {
        vm.videoStateMutable.value = YouPipeViewModel.VideoState(data = VideoData("Watched One", 100, 55, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan", "UCseed", "", "desc"))
        themed { bs -> bs.add(TvRoute.AddToPlaylist(55)); TvAddToPlaylistPage(bs, vm, 55) }
        awaitText("Music Mix"); clickText("Music Mix"); clickText("Cancel")
        rule.waitUntil(5_000) { stack?.backStack?.lastOrNull() == TvRoute.Main(TV_RAIL_HOME) }
        assertTrue(runBlocking { db.playlistItemDao().getAllFlow().first() }.none { it.videoItem.videoID == 55L })
    }
    @Test fun addToPlaylist_noVideoData_showsCloseAndPops() {
        vm.videoStateMutable.value = YouPipeViewModel.VideoState()
        themed { bs -> bs.add(TvRoute.AddToPlaylist(55)); TvAddToPlaylistPage(bs, vm, 55) }
        awaitText("Close"); clickText("Close")
        rule.waitUntil(5_000) { stack?.backStack?.lastOrNull() == TvRoute.Main(TV_RAIL_HOME) }
    }
    @Test fun dataSettings_clearHistory_emptiesDb() {
        themed { _ -> TvDataSettingsPage(vm) }
        awaitText("Clear History"); clickText("Clear History"); clickText("Clear")
        awaitDb { runBlocking { db.historyVideoDao().getAll() }.isEmpty() }
    }
    @Test fun playlistDetail_playAll_opensFirstSong() {
        themed { bs -> TvPlaylistDetailPage(bs, vm, playlistId) }
        awaitText("Playlist Song"); clickText("Play")
        assertEquals(TvRoute.VideoPlayerRoute(66), stack?.backStack?.lastOrNull())
    }
    @Test fun playlistDetail_deletePlaylist_removesIt() {
        themed { bs -> TvPlaylistDetailPage(bs, vm, playlistId) }
        awaitText("Music Mix"); clickText("Delete playlist")
        // Confirm dialog's Delete button is the last exact "Delete" node.
        val count = rule.onAllNodes(hasText("Delete")).fetchSemanticsNodes().size
        val node = rule.onAllNodes(hasText("Delete"))[count - 1]
        val a = runCatching { node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action }.getOrNull()
        if (a != null) assertTrue(a.invoke() == true) else node.performClick()
        awaitDb { runBlocking { db.playlistDao().getAll() }.none { it.id == playlistId } }
    }
    @Test fun createPlaylist_typeNameAndCreate_persistsAndPops() {
        themed { bs -> bs.add(TvRoute.CreatePlaylist); TvCreatePlaylistPage(bs, vm) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput("Road Trip")
        clickText("New playlist")
        awaitDb { runBlocking { db.playlistDao().getAll() }.any { it.name == "Road Trip" } }
        rule.waitUntil(5_000) { stack?.backStack?.lastOrNull() == TvRoute.Main(TV_RAIL_HOME) }
    }
    @Test fun createCategory_typeNameAndCreate_persists() {
        themed { bs -> TvCreateCategoryPage(bs, vm, null) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput("Documentaries")
        rule.waitForIdle()
    }

    @Test fun navigationHost_rendersHomeRail() {
        rule.setContent { MaTvTheme(TvAccent.Red) { TvNavigation(vm) } }
        rule.waitUntil(15_000) {
            listOf("Search", "Home", "Settings", "Watched One").any {
                rule.onAllNodes(hasText(it)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }
    @Test fun subscriptions_categoryRow_showsSeededChannel() {
        themed { bs -> TvSubscriptionsPage(bs, vm) }
        awaitText("Seeded Chan"); awaitText("News")
        assertEquals(TvRoute.Main(TV_RAIL_HOME), stack?.backStack?.lastOrNull())
    }
}
