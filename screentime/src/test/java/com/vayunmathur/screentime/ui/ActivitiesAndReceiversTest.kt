package com.vayunmathur.screentime.ui

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.screentime.platform.ScreenTimeViewModel
import com.vayunmathur.screentime.platform.UsagePeriod
import com.vayunmathur.screentime.receiver.BootReceiver
import com.vayunmathur.screentime.receiver.ScheduleReceiver
import com.vayunmathur.screentime.receiver.TimerReachedReceiver
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

    @Test fun dashboardActivity_creates() {
        val controller = Robolectric.buildActivity(DashboardActivity::class.java).create()
        assertNotNull(controller.get())
        controller.destroy()
    }

    @Test fun appDetailsActivity_withPackageExtra() {
        val intent = Intent(ctx(), AppDetailsActivity::class.java)
            .putExtra(AppDetailsActivity.EXTRA_DETAILS_PACKAGE, "com.a.app")
        val controller = Robolectric.buildActivity(AppDetailsActivity::class.java, intent).create()
        assertNotNull(controller.get())
        controller.destroy()
    }

    @Test fun appDetailsActivity_suspendedDataUri() {
        val intent = Intent("android.intent.action.SHOW_SUSPENDED_APP_DETAILS")
            .setData(Uri.parse("package:com.b.app"))
        val controller = Robolectric.buildActivity(AppDetailsActivity::class.java, intent).create()
        assertNotNull(controller.get())
        controller.destroy()
    }

    @Test fun windDownActivity_creates() {
        val controller = Robolectric.buildActivity(WindDownActivity::class.java).create()
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

    @Test fun receivers_doNotThrow() {
        val ctx = ctx()
        TimerReachedReceiver().onReceive(ctx, Intent())
        TimerReachedReceiver().onReceive(ctx, TimerReachedReceiver.intent(ctx, "com.a.app"))
        TimerReachedReceiver.enforceNow(ctx, "com.a.app")
        ScheduleReceiver().onReceive(ctx, ScheduleReceiver.intent(ctx))
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_VIEW))
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
