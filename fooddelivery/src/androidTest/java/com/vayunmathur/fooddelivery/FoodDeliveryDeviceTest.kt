package com.vayunmathur.fooddelivery

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.fooddelivery.api.BitesCore
import com.vayunmathur.fooddelivery.intents.OrderLookupIntent
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.network.SimpleResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device pass: the exported order-lookup intent answered in-process (fake
 * transport, never the network), and MainActivity deep links opening the
 * restaurant and tracking routes.
 */
@RunWith(AndroidJUnit4::class)
class FoodDeliveryDeviceTest {

    private val realTransport = BitesCore.transport

    @org.junit.After fun restoreTransport() {
        BitesCore.transport = realTransport
    }

    private fun installFakeTransport() {
        BitesCore.transport = { url, _, _, _ ->
            when {
                url.contains("/merchants/all/stores") -> SimpleResponse(200, "", """{"data":{"merchants":[{"id":1,"name":"Burger Barn","latitude":37.5,"longitude":-122.3,"averageRating":4.5}]}}""", emptyMap(), url)
                url.contains("/merchants/1") -> SimpleResponse(200, "", """{"data":{"id":1,"name":"Burger Barn","categories":[{"id":1,"name":"Mains","itemIds":[10]}],"items":[{"id":10,"name":"Burger","description":"Tasty","price":999}]}}""", emptyMap(), url)
                url.contains("/orders/past/all") -> SimpleResponse(200, "", """{"data":[{"id":9,"uuid":"u9","foodTotal":1000,"orderTotal":1500,"channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY","deliveredAt":"2024-01-01T00:00:00Z","merchant":{"id":1,"name":"Burger Barn"}}]}""", emptyMap(), url)
                else -> SimpleResponse(404, "", "{}", emptyMap(), url)
            }
        }
    }

    @Test fun trackingExtraOpensTrackingScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        installFakeTransport()
        AppInit.start(context)
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(com.vayunmathur.fooddelivery.notifications.OrderLiveUpdate.EXTRA_TRACK_ORDER_ID, 9)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        assertNotNull(
            "tracking screen never showed the order stage",
            device.wait(Until.findObject(By.text("Delivered")), 20_000),
        )
        assertEquals(context.packageName, device.currentPackageName)
    }

    @Test fun orderLookupIntentMatchesNearbyRestaurant() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        installFakeTransport()
        AppInit.start(context)

        fun lookup(name: String, lat: Double, lng: Double): Pair<Int, Bundle?> {
            val latch = CountDownLatch(1)
            var code = Int.MIN_VALUE; var data: Bundle? = null
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    code = resultCode; data = resultData; latch.countDown()
                }
            }
            val intent = Intent(context, OrderLookupIntent::class.java)
                .putExtra("DATA", """{"name":"$name","lat":$lat,"lng":$lng}""")
                .putExtra("RECEIVER", receiver)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            assertTrue("lookup never answered", latch.await(20, TimeUnit.SECONDS))
            return code to data
        }

        val (code, data) = lookup("Burger Barn", 37.5, -122.3)
        assertEquals(Activity.RESULT_OK, code)
        val response = data!!.getString("RESPONSE_DATA")!!
        assertTrue("expected an orderable match: $response", response.contains("\"available\":true"))
        assertTrue(response.contains("fooddelivery://restaurant/1"))

        val (code2, data2) = lookup("Nonexistent Place", 0.0, 0.0)
        assertEquals(Activity.RESULT_OK, code2)
        assertTrue(data2!!.getString("RESPONSE_DATA")!!.contains("\"available\":false"))
    }

}
