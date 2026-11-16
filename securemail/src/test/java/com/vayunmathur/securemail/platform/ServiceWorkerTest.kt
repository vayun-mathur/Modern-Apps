package com.vayunmathur.securemail.platform

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.securemail.data.MailConfig
import com.vayunmathur.securemail.service.MailSocketService
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Boot receiver gating and the socket service lifecycle under Robolectric shadows. */
@RunWith(RobolectricTestRunner::class)
class ServiceWorkerTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun `boot receiver does nothing without a configured identity`() {
        runBlocking { MailConfig(app).clear() }
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        Thread.sleep(500)
        shadowOf(app).clearStartedServices()
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `boot receiver starts the relay service when an identity exists`() {
        runBlocking {
            MailConfig(app).clear()
            MailConfig(app).setIdentity("me@maemail.org", "pub", "AA")
        }
        shadowOf(app).clearStartedServices()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val deadline = System.currentTimeMillis() + 10_000
        var started: Intent? = null
        while (System.currentTimeMillis() < deadline && started == null) {
            started = shadowOf(app).nextStartedService
            if (started == null) Thread.sleep(50)
        }
        assertNotNull(started)
        assertEquals(MailSocketService::class.java.name, started.component?.className)
        runBlocking { MailConfig(app).clear() }
    }

    @Test fun `socket service companion starts and stops via intents`() {
        shadowOf(app).clearStartedServices()
        MailSocketService.start(app)
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(MailSocketService::class.java.name, started.component?.className)
        MailSocketService.stop(app)
        val stopped = shadowOf(app).nextStoppedService
        assertEquals(MailSocketService::class.java.name, stopped.component?.className)
    }

    @Test fun `socket service creates destroys and does not bind`() {
        val controller = Robolectric.buildService(MailSocketService::class.java)
        val service = controller.create().get()
        assertNull(service.onBind(Intent(app, MailSocketService::class.java)))
        controller.destroy()
    }
}
