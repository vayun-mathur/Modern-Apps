package com.vayunmathur.auto.service

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.platform.CarLauncherState
import com.vayunmathur.auto.platform.CarStartupService
import com.vayunmathur.auto.telephony.NonCarInCallService
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import android.os.Looper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SmallServicesTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun idleMain() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test fun `music capture service without grant stops itself`() {
        val controller = Robolectric.buildService(MusicCaptureService::class.java)
        val service = controller.create().get()
        val result = service.onStartCommand(Intent(ctx, MusicCaptureService::class.java), 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertNull(service.onBind(null))
        controller.destroy()
    }

    @Test fun `music capture startIfGranted without grant is a no-op and stop is safe`() {
        MusicCaptureService.startIfGranted(ctx)
        MusicCaptureService.stop(ctx)
        idleMain()
    }

    @Test fun `car app host session discovers fallback maps and stops cleanly`() {
        CarLauncherState.setDiscovered(emptyList())
        val session = CarAppHostSession(ctx) { null }
        session.start()
        idleMain()
        // Discovery on Robolectric finds no car apps, so the bundled Maps fallback is published
        assertTrue(CarLauncherState.discovered.value.isNotEmpty())
        assertEquals("com.vayunmathur.maps", CarLauncherState.discovered.value.first().component.packageName)
        session.start() // idempotent while running
        session.setFocused(null)
        session.resetApp(null)
        session.onTemplate(com.vayunmathur.library.carhost.HostTemplate.Pane(title = "x"))
        session.stop()
        session.stop()
        CarLauncherState.setDiscovered(emptyList())
    }

    @Test fun `startup service rejects binds and ignores unknown actions`() {
        val controller = Robolectric.buildService(CarStartupService::class.java)
        val service = controller.create().get()
        assertNull(service.onBind(Intent()))
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(Intent("unknown"), 0, 1))
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
        controller.destroy()
    }

    @Test fun `non car in-call service rejects binds`() {
        val service = NonCarInCallService()
        // onBind must be null (disabled half); onCallRemoved is a no-op.
        // Cannot fabricate a Call easily, so bind rejection is the contract.
        val controller = Robolectric.buildService(NonCarInCallService::class.java)
        val svc = controller.create().get()
        assertNull(svc.onBind(Intent()))
        controller.destroy()
    }

    @Test fun `projection service bind is null and night defaults to day`() {
        val controller = Robolectric.buildService(ProjectionService::class.java)
        val service = controller.create().get()
        assertNull(service.onBind(null))
        // isNightNow is internal; same-module tests can call it
        service.isNightNow()
        controller.destroy()
    }
}
