package com.vayunmathur.findfamily.util
import com.vayunmathur.findfamily.network.generatePqcIdentityKeyPair
import com.vayunmathur.findfamily.network.generatePqcLinkKey
import com.vayunmathur.findfamily.network.myInviteFingerprint
import com.vayunmathur.findfamily.network.registerTracker
import com.vayunmathur.findfamily.network.resolveTrackerBundle
import com.vayunmathur.findfamily.network.uploadTrackerReport
import com.vayunmathur.findfamily.network.fetchTrackerReports
import com.vayunmathur.findfamily.network.registerPoweredOffEids
import com.vayunmathur.findfamily.network.WS_OP_POF_REGISTER
import com.vayunmathur.findfamily.network.WS_OP_REPORT_GET_RESP
import com.vayunmathur.findfamily.network.WS_OP_REPORT_PUT
import com.vayunmathur.findfamily.network.WS_OP_RESOLVE_RESP
import com.vayunmathur.findfamily.network.WS_OP_TRACKER_REGISTER
import com.vayunmathur.findfamily.network.Networking

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Robolectric tests for [Networking] codec + fail-closed branches when PQC/socket are unavailable. */
@RunWith(AndroidJUnit4::class)
class NetworkingCodecTest {
    @Test fun u64RoundTrip() {
        val buf = ByteArray(16)
        for (v in listOf(0uL, 1uL, 255uL, 0x0102030405060708uL, ULong.MAX_VALUE, 123456789uL)) {
            Networking.putU64Be(buf, 4, v)
            assertEquals(v.toLong(), Networking.readU64Be(buf, 4))
        }
        val b = ByteArray(8); Networking.putU64Be(b, 0, 0x0102030405060708uL)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), b.map { it.toInt() })
    }
    @Test fun noSocketOrPqcFailsClosed() = runTest {
        assertNull(Networking.myInviteFingerprint())
        assertNull(Networking.signAsMe(byteArrayOf(1)))
        val user = User(name = "A", photo = null, locationName = "x", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, id = 7L)
        assertNull(Networking.securityCodePqc(user))
        assertEquals(Networking.PeerCrypto.UNKNOWN, Networking.peerCryptoStatus(7L).status)
        val fix = LocationValue(userid = 7L, coord = Coord(1.0, 2.0), speed = 0f, acc = 1f,
            timestamp = Instant.fromEpochMilliseconds(1000), battery = 50f)
        assertFalse(Networking.publishLocation(fix, user))
        assertFalse(Networking.publishLocation(fix, TemporaryLink(name = "l", deleteAt = Instant.fromEpochMilliseconds(9999999999L), pqcPublicKey = "aGVsbG8=", id = 3L)))
        assertFalse(Networking.registerTracker(1L, byteArrayOf(1), byteArrayOf(2)))
        assertNull(Networking.resolveTrackerBundle(ByteArray(16)))
        assertFalse(Networking.uploadTrackerReport(ByteArray(16), byteArrayOf(1)))
        assertEquals(emptyList(), Networking.fetchTrackerReports(listOf(ByteArray(16))))
        assertEquals(emptyList(), Networking.fetchTrackerReports(emptyList()))
        Networking.stopLive()
    }
    @Test fun linkKeyGenerationShape() {
        val k = Networking.generatePqcLinkKey()
        assertTrue(k.seedB64Url.isNotBlank()); assertFalse(k.seedB64Url.contains('='))
        assertTrue(k.publicBundleB64.isNotBlank())
        val kp = Networking.generatePqcIdentityKeyPair()
        assertTrue(kp.publicBundleB64.isNotBlank()); assertTrue(kp.privateBundleB64.isNotBlank())
    }
}
