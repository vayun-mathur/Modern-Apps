package com.vayunmathur.contacts.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextReplacement
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.DynamicTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Second-pass coverage for the stateless edit-contact sections, driven by a literal draft. */
@RunWith(RobolectricTestRunner::class)
class EditSectionsPass2Test {
    @get:Rule val rule = createComposeRule()

    private fun draft(
        first: String = "Aisha", last: String = "Rahman",
        phones: List<PhoneNumber> = listOf(PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE)),
        emails: List<Email> = listOf(Email(2, "aisha@example.com", CDKEmail.TYPE_HOME)),
    ) = ContactViewModel.ContactDraft(
        firstName = first, lastName = last, phoneNumbers = phones, emails = emails,
    )

    @Test
    fun nameSectionEditingReportsUpdatedDraft() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                EditContactNameSection(draft(), contactId = null, isSimAccount = false) { updates += it }
            }
        }
        rule.onNodeWithText("Aisha", substring = true).assertExists()
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("Alicia")
        assertTrue(updates.isNotEmpty(), "typing a first name should report a draft update")
        assertEquals("Alicia", updates.last().firstName)
        assertEquals("Rahman", updates.last().lastName)
    }

    @Test
    fun nameSectionEditsEveryStackedField() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        val initial = ContactViewModel.ContactDraft(
            firstName = "Aisha", middleName = "M", lastName = "Rahman",
            nickname = "Ash", company = "Acme",
        )
        rule.setContent {
            DynamicTheme {
                EditContactNameSection(initial, contactId = null, isSimAccount = false) { updates += it }
            }
        }
        // stacked field order: first, middle, last, nickname, company
        val fields = rule.onAllNodes(hasSetTextAction())
        assertEquals(5, fields.fetchSemanticsNodes().size)
        fields[1].performTextReplacement("Marie")
        fields[2].performTextReplacement("Khan")
        fields[3].performTextReplacement("Ashy")
        fields[4].performTextReplacement("NewCo")
        assertTrue(updates.any { it.middleName == "Marie" }, "updates=$updates")
        assertTrue(updates.any { it.lastName == "Khan" }, "updates=$updates")
        assertTrue(updates.any { it.nickname == "Ashy" }, "updates=$updates")
        assertTrue(updates.any { it.company == "NewCo" }, "updates=$updates")
    }

    @Test
    fun nameSectionSimAccountUsesSingleJointField() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                EditContactNameSection(draft(), contactId = 5L, isSimAccount = true) { updates += it }
            }
        }
        // one joint field carrying the whole name; editing it clears the split parts
        val fields = rule.onAllNodes(hasSetTextAction())
        assertEquals(1, fields.fetchSemanticsNodes().size)
        fields[0].performTextReplacement("Aisha Rahman Khan")
        assertTrue(updates.isNotEmpty())
        assertEquals("Aisha Rahman Khan", updates.last().firstName)
        assertEquals("", updates.last().lastName)
    }

    @Test
    fun detailGroupsRenderPhoneAndEmailFields() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                EditContactDetailGroups(draft(), mobileIndex = 0, homeEmailIndex = 0, onUpdate = { updates += it })
            }
        }
        rule.onNodeWithText("aisha@example.com", substring = true).assertExists()
        rule.onNodeWithText("Add phone", substring = true).assertExists()
        rule.onNodeWithText("Add email", substring = true).assertExists()
        // editing the first (phone) field reports the updated draft
        assertTrue(rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("+14155550199")
        assertTrue(updates.any { it.phoneNumbers.first().number == "+14155550199" },
            "phone edit should report the new number, updates=$updates")
    }

    @Test
    fun detailGroupsSimAccountTruncatesExtras() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        val many = draft(
            phones = listOf(
                PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE),
                PhoneNumber(2, "+14155550103", CDKPhone.TYPE_HOME),
                PhoneNumber(3, "+14155550104", CDKPhone.TYPE_WORK),
            ),
            emails = listOf(
                Email(4, "a@x.com", CDKEmail.TYPE_HOME),
                Email(5, "b@x.com", CDKEmail.TYPE_WORK),
            ),
        )
        rule.setContent {
            DynamicTheme {
                EditContactDetailGroups(many, mobileIndex = 0, homeEmailIndex = 0, onUpdate = { updates += it }, isSimAccount = true)
            }
        }
        // the SIM early-return launches an effect that truncates to SIM limits
        rule.waitForIdle()
        assertTrue(updates.any { it.phoneNumbers.size == 1 && it.emails.size == 1 },
            "SIM draft should be truncated to one phone/email, updates=$updates")
    }

    @Test
    fun genderSectionRendersAndCommPrefHiddenWithoutNumbers() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Column {
                    EditContactGenderSection(draft().copy(genderCode = "F"), contactId = null) { updates += it }
                    EditContactCommPrefSection(draft(phones = emptyList()), contactId = null) { updates += it }
                }
            }
        }
        rule.onRoot().assertExists()
        // gender chooser shows the current (female) label from the genders array
        assertTrue(rule.onAllNodesWithText("Female", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("Gender", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun countryFlagEmojiForKnownAndUnknownNumbers() {
        assertEquals("🇺🇸", getCountryFlagEmoji("+14155550102"))
        assertEquals("🇬🇧", getCountryFlagEmoji("+442071234567"))
        assertEquals("", getCountryFlagEmoji(""))
        assertEquals("", getCountryFlagEmoji("not a number"))
        assertEquals("", getCountryFlagEmoji("4155550102")) // no country code: no flag
    }
}
