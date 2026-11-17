package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** W5: FormDetailGroup callbacks through EditContactDetailGroups — type, add, remove, clear. */
@RunWith(RobolectricTestRunner::class)
class DetailGroupInteractionsW5Test {
    @get:Rule val rule = createComposeRule()

    // Snapshot state, as in the real editor: updates must recompose the form
    // or the next callback derives from the stale draft and overwrites it.
    private var current by androidx.compose.runtime.mutableStateOf(initialDraft())

    private fun initialDraft() = ContactViewModel.ContactDraft(
        firstName = "Aisha", lastName = "Rahman",
        phoneNumbers = listOf(
            PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE),
            PhoneNumber(2, "+442071234567", CDKPhone.TYPE_WORK),
        ),
        emails = listOf(
            Email(3, "aisha@x.com", CDKEmail.TYPE_HOME),
            Email(4, "work@x.com", CDKEmail.TYPE_WORK),
        ),
        addresses = listOf(
            Address(5, "1200 Market St", CDKStructuredPostal.TYPE_HOME),
            Address(6, "1 Work Plaza", CDKStructuredPostal.TYPE_WORK),
        ),
    )
    private val updates = mutableListOf<ContactViewModel.ContactDraft>()

    private fun render(isSim: Boolean = false) {
        rule.setContent {
            DynamicTheme {
                Column {
                    EditContactDetailGroups(
                        current, mobileIndex = 0, homeEmailIndex = 0,
                        onUpdate = { current = it; updates += it },
                        isSimAccount = isSim,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun clickButtonWithText(text: String): Boolean {
        val nodes = rule.onAllNodesWithText(text, substring = true)
        if (nodes.fetchSemanticsNodes().isEmpty()) return false
        // Semantics action, not a touch: several rows sit below the fold.
        runCatching { nodes[0].performSemanticsAction(SemanticsActions.OnClick) }
        rule.waitForIdle()
        return true
    }

    private fun clickMenuOption(text: String): Boolean {
        // The popup root is added after the form, so the menu item is the
        // last clickable carrying this label (form type buttons reuse them).
        val clickables = rule.onAllNodes(hasClickAction() and hasText(text, substring = true))
        if (clickables.fetchSemanticsNodes().isEmpty()) return false
        runCatching {
            clickables[clickables.fetchSemanticsNodes().lastIndex]
                .performSemanticsAction(SemanticsActions.OnClick)
        }
        rule.waitForIdle()
        return true
    }

    @Test
    fun phoneTypeChangeThroughDropdown() {
        render()
        // The type button shows the current type label; open it and pick another.
        assertTrue(clickButtonWithText("Mobile") || clickButtonWithText("Work"))
        val options = listOf("Other", "Custom", "Home", "Work", "Mobile")
        var picked = false
        for (option in options) {
            if (clickMenuOption(option)) { picked = true; break }
        }
        assertTrue(picked, "a type option should be selectable")
        assertTrue(updates.isNotEmpty(), "type change should emit an update, updates=$updates")
    }

    @Test
    fun addButtonsAppendRowsForAllThreeGroups() {
        render()
        val beforePhones = current.phoneNumbers.size
        val beforeEmails = current.emails.size
        val beforeAddresses = current.addresses.size
        clickButtonWithText("Add phone")
        clickButtonWithText("Add email")
        clickButtonWithText("Add address")
        assertEquals(beforePhones + 1, current.phoneNumbers.size)
        assertEquals(beforeEmails + 1, current.emails.size)
        assertEquals(beforeAddresses + 1, current.addresses.size)
    }

    @Test
    fun removeNonMandatoryRowAndClearMandatoryRow() {
        render()
        // Click every remove/clear icon button in the second phone row area:
        // icon buttons carry no text, so walk clickable nodes and watch sizes.
        val initialPhones = current.phoneNumbers.size
        val clickables = rule.onAllNodes(hasClickAction())
        val count = clickables.fetchSemanticsNodes().size
        for (i in 0 until count) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performSemanticsAction(SemanticsActions.OnClick) }
            rule.waitForIdle()
            if (current.phoneNumbers.size < initialPhones) break
            // A dropdown may have opened; dismiss by picking nothing (re-render state kept).
        }
        assertTrue(
            current.phoneNumbers.size < initialPhones || current.phoneNumbers[0].value.isEmpty(),
            "a remove or clear should change the phone list, current=${current.phoneNumbers}",
        )
    }

    @Test
    fun customTypeRowsShowLabelFields() {
        current = current.copy(
            phoneNumbers = listOf(PhoneNumber(1, "+14155550102", CDKPhone.TYPE_CUSTOM, "Club")),
            emails = listOf(Email(3, "aisha@x.com", CDKEmail.TYPE_CUSTOM, "Alt")),
            addresses = listOf(Address(5, "1200 Market St", CDKStructuredPostal.TYPE_CUSTOM, "Cabin")),
        )
        render()
        assertTrue(
            rule.onAllNodesWithText("Custom label", substring = true).fetchSemanticsNodes().size >= 1,
            "custom rows should show label fields",
        )
        assertTrue(
            rule.onAllNodesWithText("Club", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
    }
}
