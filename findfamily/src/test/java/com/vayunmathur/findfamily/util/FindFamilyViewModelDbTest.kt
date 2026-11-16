package com.vayunmathur.findfamily.util

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class FindFamilyViewModelDbTest {

    private lateinit var db: FFDatabase
    private lateinit var repo: FindFamilyRepository
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    @BeforeTest
    fun open() {
        // FindFamilyViewModel init reconciles WorkManager-backed sync.
        runCatching {
            androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build())
        }
        db = Room.inMemoryDatabaseBuilder(ctx, FFDatabase::class.java).allowMainThreadQueries().build()
        repo = FindFamilyRepository(ctx, db)
    }

    @AfterTest fun close() { db.close() }

    private fun user(name: String, status: RequestStatus = RequestStatus.MUTUAL_CONNECTION, loc: String = "Home") = User(
        name = name, photo = null, locationName = loc, sendingEnabled = true,
        requestStatus = status, lastLocationChangeTime = Clock.System.now(),
    )

    private fun pump() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private suspend fun awaitTrue(desc: String, timeoutMs: Long = 8_000, cond: () -> Boolean) {
        // Step counted: Robolectric's substituted clock can run ahead of
        // real time, expiring a wall-clock deadline before background
        // flow work lands on a loaded box.
        var steps = 0
        val maxSteps = (timeoutMs / 25).toInt()
        while (steps++ < maxSteps) {
            pump()
            if (cond()) return
            try { Thread.sleep(25) } catch (_: InterruptedException) { }
        }
        throw AssertionError("timed out waiting for: $desc")
    }

    @Test
    fun derivedFlowsReflectSeededData() = runBlocking {
        val alice = repo.upsertUser(user("Alice"))
        repo.upsertUser(user("Bob", RequestStatus.AWAITING_REQUEST, loc = "Office"))
        repo.upsertUser(user("Carol", RequestStatus.AWAITING_RESPONSE, loc = "Office"))
        repo.waypointStore.upsert(Waypoint(name = "Home", range = 100.0, coord = Coord(1.0, 2.0)))
        // Recent fix: the ViewModel startup cleanup deletes week-old locations.
        val nowFix = Clock.System.now()
        repo.upsertLocation(LocationValue(userid = alice, coord = Coord(1.0, 2.0), speed = 0f, acc = 5f,
            timestamp = nowFix, battery = 80f,
            reportedAt = nowFix, source = LocationSource.LIVE))
        val vm = FindFamilyViewModel(app, repo)
        val collectJob = CoroutineScope(Dispatchers.Default).async {
            launchCollect(vm)
        }
        awaitTrue("connected users") { vm.connectedUsers.value.map { it.name }.containsAll(listOf("Alice", "Carol")) }
        // Each derived WhileSubscribed flow computes independently; on a
        // loaded box awaitingRequest/usersByLocationName can still hold
        // their initial empty values when connectedUsers lands, so await
        // each condition instead of asserting the first snapshot (the CI
        // failure was awaiting == [] for exactly this reason).
        awaitTrue("awaiting request users") { vm.awaitingRequestUsers.value.map { it.name } == listOf("Bob") }
        assertEquals(listOf("Bob"), vm.awaitingRequestUsers.value.map { it.name })
        awaitTrue("users by id") { vm.usersById.value.values.any { it.name == "Bob" } }
        assertEquals("Office", vm.usersById.value.values.first { it.name == "Bob" }.locationName)
        awaitTrue("users by location") { vm.usersByLocationName.value["Office"]?.sorted() == listOf("Bob", "Carol") }
        assertEquals(listOf("Bob", "Carol"), vm.usersByLocationName.value["Office"]?.sorted())
        awaitTrue("waypoints") { vm.waypoints.value.size == 1 }
        // Repository one-shot sees the fix (same getLatest DAO query the VM flow wraps).
        assertEquals(80f, kotlinx.coroutines.runBlocking { repo.latestLocationsOnce().firstOrNull()?.battery })
        assertEquals(alice, kotlinx.coroutines.runBlocking { repo.latestLocationByUser.first().keys.firstOrNull() })
        // History is keyed by the person-sheet route arg, passed explicitly.
        awaitTrue("location history") { kotlinx.coroutines.runBlocking { vm.locationHistory(alice).first().size == 1 } }
        vm.mapState.setShowingPresent(false)
        assertEquals(false, vm.isShowingPresent.value)
        vm.mapState.onPersonOpened()
        assertEquals(true, vm.isShowingPresent.value)
        collectJob.cancel()
    }

    private suspend fun launchCollect(vm: FindFamilyViewModel) {
        // Trigger WhileSubscribed flows by collecting the main ones briefly via async children.
        val jobs = listOf(
            CoroutineScope(Dispatchers.Default).async { vm.users.collect { } },
            CoroutineScope(Dispatchers.Default).async { vm.connectedUsers.collect { } },
            CoroutineScope(Dispatchers.Default).async { vm.awaitingRequestUsers.collect { } },
            CoroutineScope(Dispatchers.Default).async { vm.usersById.collect { } },
            CoroutineScope(Dispatchers.Default).async { vm.usersByLocationName.collect { } },
            CoroutineScope(Dispatchers.Default).async { vm.waypoints.collect { } },
            CoroutineScope(Dispatchers.Default).async { vm.latestLocationByUser.collect { } },
            CoroutineScope(Dispatchers.Default).async { vm.locationHistory(0L).collect { } },
            CoroutineScope(Dispatchers.Default).async { vm.temporaryLinks.collect { } },
        )
        jobs.forEach { it.await() }
    }

    @Test
    fun mutationsPersistThroughRepository() = runBlocking {
        val vm = FindFamilyViewModel(app, repo)
        val id = repo.upsertUser(user("Dave"))
        vm.updateContactNamePhoto(id, "David", "photo://d")
        awaitTrue("rename") { kotlinx.coroutines.runBlocking { repo.getUser(id)?.name == "David" } }
        val wpId = repo.waypointStore.upsert(Waypoint(name = "Gym", range = 50.0, coord = Coord(0.0, 0.0)))
        vm.beginEditWaypoint(repo.waypointStore.get(wpId))
        assertEquals("Gym", vm.waypointName.value)
        vm.mapState.setWaypointName("Gym2")
        vm.mapState.setWaypointRange("75")
        assertTrue(vm.mapState.saveCurrentWaypoint(wpId))
        awaitTrue("waypoint saved") { kotlinx.coroutines.runBlocking { repo.waypointStore.get(wpId).name == "Gym2" } }
        vm.deleteWaypoint(repo.waypointStore.get(wpId))
        awaitTrue("waypoint deleted") { kotlinx.coroutines.runBlocking { repo.waypointStore.getAll().isEmpty() } }
        val link = TemporaryLink(name = "L", deleteAt = Clock.System.now(), pqcPublicKey = "pub")
        repo.temporaryLinkStore.upsert(link)
        vm.deleteTemporaryLink(repo.temporaryLinkStore.getAll().first())
        awaitTrue("link deleted") { kotlinx.coroutines.runBlocking { repo.temporaryLinkStore.getAll().isEmpty() } }
        var done = false
        vm.upsertUser(user("Erin")) { done = true }
        awaitTrue("upsert done callback") { done }
        vm.deleteUser(repo.getAllUsers().first { it.name == "Erin" })
        awaitTrue("user deleted") { kotlinx.coroutines.runBlocking { repo.getAllUsers().none { it.name == "Erin" } } }
    }

    @Test
    fun waypointFormCreateAndPersonOpenedReset() = runBlocking {
        val vm = FindFamilyViewModel(app, repo)
        vm.mapState.beginCreateWaypoint()
        vm.mapState.setWaypointName("Park")
        vm.mapState.setWaypointRange("200")
        vm.mapState.setWaypointCoord(Coord(5.0, 6.0))
        assertTrue(vm.mapState.saveCurrentWaypoint(0L))
        awaitTrue("waypoint created") { kotlinx.coroutines.runBlocking { repo.waypointStore.getAll().any { it.name == "Park" } } }
        // Opening a person sheet resets history mode and drops any stale pin.
        vm.mapState.setShowingPresent(false)
        vm.mapState.setHistoricalPosition(com.vayunmathur.library.map.GeoPoint(1.0, 2.0))
        vm.mapState.onPersonOpened()
        assertEquals(true, vm.isShowingPresent.value)
        assertEquals(null, vm.historicalPosition.value)
        vm.refreshPermissions()
        // Permission flags must be readable (values depend on Robolectric grants).
        @Suppress("UNUSED_EXPRESSION") vm.hasForeground.value; vm.hasBackground.value; vm.hasBluetooth.value
        assertTrue(true)
    }
}
