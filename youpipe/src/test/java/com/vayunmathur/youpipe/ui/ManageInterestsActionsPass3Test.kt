package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.ChannelPreference
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.KeywordPreference
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows

/** Slice 2/3 pass 3: ManageInterests unblock/unmute/pin actions write through to preferences. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ManageInterestsActionsPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java)
            .allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
        runBlocking {
            db.channelPreferenceDao().upsert(ChannelPreference("blockedchan", blocked = true))
            db.keywordPreferenceDao().upsert(KeywordPreference("spoilerkw", muted = true))
            db.historyVideoDao().upsert(
                HistoryVideo(
                    progress = 1_000,
                    videoItem = VideoInfo("Cats And Dogs", 55, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Seedy"),
                    timestamp = Instant.fromEpochSeconds(1_700_100_000),
                ),
            )
        }
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun <T> awaitFlow(flow: Flow<T>, ready: (T) -> Boolean): T {
        var result: T? = null
        val job = CoroutineScope(Dispatchers.IO).launch {
            flow.collect { value -> if (ready(value)) result = value }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (result == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        job.cancel()
        return result ?: throw AssertionError("flow never became ready")
    }

    @Test
    fun unblockAndUnmuteClearPreferences() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main(4)))
        rule.setContent { ManageInterestsPage(stack, vm) }
        rule.waitUntilExactlyOneExists(hasText("blockedchan"), timeoutMillis = 10_000)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(app.getString(R.string.action_unblock)))
        rule.onNodeWithText(app.getString(R.string.action_unblock)).performClick()
        val channels = awaitFlow(vm.channelPreferences) { list -> list.none { it.channelKey == "blockedchan" && it.blocked } }
        assertTrue(channels.none { it.blocked })
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(app.getString(R.string.action_unmute)))
        rule.onNodeWithText(app.getString(R.string.action_unmute)).performClick()
        val keywords = awaitFlow(vm.keywordPreferences) { list -> list.none { it.keyword == "spoilerkw" && it.muted } }
        assertTrue(keywords.none { it.muted })
    }

    @Test
    fun pinTopChannelWritesPinnedPreference() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main(4)))
        rule.setContent { ManageInterestsPage(stack, vm) }
        // The top channel comes from watch history (author "Seedy").
        rule.waitUntilExactlyOneExists(hasText("seedy", ignoreCase = true), timeoutMillis = 10_000)
        rule.onNodeWithText(app.getString(R.string.action_pin)).performClick()
        val channels = awaitFlow(vm.channelPreferences) { list -> list.any { it.pinned } }
        assertEquals(listOf(true), channels.filter { it.pinned }.map { it.pinned })
    }
}
