package com.vayunmathur.clock.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.clock.MainActivity
import com.vayunmathur.clock.Navigation
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.platform.AlarmActivity
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tests for the page binders, [Navigation], [Route] serialization, MainActivity and AlarmActivity. */
@RunWith(AndroidJUnit4::class)
class ClockPagesUiTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    @get:Rule
    val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun vm() = ClockViewModel(ctx as android.app.Application, ClockRepository.get(ctx))
    private fun ds() = DataStoreUtils.getInstance(ctx)

    @Test
    fun routeSerializationRoundTrips() {
        val routes: List<Route> = listOf(
            Route.Main(2), Route.Alarm, Route.Clock, Route.Timer, Route.Stopwatch,
            Route.AlarmSettings, Route.SelectTimeZonesDialog,
            Route.NewTimerDialog(300, "tea"), Route.NewAlarmDialog(7, 30, "wake", arrayListOf(1, 3), false),
        )
        for (route in routes) {
            val encoded = Json.encodeToString(Route.serializer(), route)
            val decoded = Json.decodeFromString(Route.serializer(), encoded)
            assertEquals(route.toString(), decoded.toString(), "route $route via $encoded")
        }
        val setTime = Route.AlarmSetTimeDialog(5, kotlinx.datetime.LocalTime(8, 15))
        val encoded = Json.encodeToString(Route.serializer(), setTime)
        assertEquals(setTime.toString(), Json.decodeFromString(Route.serializer(), encoded).toString())
    }

    @Test
    fun tabsRenderWithStopwatchInitialTab() {
        val backStack = NavBackStack(arrayOf<Route>(Route.Main()))
        rule.setContent { ClockTabs(backStack, ds(), vm(), initialTab = 3) }
        rule.waitForIdle()
        // Bottom navigation labels for the four tabs are composed.
        rule.onNodeWithText("Alarm").assertExists()
    }

    @Test
    fun navigationClockRouteRenders() {
        rule.setContent { Navigation(ds(), vm(), Route.Clock) }
        rule.waitForIdle()
    }

    @Test
    fun stopwatchPageBindsViewModel() {
        rule.setContent { StopwatchPage(vm()) }
        rule.onNodeWithText("Laps").assertIsDisplayed()
    }

    @Test
    fun clockPageBindsViewModel() {
        val backStack = NavBackStack(arrayOf<Route>(Route.Main()))
        rule.setContent { ClockPage(backStack, ds(), vm()) }
        rule.waitForIdle()
    }

    @Test
    fun mainActivityLaunches() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).create().start().resume()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun alarmActivityWithoutIdFinishes() {
        val controller = Robolectric.buildActivity(AlarmActivity::class.java, Intent()).create()
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }

    @Test
    fun alarmActivityWithUnknownIdFinishesAfterLoadFails() {
        val intent = Intent(ctx, AlarmActivity::class.java).apply { putExtra("ALARM_ID", 987654L) }
        val controller = Robolectric.buildActivity(AlarmActivity::class.java, intent).create().start()
        // DB lookup fails under Robolectric, which finishes the activity.
        val deadline = System.currentTimeMillis() + 5000
        while (!controller.get().isFinishing && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }
}
