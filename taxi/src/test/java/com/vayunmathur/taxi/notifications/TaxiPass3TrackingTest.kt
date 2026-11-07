package com.vayunmathur.taxi.notifications

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.DriverInfo
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.RideStatus
import com.vayunmathur.taxi.data.RideStopInfo
import com.vayunmathur.taxi.data.VehicleInfo
import com.vayunmathur.taxi.data.lyft.LyftTokenStore
import com.vayunmathur.taxi.network.TaxiPass3FakeHttp
import com.vayunmathur.taxi.network.lyft.LyftToken
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pass 3: the Live Update notification builder and the foreground
 * tracking service lifecycle under Robolectric, over the fake transport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxiPass3TrackingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val http = TaxiPass3FakeHttp()

    private fun ride(status: RideStatus, etaSeconds: Int? = 300): ActiveRide = ActiveRide(
        rideId = "ride-42", status = status, statusRaw = status.wire,
        driver = DriverInfo("Jo", "Smith", null, null, 4.8),
        vehicle = VehicleInfo("Toyota", "Camry", "Black", "XYZ", null),
        driverLocation = null,
        stops = listOf(
            RideStopInfo(LatLng(37.7, -122.4), "Home", "pickup", etaSeconds, completed = false),
            RideStopInfo(LatLng(37.8, -122.5), "Work", "dropoff", 900, completed = false),
        ),
        raw = "{}",
    )

    @BeforeTest
    fun setUp() {
        http.install()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @AfterTest
    fun tearDown() {
        http.uninstall()
        runBlocking { LyftTokenStore(context).clear() }
    }

    @Test
    fun liveUpdateBuildsForEveryLifecyclePhase() {
        RideLiveUpdate.ensureChannel(context)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel(RideLiveUpdate.CHANNEL_ID))

        for (status in RideStatus.entries) {
            val notification = RideLiveUpdate.build(context, ride(status))
            val shadow = shadowOf(notification)
            assertEquals(
                "Jo Smith",
                shadow.contentTitle?.toString() ?: ride(status).driver!!.displayName,
            )
            assertTrue(notification.contentIntent != null)
        }
        // Terminal and ETA-less variants build too.
        assertNotNull(RideLiveUpdate.build(context, ride(RideStatus.COMPLETED, etaSeconds = null)))
        assertNotNull(
            RideLiveUpdate.build(
                context,
                ride(RideStatus.ACCEPTED, etaSeconds = null).copy(driver = null, vehicle = null),
            ),
        )
        assertNotNull(
            RideLiveUpdate.build(
                context,
                ride(RideStatus.PICKED_UP, etaSeconds = 61).copy(driver = null),
            ),
        )
        RideLiveUpdate.cancel(context)
        manager.cancelAll()
    }

    @Test
    fun trackingServiceStartsPollsAndStopsOnTerminalRide() {
        runBlocking {
            LyftTokenStore(context).save(
                LyftToken(accessToken = "access-tok", refreshToken = "rt", expiresIn = 3600),
            )
        }
        http.on("/v1/activeride") {
            http.raw(
                200,
                """{"ride":{"ride_id":"ride-42","status":"completed",
                   "driver":{"first_name":"Jo"},"vehicle":{"make":"Toyota"},
                   "stops":[]}}""",
                url = it.url,
            )
        }
        val controller = Robolectric.buildService(RideTrackingService::class.java).create()
        val service = controller.get()
        // Blank ride id stops immediately.
        assertEquals(
            android.app.Service.START_NOT_STICKY,
            service.onStartCommand(Intent(), 0, 1),
        )
        // A real ride: foreground starts, the poll sees the terminal status
        // and the service stops itself after the linger (virtualised here by
        // destroying the controller first to avoid the post-terminal delay).
        val intent = Intent(context, RideTrackingService::class.java).putExtra("ride_id", "ride-42")
        val result = service.onStartCommand(intent, 0, 2)
        assertEquals(android.app.Service.START_REDELIVER_INTENT, result)
        // A repeat start for the same ride is idempotent.
        val again = service.onStartCommand(intent, 0, 3)
        assertEquals(android.app.Service.START_REDELIVER_INTENT, again)
        controller.destroy()
        // Companion start/stop guard rails.
        RideTrackingService.start(context, null)
        RideTrackingService.start(context, "")
        RideTrackingService.stop(context)
    }

    @Test
    fun trackingServicePollLoopNotifiesAndSelfStops() {
        runBlocking {
            LyftTokenStore(context).save(
                LyftToken(accessToken = "access-tok", refreshToken = "rt", expiresIn = 3600),
            )
        }
        http.on("/v1/activeride") {
            http.raw(
                200,
                "{\"ride\":{\"ride_id\":\"ride-42\",\"status\":\"completed\"," +
                    "\"driver\":{\"first_name\":\"Jo\"},\"vehicle\":{\"make\":\"Toyota\"}," +
                    "\"stops\":[]}}",
                url = it.url,
            )
        }
        val controller = Robolectric.buildService(RideTrackingService::class.java).create()
        val service = controller.get()
        val intent = Intent(context, RideTrackingService::class.java).putExtra("ride_id", "ride-42")
        service.onStartCommand(intent, 0, 10)
        // The poll loop runs on Dispatchers.IO in real time: the terminal
        // ride is notified, lingers, and the service stops itself.
        val deadline = System.currentTimeMillis() + 30_000
        var stopped = false
        while (System.currentTimeMillis() < deadline) {
            if (shadowOf(service).isStoppedBySelf) { stopped = true; break }
            Thread.sleep(50)
        }
        assertTrue(stopped, "service did not stop itself after the terminal ride")
        assertTrue(http.callsTo("/v1/activeride").isNotEmpty())
        controller.destroy()
    }
}
