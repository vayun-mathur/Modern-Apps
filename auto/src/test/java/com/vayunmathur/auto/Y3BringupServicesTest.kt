package com.vayunmathur.auto

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.network.UsbConnector
import com.vayunmathur.auto.platform.CarChimeraService
import com.vayunmathur.auto.platform.CarCompanionDeviceService
import com.vayunmathur.auto.platform.CarStartupService
import com.vayunmathur.auto.platform.DeepLinkResolverActivity
import com.vayunmathur.auto.platform.WirelessSetupSharedService
import com.vayunmathur.auto.platform.WirelessStartupActivity
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3BringupServicesTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun stubServicesStartAndBind() {
        for ((cls, intent) in listOf(
            WirelessSetupSharedService::class.java to Intent(ctx, WirelessSetupSharedService::class.java),
            CarChimeraService::class.java to Intent(ctx, CarChimeraService::class.java),
            CarStartupService::class.java to Intent(ctx, CarStartupService::class.java),
        )) {
            val c = Robolectric.buildService(cls)
            val s = c.create().get()
            runCatching { s.onStartCommand(intent, 0, 1) }
            runCatching { s.onBind(intent) }
            runCatching { s.onStartCommand(null, 0, 2) }
            c.destroy()
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun companionDeviceServiceCallbacksWithoutDevice() {
        val c = Robolectric.buildService(CarCompanionDeviceService::class.java)
        val s = c.create().get()
        assertNotNull(s)
        runCatching { s.onBind(Intent(ctx, CarCompanionDeviceService::class.java)) }
        c.destroy()
    }

    @Test fun resolverAndStartupActivitiesLaunch() {
        for ((cls, data) in listOf(
            DeepLinkResolverActivity::class.java to "auto://pair",
            WirelessStartupActivity::class.java to null,
        )) {
            val intent = Intent(ctx, cls).apply { if (data != null) this.data = Uri.parse(data) }
            val c = Robolectric.buildActivity(cls, intent)
            runCatching { c.create().start().resume() }
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNotNull(c.get())
            runCatching { c.pause().stop().destroy() }
        }
        // Resolver with no data and with a maps deep link.
        val c2 = Robolectric.buildActivity(DeepLinkResolverActivity::class.java, Intent(ctx, DeepLinkResolverActivity::class.java))
        runCatching { c2.create() }
        runCatching { c2.destroy() }
    }

    @Test fun usbConnectorStartStopWithoutAccessory() {
        // No accessory attached: every entry point is a safe false/no-op.
        UsbConnector.onAccessoryIntent(ctx, Intent(android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_ATTACHED))
        UsbConnector.forceStart(ctx)
        Thread.sleep(300)
        kotlin.test.assertFalse(UsbConnector.onPermissionResult(ctx, Intent("other.action")))
        UsbConnector.retryPermission(ctx)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
