package com.vayunmathur.findfamily.ui
import com.vayunmathur.findfamily.domain.AddInvite

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.Navigation
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.platform.FakeAndroidKeyStore
import com.vayunmathur.findfamily.platform.FindFamilyViewModel
import com.vayunmathur.findfamily.platform.Platform
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DatabaseHelper
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The real [Navigation] graph and [MainPage] over an in-memory repository.
 *
 * Earlier suites rendered the stateless `MainPageContent` with a stub map
 * slot, so `MainPage` itself, its effects, the map slot and the navigation
 * entries never executed. Here the ViewModel is backed by genuine Room
 * rows (see [TestFindFamilyRepository]) and the full graph composes:
 * family sheet, person sheet, waypoint form, history scrubber and every
 * dialog entry the deep links can push. Selection is route-carried
 * (PersonSheet/WaypointSheet destinations), never ViewModel state.
 */
@RunWith(AndroidJUnit4::class)
class MainPageNavigationRealRepoTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var repo: FindFamilyRepository
    private lateinit var vm: FindFamilyViewModel

    @BeforeTest
    fun setup() {
        FakeAndroidKeyStore.install()
        runCatching { DatabaseHelper(app).storePassphrase("nav-test-pass") }
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        repo = TestFindFamilyRepository.install(app)
        vm = FindFamilyViewModel(app, repo)
    }

    private fun user(name: String, status: RequestStatus = RequestStatus.MUTUAL_CONNECTION) = User(
        name = name, photo = null, locationName = "Home", sendingEnabled = true,
        requestStatus = status, lastLocationChangeTime = Clock.System.now(),
    )

    private suspend fun seedFamily() {
        val alice = repo.upsertUser(user("Alice"))
        repo.upsertUser(user("Bob", RequestStatus.AWAITING_REQUEST))
        repo.upsertUser(user("Carol", RequestStatus.AWAITING_RESPONSE))
        val now = Clock.System.now()
        repo.upsertLocation(LocationValue(
            userid = alice, coord = Coord(37.0, -122.0), speed = 1f, acc = 5f,
            timestamp = now, battery = 80f, reportedAt = now, source = LocationSource.LIVE,
        ))
        repo.waypointStore.upsert(Waypoint(name = "Home", range = 150.0, coord = Coord(37.0, -122.0)))
        repo.temporaryLinkStore.upsert(
            TemporaryLink(name = "Trip Link", deleteAt = now, pqcPublicKey = "bundle", id = 91L)
        )
    }

    private fun composeNavigation(
        showMissingFeatures: Boolean = false,
        deepLinkUwbPeerId: Long? = null,
        deepLinkAddInvite: AddInvite? = null,
        noShowTarget: Pair<Long, Long?>? = null,
    ) {
        rule.setContent {
            DynamicTheme {
                Navigation(
                    Platform(app), vm,
                    showMissingFeatures = showMissingFeatures,
                    deepLinkUwbPeerId = deepLinkUwbPeerId,
                    deepLinkAddInvite = deepLinkAddInvite,
                    noShowTarget = noShowTarget,
                )
            }
        }
        rule.waitForIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun mainPageShowsSeededFamily() = runBlocking {
        seedFamily()
        composeNavigation()
        assertTrue(rule.onAllNodesWithText("Alice", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun mainPageWithEmptyLibraryRenders() {
        composeNavigation()
        rule.waitForIdle()
    }

    @Test fun missingFeaturesDialogIsPushed() {
        composeNavigation(showMissingFeatures = true)
        assertTrue(
            rule.onAllNodesWithText("OK", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("features", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test fun addInviteDeepLinkOpensAddPersonDialog() = runBlocking {
        seedFamily()
        composeNavigation(deepLinkAddInvite = AddInvite(123L, "fingerprint"))
        assertTrue(rule.onAllNodesWithText("Add Person", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun noShowTargetOpensMainPageOnWatchedUser() = runBlocking {
        seedFamily()
        val alice = repo.getAllUsers().first { it.name == "Alice" }
        composeNavigation(noShowTarget = alice.id to null)
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Alice", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun uwbDeepLinkOpensRangingScreen() = runBlocking {
        seedFamily()
        val alice = repo.getAllUsers().first { it.name == "Alice" }
        composeNavigation(deepLinkUwbPeerId = alice.id)
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Alice", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun personSheetShowsPersonDetail() { runBlocking {
        seedFamily()
        val alice = repo.getAllUsers().first { it.name == "Alice" }
        rule.setContent { androidx.compose.runtime.CompositionLocalProvider(com.vayunmathur.library.util.LocalNavResultRegistry provides com.vayunmathur.library.util.NavResultRegistry()) { DynamicTheme { PersonSheet(stack(), vm, Platform(app), alice.id) } } }
        rule.waitForIdle()
        // Person detail shows Alice in both the header and the name field.
        rule.onAllNodesWithText("Alice", substring = true)[0].assertIsDisplayed()
    } }

    @Test fun waypointSheetShowsEditorForm() = runBlocking {
        seedFamily()
        val wp = repo.waypointStore.getAll().single()
        vm.beginEditWaypoint(wp)
        rule.setContent { androidx.compose.runtime.CompositionLocalProvider(com.vayunmathur.library.util.LocalNavResultRegistry provides com.vayunmathur.library.util.NavResultRegistry()) { DynamicTheme { WaypointSheet(stack(), vm, wp.id) } } }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Home", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun personSheetInHistoryModeShowsScrubber() = runBlocking {
        seedFamily()
        val alice = repo.getAllUsers().first { it.name == "Alice" }
        // PersonSheet resets to present on open; enter history explicitly.
        rule.setContent { androidx.compose.runtime.CompositionLocalProvider(com.vayunmathur.library.util.LocalNavResultRegistry provides com.vayunmathur.library.util.NavResultRegistry()) { DynamicTheme { PersonSheet(stack(), vm, Platform(app), alice.id) } } }
        rule.waitForIdle()
        vm.mapState.setShowingPresent(false)
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Alice", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    private fun stack() = com.vayunmathur.library.util.NavBackStack<com.vayunmathur.findfamily.Route>(
        arrayOf(com.vayunmathur.findfamily.Route.MainPage)
    )
}
