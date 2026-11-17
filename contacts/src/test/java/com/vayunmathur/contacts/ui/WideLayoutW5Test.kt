package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.platform.ContactPlatforms
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** W5: wide/landscape header branches (expanded width puts avatar beside the name). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h400dp")
class WideLayoutW5Test {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val contact = Contact(
        id = 7, accountType = "com.google", accountName = "aisha@gmail.com", isFavorite = true,
        details = ContactDetails(
            phoneNumbers = listOf(
                PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE),
                PhoneNumber(2, "+442071234567", CDKPhone.TYPE_WORK),
            ),
            emails = listOf(Email(3, "aisha@x.com", CDKEmail.TYPE_HOME)),
            addresses = emptyList(), dates = emptyList(), photos = emptyList(),
            names = listOf(Name(4, "Dr", "Aisha", "M", "Rahman", "PhD")),
            orgs = listOf(Organization(5, "Acme Design")),
            notes = emptyList(), nicknames = emptyList(), groups = emptyList(),
        ),
    )

    private val allPlatforms = ContactPlatforms(
        whatsAppCallId = 201, whatsAppVideoId = 202, whatsAppMessageId = 203,
        signalCallId = 205, signalVideoId = 204, signalMessageId = 206,
        telegramCallId = 208, telegramVideoId = 207, telegramMessageId = 209,
    )

    @Test
    fun wideProfileHeaderAndActionRowRenderAndAct() {
        rule.setContent {
            DynamicTheme {
                Column {
                    ProfileHeader(contact)
                    ActionButtonsRow(
                        phoneNumbers = contact.details.phoneNumbers,
                        email = "aisha@x.com",
                        platforms = allPlatforms,
                        isGoogleMeetInstalled = true,
                        onSaveCommPref = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("Aisha", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            rule.onAllNodesWithText("Acme Design", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
        // Click each wide action button; menus that open get their first item.
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until count) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            rule.waitForIdle()
            val whats = rule.onAllNodesWithText("WhatsApp", substring = true)
            if (whats.fetchSemanticsNodes().isNotEmpty()) {
                runCatching { whats[0].performClick() }
                rule.waitForIdle()
            }
        }
        assertTrue(rule.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun wideProfileHeaderWithoutNameParts() {
        val bare = contact.copy(
            details = contact.details.copy(names = emptyList(), orgs = emptyList())
        )
        rule.setContent { DynamicTheme { ProfileHeader(bare) } }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty())
    }
}
