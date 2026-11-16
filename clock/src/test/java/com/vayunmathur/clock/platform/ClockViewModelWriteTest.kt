package com.vayunmathur.clock.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.data.Timer
import kotlinx.datetime.LocalTime
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Clock

/**
 * ViewModel write paths and background intent dispatch.
 *
 * The repository is SQLCipher-backed, whose native library does not exist on
 * the host JVM: writes launched by the ViewModel fail in their IO coroutine
 * (the existing receiver tests rely on the same fail-soft behaviour), so what
 * is pinned here is the synchronous contract - routes returned, no exception
 * reaching the caller, and the default-alarm construction used on the way in.
 */
@RunWith(RobolectricTestRunner::class)
class ClockViewModelWriteTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    /**
     * The DB writes below die in background coroutines with the SQLCipher native
     * error, which kotlinx-coroutines-test stores process-wide and rethrows at
     * the next runTest-based (compose) test. Drain that store here, where the
     * error is expected, so it cannot fail an unrelated later test.
     */
    private fun drainUncaughtDbErrors() {
        Thread.sleep(400)
        // Background DB coroutines can take a while to reach the failing open;
        // drain repeatedly so a late one cannot leak into the next compose test.
        repeat(8) {
            try {
                kotlinx.coroutines.test.runTest { }
            } catch (e: IllegalStateException) {
                if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
            }
            Thread.sleep(400)
        }
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private fun vm() = ClockViewModel(app, ClockRepository.get(app))

    @Test fun upsertAndDeleteDoNotThrowSynchronously() {
        val vm = vm()
        val timer = Timer(false, "tea", Clock.System.now(), 3.minutes, 3.minutes, id = 5)
        val alarm = vm.buildDefaultAlarm(LocalTime(7, 0), "gym", 0)
        vm.upsert(timer)
        vm.upsert(alarm)
        vm.delete(timer)
        vm.delete(alarm)
        // Give the IO coroutines a chance to run into the (host-unavailable) database;
        // the calls above must already have returned normally.
        Thread.sleep(300)
        drainUncaughtDbErrors()
        assertEquals("gym", alarm.name)
        assertEquals(LocalTime(7, 0), alarm.time)
    }

    @Test fun skipUiSetAlarmBuildsDefaultAndReturnsNoRoute() {
        val vm = vm()
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, 6)
            putExtra(AlarmClock.EXTRA_MINUTES, 45)
            putExtra(AlarmClock.EXTRA_MESSAGE, "run")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, arrayListOf(2, 4, 6))
        }
        assertNull(vm.handleIncomingIntent(intent))
        drainUncaughtDbErrors()
    }

    @Test fun skipUiSetTimerReturnsNoRouteAndShowTimersIsUnhandled() {
        val vm = vm()
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, 90)
            putExtra(AlarmClock.EXTRA_MESSAGE, "eggs")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        assertNull(vm.handleIncomingIntent(intent))
        assertNull(vm.handleIncomingIntent(Intent(AlarmClock.ACTION_SHOW_TIMERS)))
        assertEquals(Route.Alarm, vm.handleIncomingIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS)))
        drainUncaughtDbErrors()
    }

    @Test fun setAlarmWithoutHourOrMinutesFallsBackToDialogRoute() {
        val vm = vm()
        val route = vm.handleIncomingIntent(
            Intent(AlarmClock.ACTION_SET_ALARM).apply { putExtra(AlarmClock.EXTRA_SKIP_UI, true) },
        )
        // skipUi cannot be honoured without a time: the dialog route is the answer.
        assertTrue(route is Route.NewAlarmDialog && route.hour == null && route.minutes == null)
    }
}
