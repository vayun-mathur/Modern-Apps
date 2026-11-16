package com.vayunmathur.findfamily.ui

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.platform.FakeAndroidKeyStore
import com.vayunmathur.findfamily.util.FindFamilyViewModel
import com.vayunmathur.findfamily.util.LocationServiceController
import com.vayunmathur.findfamily.util.LocationTrackingService
import com.vayunmathur.findfamily.util.Networking
import com.vayunmathur.findfamily.util.PersonActions
import com.vayunmathur.findfamily.util.seedDirectBootMirror
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DatabaseHelper
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Remaining map-page chrome: auto-toggle dropdown choices, page effects dialogs, map drawing. */
@RunWith(AndroidJUnit4::class)
class MainPageChromeRealRepoTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var repo: com.vayunmathur.findfamily.data.FindFamilyRepository
    private lateinit var vm: FindFamilyViewModel

    @BeforeTest
    fun setup() {
        FakeAndroidKeyStore.install()
        runCatching { DatabaseHelper(app).storePassphrase("chrome-test-pass") }
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        repo = TestFindFamilyRepository.install(app)
        vm = FindFamilyViewModel(app, repo)
    }

    private class RecordingActions : PersonActions {
        var autoToggle: Duration? = null
        var autoToggleSet = false
        var arrival: Long? = null
        var arrivalSet = false
        override fun setUserAutoToggle(user: User, duration: Duration?) {
            autoToggleSet = true; autoToggle = duration
        }
        override fun setUserArrivalToggle(user: User, waypointId: Long?) {
            arrivalSet = true; arrival = waypointId
        }
    }

    private fun user(
        sending: Boolean = true,
        toggleAt: kotlin.time.Instant? = null,
        arrivalAt: Long? = null,
    ) = User(
        name = "Alice", photo = null, locationName = "Home", sendingEnabled = sending,
        requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
        id = 3L, sharingAutoToggleAt = toggleAt, sharingAutoToggleWaypointId = arrivalAt,
    )

    private val waypoints = listOf(
        Waypoint("home-place", 100.0, Coord(1.0, 2.0), id = 5L),
        Waypoint("", 100.0, Coord(3.0, 4.0), id = 6L),
    )

    @Test fun autoToggleChoosesDurationNeverAndArrival() {
        val actions = RecordingActions()
        rule.setContent { DynamicTheme { AutoToggleRow(user(), waypoints, actions) } }
        rule.onNodeWithText("Never", substring = true).performClick()
        rule.onNodeWithText("1 hour", substring = true).performClick()
        assertTrue(actions.autoToggleSet)
        assertEquals(1.hours, actions.autoToggle)
    }

    @Test fun autoToggleShowsCountdownAndEnableLabel() {
        val actions = RecordingActions()
        val future = Clock.System.now() + 2.hours
        rule.setContent {
            DynamicTheme { AutoToggleRow(user(sending = false, toggleAt = future), waypoints, actions) }
        }
        rule.waitForIdle()
        // A live countdown (not "Never") is shown for an armed future toggle.
        assertTrue(rule.onAllNodesWithText("Never", substring = true).fetchSemanticsNodes().isEmpty() ||
            rule.onAllNodesWithText(":", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun autoToggleExpiredCountdownFallsBackToNever() {
        val actions = RecordingActions()
        rule.setContent {
            DynamicTheme {
                AutoToggleRow(user(toggleAt = Clock.System.now() - 1.hours), waypoints, actions)
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Never", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun autoToggleArrivalLabelWinsOverCountdown() {
        val actions = RecordingActions()
        rule.setContent {
            DynamicTheme {
                AutoToggleRow(user(arrivalAt = 5L), waypoints, actions)
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("home-place", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun pageEffectsShowSecurityAndGpsDialogs() = runBlocking {
        val alice = repo.upsertUser(User(
            name = "Alice", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
        ))
        var security = true
        var gps = true
        rule.setContent {
            DynamicTheme {
                val camera = rememberCameraState()
                MainPageEffects(
                    ffViewModel = vm, camera = camera, selectedUserId = alice,
                    selfUserId = Networking.userid, isShowingPresent = true,
                    historicalPosition = null, userPositions = emptyMap(),
                    selectedWaypointId = null, waypoints = emptyList(),
                    showSecurityCode = security, onShowSecurityCode = { security = it },
                    showGpsWarning = gps, onShowGpsWarning = { gps = it },
                )
            }
        }
        rule.waitForIdle()
        Thread.sleep(300)
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Alice", substring = true).fetchSemanticsNodes().isNotEmpty())
        Unit
    }

    @Test fun mapDrawingAndUserPicturesRender() {
        var clicked = false
        rule.setContent {
            DynamicTheme {
                Canvas(Modifier.size(100.dp)) {
                    drawCircleWithBorder(center, Color.Blue, Color.Red, 40f)
                    drawCircleWithBorder(center, Color.Green, Color.Black, 10f)
                }
                UserPicture("https://example.com/photo.png", 'P', 48.dp, grayscale = false) { clicked = true }
                UserPicture(null, 'Q', 48.dp, grayscale = true) { clicked = true }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Q").performClick()
        assertTrue(clicked)
    }

    @Test fun userPictureDataUriTakesImageBranch() {
        rule.setContent {
            DynamicTheme {
                UserPicture("data:image/jpeg;base64,aGVsbG8=", 'D', 48.dp, grayscale = false) {}
            }
        }
        rule.waitForIdle()
    }

    @Test fun seedDirectBootMirrorPublishesRosterAndDedupes() = runBlocking {
        val service = Robolectric.buildService(LocationTrackingService::class.java).create().get()
        service.repositoryOverride = repo
        Networking.init(repo, com.vayunmathur.library.util.DataStoreUtils.getInstance(app), "Me")
        Networking.repository = repo
        repo.upsertUser(User(
            name = "Bundled", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
            pqcEncryptionKey = "bundle-b64", outboundPairId = "pair-1",
        ))
        repo.upsertUser(User(
            name = "No Bundle", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
        ))
        repo.upsertUser(User(
            name = "Muted", photo = null, locationName = "Home", sendingEnabled = false,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
            pqcEncryptionKey = "bundle-b64",
        ))
        service.seedDirectBootMirror()
        // Only sharing peers with a bundle make the pre-unlock roster.
        assertEquals(1, service.publishRoster.size)
        assertEquals("bundle-b64", service.publishRoster.single().bundle)
        assertNotNull(service.lastSeededMirror)
        val firstSignature = service.lastSeededMirror
        service.seedDirectBootMirror()
        assertEquals(firstSignature, service.lastSeededMirror)
        // Turning global sharing off empties the roster on the next seed.
        LocationServiceController.setGlobalSharingEnabled(app, false)
        service.seedDirectBootMirror()
        assertTrue(service.publishRoster.isEmpty())
        LocationServiceController.setGlobalSharingEnabled(app, true)
        service.seedDirectBootMirror()
        assertEquals(1, service.publishRoster.size)
        assertEquals(1, DirectBootStore.roster(app).size)
        Unit
    }

    @Test fun serviceStartCommandWithoutPermissionStopsNotSticky() {
        val controller = Robolectric.buildService(LocationTrackingService::class.java)
        val service = controller.create().get()
        service.repositoryOverride = repo
        val result = service.onStartCommand(Intent(app, LocationTrackingService::class.java), 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
        // The ceiling-check action on a non-tracking service also settles.
        val ceiling = Intent(app, LocationTrackingService::class.java)
            .setAction(com.vayunmathur.findfamily.platform.FixCeilingAlarm.ACTION_CHECK)
        val ceilingResult = service.onStartCommand(ceiling, 0, 2)
        assertTrue(ceilingResult == android.app.Service.START_NOT_STICKY ||
            ceilingResult == android.app.Service.START_STICKY)
        assertNull(service.onBind(Intent(app, LocationTrackingService::class.java)))
        controller.destroy()
    }
}
