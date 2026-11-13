package com.vayunmathur.findfamily.platform

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Looper
import android.os.SystemClock
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.domain.ForcedFixPolicy
import com.vayunmathur.findfamily.util.LocationTrackingService
import com.vayunmathur.findfamily.util.Networking
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The 15-minute active-check ceiling at service level: the Doze-proof alarm is armed while
 * sharing, re-armed after every check, cancelled when sharing stops or the service dies;
 * a ceiling tick asks the current-location source (never last-known) even while a fresh
 * passive fix is held and the movement detector says still; and the result is published
 * through the normal heartbeat path.
 */
@RunWith(AndroidJUnit4::class)
class FixCeilingAlarmTest {

    private lateinit var db: FFDatabase
    private lateinit var repo: FindFamilyRepository
    private lateinit var controller: ServiceController<LocationTrackingService>
    private lateinit var service: LocationTrackingService
    private val ctx: Context get() = ApplicationProvider.getApplicationContext<Application>()
    private val alarms: ShadowAlarmManager get() = shadowOf(service.getSystemService(AlarmManager::class.java))

    private val forcedCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var networkAnswer: Location? = null
    @Volatile private var gpsAnswer: Location? = null

    private fun location(provider: String = LocationManager.GPS_PROVIDER, acc: Float = 10f) =
        Location(provider).apply {
            latitude = 51.5
            longitude = -0.12
            accuracy = acc
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            time = System.currentTimeMillis()
        }

    private fun advance(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
    private fun now() = SystemClock.elapsedRealtime()

    /** Lets background coroutines that hop onto the (paused) main looper finish. */
    private fun pumpUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for the service" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun await(job: Job?) {
        assertNotNull(job, "expected a forced fix to start")
        pumpUntil { job.isCompleted }
    }

