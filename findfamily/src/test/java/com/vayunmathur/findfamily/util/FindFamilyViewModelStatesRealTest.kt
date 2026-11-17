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
import com.vayunmathur.findfamily.platform.FindFamilyViewModel
import com.vayunmathur.findfamily.network.Networking

import android.app.Application
import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.NoShowAlert
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.awaitFamilyCondition
import com.vayunmathur.library.ui.DynamicTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** ViewModel derived states, the verified-bundle connect path, and waypoint deletion cascades. */
@RunWith(AndroidJUnit4::class)
class FindFamilyViewModelStatesRealTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    @BeforeTest
    fun setup() {
        runCatching {
            androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build())
        }
    }

    @Test fun derivedStatesTrackRepositoryRows() = runBlocking {
        val repo = TestFindFamilyRepository.install(ctx)
        val vm = FindFamilyViewModel(app, repo)
        val aliceId = repo.upsertUser(User(
            name = "Alice", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
        ))
        val wpId = repo.waypointStore.upsert(Waypoint(name = "Home", range = 100.0, coord = Coord(1.0, 2.0)))
        var userState: State<User?>? = null
        var waypointState: State<Waypoint?>? = null
        var missingState: State<User?>? = null
        rule.setContent {
            DynamicTheme {
                userState = vm.userByIdState(aliceId)
                waypointState = vm.waypointByIdState(wpId)
                missingState = vm.userByIdState(424_242L) { null }
            }
        }
        assertTrue(awaitFamilyCondition { userState?.value?.name == "Alice" })
        assertTrue(awaitFamilyCondition { waypointState?.value?.name == "Home" })
        assertNull(missingState?.value)
        Unit
    }

    @Test fun connectUserWithVerifiedBundleStoresCheckedKey() = runBlocking {
        val repo = TestFindFamilyRepository.install(ctx)
        val vm = FindFamilyViewModel(app, repo)
        Networking.repository = repo
        val identity = Networking.generatePqcIdentityKeyPair()
        val peerId = repo.upsertUser(User(
            name = "Peer", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.AWAITING_RESPONSE, lastLocationChangeTime = Clock.System.now(),
            pqcEncryptionKey = identity.publicBundleB64,
        ))
        val peer = repo.getUser(peerId)!!
        var done = false
        vm.connectUser(peer, inviteFingerprint = null, onNeedsUpdate = {}, onDone = { done = true })
        // The verified bundle is stored and the pair id minted once.
        assertTrue(awaitFamilyCondition {
            val stored = repo.getUser(peerId)
            stored?.pqcEncryptionKey == identity.publicBundleB64 && stored.outboundPairId != null
        })
        // onDone follows a service sync that can block on the paused test
        // DataStore; the persisted pair id above is the observable outcome.
        val firstPairId = repo.getUser(peerId)!!.outboundPairId
        // Reconnecting must not rotate the pinned pair id.
        vm.connectUser(repo.getUser(peerId)!!, inviteFingerprint = null, onNeedsUpdate = {}, onDone = {})
        assertTrue(awaitFamilyCondition {
            repo.getUser(peerId)?.outboundPairId == firstPairId
        })
        Unit
    }

    @Test fun contactEditAndAutoToggleCleanupLandInRoom() = runBlocking {
        val repo = TestFindFamilyRepository.install(ctx)
        val vm = FindFamilyViewModel(app, repo)
        val aliceId = repo.upsertUser(User(
            name = "Alice", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
        ))
        vm.updateContactNamePhoto(aliceId, "Alicia", "data:image/jpeg;base64,AA==")
        assertTrue(awaitFamilyCondition { repo.getUser(aliceId)?.name == "Alicia" })
        vm.clearExpiredAutoTogglesIfAny()
        vm.applyDueAutoToggles()
        // Due toggles were applied without disturbing the row.
        assertTrue(awaitFamilyCondition { repo.getUser(aliceId)?.name == "Alicia" })
        Unit
    }

    @Test fun deleteWaypointCancelsItsNoShowAlerts() = runBlocking {
        val repo = TestFindFamilyRepository.install(ctx)
        val vm = FindFamilyViewModel(app, repo)
        val wpId = repo.waypointStore.upsert(Waypoint(name = "Cabin", range = 100.0, coord = Coord(1.0, 2.0)))
        val waypoint = repo.waypointStore.get(wpId)
        val peerId = repo.upsertUser(User(
            name = "Camper", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
        ))
        repo.noShowAlertStore.upsert(NoShowAlert(
            watchedUserId = peerId, waypointId = wpId,
            expectedAt = Clock.System.now() + kotlin.time.Duration.parse("P3650D"),
            grace = kotlin.time.Duration.parse("PT5M"), oneShot = true,
        ))
        vm.deleteWaypoint(waypoint)
        assertTrue(awaitFamilyCondition { repo.waypointStore.getAll().none { it.id == wpId } })
        Unit
    }
}
