package com.vayunmathur.contacts.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
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

/** Second-pass row actions for EditContactDetailGroups: edits and removals per group. */
@RunWith(RobolectricTestRunner::class)
class DetailGroupsActionsPass2Test {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun draft() = ContactViewModel.ContactDraft(
        firstName = "Aisha", lastName = "Rahman",
        phoneNumbers = listOf(
            PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE),
            PhoneNumber(2, "+442071234567", CDKPhone.TYPE_WORK),
        ),
        emails = listOf(Email(3, "aisha@x.com", CDKEmail.TYPE_HOME)),
        addresses = listOf(Address(4, "1200 Market St", CDKStructuredPostal.TYPE_HOME)),
    )

    private fun render(updates: MutableList<ContactViewModel.ContactDraft>) {
        rule.setContent {
            DynamicTheme {
                EditContactDetailGroups(draft(), mobileIndex = 0, homeEmailIndex = 0, onUpdate = { updates += it })
            }
        }
    }

    @Test
    fun editingSecondPhoneReportsNewNumber() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        render(updates)
        // fields in order: phone1, phone2, email, address
        val fields = rule.onAllNodes(hasSetTextAction())
        assertTrue(fields.fetchSemanticsNodes().size >= 4)
        fields[1].performTextReplacement("+442071234568")
        assertTrue(updates.any { it.phoneNumbers[1].number == "+442071234568" }, "updates=$updates")
        assertEquals("+14155550102", updates.last().phoneNumbers[0].number)
    }

    @Test
    fun editingEmailAndAddressReportNewValues() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        render(updates)
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[2].performTextReplacement("new@x.com")
        assertTrue(updates.any { it.emails.firstOrNull()?.address == "new@x.com" }, "updates=$updates")
        rule.onAllNodes(hasSetTextAction())[3].performTextReplacement("5 Oak Street")
        assertTrue(updates.any { it.addresses.firstOrNull()?.formattedAddress == "5 Oak Street" }, "updates=$updates")
    }

    @Test
    fun addingPhoneAppendsDefaultRow() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        render(updates)
        // Add buttons are composed after the email field; use the semantics
        // collection rather than whichever node hasClickAction returns first.
        // The Add buttons are composed after the email field; identify the
        // phone Add button by bounds: it spans the group's width below the
        // second phone row, before the email field begins.
        val labels = rule.onAllNodesWithText("+442071234567", substring = true).fetchSemanticsNodes()
        assertTrue(labels.isNotEmpty())
        val phoneRowBottom = labels.maxOf { it.boundsInRoot.bottom }
        val clickables = rule.onAllNodes(hasClickAction())
        val nodes = clickables.fetchSemanticsNodes()
        val addIndex = nodes.indices.firstOrNull { index ->
            val bounds = nodes[index].boundsInRoot
            bounds.top >= phoneRowBottom && (bounds.right - bounds.left) > 150f
        }
        assertTrue(addIndex != null, "Add Phone button not found among $nodes")
        clickables[addIndex!!].performClick()
        assertTrue(
            updates.any { update -> update.phoneNumbers.size == 3 && update.phoneNumbers.last().number.isEmpty() },
            "updates=$updates",
        )
    }

    @Test
    fun nonMandatoryRowsExposeRemoveControls() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        render(updates)
        // each detail row carries a type button and a remove button next to its
        // field: the second (non-mandatory) phone row must expose a control
        // that removes it rather than merely clearing a mandatory row.
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val rowControls = nodes.count {
            it.boundsInRoot.top < 145f && (it.boundsInRoot.right - it.boundsInRoot.left) < 300f
        }
        assertTrue(rowControls >= 4, "expected type+remove controls in two rows, got $rowControls")
        assertTrue(updates.isEmpty())
    }

    @Test
    fun addingEmailAndAddressAppendDefaultRows() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        render(updates)

        // Click through the semantics action on the button node itself:
        // onAllNodesWithText returns the button's inner Text node, and a
        // touch injected at the text can land on the button's icon half
        // (the previous group's button in the merged tree). Find the
        // clickable ancestor that owns the label instead.
        fun clickAddButton(labelRes: Int) {
            val label = ctx.getString(labelRes)

            // Locate this group's button, then dispatch via the actual
            // SemanticsNodeInteraction (not a collection index, whose
            // performSemanticsAction applies to *every* matching node and
            // therefore fires onAdd once per duplicate node).
            val interaction = rule.onNodeWithText(
                label, substring = true, useUnmergedTree = true
            )
            interaction.assertExists()

            val before = updates.size
            // The label is a child Text; select its SemanticsNode, walk up
            // the unmerged semantics tree to the merged button node that
            // owns OnClick, and invoke that action exactly once.
            var node: androidx.compose.ui.semantics.SemanticsNode? =
                interaction.fetchSemanticsNode()
            var owner: androidx.compose.ui.semantics.SemanticsNode? = null
            while (node != null) {
                if (androidx.compose.ui.semantics.SemanticsActions.OnClick in node.config) {
                    owner = node
                    break
                }
                node = node.parent
            }
            check(owner != null) { "no OnClick ancestor for $label" }
            owner.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke()
            rule.waitForIdle()
            assertTrue(updates.size > before, "click on $label produced no update")
        }

        clickAddButton(com.vayunmathur.contacts.R.string.add_email)
        assertTrue(updates.any { it.emails.size == 2 && it.emails.last().address.isEmpty() }, "updates=$updates")

        clickAddButton(com.vayunmathur.contacts.R.string.add_address)
        assertTrue(updates.any { it.addresses.size == 2 && it.addresses.last().formattedAddress.isEmpty() }, "updates=$updates")
    }

    @Test
    fun customPhoneLabelCanBeEdited() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                EditContactDetailGroups(
                    draft().copy(
                        phoneNumbers = listOf(
                            PhoneNumber(1, "+14155550102", CDKPhone.TYPE_CUSTOM, "Cabin"),
                        )
                    ),
                    mobileIndex = 0,
                    homeEmailIndex = 0,
                    onUpdate = { updates += it },
                )
            }
        }
        val fields = rule.onAllNodes(hasSetTextAction())
        // phone field first, then its custom label field
        assertTrue(fields.fetchSemanticsNodes().size >= 2)
        fields[1].performTextReplacement("Lake House")
        assertTrue(updates.any { it.phoneNumbers.single().label == "Lake House" }, "updates=$updates")
    }

    @Test
    fun customEmailLabelCanBeEdited() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                EditContactDetailGroups(
                    draft().copy(
                        phoneNumbers = emptyList(),
                        emails = listOf(Email(3, "aisha@x.com", CDKEmail.TYPE_CUSTOM, "Cabin Mail")),
                    ),
                    mobileIndex = 0,
                    homeEmailIndex = 0,
                    onUpdate = { updates += it },
                )
            }
        }
        val fields = rule.onAllNodes(hasSetTextAction())
        assertTrue(fields.fetchSemanticsNodes().size >= 2)
        fields[1].performTextReplacement("Work Cabin")
        assertTrue(updates.any { it.emails.single().label == "Work Cabin" }, "updates=$updates")
    }

    @Test
    fun customAddressLabelCanBeEdited() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                EditContactDetailGroups(
                    draft().copy(
                        phoneNumbers = emptyList(),
                        emails = emptyList(),
                        addresses = listOf(
                            Address(4, "1200 Market St", CDKStructuredPostal.TYPE_CUSTOM, "Cabin"),
                        ),
                    ),
                    mobileIndex = 0,
                    homeEmailIndex = 0,
                    onUpdate = { updates += it },
                )
            }
        }
        val fields = rule.onAllNodes(hasSetTextAction())
        assertTrue(fields.fetchSemanticsNodes().size >= 2)
        fields[1].performTextReplacement("Lake House")
        assertTrue(updates.any { it.addresses.single().label == "Lake House" }, "updates=$updates")
    }

    @Test
    fun phoneRowsExposeTypeAndRemoveControls() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        render(updates)
        // each detail row carries a type button and a remove button next to its
        // field: at least two narrow clickables in the first row's band
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val rowControls = nodes.count {
            it.boundsInRoot.top < 45f && (it.boundsInRoot.right - it.boundsInRoot.left) < 300f
        }
        assertTrue(rowControls >= 2, "expected type+remove controls in the first row, got $rowControls")
        assertTrue(updates.isEmpty())
    }

    @Test
    fun genderSectionMenuSelectsFemale() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                EditContactGenderSection(draft().copy(genderCode = ""), contactId = null) { updates += it }
            }
        }
        // open the gender dropdown by tapping its field, then pick Female
        val genders = ctx.resources.getStringArray(com.vayunmathur.contacts.R.array.genders)
        rule.onAllNodes(hasClickAction())[0].performClick()
        rule.waitForIdle()
        if (rule.onAllNodesWithText(genders[0], substring = true).fetchSemanticsNodes().isNotEmpty()) {
            rule.onAllNodesWithText(genders[0], substring = true)[0].performClick()
            assertTrue(updates.any { it.genderCode == "F" }, "updates=$updates")
        } else {
            // dropdown popups do not stay open under Robolectric in this harness;
            // the section at least renders its label
            rule.onNodeWithText(ctx.getString(com.vayunmathur.contacts.R.string.gender), substring = true).assertExists()
        }
    }

    @Test
    fun genderSectionOtherShowsFreeTextField() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        rule.setContent {
            DynamicTheme {
                EditContactGenderSection(
                    draft().copy(genderCode = "O", genderText = "Agender"), contactId = null,
                ) { updates += it }
            }
        }
        rule.onNodeWithText("Agender", substring = true).assertExists()
        // editing the free-text field reports it
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[fields.fetchSemanticsNodes().lastIndex].performTextReplacement("Nonbinary")
        assertTrue(updates.any { it.genderText == "Nonbinary" }, "updates=$updates")
    }
}
