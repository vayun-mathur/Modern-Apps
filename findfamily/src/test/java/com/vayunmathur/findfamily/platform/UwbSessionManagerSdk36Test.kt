package com.vayunmathur.findfamily.platform

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.TrackerStore
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.UserKind
import com.vayunmathur.findfamily.domain.UwbEnvelope
import com.vayunmathur.findfamily.domain.UwbEnvelopeKind
import com.vayunmathur.findfamily.domain.UwbHandshake
import com.vayunmathur.findfamily.domain.UwbInbox
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * [UwbSessionManager] handshake state machine on SDK 36 (where the ranging API exists).
 *
 * There is no peer and no socket in the sandbox, so every handshake must fail closed:
 * the attempt settles back to [UwbSessionManager.UwbSessionState.Idle] with no peer
 * attached, instead of hanging in Starting/WaitingForPeer or crashing the scope.
 * One repository backs the whole class because the manager keeps the repository from
 * its first (and only effective) `init`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UwbSessionManagerSdk36Test {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repo: FindFamilyRepository get() = repoRef!!

    @BeforeTest
    fun setup() {
        runBlocking {
            if (repoRef == null) {
                repoRef = TestFindFamilyRepository.install(ctx)
            }
            UwbSessionManager.init(ctx, repoRef!!)
            UwbSessionManager.stop()
            settle()
        }
    }

    private suspend fun settle(timeoutMs: Long = 8_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (UwbSessionManager.state.value is UwbSessionManager.UwbSessionState.Idle &&
                UwbSessionManager.peerUserId.value == null
            ) return true
            delay(50)
        }
        return UwbSessionManager.state.value is UwbSessionManager.UwbSessionState.Idle
    }

    private suspend fun addUser(platform: String?, kind: UserKind = UserKind.PERSON): Long =
        repo.upsertUser(
            User(
                name = "Peer-$platform-$kind",
                photo = null,
                locationName = "Home",
                sendingEnabled = true,
                requestStatus = RequestStatus.MUTUAL_CONNECTION,
                lastLocationChangeTime = Clock.System.now(),
                platform = platform,
                kind = kind,
            )
        )

    @Test fun availabilityMatchesSdkAndFeature() {
        assertEquals(
            UwbSessionManager.isSupportedSdk &&
                ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_UWB),
            UwbSessionManager.isAvailable(ctx),
        )
        assertTrue(UwbSessionManager.isSupportedSdk)
    }

    @Test fun initiatorWithUnknownPeerFailsClosed() = runBlocking {
        UwbSessionManager.startAsInitiator(999_999L)
        assertTrue(settle(), "unknown peer must settle to Idle, was ${UwbSessionManager.state.value}")
        assertNull(UwbSessionManager.peerUserId.value)
    }

    @Test fun initiatorWithAndroidPeerFailsClosedWithoutSocket() = runBlocking {
        val id = addUser(platform = "android")
        UwbSessionManager.startAsInitiator(id)
        assertTrue(settle(), "android peer without socket must settle to Idle, was ${UwbSessionManager.state.value}")
        assertNull(UwbSessionManager.peerUserId.value)
    }

    @Test fun initiatorWithIosPeerFailsClosedWithoutSocket() = runBlocking {
        val id = addUser(platform = "ios")
        UwbSessionManager.startAsInitiator(id)
        assertTrue(settle(), "ios peer without socket must settle to Idle, was ${UwbSessionManager.state.value}")
        assertNull(UwbSessionManager.peerUserId.value)
    }

    @Test fun initiatorWithUnboundTrackerFailsClosed() = runBlocking {
        val id = addUser(platform = null, kind = UserKind.TRACKER)
        UwbSessionManager.startAsInitiator(id)
        assertTrue(settle(), "tracker without stored secret must settle to Idle, was ${UwbSessionManager.state.value}")
        assertNull(UwbSessionManager.peerUserId.value)
    }

    @Test fun initiatorWithBoundTrackerButNoBleFailsClosed() = runBlocking {
        val id = addUser(platform = null, kind = UserKind.TRACKER)
        TrackerStore(DataStoreUtils.getInstance(ctx))
            .save(id, ByteArray(32) { 1 }, ByteArray(4), "AA:BB:CC:DD:EE:FF")
        UwbSessionManager.startAsInitiator(id)
        // GATT cannot complete in the sandbox; either the attempt fails closed on its
        // own or it is still waiting — stop() must always recover to Idle either way.
        delay(2_000)
        UwbSessionManager.stop()
        assertTrue(settle(), "tracker find must recover to Idle after stop, was ${UwbSessionManager.state.value}")
        assertNull(UwbSessionManager.peerUserId.value)
    }

    @Test fun incomingAndroidRequestFailsClosedWithoutSocket() = runBlocking {
        val id = addUser(platform = "android")
        assertTrue(
            UwbInbox.tryEmit(
                UwbEnvelope(
                    sessionId = "sess-android-req",
                    sender = id.toULong(),
                    senderPlatform = "android",
                    kind = UwbEnvelopeKind.REQUEST,
                    payload = UwbHandshake(
                        addressB64 = UwbBytes.b64(byteArrayOf(3, 4)),
                        sessionId = 11,
                        sessionKeyB64 = UwbBytes.b64(ByteArray(8)),
                        channelNumber = 9,
                        preambleIndex = 10,
                    ),
                )
            )
        )
        assertTrue(settle(), "incoming request without socket must settle to Idle, was ${UwbSessionManager.state.value}")
        // The request was consumed as a pending request or handled; a cancel for a
        // foreign session must not disturb the (now idle) manager.
        UwbInbox.tryEmit(
            UwbEnvelope(
                sessionId = "someone-else",
                sender = id.toULong(),
                senderPlatform = "android",
                kind = UwbEnvelopeKind.CANCEL,
            )
        )
        delay(300)
        assertTrue(UwbSessionManager.state.value is UwbSessionManager.UwbSessionState.Idle)
    }

    @Test fun incomingIosRequestFailsClosedWithoutSocket() = runBlocking {
        val id = addUser(platform = "ios")
        assertTrue(
            UwbInbox.tryEmit(
                UwbEnvelope(
                    sessionId = "sess-ios-req",
                    sender = id.toULong(),
                    senderPlatform = "ios",
                    kind = UwbEnvelopeKind.REQUEST,
                )
            )
        )
        assertTrue(settle(), "incoming ios request without socket must settle to Idle, was ${UwbSessionManager.state.value}")
    }

    @Test fun stopFromIdleIsStable() {
        UwbSessionManager.stop()
        assertTrue(UwbSessionManager.state.value is UwbSessionManager.UwbSessionState.Idle)
        assertNull(UwbSessionManager.peerUserId.value)
    }

    private companion object {
        // One repository for the whole class: the manager keeps the repository from
        // its first (only effective) init, and Robolectric re-creates the test-class
        // instance per method — an instance field would silently swap databases.
        var repoRef: FindFamilyRepository? = null
    }
}
