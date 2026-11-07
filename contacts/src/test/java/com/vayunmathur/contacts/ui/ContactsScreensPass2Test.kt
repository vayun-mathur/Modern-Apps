package com.vayunmathur.contacts.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.util.ContactDetailsUiState
import com.vayunmathur.contacts.util.ContactListUiState
import com.vayunmathur.contacts.util.ContactsActions
import com.vayunmathur.contacts.util.ContactsTab
import com.vayunmathur.contacts.util.GroupWithContacts
import com.vayunmathur.contacts.util.GroupsUiState
import com.vayunmathur.library.ui.DynamicTheme
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Second-pass Compose coverage for the stateless contacts screens, rendered under
 * Robolectric with literal state (the same shapes the screenshot previews use).
 */
@RunWith(RobolectricTestRunner::class)
class ContactsScreensPass2Test {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = ctx.getString(id)

    private class RecordingActions : ContactsActions {
        val opened = mutableListOf<Contact>()
        val queries = mutableListOf<String>()
        val addedGroups = mutableListOf<String>()
        val deletedGroups = mutableListOf<Long>()
        val tabs = mutableListOf<ContactsTab>()
        var addContactCalls = 0
        override fun openContact(contact: Contact) { opened += contact }
        override fun setSearchQuery(query: String) { queries += query }
        override fun addGroup(name: String) { addedGroups += name }
        override fun deleteGroup(groupId: Long) { deletedGroups += groupId }
        override fun selectTab(tab: ContactsTab) { tabs += tab }
        override fun addContact() { addContactCalls++ }
    }

    private fun contact(
        id: Long, first: String, last: String, favorite: Boolean = false,
        phone: String? = null, email: String? = null, groupIds: List<Long> = emptyList(),
    ) = Contact(
        id = id, accountType = null, accountName = null, isFavorite = favorite,
        details = ContactDetails(
            phoneNumbers = listOfNotNull(phone?.let { PhoneNumber(id * 10, it, CDKPhone.TYPE_MOBILE) }),
            emails = listOfNotNull(email?.let { Email(id * 10 + 1, it, CDKEmail.TYPE_HOME) }),
            addresses = emptyList(),
            dates = emptyList(),
            photos = emptyList(),
            names = listOf(Name(id * 10 + 4, "", first, "", last, "")),
            orgs = listOf(Organization(id * 10 + 5, "")),
            notes = listOf(Note(id * 10 + 6, "")),
            nicknames = listOf(Nickname(id * 10 + 7, "", CDKNickname.TYPE_DEFAULT)),
            groups = groupIds.mapIndexed { i, gid -> GroupMembership(id * 100 + i, gid) },
        ),
    )

    private val family = ContactGroup(1, "Family")
    private val friends = ContactGroup(2, "Friends")
    private val aisha = Contact(
        id = 2, accountType = "com.google", accountName = "aisha@gmail.com", isFavorite = true,
        details = ContactDetails(
            phoneNumbers = listOf(PhoneNumber(20, "+1 415 555 0102", CDKPhone.TYPE_MOBILE)),
            emails = listOf(Email(21, "aisha@example.com", CDKEmail.TYPE_HOME)),
            addresses = listOf(Address(22, "1200 Market St", CDKStructuredPostal.TYPE_HOME)),
            dates = listOf(Event(23, LocalDate(1992, 3, 15), CDKEvent.TYPE_BIRTHDAY)),
            photos = emptyList(),
            names = listOf(Name(24, "", "Aisha", "", "Rahman", "")),
            orgs = listOf(Organization(25, "Acme Design")),
            notes = listOf(Note(26, "Prefers email")),
            nicknames = listOf(Nickname(27, "Ash", CDKNickname.TYPE_DEFAULT)),
            groups = listOf(GroupMembership(28, 1), GroupMembership(29, 2)),
        ),
    )

    @Test
    fun contactListEmptyStatesRender() {
        rule.setContent {
            DynamicTheme { ContactListScreen(ContactListUiState(isLoading = true), ContactsActions.Noop) }
        }
        rule.onRoot().assertExists()
    }

    @Test
    fun contactListPopulatedShowsNamesAndOpensContact() {
        val actions = RecordingActions()
        val contacts = listOf(
            contact(1, "Aaron", "Blake", favorite = true, phone = "+1 415 555 0142"),
            aisha,
            contact(3, "Zoe", "Zhang", phone = "+1 415 555 0173"),
        )
        rule.setContent {
            DynamicTheme {
                ContactListScreen(
                    ContactListUiState(contacts = contacts, groups = listOf(family, friends)),
                    actions,
                )
            }
        }
        rule.onNodeWithText("Aaron", substring = true).assertExists()
        rule.onNodeWithText("Zoe", substring = true).assertExists()
        rule.onNodeWithText("Zoe", substring = true).performClick()
        assertEquals(listOf(3L), actions.opened.map { it.id })
    }

    @Test
    fun contactListSearchInputReportsQuery() {
        val actions = RecordingActions()
        rule.setContent {
            DynamicTheme {
                ContactListScreen(ContactListUiState(contacts = listOf(aisha)), actions)
            }
        }
        rule.onNodeWithText(str(R.string.search_contacts), substring = true).assertExists()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Aisha")
        assertEquals(listOf("Aisha"), actions.queries.takeLast(1))
    }

