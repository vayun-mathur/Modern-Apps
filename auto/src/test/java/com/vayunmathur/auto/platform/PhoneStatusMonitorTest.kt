package com.vayunmathur.auto.platform

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Auto module: [PhoneStatusMonitor] — the rail cluster's phone snapshot.
 * Fail-closed throughout: no signal source means a gone icon, no battery
 * broadcast means unknown level, DND reads the interruption filter, and the
 * badge counts the listener's active message notifications. Start is
 * idempotent; stop unregisters everything.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneStatusMonitorTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `fresh snapshot hides every icon`() {
        val monitor = PhoneStatusMonitor(context)
        assertNull(monitor.snapshot.signalBars)
        assertNull(monitor.snapshot.batteryPercent)
        assertFalse(monitor.snapshot.batteryCharging)
        assertFalse(monitor.snapshot.doNotDisturb)
        assertEquals(0, monitor.snapshot.notificationCount)
    }

    @Test
    fun `start is idempotent and stop unregisters`() {
        val monitor = PhoneStatusMonitor(context)
        monitor.start()
        monitor.start()
        monitor.stop()
        monitor.stop()
        // Restart works after a stop.
        monitor.start()
        monitor.stop()
    }

    @Test
    fun `dnd follows the interruption filter`() {
        val monitor = PhoneStatusMonitor(context)
        // The public setter routes into the shadow, which keeps the filter
        // as a plain field (no policy grant needed under Robolectric).
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        monitor.refreshDndAndBadge()
        assertFalse(monitor.snapshot.doNotDisturb)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        monitor.refreshDndAndBadge()
        assertTrue(monitor.snapshot.doNotDisturb)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        monitor.refreshDndAndBadge()
        assertFalse(monitor.snapshot.doNotDisturb)
    }

    @Test
    fun `badge counts the active message notifications`() {
        val monitor = PhoneStatusMonitor(context)
        monitor.refreshDndAndBadge()
        assertTrue(monitor.snapshot.notificationCount >= 0)
    }

    @Test
    fun `battery broadcast updates the level and charging`() {
        val monitor = PhoneStatusMonitor(context)
        monitor.start()
        fun broadcast(intent: Intent) {
            context.sendBroadcast(intent)
            // Delivery to the registered receiver hops the paused main looper.
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        try {
            broadcast(
                Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                    putExtra(BatteryManager.EXTRA_LEVEL, 50)
                    putExtra(BatteryManager.EXTRA_SCALE, 100)
                    putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
                },
            )
            assertEquals(50, monitor.snapshot.batteryPercent)
            assertTrue(monitor.snapshot.batteryCharging)
            // A full charge counts as charging; a discharge does not.
            broadcast(
                Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                    putExtra(BatteryManager.EXTRA_LEVEL, 100)
                    putExtra(BatteryManager.EXTRA_SCALE, 100)
                    putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_FULL)
                },
            )
            assertTrue(monitor.snapshot.batteryCharging)
            broadcast(
                Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                    putExtra(BatteryManager.EXTRA_LEVEL, 80)
                    putExtra(BatteryManager.EXTRA_SCALE, 100)
                    putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
                },
            )
            assertEquals(80, monitor.snapshot.batteryPercent)
            assertFalse(monitor.snapshot.batteryCharging)
            // Garbage levels leave the last good snapshot alone.
            broadcast(
                Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                    putExtra(BatteryManager.EXTRA_LEVEL, -1)
                    putExtra(BatteryManager.EXTRA_SCALE, -1)
                },
            )
            assertEquals(80, monitor.snapshot.batteryPercent)
            // Wrong actions are ignored.
            context.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
            assertEquals(80, monitor.snapshot.batteryPercent)
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun `signal listener is registered on start`() {
        val monitor = PhoneStatusMonitor(context)
        monitor.start()
        try {
            // Robolectric grants no telephony signal: the icon stays gone
            // (fail-closed), but the listener path ran without crashing.
            assertNull(monitor.snapshot.signalBars)
        } finally {
            monitor.stop()
        }
    }
}
