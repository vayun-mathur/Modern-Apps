package com.vayunmathur.findfamily.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Relay frame layer over a real in-memory repository: [dispatchLiveFrame]
 * parses KEYRESP / RESOLVE_RESP / REPORT_GET_RESP frames and completes the
 * pending lookups, and every socket-down publish path degrades to
 * false/null/empty instead of throwing.
 */
@RunWith(AndroidJUnit4::class)
class NetworkingFramesRealRepoTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest
    fun setup() {
        Networking.repository = TestFindFamilyRepository.install(ctx)
        Networking.stopLive()
    }

    private fun user(id: Long = 42, key: String? = null) = User(
        name = "Ann", photo = null, locationName = "Home", sendingEnabled = true,
        requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
        id = id, pqcEncryptionKey = key,
    )

    private fun fix(userid: Long) = LocationValue(
        userid = userid, coord = Coord(1.0, 2.0), speed = 0f, acc = 5f,
        timestamp = Clock.System.now(), battery = 50f, reportedAt = Clock.System.now(),
        source = LocationSource.LIVE,
    )

    private fun keyResp(status: Int, target: Long, bundle: ByteArray?): ByteArray {
        val frame = ByteArray(Networking.KEYRESP_HEADER_LEN + (bundle?.size ?: 0))
        frame[0] = Networking.WS_OP_GETKEY_RESP
        frame[Networking.KEYRESP_STATUS_OFFSET] = status.toByte()
        Networking.putU64Be(frame, Networking.KEYRESP_TARGET_OFFSET, target.toULong())
        bundle?.copyInto(frame, Networking.KEYRESP_HEADER_LEN)
        return frame
    }

    @Test fun u64RoundTripsEdgeValues() {
        for (value in listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0x0102030405060708L)) {
            val buf = ByteArray(Networking.U64_LEN + 2)
            Networking.putU64Be(buf, 1, value.toULong())
            assertEquals(value, Networking.readU64Be(buf, 1))
        }
    }

    @Test fun keyResponseCompletesPendingLookup() = runBlocking {
        val deferred = CompletableDeferred<Networking.KeyResult?>()
        Networking.pendingKeyRequests[99L] = deferred
        dispatchLiveFrame(keyResp(2, 99L, byteArrayOf(1, 2, 3)), {}, {})
        val result = deferred.await()
        assertEquals(2, result?.status)
        assertContentEquals(byteArrayOf(1, 2, 3), result?.bundle)
        assertFalse(Networking.pendingKeyRequests.containsKey(99L))
    }

    @Test fun keyResponseWithoutBundleAndShortFramesAreDropped() = runBlocking {
        val deferred = CompletableDeferred<Networking.KeyResult?>()
        Networking.pendingKeyRequests[7L] = deferred
        dispatchLiveFrame(byteArrayOf(Networking.WS_OP_GETKEY_RESP, 1), {}, {})
        assertFalse(deferred.isCompleted)
        dispatchLiveFrame(keyResp(0, 7L, null), {}, {})
        assertEquals(0, deferred.await()?.status)
        assertNull(deferred.await()?.bundle)
    }

    @Test fun resolveResponseCompletesPendingResolve() = runBlocking {
        val epoch = ByteArray(16) { it.toByte() }
        val hex = epoch.toHex()
        val deferred = CompletableDeferred<ByteArray?>()
        Networking.pendingResolves[hex] = deferred
        val frame = ByteArray(Networking.RESOLVE_RESP_MIN_LEN + 3)
        frame[0] = WS_OP_RESOLVE_RESP
        frame[Networking.RESOLVE_STATUS_OFFSET] = Networking.RESOLVE_FOUND_STATUS.toByte()
        epoch.copyInto(frame, Networking.RESOLVE_EPOCH_OFFSET)
        byteArrayOf(9, 8, 7).copyInto(frame, Networking.RESOLVE_RESP_MIN_LEN)
        dispatchLiveFrame(frame, {}, {})
        assertContentEquals(byteArrayOf(9, 8, 7), deferred.await())
        // A not-found response completes with null.
        val missing = CompletableDeferred<ByteArray?>()
        Networking.pendingResolves[hex] = missing
        val notFound = frame.copyOf()
        notFound[Networking.RESOLVE_STATUS_OFFSET] = 0
        dispatchLiveFrame(notFound, {}, {})
        assertNull(missing.await())
        // Short resolve frames are ignored.
        dispatchLiveFrame(byteArrayOf(WS_OP_RESOLVE_RESP, 1, 2), {}, {})
    }

    @Test fun reportGetResponseParsesLengthPrefixedReports() = runBlocking {
        val deferred = CompletableDeferred<List<ByteArray>>()
        Networking.pendingReportGets.add(deferred)
        val first = byteArrayOf(1, 2)
        val second = byteArrayOf(3, 4, 5)
        val frame = ByteArray(Networking.REPORT_GET_RESP_HEADER_LEN + 4 + first.size + 4 + second.size)
        frame[0] = WS_OP_REPORT_GET_RESP
        frame[Networking.REPORT_COUNT_OFFSET] = 0
        frame[Networking.REPORT_COUNT_OFFSET + 1] = 2
        var off = Networking.REPORT_GET_RESP_HEADER_LEN
        for (report in listOf(first, second)) {
            frame[off + 3] = report.size.toByte()
            off += Networking.U32_LEN
            report.copyInto(frame, off)
            off += report.size
        }
        dispatchLiveFrame(frame, {}, {})
        val reports = deferred.await()
        assertEquals(2, reports.size)
        assertContentEquals(first, reports[0])
        assertContentEquals(second, reports[1])
    }

    @Test fun reportGetResponseStopsAtTruncatedEntry() = runBlocking {
        val deferred = CompletableDeferred<List<ByteArray>>()
        Networking.pendingReportGets.add(deferred)
        // Count says two, but the second length prefix overruns the frame.
        val frame = byteArrayOf(WS_OP_REPORT_GET_RESP, 0, 2, 0, 0, 0, 1, 42, 0, 0, 0, 99, 1)
        dispatchLiveFrame(frame, {}, {})
        val reports = deferred.await()
        assertEquals(1, reports.size)
        assertContentEquals(byteArrayOf(42), reports[0])
        // No pending consumer: a stray response is dropped, not queued.
        dispatchLiveFrame(frame, {}, {})
        // Short frames are ignored entirely.
        dispatchLiveFrame(byteArrayOf(WS_OP_REPORT_GET_RESP), {}, {})
    }

    @Test fun msgFramesInRawModeAndBadCiphertextAreHandled() = runBlocking {
        val raw = mutableListOf<ByteArray>()
        val msg = byteArrayOf(Networking.WS_OP_MSG, 0, 1, 2, 3)
        dispatchLiveFrame(msg, { error("decrypt path must not run in raw mode") }, {}, { raw.add(it) })
        assertEquals(1, raw.size)
        // Undecryptable location and UWB payloads are dropped silently (no identity on this host).
        dispatchLiveFrame(msg, { error("bad ciphertext must not be delivered") }, {})
        dispatchLiveFrame(byteArrayOf(Networking.WS_OP_MSG, Networking.WS_FLAG_UWB.toByte(), 9, 9), {}, { error("bad UWB must not be delivered") })
        // Empty and unknown frames are no-ops.
        dispatchLiveFrame(byteArrayOf(), {}, {})
        dispatchLiveFrame(byteArrayOf(0x7f, 1, 2), {}, {})
    }

    @Test fun socketDownPublishesDegradeGracefully() = runBlocking {
        assertFalse(Networking.liveConnected)
        assertFalse(Networking.publishLocation(fix(42), user()))
        val link = TemporaryLink(name = "Trip", deleteAt = Clock.System.now(), pqcPublicKey = "aGVsbG8=", id = 5L)
        assertFalse(Networking.publishLocation(fix(42), link))
        assertFalse(Networking.publishLocation(fix(42), 42L, "aGVsbG8="))
        assertFalse(Networking.publishUwbMessage(
            com.vayunmathur.findfamily.uwb.UwbEnvelope(
                sessionId = "s", sender = 1uL, senderPlatform = "android", kind = "request",
            ), 42L, user(),
        ))
        assertEquals(Networking.PeerCrypto.UNKNOWN, Networking.peerCryptoStatus(42L).status)
        assertFalse(Networking.verifyFrom(424242L, byteArrayOf(1), byteArrayOf(2)))
    }

    @Test fun trackerHelpersWithoutSocketDegradeGracefully() = runBlocking {
        assertFalse(Networking.registerTracker(1L, byteArrayOf(1), byteArrayOf(2)))
        assertNull(Networking.resolveTrackerBundle(ByteArray(16)))
        assertFalse(Networking.uploadTrackerReport(ByteArray(16), byteArrayOf(1)))
        assertTrue(Networking.fetchTrackerReports(listOf(ByteArray(16))).isEmpty())
        assertTrue(Networking.fetchTrackerReports(emptyList()).isEmpty())
        assertFalse(Networking.registerPoweredOffEids(1L, listOf(ByteArray(16)), byteArrayOf(1)))
        assertFalse(Networking.registerPoweredOffEids(1L, emptyList(), byteArrayOf(1)))
    }

    @Test fun stopLiveFailsEveryPendingLookup() = runBlocking {
        val key = CompletableDeferred<Networking.KeyResult?>()
        val resolve = CompletableDeferred<ByteArray?>()
        val reports = CompletableDeferred<List<ByteArray>>()
        Networking.pendingKeyRequests[1L] = key
        Networking.pendingResolves["aa"] = resolve
        Networking.pendingReportGets.add(reports)
        Networking.stopLive()
        assertNull(key.await())
        assertNull(resolve.await())
        assertTrue(reports.await().isEmpty())
        assertTrue(Networking.pendingKeyRequests.isEmpty())
        assertTrue(Networking.pendingResolves.isEmpty())
        assertTrue(Networking.pendingReportGets.isEmpty())
    }

    @Test fun initCreatesSelfUserAndIsIdempotent() = runBlocking {
        val repo = Networking.repository
        val ds = DataStoreUtils.getInstance(ctx)
        Networking.init(repo, ds, "Me")
        val firstId = Networking.userid
        assertTrue(firstId > 0)
        assertEquals("Me", repo.getUser(firstId)?.name)
        Networking.init(repo, ds, "Me")
        assertEquals(firstId, Networking.userid)
        assertEquals(1, repo.getAllUsers().count { it.id == firstId })
        // No mirrored identity in a fresh store: direct-boot init refuses.
        assertFalse(Networking.initDirectBoot(DataStoreUtils.getInstance(ctx)))
    }
}
