package com.vayunmathur.findfamily.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.findfamily.uwb.UwbEnvelopeKind
import com.vayunmathur.findfamily.uwb.UwbInbox
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * [UwbSessionManager] lifecycle on a host with no UWB radio: initialisation
 * subscribes to [UwbInbox], an incoming REQUEST moves the manager out of
 * Idle (and a peer CANCEL tears it back down), and stop() always settles
 * to no-peer. The radio itself is device-bound; what is asserted here is
 * the state machine and inbox wiring around it.
 */
@RunWith(AndroidJUnit4::class)
class UwbSessionManagerRealRepoTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest
    fun setup() {
        val repo = TestFindFamilyRepository.install(ctx)
        UwbSessionManager.init(ctx, repo)
    }

    private fun envelope(kind: String, sender: Long, session: String) = UwbEnvelope(
        sessionId = session, sender = sender.toULong(), senderPlatform = "android", kind = kind,
    )

    @Test fun availabilityGateReadsSystemFeature() {
        // Robolectric has no UWB system feature; the gate must say so.
        assertEquals(
            UwbSessionManager.isSupportedSdk &&
                ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_UWB),
            UwbSessionManager.isAvailable(ctx),
        )
    }

    @Test fun incomingRequestAndCancelDriveStateMachine() = runBlocking {
        val repo = TestFindFamilyRepository.install(ctx)
        UwbSessionManager.init(ctx, repo)
        val peerId = repo.upsertUser(User(
            name = "Peer", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
        ))
        assertTrue(UwbInbox.tryEmit(envelope(UwbEnvelopeKind.REQUEST, peerId, "sess-req")))
        // Give the inbox collector a moment to run the responder path.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            UwbSessionManager.state.value is UwbSessionManager.UwbSessionState.Idle
        ) {
            delay(50)
        }
        assertNotNull(UwbSessionManager.state.value)
        // A peer cancel for that session tears the attempt down.
        UwbInbox.tryEmit(envelope(UwbEnvelopeKind.CANCEL, peerId, "sess-req"))
        delay(300)
        UwbSessionManager.stop()
        assertNull(UwbSessionManager.peerUserId.value)
        Unit
    }

    @Test fun initiatorStartAndStopSettleCleanly() = runBlocking {
        // Whether the radio path starts or refuses (uninitialised / no
        // hardware), stop() must leave the manager peer-less and reusable.
        UwbSessionManager.startAsInitiator(4242L)
        delay(200)
        UwbSessionManager.stop()
        assertNull(UwbSessionManager.peerUserId.value)
        assertNotNull(UwbSessionManager.state.value)
        Unit
    }
}
