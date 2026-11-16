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
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/** Phone VideoPage over a downloaded video: detail screen, tabs, player shell. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(shadows = [ShadowPendingMediaControllerBuilder::class])
class PhoneVideoDeepTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel
    private var stack: NavBackStack<Route>? = null
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java).allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
        runBlocking {
            db.downloadedVideoDao().upsert(DownloadedVideo(id = 77, videoItem = VideoInfo("Offline Video", 77, 300, 8, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan"), filePath = "/dl/77.mp4", timestamp = Instant.fromEpochSeconds(1_700_300_000)))
        }
    }
    @AfterTest fun tearDown() { Thread.sleep(100) }
    private fun awaitText(t: String) = rule.waitUntilExactlyOneExists(hasText(t), 20_000)

    @Test fun videoPage_downloaded_rendersDetailAndTabs() {
        rule.setContent { val bs = rememberNavBackStack<Route>(Route.Main(0)); stack = bs; VideoPage(bs, vm, 77) }
        awaitText("Offline Video")
        // Tabs exist and are tappable; each pager page must compose without crashing.
        rule.onNode(hasText("Related Videos")).performClick(); rule.waitForIdle()
        rule.onNode(hasText("Description")).performClick(); rule.waitForIdle()
        rule.onNode(hasText("Comments")).performClick(); rule.waitForIdle()
        assertTrue(stack?.backStack?.lastOrNull() == Route.Main(0))
    }
    @Test fun videoPage_unknown_setsErrorDialogAndGoBackPops() {
        rule.setContent { val bs = rememberNavBackStack<Route>(Route.Main(0), Route.VideoPage(999_777)); stack = bs; VideoPage(bs, vm, 999_777) }
        awaitText("Go Back")
        rule.onNode(hasText("Go Back")).performClick()
        rule.waitUntil(5_000) { stack?.backStack?.lastOrNull() == Route.Main(0) }
    }
}
