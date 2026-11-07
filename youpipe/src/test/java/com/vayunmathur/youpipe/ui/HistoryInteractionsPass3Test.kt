package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
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
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: History selection mode - long-press, delete, navigate. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HistoryInteractionsPass3Test {

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
        backStack = NavBackStack(arrayOf<Route>(Route.Main(2)))
        runBlocking {
            db.historyVideoDao().upsert(
                HistoryVideo(
                    progress = 1_000,
                    videoItem = VideoInfo("First Watched", 55, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Chan"),
                    timestamp = Instant.fromEpochSeconds(1_700_100_000),
                ),
            )
            db.historyVideoDao().upsert(
                HistoryVideo(
                    progress = 2_000,
                    videoItem = VideoInfo("Second Watched", 56, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Chan"),
                    timestamp = Instant.fromEpochSeconds(1_700_200_000),
                ),
            )
        }
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun rowClick_navigatesToVideo() {
        rule.setContent { HistoryPage(backStack, vm) }
        rule.waitUntilExactlyOneExists(hasText("First Watched"), timeoutMillis = 10_000)
        rule.onNodeWithText("First Watched").performClick()
        rule.waitForIdle()
        assertEquals(Route.VideoPage(55), backStack.backStack.last())
    }

    @Test
    fun longPressSelectsAndFabDeletes() {
        rule.setContent { HistoryPage(backStack, vm) }
        rule.waitUntilExactlyOneExists(hasText("First Watched"), timeoutMillis = 10_000)
        rule.onNodeWithText("First Watched").performTouchInput { longClick() }
        rule.waitForIdle()
        // Selection mode: the delete FAB is up; tapping it removes the row.
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.waitUntilDoesNotExist(hasText("First Watched"), timeoutMillis = 10_000)
        rule.onNodeWithText("Second Watched").assertExists()
        val remaining = runBlocking { withTimeout(5_000) { vm.historyVideosByRecency.first { it.size == 1 } } }
        assertEquals(listOf("Second Watched"), remaining.map { it.videoItem.name })
    }

    @Test
    fun selectingSecondRowTooDeletesBoth() {
        rule.setContent { HistoryPage(backStack, vm) }
        rule.waitUntilExactlyOneExists(hasText("First Watched"), timeoutMillis = 10_000)
        rule.onNodeWithText("First Watched").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Second Watched").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.waitUntilDoesNotExist(hasText("Second Watched"), timeoutMillis = 10_000)
        val remaining = runBlocking {
            withTimeout(5_000) { vm.historyVideosByRecency.first { it.isEmpty() } }
        }
        assertEquals(emptyList(), remaining)
    }
}
