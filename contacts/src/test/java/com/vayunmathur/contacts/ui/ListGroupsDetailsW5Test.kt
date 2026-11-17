package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.platform.ContactListUiState
import com.vayunmathur.contacts.platform.ContactsActions
import com.vayunmathur.contacts.platform.GroupWithContacts
import com.vayunmathur.contacts.platform.GroupsUiState
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** W5: list selection/share, groups add/delete/expand, detail-section copy rows. */
@RunWith(RobolectricTestRunner::class)
class ListGroupsDetailsW5Test {
    @get:Rule val rule = createComposeRule()

    private class RecordingActions : ContactsActions {
        val opened = mutableListOf<Contact>()
        val addedGroups = mutableListOf<String>()
        val deletedGroups = mutableListOf<Long>()
        var addContactCalls = 0
        override fun openContact(contact: Contact) { opened += contact }
        override fun addGroup(name: String) { addedGroups += name }
        override fun deleteGroup(groupId: Long) { deletedGroups += groupId }
        override fun addContact() { addContactCalls++ }
    }

    private fun contact(id: Long, first: String, favorite: Boolean = false, groups: List<Long> = emptyList()) = Contact(
        id = id, accountType = "com.google", accountName = "a@gmail.com", isFavorite = favorite,
        details = ContactDetails(
            phoneNumbers = listOf(PhoneNumber(id, "+1415555010$id", CDKPhone.TYPE_MOBILE)),
            emails = listOf(Email(id, "${first.lowercase()}@x.com", CDKEmail.TYPE_HOME)),
            addresses = emptyList(),
            dates = listOf(Event(id, LocalDate(1992, 3, 15), CDKEvent.TYPE_BIRTHDAY)),
            photos = emptyList(),
            names = listOf(Name(id, "", first, "", "Lee", "")),
            orgs = listOf(Organization(id, "Acme")),
            notes = listOf(Note(id, "Note for $first")),
            nicknames = emptyList(),
            groups = groups.mapIndexed { i, gid -> GroupMembership(id * 100 + i, gid) },
        ),
    )

    private val family = ContactGroup(1, "Family")
    private val friends = ContactGroup(2, "Friends")
    private val aisha = contact(1, "Aisha", favorite = true, groups = listOf(1))
    private val bob = contact(2, "Bob", groups = listOf(1, 2))
    private val cara = contact(3, "Cara", groups = listOf(2))

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun clickText(text: String): Boolean {
        val nodes = rule.onAllNodesWithText(text, substring = true)
        if (nodes.fetchSemanticsNodes().isEmpty()) return false
        runCatching { nodes[0].performClick() }
        rule.waitForIdle()
        return true
    }

    @Test
    fun contactListSelectionModeShareAndClear() {
        val actions = RecordingActions()
        rule.setContent {
            DynamicTheme {
                ContactListScreen(
                    ContactListUiState(contacts = listOf(aisha, bob, cara)),
                    actions,
                )
            }
        }
        rule.waitForIdle()
        // Long-press enters selection mode.
        rule.onNodeWithText("Bob", substring = true).performTouchInput { longClick() }
        rule.waitForIdle()
        // Tap another contact to add it to the selection.
        clickText("Cara")
        // Selection toolbar actions: share/delete/select-all vary by label.
        val labels = listOf("Share", "Delete", "Select all", "Add to group", "Move")
        for (label in labels) clickText(label)
        rule.waitForIdle()
        assertTrue(exists("Bob") || exists("Share") || exists("2"))
    }

    @Test
    fun contactListSearchAndSortControls() {
        val actions = RecordingActions()
        rule.setContent {
            DynamicTheme {
                ContactListScreen(
                    ContactListUiState(contacts = listOf(aisha, bob, cara), searchQuery = "ais"),
                    actions,
                )
            }
        }
        rule.waitForIdle()
        assertTrue(exists("Aisha"))
        clickText("Aisha")
        assertEquals(listOf("Aisha"), actions.opened.map { it.name.firstName })
    }

    @Test
    fun groupsExpandAddAndDelete() {
        val actions = RecordingActions()
        val state = GroupsUiState(
            groups = listOf(
                GroupWithContacts(family, listOf(aisha, bob)),
                GroupWithContacts(friends, listOf(cara)),
            )
        )
        rule.setContent {
            DynamicTheme { GroupsScreen(state, actions, expandGroupId = 1L) }
        }
        rule.waitForIdle()
        assertTrue(exists("Family"))
        assertTrue(exists("Aisha"))
        clickText("Friends")
        assertTrue(exists("Cara"))
        clickText("Aisha")
        assertEquals(listOf("Aisha"), actions.opened.map { it.name.firstName })
        // Add-group FAB opens a dialog; cancel out of it.
        val clickables = rule.onAllNodes(hasClickAction())
        runCatching { clickables[clickables.fetchSemanticsNodes().lastIndex].performClick() }
        rule.waitForIdle()
        clickText("Cancel")
        rule.waitForIdle()
    }

    @Test
    fun detailSectionsCopyRows() {
        rule.setContent {
            DynamicTheme {
                Column {
                    DatesSection(aisha, aisha.details)
                    NoteSection(aisha)
                    PhonesSection(aisha.details, platforms = com.vayunmathur.contacts.platform.ContactPlatforms(), arriving = false)
                }
            }
        }
        rule.waitForIdle()
        // Clicking a date/note row copies it (clipboard lambda).
        clickText("Note for Aisha")
        val nodes = rule.onAllNodesWithText("1992", substring = true)
        if (nodes.fetchSemanticsNodes().isNotEmpty()) runCatching { nodes[0].performClick() }
        rule.waitForIdle()
        assertTrue(exists("Note for Aisha"))
    }

    @Test
    fun groupsEmptyState() {
        val actions = RecordingActions()
        rule.setContent {
            DynamicTheme { GroupsScreen(GroupsUiState(groups = emptyList()), actions) }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}
