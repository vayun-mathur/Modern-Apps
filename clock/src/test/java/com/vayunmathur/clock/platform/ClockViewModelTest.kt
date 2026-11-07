package com.vayunmathur.clock.platform

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.data.Timer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.runner.RunWith
import com.vayunmathur.clock.FakeAndroidKeyStore
import org.junit.Before
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock

/** Tests for [ClockViewModel]: default alarms, intent dispatch, stopwatch and DAO passthrough. */
@RunWith(RobolectricTestRunner::class)
class ClockViewModelTest {

    class OtherViewModel(app: Application) : androidx.lifecycle.AndroidViewModel(app)
    @Before fun setUp() = FakeAndroidKeyStore.install()


    private val app: Application
        get() = ApplicationProvider.getApplicationContext<Context>() as Application

    private fun vm() = ClockViewModel(app, ClockRepository.get(app))

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            if (cond()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out: $desc")
    }

    private fun timer(name: String) = Timer(
        isRunning = false, name = name, remainingStartTime = Clock.System.now(),
        remainingLength = 3.minutes, totalLength = 3.minutes,
    )

    @Test
    fun buildDefaultAlarmUsesDefaults() {
        val alarm = vm().buildDefaultAlarm(LocalTime(6, 45), "gym", 0b101)
        assertEquals(LocalTime(6, 45), alarm.time)
        assertEquals("gym", alarm.name)
        assertTrue(alarm.enabled)
        assertEquals(0b101, alarm.days)
        assertTrue(alarm.vibrate)
    }

    @Test
    fun timerRemainingClampsAndCountsDown() {
        val now = Clock.System.now()
        val paused = Timer(false, "p", now, 5.minutes, 5.minutes)
        assertEquals(5.minutes, timerRemaining(paused, now))
        val running = Timer(true, "r", now - 90.seconds, 5.minutes, 5.minutes)
        val remaining = timerRemaining(running, now)
        assertTrue(remaining in 3.minutes..4.minutes, "remaining=$remaining")
        val overdue = Timer(true, "o", now - 10.minutes, 5.minutes, 5.minutes)
        assertEquals(kotlin.time.Duration.ZERO, timerRemaining(overdue, now))
    }

    @Test
    fun handleIntentNullAndUnknown() {
        val vm = vm()
        assertNull(vm.handleIncomingIntent(null))
        assertNull(vm.handleIncomingIntent(Intent("com.example.WHATEVER")))
    }

    @Test
    fun handleShowAlarmsRoutesToAlarmTab() {
        assertEquals(Route.Alarm, vm().handleIncomingIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS)))
    }

    @Test
    fun handleSetAlarmWithoutSkipUiReturnsDialogRoute() {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, 7)
            putExtra(AlarmClock.EXTRA_MINUTES, 15)
            putExtra(AlarmClock.EXTRA_MESSAGE, "wake up")
            putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, arrayListOf(2, 4))
        }
        val route = vm().handleIncomingIntent(intent)
        assertTrue(route is Route.NewAlarmDialog, "route=$route")
        route as Route.NewAlarmDialog
        assertEquals(7, route.hour)
        assertEquals(15, route.minutes)
        assertEquals("wake up", route.message)
        assertEquals(arrayListOf(2, 4), route.days)
        assertFalse(route.skipUi)
    }

    @Test
    fun handleSetTimerReturnsDialogRoute() {
        val vm = vm()
        val uiIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, 300)
            putExtra(AlarmClock.EXTRA_MESSAGE, "tea")
        }
        val route = vm.handleIncomingIntent(uiIntent)
        assertTrue(route is Route.NewTimerDialog && route.lengthSeconds == 300 && route.message == "tea")
    }

    @Test
    fun stopwatchToggleLapReset() {
        val vm = vm()
        // Stopwatch state is persisted in DataStore and shared across tests in this
        // process; start from a clean reset rather than assuming a fresh state.
        vm.resetStopwatch()
        assertFalse(vm.stopwatchRunning.value)
        vm.toggleStopwatch()
        assertTrue(vm.stopwatchRunning.value)
        vm.addLap()
        assertEquals(1, vm.lapTimes.value.size)
        vm.toggleStopwatch()
        assertFalse(vm.stopwatchRunning.value)
        vm.resetStopwatch()
        assertTrue(vm.lapTimes.value.isEmpty())
        assertEquals(kotlin.time.Duration.ZERO, vm.stopwatchCountingTime.value)
    }

    @Test
    fun citiesLoadFromAssets() {
        val vm = vm()
        await("cities loaded") { vm.cities.value != null }
        assertTrue(vm.cities.value!!.isNotEmpty())
    }

    @Test
    fun factoryCreatesAndRejects() {
        val factory = ClockViewModelFactory(app, ClockRepository.get(app))
        assertTrue(factory.create(ClockViewModel::class.java) is ClockViewModel)
        val error = runCatching { factory.create(OtherViewModel::class.java) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "error=$error")
    }
}
