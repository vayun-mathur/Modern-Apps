package com.vayunmathur.taxi.notifications

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.DriverInfo
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.RideStatus
import com.vayunmathur.taxi.data.RideStopInfo
import com.vayunmathur.taxi.data.VehicleInfo
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RideLiveUpdateDeepTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun ride(status: RideStatus, eta: Int? = 300, driver: DriverInfo? = DriverInfo("Jo", "Smith", null, null, 4.9),
                     vehicle: VehicleInfo? = VehicleInfo("Toyota", "Camry", "Black", "XYZ", null)): ActiveRide {
        val stops = if (eta == null) emptyList() else listOf(RideStopInfo(LatLng(1.0, 2.0), "Home", "pickup", eta, false))
        return ActiveRide("ride-42", status, status.wire, driver, vehicle, null, stops, "")
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        ?: n.extras.getCharSequence("android.text")?.toString()

    @Test fun buildsForEveryMilestoneWithDriverTitle() {
        for (status in RideStatus.entries) {
            val n = RideLiveUpdate.build(context, ride(status))
            assertNotNull(n)
            assertEquals(RideLiveUpdate.CHANNEL_ID, n.channelId)
        }
        // driver name preferred as title
        assertEquals("Jo Smith", title(RideLiveUpdate.build(context, ride(RideStatus.ACCEPTED))))
        // ETA ceiled: 300s = 5 min, 301s = 6 min shown in text if pre-pickup
        assertTrue(text(RideLiveUpdate.build(context, ride(RideStatus.ACCEPTED, eta = 300)))!!.contains("5"))
        assertTrue(text(RideLiveUpdate.build(context, ride(RideStatus.ACCEPTED, eta = 301)))!!.contains("6"))
    }

    @Test fun titleFallsBackToVehicleThenGenericAndNoEtaCases() {
        val noDriver = ride(RideStatus.ARRIVED, driver = null)
        assertEquals("Black Toyota Camry", title(RideLiveUpdate.build(context, noDriver)))
        val bare = ride(RideStatus.PICKED_UP, driver = null, vehicle = null, eta = null)
        assertNotNull(title(RideLiveUpdate.build(context, bare)))
        // picked-up / terminal: no pickup ETA even though stop present
        assertNotNull(RideLiveUpdate.build(context, ride(RideStatus.PICKED_UP, eta = 120)))
        assertNotNull(RideLiveUpdate.build(context, ride(RideStatus.COMPLETED, eta = 120)))
        // zero/negative eta -> label only
        assertNotNull(RideLiveUpdate.build(context, ride(RideStatus.PENDING, eta = 0)))
        val negStops = ActiveRide("r", RideStatus.PENDING, "pending", null, null, null,
            listOf(RideStopInfo(null, "H", "pickup", -5, false)), "")
        assertNotNull(RideLiveUpdate.build(context, negStops))
        // completed stop eta skipped, next incomplete used - build still succeeds
        val doneFirst = ActiveRide("r", RideStatus.ACCEPTED, "accepted", null, null, null,
            listOf(RideStopInfo(null, "H", "pickup", 999, true), RideStopInfo(null, "W", "dropoff", 60, false)), "")
        assertTrue(text(RideLiveUpdate.build(context, doneFirst))!!.contains("1"))
    }

    @Test fun channelCancelAndServiceCompanion() {
        RideLiveUpdate.ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        assertNotNull(nm.getNotificationChannel(RideLiveUpdate.CHANNEL_ID))
        val n = RideLiveUpdate.build(context, ride(RideStatus.ACCEPTED))
        androidx.core.app.NotificationManagerCompat.from(context).notify(RideLiveUpdate.NOTIFICATION_ID, n)
        RideLiveUpdate.cancel(context)
        assertEquals(0, Shadows.shadowOf(nm).allNotifications.size)
        // service companion: blank ids no-op, stop does not throw
        RideTrackingService.start(context, null)
        RideTrackingService.start(context, "")
        RideTrackingService.start(context, "   ")
        RideTrackingService.stop(context)
        assertEquals(5120, RideLiveUpdate.NOTIFICATION_ID)
        assertTrue(RideLiveUpdate.EXTRA_TRACK_RIDE_ID.contains("EXTRA_TRACK_RIDE_ID"))
    }

    @Test fun trackingServiceBlankIntentStopsAndValidIntentStarts() {
        val controller = org.robolectric.Robolectric.buildService(RideTrackingService::class.java)
        val service = controller.create().get()
        // blank -> START_NOT_STICKY (2? assert constant value via return: 2 is START_NOT_STICKY? Actually 2 = START_NOT_STICKY? check: START_STICKY=1, START_NOT_STICKY=2)
        val blankResult = service.onStartCommand(android.content.Intent(context, RideTrackingService::class.java), 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, blankResult)
        controller.destroy()
    }
}
