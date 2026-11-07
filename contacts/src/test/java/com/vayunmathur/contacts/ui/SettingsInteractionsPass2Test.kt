package com.vayunmathur.contacts.ui

import android.app.Application
import android.provider.ContactsContract
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.loadAccountsInternal
import com.vayunmathur.contacts.util.setAccountVisibility
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Second-pass Settings interactions: account visibility toggles against the fake provider. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsInteractionsPass2Test {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = app as android.content.Context
    private fun str(id: Int) = ctx.getString(id)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
    }


    private fun androidx.compose.ui.semantics.SemanticsNode.configOrNullToggleState():
        androidx.compose.ui.state.ToggleableState? =
        if (androidx.compose.ui.semantics.SemanticsProperties.ToggleableState in config) {
            config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState]
        } else {
            null
        }

    private fun vm(): ContactViewModel {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        runBlocking { vm.loadAccountsInternal() }
        return vm
    }

    @Test
    fun accountsRenderAndVisibilityToggleHidesAccount() {
        val vm = vm()
        assertTrue(vm.accounts.value.any { it.name == "a@gmail.com" }, "accounts=${vm.accounts.value}")
        rule.setContent {
            DynamicTheme { SettingsPage(vm, NavBackStack<Route>(arrayOf(Route.Main))) }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(str(R.string.visible_accounts), substring = true))
        rule.onNodeWithText("a@gmail.com", substring = true).assertExists()

        // hide the account through the VM (the same call the checkbox makes);
        // the row's checkbox must then render unchecked
        val google = vm.accounts.value.first { it.name == "a@gmail.com" }
        vm.setAccountVisibility(google, false)
        val deadline = System.nanoTime() + 15_000_000_000L
        var hidden = emptySet<String>()
        while (System.nanoTime() < deadline) {
            hidden = runBlocking { vm.dataStore.getStringSetAwait("hidden_accounts") }
            if (hidden.any { it.contains("a@gmail.com") }) break
            Thread.sleep(25)
        }
        assertTrue(hidden.any { it.contains("a@gmail.com") }, "hidden=$hidden")

        rule.waitUntil(15_000) {
            rule.onAllNodes(isToggleable()).fetchSemanticsNodes().any { node ->
                node.configOrNullToggleState() == androidx.compose.ui.state.ToggleableState.Off
            }
        }
    }

    @Test
    fun calendarSyncAndLabelSwitchesRender() {
        val vm = vm()
        rule.setContent {
            DynamicTheme { SettingsPage(vm, NavBackStack<Route>(arrayOf(Route.Main))) }
        }
        rule.onNodeWithText(str(R.string.calendar_sync), substring = true).assertExists()
        rule.onNodeWithText(str(R.string.show_account_labels), substring = true).assertExists()
        // flip the account-labels switch (in the same row band as its label)
        // and wait for the store to reflect it
        val labelTop = rule.onNodeWithText(str(R.string.show_account_labels), substring = true)
            .fetchSemanticsNode().boundsInRoot.top
        val toggles = rule.onAllNodes(isToggleable())
        val nodes = toggles.fetchSemanticsNodes()
        val switch = nodes.indices.firstOrNull {
            kotlin.math.abs(nodes[it].boundsInRoot.top - labelTop) < 80f
        }
        assertTrue(switch != null, "no switch in the labels row")
        val before = vm.dataStore.getBoolean("show_account_labels", true)
        toggles[switch!!].performClick()
        val deadline = System.nanoTime() + 15_000_000_000L
        while (vm.dataStore.getBoolean("show_account_labels", true) == before &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(25)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertEquals(!before, vm.dataStore.getBoolean("show_account_labels", true))
    }

    @Test
    fun exportAndImportRowsRender() {
        val vm = vm()
        rule.setContent {
            DynamicTheme { SettingsPage(vm, NavBackStack<Route>(arrayOf(Route.Main))) }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(str(R.string.export_contacts), substring = true))
        rule.onNodeWithText(str(R.string.export_contacts), substring = true).assertExists()
        assertEquals(0, vm.allContactsForExport.value.count { it.id == 999L })
        assertTrue(vm.allContactsForExport.value.any { it.id == 7L })
    }
}
