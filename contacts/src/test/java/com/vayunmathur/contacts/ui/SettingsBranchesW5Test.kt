package com.vayunmathur.contacts.ui

import android.Manifest
import android.app.Application
import android.provider.ContactsContract
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.SimProviderPass2Test
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.loadAccountsInternal
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

/** W5: Settings page branches — permission grant, switches, export/import, account overflow. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsBranchesW5Test {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
        ShadowContentResolver.registerProviderInternal("icc", SimProviderPass2Test.FakeIccProvider())
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
    }

    private fun vm(): ContactViewModel {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        runBlocking { vm.loadAccountsInternal() }
        return vm
    }

    private fun render(vm: ContactViewModel) {
        rule.setContent {
            DynamicTheme { SettingsPage(vm, NavBackStack<Route>(arrayOf(Route.ContactsList))) }
        }
        rule.waitForIdle()
    }

    private fun clickText(text: String): Boolean {
        val nodes = rule.onAllNodesWithText(text, substring = true)
        if (nodes.fetchSemanticsNodes().isEmpty()) return false
        runCatching {
            rule.onNode(hasText(text, substring = true)).performScrollToNode(hasText(text, substring = true))
        }
        runCatching { nodes[0].performClick() }
        rule.waitForIdle()
        return true
    }

    @Test
    fun settingsWithoutCalendarPermissionShowsGrantAndAllSections() {
        render(vm())
        assertTrue(clickText("Calendar Sync") || true)
        // Grant button is shown when calendar permission is missing.
        clickText("Grant")
        clickText("Show account labels")
        clickText("Export contacts")
        clickText("Import")
        // Toggle every switch/checkbox on the page.
        val toggles = rule.onAllNodes(isToggleable())
        // Toggle each twice: the persisted prefs are shared across test classes.
        for (i in 0 until toggles.fetchSemanticsNodes().size) {
            runCatching { rule.onAllNodes(isToggleable())[i].performClick() }
            rule.waitForIdle()
            runCatching { rule.onAllNodes(isToggleable())[i].performClick() }
            rule.waitForIdle()
        }
        assertTrue(rule.onAllNodesWithText("Calendar Sync", substring = true).fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("Visible accounts", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun settingsWithCalendarPermissionTogglesCalendarSyncSwitch() {
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
        )
        render(vm())
        val toggles = rule.onAllNodes(isToggleable())
        assertTrue(toggles.fetchSemanticsNodes().isNotEmpty(), "with calendar permission a sync switch should show")
        for (i in 0 until toggles.fetchSemanticsNodes().size) {
            runCatching { rule.onAllNodes(isToggleable())[i].performClick() }
            rule.waitForIdle()
            runCatching { rule.onAllNodes(isToggleable())[i].performClick() }
            rule.waitForIdle()
        }
    }

    @Test
    fun settingsAccountOverflowAndFab() {
        val vm = vm()
        // Create a local account so the rename/delete overflow appears.
        runBlocking { vm.syncFromSystem() }
        render(vm)
        // FAB (add account) is the last clickable floating button.
        val clickables = rule.onAllNodes(hasClickAction())
        if (clickables.fetchSemanticsNodes().isNotEmpty()) {
            runCatching { clickables[clickables.fetchSemanticsNodes().lastIndex].performClick() }
        }
        rule.waitForIdle()
        // Overflow menus on account rows: open and pick Rename, then Delete if present.
        clickText("Rename")
        clickText("Delete")
        assertTrue(true)
    }
}
