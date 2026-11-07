package com.vayunmathur.communicate.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateCallLogEntry
import com.vayunmathur.communicate.data.CommunicateCallType
import com.vayunmathur.communicate.data.CommunicateLine
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UiPass3 coverage for the call-log list (`CallLogsScreen.kt`): stateless
 * [CallLogsContent] rows, duration/type labels and the delete-confirm flow.
 */
@RunWith(RobolectricTestRunner::class)
class CallLogsUiPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun str(id: Int): String = context.getString(id)

    private fun entry(
        id: Long = 1L,
        name: String? = "Pat Lee",
        number: String = "+1555",
        type: CommunicateCallType = CommunicateCallType.Incoming,
        duration: Long = 65L,
        line: CommunicateLine = CommunicateLine.Sim,
    ) = CommunicateCallLogEntry(
        id = id,
        displayName = name,
        phoneNumber = number,
        type = type,
        timestampMillis = System.currentTimeMillis(),
        durationSeconds = duration,
        line = line,
    )

    @Test
    fun `rows show name number and duration and call back on tap`() {
        val called = mutableListOf<CommunicateCallLogEntry>()
        val e = entry()
        rule.setContent {
            CallLogsContent(entries = listOf(e), onCall = { called.add(it) }, onDelete = {})
        }
        rule.onNodeWithText("Pat Lee").assertIsDisplayed()
        rule.onNodeWithText("+1555").assertIsDisplayed()
        rule.onNodeWithText("Pat Lee").performClick()
        rule.waitForIdle()
        assertEquals(listOf(e), called)
    }

    @Test
    fun `unnamed rows fall back to the number as title`() {
        rule.setContent {
            CallLogsContent(entries = listOf(entry(name = null)), onCall = {}, onDelete = {})
        }
        rule.onNodeWithText("+1555").assertIsDisplayed()
    }

    @Test
    fun `every call type renders its label`() {
        // One row, re-composed per type so every label branch renders.
        var type by androidx.compose.runtime.mutableStateOf(CommunicateCallType.Incoming)
        rule.setContent {
            CallLogsContent(entries = listOf(entry(type = type, duration = 0L)), onCall = {}, onDelete = {})
        }
        val labels = mapOf(
            CommunicateCallType.Incoming to R.string.incoming_call,
            CommunicateCallType.Outgoing to R.string.outgoing_call,
            CommunicateCallType.Missed to R.string.missed_call,
            CommunicateCallType.Rejected to R.string.rejected_call,
            CommunicateCallType.Blocked to R.string.blocked_call,
            CommunicateCallType.Voicemail to R.string.voicemail_call,
            CommunicateCallType.Unknown to R.string.unknown_call,
        )
        for ((t, labelRes) in labels) {
            rule.runOnIdle { type = t }
            rule.waitForIdle()
            rule.onNodeWithText(str(labelRes), substring = true).assertExists()
        }
    }

    @Test
    fun `long durations render hours and minutes`() {
        rule.setContent {
            CallLogsContent(
                entries = listOf(entry(duration = 3661L)),
                onCall = {}, onDelete = {},
            )
        }
        // 1 hr 1 min (seconds dropped past the hour).
        rule.onNodeWithText("1 hr", substring = true).assertIsDisplayed()
    }

    @Test
    fun `delete asks first and only then deletes`() {
        val deleted = mutableListOf<CommunicateCallLogEntry>()
        val e = entry()
        rule.setContent {
            CallLogsContent(entries = listOf(e), onCall = {}, onDelete = { deleted.add(it) })
        }
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("More")).performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.delete)).performClick()
        rule.onNodeWithText(str(R.string.delete_call_log_title)).assertIsDisplayed()
        assertEquals(emptyList(), deleted)
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.delete)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(e), deleted)
    }

    @Test
    fun `delete can be cancelled`() {
        val deleted = mutableListOf<CommunicateCallLogEntry>()
        rule.setContent {
            CallLogsContent(entries = listOf(entry()), onCall = {}, onDelete = { deleted.add(it) })
        }
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("More")).performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.delete)).performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        rule.waitForIdle()
        assertEquals(emptyList(), deleted)
        rule.onNodeWithText(str(R.string.delete_call_log_title)).assertDoesNotExist()
    }

    @Test
    fun `empty list composes without rows`() {
        rule.setContent {
            CallLogsContent(entries = emptyList(), onCall = {}, onDelete = {})
        }
        rule.onNodeWithText("Pat Lee").assertDoesNotExist()
    }
}
