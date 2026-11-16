package com.vayunmathur.clock

import android.content.Intent
import android.provider.AlarmClock
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * MainActivity's inbound-intent handling at create time: each AlarmClock
 * action is translated to an initial route (or handled in the background)
 * before any content composes.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityIntentTest {

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private fun createWith(intent: Intent) =
        Robolectric.buildActivity(MainActivity::class.java, intent).create()

    @Test fun showAlarmsIntentCreates() {
        val controller = createWith(Intent(AlarmClock.ACTION_SHOW_ALARMS))
        assertFalse(controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun setAlarmIntentCreatesDialogRoute() {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, 6)
            .putExtra(AlarmClock.EXTRA_MINUTES, 30)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "flight")
        val controller = createWith(intent)
        assertFalse(controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun setTimerIntentCreatesDialogRoute() {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, 240)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "eggs")
        val controller = createWith(intent)
        assertFalse(controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun plainLaunchCreates() {
        val controller = createWith(Intent(Intent.ACTION_MAIN))
        assertFalse(controller.get().isFinishing)
        controller.destroy()
    }
}
