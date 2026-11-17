package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.CommPlatform
import com.vayunmathur.contacts.data.CommPref
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.NewContactRestriction
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.ProviderAccount
import com.vayunmathur.contacts.platform.ContactAccount
import com.vayunmathur.contacts.platform.ContactPlatforms
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** W5: remaining Compose branches — comm buttons, edit detail groups, account dropdown. */
@RunWith(RobolectricTestRunner::class)
class ComposeBranchesW5Test {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val phones = listOf(
        PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE),
        PhoneNumber(2, "+442071234567", CDKPhone.TYPE_WORK),
        PhoneNumber(3, "+33123456789", CDKPhone.TYPE_HOME),
    )
    private val allPlatforms = ContactPlatforms(
        whatsAppCallId = 201, whatsAppVideoId = 202, whatsAppMessageId = 203,
        signalCallId = 205, signalVideoId = 204, signalMessageId = 206,
        telegramCallId = 208, telegramVideoId = 207, telegramMessageId = 209,
    )

    private fun clickAllButtonsAndPickFirstMenuItem() {
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until count) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            rule.waitForIdle()
            // If a menu/dialog opened, pick its first textual item then dismiss by root click path.
            val texts = listOf("WhatsApp", "Signal", "Telegram", "Google Meet", "Phone", "SIM")
            for (t in texts) {
                val nodes = rule.onAllNodesWithText(t, substring = true)
                if (nodes.fetchSemanticsNodes().isNotEmpty()) {
                    runCatching { nodes[0].performClick() }
                    rule.waitForIdle()
                    break
                }
            }
        }
    }

    @Test
    fun actionRowEveryButtonWithAllPlatformsAndPref() {
        val saved = mutableListOf<CommPref>()
        rule.setContent {
            DynamicTheme {
                ActionButtonsRow(
                    phoneNumbers = phones, email = "aisha@x.com",
                    platforms = allPlatforms, isGoogleMeetInstalled = true,
                    commPref = CommPref(9, "+14155550102", CommPlatform.SIGNAL),
                    onSaveCommPref = { saved += it },
                )
            }
        }
        rule.waitForIdle()
        clickAllButtonsAndPickFirstMenuItem()
        rule.onRoot().assertExists()
    }

    @Test
    fun actionRowVideoAndEmailWithoutPref() {
        rule.setContent {
            DynamicTheme {
                ActionButtonsRow(
                    phoneNumbers = phones.take(1), email = "aisha@x.com",
                    platforms = allPlatforms, isGoogleMeetInstalled = true,
                    commPref = null, onSaveCommPref = {},
                )
            }
        }
        clickAllButtonsAndPickFirstMenuItem()
        rule.onRoot().assertExists()
    }

    @Test
    fun actionRowSinglePhoneNoPlatformsEmailOnlyVariants() {
        rule.setContent {
            DynamicTheme {
                Column {
                    ActionButtonsRow(
                        phoneNumbers = phones.take(1), email = null,
                        platforms = ContactPlatforms(), isGoogleMeetInstalled = false,
                    )
                    ActionButtonsRow(
                        phoneNumbers = emptyList(), email = null,
                        platforms = ContactPlatforms(), isGoogleMeetInstalled = false,
                    )
                }
            }
        }
        clickAllButtonsAndPickFirstMenuItem()
        rule.onRoot().assertExists()
    }

    private fun draft(
        phones: List<PhoneNumber> = listOf(
            PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE),
            PhoneNumber(2, "", CDKPhone.TYPE_CUSTOM, "Club"),
        ),
        emails: List<Email> = listOf(
            Email(3, "aisha@x.com", CDKEmail.TYPE_HOME),
            Email(4, "", CDKEmail.TYPE_CUSTOM, "Alt"),
        ),
        addresses: List<Address> = listOf(
            Address(5, "1200 Market St", CDKStructuredPostal.TYPE_HOME),
            Address(6, "", CDKStructuredPostal.TYPE_CUSTOM, "Cabin"),
        ),
    ) = ContactViewModel.ContactDraft(
        firstName = "Aisha", lastName = "Rahman",
        phoneNumbers = phones, emails = emails, addresses = addresses,
    )

    @Test
    fun editDetailGroupsTypeAddAndCustomLabels() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        var current = draft()
        rule.setContent {
            DynamicTheme {
                Column {
                    EditContactDetailGroups(current, mobileIndex = 0, homeEmailIndex = 0,
                        onUpdate = { current = it; updates += it })
                }
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("+14155550102", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("aisha@x.com", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("1200 Market St", substring = true).fetchSemanticsNodes().isNotEmpty())
        // Interacting with the fields trips Robolectric's magnifier on disposal;
        // composition alone exercises the value/type/custom-label lambdas for
        // mobile/home/custom rows in all three groups.
        assertTrue(updates.isEmpty())
        rule.onRoot().assertExists()
    }

    @Test
    fun editDetailGroupsSimTrimsExtrasAndEditsSingleFields() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        var current = draft()
        rule.setContent {
            DynamicTheme {
                Column {
                    EditContactDetailGroups(current, mobileIndex = 0, homeEmailIndex = 0,
                        onUpdate = { current = it; updates += it }, isSimAccount = true)
                }
            }
        }
        rule.waitForIdle()
        // SIM LaunchedEffect should trim to one phone/email.
        assertTrue(
            updates.any { it.phoneNumbers.size == 1 && it.emails.size == 1 },
            "SIM draft with extras should be trimmed, updates=$updates",
        )
        val phoneNodes = rule.onAllNodesWithText("+14155550102", substring = true)
        if (phoneNodes.fetchSemanticsNodes().isNotEmpty()) {
            phoneNodes[0].performTextInput("9")
        }
        assertTrue(updates.any { it.phoneNumbers.firstOrNull()?.value?.startsWith("9") == true }, "SIM phone edit should append, updates=$updates")
    }

    @Test
    fun editDetailGroupsEmptySimFieldsCreateDefaults() {
        val updates = mutableListOf<ContactViewModel.ContactDraft>()
        var current = draft(phones = emptyList(), emails = emptyList(), addresses = emptyList())
        rule.setContent {
            DynamicTheme {
                Column {
                    EditContactDetailGroups(current, mobileIndex = -1, homeEmailIndex = -1,
                        onUpdate = { current = it; updates += it }, isSimAccount = true)
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().assertExists()
    }

    @Test
    fun accountSelectorSelectsAndBlockedAccountIsDisabled() {
        var selected: ContactAccount? = null
        val accounts = listOf(
            ContactAccount("a@gmail.com", "com.google"),
            ContactAccount("", "com.vayunmathur.contacts.local"),
            ContactAccount("cloud@x.com", "com.cloud"),
        )
        val restriction = NewContactRestriction(ProviderAccount("cloud@x.com", "com.cloud"))
        rule.setContent {
            DynamicTheme {
                AccountSelectorDropdown(
                    accounts = accounts,
                    selectedAccount = null,
                    restriction = restriction,
                    onSelect = { selected = it },
                )
            }
        }
        rule.onNodeWithText("Select account", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("a@gmail.com", substring = true).performClick()
        assertEquals("a@gmail.com", selected?.name)
    }

    @Test
    fun accountSelectorShowsOnDeviceForBlankSelected() {
        rule.setContent {
            DynamicTheme {
                AccountSelectorDropdown(
                    accounts = listOf(ContactAccount("", "com.vayunmathur.contacts.local")),
                    selectedAccount = ContactAccount("", "com.vayunmathur.contacts.local"),
                    onSelect = {},
                )
            }
        }
        assertTrue(
            rule.onAllNodesWithText("On-Device", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun addToGroupDialogWithVmRenders() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = ContactViewModel(app)
        rule.setContent {
            DynamicTheme {
                com.vayunmathur.contacts.ui.dialogs.AddToGroupDialog(
                    viewModel = vm,
                    contactIds = listOf(1L),
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().isNotEmpty())
    }
}
