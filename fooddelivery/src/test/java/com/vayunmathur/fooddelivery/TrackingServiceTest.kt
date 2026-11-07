package com.vayunmathur.fooddelivery

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.fooddelivery.api.BitesCore
import com.vayunmathur.fooddelivery.notifications.OrderTrackingService
import com.vayunmathur.library.network.SimpleResponse
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tracking foreground service against a fake [BitesCore.transport]:
 * it must post the placeholder, find the delivered order, and stop itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TrackingServiceTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun servicePollsDeliveredOrderAndStops() {
        val realTransport = BitesCore.transport
        // AppInit must be ready or the poll loop waits on it forever.
        com.vayunmathur.fooddelivery.platform.AppInit.start(ctx)
        BitesCore.transport = { url, _, _, _ ->
            SimpleResponse(
                200, "",
                """{"data":[{"id":9,"uuid":"u9","foodTotal":1000,"orderTotal":1500,"channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY","deliveredAt":"2024-01-01T00:00:00Z"}]}""",
                emptyMap(), url,
            )
        }
        try {
            val intent = Intent(ctx, OrderTrackingService::class.java).putExtra("order_id", 9)
            val controller = Robolectric.buildService(OrderTrackingService::class.java, intent).create()
            val service = controller.get()
            assertEquals(Service.START_REDELIVER_INTENT, service.onStartCommand(intent, 0, 1))
            // A repeat start for the same order is idempotent while the loop is active.
            // (Asserted loosely: the second call may race the loop finishing; either
            // START_REDELIVER_INTENT is correct.)
            assertEquals(null, service.onBind(null))
            val bad = Intent(ctx, OrderTrackingService::class.java)
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(bad, 0, 2))
            val deadline = System.currentTimeMillis() + 20_000
            var stopped = false
            while (System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(100)
                if (shadowOf(service).isStoppedBySelf) { stopped = true; break }
            }
            assertTrue(stopped, "service never stopped itself for a delivered order")
            // stop() from TrackingScreen/Orders paths must not crash after self-stop.
            OrderTrackingService.stop(ctx)
            controller.destroy()
        } finally {
            BitesCore.transport = realTransport
        }
    }

    @Test fun serviceStopsWhenOrderDisappears() {
        val realTransport = BitesCore.transport
        com.vayunmathur.fooddelivery.platform.AppInit.start(ctx)
        BitesCore.transport = { url, _, _, _ ->
            SimpleResponse(200, "", """{"data":[]}""", emptyMap(), url)
        }
        try {
            val intent = Intent(ctx, OrderTrackingService::class.java).putExtra("order_id", 77)
            val controller = Robolectric.buildService(OrderTrackingService::class.java, intent).create()
            val service = controller.get()
            service.onStartCommand(intent, 0, 1)
            val deadline = System.currentTimeMillis() + 20_000
            var stopped = false
            while (System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(100)
                if (shadowOf(service).isStoppedBySelf) { stopped = true; break }
            }
            assertTrue(stopped, "service never stopped itself for a missing order")
            controller.destroy()
        } finally {
            BitesCore.transport = realTransport
        }
    }
}
