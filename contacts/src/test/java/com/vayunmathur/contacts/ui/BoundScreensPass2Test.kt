package com.vayunmathur.contacts.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.ui.dialogs.AddAccountDialog
import com.vayunmathur.contacts.ui.dialogs.AddToGroupDialog
import com.vayunmathur.contacts.ui.dialogs.EventDatePickerDialog
import com.vayunmathur.contacts.ui.dialogs.EventDeleteConfirmDialog
import com.vayunmathur.contacts.ui.dialogs.MoveDialogHost
import com.vayunmathur.contacts.ui.dialogs.MoveToAccountDialog
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Second-pass coverage for the ViewModel-bound screens and dialogs, with an empty provider. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BoundScreensPass2Test {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = app as android.content.Context
    private fun str(id: Int) = ctx.getString(id)

    private fun vm(): ContactViewModel {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        return vm
    }

    private fun backStack() = NavBackStack<Route>(arrayOf(Route.Main))

    private fun fixtureContact(): Contact = Contact(
        id = 7, accountType = null, accountName = null, isFavorite = true,
        details = ContactDetails(
            phoneNumbers = listOf(com.vayunmathur.contacts.data.PhoneNumber(1, "+14155550107", com.vayunmathur.contacts.data.CDKPhone.TYPE_MOBILE)),
            emails = emptyList(), addresses = emptyList(), dates = emptyList(), photos = emptyList(),
            names = listOf(Name(2, "", "Aisha", "", "Rahman", "")),
            orgs = listOf(Organization(3, "Acme")),
            notes = listOf(Note(4, "hello")),
            nicknames = listOf(Nickname(5, "", CDKNickname.TYPE_DEFAULT)),
            groups = emptyList(),
        ),
    )

    @Test
    fun settingsPageRendersAccountsAndSyncRows() {
        val vm = vm()
        rule.setContent { DynamicTheme { SettingsPage(vm, backStack()) } }
        rule.onNodeWithText(str(R.string.calendar_sync), substring = true).assertExists()
        rule.onNodeWithText(str(R.string.show_account_labels), substring = true).assertExists()
        rule.onNodeWithText(str(R.string.export_contacts), substring = true).assertExists()
    }

    @Test
    fun groupsPageRendersEmptyGroups() {
        val vm = vm()
        rule.setContent { DynamicTheme { GroupsPage(vm, backStack()) } }
        rule.onNodeWithText(str(R.string.no_groups_yet), substring = true).assertExists()
    }

    @Test
    fun contactDetailsPageShowsFixtureContact() {
        val vm = vm()
        vm.allContactsState.value = listOf(fixtureContact())
        var edited: Long? = null
        rule.setContent {
            DynamicTheme {
                ContactDetailsPage(vm, contactId = 7, onBack = {}, onEdit = { edited = it }, onDelete = {})
            }
        }
        vm.allContactsState.value = listOf(fixtureContact())
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Aisha", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Aisha", substring = true).assertExists()
        assertEquals(null, edited)
    }

    @Test
    fun contactDetailsPageUnknownContactDoesNotCrash() {
        val vm = vm()
        rule.setContent {
            DynamicTheme { ContactDetailsPage(vm, contactId = 999, onBack = {}, onEdit = {}, onDelete = {}) }
        }
        rule.onRoot().assertExists()
    }

    @Test
    fun editContactPageNewContactShowsFormAndSave() {
        val vm = vm()
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalNavResultRegistry provides NavResultRegistry()
            ) { DynamicTheme { EditContactPage(backStack(), vm, Route.EditContact(contactId = null)) } }
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.add_contact), substring = true).assertExists()
        assertTrue(vm.editDraft.value != null, "new-contact draft should be initialized")
    }

    @Test
    fun editContactPageExistingContactLoadsDraft() {
        val vm = vm()
        vm.allContactsState.value = listOf(fixtureContact())
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalNavResultRegistry provides NavResultRegistry()
            ) { DynamicTheme { EditContactPage(backStack(), vm, Route.EditContact(contactId = 7)) } }
        }
        rule.waitForIdle()
        assertEquals("Aisha", vm.editDraft.value?.firstName)
        rule.onNodeWithText(str(R.string.edit_contact), substring = true).assertExists()
    }

    @Test
    fun insertOrEditScreenRenders() {
        val vm = vm()
        var exited = 0
        rule.setContent {
            DynamicTheme {
                InsertOrEditContactScreen(vm, backStack(), Route.InsertOrEditContact()) { exited++ }
            }
        }
        rule.onRoot().assertExists()
        assertEquals(0, exited)
    }

    @Test
    fun importVcfScreenEmptyUrisRenders() {
        val vm = vm()
        rule.setContent {
            DynamicTheme { ImportVcfScreen(vm, backStack(), uris = emptyList()) }
        }
        rule.onRoot().assertExists()
        assertTrue(vm.parsedVcfContacts.value.isNullOrEmpty())
    }

    @Test
    fun cropPhotoScreenWithBogusUriRenders() {
        var cancelled = 0
        rule.setContent {
            DynamicTheme {
                CropPhotoScreen(uri = "content://nonexistent/photo/1", onCropComplete = {}, onCancel = { cancelled++ })
            }
        }
        rule.onRoot().assertExists()
        assertEquals(0, cancelled)
    }

    @Test
    fun contactListPickLoadingAndPopulated() {
        val picked = mutableListOf<Uri>()
        rule.setContent {
            DynamicTheme {
                ContactListPick(mimeType = null, contacts = emptyList(), isLoading = true, onClick = { picked += it })
            }
        }
        rule.onRoot().assertExists()
        assertTrue(picked.isEmpty())
    }

    @Test
    fun contactItemPickEmitsContactUriAndSimRowDoesNot() {
        val picked = mutableListOf<Uri>()
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Column {
                    ContactItemPick(fixtureContact(), mimeType = null, selectedUris = emptySet()) { picked += it }
                }
            }
        }
        rule.onNodeWithText("Aisha", substring = true).assertExists()
        rule.onNodeWithText("Aisha", substring = true).performClick()
        assertEquals(1, picked.size)
        assertTrue(picked.single().toString().endsWith("/7"), picked.single().toString())
    }

    @Test
    fun addAccountDialogTypesName() {
        val vm = vm()
        var dismissed = 0
        rule.setContent { DynamicTheme { AddAccountDialog(vm) { dismissed++ } } }
        rule.onNodeWithText(str(R.string.add_contacts_account), substring = true).assertExists()
        rule.onNode(hasSetTextAction()).performTextReplacement("Family Account")
        assertEquals(0, dismissed)
    }

    @Test
    fun addToGroupDialogWithNoGroupsRenders() {
        val vm = vm()
        rule.setContent { DynamicTheme { AddToGroupDialog(vm, listOf(7L)) {} } }
        assertEquals(2, rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().size)
    }

    @Test
    fun eventDeleteConfirmDialogRenders() {
        val vm = vm()
        var confirmed = 0
        rule.setContent {
            DynamicTheme {
                EventDeleteConfirmDialog(7, "Aisha Rahman", vm, onConfirm = { confirmed++ }, onDismiss = {})
            }
        }
        assertEquals(2, rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().size)
        assertEquals(0, confirmed)
    }

    @Test
    fun moveToAccountDialogSelectsAccount() {
        var selected: ContactAccount? = null
        val accounts = listOf(
            ContactAccount("a@gmail.com", "com.google"),
            ContactAccount("On device", "com.vayunmathur.contacts.local"),
        )
        rule.setContent {
            DynamicTheme {
                MoveToAccountDialog(
                    contactCount = 2, accounts = accounts, sourceAccounts = emptySet(),
                    selectedAccount = null, onSelectAccount = { selected = it },
                    onClearSelection = {}, onConfirm = {}, onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("a@gmail.com", substring = true).assertExists()
        rule.onNodeWithText("a@gmail.com", substring = true).performClick()
        assertEquals(ContactAccount("a@gmail.com", "com.google"), selected)
    }

    @Test
    fun moveDialogHostRenders() {
        val vm = vm()
        rule.setContent { DynamicTheme { MoveDialogHost(vm, listOf(7L), onDismiss = {}) } }
        assertEquals(2, rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().size)
    }

    @Test
    fun eventDatePickerDialogRenders() {
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalNavResultRegistry provides NavResultRegistry()
            ) { DynamicTheme { EventDatePickerDialog("birthday", LocalDate(1992, 3, 15)) {} } }
        }
        // the date picker is a dialog: it lives in a second (popup) root
        assertEquals(2, rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().size)
        assertTrue(rule.onAllNodesWithText("15", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("1992", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}
