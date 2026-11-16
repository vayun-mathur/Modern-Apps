package com.vayunmathur.youpipe.tv

import android.app.Application
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
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
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.util.YouPipeViewModel
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

/** TV search field + downloads multiselect/delete. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TvSearchDownloadsDeepTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel
    private var stack: NavBackStack<TvRoute>? = null
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        TvLoadedVideo.id = null
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java).allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
        runBlocking {
            db.downloadedVideoDao().upsert(DownloadedVideo(id = 77, videoItem = VideoInfo("Offline Video", 77, 300, 8, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan"), filePath = "/dl/77.mp4", timestamp = Instant.fromEpochSeconds(1_700_300_000)))
            db.downloadedVideoDao().upsert(DownloadedVideo(id = 78, videoItem = VideoInfo("Second Video", 78, 200, 5, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan"), filePath = "/dl/78.mp4", timestamp = Instant.fromEpochSeconds(1_700_400_000)))
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

    @Test fun downloads_longPressSelectsAndDeleteRemoves() {
        themed { bs -> TvDownloadsPage(bs, vm) }
        awaitText("Offline Video")
        // Long-press the first download card (badge merged into its click node).
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        val idx = (0 until count).first { rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()[it].boundsInRoot.top > 80f }
        val card = rule.onAllNodes(hasClickAction())[idx]
        val longClick = runCatching { card.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnLongClick].action }.getOrNull()
        assertTrue(longClick?.invoke() == true, "no long click on download card")
        awaitText("Delete"); clickText("Delete")
        val d = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < d) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(30)
            if (runBlocking { db.downloadedVideoDao().getAllFlow().first() }.size == 1) break
        }
        assertEquals(1, runBlocking { db.downloadedVideoDao().getAllFlow().first() }.size)
    }

    @Test fun downloads_selectionCancel_restoresNormalHeader() {
        themed { bs -> TvDownloadsPage(bs, vm) }
        awaitText("Offline Video")
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        val idx = (0 until count).first { rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()[it].boundsInRoot.top > 80f }
        val card = rule.onAllNodes(hasClickAction())[idx]
        runCatching { card.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnLongClick].action }.getOrNull()?.invoke()
        awaitText("Cancel"); clickText("Cancel")
        awaitText("Offline Video")
        assertEquals(2, runBlocking { db.downloadedVideoDao().getAllFlow().first() }.size)
    }

    @Test fun search_typingUpdatesQueryAndEmptyHintShows() {
        themed { bs -> TvSearchPage(bs, vm) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput("lofi beats")
        rule.waitUntil(10_000) { vm.searchQuery.value == "lofi beats" }
        assertEquals("lofi beats", vm.searchQuery.value)
    }

    @Test fun search_watchUrlSubmit_playsVideo() {
        // A valid watch id is the Base64-url of an 8-byte long; build one for 77.
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(java.nio.ByteBuffer.allocate(8).putLong(77L).array())
        themed { bs -> TvSearchPage(bs, vm) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput("https://www.youtube.com/watch?v=$encoded")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitUntil(8_000) { stack?.backStack?.lastOrNull() == TvRoute.VideoPlayerRoute(77) }
        assertEquals(TvRoute.VideoPlayerRoute(77), stack?.backStack?.lastOrNull())
    }
}
