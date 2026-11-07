package com.vayunmathur.contacts.ui

import android.app.Application
import android.provider.ContactsContract
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GroupsPage against the fake provider (Family group with a member). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GroupsBoundPass2Test {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
    }


    @Test
    fun groupsPageShowsProviderGroupAndOpensMember() {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        val backStack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent {
            DynamicTheme { GroupsPage(vm, backStack, expandGroupId = 11L) }
        }
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("Family", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // members of group 11: the provider's Aisha (synthetic/projection row)
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("Aisha", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(vm.groups.value.any { it.name == "Family" },
            "groups=${vm.groups.value}")

        // the member count is shown next to the group name
        // clicking the member opens their details route
        rule.onNodeWithText("Aisha", substring = true).performClick()
        assertEquals(Route.ContactDetail(7L), backStack.backStack.last())
    }

    @Test
    fun groupsPageCollapseAndReExpand() {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        val backStack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent {
            DynamicTheme { GroupsPage(vm, backStack) }
        }
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("Family", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // expand
        rule.onNodeWithText("Family", substring = true).performClick()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("Aisha", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // collapse again: the member disappears
        rule.onNodeWithText("Family", substring = true).performClick()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("Aisha", substring = true).fetchSemanticsNodes().isEmpty()
        }
    }
}
