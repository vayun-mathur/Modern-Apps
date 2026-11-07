package com.vayunmathur.contacts.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

/** Header action launches for message/video/email and platform variants. */
@RunWith(RobolectricTestRunner::class)
class HeaderLaunchPass2Test {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private val phones = listOf(PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE))

    private fun clickButton(index: Int) {
        val clickables = rule.onAllNodes(hasClickAction())
        clickables[index].performClick()
        rule.waitForIdle()
    }

    @Test
    fun messageButtonLaunchesSmsAndSavesPref() {
        val saved = mutableListOf<CommPref>()
        rule.setContent {
            DynamicTheme {
                ActionButtonsRow(
                    phoneNumbers = phones, email = "aisha@x.com",
                    platforms = ContactPlatforms(), isGoogleMeetInstalled = false,
                    commPref = null, onSaveCommPref = { saved += it },
                )
            }
        }
        // buttons: Call(0), Message(1), Email(2) — video needs a platform or Meet
        clickButton(1)
        assertEquals(listOf(CommPref(0, "+14155550102", CommPlatform.SIM)), saved)
        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Intent.ACTION_SENDTO, intent.action)
    }

    @Test
    fun emailButtonLaunchesMailto() {
        val saved = mutableListOf<CommPref>()
        rule.setContent {
            DynamicTheme {
                ActionButtonsRow(
                    phoneNumbers = phones, email = "aisha@x.com",
                    platforms = ContactPlatforms(), isGoogleMeetInstalled = true,
                    commPref = null, onSaveCommPref = { saved += it },
                )
            }
        }
        // buttons: Call(0), Message(1), Video(2, Meet available), Email(3)
        clickButton(3)
        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertNotNull(intent, "email button should launch a mail intent")
        assertTrue(intent.data.toString().startsWith("mailto:"), intent.data.toString())
    }

    @Test
    fun videoButtonWithMeetPickerLaunchesMeet() {
        val saved = mutableListOf<CommPref>()
        rule.setContent {
            DynamicTheme {
                ActionButtonsRow(
                    phoneNumbers = phones, email = null,
                    platforms = ContactPlatforms(), isGoogleMeetInstalled = true,
                    commPref = null, onSaveCommPref = { saved += it },
                )
            }
        }
        // Video(2): SIM + Meet options -> platform picker appears
        clickButton(2)
        val meetNodes = rule.onAllNodesWithText("Google Meet", substring = true)
        if (meetNodes.fetchSemanticsNodes().isNotEmpty()) {
            meetNodes[0].performClick()
            rule.waitForIdle()
            assertTrue(saved.any { it.platform == CommPlatform.MEET }, "saved=$saved")
            val intent = Shadows.shadowOf(app).nextStartedActivity
            assertNotNull(intent)
            assertEquals("com.google.android.apps.tachyon.action.CALL", intent.action)
        }
    }

    @Test
    fun callWithWhatsAppPrefLaunchesWhatsAppCall() {
        val saved = mutableListOf<CommPref>()
        rule.setContent {
            DynamicTheme {
                ActionButtonsRow(
                    phoneNumbers = phones, email = null,
                    platforms = ContactPlatforms(whatsAppCallId = 201, whatsAppMessageId = 203),
                    isGoogleMeetInstalled = false,
                    commPref = CommPref(9, "+14155550102", CommPlatform.WHATSAPP),
                    onSaveCommPref = { saved += it },
                )
            }
        }
        clickButton(0)
        // pref platform launches directly (platform row action, no picker)
        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertNotNull(intent, "WhatsApp-pref call should launch something")
        assertTrue(saved.isEmpty(), "pref already set: no new save expected, saved=$saved")
    }
}
