package com.vayunmathur.maps.util
import com.vayunmathur.maps.domain.RouteService
import com.vayunmathur.maps.platform.NavigationSessionManager

import android.Manifest
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor

/**
 * C5 navigation-session sensor fusion: accelerometer and magnetic-field
 * events feed the compass bearing used while stationary, and stopping
 * unregisters both listeners.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsNavSensorC5Test {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    @AfterTest
    fun tearDown() {
        NavigationSessionManager.stop()
    }

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun route() = RouteService.Route(
        10.minutes, 2_000.0,
        listOf(pt(-122.0, 37.0), pt(-122.0, 37.002)),
        listOf(
            RouteService.Step(
                500.0, 60.seconds, listOf(pt(-122.0, 37.0)),
                RouteService.API.NavInstruction(RouteService.API.Maneuver.DEPART, "Head north"),
                RouteService.TravelMode.DRIVE,
            ),
        ),
    )

    private fun field(name: String): java.lang.reflect.Field =
        NavigationSessionManager::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Test
    fun `sensor events update the compass bearing`() {
        NavigationSessionManager.init(ctx)
        NavigationSessionManager.stop()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLm = Shadows.shadowOf(lm)
        shadowLm.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 37.0; longitude = -122.0; accuracy = 5f; time = System.currentTimeMillis()
        }
        shadowLm.setLastKnownLocation(LocationManager.GPS_PROVIDER, fix)

        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val shadowSm = Shadows.shadowOf(sm)
        shadowSm.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        shadowSm.addSensor(ShadowSensor.newInstance(Sensor.TYPE_MAGNETIC_FIELD))

        NavigationSessionManager.start(route(), RouteService.TravelMode.DRIVE, pt(-122.0, 37.002), "Dest")
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val listener = field("registeredSensorListener").get(NavigationSessionManager)
            as SensorEventListener
        assertNotNull(listener)
        // Gravity down + field pointing north: device facing north.
        listener.onSensorChanged(
            SensorEventBuilder.newBuilder()
                .setSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
                .setValues(floatArrayOf(0f, 0f, 9.81f))
                .build(),
        )
        listener.onSensorChanged(
            SensorEventBuilder.newBuilder()
                .setSensor(ShadowSensor.newInstance(Sensor.TYPE_MAGNETIC_FIELD))
                .setValues(floatArrayOf(0f, 20f, -40f))
                .build(),
        )
        val bearing = field("compassBearing").getFloat(NavigationSessionManager)
        assertTrue(bearing in 0f..360f, "bearing normalised, was $bearing")
        // Stopping unregisters the sensor listener.
        NavigationSessionManager.stop()
        assertEquals(null, field("registeredSensorListener").get(NavigationSessionManager))
        assertEquals(NavigationSessionManager.NavState.Idle, NavigationSessionManager.state.value)
    }

    @Test
    fun `start without location permission fails the session`() {
        NavigationSessionManager.init(ctx)
        NavigationSessionManager.stop()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        NavigationSessionManager.start(route(), RouteService.TravelMode.DRIVE, pt(-122.0, 37.002), "Dest")
        val state = NavigationSessionManager.state.value
        assertTrue(state is NavigationSessionManager.NavState.Failed, "was $state")
        NavigationSessionManager.stop()
    }
}
