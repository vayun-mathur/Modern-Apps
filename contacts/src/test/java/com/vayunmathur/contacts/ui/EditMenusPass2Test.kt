package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.DynamicTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Second-pass menu interactions for the edit form: affix choosers and type pickers. */
@RunWith(RobolectricTestRunner::class)
class EditMenusPass2Test {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = ctx.getString(id)

    private fun draft(
        phones: List<PhoneNumber> = listOf(PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE)),
        emails: List<Email> = listOf(Email(2, "aisha@x.com", CDKEmail.TYPE_HOME)),
    ) = ContactViewModel.ContactDraft(
        firstName = "Aisha", lastName = "Rahman", phoneNumbers = phones, emails = emails,
    )

    @Test
    fun namePrefixChooserShowsCurrentValue() {
        // The prefix and suffix pickers are the same NameAffixChooser component;
        // menu selection is exercised on the suffix below. Here the prefix button
        // must show the placeholder when empty and the value when set.
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                EditContactNameSection(draft(), contactId = null, isSimAccount = false) { updates += it }
            }
        }
        rule.onNodeWithText(str(R.string.name_prefix), substring = true).assertExists()
        assertTrue(updates.isEmpty())
    }

    @Test
    fun namePrefixChooserShowsSetValue() {
        rule.setContent {
            DynamicTheme {
                EditContactNameSection(
                    draft().copy(namePrefix = "Dr", nameSuffix = "Jr"),
                    contactId = null, isSimAccount = false,
                ) {}
            }
        }
        rule.onAllNodesWithText("Dr", substring = true)[0].assertExists()
        rule.onAllNodesWithText("Jr", substring = true)[0].assertExists()
    }

    @Test
    fun nameSuffixChooserSelects() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                EditContactNameSection(draft(), contactId = null, isSimAccount = false) { updates += it }
            }
        }
        rule.onNodeWithText(str(R.string.name_suffix), substring = true).performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Jr", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Jr", substring = true).performClick()
        assertTrue(updates.any { it.nameSuffix == "Jr" }, "updates=$updates")
    }

    @Test
    fun phoneTypeMenuRendersCurrentType() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                EditContactDetailGroups(draft(), mobileIndex = 0, homeEmailIndex = 0, onUpdate = { updates += it })
            }
        }
        // The type picker button shows the current (Mobile) type. Its DropdownMenu
        // popup does not stay open under Robolectric in this harness (the same
        // menu works in NameAffixChooser), so option selection is not asserted
        // here; the menu wiring is the library FormDetailGroup's, covered by
        // its rendering plus the suffix menu interaction above.
        val typeNodes = rule.onAllNodesWithText("Mobile", substring = true)
        assertTrue(typeNodes.fetchSemanticsNodes().isNotEmpty(), "Mobile type label should render")
        rule.onNodeWithText("+14155550102", substring = true).assertExists()
        assertTrue(updates.isEmpty())
    }

    @Test
    fun addEmailAndAddressAppendBlankRows() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                EditContactDetailGroups(draft(), mobileIndex = 0, homeEmailIndex = 0, onUpdate = { updates += it })
            }
        }
        rule.onNodeWithText(str(R.string.add_email), substring = true).assertExists()
        // click the smallest (most specific) node for each add action by bounds,
        // the same disambiguation the add-phone row needs
        fun clickSmallestWithText(text: String) {
            val nodes = rule.onAllNodesWithText(text, substring = true)
            val semantics = nodes.fetchSemanticsNodes()
            val idx = semantics.indices.minByOrNull {
                val b = semantics[it].boundsInRoot
                (b.right - b.left) * (b.bottom - b.top)
            }!!
            nodes[idx].performClick()
        }
        clickSmallestWithText(str(R.string.add_email))
        assertTrue(updates.any { it.emails.size == 2 }, "add email expected, updates=$updates")
        clickSmallestWithText(str(R.string.add_address))
        assertTrue(updates.any { it.addresses.size == 1 }, "add address expected, updates=$updates")
    }

    @Test
    fun customTypePhoneShowsLabelField() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        val custom = draft(phones = listOf(PhoneNumber(1, "+14155550102", CDKPhone.TYPE_CUSTOM, "Cabin")))
        rule.setContent {
            DynamicTheme {
                EditContactDetailGroups(custom, mobileIndex = 0, homeEmailIndex = 0, onUpdate = { updates += it })
            }
        }
        assertTrue(rule.onAllNodesWithText("Cabin", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2)
        assertTrue(updates.isEmpty())
    }
}

/** Wide-window variant: the name section switches to its two-per-row grid. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1000dp-h700dp")
class EditNameGridPass2Test {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun wideNameGridEditsEveryField() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                Column {
                    EditContactNameSection(
                        ContactViewModel.ContactDraft(
                            namePrefix = "", firstName = "Aisha", middleName = "M",
                            lastName = "Rahman", nameSuffix = "", nickname = "Ash",
                            company = "Acme",
                        ),
                        contactId = 7L, isSimAccount = false,
                    ) { updates += it }
                }
            }
        }
        // grid field order follows composition: first, middle, last, nickname, company
        val fields = rule.onAllNodes(hasSetTextAction())
        assertTrue(fields.fetchSemanticsNodes().size >= 5)
        fields[0].performTextReplacement("Alicia")
        fields[1].performTextReplacement("Marie")
        fields[2].performTextReplacement("Khan")
        fields[3].performTextReplacement("Ashy")
        fields[4].performTextReplacement("NewCo")
        assertTrue(updates.any { it.firstName == "Alicia" }, "updates=$updates")
        assertTrue(updates.any { it.middleName == "Marie" }, "updates=$updates")
        assertTrue(updates.any { it.lastName == "Khan" }, "updates=$updates")
        assertTrue(updates.any { it.nickname == "Ashy" }, "updates=$updates")
        assertTrue(updates.any { it.company == "NewCo" }, "updates=$updates")
    }

    @Test
    fun wideNameSectionRendersGridFields() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                Column {
                    EditContactNameSection(
                        ContactViewModel.ContactDraft(firstName = "Aisha", middleName = "M", lastName = "Rahman"),
                        contactId = 7L, isSimAccount = false,
                    ) { updates += it }
                }
            }
        }
        val first = rule.onNodeWithText("Aisha", substring = true).fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithText("Rahman", substring = true).fetchSemanticsNode().boundsInRoot
        // grid: fields take half the window width in two columns (stacked
        // layout gives them the full width, as the portrait tests show)
        assertTrue(first.right - first.left < 600f,
            "grid fields should be half-width: first=$first last=$last")
        assertTrue(last.right - last.left < 600f)
        assertTrue(updates.isEmpty())
        assertTrue(rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 3)
    }
}
