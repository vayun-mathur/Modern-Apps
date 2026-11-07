package com.vayunmathur.communicate.ui

import android.Manifest
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * UiPass3 coverage for the shared communicate UI helpers (`CommunicateUi.kt`,
 * `DialPad.kt`, thread rows): initials/group titles, status ticks, gates,
 * line selector and the dial pad.
 */
@RunWith(RobolectricTestRunner::class)
class CommunicateUiPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun str(id: Int): String = context.getString(id)

    // -- pure helpers -------------------------------------------------------

    @Test
    fun `initials take the first two words`() {
        assertEquals("PL", initialsFor("Pat Lee"))
        assertEquals("P", initialsFor("Pat"))
        assertEquals("PL", initialsFor("pat lee"))
        assertEquals("?", initialsFor(""))
        assertEquals("?", initialsFor("   "))
        assertEquals("15", initialsFor("+1 555"))
        assertEquals("AB", initialsFor("a-b_c"))
    }

    @Test
    fun `group title lists two names then counts the rest`() {
        assertNull(groupTitleFromParticipants(emptyList()))
        assertEquals("Alice", groupTitleFromParticipants(listOf("Alice")))
        assertEquals("Alice, Bob", groupTitleFromParticipants(listOf("Alice", "Bob")))
        assertEquals("Alice, Bob +2", groupTitleFromParticipants(listOf("Alice", "Bob", "Cid", "Dee")))
    }

    @Test
    fun `formatDateTime renders today and old dates`() {
        val now = System.currentTimeMillis()
        assertTrue(formatDateTime(context, now).isNotBlank())
        assertTrue(formatDateTime(context, now - 3L * 24 * 3600 * 1000).isNotBlank())
        assertTrue(formatDateTime(context, now - 40L * 24 * 3600 * 1000).isNotBlank())
    }

    @Test
    fun `permission helpers reflect grants`() {
        assertEquals(false, context.hasContactsPermission())
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
        assertEquals(true, context.hasContactsPermission())
        assertEquals(true, context.hasCommunicatePermission(Manifest.permission.READ_CONTACTS))
    }

    // -- small composables --------------------------------------------------

    @Test
    fun `line tag hides blank and shows text`() {
        var tag by androidx.compose.runtime.mutableStateOf<String?>(null)
        rule.setContent { LineTag(tag) }
        rule.onNodeWithText("SIM 1").assertDoesNotExist()
        rule.runOnIdle { tag = "SIM 1" }
        rule.waitForIdle()
        rule.onNodeWithText("SIM 1").assertIsDisplayed()
        rule.runOnIdle { tag = "  " }
        rule.waitForIdle()
        rule.onNodeWithText("SIM 1").assertDoesNotExist()
    }

    @Test
    fun `thread avatar shows initials or group icon`() {
        var group by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent { ThreadAvatar(title = "Pat Lee", isGroup = group) }
        rule.onNodeWithText("PL").assertIsDisplayed()
        rule.runOnIdle { group = true }
        rule.waitForIdle()
        rule.onNodeWithText("PL").assertDoesNotExist()
    }

    @Test
    fun `unread badge shows the count`() {
        rule.setContent { UnreadBadge(3) }
        rule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `status ticks render per delivery state`() {
        var status by androidx.compose.runtime.mutableStateOf(MessageStatus.None)
        rule.setContent { MessageStatusTicks(status) }
        rule.onNodeWithText("✓").assertDoesNotExist()
        rule.runOnIdle { status = MessageStatus.Failed }
        rule.waitForIdle()
        rule.onNodeWithText("!").assertIsDisplayed()
        rule.runOnIdle { status = MessageStatus.Sent }
        rule.waitForIdle()
        rule.onNodeWithText("✓").assertIsDisplayed()
        rule.runOnIdle { status = MessageStatus.Delivered }
        rule.waitForIdle()
        rule.onNodeWithText("✓✓").assertIsDisplayed()
        rule.runOnIdle { status = MessageStatus.Read }
        rule.waitForIdle()
        rule.onNodeWithText("✓✓").assertIsDisplayed()
    }

    // -- gates ---------------------------------------------------------------

    @Test
    fun `permission gate shows grant prompt when denied`() {
        var contentShown = false
        rule.setContent {
            PermissionGate(permission = Manifest.permission.READ_SMS, message = "Need SMS") {
                contentShown = true
            }
        }
        rule.onNodeWithText(str(R.string.permission_title)).assertIsDisplayed()
        rule.onNodeWithText("Need SMS").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.grant_permission)).assertIsDisplayed()
        assertEquals(false, contentShown)
    }

    @Test
    fun `permission gate shows content when granted`() {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_SMS)
        var revisions = -1
        rule.setContent {
            PermissionGate(permission = Manifest.permission.READ_SMS, message = "Need SMS") { rev ->
                revisions = rev
                androidx.compose.material3.Text("secret content")
            }
        }
        rule.onNodeWithText("secret content").assertIsDisplayed()
        assertEquals(0, revisions)
    }

    @Test
    fun `role gate explains when the role is unavailable`() {
        rule.setContent {
            DefaultSmsGate { androidx.compose.material3.Text("sms home") }
        }
        // Robolectric holds no SMS role: the gate must explain, not show content.
        rule.onNodeWithText(str(R.string.default_app_title)).assertIsDisplayed()
        rule.onNodeWithText("sms home").assertDoesNotExist()
    }

    @Test
    fun `line badges label the vendor lines`() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                LineBadge(CommunicateLine.WhatsApp)
                LineBadge(CommunicateLine.Signal)
                LineBadge(CommunicateLine.GoogleVoice)
            }
        }
        rule.onNodeWithText("WhatsApp").assertIsDisplayed()
        rule.onNodeWithText("Signal").assertIsDisplayed()
        rule.onNodeWithText("Voice").assertIsDisplayed()
    }

    @Test
    fun `line choices fall back to the placeholder sim when signed out`() {
        var choices: List<LineChoice>? = null
        rule.setContent { choices = rememberLineChoices() }
        rule.waitForIdle()
        // No active SIM and no GV/WhatsApp/Signal sign-in: only the SIM fallback.
        assertEquals(
            listOf<LineChoice>(LineChoice.Sim(-1, str(R.string.account_sim))),
            choices,
        )
    }

    // -- line selector -------------------------------------------------------

    @Test
    fun `line selector opens the menu and reports the pick`() {
        val picks = mutableListOf<LineChoice>()
        val choices = listOf(LineChoice.GoogleVoice, LineChoice.WhatsApp, LineChoice.Signal)
        rule.setContent {
            LineSelector(choices = choices, selected = LineChoice.GoogleVoice, onSelect = { picks.add(it) })
        }
        rule.onNodeWithText("Google Voice").assertIsDisplayed()
        rule.onNodeWithText("Google Voice").performClick()
        rule.onNodeWithText("Signal").performClick()
        rule.waitForIdle()
        assertEquals(listOf<LineChoice>(LineChoice.Signal), picks)
    }

    // -- dial pad ------------------------------------------------------------

    @Test
    fun `dial pad appends digits and fires call clear backspace`() {
        val events = mutableListOf<String>()
        rule.setContent {
            DialPad(
                number = "12",
                onAppend = { events.add("append:$it") },
                onBackspace = { events.add("back") },
                onClear = { events.add("clear") },
                onPaste = { events.add("paste") },
                onCall = { events.add("call") },
            )
        }
        rule.onNodeWithText("12").assertIsDisplayed()
        rule.onNodeWithText("3").performClick()
        rule.onNodeWithText("#").performClick()
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("Call")).performClick()
        rule.waitForIdle()
        assertEquals(listOf("append:3", "append:#", "call"), events)
    }

    @Test
    fun `dial pad disables call and clear on an empty number`() {
        rule.setContent {
            DialPad(
                number = "",
                onAppend = {}, onBackspace = {}, onClear = {}, onPaste = {}, onCall = {},
            )
        }
        rule.onNodeWithText(str(R.string.phone_number)).assertIsDisplayed()
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("Call")).assertIsNotEnabled()
        rule.onNodeWithText("Clear").assertIsNotEnabled()
    }

    @Test
    fun `dial pad enables actions once a number exists`() {
        rule.setContent {
            DialPad(
                number = "5",
                onAppend = {}, onBackspace = {}, onClear = {}, onPaste = {}, onCall = {},
            )
        }
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("Call")).assertIsEnabled()
        rule.onNodeWithText("Clear").assertIsEnabled()
    }
}
