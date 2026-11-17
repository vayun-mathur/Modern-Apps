package com.vayunmathur.screentime.ui

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.screentime.platform.ScreenTimeViewModel
import com.vayunmathur.screentime.platform.UsagePeriod
import com.vayunmathur.screentime.platform.BootReceiver
import com.vayunmathur.screentime.platform.ScheduleReceiver
import com.vayunmathur.screentime.platform.TimerReachedReceiver
import com.vayunmathur.screentime.widget.WidgetRefresh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Activities launch, receivers tolerate the DB-less sandbox, VM period/step moves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivitiesAndReceiversTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun mainActivity_creates() {
        val controller = Robolectric.buildActivity(com.vayunmathur.screentime.MainActivity::class.java).create()
        assertNotNull(controller.get())
        controller.destroy()
    }

    @Test fun mainActivity_appDetailsWithPackageExtra() {
        val intent = Intent(ctx(), com.vayunmathur.screentime.MainActivity::class.java)
            .putExtra(com.vayunmathur.screentime.MainActivity.EXTRA_DETAILS_PACKAGE, "com.a.app")
        val controller = Robolectric.buildActivity(com.vayunmathur.screentime.MainActivity::class.java, intent).create()
        assertNotNull(controller.get())
        controller.destroy()
    }

    @Test fun mainActivity_appDetailsSuspendedDataUri() {
        val intent = Intent("android.intent.action.SHOW_SUSPENDED_APP_DETAILS")
            .setData(Uri.parse("package:com.b.app"))
        val controller = Robolectric.buildActivity(com.vayunmathur.screentime.MainActivity::class.java, intent).create()
        assertNotNull(controller.get())
        controller.destroy()
    }

    @Test fun mainActivity_windDownCreates() {
        val controller = Robolectric.buildActivity(com.vayunmathur.screentime.MainActivity::class.java, Intent("android.settings.BEDTIME_SETTINGS")).create()
        assertNotNull(controller.get())
        controller.destroy()
    }

    @Test fun frictionActivity_nudgeAndSpent() {
        val spent = Robolectric.buildActivity(
            FrictionActivity::class.java,
            FrictionActivity.intent(ctx(), "com.a.app", nudge = false),
        ).create()
        assertNotNull(spent.get())
        spent.destroy()
        val nudge = Robolectric.buildActivity(
            FrictionActivity::class.java,
            FrictionActivity.intent(ctx(), null, nudge = true),
        ).create()
        assertNotNull(nudge.get())
        nudge.destroy()
    }

    @Test fun receivers_intentsAndEnforceNow() {
        // onReceive itself is not driven here: Robolectric's goAsync() returns
        // a PendingResult whose finish() NPEs off-device, and that uncaught IO
        // exception leaks into later tests. The intent builders and the
        // enforceNow path (no goAsync) are covered instead.
        val ctx = ctx()
        val timerIntent = TimerReachedReceiver.intent(ctx, "com.a.app")
        kotlin.test.assertEquals("com.a.app", timerIntent.getStringExtra(com.vayunmathur.screentime.platform.EXTRA_PACKAGE_NAME))
        kotlin.test.assertNotNull(ScheduleReceiver.intent(ctx))
        TimerReachedReceiver.enforceNow(ctx, "com.a.app")
        ShadowLooper.idleMainLooper()
    }

    @Test fun widgetRefresh_doesNotThrow() {
        WidgetRefresh.refresh(ctx())
        ShadowLooper.idleMainLooper()
    }

    @Test fun viewModel_periodStepAndDetail() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = ScreenTimeViewModel(app)
        assertEquals(UsagePeriod.Week, vm.state.value.period)
        vm.setPeriod(UsagePeriod.Day)
        vm.setPeriod(UsagePeriod.Day)
        vm.stepAnchor(forward = false)
        vm.stepAnchor(forward = true)
        vm.openDetail("com.a.app")
        vm.openDetail(null)
        vm.refresh()
        ShadowLooper.idleMainLooper()
        assertTrue(vm.detailState.value.packageName == null)
    }
}
