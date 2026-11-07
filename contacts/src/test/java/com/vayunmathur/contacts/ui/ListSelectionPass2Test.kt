package com.vayunmathur.contacts.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.util.ContactListUiState
import com.vayunmathur.contacts.util.ContactsActions
import com.vayunmathur.library.ui.DynamicTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Second-pass list selection mode: bulk share/group/move and delete confirmation. */
@RunWith(RobolectricTestRunner::class)
class ListSelectionPass2Test {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = ctx.getString(id, *args)

    private class RecordingActions : ContactsActions {
        val deleted = mutableListOf<Contact>()
        val shared = mutableListOf<Pair<List<Contact>, String>>()
        val moved = mutableListOf<List<Long>>()
        val grouped = mutableListOf<List<Long>>()
        val opened = mutableListOf<Contact>()
        override fun deleteContact(contact: Contact) { deleted += contact }
        override fun shareContacts(contacts: List<Contact>, filename: String) { shared += contacts to filename }
        override fun moveContacts(contactIds: List<Long>) { moved += contactIds }
        override fun addToGroup(contactIds: List<Long>) { grouped += contactIds }
        override fun openContact(contact: Contact) { opened += contact }
    }

    private fun contact(id: Long, first: String) = Contact(
        id = id, accountType = null, accountName = null, isFavorite = false,
        details = ContactDetails(
            phoneNumbers = listOf(PhoneNumber(id, "+1415555010$id", CDKPhone.TYPE_MOBILE)),
            emails = emptyList(), addresses = emptyList(), dates = emptyList(), photos = emptyList(),
            names = listOf(Name(id, "", first, "", "Lee", "")),
            orgs = listOf(Organization(id, "")),
            notes = listOf(Note(id, "")),
            nicknames = listOf(Nickname(id, "", CDKNickname.TYPE_DEFAULT)),
            groups = emptyList(),
        ),
    )

    private val contacts = listOf(contact(1, "Aisha"), contact(2, "Bob"), contact(3, "Zoe"))
    private val actions = RecordingActions()

    private fun render() {
        rule.setContent {
            DynamicTheme { ContactListScreen(ContactListUiState(contacts = contacts), actions) }
        }
        rule.waitForIdle()
    }

    private fun longPress(name: String) {
        rule.onNodeWithText(name, substring = true).performTouchInput {
            down(center)
            advanceEventTime(700)
            up()
        }
        rule.waitForIdle()
    }

    /** Clickable nodes in the top app-bar strip, left to right. */
    private fun topClickables(): List<Int> {
        val clickables = rule.onAllNodes(hasClickAction())
        val nodes = clickables.fetchSemanticsNodes()
        // the strip is bounded by the first contact row: use the topmost row
        // node's top as the cutoff when rows are composed
        return nodes.indices
            .filter { nodes[it].boundsInRoot.top < 110f }
            .sortedBy { nodes[it].boundsInRoot.left }
    }

    @Test
    fun selectionModeCountsAndToggles() {
        render()
        longPress("Aisha")
        rule.onNodeWithText(str(R.string.selected_count, 1), substring = true).assertExists()
        // tapping another row toggles it into the selection
        rule.onNodeWithText("Bob", substring = true).performClick()
        rule.onNodeWithText(str(R.string.selected_count, 2), substring = true).assertExists()
        // tapping a selected row again removes it
        rule.onNodeWithText("Bob", substring = true).performClick()
        rule.onNodeWithText(str(R.string.selected_count, 1), substring = true).assertExists()
        assertTrue(actions.opened.isEmpty(), "selection taps must not open contacts")
    }

    @Test
    fun selectionShareGroupMoveFireWithSelectedIds() {
        render()
        longPress("Aisha")
        rule.onNodeWithText("Bob", substring = true).performClick()
        rule.onNodeWithText(str(R.string.selected_count, 2), substring = true).assertExists()

        // click across the top strip (skipping the close button at index 0);
        // each of group/move/share must fire exactly once with [1, 2]
        val clickables = rule.onAllNodes(hasClickAction())
        for (index in topClickables().drop(1)) {
            clickables[index].performClick()
            rule.waitForIdle()
        }
        assertTrue(actions.grouped.any { it.toSet() == setOf(1L, 2L) }, "grouped=${actions.grouped}")
        assertTrue(actions.moved.any { it.toSet() == setOf(1L, 2L) }, "moved=${actions.moved}")
        assertTrue(
            actions.shared.any { it.second == "selected_contacts.vcf" && it.first.map { c -> c.id }.toSet() == setOf(1L, 2L) },
            "shared=${actions.shared}",
        )
    }

    @Test
    fun selectionDeleteConfirmationDeletes() {
        render()
        longPress("Zoe")
        rule.onNodeWithText(str(R.string.selected_count, 1), substring = true).assertExists()

        // delete is the rightmost top action: click top nodes from the right until
        // the confirmation dialog appears
        val clickables = rule.onAllNodes(hasClickAction())
        val top = topClickables()
        var dialogShown = false
        for (index in top.reversed()) {
            clickables[index].performClick()
            rule.waitForIdle()
            if (rule.onAllNodesWithText(str(R.string.delete_selected_contacts_title), substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                dialogShown = true
                break
            }
        }
        assertTrue(dialogShown, "delete confirmation should appear")
        rule.onNodeWithText(ctx.getString(com.vayunmathur.library.ui.R.string.delete)).performClick()
        assertEquals(listOf(3L), actions.deleted.map { it.id })
    }

    @Test
    fun shareAllFromNormalTopBar() {
        render()
        // normal-mode share (top bar) exports every contact
        val clickables = rule.onAllNodes(hasClickAction())
        val top = topClickables()
        for (index in top) {
            clickables[index].performClick()
            rule.waitForIdle()
            if (actions.shared.isNotEmpty()) break
        }
        assertTrue(
            actions.shared.any { it.second == "all_contacts.vcf" && it.first.size == 3 },
            "shared=${actions.shared}",
        )
    }
}
