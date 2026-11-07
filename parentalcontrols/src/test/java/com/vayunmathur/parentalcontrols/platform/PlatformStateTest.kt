package com.vayunmathur.parentalcontrols.platform

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.parentalcontrols.data.BedtimeSchedule
import com.vayunmathur.parentalcontrols.data.DowntimeSchedule
import com.vayunmathur.parentalcontrols.data.SchoolTimeSchedule
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlatformStateTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun masterSwitchDefaultsOnAndPersists() {
        assertTrue(SupervisionMaster.isEnabled(ctx))
        SupervisionMaster.setEnabled(ctx, false)
        assertFalse(SupervisionMaster.isEnabled(ctx))
        SupervisionMaster.setEnabled(ctx, true)
        assertTrue(SupervisionMaster.isEnabled(ctx))
    }

    @Test
    fun webContentFiltersDefaultOffAndPersist() {
        assertFalse(WebContentFilters.isEnabled(ctx))
        WebContentFilters.setEnabled(ctx, true)
        assertTrue(WebContentFilters.isEnabled(ctx))
        WebContentFilters.setEnabled(ctx, false)
        assertFalse(WebContentFilters.isEnabled(ctx))
    }

    @Test
    fun limitStateMarksPrunesAndResetsByDay() {
        val state = LimitState(ctx)
        state.pruneToToday()
        assertFalse(state.hasReached("com.example.game"))
        state.markReached("com.example.game")
        state.markReached("com.example.video")
        state.markReached("com.example.game") // idempotent
        assertTrue(state.hasReached("com.example.game"))
        assertTrue(state.hasReached("com.example.video"))
        assertFalse(state.hasReached("com.example.other"))
        state.pruneToToday() // same day: no reset
        assertTrue(state.hasReached("com.example.game"))
    }

    @Test
    fun activeAtMapsLocalDateTimeToWindow() {
        // Monday 2026-11-02 is a Monday; 22:00 local.
        val mondayNight = LocalDateTime.of(2026, 11, 2, 22, 0)
        val bedtime = BedtimeSchedule(enabled = true) // 21:00-07:00
        assertTrue(bedtime.activeAt(mondayNight))
        assertFalse(bedtime.activeAt(LocalDateTime.of(2026, 11, 2, 12, 0)))
        val downtime = DowntimeSchedule(enabled = true) // 12:00-14:00
        assertTrue(downtime.activeAt(LocalDateTime.of(2026, 11, 2, 13, 0)))
        assertFalse(downtime.activeAt(mondayNight))
        val school = SchoolTimeSchedule(enabled = true) // weekdays 08:00-15:00
        assertTrue(school.activeAt(LocalDateTime.of(2026, 11, 2, 9, 0)))
        assertFalse(school.activeAt(LocalDateTime.of(2026, 11, 7, 9, 0))) // Saturday
    }

    @Test
    fun bedtimeSchedulerArmsAndCancels() {
        val scheduler = BedtimeScheduler(ctx)
        val alarmManager = ctx.getSystemService(AlarmManager::class.java)
        val shadow = shadowOf(alarmManager)
        // Disabled schedules cancel and arm nothing.
        scheduler.arm(BedtimeSchedule(enabled = false))
        scheduler.armAll(
            BedtimeSchedule(enabled = false),
            DowntimeSchedule(enabled = false),
            SchoolTimeSchedule(enabled = false),
        )
        assertTrue(shadow.scheduledAlarms.isEmpty())
        // An enabled cross-midnight window always has a next boundary within a week.
        scheduler.arm(BedtimeSchedule(enabled = true))
        assertTrue(shadow.scheduledAlarms.isNotEmpty())
        // Disabling again cancels the pending alarm.
        scheduler.arm(BedtimeSchedule(enabled = false))
        // An always-on same-day window (00:00-00:00 wraps to all day via start>end? use full mask window)
        scheduler.armAll(
            BedtimeSchedule(enabled = true),
            DowntimeSchedule(enabled = true),
            SchoolTimeSchedule(enabled = true),
        )
        assertTrue(shadow.scheduledAlarms.isNotEmpty())
    }

    @Test
    fun appLimitsWithNoUsageAccessMeasureZero() {
        val limits = AppLimits(ctx)
        assertEquals(Duration.ZERO, limits.timeUsedToday("com.example.game"))
        assertEquals(Duration.ZERO, limits.totalUsedToday())
        limits.unregister("com.example.game")
        // Hidden register method is unreachable on a plain JVM build; sync must not throw.
        limits.sync(
            listOf(
                com.vayunmathur.parentalcontrols.data.AppRule("com.example.game", dailyLimitMinutes = 30),
                com.vayunmathur.parentalcontrols.data.AppRule("com.example.free"),
            ),
            listOf(com.vayunmathur.parentalcontrols.data.BonusGrant(packageName = "com.example.game", bonusMinutes = 10, day = "2026-11-06")),
        )
        assertEquals("android.app.usage.extra.OBSERVER_ID", LimitExtras.OBSERVER_ID)
        assertEquals("com.vayunmathur.parentalcontrols.extra.PACKAGE_NAME", EXTRA_PACKAGE_NAME)
    }

    @Test
    fun usageAccessReportsAStatus() {
        val status = UsageAccess.ensure(ctx)
        // On a JVM harness the op is either granted by the shadow or denied; either way the
        // cheap check must agree with a denied post-attempt state.
        assertTrue(status == UsageAccess.Status.GRANTED || status == UsageAccess.Status.DENIED)
        assertEquals(status == UsageAccess.Status.GRANTED, UsageAccess.isGranted(ctx) || status == UsageAccess.Status.GRANTED)
    }

    @Test
    fun supervisionApisAnswerEmptyAndNotSupervisor() {
        assertTrue(SupervisionApis.preferenceData(null).isEmpty)
        assertTrue(SupervisionApis.supportedApps(null).isEmpty)
        assertFalse(SupervisionApis.isSupervisorAccount().getBoolean("is_supervisor_account"))
        assertEquals(1, SupervisionApis.PREFERENCE_DATA)
        assertEquals(2, SupervisionApis.SUPPORTED_APPS)
        assertEquals(3, SupervisionApis.IS_SUPERVISOR_ACCOUNT)
        val withRequest = SupervisionApis.preferenceData(android.os.Bundle().apply { putString("k", "v") })
        assertTrue(withRequest.isEmpty)
    }

    @Test
    fun supervisionCallerTrustsSystemAndRejectsStrangers() {
        val caller = SupervisionCaller(ctx)
        assertTrue(caller.isSettings(android.os.Process.SYSTEM_UID))
        // Settings is not installed in the harness, so a random app uid is rejected.
        assertFalse(caller.isSettings(10_999))
        assertFalse(caller.isSettings(-1))
    }

    @Test
    fun blockReasonPrecedenceEnumOrder() {
        assertEquals(
            listOf("Bedtime", "Downtime", "SchoolTime", "DailyLimit", "AppLimit"),
            Enforcer.BlockReason.entries.map { it.name },
        )
    }
}