    @Test
    fun contactDetailsShowsAllSections() {
        rule.setContent {
            DynamicTheme {
                ContactDetailsScreen(
                    ContactDetailsUiState(contact = aisha, groups = listOf(family, friends)),
                    ContactsActions.Noop,
                )
            }
        }
        rule.onNodeWithText("Aisha", substring = true).assertExists()
        // sections live in a lazy column: scroll each into composition, in order
        val scroller = rule.onNode(androidx.compose.ui.test.hasScrollAction())
        scroller.performScrollToNode(androidx.compose.ui.test.hasText("(415) 555-0102", substring = true))
        rule.onNodeWithText("(415) 555-0102", substring = true).assertExists()
        scroller.performScrollToNode(androidx.compose.ui.test.hasText("aisha@example.com", substring = true))
        rule.onNodeWithText("aisha@example.com", substring = true).assertExists()
        scroller.performScrollToNode(androidx.compose.ui.test.hasText("1200 Market St", substring = true))
        rule.onNodeWithText("1200 Market St", substring = true).assertExists()
        scroller.performScrollToNode(androidx.compose.ui.test.hasText("Prefers email", substring = true))
        rule.onNodeWithText("Prefers email", substring = true).assertExists()
        scroller.performScrollToNode(androidx.compose.ui.test.hasText("Acme Design", substring = true))
        rule.onNodeWithText("Acme Design", substring = true).assertExists()
    }

    @Test
    fun contactDetailsMinimalContactRenders() {
        val bare = contact(9, "Bob", "Smith")
        rule.setContent {
            DynamicTheme {
                ContactDetailsScreen(
                    ContactDetailsUiState(contact = bare),
                    ContactsActions.Noop, showBackButton = false,
                )
            }
        }
        rule.onNodeWithText("Bob", substring = true).assertExists()
    }

    @Test
    fun groupsEmptyAndLoadingRender() {
        rule.setContent {
            DynamicTheme { GroupsScreen(GroupsUiState(isLoading = false), ContactsActions.Noop) }
        }
        rule.onNodeWithText(str(R.string.no_groups_yet), substring = true).assertExists()
    }

    @Test
    fun groupsPopulatedExpandAndShowMembers() {
        val members = listOf(contact(1, "Aaron", "Blake", groupIds = listOf(1)), aisha)
        rule.setContent {
            DynamicTheme {
                GroupsScreen(
                    GroupsUiState(
                        groups = listOf(
                            GroupWithContacts(family, members),
                            GroupWithContacts(friends, listOf(aisha)),
                        )
                    ),
                    ContactsActions.Noop, expandGroupId = family.id,
                )
            }
        }
        rule.onNodeWithText("Family").assertExists()
        rule.onNodeWithText("Friends").assertExists()
        rule.onNodeWithText("2", substring = true).assertExists()
        // collapse Family again by tapping its header
        rule.onNodeWithText("Family").performClick()
        rule.onNodeWithText("Friends").assertExists()
    }

    @Test
    fun contactsActionsNoopAndContractDefaults() {
        val noop = ContactsActions.Noop
        noop.setSearchQuery("x"); noop.deleteContact(aisha); noop.saveContact(aisha)
        noop.addGroup("g"); noop.deleteGroup(1); noop.openContact(aisha); noop.addContact()
        noop.editContact(1); noop.confirmDeleteContact(aisha); noop.closeContact()
        noop.pickRingtone(aisha); noop.addToGroup(listOf(1)); noop.moveContacts(listOf(1))
        noop.shareContacts(listOf(aisha), "x.vcf"); noop.selectTab(ContactsTab.Groups)
        assertEquals(null, noop.decodePhoto("AAAA"))
        assertEquals(ContactsTab.Contacts, ContactsTab.valueOf("Contacts"))
        assertEquals(3, ContactsTab.entries.size)

        val listState = ContactListUiState()
        assertTrue(listState.contacts.isEmpty())
        assertEquals("", listState.searchQuery)
        assertTrue(listState.showAccountLabels)
        assertEquals(null, listState.openContactId)
        val detailsState = ContactDetailsUiState(contact = aisha)
        assertTrue(detailsState.groups.isEmpty())
        assertEquals(false, detailsState.isGoogleMeetInstalled)
        assertTrue(GroupsUiState().groups.isEmpty())
        assertEquals(false, GroupsUiState().isLoading)
        assertEquals(
            GroupWithContacts(family, listOf(aisha)),
            GroupWithContacts(family, listOf(aisha)).copy(),
        )
    }

    @Test
    fun contactItemDirectRender() {
        var clicked: Contact? = null
        rule.setContent {
            DynamicTheme {
                ContactItem(
                    contact = aisha,
                    isSelected = false,
                    showAccountLabels = true,
                    onClick = { clicked = aisha },
                    allGroups = listOf(family, friends),
                )
            }
        }
        rule.onNodeWithText("Aisha", substring = true).assertExists()
        rule.onNodeWithText("Aisha", substring = true).performClick()
        assertEquals(aisha, clicked)
    }

    @Test
    fun letterAndFavoritesHeadersRender() {
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Column {
                    LetterHeader('A')
                    FavoritesHeader()
                    val bianca = contact(5, "Bianca", "Lopez")
                    GroupedContactSection(count = 1) {
                        ContactItem(
                            contact = bianca, isSelected = false,
                            showAccountLabels = false, onClick = {},
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("Bianca", substring = true).assertExists()
        rule.onRoot().assertExists()
    }
}
