package com.vayunmathur.screentime.platform

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression tests for the write-path review fixes (timers, wind-down, intervals, reconcile). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WritePathFixesTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    // --- Timer remainder (item 2) ---

    @Test fun remainingIsFullBudgetWhenUnused() {
        assertEquals(60 * 60_000L, remainingMillis(60, 0L))
    }

    @Test fun remainingSubtractsUsedAtMillisPrecision() {
        assertEquals(60 * 60_000L - 90_500L, remainingMillis(60, 90_500L))
        assertEquals(1L, remainingMillis(1, 59_999L))
    }

    @Test fun remainingFloorsAtZeroWhenExhaustedOrOver() {
        assertEquals(0L, remainingMillis(30, 30 * 60_000L))
        assertEquals(0L, remainingMillis(30, 31 * 60_000L))
        assertEquals(0L, remainingMillis(0, 0L))
    }

    // --- Next-midnight + throttled reconcile (item 1) ---

    @Test fun nextMidnightIsStartOfNextDay() {
        val zone = ZoneId.of("America/New_York")
        val from = ZonedDateTime.of(2026, 11, 21, 15, 30, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 22, 0, 0, 0, 0, zone), nextMidnight(from))
        val atMidnight = ZonedDateTime.of(2026, 11, 21, 0, 0, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 22, 0, 0, 0, 0, zone), nextMidnight(atMidnight))
    }

    @Test fun shouldReconcileHonoursThrottleAndClockChanges() {
        assertTrue(shouldReconcile(0L, 1_000L, 30_000L))
        assertFalse(shouldReconcile(1_000L, 20_000L, 30_000L))
        assertTrue(shouldReconcile(1_000L, 31_000L, 30_000L))
        assertTrue(shouldReconcile(50_000L, 10_000L, 30_000L)) // clock went backwards
    }

    // --- Interval keying (item 3) ---

    @Test fun intervalsKeyByPackageAndClass() {
        val events = listOf(
            FgEvent("com.multi", "A", isResume = true, timeStamp = 0L),
            FgEvent("com.multi", "B", isResume = true, timeStamp = 10_000L),
            FgEvent("com.multi", "A", isResume = false, timeStamp = 20_000L),
            FgEvent("com.multi", "B", isResume = false, timeStamp = 40_000L),
        )
        val intervals = buildIntervals(events, start = 0L, end = 100_000L)
        assertEquals(
            listOf(
                Triple("com.multi", 0L, 20_000L),
                Triple("com.multi", 10_000L, 40_000L),
            ),
            intervals,
        )
    }

    @Test fun intervalsCrossMidnightOpenAtWindowStart() {
        val events = listOf(
            // Fully before the window: opens provisionally, cancelled by its pause.
            FgEvent("com.old", "A", isResume = true, timeStamp = 100L),
            FgEvent("com.old", "A", isResume = false, timeStamp = 200L),
            // Crossed midnight: resumed before, paused inside.
            FgEvent("com.cross", "A", isResume = true, timeStamp = 500L),
            FgEvent("com.cross", "A", isResume = false, timeStamp = 1_500L),
            // Still foreground at the end: closed at the edge.
            FgEvent("com.open", "A", isResume = true, timeStamp = 1_800L),
        )
        val intervals = buildIntervals(events, start = 1_000L, end = 2_000L)
        assertEquals(
            setOf(
                Triple("com.cross", 1_000L, 1_500L),
                Triple("com.open", 1_800L, 2_000L),
            ),
            intervals.toSet(),
        )
    }

    // --- Wind-down applied-state restore (item 4) ---

    @Test fun windDownExitRestoresPriorGrayscaleNotOff() {
        ctx.getSharedPreferences("screentime_winddown_applied", Context.MODE_PRIVATE)
            .edit().clear().apply()
        val resolver = ctx.contentResolver
        Settings.Secure.putInt(resolver, "accessibility_display_daltonizer_enabled", 1)
        Settings.Secure.putString(resolver, "accessibility_display_daltonizer_mode", "2")
        val applier = WindDownApplier(ctx)
        applier.enter(grayscale = true, doNotDisturb = false)
        assertEquals("0", Settings.Secure.getString(resolver, "accessibility_display_daltonizer_mode"))
        // Exit takes no schedule flags: even if grayscale was toggled off in
        // the schedule mid-window, the prior user state (on, mode 2) returns.
        applier.exit()
        assertEquals(1, Settings.Secure.getInt(resolver, "accessibility_display_daltonizer_enabled", 0))
        assertEquals("2", Settings.Secure.getString(resolver, "accessibility_display_daltonizer_mode"))
    }

    @Test fun windDownExitRestoresPriorDndEvenIfPolicyRevokedMidWindow() {
        ctx.getSharedPreferences("screentime_winddown_applied", Context.MODE_PRIVATE)
            .edit().clear().apply()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val shadow = Shadows.shadowOf(nm)
        shadow.setNotificationPolicyAccessGranted(true)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        val applier = WindDownApplier(ctx)
        applier.enter(grayscale = false, doNotDisturb = true)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, nm.currentInterruptionFilter)
        shadow.setNotificationPolicyAccessGranted(false)
        applier.exit()
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, nm.currentInterruptionFilter)
        shadow.setNotificationPolicyAccessGranted(false)
    }

    @Test fun windDownReEnterDoesNotOverwritePriorValues() {
        ctx.getSharedPreferences("screentime_winddown_applied", Context.MODE_PRIVATE)
            .edit().clear().apply()
        val resolver = ctx.contentResolver
        Settings.Secure.putInt(resolver, "accessibility_display_daltonizer_enabled", 0)
        val applier = WindDownApplier(ctx)
        applier.enter(grayscale = true, doNotDisturb = false)
        applier.enter(grayscale = true, doNotDisturb = false) // re-enter must keep prior=0
        applier.exit()
        assertEquals(0, Settings.Secure.getInt(resolver, "accessibility_display_daltonizer_enabled", 1))
    }
}
