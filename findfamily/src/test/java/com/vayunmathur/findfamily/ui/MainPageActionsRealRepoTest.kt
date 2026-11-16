package com.vayunmathur.findfamily.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.awaitFamilyCondition
import com.vayunmathur.findfamily.platform.FakeAndroidKeyStore
import com.vayunmathur.findfamily.util.FindFamilyViewModel
import com.vayunmathur.findfamily.util.MainPageActions
import com.vayunmathur.findfamily.util.Platform
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Every action produced by [rememberMainPageActions], invoked for real:
 * navigation pushes land on the back stack, link copies land on the
 * clipboard, and deletes land in Room.
 */
@RunWith(AndroidJUnit4::class)
class MainPageActionsRealRepoTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app
    private lateinit var repo: FindFamilyRepository
    private lateinit var vm: FindFamilyViewModel

    @BeforeTest
    fun setup() {
        FakeAndroidKeyStore.install()
        runCatching { DatabaseHelper(app).storePassphrase("actions-test-pass") }
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        repo = TestFindFamilyRepository.install(app)
        vm = FindFamilyViewModel(app, repo)
    }

    @Test fun allMainPageActionsTakeEffect() = runBlocking {
        val aliceId = repo.upsertUser(User(
            name = "Alice", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
        ))
        val alice = repo.getUser(aliceId)!!
        val wpId = repo.waypointStore.upsert(Waypoint(name = "Home", range = 100.0, coord = Coord(1.0, 2.0)))
        val stack = NavBackStack<Route>(arrayOf(Route.MainPage))
        var gpsShown = false
        var captured: Pair<
            com.vayunmathur.findfamily.util.FamilyListActions,
            MainPageActions>? = null
        rule.setContent {
            DynamicTheme {
                captured = rememberMainPageActions(
                    ffViewModel = vm, backStack = stack, platform = Platform(ctx),
                    onShowGpsWarning = { gpsShown = it },
                )
            }
        }
        rule.waitForIdle()
        val (family, main) = captured!!
        // Family actions: accepting a request pushes the prefilled Add Person dialog.
        family.acceptRequest(777L)
        assertEquals(Route.AddPersonDialog(777L), stack.last())
        stack.pop()
        // Copying links writes both URL shapes to the clipboard.
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        family.copyLink(TemporaryLink(name = "seeded", deleteAt = Clock.System.now(),
            pqcPublicKey = "bundle", pqcSeed = "seed-abc", id = 123L))
        assertTrue(clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.endsWith("#s=seed-abc") == true)
        family.copyLink(TemporaryLink(name = "legacy", deleteAt = Clock.System.now(),
            pqcPublicKey = "bundle", pqcKey = "full-key", id = 124L))
        assertTrue(clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.endsWith("#pqc_key=full-key") == true)
        family.deleteTemporaryLink(TemporaryLink(name = "x", deleteAt = Clock.System.now(), pqcPublicKey = "b", id = 1L))
        // Selection is navigation: tapping a person/waypoint pushes its sheet
        // destination (the stack is the only selection holder).
        family.selectUser(aliceId)
        assertEquals(Route.PersonSheet(aliceId), stack.last())
        stack.pop()
        family.beginEditWaypoint(repo.waypointStore.get(wpId))
        assertEquals(Route.WaypointSheet(wpId), stack.last())
        assertEquals("Home", vm.waypointName.value)
        stack.pop()
        // Person-scoped actions live on the sheet destination / ViewModel.
        vm.setUserSharing(alice, false)
        assertTrue(awaitFamilyCondition { repo.getUser(aliceId)?.sendingEnabled == false })
        // Main actions: dialogs and waypoint creation.
        main.addPerson()
        assertEquals(Route.AddPersonDialog(), stack.last())
        stack.pop()
        main.addLink()
        assertEquals(Route.AddLinkDialog, stack.last())
        stack.pop()
        main.addTracker()
        assertEquals(Route.AddTrackerDialog, stack.last())
        stack.pop()
        main.onGpsWarningClick()
        assertTrue(gpsShown)
        main.beginCreateWaypoint()
        assertEquals(Route.WaypointSheet(0L), stack.last())
        vm.mapState.setWaypointName("New Place")
        assertTrue(vm.mapState.saveCurrentWaypoint(0L))
        assertTrue(awaitFamilyCondition { repo.waypointStore.getAll().any { it.name == "New Place" } })
        stack.pop()
        // History present/history toggling is MapPageState, driven by the sheet.
        vm.mapState.setShowingPresent(false)
        assertEquals(false, vm.isShowingPresent.value)
        vm.mapState.onPersonOpened()
        assertEquals(true, vm.isShowingPresent.value)
        // Deletes remove the rows (sheet destinations pop themselves after).
        vm.deleteWaypoint(repo.waypointStore.get(wpId))
        vm.deleteUser(alice)
        assertTrue(awaitFamilyCondition { repo.getUser(aliceId) == null })
        Unit
    }
}
