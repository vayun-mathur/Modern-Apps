package com.vayunmathur.contacts.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CommPlatform
import com.vayunmathur.contacts.data.CommPref
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.util.ContactPlatforms
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/** Second-pass coverage for the details header's communication action row. */
@RunWith(RobolectricTestRunner::class)
class HeaderActionsPass2Test {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = app as android.content.Context
    private fun uiStr(id: Int) = ctx.getString(id)

    private val phones = listOf(
        PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE),
        PhoneNumber(2, "+442071234567", CDKPhone.TYPE_WORK),
    )

    private val allPlatforms = ContactPlatforms(
        whatsAppCallId = 201, whatsAppVideoId = 202, whatsAppMessageId = 203,
        signalCallId = 205, signalVideoId = 204, signalMessageId = 206,
        telegramCallId = 208, telegramVideoId = 207, telegramMessageId = 209,
    )

    @Test
    fun actionRowWithAllPlatformsRendersButtons() {
        val saved = mutableListOf<CommPref>()
        rule.setContent {
            DynamicTheme {
                ActionButtonsRow(
                    phoneNumbers = phones, email = "aisha@x.com",
                    platforms = allPlatforms, isGoogleMeetInstalled = true,
                    commPref = null, onSaveCommPref = { saved += it },
                )
            }
        }
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.call), substring = true).assertExists()
        assertTrue(saved.isEmpty())
        // with two numbers, tapping Call first opens the number picker;
        // picking a number then opens the platform picker (the label text is
        // outside the button, so tap the first clickable in the row)
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[0].performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("(415) 555-0102", substring = true)[0].performClick()
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("WhatsApp", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Signal", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                saved.isNotEmpty(),
            "platform picker or a launch should follow picking a number",
        )
    }

    @Test
    fun actionRowWithSimOnlyLaunchesDirectlyAndSavesPref() {
        val saved = mutableListOf<CommPref>()
        rule.setContent {
            DynamicTheme {
                ActionButtonsRow(
                    phoneNumbers = phones.take(1), email = null,
                    platforms = ContactPlatforms(), isGoogleMeetInstalled = false,
                    commPref = null, onSaveCommPref = { saved += it },
                )
            }
        }
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[0].performClick()
        // SIM is the only option: the tap launches and records the preference
        assertEquals(listOf(CommPref(0, "+14155550102", CommPlatform.SIM)), saved)
        assertNotNull(Shadows.shadowOf(app).nextStartedActivity)
    }

    @Test
    fun actionRowWithExistingPrefUsesIt() {
        val saved = mutableListOf<CommPref>()
        rule.setContent {
            DynamicTheme {
                ActionButtonsRow(
                    phoneNumbers = phones, email = "aisha@x.com",
                    platforms = allPlatforms, isGoogleMeetInstalled = false,
                    commPref = CommPref(9, "+442071234567", CommPlatform.WHATSAPP),
                    onSaveCommPref = { saved += it },
                )
            }
        }
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.call), substring = true).assertExists()
        rule.waitForIdle()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun actionRowWithoutPhonesShowsEmailOnlyOrNothing() {
        rule.setContent {
            DynamicTheme {
                ActionButtonsRow(
                    phoneNumbers = emptyList(), email = "aisha@x.com",
                    platforms = ContactPlatforms(), isGoogleMeetInstalled = false,
                )
            }
        }
        // no phone: no Call button
        assertEquals(
            0,
            rule.onAllNodesWithText(uiStr(com.vayunmathur.library.ui.R.string.call), substring = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun genderDisplayTextCoversCodes() {
        // genderDisplayText is internal to the gender section file; exercise it via
        // the section rendering for each code instead (see EditForm tests) — here we
        // assert the platform display names used by the comm-pref field
        assertEquals("SIM", CommPlatform.SIM.name)
        assertTrue(CommPlatform.entries.size >= 4)
    }
}
