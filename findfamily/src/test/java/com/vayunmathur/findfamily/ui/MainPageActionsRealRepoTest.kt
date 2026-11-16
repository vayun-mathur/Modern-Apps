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
        val stack = NavBackStack<Route>(arrayOf(Route.MainPage()))
        var gpsShown = false
        var securityShown = false
        var contactPicks = 0
        var captured: Triple<
            com.vayunmathur.findfamily.util.FamilyListActions,
            com.vayunmathur.findfamily.util.PersonActions,
            MainPageActions>? = null
        rule.setContent {
            DynamicTheme {
                captured = rememberMainPageActions(
                    ffViewModel = vm, backStack = stack, platform = Platform(ctx),
                    selectedUser = alice, selectedWaypointId = wpId,
                    waypoints = runBlocking { repo.waypointStore.getAll() },
                    onShowGpsWarning = { gpsShown = it },
                    onShowSecurityCode = { securityShown = it },
                    requestPickContact = { contactPicks++ },
                )
            }
        }
        rule.waitForIdle()
        val (family, person, main) = captured!!
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
        family.selectUser(aliceId)
        family.beginEditWaypoint(repo.waypointStore.get(wpId))
        // Person actions delegate to the contact picker.
        person.changeConnectedContact()
        assertEquals(1, contactPicks)
        person.setUserSharing(alice, false)
        assertTrue(awaitFamilyCondition { repo.getUser(aliceId)?.sendingEnabled == false })
        // Main actions: dialogs, selection and waypoint form.
        main.addPerson()
        assertEquals(Route.AddPersonDialog(), stack.last())
        stack.pop()
        main.addLink()
        assertEquals(Route.AddLinkDialog, stack.last())
        stack.pop()
        main.addTracker()
        assertEquals(Route.AddTrackerDialog, stack.last())
        stack.pop()
        main.openUwbRanging(aliceId)
        assertEquals(Route.UwbRangingPage(aliceId), stack.last())
        stack.pop()
        main.onGpsWarningClick()
        assertTrue(gpsShown)
        main.onShowSecurityCode()
        assertTrue(securityShown)
        main.setWaypointName("Cabin")
        main.setWaypointRange("250")
        assertEquals("Cabin", vm.waypointName.value)
        assertEquals("250", vm.waypointRange.value)
        main.beginCreateWaypoint()
        main.setWaypointName("New Place")
        main.saveCurrentWaypoint()
        assertTrue(awaitFamilyCondition { repo.waypointStore.getAll().any { it.name == "New Place" } })
        main.enterHistory()
        assertEquals(false, vm.isShowingPresent.value)
        main.setShowingPresent(true)
        assertEquals(true, vm.isShowingPresent.value)
        vm.selectUser(aliceId)
        main.clearSelection()
        assertNull(vm.selectedUserId.value)
        // Deletes remove the rows and clear the selection.
        main.deleteSelectedWaypoint()
        main.deleteSelectedUser()
        assertTrue(awaitFamilyCondition { repo.getUser(aliceId) == null })
        Unit
    }
}
