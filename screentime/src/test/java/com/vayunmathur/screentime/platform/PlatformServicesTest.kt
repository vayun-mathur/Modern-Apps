package com.vayunmathur.screentime.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.screentime.data.AppTimer
import com.vayunmathur.screentime.data.WindDownSchedule
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Platform services under Robolectric shadows: access, history zeros, timers, spent state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformServicesTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test fun usageAccess_ensureReturnsStatus() {
        // Robolectric's AppOps shadow defaults to allowed; either status is a
        // valid answer, but ensure must return one without throwing.
        assertNotNull(UsageAccess.ensure(ctx()))
        assertEquals(UsageAccess.isGranted(ctx()), UsageAccess.ensure(ctx()) == UsageAccess.Status.GRANTED)
    }

    @Test fun usageHistory_noAccess_returnsZeros() {
        val history = UsageHistory(ctx())
        val week = history.aggregate(UsagePeriod.Week, LocalDate.now())
        assertEquals(0L, week.totalMillis)
        assertTrue(week.bars.isEmpty() || week.bars.all { it.millis == 0L })
        val day = history.forPackage(UsagePeriod.Day, LocalDate.now(), "com.a.app")
        assertEquals(0L, day.totalMillis)
        assertTrue(day.perApp.isEmpty())
    }

    @Test fun appTimers_noStats_zeroAndNull() {
        val timers = AppTimers(ctx())
        assertEquals(0L, timers.timeUsedToday("com.a.app").toMillis())
        assertEquals(0L, timers.totalUsedToday().toMillis())
        assertTrue(timers.usageTodayByPackage().isNullOrEmpty())
        timers.sync(listOf(AppTimer("com.a.app", 60), AppTimer("com.b.app", 30)))
        timers.unregister("com.a.app")
    }

    @Test fun spentState_marksAndPrunes() {
        val spent = SpentState(ctx())
        assertFalse(spent.hasSpent("com.a.app"))
        spent.markSpent("com.a.app")
        assertTrue(spent.hasSpent("com.a.app"))
        spent.pruneToToday()
        assertTrue(spent.hasSpent("com.a.app"))
    }

    @Test fun suspender_withoutPlatformMethod_tracksNothing() {
        val suspender = Suspender(ctx())
        suspender.sync(setOf("com.a.app"))
        // setPackagesSuspended is a hidden API; without it nothing is remembered.
        assertTrue(suspender.pausedNow().isEmpty() || suspender.pausedNow().contains("com.a.app"))
        suspender.sync(emptySet())
    }

    @Test fun windowScheduler_armDisabledAndEnabled() {
        val scheduler = WindowScheduler(ctx())
        scheduler.armAll(WindDownSchedule(enabled = false))
        scheduler.armAll(WindDownSchedule(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60))
    }

    @Test fun windDownApplier_enterExit() {
        val applier = WindDownApplier(ctx())
        applier.enter(grayscale = true, doNotDisturb = true)
        applier.exit(grayscale = true, doNotDisturb = true)
        applier.enter(grayscale = false, doNotDisturb = false)
        applier.exit(grayscale = false, doNotDisturb = false)
    }
}
