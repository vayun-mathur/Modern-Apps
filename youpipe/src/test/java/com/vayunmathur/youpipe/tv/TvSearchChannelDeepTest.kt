package com.vayunmathur.youpipe.tv

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
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.ui.ChannelInfo
import com.vayunmathur.youpipe.ui.ItemInfo
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * TV search results + channel header, with ViewModel state arranged through
 * the same StateFlow seams the extractor writes (set via reflection because
 * search/channel flows have no internal test setter, unlike videoState).
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TvSearchChannelDeepTest {
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
    }
    @AfterTest fun tearDown() { TvLoadedVideo.id = null; Thread.sleep(100) }

    @Suppress("UNCHECKED_CAST")
    private fun <T> setFlow(name: String, value: T) {
        val f = YouPipeViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        (f.get(vm) as MutableStateFlow<T>).value = value
    }
    private fun awaitText(t: String) = rule.waitUntilExactlyOneExists(hasText(t), 15_000)
    private fun clickText(t: String) {
        val node = rule.onNode(hasText(t))
        val a = runCatching { node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action }.getOrNull()
        if (a != null) assertTrue(a.invoke() == true, "click $t") else node.performClick()
    }
    private fun themed(content: @androidx.compose.runtime.Composable (NavBackStack<TvRoute>) -> Unit) {
        rule.setContent { val bs = rememberNavBackStack<TvRoute>(TvRoute.Main(TV_RAIL_HOME)); stack = bs; MaTvTheme(TvAccent.Red) { content(bs) } }
    }

    @Test fun search_resultsGrid_showsChannelsAndVideos_andChannelOpens() {
        themed { bs -> TvSearchPage(bs, vm) }
        rule.waitForIdle()
        setFlow("_searchResults", listOf<ItemInfo>(
            ChannelInfo("Result Channel", "UCres", 12_000, 40, ""),
            VideoInfo("Result Video", 88, 90, 10, Instant.fromEpochSeconds(1_650_000_000), "", "Result Channel"),
        ))
        awaitText("Result Channel"); awaitText("Result Video")
        clickText("Result Channel")
        assertEquals(TvRoute.Channel("UCres"), stack?.backStack?.lastOrNull())
    }

    @Test fun search_suggestionsChips_submitSuggestion() {
        themed { bs -> TvSearchPage(bs, vm) }
        rule.waitForIdle()
        setFlow("_suggestions", listOf("lofi beats", "jazz piano"))
        awaitText("lofi beats"); clickText("jazz piano")
        rule.waitUntil(8_000) { vm.searchQuery.value == "jazz piano" }
        assertEquals("jazz piano", vm.searchQuery.value)
    }

    @Test fun channel_loadedHeader_subscribeAndVideoGrid() {
        themed { bs -> TvChannelPage(bs, vm, "UCres") }
        rule.waitForIdle()
        setFlow("_channelState", YouPipeViewModel.ChannelState(
            info = ChannelInfo("Result Channel", "UCres", 12_000, 40, ""),
            videos = listOf(VideoInfo("Channel Video", 91, 90, 10, Instant.fromEpochSeconds(1_650_000_000), "", "Result Channel")),
        ))
        awaitText("Result Channel"); awaitText("Channel Video")
        clickText("Subscribe")
        val d = System.currentTimeMillis() + 8_000; var ok = false
        while (System.currentTimeMillis() < d) {
            Thread.sleep(50)
            ok = kotlinx.coroutines.runBlocking { db.subscriptionDao().getAll() }.any { it.channelID == "UCres" }
            if (ok) break
        }
        assertTrue(ok, "subscribe did not persist")
        // Open the channel video via its card (focus + Enter, as TV cards require).
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        val idx = (0 until count).first { rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()[it].boundsInRoot.top > 120f }
        val card = rule.onAllNodes(hasClickAction())[idx]
        runCatching { card.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.RequestFocus].action?.invoke() }
        card.performKeyInput { pressKey(Key.Enter) }; card.performClick()
        rule.waitUntil(5_000) { stack?.backStack?.lastOrNull() == TvRoute.VideoPlayerRoute(91) }
    }
}
