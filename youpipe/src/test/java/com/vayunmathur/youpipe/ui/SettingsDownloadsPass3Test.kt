package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: downloads selection, settings toggles, selectors, clear-history. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsDownloadsPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel
    private lateinit var backStack: NavBackStack<Route>

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java)
            .allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
        backStack = NavBackStack(arrayOf<Route>(Route.Main(3)))
        runBlocking {
            db.downloadedVideoDao().upsert(
                DownloadedVideo(
                    id = 71,
                    videoItem = VideoInfo("Offline One", 71, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Chan"),
                    filePath = "/dl/71.mp4", timestamp = Instant.fromEpochSeconds(1_700_100_000),
                ),
            )
            db.downloadedVideoDao().upsert(
                DownloadedVideo(
                    id = 72,
                    videoItem = VideoInfo("Offline Two", 72, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Chan"),
                    filePath = "/dl/72.mp4", timestamp = Instant.fromEpochSeconds(1_700_200_000),
                ),
            )
            db.historyVideoDao().upsert(
                HistoryVideo(
                    progress = 1,
                    videoItem = VideoInfo("Watched", 55, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Chan"),
                    timestamp = Instant.fromEpochSeconds(1_700_100_000),
                ),
            )
        }
    }

    @AfterTest
    fun tearDown() {
        // Deliberately not closed: the previous test's ViewModel still has
        // flow collectors armed on the paused main looper, and a collector
        // landing after close crashes whichever test idles the looper next.
        // The in-memory database becomes garbage with its sandbox instead.
    }

    private fun awaitVmValue(read: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!read() && System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            Thread.sleep(25)
        }
        assertTrue(read(), "view model state never updated")
    }

    @Test
    fun downloads_longPressSelectAndDelete() {
        rule.setContent { DownloadedVideosPage(backStack, vm) }
        rule.waitUntilExactlyOneExists(hasText("Offline One"), timeoutMillis = 10_000)
        rule.onNodeWithText("Offline One").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Offline Two").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.waitUntilDoesNotExist(hasText("Offline One"), timeoutMillis = 10_000)
        rule.waitUntilDoesNotExist(hasText("Offline Two"), timeoutMillis = 10_000)
        val remaining = runBlocking {
            withTimeout(5_000) { vm.downloadedVideosByRecency.first { it.isEmpty() } }
        }
        assertEquals(emptyList(), remaining)
    }

    @Test
    fun downloads_rowClickNavigates() {
        rule.setContent { DownloadedVideosPage(backStack, vm) }
        rule.waitUntilExactlyOneExists(hasText("Offline Two"), timeoutMillis = 10_000)
        rule.onNodeWithText("Offline Two").performClick()
        rule.waitForIdle()
        assertEquals(Route.VideoPage(72), backStack.backStack.last())
    }

    @Test
    fun generalSettings_togglesAndSelectorsWriteThrough() {
        rule.setContent {
            val stack = NavBackStack(arrayOf<Route>(Route.Main(4)))
            GeneralSettingsPage(stack, vm)
        }
        rule.onNodeWithText(app.getString(R.string.label_dearrow)).performClick()
        awaitVmValue { vm.deArrowEnabled.value }
        rule.onNodeWithText(app.getString(R.string.label_keep_controls_visible)).performClick()
        awaitVmValue { vm.keepPlayerControlsVisible.value }
        rule.onNodeWithText(app.getString(R.string.label_notification_style_progress)).performClick()
        awaitVmValue { vm.notificationStyleProgressBar.value }

        // Default-page selector: open the menu and pick a different home.
        rule.onNodeWithText(app.getString(R.string.label_default_page)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(app.getString(R.string.title_history)).performClick()
        awaitVmValue { vm.defaultPage.value == "history" }
    }

    @Test
    fun dataSettings_clearHistoryConfirmsAndClears() {
        rule.setContent {
            val stack = NavBackStack(arrayOf<Route>(Route.Main(4)))
            DataSettingsPage(stack, vm)
        }
        rule.onNodeWithText(app.getString(R.string.clear_history)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(app.getString(R.string.are_you_sure_you_want_to_clear_all_watch))
            .assertIsDisplayed()
        rule.onNodeWithText(
            app.getString(com.vayunmathur.library.ui.R.string.clear),
        ).performClick()
        val remaining = runBlocking {
            withTimeout(5_000) { vm.historyVideosByRecency.first { it.isEmpty() } }
        }
        assertEquals(emptyList(), remaining)
    }
}
