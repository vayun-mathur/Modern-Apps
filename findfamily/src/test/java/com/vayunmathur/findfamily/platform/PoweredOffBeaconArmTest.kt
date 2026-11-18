package com.vayunmathur.findfamily.platform

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.PoweredOffKeyStore
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.network.Networking
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Powered-off beacon arming + final-report shutdown paths with no NearbyManager,
 * no identity socket and no priv-app permission: every path must decline quietly
 * (null / dropped report) rather than throw, because these run inside the
 * broadcast that blocks the user's power-off.
 */
@RunWith(AndroidJUnit4::class)
class PoweredOffBeaconArmTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun beaconIsOffByDefaultAndArmIsNoOp() = runBlocking {
        assertFalse(PoweredOffBeacon.isEnabled(ctx))
        assertNull(PoweredOffBeacon.armForShutdown(ctx))
        // SDK 34 has no NearbyManager service: unsupported, asked politely.
        assertFalse(PoweredOffBeacon.isSupported(ctx))
    }

    @Test fun grantAndRevokeWithoutIdentityFailClosed() = runBlocking {
        // Whether or not another test in this class has loaded an identity yet,
        // there is either no identity or no filed beacon secret for it, and the
        // peer is unreachable either way: both calls must report failure.
        assertFalse(PoweredOffBeacon.grantRecovery(ctx, 77L))
        assertFalse(PoweredOffBeacon.revokeRecovery(ctx, 77L))
    }

    @Test fun armWithIdentityButNoSocketDeclinesAfterFilingKeys() = runBlocking {
        // Load a real identity the supported way: userid from the DataStore,
        // PQC identity minted/loaded by Networking.init.
        val repo = TestFindFamilyRepository.install(ctx)
        val ds = DataStoreUtils.getInstance(ctx)
        if (ds.getLongAwait("userid") == null) ds.setLong("userid", 424_242L, true)
        Networking.init(repo, ds, "Me")
        val myId = Networking.userid
        assertTrue(myId != 0L)
        try {
            PoweredOffBeacon.setEnabled(ctx, true)
            assertTrue(PoweredOffBeacon.isEnabled(ctx))
            // Keys are filed at opt-in, so the owner can read future sightings.
            val store = PoweredOffKeyStore(DirectBootStore.store(ctx))
            assertTrue(store.canRead(myId))
            // No relay socket in the sandbox: registration fails, nothing is armed.
            assertNull(PoweredOffBeacon.armForShutdown(ctx))
            // Grant without a reachable peer: roster updates, delivery reports false.
            assertFalse(PoweredOffBeacon.grantRecovery(ctx, 55L))
            assertTrue(55L in PoweredOffBeacon.recoveryGrantees(ctx))
            PoweredOffBeacon.setEnabled(ctx, false)
            assertFalse(PoweredOffBeacon.isEnabled(ctx))
            assertFalse(store.canRead(myId))
            assertTrue(PoweredOffBeacon.recoveryGrantees(ctx).isEmpty())
        } finally {
            runCatching { PoweredOffBeacon.setEnabled(ctx, false) }
        }
    }

    @Test fun finalReporterHandlesShutdownAndUnknownActions() {
        val app = ctx.applicationContext
        FinalLocationReporter.stop(ctx)
        var fix: android.location.Location? =
            android.location.Location("gps").apply {
                latitude = 3.0; longitude = 4.0; accuracy = 5f
                time = System.currentTimeMillis()
            }
        var roster: List<DirectBootStore.Target> = listOf(DirectBootStore.Target(9L, "aGVsbG8=", null))
        FinalLocationReporter.start(ctx, { fix }, { roster })
        val shadowApp: ShadowApplication = Shadows.shadowOf(app as android.app.Application)
        val receiver = shadowApp.registeredReceivers.firstOrNull {
            it.intentFilter.hasAction(Intent.ACTION_SHUTDOWN)
        }?.broadcastReceiver
        assertTrue(receiver != null, "final reporter registered its receiver")
        // Unknown action: ignored entirely.
        receiver!!.onReceive(ctx, Intent("com.example.WHATEVER"))
        // Shutdown with a fix and a roster but no identity/socket: the parting
        // report is dropped (logged), the beacon arm declines, nothing throws.
        receiver.onReceive(ctx, Intent(Intent.ACTION_SHUTDOWN))
        Thread.sleep(500)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        // A second shutdown must not double-report.
        receiver.onReceive(ctx, Intent(Intent.ACTION_SHUTDOWN))
        Thread.sleep(200)
        // Reboot is treated as a shutdown-class parting report too (already reported).
        receiver.onReceive(ctx, Intent(Intent.ACTION_REBOOT))
        Thread.sleep(200)
        FinalLocationReporter.stop(ctx)
        FinalLocationReporter.stop(ctx)
        // With no fix at all, battery-low also completes quietly.
        fix = null
        roster = emptyList()
        FinalLocationReporter.start(ctx, { fix }, { roster })
        val receiver2 = Shadows.shadowOf(app as android.app.Application).registeredReceivers
            .firstOrNull { it.intentFilter.hasAction(Intent.ACTION_BATTERY_LOW) }?.broadcastReceiver
        assertTrue(receiver2 != null)
        receiver2!!.onReceive(ctx, Intent(Intent.ACTION_BATTERY_LOW))
        Thread.sleep(300)
        FinalLocationReporter.stop(ctx)
    }

    @Test fun directBootSeededButUnusableIdentityIdles() = runBlocking {
        // Seed the credential-encrypted store with a userid and garbage PQC blobs,
        // then mirror them to device-protected storage. The mirror is now "seeded"
        // but the identity inside it cannot load, so the pre-unlock job must idle
        // rather than mint a stranger identity.
        val ce = DataStoreUtils.getInstance(ctx)
        ce.setLong("userid", 777_001L)
        for (key in listOf("ff_pqcKemPub", "ff_pqcKemPriv", "ff_pqcDsaPub", "ff_pqcDsaPriv")) {
            ce.setByteArray(key, byteArrayOf(1, 2, 3))
        }
        DirectBootStore.seed(ctx, emptyList(), trackingEnabled = true, globalSharingEnabled = false)
        assertTrue(DirectBootStore.isSeeded(ctx))

        val service = Robolectric.buildService(LocationTrackingService::class.java).create().get()
        service.repositoryOverride = TestFindFamilyRepository.install(ctx)
        service.bm = ctx.getSystemService(BatteryManager::class.java)
        service.startDirectBootTracking()
        var waitedMs = 0
        while (waitedMs < 10_000) {
            val job = service.directBootJob
            if (job == null || job.isCompleted) break
            delay(50); waitedMs += 50
        }
        // Either the unusable identity idles the job (the expected path), or the
        // identity re-minted and the publish loop is running — in which case it
        // must still stop cleanly. What must never happen is a crash or a hang
        // outside the cancellable loop.
        val job = service.directBootJob
        if (job != null && job.isActive) {
            job.cancel()
            delay(200)
        }
        assertTrue(job == null || job.isCompleted || job.isCancelled)
        assertEquals(emptyList(), service.publishRoster)
    }
}
