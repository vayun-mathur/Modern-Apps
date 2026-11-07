package com.vayunmathur.findfamily.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.ui.dialogs.AddLinkDialog
import com.vayunmathur.findfamily.ui.dialogs.AddPersonDialog
import com.vayunmathur.findfamily.ui.dialogs.AddTrackerDialog
import com.vayunmathur.findfamily.ui.dialogs.DropdownField
import com.vayunmathur.findfamily.ui.dialogs.GpsFallbackWarningDialog
import com.vayunmathur.findfamily.ui.dialogs.KeyMismatchDialog
import com.vayunmathur.findfamily.ui.dialogs.OutdatedPeerDialog
import com.vayunmathur.findfamily.ui.dialogs.SecurityCodeDialog
import com.vayunmathur.findfamily.util.FindFamilyViewModel
import com.vayunmathur.findfamily.util.Platform
import com.vayunmathur.findfamily.util.UwbSessionManager
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Clock

@RunWith(AndroidJUnit4::class)
class DialogsWithVmTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app
    private lateinit var repo: FindFamilyRepository
    private lateinit var vm: FindFamilyViewModel

    @BeforeTest
    fun setup() {
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        val db = Room.inMemoryDatabaseBuilder(ctx, FFDatabase::class.java).allowMainThreadQueries().build()
        repo = FindFamilyRepository(ctx, db)
        vm = FindFamilyViewModel(app, repo)
    }

    private fun stack(): NavBackStack<Route> = NavBackStack(arrayOf(Route.MainPage()))

    private fun user(name: String = "Alice") = User(
        name = name, photo = null, locationName = "Home", sendingEnabled = true,
        requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(), id = 42,
    )

    @Test
    fun uwbContentIdleAndWaiting() {
        rule.setContent { UwbRangingContent(peerName = "Alice", session = UwbSessionManager.UwbSessionState.Idle, onBack = {}) }
        rule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun uwbContentFailedAndUnsupported() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                UwbRangingContent(peerName = "Bob", session = UwbSessionManager.UwbSessionState.Failed("radio error"), onBack = {})
            }
        }
        rule.onNodeWithText("Bob").assertIsDisplayed()
    }

    @Test
    fun uwbContentRanging() {
        rule.setContent {
            UwbRangingContent(
                peerName = "Carol",
                session = UwbSessionManager.UwbSessionState.Ranging(
                    com.vayunmathur.findfamily.uwb.RangingSample(
                        distanceMeters = 2.5f, azimuthDeg = 10f, elevationDeg = null,
                        timestampNanos = 1_000_000L,
                    )
                ),
                onBack = {},
            )
        }
        rule.onNodeWithText("Carol").assertIsDisplayed()
    }

    @Test
    fun addPersonDialogRenders() {
        rule.setContent { AddPersonDialog(backStack = stack(), ffViewModel = vm, platform = Platform(ctx), id = null) }
        rule.onNodeWithText("Add Person", substring = true).assertExists()
    }

    @Test
    fun addPersonDialogWithInviteId() {
        rule.setContent { AddPersonDialog(backStack = stack(), ffViewModel = vm, platform = Platform(ctx), id = 123L, fingerprint = "abc") }
        rule.onNodeWithText("Add Person", substring = true).assertExists()
    }

    @Test
    fun addLinkDialogRenders() {
        rule.setContent { AddLinkDialog(backStack = stack(), ffViewModel = vm) }
        rule.waitForIdle()
    }

    @Test
    fun addTrackerDialogRenders() {
        rule.setContent { AddTrackerDialog(backStack = stack()) }
        rule.waitForIdle()
    }

    @Test
    fun securityCodeDialogRenders() {
        rule.setContent { SecurityCodeDialog(user = user(), ffViewModel = vm, onDismiss = {}) }
        rule.waitForIdle()
    }

    @Test
    fun gpsWarningDialogRenders() {
        rule.setContent { GpsFallbackWarningDialog(onDismiss = {}) }
        rule.onAllNodesWithText("GPS", substring = true)[0].assertIsDisplayed()
    }

    @Test
    fun keyMismatchDialogRenders() {
        rule.setContent { KeyMismatchDialog(onDismiss = {}) }
        rule.waitForIdle()
    }

    @Test
    fun outdatedPeerDialogRenders() {
        rule.setContent { OutdatedPeerDialog(onDismiss = {}) }
        rule.waitForIdle()
    }

    @Test
    fun dropdownFieldRenders() {
        rule.setContent { DropdownField(value = "1 day", setValue = {}, options = listOf("1 day", "1 week")) }
        rule.onNodeWithText("1 day").assertIsDisplayed()
    }

    @Test
    fun mainActivityLaunches() {
        val controller = org.robolectric.Robolectric.buildActivity(com.vayunmathur.findfamily.MainActivity::class.java)
        val activity = controller.create().start().resume().get()
        org.junit.Assert.assertNotNull(activity)
        Thread.sleep(400)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }

    @Test
    fun seedUserForDialogs() = runBlocking {
        repo.upsertUser(user())
        org.junit.Assert.assertEquals(1, repo.getAllUsers().size)
    }
}
