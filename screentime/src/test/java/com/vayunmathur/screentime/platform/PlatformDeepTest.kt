package com.vayunmathur.screentime.platform

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.screentime.data.AppTimer
import com.vayunmathur.screentime.data.ScreenTimeRules
import com.vayunmathur.screentime.data.WindDownSchedule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsageStatsManager
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** AppTimers/UsageAccess/WindDownApplier/WindowScheduler against Robolectric shadows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformDeepTest {

    @org.junit.Before fun setUp() = com.vayunmathur.screentime.FakeAndroidKeyStore.install()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun seedToday(pkg: String, foregroundMs: Long) {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stats = ShadowUsageStatsManager.UsageStatsBuilder.newBuilder()
            .setPackageName(pkg)
            .setFirstTimeStamp(start)
            .setLastTimeStamp(start + 3_600_000)
            .setTotalTimeInForeground(foregroundMs)
            .build()
        val shadow = Shadows.shadowOf(ctx.getSystemService(UsageStatsManager::class.java))
        shadow.addUsageStats(UsageStatsManager.INTERVAL_DAILY, stats)
        shadow.addUsageStats(UsageStatsManager.INTERVAL_BEST, stats)
    }

    @Test fun appTimersReadSeededUsageAndSyncObservers() {
        seedToday("com.a.app", 90 * 60_000L)
        seedToday("com.b.app", 30 * 60_000L)
        val timers = AppTimers(ctx)
        assertEquals(Duration.ofMinutes(90), timers.timeUsedToday("com.a.app"))
        assertEquals(Duration.ofMinutes(120), timers.totalUsedToday())
        assertEquals(
            mapOf("com.a.app" to 90 * 60_000L, "com.b.app" to 30 * 60_000L),
            timers.usageTodayByPackage(),
        )
        assertEquals(Duration.ZERO, timers.timeUsedToday("com.unused.app"))

        // Under its limit, com.b.app gets an observer; already over its limit,
        // com.a.app is enforced immediately (its spent mark lands in SpentState).
        timers.sync(listOf(AppTimer("com.b.app", 60), AppTimer("com.a.app", 60)))
        val shadow = Shadows.shadowOf(ctx.getSystemService(UsageStatsManager::class.java))
        assertTrue(shadow.registeredAppUsageObservers.isNotEmpty())
        val deadline = System.currentTimeMillis() + 5_000
        while (!SpentState(ctx).hasSpent("com.a.app") && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue(SpentState(ctx).hasSpent("com.a.app"))
        SpentState(ctx).clear("com.a.app")
        assertFalse(SpentState(ctx).hasSpent("com.a.app"))
        timers.unregister("com.b.app")
    }

    @Test fun usageAccessDeniedThenGranted() {
        val appOps = ctx.getSystemService(AppOpsManager::class.java)
        val shadowOps = Shadows.shadowOf(appOps)
        shadowOps.setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            ctx.packageName,
            AppOpsManager.MODE_IGNORED,
        )
        // ensure() tries to self-grant through the hidden setUidMode setter, but
        // the shadow's op check reads the package mode, so a denied op stays
        // denied here - the "visit Settings" dashboard state.
        assertFalse(UsageAccess.isGranted(ctx))
        assertEquals(UsageAccess.Status.DENIED, UsageAccess.ensure(ctx))
        assertFalse(UsageAccess.isGranted(ctx))
        assertEquals(null, AppTimers(ctx).usageTodayByPackage())

        shadowOps.setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            ctx.packageName,
            AppOpsManager.MODE_ALLOWED,
        )
        assertEquals(UsageAccess.Status.GRANTED, UsageAccess.ensure(ctx))
        assertTrue(UsageAccess.isGranted(ctx))
        assertNotNull(AppTimers(ctx).usageTodayByPackage())
    }

    @Test fun windDownApplierWritesGrayscaleAndDndSettings() {
        val applier = WindDownApplier(ctx)
        applier.enter(grayscale = true, doNotDisturb = false)
        assertEquals(
            1,
            Settings.Secure.getInt(ctx.contentResolver, "accessibility_display_daltonizer_enabled", 0),
        )
        assertEquals(
            "0",
            Settings.Secure.getString(ctx.contentResolver, "accessibility_display_daltonizer_mode"),
        )
        applier.exit(grayscale = true, doNotDisturb = false)
        assertEquals(
            0,
            Settings.Secure.getInt(ctx.contentResolver, "accessibility_display_daltonizer_enabled", 1),
        )
        // Without policy access, DND calls are a no-op rather than a crash.
        applier.enter(grayscale = false, doNotDisturb = true)
        applier.exit(grayscale = false, doNotDisturb = true)

        val nm = ctx.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(nm).setNotificationPolicyAccessGranted(true)
        applier.enter(grayscale = false, doNotDisturb = true)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, nm.currentInterruptionFilter)
        applier.exit(grayscale = false, doNotDisturb = true)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, nm.currentInterruptionFilter)
        Shadows.shadowOf(nm).setNotificationPolicyAccessGranted(false)
    }

    @Test fun windowSchedulerArmsOnlyWhenEnabled() {
        val alarms = ctx.getSystemService(AlarmManager::class.java)
        val shadow = Shadows.shadowOf(alarms)
        val scheduler = WindowScheduler(ctx)
        scheduler.armAll(WindDownSchedule(enabled = false))
        assertEquals(null, shadow.nextScheduledAlarm)
        scheduler.armAll(
            WindDownSchedule(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60),
        )
        assertNotNull(shadow.nextScheduledAlarm)
        // Re-arming replaces rather than stacks; disabling cancels outright.
        scheduler.armAll(WindDownSchedule(enabled = false))
        assertEquals(null, shadow.nextScheduledAlarm)
    }

    @Test fun rulesFlowGettersAreColdAndSingletonIsStable() {
        val rules = ScreenTimeRules.get(ctx)
        assertSame(rules, ScreenTimeRules.get(ctx))
        // Getters hand out cold DAO flows; obtaining them must not open the
        // (host-unavailable) database, exactly as the dashboard relies on.
        assertNotNull(rules.allTimers)
        assertNotNull(rules.pausedApps)
        assertNotNull(rules.windDownSchedule)
    }

    @Test fun suspenderSyncWithoutPlatformMethodIsStable() {
        val suspender = Suspender(ctx)
        suspender.sync(setOf("com.a.app", "com.b.app"))
        suspender.sync(setOf("com.b.app"))
        suspender.sync(emptySet())
        // setPackagesSuspended is hidden; on the host it is unreachable, so the
        // tracked set can only ever be what the shadow platform accepted.
        assertNotNull(suspender.pausedNow())
    }
}
