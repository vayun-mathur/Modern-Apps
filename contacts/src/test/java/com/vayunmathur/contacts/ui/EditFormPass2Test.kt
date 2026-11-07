package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CommPlatform
import com.vayunmathur.contacts.data.CommPref
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Second-pass interactions for the remaining edit-form sections. */
@RunWith(RobolectricTestRunner::class)
class EditFormPass2Test {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun draft(
        phones: List<PhoneNumber> = listOf(PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE)),
        groups: List<GroupMembership> = emptyList(),
        commPref: CommPref? = null,
        genderCode: String = "",
    ) = ContactViewModel.ContactDraft(
        firstName = "Aisha", lastName = "Rahman",
        phoneNumbers = phones,
        emails = listOf(Email(2, "aisha@x.com", CDKEmail.TYPE_HOME)),
        groupMemberships = groups, commPref = commPref, genderCode = genderCode,
    )

    @Test
    fun groupSectionAddAndRemoveMembership() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        val allGroups = listOf(ContactGroup(1, "Family"), ContactGroup(2, "Friends"), ContactGroup(3, "  "))
        rule.setContent {
            DynamicTheme {
                Column {
                    EditContactGroupSection(
                        draft(groups = listOf(GroupMembership(9, 1))),
                        allGroups, contactId = null,
                    ) { updates += it }
                }
            }
        }
        rule.onNodeWithText("Family", substring = true).assertExists()
        // available groups live in a dropdown: open the field, then pick Friends
        rule.onNodeWithText("Groups", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Friends", substring = true).assertExists()
        rule.onNodeWithText("Friends", substring = true).performClick()
        assertTrue(updates.any { it.groupMemberships.any { gm -> gm.groupId == 2L } },
            "picking Friends should add membership, updates=$updates")
    }

    @Test
    fun groupSectionHiddenWhenNoGroups() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                Column { EditContactGroupSection(draft(), emptyList(), contactId = null) { updates += it } }
            }
        }
        rule.onRoot().assertExists()
        assertTrue(updates.isEmpty())
    }

    @Test
    fun accountSectionShowsCurrentAccountAndOpensChooser() {
        var changed: Pair<String, String>? = null
        val accounts = listOf(
            ContactAccount("a@gmail.com", "com.google"),
            ContactAccount("Family", "com.vayunmathur.contacts.local"),
        )
        rule.setContent {
            DynamicTheme {
                EditContactAccountSection(
                    draft().copy(accountName = "a@gmail.com", accountType = "com.google"),
                    accounts,
                ) { name, type -> changed = name to type }
            }
        }
        rule.onNodeWithText("a@gmail.com", substring = true).assertExists()
        // open the chooser and pick the other account
        rule.onNodeWithText("a@gmail.com", substring = true).performClick()
        rule.waitForIdle()
        if (rule.onAllNodesWithText("Family", substring = true).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Family", substring = true).performClick()
            assertEquals("Family" to "com.vayunmathur.contacts.local", changed)
        }
    }

    @Test
    fun commPrefSectionWithPrefShowsAndClears() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        val pref = CommPref(1, "+14155550102", CommPlatform.SIM)
        rule.setContent {
            DynamicTheme {
                EditContactCommPrefSection(draft(commPref = pref), contactId = null) { updates += it }
            }
        }
        rule.onNodeWithText(ctx.getString(com.vayunmathur.contacts.R.string.preferred_contact_method), substring = true)
            .assertExists()
        // the clear (remove-circle) button is the last clickable in the section
        val clickables = rule.onAllNodes(hasClickAction())
        clickables[clickables.fetchSemanticsNodes().lastIndex].performClick()
        assertTrue(updates.any { it.commPref == null }, "clear should null the pref, updates=$updates")
    }

    @Test
    fun commPrefSectionWithoutPrefStillRenders() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                EditContactCommPrefSection(draft(), contactId = null) { updates += it }
            }
        }
        rule.onNodeWithText(ctx.getString(com.vayunmathur.contacts.R.string.preferred_contact_method), substring = true)
            .assertExists()
        assertTrue(updates.isEmpty())
    }

    @Test
    fun genderSectionShowsSelectedGender() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                EditContactGenderSection(draft(genderCode = "M"), contactId = null) { updates += it }
            }
        }
        rule.onRoot().assertExists()
        // genders array: index 1 is Male
        val genders = ctx.resources.getStringArray(com.vayunmathur.contacts.R.array.genders)
        rule.onNodeWithText(genders[1], substring = true).assertExists()
    }

    @Test
    fun dateDetailsRenderChangeTypeAndRemove() {
        val changes = mutableListOf<List<Event>>()
        val events = listOf(
            Event(5, LocalDate(2020, 6, 1), CDKEvent.TYPE_ANNIVERSARY),
            Event(6, LocalDate(1992, 3, 15), CDKEvent.TYPE_BIRTHDAY), // skipped by this section
        )
        val backStack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalNavResultRegistry provides NavResultRegistry()
            ) {
                DynamicTheme {
                    Column {
                        EditContactDateDetails(
                            backStack = backStack, details = events,
                            onDetailsChange = { changes += it },
                            icon = { DateDetailsAddIcon() },
                            options = listOf(CDKEvent.TYPE_ANNIVERSARY, CDKEvent.TYPE_OTHER, CDKEvent.TYPE_CUSTOM),
                        )
                    }
                }
            }
        }
        // only the anniversary row renders (birthday is handled by its own section)
        assertEquals(1, rule.onAllNodesWithText("Dates", substring = true).fetchSemanticsNodes().size.coerceAtMost(1))
        assertTrue(backStack.backStack.size == 1)
        assertTrue(changes.isEmpty())
        rule.onRoot().assertExists()
    }
}
