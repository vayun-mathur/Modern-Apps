package com.vayunmathur.findfamily.util

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.tracker.PoweredOffKeyStore
import com.vayunmathur.findfamily.tracker.TrackerProtocol
import com.vayunmathur.findfamily.tracker.poweredOffGrantSigningBytes
import com.vayunmathur.findfamily.uwb.PoweredOffGrant
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.findfamily.uwb.UwbEnvelopeKind
import com.vayunmathur.library.util.DataStoreUtils
import kotlin.io.encoding.Base64
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
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Inbound enrichment branches and powered-off grant acceptance over real
 * Room: waypoint exit with hysteresis, low-battery crossing, and grants
 * signed by a peer identity minted in the test (accepted, stale and
 * forged outcomes asserted in the key store).
 */
@RunWith(AndroidJUnit4::class)
class InboundEnrichRealRepoTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app
    private lateinit var repo: com.vayunmathur.findfamily.data.FindFamilyRepository
    private lateinit var service: LocationTrackingService

    @BeforeTest
    fun setup() {
        runCatching {
            androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build())
        }
        repo = TestFindFamilyRepository.install(ctx)
        service = Robolectric.buildService(LocationTrackingService::class.java).create().get()
        service.repositoryOverride = repo
        service.setupNotificationChannels()
    }

    private fun user(name: String, id: Long = 0, lastWaypointId: Long? = null, locationName: String = "Elsewhere") = User(
        name = name, photo = null, locationName = locationName, sendingEnabled = true,
        requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
        id = id, lastWaypointId = lastWaypointId,
    )

    private fun fix(userid: Long, coord: Coord, acc: Float = 5f, battery: Float = 90f) = LocationValue(
        userid = userid, coord = coord, speed = 0f, acc = acc,
        timestamp = Clock.System.now(), battery = battery, reportedAt = Clock.System.now(),
        source = LocationSource.LIVE,
    )

    private fun notifications() = Shadows.shadowOf(
        ctx.getSystemService(NotificationManager::class.java) as NotificationManager
    ).allNotifications.size

    @Test fun enrichExitLeavesWaypointAndNotifies() = runBlocking {
        val wp = repo.waypointStore.upsert(Waypoint(name = "HomeBase", range = 200.0, coord = Coord(51.5, -0.12)))
        val uid = repo.upsertUser(user("Carol", lastWaypointId = wp, locationName = "HomeBase"))
        val before = notifications()
        // A fix kilometres away with tight accuracy: Carol has left.
        service.enrichIncomingLocations(listOf(fix(uid, Coord(52.5, 0.12))), emptyMap())
        val row = repo.getUser(uid)!!
        assertNull(row.lastWaypointId)
        assertEquals("Unknown Location", row.locationName)
        assertTrue(notifications() > before, "exit notification posted")
        Unit
    }

    @Test fun enrichCoarseFixKeepsPreviousWaypoint() = runBlocking {
        val wp = repo.waypointStore.upsert(Waypoint(name = "HomeBase", range = 200.0, coord = Coord(51.5, -0.12)))
        val uid = repo.upsertUser(user("Dan", lastWaypointId = wp, locationName = "HomeBase"))
        // Accuracy worse than the geofence gate: membership must not move.
        service.enrichIncomingLocations(listOf(fix(uid, Coord(60.0, 10.0), acc = 500f)), emptyMap())
        assertEquals(wp, repo.getUser(uid)!!.lastWaypointId)
        assertEquals("HomeBase", repo.getUser(uid)!!.locationName)
        Unit
    }

    @Test fun enrichLowBatteryCrossingNotifiesOnce() = runBlocking {
        val wp = repo.waypointStore.upsert(Waypoint(name = "HomeBase", range = 500.0, coord = Coord(51.5, -0.12)))
        val uid = repo.upsertUser(user("Eve", lastWaypointId = wp, locationName = "HomeBase"))
        val previous = fix(uid, Coord(51.5, -0.12), battery = 90f)
        val before = notifications()
        service.enrichIncomingLocations(
            listOf(fix(uid, Coord(51.5, -0.12), battery = 10f)),
            mapOf(uid to previous),
        )
        assertTrue(notifications() > before, "low-battery notification posted")
        // Already below the threshold last time: no second alert.
        val settled = notifications()
        service.enrichIncomingLocations(
            listOf(fix(uid, Coord(51.5, -0.12), battery = 9f)),
            mapOf(uid to fix(uid, Coord(51.5, -0.12), battery = 10f)),
        )
        assertEquals(settled, notifications())
        Unit
    }

    @Test fun enrichSelfUserUsesStoredFix() = runBlocking {
        Networking.init(repo, DataStoreUtils.getInstance(ctx), "Me")
        Networking.repository = repo
        val selfId = Networking.userid
        val wp = repo.waypointStore.upsert(Waypoint(name = "HomeBase", range = 500.0, coord = Coord(51.5, -0.12)))
        if (repo.getUser(selfId) == null) {
            repo.upsertUser(user("Me", id = selfId))
        }
        val stored = fix(selfId, Coord(51.5, -0.12))
        service.enrichIncomingLocations(emptyList(), mapOf(selfId to stored))
        // Self has no inbound fix in the batch; the stored latest is used.
        val row = repo.getUser(selfId)
        assertTrue(row?.lastWaypointId == wp || row?.locationName == "HomeBase")
        Unit
    }

    private fun grantEnvelope(
        peerId: Long,
        peerDsaPriv: ByteArray,
        epoch: Long,
        secret: ByteArray,
        recoveryPriv: ByteArray,
        tamper: Boolean = false,
    ): UwbEnvelope {
        val signed = poweredOffGrantSigningBytes(peerId, Networking.userid, epoch, secret, recoveryPriv)
        val signature = Pqc.signWith(peerDsaPriv, signed)
        return UwbEnvelope(
            sessionId = "grant-$epoch", sender = peerId.toULong(), senderPlatform = "android",
            kind = UwbEnvelopeKind.POF_GRANT,
            recovery = PoweredOffGrant(
                epoch = epoch,
                secretB64 = Base64.encode(secret),
                recoveryPrivB64 = Base64.encode(recoveryPriv),
                sigB64 = Base64.encode(if (tamper) ByteArray(signature.size) { 1 } else signature),
            ),
        )
    }

    @Test fun poweredOffGrantAcceptanceStaleAndForged() = runBlocking {
        Networking.init(repo, DataStoreUtils.getInstance(ctx), "Me")
        Networking.repository = repo
        val pair = Networking.generatePqcIdentityKeyPair()
        val peerId = repo.upsertUser(User(
            name = "Owner", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
            pqcEncryptionKey = pair.publicBundleB64,
        ))
        val privateBundle = Base64.decode(pair.privateBundleB64)
        val kemPriv = TrackerProtocol.kemPrivFromPrivateBundle(privateBundle)
        val dsaPriv = privateBundle.copyOfRange(4 + kemPriv.size, privateBundle.size)
        val store = PoweredOffKeyStore(com.vayunmathur.findfamily.data.DirectBootStore.store(ctx))
        service.poweredOffKeys = store

        val secret = ByteArray(32) { 3 }
        val recoveryPriv = ByteArray(16) { 4 }
        service.acceptPoweredOffGrant(grantEnvelope(peerId, dsaPriv, 5L, secret, recoveryPriv))
        assertTrue(store.canRead(peerId))
        assertContentEquals(secret, store.secret(peerId))
        assertEquals(5L, store.epoch(peerId))

        // A stale (older-epoch) grant cannot overwrite the fresher keys.
        val staleSecret = ByteArray(32) { 9 }
        service.acceptPoweredOffGrant(grantEnvelope(peerId, dsaPriv, 4L, staleSecret, recoveryPriv))
        assertContentEquals(secret, store.secret(peerId))
        assertEquals(5L, store.epoch(peerId))

        // A forged signature is refused even at a newer epoch.
        service.acceptPoweredOffGrant(grantEnvelope(peerId, dsaPriv, 6L, staleSecret, recoveryPriv, tamper = true))
        assertContentEquals(secret, store.secret(peerId))
        assertEquals(5L, store.epoch(peerId))

        // A grant from an unknown sender is refused outright.
        val unknown = grantEnvelope(987654L, dsaPriv, 7L, secret, recoveryPriv)
        service.acceptPoweredOffGrant(unknown)
        assertFalse(store.canRead(987654L))
        assertNull(store.secret(987654L))
        Unit
    }
}
