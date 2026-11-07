package com.vayunmathur.emergency.ui

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1280dp")
class ActivitiesTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `sos activity renders with default and explicit remaining time`() {
        val intent = SosActivity.intent(ctx, 60_000)
        assertEquals(60_000L, intent.getLongExtra("STATE_MILLIS_LEFT", -1))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val controller = Robolectric.buildActivity(SosActivity::class.java, intent)
        val activity = controller.create().start().resume().visible().get()
        ShadowLooper.idleMainLooper()
        assertNotNull(activity)
        // gesture is off by default in the sandbox, so the screen explains
        // instead of counting down and nothing is dialled or started
        assertNullishStarted()
        controller.pause().stop().destroy()

        val defaultController = Robolectric.buildActivity(
            SosActivity::class.java, SosActivity.intent(ctx),
        )
        val defaultActivity = defaultController.create().start().resume().visible().get()
        ShadowLooper.idleMainLooper()
        assertNotNull(defaultActivity)
        defaultController.pause().stop().destroy()
    }

    private fun assertNullishStarted() {
        // no foreground SOS service was started by rendering the screen
        val shadow = Shadows.shadowOf(ctx as android.app.Application)
        assertTrue(shadow.nextStartedService == null)
    }

    @Test
    fun `view info activity is direct-boot aware for pre-unlock responders`() {
        // The lock-screen card must open after a reboot before the owner has
        // unlocked: the repository reads device-protected storage for exactly
        // this, which only works if the activity may run in direct-boot mode.
        val info = ctx.packageManager.getActivityInfo(
            android.content.ComponentName(ctx, ViewInfoActivity::class.java), 0,
        )
        assertTrue(info.directBootAware)
    }

    @Test
    fun `view info activity renders and refreshes on resume`() {
        val controller = Robolectric.buildActivity(ViewInfoActivity::class.java)
        val activity = controller.create().start().resume().visible().get()
        ShadowLooper.idleMainLooper()
        assertNotNull(activity)
        controller.pause().resume()
        ShadowLooper.idleMainLooper()
        controller.pause().stop().destroy()
    }

    @Test
    fun `edit info activity renders and refreshes on resume`() {
        val controller = Robolectric.buildActivity(EditInfoActivity::class.java)
        val activity = controller.create().start().resume().visible().get()
        ShadowLooper.idleMainLooper()
        assertNotNull(activity)
        controller.pause().resume()
        ShadowLooper.idleMainLooper()
        controller.pause().stop().destroy()
    }

    @Test
    fun `permissions rationale activity is a bare activity`() {
        val controller = Robolectric.buildActivity(PermissionsRationaleActivity::class.java)
        val activity = controller.create().start().resume().get()
        assertNotNull(activity)
        controller.pause().stop().destroy()
    }
}
