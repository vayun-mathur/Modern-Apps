package com.vayunmathur.screentime.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.screentime.FakeAndroidKeyStore
import com.vayunmathur.screentime.platform.EXTRA_PACKAGE_NAME
import com.vayunmathur.screentime.platform.SpentState
import com.vayunmathur.screentime.receiver.BootReceiver
import com.vayunmathur.screentime.receiver.ScheduleReceiver
import com.vayunmathur.screentime.receiver.TimerReachedReceiver
import com.vayunmathur.screentime.widget.WidgetRefresh
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Friction screen actions, receiver entry guards, and the widget refresh push. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrictionAndReceiversTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = ctx.getString(id)

    private fun drain() {
        Thread.sleep(500)
        repeat(8) {
            try {
                kotlinx.coroutines.test.runTest { }
            } catch (e: IllegalStateException) {
                if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
            }
            Thread.sleep(300)
        }
    }

    @Test fun frictionNudgeBreakAndContinue() {
        var breaks = 0
        var continues = 0
        rule.setContent {
            FrictionScreen(appLabel = "Alpha App", nudge = true, onContinue = { continues++ }, onTakeABreak = { breaks++ })
        }
        rule.onNodeWithText("Alpha App").assertExists()
        rule.onNodeWithText(str(com.vayunmathur.screentime.R.string.friction_nudge_title)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.screentime.R.string.friction_break)).performClick()
        assertEquals(1, breaks)
        // Taking a break consumes the break button; continuing remains available.
        rule.onNodeWithText(str(com.vayunmathur.screentime.R.string.friction_continue)).performClick()
        assertEquals(1, continues)
        rule.onNodeWithText(str(com.vayunmathur.screentime.R.string.friction_break)).assertDoesNotExist()
    }

    @Test fun frictionSpentWithoutLabel() {
        var continues = 0
        rule.setContent {
            FrictionScreen(appLabel = null, nudge = false, onContinue = { continues++ }, onTakeABreak = {})
        }
        rule.onNodeWithText(str(com.vayunmathur.screentime.R.string.friction_spent_title)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.screentime.R.string.friction_open_anyway)).performClick()
        assertEquals(1, continues)
    }

    @Test fun receiversGuardMissingPackageAndWrongActions() {
        // No package extra: returns before goAsync, so this is safe on the host.
        TimerReachedReceiver().onReceive(ctx, Intent())
        // Wrong actions: BootReceiver ignores everything but boot completions.
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_TIME_CHANGED))
        BootReceiver().onReceive(ctx, Intent())
        assertEquals(
            "com.a.app",
            TimerReachedReceiver.intent(ctx, "com.a.app").getStringExtra(EXTRA_PACKAGE_NAME),
        )
        assertTrue(ScheduleReceiver.intent(ctx).component != null)
    }

    // Boot/Schedule/TimerReached onReceive itself is not driven here: goAsync()
    // returns null under Robolectric and the receivers' finally { finish() }
    // then NPEs in the IO coroutine (see ActivitiesAndReceiversTest). Their
    // intent builders and the no-goAsync guard paths above are the host seam.

    @Test fun widgetRefreshPushesSeededTotals() {
        // Seed today's usage so the refresh computes a real total and top app
        // (label lookup falls back to the package name for uninstalled packages).
        val start = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val shadow = org.robolectric.Shadows.shadowOf(
            ctx.getSystemService(android.app.usage.UsageStatsManager::class.java),
        )
        for ((pkg, ms) in listOf("com.a.app" to 90 * 60_000L, "com.b.app" to 30 * 60_000L)) {
            val stats = org.robolectric.shadows.ShadowUsageStatsManager.UsageStatsBuilder.newBuilder()
                .setPackageName(pkg)
                .setFirstTimeStamp(start)
                .setLastTimeStamp(start + 3_600_000)
                .setTotalTimeInForeground(ms)
                .build()
            shadow.addUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, stats)
            shadow.addUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST, stats)
        }
        WidgetRefresh.refresh(ctx)
        Thread.sleep(1_500)
        ShadowLooper.idleMainLooper()
        drain()
    }
}
