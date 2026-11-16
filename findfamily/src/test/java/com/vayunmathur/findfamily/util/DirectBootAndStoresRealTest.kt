package com.vayunmathur.findfamily.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.BatteryManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.InboundSpool
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.platform.FinalLocationReporter
import com.vayunmathur.findfamily.tracker.PoweredOffKeyStore
import com.vayunmathur.findfamily.tracker.TrackerStore
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pre-unlock (direct-boot) tracking paths and the shutdown reporter over
 * device-protected storage: the service is built for real, its repository
 * is the in-memory singleton, and the spool/store round-trips are asserted
 * on disk state.
 */
@RunWith(AndroidJUnit4::class)
class DirectBootAndStoresRealTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app
    private lateinit var service: LocationTrackingService

    @BeforeTest
    fun setup() {
        runCatching {
            androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build())
        }
        service = Robolectric.buildService(LocationTrackingService::class.java).create().get()
        service.repositoryOverride = TestFindFamilyRepository.install(ctx)
    }

    private fun spoolFile(): File =
        File(ctx.createDeviceProtectedStorageContext().filesDir, "ff_inbound_spool.bin")

    @Test fun directBootIdlesWhenMirrorUnseeded() = runBlocking {
        spoolFile().delete()
        service.startDirectBootTracking()
        delay(300)
        // No seeded mirror on a fresh Robolectric install: the job must
        // finish (idle) rather than minting an identity.
        assertTrue(service.directBootJob == null || service.directBootJob!!.isCompleted)
    }

    @Test fun spoolAndReplayPreUnlockFrames() = runBlocking {
        spoolFile().delete()
        val frame = byteArrayOf(Networking.WS_OP_MSG, 0, 1, 2, 3)
        service.spoolPreUnlockFrame(frame)
        assertTrue(spoolFile().exists())
        // Replay drains through dispatchLiveFrame (bad ciphertext drops) and clears the spool.
        service.replayPreUnlockInbound()
        assertFalse(spoolFile().exists())
        assertEquals(0, InboundSpool.drain(ctx) { error("spool must be empty after replay") })
    }

    @Test fun publishDirectBootWithoutFixOrTargetsIsNoOp() = runBlocking {
        service.lastKnownLocation = null
        service.publishDirectBoot(listOf(DirectBootStore.Target(1L, "bundle", null)))
        val location = Location("gps").apply {
            latitude = 12.0; longitude = 34.0; accuracy = 5f; time = System.currentTimeMillis()
        }
        service.lastKnownLocation = location
        // No targets: nothing to publish to, must return quietly.
        service.publishDirectBoot(emptyList())
        // With a target but no live socket, the publish degrades per-target.
        service.bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        service.publishDirectBoot(listOf(DirectBootStore.Target(2L, "aGVsbG8=", "pair")))
        assertEquals(12.0, service.lastKnownLocation?.latitude)
    }

    @Test fun trackerAndPoweredOffStoresRoundTrip() = runBlocking {
        val trackerStore = TrackerStore(DataStoreUtils.getInstance(ctx))
        trackerStore.save(42L, byteArrayOf(1, 2, 3), byteArrayOf(4, 5), "AA:BB:CC:DD:EE:FF")
        assertContentEquals(byteArrayOf(1, 2, 3), trackerStore.secret(42L))
        assertContentEquals(byteArrayOf(4, 5), trackerStore.privateBundle(42L))
        assertEquals("AA:BB:CC:DD:EE:FF", trackerStore.bleAddress(42L))
        assertNull(trackerStore.secret(43L))
        trackerStore.save(42L, byteArrayOf(9), byteArrayOf(8))
        assertContentEquals(byteArrayOf(9), trackerStore.secret(42L))
        // A save without an address leaves the previously bound address in place.
        assertEquals("AA:BB:CC:DD:EE:FF", trackerStore.bleAddress(42L))

        val pofStore = PoweredOffKeyStore(DirectBootStore.store(ctx))
        assertFalse(pofStore.canRead(7L))
        assertEquals(0L, pofStore.epoch(7L))
        pofStore.save(7L, byteArrayOf(1, 1), byteArrayOf(2, 2), epoch = 3L)
        assertTrue(pofStore.canRead(7L))
        assertContentEquals(byteArrayOf(1, 1), pofStore.secret(7L))
        assertContentEquals(byteArrayOf(2, 2), pofStore.kemPrivateKey(7L))
        assertEquals(3L, pofStore.epoch(7L))
        pofStore.save(7L, byteArrayOf(5), byteArrayOf(6), epoch = 4L)
        assertEquals(4L, pofStore.epoch(7L))
        pofStore.forget(7L)
        assertFalse(pofStore.canRead(7L))
        assertNull(pofStore.secret(7L))
    }

    @Test fun directBootStoreMirrorRoundTrip() = runBlocking {
        val targets = listOf(
            DirectBootStore.Target(11L, "bundle-a", "pair-a"),
            DirectBootStore.Target(12L, "bundle-b", null),
        )
        DirectBootStore.seed(ctx, targets, trackingEnabled = false, globalSharingEnabled = true)
        assertFalse(DirectBootStore.isTrackingEnabled(ctx))
        assertTrue(DirectBootStore.isGlobalSharingEnabled(ctx))
        // Seeding copies identity keys only if the CE store holds them; the
        // roster itself always round-trips.
        assertEquals(targets, DirectBootStore.roster(ctx))
        DirectBootStore.seed(ctx, emptyList(), trackingEnabled = true, globalSharingEnabled = false)
        assertTrue(DirectBootStore.isTrackingEnabled(ctx))
        assertFalse(DirectBootStore.isGlobalSharingEnabled(ctx))
        assertTrue(DirectBootStore.roster(ctx).isEmpty())
    }

    @Test fun finalReporterBroadcastsCoverReportBranches() {
        FinalLocationReporter.stop(ctx)
        var fix: Location? = null
        var roster: List<DirectBootStore.Target> = emptyList()
        FinalLocationReporter.start(ctx, { fix }, { roster })
        val receivers = Shadows.shadowOf(app).registeredReceivers
        val receiver = receivers.firstOrNull {
            it.intentFilter.hasAction(Intent.ACTION_BATTERY_LOW)
        }?.broadcastReceiver
        assertTrue(receiver != null, "final reporter registered its receiver")
        // Battery-low with no fix ever: reports nothing, finishes quietly.
        receiver!!.onReceive(ctx, Intent(Intent.ACTION_BATTERY_LOW))
        Thread.sleep(300)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        // With a fix but nobody to report to.
        fix = Location("gps").apply { latitude = 1.0; longitude = 2.0; accuracy = 5f }
        receiver.onReceive(ctx, Intent(Intent.ACTION_BATTERY_LOW))
        Thread.sleep(300)
        // With a target but no identity/socket: the report is dropped, logged.
        roster = listOf(DirectBootStore.Target(5L, "aGVsbG8=", null))
        receiver.onReceive(ctx, Intent(Intent.ACTION_BATTERY_LOW))
        Thread.sleep(300)
        FinalLocationReporter.stop(ctx)
        // Stopping twice is a no-op.
        FinalLocationReporter.stop(ctx)
    }
}
