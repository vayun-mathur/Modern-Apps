package com.vayunmathur.communicate.signal

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalProtocol
import com.vayunmathur.communicate.data.signal.acceptCall
import com.vayunmathur.communicate.data.signal.endCall
import com.vayunmathur.communicate.data.signal.handleCallMessage
import com.vayunmathur.communicate.data.signal.placeCall
import com.vayunmathur.communicate.data.signal.placeGroupCall
import com.vayunmathur.communicate.data.signal.rejectCall
import com.vayunmathur.communicate.data.signal.runContactDiscovery
import com.vayunmathur.communicate.data.signal.setCallAudioEnabled
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Slice 2/6 pass 2: call signalling with no RingRTC manager — everything drops safely. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalSlice2Pass2CallsTest {

    private lateinit var db: SignalDatabase

    @BeforeTest fun open() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        SignalClient.db = db
        SignalClient.authData = SignalAuthData(phoneNumber = "+14155552671", aci = "3f0e5a2c-1111-2222-3333-444455556666", password = "secret")
        SignalClient.socket = null
        SignalClient.unauthSocket = null
        SignalClient.e2e = null
        SignalClient.pendingIdentityChanges.clear()
    }

    @AfterTest fun close() {
        SignalClient.db = null; SignalClient.authData = null; SignalClient.e2e = null
        SignalClient.pendingIdentityChanges.clear()
        db.close()
    }

    private fun envelope(): SignalProtocol.SignalEnvelope = SignalProtocol.SignalEnvelope(
        type = SignalServiceProtos.Envelope.Type.DOUBLE_RATCHET, sourceAci = "+14155550001", sourceDevice = 1,
        timestamp = 1000, serverTimestamp = 2000, content = ByteArray(0), urgent = true,
    )

    @Test fun `call controls without a manager all fail closed`() = runTest {
        assertFalse(SignalClient.acceptCall("1"))
        assertFalse(SignalClient.acceptCall("not-a-number"))
        // rejectCall reports true: RingRTC's absence still ends the call locally (it emits CallEnded)
        assertTrue(SignalClient.rejectCall("1"))
        assertFalse(SignalClient.endCall())
        assertFalse(SignalClient.setCallAudioEnabled(true))
        assertFalse(SignalClient.setCallAudioEnabled(false))
        assertFalse(SignalClient.placeGroupCall("group:xyz"))
        SignalClient.placeCall("someone", video = true) // fire-and-forget: must not throw offline
    }

    @Test fun `inbound call messages for other devices and without ringrtc are dropped`() = runTest {
        val otherDevice = SignalServiceProtos.CallMessage.newBuilder()
            .setDestinationDeviceId(99)
            .setOffer(SignalServiceProtos.CallMessage.Offer.newBuilder().setId(7).setOpaque(ByteString.copyFrom(byteArrayOf(1))))
            .build()
        SignalClient.handleCallMessage(otherDevice, "+14155550001", 1, envelope(), 1000)

        val offer = SignalServiceProtos.CallMessage.newBuilder()
            .setOffer(SignalServiceProtos.CallMessage.Offer.newBuilder().setId(7).setOpaque(ByteString.copyFrom(byteArrayOf(1))))
            .build()
        SignalClient.handleCallMessage(offer, "+14155550001", 1, envelope(), 1000)

        val hangup = SignalServiceProtos.CallMessage.newBuilder()
            .setHangup(SignalServiceProtos.CallMessage.Hangup.newBuilder().setId(7))
            .build()
        SignalClient.handleCallMessage(hangup, "+14155550001", 1, envelope(), 1000)
        // Reaching here without an exception is the contract: no manager, no crash
    }

    @Test fun `contact discovery with no device contacts completes trivially and resets its guard`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // No device contacts under Robolectric: sync completes with nothing to look up
        assertTrue(SignalClient.runContactDiscovery(ctx))
        // The running flag was reset in finally, so a second run also completes
        assertTrue(SignalClient.runContactDiscovery(ctx))
    }
}
