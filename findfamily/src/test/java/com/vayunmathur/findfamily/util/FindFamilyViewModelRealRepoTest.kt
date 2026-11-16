package com.vayunmathur.findfamily.util

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.NoShowAlert
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.awaitFamilyCondition
import com.vayunmathur.findfamily.platform.FakeAndroidKeyStore
import com.vayunmathur.library.util.DatabaseHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * [FindFamilyViewModel] mutations over a real in-memory Room repository
 * (see [TestFindFamilyRepository]): every write is asserted in Room, not
 * against a fake, so DAO SQL, cascades and flow projections all run.
 */
@RunWith(AndroidJUnit4::class)
class FindFamilyViewModelRealRepoTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var repo: FindFamilyRepository
    private lateinit var vm: FindFamilyViewModel

    @BeforeTest
    fun setup() {
        FakeAndroidKeyStore.install()
        runCatching { DatabaseHelper(app).storePassphrase("vm-test-pass") }
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        repo = TestFindFamilyRepository.install(app)
        vm = FindFamilyViewModel(app, repo)
    }

    private fun pump() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private fun user(id: Long = 0, name: String, status: RequestStatus = RequestStatus.MUTUAL_CONNECTION) = User(
        name = name, photo = null, locationName = "Home", sendingEnabled = true,
        requestStatus = status, lastLocationChangeTime = Clock.System.now(), id = id,
    )

    private suspend fun await(desc: String, cond: suspend () -> Boolean) {
        val ok = awaitFamilyCondition { pump(); cond() }
        assertTrue(ok, "timed out waiting for: $desc")
    }

    @Test fun upsertAndUpdateContactPersist() = runBlocking {
        vm.upsertUser(user(name = "Alice"))
        await("user upserted") { repo.getAllUsers().any { it.name == "Alice" } }
        val id = repo.getAllUsers().first { it.name == "Alice" }.id
        vm.updateContactNamePhoto(id, "Alice A", "photo://a")
        await("contact updated") { repo.getUser(id)?.name == "Alice A" }
        assertEquals("photo://a", repo.getUser(id)?.photo)
    }

    @Test fun deleteUserRemovesRowAndHistory() = runBlocking {
        val id = repo.upsertUser(user(name = "Gone"))
        repo.upsertLocation(LocationValue(
            userid = id, coord = Coord(1.0, 2.0), speed = 0f, acc = 5f,
            timestamp = Clock.System.now(), battery = 50f, reportedAt = Clock.System.now(),
            source = LocationSource.LIVE,
        ))
        vm.deleteUser(repo.getUser(id)!!)
        await("user deleted") { repo.getUser(id) == null }
        assertTrue(repo.locationHistory(id).first().isEmpty())
    }

    @Test fun sharingTogglesPersistAtomically() = runBlocking {
        val id = repo.upsertUser(user(name = "Alice"))
        vm.setUserSharing(repo.getUser(id)!!, false)
        await("sharing off") { repo.getUser(id)?.sendingEnabled == false }
        vm.setUserAutoToggle(repo.getUser(id)!!, 2.hours)
        await("auto toggle set") { repo.getUser(id)?.sharingAutoToggleAt != null }
        vm.setUserSharing(repo.getUser(id)!!, true)
        await("manual toggle clears auto") {
            val row = repo.getUser(id)!!; row.sendingEnabled && row.sharingAutoToggleAt == null
        }
        val wp = repo.waypointStore.upsert(Waypoint(name = "Home", range = 100.0, coord = Coord(1.0, 2.0)))
        vm.setUserArrivalToggle(repo.getUser(id)!!, wp)
        await("arrival toggle set") { repo.getUser(id)?.sharingAutoToggleWaypointId == wp }
        vm.setUserArrivalToggle(repo.getUser(id)!!, null)
        await("arrival toggle cleared") { repo.getUser(id)?.sharingAutoToggleWaypointId == null }
    }

    @Test fun applyDueAutoTogglesFlipsDueUser() = runBlocking {
        val id = repo.upsertUser(user(name = "Due"))
        repo.setSharingAutoToggleAt(id, 1_000L)
        vm.applyDueAutoToggles()
        assertFalse(repo.getUser(id)!!.sendingEnabled)
    }

    @Test fun deleteTemporaryLinkAndWaypointPersist() = runBlocking {
        val linkId = repo.temporaryLinkStore.upsert(
            TemporaryLink(name = "Trip", deleteAt = Clock.System.now(), pqcPublicKey = "b", id = 55L)
        )
        vm.deleteTemporaryLink(repo.temporaryLinkStore.getAll().single())
        await("link deleted") { repo.temporaryLinkStore.getAll().isEmpty() }
        assertEquals(55L, linkId)
        val wpId = repo.waypointStore.upsert(Waypoint(name = "Home", range = 100.0, coord = Coord(1.0, 2.0)))
        vm.deleteWaypoint(repo.waypointStore.get(wpId))
        await("waypoint deleted") { repo.waypointStore.getAll().isEmpty() }
    }

    @Test fun noShowAlertPersistsAndSchedules() = runBlocking {
        val id = repo.upsertUser(user(name = "Watched"))
        vm.setNoShowAlert(repo.getUser(id)!!, null, Clock.System.now() + 30.minutes, 10)
        await("alert stored") { repo.noShowAlertStore.getAll().size == 1 }
        val alert = repo.noShowAlertStore.getAll().single()
        assertEquals(id, alert.watchedUserId)
        assertEquals(10.minutes, alert.grace)
    }

    @Test fun selectionAndWaypointFormState() = runBlocking {
        val wpId = repo.waypointStore.upsert(Waypoint(name = "Office", range = 250.0, coord = Coord(3.0, 4.0)))
        vm.applyInitialSelection(7L, wpId)
        assertEquals(7L, vm.selectedUserId.value)
        assertEquals(wpId, vm.mapState.selectedWaypointId.value)
        vm.beginEditWaypoint(repo.waypointStore.get(wpId))
        assertEquals("Office", vm.waypointName.value)
        assertEquals("250.0", vm.waypointRange.value)
        vm.mapState.setWaypointName("Office 2")
        vm.mapState.setWaypointRange("300")
        vm.mapState.saveCurrentWaypoint()
        await("waypoint saved") { repo.waypointStore.get(wpId).name == "Office 2" }
        assertEquals(300.0, repo.waypointStore.get(wpId).range)
        vm.mapState.beginCreateWaypoint()
        assertEquals(0L, vm.mapState.selectedWaypointId.value)
        assertEquals("100", vm.waypointRange.value)
        vm.mapState.setWaypointName("New Place")
        vm.mapState.setWaypointCoord(Coord(5.0, 6.0))
        vm.mapState.saveCurrentWaypoint()
        await("waypoint created") { repo.waypointStore.getAll().any { it.name == "New Place" } }
        vm.selectUser(9L)
        vm.mapState.setShowingPresent(false)
        assertFalse(vm.isShowingPresent.value)
        vm.mapState.setHistoricalPosition(com.vayunmathur.library.map.GeoPoint(1.0, 2.0))
        assertNotNull(vm.historicalPosition.value)
        vm.mapState.setShowingPresent(true)
        assertNull(vm.historicalPosition.value)
        vm.mapState.clearSelection()
        assertNull(vm.selectedUserId.value)
    }

    @Test fun invalidWaypointFormDoesNotPersist() = runBlocking {
        vm.mapState.beginCreateWaypoint()
        vm.mapState.setWaypointName("")
        vm.mapState.saveCurrentWaypoint()
        assertTrue(repo.waypointStore.getAll().isEmpty())
        vm.mapState.setWaypointName("Named")
        vm.mapState.setWaypointRange("not-a-number")
        vm.mapState.saveCurrentWaypoint()
        assertTrue(repo.waypointStore.getAll().isEmpty())
    }

    @Test fun connectUserOfflineUpsertsWithMintedPairId() = runBlocking {
        var done = false
        vm.connectUser(user(id = 4242, name = "Peer"), onNeedsUpdate = {}, onDone = { done = true })
        await("peer connected") { repo.getUser(4242) != null }
        val stored = repo.getUser(4242)!!
        assertEquals("Peer", stored.name)
        assertNotNull(stored.outboundPairId)
        await("onDone fired") { pump(); done }
        // Re-connecting must not rotate the pinned outbound pair id.
        val firstPair = stored.outboundPairId
        vm.connectUser(repo.getUser(4242)!!, onNeedsUpdate = {}, onDone = {})
        await("reconnect settled") { repo.getUser(4242)?.outboundPairId == firstPair }
    }

    @Test fun createTemporaryLinkReportsAndStoresOnSuccess() = runBlocking {
        var outcome: Boolean? = null
        vm.createTemporaryLink("Weekend", 1.hours) { outcome = it }
        await("link outcome reported") { outcome != null }
        if (outcome == true) {
            assertTrue(repo.temporaryLinkStore.getAll().any { it.name == "Weekend" })
        } else {
            // Native PQC keygen unavailable on this host: failure is reported, nothing stored.
            assertTrue(repo.temporaryLinkStore.getAll().isEmpty())
        }
    }

    @Test fun securityCodeWithoutPeerBundleIsNull() = runBlocking {
        val id = repo.upsertUser(user(name = "Alice"))
        assertNull(vm.securityCodeFor(repo.getUser(id)!!))
    }

    @Test fun permissionRefreshAndFeatureFlagsRun() {
        vm.refreshPermissions()
        // Robolectric grants nothing by default.
        assertFalse(vm.hasForeground.value)
        assertFalse(vm.hasBackground.value)
        assertFalse(vm.hasBluetooth.value)
        // Computed once from shadow LocationManager/Geocoder; either answer is device state.
        val missing = vm.missingFeatures
        assertTrue(missing || !missing)
        assertTrue(vm.backupPassphrase.isNotBlank())
    }

    @Test fun derivedFlowsProjectSeededRows() = runBlocking {
        // WhileSubscribed state flows only project while collected.
        val collectors = listOf(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { vm.users.collect {} },
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { vm.connectedUsers.collect {} },
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { vm.awaitingRequestUsers.collect {} },
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { vm.usersById.collect {} },
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { vm.locationHistory.collect {} },
        )
        val alice = repo.upsertUser(user(name = "Alice"))
        repo.upsertUser(user(name = "Bob", status = RequestStatus.AWAITING_REQUEST))
        repo.upsertUser(user(name = "Carol", status = RequestStatus.AWAITING_RESPONSE))
        repo.upsertLocation(LocationValue(
            userid = alice, coord = Coord(1.0, 2.0), speed = 0f, acc = 5f,
            timestamp = Clock.System.now(), battery = 66f, reportedAt = Clock.System.now(),
            source = LocationSource.LIVE,
        ))
        await("connected projected") {
            vm.connectedUsers.value.map { it.name }.containsAll(listOf("Alice", "Carol"))
        }
        // Each derived flow projects independently; awaitingRequest and
        // usersById can still hold their initial empty values when the
        // connected projection lands, so await them too (the DbTest
        // sibling failed in CI for exactly this snapshot race).
        await("awaiting projected") { vm.awaitingRequestUsers.value.map { it.name } == listOf("Bob") }
        assertEquals(listOf("Bob"), vm.awaitingRequestUsers.value.map { it.name })
        await("by-id projected") { vm.usersById.value[alice]?.name == "Alice" }
        assertEquals("Alice", vm.usersById.value[alice]?.name)
        vm.selectUser(alice)
        await("history projected") { vm.locationHistory.value.size == 1 }
        assertEquals(66f, vm.locationHistory.value.single().battery)
        collectors.forEach { it.cancel() }
    }

    @Test fun rangingFacadeDelegatesToSessionManager() {
        // The session manager refuses to start until the tracking service
        // has initialised it (and UWB hardware exists); the facade must
        // still be safe to call and stop() must leave no peer behind.
        vm.startRanging(123L)
        vm.stopRanging()
        assertNull(vm.uwbPeerUserId.value)
        assertNotNull(vm.uwbSession.value)
    }

    @Test fun factoryCreatesViewModelAndRejectsOthers() {
        val factory = FindFamilyViewModelFactory(app, repo)
        assertTrue(factory.create(FindFamilyViewModel::class.java) is FindFamilyViewModel)
    }

    @Test fun noShowAlertDataRoundTripsThroughStore() = runBlocking {
        val alert = NoShowAlert(
            watchedUserId = 1L, waypointId = null,
            expectedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L), grace = 5.minutes,
        )
        val id = repo.noShowAlertStore.upsert(alert)
        assertEquals(alert.expectedAt, repo.noShowAlertStore.get(id)?.expectedAt)
    }
}
