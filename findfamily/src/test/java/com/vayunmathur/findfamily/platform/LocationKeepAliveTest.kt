package com.vayunmathur.findfamily.platform

import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.SystemClock
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.domain.ForcedFixPolicy
import com.vayunmathur.findfamily.domain.ForcedFixReason
import com.vayunmathur.findfamily.domain.MotionTransition
import com.vayunmathur.findfamily.domain.MovementDetector
import com.vayunmathur.findfamily.util.LocationTrackingService
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * GitHub #895, service level: the sender keeps producing fixes while "still", a coarse fix
 * wakes movement detection, vehicle motion with low acceleration stays moving, and a stale
 * held fix forces a one-off fix. Built on the repositoryOverride seam from 56de52e9b plus
 * the currentLocationSourceOverride seam added for #895.
 */
@RunWith(AndroidJUnit4::class)
class LocationKeepAliveTest {

    private lateinit var db: FFDatabase
    private lateinit var service: LocationTrackingService
    private val ctx: Context get() = ApplicationProvider.getApplicationContext<Application>()

    /** Providers asked for a forced fix, in order, and what each answers. */
    private val forcedCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var networkAnswer: Location? = null
    private var gpsAnswer: Location? = null

    private val homeLat = 51.5
    private val homeLon = -0.12
    private fun northOf(meters: Double) = homeLat + meters / 111_195.0

    private fun location(
        provider: String = LocationManager.GPS_PROVIDER,
        lat: Double = homeLat,
        acc: Float = 10f,
    ) = Location(provider).apply {
        latitude = lat
        longitude = homeLon
        accuracy = acc
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        time = System.currentTimeMillis()
    }

