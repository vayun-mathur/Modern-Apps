package com.vayunmathur.contacts.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

/** Direct SIM field editing: replace, create, and truncation behavior. */
@RunWith(RobolectricTestRunner::class)
class SimFieldsPass2Test {
    @get:Rule val rule = createComposeRule()

    @Test
    fun existingPhoneAndEmailAreEditedInPlace() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        val draft = ContactViewModel.ContactDraft(
            firstName = "Aisha",
            phoneNumbers = listOf(
                PhoneNumber(10, "+14155550102", CDKPhone.TYPE_MOBILE),
                PhoneNumber(11, "+442071234567", CDKPhone.TYPE_WORK),
            ),
            emails = listOf(
                Email(12, "aisha@x.com", CDKEmail.TYPE_HOME),
                Email(13, "second@x.com", CDKEmail.TYPE_WORK),
            ),
        )
        rule.setContent {
            DynamicTheme { SimContactFields(draft) { updates += it } }
        }

        val fields = rule.onAllNodes(hasSetTextAction())
        assertEquals(2, fields.fetchSemanticsNodes().size)
        fields[0].performTextReplacement("+14155550199")
        fields[1].performTextReplacement("new@x.com")

        assertEquals("+14155550199", updates.first().phoneNumbers[0].number)
        assertEquals(10L, updates.first().phoneNumbers[0].id)
        assertEquals("+442071234567", updates.first().phoneNumbers[1].number)
        assertEquals("new@x.com", updates.last().emails[0].address)
        assertEquals(12L, updates.last().emails[0].id)
        assertEquals("second@x.com", updates.last().emails[1].address)
    }

    @Test
    fun emptyDraftCreatesDefaultMobileAndHomeEntries() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                SimContactFields(ContactViewModel.ContactDraft()) { updates += it }
            }
        }

        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("+442071234567")
        fields[1].performTextReplacement("created@x.com")

        assertEquals("+442071234567", updates.first().phoneNumbers.single().number)
        assertEquals(CDKPhone.TYPE_MOBILE, updates.first().phoneNumbers.single().type)
        assertEquals("created@x.com", updates.last().emails.single().address)
        assertEquals(CDKEmail.TYPE_HOME, updates.last().emails.single().type)
    }

    @Test
    fun simDetailGroupsShowsOnlySimFields() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        val draft = ContactViewModel.ContactDraft(
            phoneNumbers = listOf(
                PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE),
                PhoneNumber(2, "+442071234567", CDKPhone.TYPE_WORK),
            ),
            emails = listOf(
                Email(3, "aisha@x.com", CDKEmail.TYPE_HOME),
                Email(4, "second@x.com", CDKEmail.TYPE_WORK),
            ),
        )
        rule.setContent {
            DynamicTheme {
                EditContactDetailGroups(
                    draft,
                    mobileIndex = 0,
                    homeEmailIndex = 0,
                    onUpdate = { updates += it },
                    isSimAccount = true,
                )
            }
        }

        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("+14155550102", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            rule.onAllNodesWithText("second@x.com", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )
        assertTrue(updates.any { it.phoneNumbers.size == 1 && it.emails.size == 1 })
    }
}
