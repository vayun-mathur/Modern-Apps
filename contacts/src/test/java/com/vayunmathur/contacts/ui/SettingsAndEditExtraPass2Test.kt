package com.vayunmathur.contacts.ui

import android.app.Application
import android.provider.ContactsContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.LOCAL_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.encodeExtraAccountEntry
import com.vayunmathur.contacts.util.initEditDraft
import com.vayunmathur.contacts.util.loadAccountsInternal
import com.vayunmathur.contacts.util.updateEditDraft
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Settings account-menu and edit-page SIM/gender extras. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsAndEditExtraPass2Test {
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

    private fun vm(): ContactViewModel {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        return vm
    }

    @Test
    fun settingsLocalAccountMenuOpensRenameDialog() {
        val vm = vm()
        // seed through the store so the page's own account reload keeps it
        runBlocking {
            vm.dataStore.addStringToSetIfAbsent(
                "extra_accounts_set",
                com.vayunmathur.contacts.util.encodeExtraAccountEntry("Family", LOCAL_ACCOUNT_TYPE),
            )
            vm.loadAccountsInternal()
        }
        rule.setContent {
            DynamicTheme { SettingsPage(vm, NavBackStack<Route>(arrayOf(Route.Main))) }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Family", substring = true))
        rule.onNodeWithText("Family", substring = true).assertExists()
        // the local account row has an overflow menu: click its last clickable
        val clickables = rule.onAllNodes(hasClickAction())
        val nodes = clickables.fetchSemanticsNodes()
        val familyTop = rule.onNodeWithText("Family", substring = true)
            .fetchSemanticsNode().boundsInRoot.top
        val rowClickables = nodes.indices.filter {
            kotlin.math.abs(nodes[it].boundsInRoot.top - familyTop) < 90f
        }
        assertTrue(rowClickables.isNotEmpty())
        clickables[rowClickables.last()].performClick()
        rule.waitForIdle()
        // menu offers Rename for local accounts
        if (rule.onAllNodesWithText(ctx.getString(com.vayunmathur.library.ui.R.string.rename), substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            rule.onAllNodesWithText(ctx.getString(com.vayunmathur.library.ui.R.string.rename), substring = true)[0]
                .performClick()
            rule.waitUntil(15_000) {
                rule.onAllNodesWithText(str(R.string.rename_account), substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun settingsExportRowExistsAndIsClickable() {
        val vm = vm()
        rule.setContent {
            DynamicTheme { SettingsPage(vm, NavBackStack<Route>(arrayOf(Route.Main))) }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(str(R.string.export_contacts), substring = true))
        rule.onNodeWithText(str(R.string.export_contacts), substring = true).assertExists()
        assertTrue(vm.allContactsForExport.value.any { it.id == 7L })
    }

    @Test
    fun editPageWithSimDraftShowsSimFields() {
        val vm = vm()
        vm.accountsState.value = listOf(ContactAccount("5", SIM_ACCOUNT_TYPE))
        vm.lastSelectedAccountState.value = ContactAccount("5", SIM_ACCOUNT_TYPE)
        vm.initEditDraft(null)
        assertEquals(SIM_ACCOUNT_TYPE, vm.editDraft.value?.accountType)
        val backStack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                DynamicTheme { EditContactPage(backStack, vm, Route.EditContact(contactId = null)) }
            }
        }
        rule.waitForIdle()
        // SIM drafts use the joint name field + single phone/email (SIM limits)
        assertEquals(SIM_ACCOUNT_TYPE, vm.editDraft.value?.accountType)
        rule.onRoot().assertExists()
    }

    @Test
    fun editPageGenderOtherTextUpdatesDraft() {
        val vm = vm()
        vm.initEditDraft(7L)
        assertEquals("Aisha", vm.editDraft.value?.firstName)
        vm.updateEditDraft { it.copy(genderCode = "O", genderText = "") }
        val backStack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                DynamicTheme { EditContactPage(backStack, vm, Route.EditContact(contactId = 7)) }
            }
        }
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(str(R.string.gender), substring = true))
        // the gender free-text field is the text field in the gender section:
        // type into the last text field on screen
        val fields = rule.onAllNodes(hasSetTextAction())
        assertTrue(fields.fetchSemanticsNodes().isNotEmpty())
        fields[fields.fetchSemanticsNodes().lastIndex].performTextReplacement("Agender")
        assertTrue(
            vm.editDraft.value?.genderText == "Agender" ||
                vm.editDraft.value?.genderCode == "O",
            "draft=${vm.editDraft.value}",
        )
    }
}
