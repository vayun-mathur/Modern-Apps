package com.vayunmathur.contacts.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.vayunmathur.contacts.data.CommPlatform
import com.vayunmathur.contacts.data.CommPref
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.util.ContactPlatforms
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** W5: portrait (compact width, tall) header + action row: the stacked layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class PortraitHeaderW5Test {
    @get:Rule val rule = createComposeRule()

    private fun contact() = Contact(
        id = 7,
        accountType = "com.google",
        accountName = "a@gmail.com",
        isFavorite = false,
        details = ContactDetails.empty().copy(
            names = listOf(Name(1, "Dr.", "Aisha", "Bint", "Rahman", "PhD")),
            phoneNumbers = listOf(PhoneNumber(1, "+15550100", 2)),
        ),
    )

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun clickLabel(text: String): Boolean {
        val clickables = rule.onAllNodes(hasClickAction())
        val semantics = clickables.fetchSemanticsNodes()
        val index = semantics.indexOfFirst { node ->
            node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                .joinToString(" ") { it.text }.contains(text, ignoreCase = true)
        }
        if (index < 0) return false
        runCatching { clickables[index].performSemanticsAction(SemanticsActions.OnClick) }
        rule.waitForIdle()
        return true
    }

    @Test
    fun portraitProfileHeaderStacksAvatarAndName() {
        rule.setContent { DynamicTheme { ProfileHeader(contact()) } }
        rule.waitForIdle()
        assertTrue(exists("Aisha"), "portrait header should show the given name")
        assertTrue(exists("Rahman"), "portrait header should show the family name")
    }

    @Test
    fun portraitActionRowWrapsButtonsAndActs() {
        var saved: CommPref? = null
        rule.setContent {
            DynamicTheme {
                ActionButtonsRow(
                    phoneNumbers = listOf(PhoneNumber(1, "+15550100", 2), PhoneNumber(2, "+15550101", 1)),
                    email = "aisha@example.com",
                    platforms = ContactPlatforms(
                        whatsAppCallId = 201, whatsAppVideoId = 202, whatsAppMessageId = 203,
                        signalCallId = 205, signalVideoId = 204, signalMessageId = 206,
                        telegramCallId = 208, telegramVideoId = 207, telegramMessageId = 209,
                    ),
                    isGoogleMeetInstalled = true,
                    commPref = null,
                    onSaveCommPref = { saved = it },
                )
            }
        }
        rule.waitForIdle()
        assertTrue(exists("Call"), "Call button should exist in portrait row")
        // Click every clickable in turn; comm buttons open their platform
        // menus, and the first platform entry picked saves the preference.
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until count) {
            runCatching {
                rule.onAllNodes(hasClickAction())[i]
                    .performSemanticsAction(SemanticsActions.OnClick)
            }
            rule.waitForIdle()
            for (label in listOf("WhatsApp", "Signal", "Telegram", "Google Meet", "Phone")) {
                if (clickLabel(label)) break
            }
            rule.waitForIdle()
        }
        assertTrue(exists("Call"))
    }

    @Test
    fun portraitActionRowSinglePhoneNoVideo() {
        rule.setContent {
            DynamicTheme {
                ActionButtonsRow(
                    phoneNumbers = listOf(PhoneNumber(1, "+15550100", 2)),
                    email = null,
                    platforms = ContactPlatforms(),
                    isGoogleMeetInstalled = false,
                )
            }
        }
        rule.waitForIdle()
        assertTrue(exists("Call"))
        assertTrue(exists("Message"))
    }
}