    @BeforeTest
    fun setup() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        db = Room.inMemoryDatabaseBuilder(ctx, FFDatabase::class.java).allowMainThreadQueries().build()
        repo = FindFamilyRepository(ctx, db)
        controller = Robolectric.buildService(LocationTrackingService::class.java).create()
        service = controller.get()
        service.repositoryOverride = repo
        if (Networking.userid == 0L) {
            runBlocking { Networking.init(repo, DataStoreUtils.getInstance(ctx), "Me") }
        }
        val lm = service.getSystemService(LocationManager::class.java)
        shadowOf(lm).setLocationEnabled(true)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setProviderEnabled(LocationManager.PASSIVE_PROVIDER, true)
        service.currentLocationSourceOverride = CurrentLocationSource { provider, _ ->
            forcedCalls += provider
            if (provider == LocationManager.NETWORK_PROVIDER) networkAnswer else gpsAnswer
        }
        service.lastKnownLocation = location()
        service.registerSensors()
        service.bm = ctx.getSystemService(BatteryManager::class.java)
    }

    @AfterTest
    fun teardown() {
        service.serviceScope.cancel()
        FixCeilingAlarm.releaseWakeLock()
        db.close()
    }

    @Test
    fun sharingArmsAnExactAllowWhileIdleAlarmAndStoppingCancelsIt() {
        assertTrue(alarms.scheduledAlarms.isEmpty(), "no ceiling while sharing with nobody")
        service.updateFixCeiling(true)
        val alarm = assertNotNull(alarms.peekNextScheduledAlarm())
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.getType())
        assertTrue(alarm.isAllowWhileIdle, "must fire in Doze")
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
        // Never checked yet: due now.
        assertEquals(now(), alarm.triggerAtMs)

        service.updateFixCeiling(false)
        assertTrue(alarms.scheduledAlarms.isEmpty(), "sharing stopped: alarm cancelled")
    }

    @Test
    fun everyCheckMovesTheAlarmToOneCeilingLater() {
        service.updateFixCeiling(true)
        val first = now()
        await(service.fixCeilingAlarmTick())
        assertEquals(listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER), forcedCalls.toList())
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(first + ForcedFixPolicy.ACTIVE_CHECK_DUE_MS, alarms.peekNextScheduledAlarm()?.triggerAtMs)

        advance(ForcedFixPolicy.ACTIVE_CHECK_DUE_MS)
        val second = now()
        await(service.fixCeilingAlarmTick())
        assertEquals(1, alarms.scheduledAlarms.size, "moved, not duplicated")
        assertEquals(second + ForcedFixPolicy.ACTIVE_CHECK_DUE_MS, alarms.peekNextScheduledAlarm()?.triggerAtMs)
    }

    @Test
    fun ceilingAsksForACurrentFixDespiteAFreshPassiveFixAndStillMotion() = runBlocking {
        service.updateFixCeiling(true)
        networkAnswer = location(LocationManager.NETWORK_PROVIDER, acc = 30f)
        await(service.fixCeilingAlarmTick())
        forcedCalls.clear()
        // Every publish inserts a self row (autogenerated id; timestamps are epoch seconds,
        // so count rows rather than compare stamps).
        val publishesBefore = repo.locationHistory(Networking.userid).first().size
        assertTrue(publishesBefore >= 1, "the first check was published too")

        // Thirteen minutes of passive fixes (another app's GPS) keep the held fix fresh,
        // and the phone does not move: before the ceiling nothing asks for a fix...
        repeat(13) {
            advance(60_000L)
            service.passiveListener.onLocationChanged(location(LocationManager.PASSIVE_PROVIDER, acc = 15f))
            assertEquals(null, service.fixCeilingAlarmTick(), "no check before the ceiling (minute ${it + 1})")
        }
        assertFalse(service.isMoving)
        assertTrue(forcedCalls.isEmpty())

        // ...and at the ceiling the current-location source is asked anyway.
        advance(60_000L)
        service.passiveListener.onLocationChanged(location(LocationManager.PASSIVE_PROVIDER, acc = 15f))
        await(service.fixCeilingAlarmTick())
        assertEquals(listOf(LocationManager.NETWORK_PROVIDER), forcedCalls.toList(), "network was good enough")
        assertFalse(service.isMoving, "the check does not need movement")

        // Same place, but the check is published through the normal path right away.
        val history = repo.locationHistory(Networking.userid).first()
        assertEquals(publishesBefore + 1, history.size, "the checked fix was published")
        assertTrue(Clock.System.now() - history.maxOf { it.timestamp } < 60.seconds)
    }

    @Test
    fun powerSaveCeilingFallsBackToGpsButTheStalenessCheckStillDoesNot() {
        shadowOf(service.powerManager).setIsPowerSaveMode(true)
        networkAnswer = location(LocationManager.NETWORK_PROVIDER, acc = 500f)
        gpsAnswer = location(acc = 8f)

        // Ceiling off (not sharing): a stale held fix in power-save stays network-only.
        advance(ForcedFixPolicy.INTERVAL_POWER_SAVE_MS)
        await(service.fixCeilingAlarmTick())
        assertEquals(listOf(LocationManager.NETWORK_PROVIDER), forcedCalls.toList())

        // Ceiling on: the 15-minute check tries GPS when network is unusable.
        forcedCalls.clear()
        service.updateFixCeiling(true)
        advance(ForcedFixPolicy.ACTIVE_CHECK_DUE_MS)
        await(service.fixCeilingAlarmTick())
        assertEquals(listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER), forcedCalls.toList())
    }

    @Test
    fun inexactFallbackArmsEarlyEnoughToLandInsideTheCeiling() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        service.updateFixCeiling(true)
        val checkedAt = now()
        await(service.fixCeilingAlarmTick())
        val alarm = assertNotNull(alarms.peekNextScheduledAlarm())
        assertTrue(alarm.isAllowWhileIdle)
        val delay = alarm.triggerAtMs - checkedAt
        // Latest inexact delivery is 1.75x the delay after arming.
        assertTrue(checkedAt + (delay * 1.75).toLong() <= checkedAt + ForcedFixPolicy.ACTIVE_CHECK_MAX_GAP_MS)
        assertTrue(delay > 0L)
    }

    @Test
    fun receiverHoldsTheCpuAndPokesTheRunningService() {
        FixCeilingReceiver().onReceive(ctx, Intent(FixCeilingAlarm.ACTION_ALARM))
        val started = assertNotNull(shadowOf(ctx as Application).nextStartedService)
        assertEquals(FixCeilingAlarm.ACTION_CHECK, started.action)
        assertEquals(LocationTrackingService::class.java.name, started.component?.className)
        assertTrue(FixCeilingAlarm.isWakeLockHeld)
        FixCeilingAlarm.releaseWakeLock()
        assertFalse(FixCeilingAlarm.isWakeLockHeld)
    }

    @Test
    fun alarmCommandChecksReArmsAndReleasesTheCpu() = runBlocking {
        repo.upsertUser(
            User(
                name = "Peer", photo = null, locationName = "Elsewhere", sendingEnabled = true,
                requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
            )
        )
        service.trackingInitialized = true
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        FixCeilingAlarm.acquireWakeLock(ctx)
        val firedAt = now()
        val command = Intent(service, LocationTrackingService::class.java).setAction(FixCeilingAlarm.ACTION_CHECK)
        service.onStartCommand(command, 0, 1)
        pumpUntil { !FixCeilingAlarm.isWakeLockHeld }
        assertTrue(service.fixCeilingEnabled, "a peer with sending on means sharing")
        assertEquals(LocationManager.NETWORK_PROVIDER, forcedCalls.firstOrNull())
        assertEquals(firedAt + ForcedFixPolicy.ACTIVE_CHECK_DUE_MS, alarms.peekNextScheduledAlarm()?.triggerAtMs)
    }

    @Test
    fun destroyingTheServiceCancelsTheAlarm() {
        service.updateFixCeiling(true)
        assertEquals(1, alarms.scheduledAlarms.size)
        controller.destroy()
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }
}