    private fun advance(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))

    @BeforeTest
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, FFDatabase::class.java).allowMainThreadQueries().build()
        service = Robolectric.buildService(LocationTrackingService::class.java).create().get()
        service.repositoryOverride = FindFamilyRepository(ctx, db)
        // The service's own instance: the one registerSensors() will use.
        val lm = service.getSystemService(LocationManager::class.java)
        shadowOf(lm).setLocationEnabled(true)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setProviderEnabled(LocationManager.PASSIVE_PROVIDER, true)
        service.currentLocationSourceOverride = CurrentLocationSource { provider, _ ->
            forcedCalls += provider
            if (provider == LocationManager.NETWORK_PROVIDER) networkAnswer else gpsAnswer
        }
        // A fresh held fix so registerSensors() does not start a forced fix of its own.
        service.lastKnownLocation = location()
        service.registerSensors()
        service.bm = ctx.getSystemService(BatteryManager::class.java)
    }

    @AfterTest
    fun teardown() {
        service.serviceScope.cancel()
        db.close()
    }

    @Test
    fun lowPowerAndPassiveRequestsAreRegisteredAtStartAndSurviveStillness() {
        assertEquals(LOW_POWER_INTERVAL_MS, service.lowPowerIntervalMs)
        assertTrue(service.passiveRequested)
        service.startMovingNow()
        assertTrue(service.isMoving)
        advance(MovementDetector.FIX_QUIET_MS + 60_000L)
        service.keepAliveTick()
        assertFalse(service.isMoving, "no acceleration, no displacement: still")
        // Never fully dark: the low-power request is still there.
        assertEquals(LOW_POWER_INTERVAL_MS, service.lowPowerIntervalMs)
        assertTrue(service.passiveRequested)
    }

    @Test
    fun powerSaveLengthensTheLowPowerInterval() {
        shadowOf(service.powerManager).setIsPowerSaveMode(true)
        service.refreshLowPowerUpdates()
        assertEquals(LOW_POWER_INTERVAL_POWER_SAVE_MS, service.lowPowerIntervalMs)
    }

    @Test
    fun coarseFixWakesMovementButIsNotHeldForDisplay() {
        advance(1_000L)
        val home = location()
        service.onLocationFix(home)
        assertFalse(service.isMoving)
        // Low-power network answer 3 km away with 600 m accuracy.
        val coarse = location(LocationManager.NETWORK_PROVIDER, lat = northOf(3_000.0), acc = 600f)
        service.onLocationFix(coarse)
        assertTrue(service.isMoving, "a coarse fix far from the anchor must wake movement detection")
        // The 100 m display gate still applies: the coarse fix is not held or published.
        assertSame(home, service.lastKnownLocation)
    }

    @Test
    fun vehicleWithLowAccelerationButDisplacementStaysMoving() {
        service.startMovingNow()
        service.onLocationFix(location())
        var meters = 0.0
        repeat(10) {
            advance(60_000L)
            meters += 600.0
            val now = SystemClock.elapsedRealtime()
            // The linear-acceleration path the sensor callback takes: smooth ride, 0.1 m/s².
            service.applyMotionTransition(service.motion.onAcceleration(0.1f, now))
            service.onLocationFix(location(lat = northOf(meters)))
            service.keepAliveTick()
            assertTrue(service.isMoving, "still moving after ${it + 1} min of displacement")
        }
        // The vehicle stops: once both quiet windows pass, the phone goes still.
        advance(MovementDetector.FIX_QUIET_MS + 1_000L)
        service.applyMotionTransition(service.motion.onTick(SystemClock.elapsedRealtime()))
        assertFalse(service.isMoving)
    }

    @Test
    fun staleHeldFixRequestsAForcedFixOncePerInterval() {
        assertNull(service.forcedFixDue(SystemClock.elapsedRealtime()), "fresh fix: nothing due")
        advance(ForcedFixPolicy.INTERVAL_DEFAULT_MS)
        assertEquals(ForcedFixReason.STALE_HELD_FIX, service.forcedFixDue(SystemClock.elapsedRealtime()))
        // The attempt is recorded: the next heartbeat tick does not retry.
        advance(30_000L)
        assertNull(service.forcedFixDue(SystemClock.elapsedRealtime()))
    }

    @Test
    fun chargingTightensTheForcedFixInterval() {
        shadowOf(service.bm).setIsCharging(true)
        advance(ForcedFixPolicy.INTERVAL_CHARGING_MS)
        assertEquals(ForcedFixReason.STALE_HELD_FIX, service.forcedFixDue(SystemClock.elapsedRealtime()))
    }

    @Test
    fun forcedFixFallsBackToGpsWhenNetworkIsTooCoarseToDisplay() = runBlocking {
        advance(ForcedFixPolicy.INTERVAL_DEFAULT_MS + 1_000L)
        networkAnswer = location(LocationManager.NETWORK_PROVIDER, acc = 500f)
        val gps = location(acc = 8f)
        gpsAnswer = gps
        service.runForcedFix()
        val expected = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        assertEquals(expected, forcedCalls.toList())
        assertSame(gps, service.lastKnownLocation, "the fresh GPS fix replaces the stale held one")
    }

    @Test
    fun forcedFixStopsAtNetworkWhenItIsGoodEnough() = runBlocking {
        advance(ForcedFixPolicy.INTERVAL_DEFAULT_MS + 1_000L)
        val network = location(LocationManager.NETWORK_PROVIDER, acc = 30f)
        networkAnswer = network
        service.runForcedFix()
        assertEquals(listOf(LocationManager.NETWORK_PROVIDER), forcedCalls.toList())
        assertSame(network, service.lastKnownLocation)
    }

    @Test
    fun coarseForcedFixFarAwayWakesMovementEvenWithoutAGpsFix() = runBlocking {
        service.onLocationFix(location())
        advance(ForcedFixPolicy.INTERVAL_DEFAULT_MS + 1_000L)
        networkAnswer = location(LocationManager.NETWORK_PROVIDER, lat = northOf(4_000.0), acc = 700f)
        gpsAnswer = null
        service.runForcedFix()
        assertTrue(service.isMoving)
        assertEquals(MotionTransition.NONE, service.motion.onTick(SystemClock.elapsedRealtime()))
    }
}
