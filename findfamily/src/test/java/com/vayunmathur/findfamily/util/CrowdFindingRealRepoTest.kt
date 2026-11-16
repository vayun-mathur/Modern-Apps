package com.vayunmathur.findfamily.util

import android.location.Location
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.UserKind
import com.vayunmathur.findfamily.tracker.PoweredOffKeyStore
import com.vayunmathur.findfamily.tracker.PoweredOffProtocol
import com.vayunmathur.findfamily.tracker.PoweredOffReporting
import com.vayunmathur.findfamily.tracker.PoweredOffSighting
import com.vayunmathur.findfamily.tracker.TrackerProtocol
import com.vayunmathur.findfamily.tracker.TrackerReporting
import com.vayunmathur.findfamily.tracker.TrackerSighting
import com.vayunmathur.findfamily.tracker.TrackerStore
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WsSession
import com.vayunmathur.library.util.DataStoreUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import kotlin.io.encoding.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Crowd-finding owner/finder paths over a fake live socket and real PQC
 * keys: tracker and powered-off sightings are sealed, "fetched" through
 * the relay frame layer, decrypted, and stored as pins in Room.
 */
@RunWith(AndroidJUnit4::class)
class CrowdFindingRealRepoTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var repo: com.vayunmathur.findfamily.data.FindFamilyRepository
    private lateinit var service: LocationTrackingService
    private lateinit var out: ByteArrayOutputStream

    @BeforeTest
    fun setup() = runBlocking {
        runCatching {
            androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build())
        }
        repo = TestFindFamilyRepository.install(ctx)
        Networking.stopLive()
        Networking.init(repo, DataStoreUtils.getInstance(ctx), "Me")
        Networking.repository = repo
        out = ByteArrayOutputStream()
        Networking.wsSession = fakeSession(out)
        service = Robolectric.buildService(LocationTrackingService::class.java).create().get()
        service.repositoryOverride = repo
        Unit
    }

    private fun fakeSession(output: ByteArrayOutputStream): WsSession {
        val clientConstructor = WebSocketClient::class.java
            .getDeclaredConstructor(
                Socket::class.java, java.io.InputStream::class.java,
                java.io.OutputStream::class.java, Map::class.java, Map::class.java,
            ).apply { isAccessible = true }
        val client = clientConstructor.newInstance(
            Socket(), ByteArrayInputStream(ByteArray(0)), output,
            emptyMap<String, List<String>>(), emptyMap<String, String>(),
        )
        return WsSession::class.java
            .getDeclaredConstructor(WebSocketClient::class.java)
            .apply { isAccessible = true }
            .newInstance(client)
    }

    private fun location(userid: Long, lat: Double = 51.5, lon: Double = -0.12) = LocationValue(
        userid = userid, coord = Coord(lat, lon), speed = 0f, acc = 5f,
        timestamp = Clock.System.now(), battery = 42f, reportedAt = Clock.System.now(),
        source = LocationSource.LIVE,
    )

    private suspend fun dispatchReportGet(ciphertexts: List<ByteArray>) {
        var size = Networking.REPORT_GET_RESP_HEADER_LEN
        ciphertexts.forEach { size += Networking.U32_LEN + it.size }
        val frame = ByteArray(size)
        frame[0] = WS_OP_REPORT_GET_RESP
        frame[Networking.REPORT_COUNT_OFFSET + 1] = ciphertexts.size.toByte()
        var off = Networking.REPORT_GET_RESP_HEADER_LEN
        for (ct in ciphertexts) {
            frame[off] = (ct.size ushr 24).toByte()
            frame[off + 1] = (ct.size ushr 16).toByte()
            frame[off + 2] = (ct.size ushr 8).toByte()
            frame[off + 3] = ct.size.toByte()
            off += Networking.U32_LEN
            ct.copyInto(frame, off)
            off += ct.size
        }
        dispatchLiveFrame(frame, {}, {})
    }

    private suspend fun dispatchResolve(epochId: ByteArray, bundle: ByteArray) {
        val frame = ByteArray(Networking.RESOLVE_RESP_MIN_LEN + bundle.size)
        frame[0] = WS_OP_RESOLVE_RESP
        frame[Networking.RESOLVE_STATUS_OFFSET] = Networking.RESOLVE_FOUND_STATUS.toByte()
        val handle = if (epochId.size == PoweredOffProtocol.EID_LEN) {
            PoweredOffProtocol.handleOf(epochId)
        } else {
            epochId
        }
        handle.copyInto(frame, Networking.RESOLVE_EPOCH_OFFSET)
        bundle.copyInto(frame, Networking.RESOLVE_RESP_MIN_LEN)
        dispatchLiveFrame(frame, {}, {})
    }

    @Test fun trackerOwnerPollStoresDecryptedSighting() = runBlocking {
        val pair = Networking.generatePqcIdentityKeyPair()
        val publicBundle = Base64.decode(pair.publicBundleB64)
        val privateBundle = Base64.decode(pair.privateBundleB64)
        val trackerId = repo.upsertUser(User(
            name = "Keys", photo = null, locationName = "Unknown Location", sendingEnabled = false,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
            pqcEncryptionKey = pair.publicBundleB64, kind = UserKind.TRACKER,
        ))
        val store = TrackerStore(DataStoreUtils.getInstance(ctx))
        val secret = ByteArray(32) { (it + 1).toByte() }
        store.save(trackerId, secret, privateBundle, "AA:BB")
        service.trackerStore = store

        val sighting = location(userid = 0L, lat = 40.0, lon = -70.0)
        val ct = TrackerProtocol.sealReport(publicBundle, sighting)
        val poll = async { service.pollTrackerReports() }
        delay(250)
        dispatchReportGet(listOf(ct))
        poll.await()
        val stored = repo.locationHistory(trackerId).first()
        assertEquals(1, stored.size)
        assertEquals(Coord(40.0, -70.0), stored.single().coord)
        Unit
    }

    @Test fun trackerPollWithoutStoreOrTrackersIsNoOp() = runBlocking {
        service.trackerStore = null
        service.pollTrackerReports()
        service.trackerStore = TrackerStore(DataStoreUtils.getInstance(ctx))
        service.pollTrackerReports()
        assertTrue(repo.getAllUsers().isEmpty())
    }

    @Test fun trackerReportingRejectsNonTrackerAndMissingKeys() = runBlocking {
        val store = TrackerStore(DataStoreUtils.getInstance(ctx))
        val person = User(
            name = "Person", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
            id = 5L,
        )
        assertFalse(TrackerReporting.registerTracker(person, store))
        assertTrue(TrackerReporting.fetchTrackerLocations(person, store).isEmpty())
        val tracker = person.copy(id = 6L, kind = UserKind.TRACKER)
        assertFalse(TrackerReporting.registerTracker(tracker, store))
        assertTrue(TrackerReporting.fetchTrackerLocations(tracker, store).isEmpty())
    }

    @Test fun trackerFinderReportSealsAndUploads() = runBlocking {
        val pair = Networking.generatePqcIdentityKeyPair()
        val publicBundle = Base64.decode(pair.publicBundleB64)
        val epochId = ByteArray(16) { (it + 3).toByte() }
        val reported = async {
            TrackerReporting.reportSighting(TrackerSighting(epochId, battery = 80, rssi = -60), location(0L))
        }
        delay(200)
        dispatchResolve(epochId, publicBundle)
        assertTrue(reported.await())
        // Unresolvable sightings report false.
        val unresolved = async {
            TrackerReporting.reportSighting(TrackerSighting(ByteArray(16) { 9 }, battery = -1, rssi = -90), location(0L))
        }
        delay(200)
        val frame = ByteArray(Networking.RESOLVE_RESP_MIN_LEN)
        frame[0] = WS_OP_RESOLVE_RESP
        frame[Networking.RESOLVE_STATUS_OFFSET] = 0
        ByteArray(16) { 9 }.copyInto(frame, Networking.RESOLVE_EPOCH_OFFSET)
        dispatchLiveFrame(frame, {}, {})
        assertFalse(unresolved.await())
    }

    @Test fun poweredOffOwnerPollStoresNetworkSighting() = runBlocking {
        val pair = Networking.generatePqcIdentityKeyPair()
        val publicBundle = Base64.decode(pair.publicBundleB64)
        val privateBundle = Base64.decode(pair.privateBundleB64)
        val kemPriv = TrackerProtocol.kemPrivFromPrivateBundle(privateBundle)
        val peerId = repo.upsertUser(User(
            name = "Lost Phone", photo = null, locationName = "Unknown Location", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
        ))
        val store = PoweredOffKeyStore(com.vayunmathur.findfamily.data.DirectBootStore.store(ctx))
        val secret = ByteArray(32) { (it + 7).toByte() }
        store.save(peerId, secret, kemPriv, epoch = 1L)
        service.poweredOffKeys = store
        service.lastPoweredOffPollMs = 0L

        val ct = TrackerProtocol.sealReport(publicBundle, location(0L, lat = 48.0, lon = 2.0))
        val poll = async { service.pollPoweredOffSightings() }
        delay(250)
        dispatchReportGet(listOf(ct))
        poll.await()
        val stored = repo.locationHistory(peerId).first()
        assertEquals(1, stored.size)
        assertEquals(LocationSource.NETWORK_SIGHTING, stored.single().source)
        // Rate-limited: an immediate second poll does not run again.
        service.pollPoweredOffSightings()
        assertEquals(1, repo.locationHistory(peerId).first().size)
        Unit
    }

    @Test fun poweredOffFinderReportDedupesAfterSuccess() = runBlocking {
        val pair = Networking.generatePqcIdentityKeyPair()
        val publicBundle = Base64.decode(pair.publicBundleB64)
        val eid = ByteArray(PoweredOffProtocol.EID_LEN) { (it + 11).toByte() }
        val sighting = PoweredOffSighting(eid, rssi = -70)
        val reported = async { PoweredOffReporting.reportSighting(sighting, location(0L)) }
        delay(200)
        dispatchResolve(eid, publicBundle)
        assertTrue(reported.await())
        assertTrue(PoweredOffReporting.isRecentlyReported(eid))
        // Second sighting of the same EID within its slot is not re-reported.
        assertFalse(PoweredOffReporting.reportSighting(sighting, location(0L)))
    }

    @Test fun scannersStartAndCancelCleanly() = runBlocking {
        service.bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
        service.lastKnownLocation = Location("gps").apply {
            latitude = 1.0; longitude = 2.0; accuracy = 5f
        }
        service.startTrackerScanner()
        assertTrue(service.trackerScanJob != null)
        service.startTrackerScanner()
        service.trackerScanJob?.cancel()
        service.startPoweredOffScanner()
        assertTrue(service.poweredOffScanJob != null)
        service.startPoweredOffScanner()
        service.poweredOffScanJob?.cancel()
        delay(100)
    }
}
