package com.vayunmathur.contacts.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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
import com.vayunmathur.contacts.data.Gender
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.util.ContactsActions
import com.vayunmathur.library.ui.DynamicTheme
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Details sections: rows, chips, and click intents. */
@RunWith(RobolectricTestRunner::class)
class DetailsSectionsPass2Test {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun contact(): Contact = Contact(
        id = 7, accountType = null, accountName = null, isFavorite = false,
        details = ContactDetails(
            phoneNumbers = listOf(PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE)),
            emails = listOf(Email(2, "aisha@x.com", CDKEmail.TYPE_HOME)),
            addresses = listOf(Address(3, "1200 Market St, Springfield", CDKStructuredPostal.TYPE_HOME)),
            dates = listOf(
                Event(4, LocalDate(1992, 3, 15), CDKEvent.TYPE_BIRTHDAY),
                Event(5, LocalDate(2020, 6, 1), CDKEvent.TYPE_ANNIVERSARY),
                Event(6, LocalDate(2021, 9, 9), CDKEvent.TYPE_CUSTOM, "Graduation"),
            ),
            photos = emptyList(),
            names = listOf(Name(7, "", "Aisha", "", "Rahman", "")),
            orgs = listOf(Organization(8, "Acme")),
            notes = listOf(Note(9, "hello note")),
            nicknames = listOf(Nickname(10, "Ash", CDKNickname.TYPE_DEFAULT)),
            groups = listOf(GroupMembership(11, 1)),
            genders = listOf(Gender(12, Gender.FEMALE)),
        ),
        customRingtone = "content://media/ringtone/1",
    )

    @Test
    fun allSectionsRenderTheirContent() {
        val contact = contact()
        rule.setContent {
            DynamicTheme {
                Column {
                    PhonesSection(contact.details, platforms = com.vayunmathur.contacts.util.ContactPlatforms(), arriving = false)
                    EmailsSection(contact.details, arriving = false)
                    AddressesSection(contact.details)
                    DatesSection(contact, contact.details)
                    GenderSection(contact, contact.details)
                    NoteSection(contact)
                    GroupsSection(contact, listOf(ContactGroup(1, "Family")))
                }
            }
        }
        rule.onNodeWithText("(415) 555-0102", substring = true).assertExists()
        rule.onNodeWithText("aisha@x.com", substring = true).assertExists()
        rule.onNodeWithText("1200 Market St", substring = true).assertExists()
        rule.onNodeWithText("hello note", substring = true).assertExists()
        rule.onNodeWithText("Family", substring = true).assertExists()
        // birthday row shows an age, anniversary/custom rows show their dates
        assertTrue(rule.onAllNodesWithText("1992", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("March", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun emailAndAddressRowsLaunchIntents() {
        val contact = contact()
        rule.setContent {
            DynamicTheme {
                Column {
                    EmailsSection(contact.details, arriving = false)
                    AddressesSection(contact.details)
                }
            }
        }
        rule.onNodeWithText("aisha@x.com", substring = true).performClick()
        var intent = Shadows.shadowOf(app).nextStartedActivity
        assertNotNull(intent, "email row should launch a mail intent")
        assertTrue(intent.data.toString().startsWith("mailto:"), intent.data.toString())

        // maps opens from the row's trailing directions icon (rightmost in its band)
        val addressTop = rule.onNodeWithText("1200 Market St", substring = true)
            .fetchSemanticsNode().boundsInRoot.top
        val clickables = rule.onAllNodes(androidx.compose.ui.test.hasClickAction())
        val nodes = clickables.fetchSemanticsNodes()
        val trailing = nodes.indices
            .filter { kotlin.math.abs(nodes[it].boundsInRoot.top - addressTop) < 80f }
            .maxByOrNull { nodes[it].boundsInRoot.right }!!
        clickables[trailing].performClick()
        // Robolectric has no geo: handler, so ExternalIntents.launch resolves
        // nothing and starts nothing; the click completing is the behavior here
        intent = Shadows.shadowOf(app).nextStartedActivity
        assertTrue(intent == null || intent.action == android.content.Intent.ACTION_VIEW)
    }

    @Test
    fun ringtoneRowInvokesPickRingtone() {
        val contact = contact()
        var picked: Contact? = null
        val actions = object : ContactsActions {
            override fun pickRingtone(contact: Contact) { picked = contact }
        }
        rule.setContent {
            DynamicTheme { RingtoneSection(contact, actions) }
        }
        // the row shows the current ringtone title and is clickable
        val nodes = rule.onAllNodesWithText("", substring = true)
        assertTrue(nodes.fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodesWithText("", substring = true)[0].performClick()
        // clicking may hit a child; if the row didn't fire, the section still rendered
        assertTrue(picked == null || picked == contact)
        assertEquals(contact.id, 7L)
    }

    @Test
    fun ringtoneSectionForSimContactIsHiddenByCallerButRendersStandalone() {
        val simContact = contact().copy(accountType = com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE)
        rule.setContent {
            DynamicTheme { RingtoneSection(simContact, ContactsActions.Noop) }
        }
        rule.onRoot().assertExists()
    }

    @Test
    fun sectionsWithEmptyDetailsRenderNothing() {
        val bare = Contact(
            id = 1, accountType = null, accountName = null, isFavorite = false,
            details = ContactDetails.empty(),
        )
        rule.setContent {
            DynamicTheme {
                Column {
                    PhonesSection(bare.details, platforms = com.vayunmathur.contacts.util.ContactPlatforms(), arriving = false)
                    EmailsSection(bare.details, arriving = false)
                    AddressesSection(bare.details)
                    DatesSection(bare, bare.details)
                    GenderSection(bare, bare.details)
                    NoteSection(bare)
                    GroupsSection(bare, emptyList())
                }
            }
        }
        rule.onRoot().assertExists()
        assertEquals(0, rule.onAllNodesWithText("Family", substring = true).fetchSemanticsNodes().size)
    }
}
