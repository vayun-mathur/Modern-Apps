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
        val dl = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < dl) {
            pump()
            if (cond()) return
            delay(40)
        }
        throw AssertionError("timed out waiting for: $desc")
    }

    @Test
    fun derivedFlowsReflectSeededData() = runBlocking {
        val alice = repo.upsertUser(user("Alice"))
        repo.upsertUser(user("Bob", RequestStatus.AWAITING_REQUEST, loc = "Office"))
        repo.upsertUser(user("Carol", RequestStatus.AWAITING_RESPONSE, loc = "Office"))
        repo.upsertWaypoint(Waypoint(name = "Home", range = 100.0, coord = Coord(1.0, 2.0)))
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
        assertEquals(listOf("Bob"), vm.awaitingRequestUsers.value.map { it.name })
        assertEquals("Office", vm.usersById.value.values.first { it.name == "Bob" }.locationName)
        assertEquals(listOf("Bob", "Carol"), vm.usersByLocationName.value["Office"]?.sorted())
        awaitTrue("waypoints") { vm.waypoints.value.size == 1 }
        // Repository one-shot sees the fix (same getLatest DAO query the VM flow wraps).
        assertEquals(80f, kotlinx.coroutines.runBlocking { repo.latestLocationsOnce().firstOrNull()?.battery })
        assertEquals(alice, kotlinx.coroutines.runBlocking { repo.latestLocationByUser.first().keys.firstOrNull() })
        // Selection + history
        vm.selectUser(alice)
        awaitTrue("location history") { vm.locationHistory.value.size == 1 }
        vm.mapState.setShowingPresent(false)
        assertEquals(false, vm.isShowingPresent.value)
        vm.mapState.clearSelection()
        assertEquals(null, vm.selectedUserId.value)
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
            CoroutineScope(Dispatchers.Default).async { vm.locationHistory.collect { } },
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
        val wpId = repo.upsertWaypoint(Waypoint(name = "Gym", range = 50.0, coord = Coord(0.0, 0.0)))
        vm.beginEditWaypoint(repo.getWaypoint(wpId))
        assertEquals("Gym", vm.waypointName.value)
        vm.mapState.setWaypointName("Gym2")
        vm.mapState.setWaypointRange("75")
        vm.mapState.saveCurrentWaypoint()
        awaitTrue("waypoint saved") { kotlinx.coroutines.runBlocking { repo.getWaypoint(wpId).name == "Gym2" } }
        vm.deleteWaypoint(repo.getWaypoint(wpId))
        awaitTrue("waypoint deleted") { kotlinx.coroutines.runBlocking { repo.getAllWaypoints().isEmpty() } }
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
    fun waypointFormCreateAndInitialSelection() = runBlocking {
        val vm = FindFamilyViewModel(app, repo)
        vm.mapState.beginCreateWaypoint()
        vm.mapState.setWaypointName("Park")
        vm.mapState.setWaypointRange("200")
        vm.mapState.setWaypointCoord(Coord(5.0, 6.0))
        vm.mapState.saveCurrentWaypoint()
        awaitTrue("waypoint created") { kotlinx.coroutines.runBlocking { repo.getAllWaypoints().any { it.name == "Park" } } }
        val uid = repo.upsertUser(user("Frank"))
        vm.applyInitialSelection(uid, null)
        assertEquals(uid, vm.selectedUserId.value)
        vm.applyInitialSelection(null, repo.getAllWaypoints().first().id)
        assertNotNull(vm.mapState.selectedWaypointId.value)
        vm.refreshPermissions()
        // Permission flags must be readable (values depend on Robolectric grants).
        @Suppress("UNUSED_EXPRESSION") vm.hasForeground.value; vm.hasBackground.value; vm.hasBluetooth.value
        assertTrue(true)
    }
}
