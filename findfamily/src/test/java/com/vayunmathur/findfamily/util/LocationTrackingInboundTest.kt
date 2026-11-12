package com.vayunmathur.findfamily.util

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
import com.vayunmathur.findfamily.uwb.UwbCancelReason
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.findfamily.uwb.UwbEnvelopeKind
import com.vayunmathur.findfamily.uwb.UwbInbox
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
        // Future-dated fix from Alice: dropped by time clamp.
        val future = Instant.fromEpochSeconds(Clock.System.now().epochSeconds + 48 * 3600)
        service.processIncomingFixes(listOf(InboundFix(fix(alice, at = future), "expected-pair")))
        assertTrue(repo.locationHistory(alice).first().isEmpty())
        // Wrong pair id: dropped.
        service.processIncomingFixes(listOf(InboundFix(fix(alice), "wrong-pair")))
        assertTrue(repo.locationHistory(alice).first().isEmpty())
        // Correct pair id: stored.
        service.processIncomingFixes(listOf(InboundFix(fix(alice), "expected-pair")))
        assertEquals(1, repo.locationHistory(alice).first().size)
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
        val wpId = repo.upsertWaypoint(Waypoint(name = "HomeBase", range = 500.0, coord = Coord(51.5, -0.12)))
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
        val wpId = repo.upsertWaypoint(Waypoint(name = "HomeBase", range = 500.0, coord = Coord(51.5, -0.12)))
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
                recovery = com.vayunmathur.findfamily.uwb.PoweredOffGrant(epoch = 1, secretB64 = "AA==", recoveryPrivB64 = "AA==", sigB64 = "AA==")),
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
    fun serviceStartWithoutPermissionStops() {
        val controller = Robolectric.buildService(LocationTrackingService::class.java)
        val svc = controller.create().get()
        svc.repositoryOverride = repo
        val rc = svc.onStartCommand(null, 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, rc)
        controller.destroy()
    }
}
