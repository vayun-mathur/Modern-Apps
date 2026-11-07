package com.vayunmathur.youpipe.ui

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: orientation lock lifecycle and history selection toggle-off. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OrientationHistoryPass3Test {

    @get:Rule
    val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

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
        }
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun lockScreenOrientation_appliesAndRestoresOnDispose() {
        var locked by mutableStateOf(true)
        val original = rule.activity.requestedOrientation
        rule.setContent { if (locked) LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE) }
        rule.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, rule.activity.requestedOrientation)
        rule.runOnUiThread { locked = false }
        rule.waitForIdle()
        assertEquals(original, rule.activity.requestedOrientation)
    }

    @Test
    fun historyCheckbox_deselectsAndRowClickNavigatesAgain() {
        rule.setContent { HistoryPage(backStack, vm) }
        rule.waitUntilExactlyOneExists(hasText("First Watched"), timeoutMillis = 10_000)
        rule.onNodeWithText("First Watched").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Delete").assertExists()
        // Toggle the selection checkbox off: selection mode ends, FAB goes away.
        rule.onAllNodes(isToggleable())[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Delete").assertDoesNotExist()
        rule.onNodeWithText("First Watched").performClick()
        rule.waitForIdle()
        assertEquals(Route.VideoPage(55), backStack.backStack.last())
    }
}
