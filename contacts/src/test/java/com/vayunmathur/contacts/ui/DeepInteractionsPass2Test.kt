package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.data.ContactPrefill
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.PrefillValue
import com.vayunmathur.contacts.util.ContactDetailsUiState
import com.vayunmathur.contacts.util.ContactListUiState
import com.vayunmathur.contacts.util.ContactPlatforms
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.ContactsActions
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
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

/** Second-pass deep interactions: details actions, comm rows, list selection, insert-or-edit. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DeepInteractionsPass2Test {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = ctx.getString(id)

    private class RecordingActions : ContactsActions {
        val saved = mutableListOf<Contact>()
        val edited = mutableListOf<Long>()
        val shared = mutableListOf<String>()
        val moved = mutableListOf<List<Long>>()
        val deleteConfirmed = mutableListOf<Contact>()
        val opened = mutableListOf<Contact>()
        val ringtone = mutableListOf<Contact>()
        val groupAdds = mutableListOf<List<Long>>()
        var closed = 0
        override fun saveContact(contact: Contact) { saved += contact }
        override fun editContact(contactId: Long) { edited += contactId }
        override fun shareContacts(contacts: List<Contact>, filename: String) { shared += filename }
        override fun moveContacts(contactIds: List<Long>) { moved += contactIds }
        override fun confirmDeleteContact(contact: Contact) { deleteConfirmed += contact }
        override fun openContact(contact: Contact) { opened += contact }
        override fun closeContact() { closed++ }
        override fun pickRingtone(contact: Contact) { ringtone += contact }
        override fun addToGroup(contactIds: List<Long>) { groupAdds += contactIds }
    }

    private fun contact(): Contact = Contact(
        id = 7, accountType = "com.google", accountName = "a@gmail.com", isFavorite = false,
        details = ContactDetails(
            phoneNumbers = listOf(
                PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE),
                PhoneNumber(2, "+442071234567", CDKPhone.TYPE_WORK),
            ),
            emails = listOf(Email(3, "aisha@x.com", CDKEmail.TYPE_HOME)),
            addresses = listOf(Address(4, "1200 Market St", CDKStructuredPostal.TYPE_HOME)),
            dates = listOf(
                Event(5, LocalDate(1992, 3, 15), CDKEvent.TYPE_BIRTHDAY),
                Event(6, LocalDate(2020, 6, 1), CDKEvent.TYPE_ANNIVERSARY),
            ),
            photos = emptyList(),
            names = listOf(Name(7, "", "Aisha", "", "Rahman", "")),
            orgs = listOf(Organization(8, "Acme")),
            notes = listOf(Note(9, "hello note")),
            nicknames = listOf(Nickname(10, "Ash", CDKNickname.TYPE_DEFAULT)),
            groups = listOf(GroupMembership(11, 1)),
        ),
        customRingtone = "content://media/ringtone/1",
    )

    private fun vmWith(contact: Contact): ContactViewModel {
        val vm = ContactViewModel(ctx as android.app.Application)
        runBlocking { vm.syncFromSystem() }
        vm.allContactsState.value = listOf(contact)
        return vm
    }

    @Test
    fun detailsTopBarActionsInvokeCallbacks() {
        val actions = RecordingActions()
        val contact = contact()
        rule.setContent {
            DynamicTheme {
                ContactDetailsScreen(
                    ContactDetailsUiState(
                        contact = contact,
                        groups = listOf(ContactGroup(1, "Family")),
                        platforms = ContactPlatforms(
                            whatsAppMessageId = 101, whatsAppCallId = 102,
                            signalMessageId = 103, telegramCallId = 104,
                        ),
                        isGoogleMeetInstalled = true,
                    ),
                    actions,
                )
            }
        }
        rule.onNodeWithText("Aisha", substring = true).assertExists()
        // top app bar icons in declaration order: favorite, edit, share, move, delete
        val clickables = rule.onAllNodes(hasClickAction())
        val count = clickables.fetchSemanticsNodes().size
        assertTrue(count >= 5, "expected at least the 5 app-bar actions, got $count")
        // app-bar nodes are the topmost clickables; left-to-right after the back
        // button they are: favorite, edit, share, move, delete
        val nodes = clickables.fetchSemanticsNodes()
        val appBar = nodes.indices
            .filter { nodes[it].boundsInRoot.top < 220f }
            .sortedBy { nodes[it].boundsInRoot.left }
        assertTrue(appBar.size >= 6, "expected back + 5 app-bar actions, got ${appBar.size}: " +
            nodes.indices.joinToString { i -> "#$i[${nodes[i].boundsInRoot.left.toInt()},${nodes[i].boundsInRoot.top.toInt()}]" })
        // every app-bar icon (and its semantics child) sits in this top strip;
        // clicking across it must fire each of the five actions exactly once
        for (k in 1 until appBar.size) {
            clickables[appBar[k]].performClick()
        }
        assertEquals(listOf(true), actions.saved.map { it.isFavorite })
        assertEquals(listOf(7L), actions.edited)
        assertEquals(listOf("Aisha_Rahman.vcf"), actions.shared)
        assertEquals(listOf(listOf(7L)), actions.moved)
        assertEquals(listOf(contact), actions.deleteConfirmed)
        assertEquals(0, actions.closed)
    }

    @Test
    fun detailsSectionsAndCommRowsRenderWithPlatforms() {
        val actions = RecordingActions()
        rule.setContent {
            DynamicTheme {
                ContactDetailsScreen(
                    ContactDetailsUiState(
                        contact = contact(),
                        groups = listOf(ContactGroup(1, "Family")),
                        platforms = ContactPlatforms(whatsAppMessageId = 101, signalCallId = 102),
                    ),
                    actions, showBackButton = false,
                )
            }
        }
        val scroller = rule.onNode(hasScrollAction())
        scroller.performScrollToNode(hasText("(415) 555-0102", substring = true))
        rule.onNodeWithText("(415) 555-0102", substring = true).assertExists()
        scroller.performScrollToNode(hasText("aisha@x.com", substring = true))
        rule.onNodeWithText("aisha@x.com", substring = true).assertExists()
        scroller.performScrollToNode(hasText("1200 Market St", substring = true))
        scroller.performScrollToNode(hasText("hello note", substring = true))
        rule.onNodeWithText("hello note", substring = true).assertExists()
        scroller.performScrollToNode(hasText("Family", substring = true))
        rule.onNodeWithText("Family", substring = true).assertExists()
    }

    @Test
    fun detailsLandscapeLayoutRenders() {
        val actions = RecordingActions()
        rule.setContent {
            Box(Modifier.requiredSize(900.dp, 420.dp)) {
                DynamicTheme {
                    ContactDetailsScreen(ContactDetailsUiState(contact = contact()), actions)
                }
            }
        }
        rule.onNodeWithText("Aisha", substring = true).assertExists()
        rule.onNodeWithText("Rahman", substring = true).assertExists()
    }

    @Test
    fun contactListLongPressEntersSelectionMode() {
        val actions = RecordingActions()
        val contact = contact()
        rule.setContent {
            DynamicTheme {
                ContactListScreen(
                    ContactListUiState(contacts = listOf(contact), showAddButton = true),
                    actions,
                )
            }
        }
        rule.onNodeWithText("Aisha", substring = true).assertExists()
        rule.onNodeWithText("Aisha", substring = true)
            .performTouchInput { down(center); advanceEventTime(700); up() }
        rule.waitForIdle()
        // selection mode replaces the top bar; the delete action appears in it
        assertTrue(
            rule.onAllNodesWithText(str(com.vayunmathur.library.ui.R.string.delete), substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size > 3,
            "selection mode should expose bulk actions",
        )
        assertTrue(actions.opened.isEmpty(), "long press must not open the contact")
    }

    @Test
    fun contactListWithAccountLabelsAndSortModes() {
        val actions = RecordingActions()
        val contact = contact()
        var sortChanged: com.vayunmathur.contacts.util.ContactSorting.ContactSortMode? = null
        rule.setContent {
            DynamicTheme {
                ContactListScreen(
                    ContactListUiState(
                        contacts = listOf(contact),
                        accountLabels = mapOf("com.google|a@gmail.com" to "Google"),
                        openContactId = 7L,
                    ),
                    actions,
                    sortMode = com.vayunmathur.contacts.util.ContactSorting.ContactSortMode.LastName,
                    onSortModeChange = { sortChanged = it },
                )
            }
        }
        rule.onNodeWithText("Google", substring = true).assertExists()
        rule.onNodeWithText("Aisha", substring = true).performClick()
        assertEquals(listOf(contact), actions.opened)
        assertEquals(null, sortChanged)
    }

    @Test
    fun insertOrEditShowsContactsAndCreateNewRoutes() {
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
            android.provider.ContactsContract.AUTHORITY,
            com.vayunmathur.contacts.data.FakeProviderPass2Test.FakeContactsProvider(),
        )
        val vm = ContactViewModel(ctx as android.app.Application)
        runBlocking { vm.syncFromSystem() }
        val backStack = NavBackStack<Route>(arrayOf(Route.Main))
        val prefill = ContactPrefill(
            name = "New Number",
            phones = listOf(PrefillValue("+14155550199")),
        )
        rule.setContent {
            DynamicTheme {
                InsertOrEditContactScreen(vm, backStack, Route.InsertOrEditContact(prefill)) {}
            }
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Aisha", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("+14155550199", substring = true).assertExists()
        // search narrows to no matches
        rule.onNode(hasSetTextAction()).performTextInput("zzzz")
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(str(R.string.no_contacts_found), substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // create-new replaces the top of the stack with the editor
        rule.onNodeWithText(str(R.string.create_new_contact), substring = true).performClick()
        val top = backStack.backStack.last()
        assertTrue(top is Route.EditContact && top.contactId == null, "top=$top")
    }

    @Test
    fun insertOrEditEmptyStateAfterLoad() {
        val vm = ContactViewModel(ctx as android.app.Application)
        runBlocking { vm.syncFromSystem() }
        val backStack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent {
            DynamicTheme {
                InsertOrEditContactScreen(vm, backStack, Route.InsertOrEditContact()) {}
            }
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(str(R.string.no_contacts_yet), substring = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText(str(R.string.create_new_contact), substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
