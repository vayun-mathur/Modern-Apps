package com.vayunmathur.findfamily.util
import com.vayunmathur.findfamily.network.InboundFix
import com.vayunmathur.findfamily.platform.LocationTrackingService
import com.vayunmathur.findfamily.notifications.createNotification
import com.vayunmathur.findfamily.notifications.createNotificationWithCategory
import com.vayunmathur.findfamily.notifications.createUwbRequestNotification
import com.vayunmathur.findfamily.platform.enrichIncomingLocations
import com.vayunmathur.findfamily.platform.handleUwbEnvelopes
import com.vayunmathur.findfamily.notifications.notifyEntryExit
import com.vayunmathur.findfamily.notifications.notifyNoShow
import com.vayunmathur.findfamily.platform.processIncomingFixes
import com.vayunmathur.findfamily.platform.processIncomingLocations
import com.vayunmathur.findfamily.notifications.setupNotificationChannels

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.domain.IngestSequencing
import com.vayunmathur.findfamily.domain.UwbCancelReason
import com.vayunmathur.findfamily.domain.UwbEnvelope
import com.vayunmathur.findfamily.domain.UwbEnvelopeKind
import com.vayunmathur.findfamily.domain.UwbInbox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class LocationTrackingInboundTest {

    private lateinit var db: FFDatabase
    private lateinit var repo: FindFamilyRepository
    private lateinit var service: LocationTrackingService
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    @BeforeTest
    fun setup() {
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(ctx, FFDatabase::class.java).allowMainThreadQueries().build()
        repo = FindFamilyRepository(ctx, db)
        service = Robolectric.buildService(LocationTrackingService::class.java).create().get()
        service.repositoryOverride = repo
    }

    @AfterTest fun teardown() { db.close() }

    private fun user(name: String, status: RequestStatus = RequestStatus.MUTUAL_CONNECTION, id: Long = 0, inbound: String? = null) = User(
        name = name, photo = null, locationName = "Elsewhere", sendingEnabled = true,
        requestStatus = status, lastLocationChangeTime = Clock.System.now(), id = id, inboundPairId = inbound,
    )

    private fun fix(userid: Long, at: Instant = Clock.System.now(), acc: Float = 5f, battery: Float = 90f, source: LocationSource = LocationSource.LIVE, coord: Coord = Coord(51.5, -0.12)) = LocationValue(
        userid = userid, coord = coord, speed = 0f, acc = acc, timestamp = at, battery = battery, reportedAt = at, source = source,
    )

    @Test
    fun processIncomingLocationsFiltersAndPersists() = runBlocking {
        val uid = repo.upsertUser(user("Alice"))
        service.processIncomingLocations(listOf(
            fix(uid, source = LocationSource.UNKNOWN),
            fix(uid, acc = 99999f),
            fix(uid, acc = -1f),
            fix(uid),
        ))
        val stored = repo.locationHistory(uid).first()
        assertEquals(1, stored.size)
        assertEquals(90f, stored[0].battery)
        // Empty after filtering: no crash, nothing stored.
        service.processIncomingLocations(listOf(fix(uid, source = LocationSource.UNKNOWN)))
        assertEquals(1, repo.locationHistory(uid).first().size)
        Unit
    }

    @Test
    fun processIncomingFixesAuthGates() = runBlocking {
        val alice = repo.upsertUser(user("Alice", inbound = "expected-pair"))
        val pending = repo.upsertUser(user("Pending", status = RequestStatus.AWAITING_REQUEST))
        // Future-dated fix from Alice (fast clock, +48h): NOT dropped, but its
        // reportedAt is clamped at ingest to receiver-now + 5 min slack, so it
        // cannot pin MAX(reportedAt) forever. Stored exactly once, clamped.
        val before = Clock.System.now().epochSeconds
        val future = Instant.fromEpochSeconds(before + 48 * 3600)
        service.processIncomingFixes(listOf(InboundFix(fix(alice, at = future), "expected-pair")))
        val clamped = repo.locationHistory(alice).first()
        assertEquals(1, clamped.size)
        assertTrue(
            clamped[0].reportedAt.epochSeconds in (before + 4 * 60)..(before + 6 * 60 + 5),
            "reportedAt clamped to ~now+5min, was ${clamped[0].reportedAt}",
        )
        // Wrong pair id: dropped.
        service.processIncomingFixes(listOf(InboundFix(fix(alice), "wrong-pair")))
        assertEquals(1, repo.locationHistory(alice).first().size)
        // Correct pair id: stored. Stamped past the clamped future fix so it
        // is also newer under per-sender sequencing (a now-stamped fix here
        // would be stale against the clamped high-water and dropped — that
        // is the sequencing working, not the auth gate).
        service.processIncomingFixes(listOf(InboundFix(fix(alice, at = future + 1.hours), "expected-pair")))
        assertEquals(2, repo.locationHistory(alice).first().size)
        // Ineligible sender (awaiting request): coordinates never stored.
        service.processIncomingFixes(listOf(InboundFix(fix(pending), null)))
        assertTrue(repo.locationHistory(pending).first().isEmpty())
        // Stranger: filed as an awaiting-request row, coordinates not stored.
        service.processIncomingFixes(listOf(InboundFix(fix(987654L), null)))
        val stranger = repo.getUser(987654L)
        assertNotNull(stranger)
        assertEquals(RequestStatus.AWAITING_REQUEST, stranger.requestStatus)
        assertTrue(repo.locationHistory(987654L).first().isEmpty())
        // Empty batch is a no-op.
        service.processIncomingFixes(emptyList())
    }

    @Test
    fun processIncomingFixesPinsFirstPairId() = runBlocking {
        val bob = repo.upsertUser(user("Bob"))
        service.processIncomingFixes(listOf(InboundFix(fix(bob), "first-pair")))
        assertEquals("first-pair", repo.getUser(bob)?.inboundPairId)
        assertEquals(1, repo.locationHistory(bob).first().size)
        // A later fix with a different pair id is now dropped.
        service.processIncomingFixes(listOf(InboundFix(fix(bob), "other-pair")))
        assertEquals(1, repo.locationHistory(bob).first().size)
    }

    @Test
    fun enrichResolvesWaypointAndNotifies() = runBlocking {
        val uid = repo.upsertUser(user("Carol"))
        val wpId = repo.waypointStore.upsert(Waypoint(name = "HomeBase", range = 500.0, coord = Coord(51.5, -0.12)))
        val now = Clock.System.now()
        service.enrichIncomingLocations(
            listOf(fix(uid, at = now, battery = 10f, coord = Coord(51.5, -0.12))),
            emptyMap(),
        )
        val row = repo.getUser(uid)!!
        assertEquals("HomeBase", row.locationName)
        assertEquals(wpId, row.lastWaypointId)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        assertTrue(Shadows.shadowOf(nm).allNotifications.isNotEmpty())
    }

    @Test
    fun networkSightingDoesNotFireLowBattery() = runBlocking {
        val wpId = repo.waypointStore.upsert(Waypoint(name = "HomeBase", range = 500.0, coord = Coord(51.5, -0.12)))
        val uid = repo.upsertUser(user("Eve").copy(locationName = "HomeBase", lastWaypointId = wpId))
        val now = Clock.System.now()
        val previous = fix(uid, at = now, battery = 90f)
        // A powered-off sighting of Eve: finder's position, battery sealed as 0.
        // Already inside the same waypoint, so entry/exit cannot fire either;
        // only the low-battery path could produce a notification here.
        service.enrichIncomingLocations(
            listOf(fix(uid, at = now, battery = 0f, source = LocationSource.NETWORK_SIGHTING)),
            mapOf(uid to previous),
        )
        val nm = ctx.getSystemService(NotificationManager::class.java)
        assertTrue(Shadows.shadowOf(nm).allNotifications.isEmpty())
    }

    @Test
    fun notificationBuildersAndUwbEnvelopes() = runBlocking {
        service.setupNotificationChannels()
        assertNotNull(service.createNotification())
        assertNotNull(service.createNotificationWithCategory("Title", "Body", "CAT", 12L))
        val u = user("Dave", id = 55)
        service.notifyEntryExit(u, "Dave arrived at Home", arrival = true)
        service.notifyNoShow(u, "Dave did not arrive", 99L, null)
        assertNotNull(service.createUwbRequestNotification("Dave", 55L))
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val before = Shadows.shadowOf(nm).allNotifications.size
        assertTrue(before >= 2)
        service.handleUwbEnvelopes(emptyList())
        service.handleUwbEnvelopes(
            listOf(
                // Different sender than the direct call above, whose notification it would replace.
                UwbEnvelope(sessionId = "s1", sender = 77uL, senderPlatform = "android", kind = UwbEnvelopeKind.REQUEST),
                UwbEnvelope(sessionId = "s2", sender = 56uL, senderPlatform = "android", kind = UwbEnvelopeKind.ACK),
            ),
            // The Robolectric sandbox (SDK 34) has no UWB; this path is about a phone that does.
            uwbAvailable = true,
        )
        delay(300)
        assertTrue(Shadows.shadowOf(nm).allNotifications.size > before)
        // Powered-off grant from unknown/unconfigured path: silently ignored (no keys store).
        service.handleUwbEnvelopes(listOf(
            UwbEnvelope(sessionId = "s3", sender = 55uL, senderPlatform = "android", kind = UwbEnvelopeKind.POF_GRANT,
                recovery = com.vayunmathur.findfamily.domain.PoweredOffGrant(epoch = 1, secretB64 = "AA==", recoveryPrivB64 = "AA==", sigB64 = "AA==")),
        ))
        Unit
    }

    @Test
    fun unsupportedPhoneDeclinesUwbRequestWithoutNotifying() = runBlocking {
        service.setupNotificationChannels()
        // Even with the radio feature present, the SDK 34 sandbox is below the Android 16
        // ranging API, so the default availability gate must say no (#869).
        Shadows.shadowOf(ctx.packageManager).setSystemFeature(PackageManager.FEATURE_UWB, true)
        val bob = repo.upsertUser(user("Bob"))
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val before = Shadows.shadowOf(nm).allNotifications.size
        val reply = CompletableDeferred<Pair<UwbEnvelope, Long>>()
        service.handleUwbEnvelopes(
            listOf(UwbEnvelope(sessionId = "req-1", sender = bob.toULong(), senderPlatform = "android", kind = UwbEnvelopeKind.REQUEST)),
            sendUwb = { env, to, peer ->
                assertEquals("Bob", peer?.name)
                reply.complete(env to to)
                true
            },
        )
        val (env, to) = withTimeout(5_000) { reply.await() }
        assertEquals(bob, to)
        assertEquals(UwbEnvelopeKind.CANCEL, env.kind)
        assertEquals(UwbCancelReason.UNSUPPORTED, env.reason)
        assertEquals("req-1", env.sessionId)
        // No "someone is finding you" notification, and nothing handed to the ranging inbox.
        assertEquals(before, Shadows.shadowOf(nm).allNotifications.size)
        assertNull(UwbInbox.consumePendingRequest(bob))
    }

    @Test
    fun supportedPhoneStillNotifiesAndSendsNoDecline() = runBlocking {
        service.setupNotificationChannels()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val before = Shadows.shadowOf(nm).allNotifications.size
        var replies = 0
        service.handleUwbEnvelopes(
            listOf(UwbEnvelope(sessionId = "req-2", sender = 88uL, senderPlatform = "android", kind = UwbEnvelopeKind.REQUEST)),
            uwbAvailable = true,
            sendUwb = { _, _, _ -> replies++; true },
        )
        delay(300)
        assertEquals(0, replies)
        assertTrue(Shadows.shadowOf(nm).allNotifications.size > before)
        assertNotNull(UwbInbox.consumePendingRequest(88L))
        Unit
    }

    @Test
    fun outOfOrderArrivalKeepsNewer() = runBlocking {
        val uid = repo.upsertUser(user("Alice"))
        val now = Clock.System.now()
        val newer = fix(uid, at = now - 1.minutes, coord = Coord(52.0, -0.12))
        val older = fix(uid, at = now - 2.minutes, coord = Coord(53.0, -0.12))
        // Across batches: the delayed older fix must not be stored at all.
        service.processIncomingLocations(listOf(newer))
        service.processIncomingLocations(listOf(older))
        var stored = repo.locationHistory(uid).first()
        assertEquals(1, stored.size)
        assertEquals(52.0, repo.latestLocationByUser.first()[uid]?.coord?.lat)
        // Within one batch: [new, old] folds to the new fix only.
        val other = repo.upsertUser(user("Other"))
        service.processIncomingLocations(listOf(
            fix(other, at = now - 1.minutes, coord = Coord(52.0, -0.12)),
            fix(other, at = now - 2.minutes, coord = Coord(53.0, -0.12)),
        ))
        stored = repo.locationHistory(other).first()
        assertEquals(1, stored.size)
        assertEquals(52.0, stored[0].coord.lat)
        Unit
    }

    @Test
    fun duplicateDeliveryIsIdempotent() = runBlocking {
        val uid = repo.upsertUser(user("Alice"))
        val at = Clock.System.now() - 1.minutes
        service.processIncomingLocations(listOf(fix(uid, at = at), fix(uid, at = at)))
        service.processIncomingLocations(listOf(fix(uid, at = at)))
        assertEquals(1, repo.locationHistory(uid).first().size)
        Unit
    }

    @Test
    fun sequencingIsPerSenderIndependent() = runBlocking {
        val alice = repo.upsertUser(user("Alice"))
        val bob = repo.upsertUser(user("Bob"))
        val now = Clock.System.now()
        service.processIncomingLocations(listOf(fix(alice, at = now - 1.minutes)))
        // Bob's first fix is older than Alice's latest: her high-water must
        // not gate him.
        service.processIncomingLocations(listOf(fix(bob, at = now - 1.hours)))
        assertEquals(1, repo.locationHistory(bob).first().size)
        // Alice's stale fix is dropped; Bob's next fix is unaffected.
        service.processIncomingLocations(listOf(
            fix(alice, at = now - 2.minutes),
            fix(bob, at = now - 30.seconds),
        ))
        assertEquals(1, repo.locationHistory(alice).first().size)
        assertEquals(2, repo.locationHistory(bob).first().size)
        Unit
    }

    @Test
    fun clockRegressionIsDroppedUntilSenderPassesHighWater() = runBlocking {
        // Documented decision (IngestSequencing): no regression window. A
        // sender whose clock jumps backwards is dropped until its stamps
        // pass the stored high-water, then accepted again.
        val uid = repo.upsertUser(user("Alice"))
        val now = Clock.System.now()
        service.processIncomingLocations(listOf(fix(uid, at = now - 1.minutes, coord = Coord(52.0, -0.12))))
        service.processIncomingLocations(listOf(fix(uid, at = now - 1.hours, coord = Coord(53.0, -0.12))))
        assertEquals(1, repo.locationHistory(uid).first().size)
        service.processIncomingLocations(listOf(fix(uid, at = now, coord = Coord(54.0, -0.12))))
        val stored = repo.locationHistory(uid).first()
        assertEquals(2, stored.size)
        assertEquals(54.0, repo.latestLocationByUser.first()[uid]?.coord?.lat)
        Unit
    }

    @Test
    fun equalStampTiebreakIsDeterministicAtIngest() = runBlocking {
        // Same reportedAt + measuredAt, different payloads: the payload-hash
        // winner is stored regardless of arrival order within the batch.
        val at = Clock.System.now() - 1.minutes
        fun hash(lat: Double) = IngestSequencing.payloadHash(lat, -0.12, 0f, 5f, 90f, "LIVE")
        val winnerLat = if (hash(51.5) > hash(52.5)) 51.5 else 52.5
        val first = repo.upsertUser(user("First"))
        val second = repo.upsertUser(user("Second"))
        service.processIncomingLocations(listOf(
            fix(first, at = at, coord = Coord(51.5, -0.12)),
            fix(first, at = at, coord = Coord(52.5, -0.12)),
        ))
        service.processIncomingLocations(listOf(
            fix(second, at = at, coord = Coord(52.5, -0.12)),
            fix(second, at = at, coord = Coord(51.5, -0.12)),
        ))
        for (uid in listOf(first, second)) {
            val stored = repo.locationHistory(uid).first()
            assertEquals(1, stored.size)
            assertEquals(winnerLat, stored[0].coord.lat)
        }
        Unit
    }

    @Test
    fun serviceStartWithoutPermissionStops() {
        val controller = Robolectric.buildService(LocationTrackingService::class.java)
        val svc = controller.create().get()
        svc.repositoryOverride = repo
        val rc = svc.onStartCommand(null, 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, rc)
        controller.destroy()
    }
}
