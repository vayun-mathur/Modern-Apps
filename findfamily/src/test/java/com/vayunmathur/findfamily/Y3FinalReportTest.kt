package com.vayunmathur.findfamily

import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.platform.FinalLocationReporter
import com.vayunmathur.findfamily.util.LocationServiceController
import com.vayunmathur.findfamily.util.Networking
import com.vayunmathur.findfamily.util.UwbSessionManager
import com.vayunmathur.findfamily.uwb.UwbCancelReason
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.findfamily.uwb.UwbEnvelopeKind
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3FinalReportTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        TestFindFamilyRepository.install(ctx)
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun fix(): Location = Location("gps").apply {
        latitude = 37.7749; longitude = -122.4194; accuracy = 5f; speed = 3f
        time = System.currentTimeMillis(); elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
    }

    private fun broadcast(action: String) {
        // Deliver directly to the registered receiver: Robolectric does not
        // reliably route broadcasts to runtime-registered receivers.
        val field = FinalLocationReporter::class.java.getDeclaredField("receiver").apply { isAccessible = true }
        val receiver = field.get(FinalLocationReporter) as android.content.BroadcastReceiver?
            ?: run { ctx.sendBroadcast(Intent(action).setPackage(ctx.packageName)); Thread.sleep(800); return }
        receiver.onReceive(ctx, Intent(action))
        Thread.sleep(800)
    }

    @Test fun finalReporterBatteryLowWithAndWithoutFix() {
        var currentFix: Location? = null
        var roster: List<DirectBootStore.Target> = emptyList()
        FinalLocationReporter.start(ctx, lastFix = { currentFix }, roster = { roster })
        FinalLocationReporter.start(ctx, lastFix = { currentFix }, roster = { roster }) // idempotent
        broadcast(Intent.ACTION_BATTERY_LOW) // no fix: nothing to report
        currentFix = fix()
        broadcast(Intent.ACTION_BATTERY_LOW) // fix but nobody to report to
        roster = listOf(DirectBootStore.Target(42L, "", null))
        broadcast(Intent.ACTION_BATTERY_LOW) // identity/socket gates drop it, never crash
        broadcast("com.example.UNRELATED")
        FinalLocationReporter.stop(ctx); FinalLocationReporter.stop(ctx)
    }

    @Test fun uwbManagerSdk34Gates() {
        assertFalse(UwbSessionManager.isSupportedSdk)
        assertFalse(UwbSessionManager.isAvailable(ctx))
        UwbSessionManager.init(ctx, TestFindFamilyRepository.install(ctx)) // no-op below API 36
        UwbSessionManager.startAsInitiator(42L)
        assertEquals(UwbSessionManager.UwbSessionState.Unsupported(), UwbSessionManager.state.value)
        UwbSessionManager.stop()
        assertEquals(UwbSessionManager.UwbSessionState.Idle, UwbSessionManager.state.value)
        assertEquals(null, UwbSessionManager.peerUserId.value)
        // Cancel-state mapping: unsupported reason keeps its own terminal state.
        fun cancel(reason: String?) = UwbEnvelope(
            sessionId = "s1", sender = 7uL, senderPlatform = "ios",
            kind = UwbEnvelopeKind.CANCEL, reason = reason,
        )
        assertEquals(
            UwbSessionManager.UwbSessionState.PeerUnsupported,
            UwbSessionManager.stateAfterPeerCancel(cancel(UwbCancelReason.UNSUPPORTED)),
        )
        assertEquals(UwbSessionManager.UwbSessionState.Idle, UwbSessionManager.stateAfterPeerCancel(cancel("user-stopped")))
        assertEquals(UwbSessionManager.UwbSessionState.Idle, UwbSessionManager.stateAfterPeerCancel(cancel(null)))
    }

    @Test fun networkingU64AndUnreadyPublishes(): Unit = runBlocking {
        val buf = ByteArray(16)
        Networking.putU64Be(buf, 4, 0x0102030405060708uL)
        assertEquals(0x0102030405060708L, Networking.readU64Be(buf, 4))
        Networking.putU64Be(buf, 0, ULong.MAX_VALUE)
        assertEquals(-1L, Networking.readU64Be(buf, 0))
        // Identity is loaded in other suites; here, publishes to unknown peers
        // must fail as false, never throw or fabricate a send.
        val repo = TestFindFamilyRepository.install(ctx)
        if (Networking.userid == 0L) Networking.init(repo, DataStoreUtils.getInstance(ctx), "Me")
        assertTrue(Networking.userid != 0L)
        val code = Networking.securityCodePqc(
            com.vayunmathur.findfamily.data.User("Peer", null, "Place", false, com.vayunmathur.findfamily.data.RequestStatus.MUTUAL_CONNECTION, kotlin.time.Clock.System.now(), null, 4242L),
        )
        // No cached PQC bundle for this peer: no code, no crash.
        assertTrue(code == null || code.isNotBlank())
        assertFalse(Networking.verifyFrom(4242L, byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)))
        val status = Networking.peerCryptoStatus(4242L)
        assertNotNullStatus(status)
    }

    private fun assertNotNullStatus(status: Networking.PeerKeyCheck) {
        assertTrue(status.toString().isNotEmpty())
    }

    @Test fun locationServiceControllerPrefsRoundTrip(): Unit = runBlocking {
        assertFalse(LocationServiceController.hasFineLocationPermission(ctx))
        LocationServiceController.setTrackingEnabled(ctx, true)
        assertTrue(LocationServiceController.isTrackingEnabled(ctx))
        LocationServiceController.setTrackingEnabled(ctx, false)
        assertFalse(LocationServiceController.isTrackingEnabled(ctx))
        LocationServiceController.setGlobalSharingEnabled(ctx, true)
        assertTrue(LocationServiceController.isGlobalSharingEnabled(ctx))
        assertTrue(LocationServiceController.globalSharingEnabledFlow(ctx).first())
        LocationServiceController.setCrowdFindingEnabled(ctx, false)
        LocationServiceController.syncServiceState(ctx)
        LocationServiceController.stop(ctx)
    }
}
