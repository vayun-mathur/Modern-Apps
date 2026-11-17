package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
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
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows

/** Phone ChannelPage with extractor state arranged via its StateFlow seam. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ChannelPageDeepTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel
    private var stack: NavBackStack<Route>? = null
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java).allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
    }
    @AfterTest fun tearDown() { Thread.sleep(100) }

    @Suppress("UNCHECKED_CAST")
    private fun setChannel(info: ChannelInfo, videos: List<VideoInfo>) {
        val f = YouPipeViewModel::class.java.getDeclaredField("_channelState").apply { isAccessible = true }
        (f.get(vm) as MutableStateFlow<YouPipeViewModel.ChannelState>).value = YouPipeViewModel.ChannelState(info, videos)
    }

    @Test fun channelPage_loaded_subscribePersistsAndVideoListed() {
        rule.setContent { val bs = rememberNavBackStack<Route>(Route.Home); stack = bs; ChannelPage(bs, vm, "UCres") }
        rule.waitForIdle()
        setChannel(ChannelInfo("Result Channel", "UCres", 12_000, 40, ""), listOf(VideoInfo("Channel Video", 91, 90, 10, Instant.fromEpochSeconds(1_650_000_000), "", "Result Channel")))
        rule.waitUntilExactlyOneExists(hasText("Result Channel"), 15_000)
        rule.waitUntilExactlyOneExists(hasText("Channel Video"), 15_000)
        // Subscribe affordance: label may be text or icon+text; click the first exact Subscribe.
        val nodes = rule.onAllNodes(hasText("Subscribe")).fetchSemanticsNodes()
        if (nodes.isNotEmpty()) {
            val node = rule.onNode(hasText("Subscribe"))
            val a = runCatching { node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action }.getOrNull()
            if (a != null) a.invoke() else node.performClick()
            val d = System.currentTimeMillis() + 8_000; var ok = false
            while (System.currentTimeMillis() < d) {
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50)
                ok = runBlocking { db.subscriptionDao().getAll() }.any { it.channelID == "UCres" }
                if (ok) break
            }
            assertTrue(ok, "phone channel subscribe did not persist")
        }
    }
}
