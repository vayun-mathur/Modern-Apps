package com.vayunmathur.euicc.service

import android.content.Intent
import android.service.euicc.EuiccService
import android.telephony.euicc.DownloadableSubscription
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ServicesTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    // ---- EuiccManagerService ----

    @Test fun `manager service constant and fallback results without eUICC`() {
        val controller = Robolectric.buildService(EuiccManagerService::class.java)
        val service = controller.create().get()
        assertEquals(EuiccService.OTA_STATUS_NOT_UPDATED, service.onGetOtaStatus(0))
        // onStartOtaIfNecessary's callback type is an abstract class in the
        // real framework (an interface in the compile stubs), so it cannot be
        // instantiated from a test; that one call is left unexercised.
        assertNull(service.onGetEid(0))
        assertEquals("", service.onGetEuiccInfo(0).osVersion)
        // Stub result classes expose no getters; the call itself must return
        // the failure result object rather than throw without an eUICC.
        assertNotNull(service.onGetEuiccProfileInfoList(0))
        assertEquals(EuiccService.RESULT_FIRST_USER, service.onDeleteSubscription(0, "8901"))
        assertEquals(EuiccService.RESULT_FIRST_USER, service.onSwitchToSubscription(0, "8901", false))
        assertEquals(EuiccService.RESULT_FIRST_USER, service.onSwitchToSubscription(0, null, false))
        assertEquals(EuiccService.RESULT_FIRST_USER, service.onSwitchToSubscriptionWithPort(0, 0, "8901", false))
        assertEquals(EuiccService.RESULT_FIRST_USER, service.onSwitchToSubscriptionWithPort(0, 0, null, false))
        assertEquals(EuiccService.RESULT_FIRST_USER, service.onUpdateSubscriptionNickname(0, "8901", "Work"))
        assertEquals(EuiccService.RESULT_FIRST_USER, service.onUpdateSubscriptionNickname(0, "8901", null))
        assertEquals(EuiccService.RESULT_FIRST_USER, service.onEraseSubscriptions(0))
        assertEquals(EuiccService.RESULT_FIRST_USER, service.onRetainSubscriptionsForFactoryReset(0))
        controller.destroy()
    }

    @Test fun `manager service metadata and default list echo inputs`() {
        val controller = Robolectric.buildService(EuiccManagerService::class.java)
        val service = controller.create().get()
        val sub = DownloadableSubscription.forActivationCode("LPA:1\$smdp.example.com\$MATCH")
        assertNotNull(service.onGetDownloadableSubscriptionMetadata(0, sub, false))
        assertNotNull(service.onGetDefaultDownloadableSubscriptionList(0, false))
        controller.destroy()
    }

    @Test fun `manager download without usable code or channel returns first user`() {
        val controller = Robolectric.buildService(EuiccManagerService::class.java)
        val service = controller.create().get()
        val empty = DownloadableSubscription.forActivationCode("")
        // Empty code still has encodedActivationCode="" (not null) in the
        // framework object, so this goes to the channel and fails there.
        assertEquals(
            EuiccService.RESULT_FIRST_USER,
            service.onDownloadSubscription(0, empty, switchAfterDownload = false, forceDeactivateSim = false),
        )
        val coded = DownloadableSubscription.forActivationCode("LPA:1\$smdp.example.com\$MATCH")
        assertEquals(
            EuiccService.RESULT_FIRST_USER,
            service.onDownloadSubscription(0, coded, switchAfterDownload = true, forceDeactivateSim = false),
        )
        controller.destroy()
    }

    // ---- DownloadService ----

    @Test fun `download service ignores foreign actions and binds null`() {
        val controller = Robolectric.buildService(DownloadService::class.java)
        val service = controller.create().get()
        assertNull(service.onBind(Intent()))
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 1))
        assertEquals(
            android.app.Service.START_NOT_STICKY,
            service.onStartCommand(Intent("com.example.WRONG"), 0, 2),
        )
        controller.destroy()
    }

    @Test fun `download service guard starts foreground once and destroys cleanly`() {
        val controller = Robolectric.buildService(DownloadService::class.java)
        val service = controller.create().get()
        val guard = Intent(ctx, DownloadService::class.java).setAction("com.vayunmathur.euicc.action.GUARD_INSTALL")
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(guard, 0, 1))
        // Second start is a no-op but still reports NOT_STICKY.
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(guard, 0, 2))
        assertNotNull(Shadows.shadowOf(service).lastForegroundNotification)
        controller.destroy()
    }

    @Test fun `download service companion start and stop issue service intents`() {
        val app = ctx
        DownloadService.start(app)
        val started = Shadows.shadowOf(app as android.app.Application).nextStartedService
        assertNotNull(started)
        assertEquals(DownloadService::class.java.name, started.component?.className)
        assertEquals("com.vayunmathur.euicc.action.GUARD_INSTALL", started.action)
        DownloadService.stop(app)
    }
}
