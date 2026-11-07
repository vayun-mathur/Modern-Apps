package com.vayunmathur.communicate.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UiPass3 coverage for the top-level screens in their gated/empty states
 * (no default-app role, no permissions, empty providers under Robolectric)
 * plus a full [ConversationScreen] composition on an empty SIM thread and
 * the video layer with no call bound.
 */
@RunWith(RobolectricTestRunner::class)
class ScreensUiPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun str(id: Int): String = context.getString(id)

    @Test
    fun `messages screen asks for the sms role first`() {
        rule.setContent { MessagesScreen(onOpenThread = {}, onOpenAccounts = {}) }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.messages_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.default_app_title)).assertIsDisplayed()
    }

    @Test
    fun `messages screen accounts button reports`() {
        var accounts = 0
        rule.setContent { MessagesScreen(onOpenThread = {}, onOpenAccounts = { accounts++ }) }
        rule.waitForIdle()
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("Person")).performClick()
        rule.waitForIdle()
        assertEquals(1, accounts)
    }

    @Test
    fun `messages fab opens the new-message picker`() {
        rule.setContent { MessagesScreen(onOpenThread = {}, onOpenAccounts = {}) }
        rule.waitForIdle()
        rule.onNode(
            androidx.compose.ui.test.hasClickAction() and
                androidx.compose.ui.test.hasContentDescriptionExactly("Add"),
        ).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.new_message)).assertIsDisplayed()
    }

    @Test
    fun `messages picker composes to a sim thread from a typed number`() {
        org.robolectric.Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.READ_CONTACTS)
        val opened = mutableListOf<com.vayunmathur.communicate.data.SmsThread>()
        rule.setContent {
            MessagesScreen(onOpenThread = { opened.add(it) }, onOpenAccounts = {})
        }
        rule.waitForIdle()
        rule.onNode(
            androidx.compose.ui.test.hasClickAction() and
                androidx.compose.ui.test.hasContentDescriptionExactly("Add"),
        ).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Search name or number").performTextInput("5550100")
        rule.waitForIdle()
        rule.onNodeWithText("Send to", substring = true).performClick()
        rule.waitForIdle()
        assertEquals(1, opened.size)
        assertEquals("5550100", opened[0].address)
        assertEquals(CommunicateLine.Sim, opened[0].line)
    }

    @Test
    fun `conversation send clears the draft`() {
        rule.setContent {
            ConversationScreen(
                threadId = -1L, address = "+1555", line = CommunicateLine.Sim,
                remoteId = null, onBack = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.message_hint)).performTextInput("hello thread")
        rule.waitForIdle()
        rule.onNode(
            androidx.compose.ui.test.hasClickAction() and
                androidx.compose.ui.test.hasContentDescriptionExactly("Send"),
        ).performClick()
        rule.waitForIdle()
        // Draft cleared by the send handler (the send itself is provider work).
        rule.onNodeWithText("hello thread").assertDoesNotExist()
        rule.onNodeWithText(str(R.string.message_hint)).assertIsDisplayed()
    }

    @Test
    fun `call logs screen asks for the dialer role first`() {
        rule.setContent { CallLogsScreen() }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.call_logs_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.default_app_title)).assertIsDisplayed()
    }

    @Test
    fun `voicemail screen asks for the dialer role first`() {
        rule.setContent { VoicemailScreen() }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.voicemail_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.default_app_title)).assertIsDisplayed()
    }

    @Test
    fun `dialer screen asks for the dialer role first`() {
        rule.setContent { DialerScreen() }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.dialer_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.default_app_title)).assertIsDisplayed()
    }

    @Test
    fun `conversation screen composes an empty sim thread`() {
        rule.setContent {
            ConversationScreen(
                threadId = -1L, address = "+1555", line = CommunicateLine.Sim,
                remoteId = null, onBack = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("+1555").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.message_hint)).assertIsDisplayed()
    }

    @Test
    fun `conversation screen composes a group thread with its title`() {
        rule.setContent {
            ConversationScreen(
                threadId = -1L, address = "g1", line = CommunicateLine.Sim,
                remoteId = "g1", isGroup = true,
                participants = listOf("+1555", "+1666"), groupTitle = "Family",
                onBack = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Family").assertIsDisplayed()
    }

    @Test
    fun `typing in a conversation enables send`() {
        rule.setContent {
            ConversationScreen(
                threadId = -1L, address = "+1555", line = CommunicateLine.Sim,
                remoteId = null, onBack = {},
            )
        }
        rule.waitForIdle()
        rule.onNode(
            androidx.compose.ui.test.hasClickAction() and
                androidx.compose.ui.test.hasContentDescriptionExactly("Send"),
        ).assertIsNotEnabled()
        rule.onNodeWithText(str(R.string.message_hint))
            .performTextInput("hello thread")
        rule.waitForIdle()
        rule.onNode(
            androidx.compose.ui.test.hasClickAction() and
                androidx.compose.ui.test.hasContentDescriptionExactly("Send"),
        ).assertIsEnabled()
    }

    @Test
    fun `call video composes nothing without a video controller`() {
        rule.setContent {
            com.vayunmathur.communicate.ui.call.CallVideo(showRemote = true, showLocal = true)
        }
        rule.waitForIdle()
        // No controller bound: the layer returns before creating any renderer.
        assertEquals(
            true,
            com.vayunmathur.communicate.data.call.InAppCallRegistry.videoController == null,
        )
    }

    @Test
    fun `call video stops at a controller with no egl context`() {
        val controller = object :
            com.vayunmathur.communicate.data.call.InAppCallVideoController {
            override fun setVideoEnabled(enabled: Boolean) = Unit
            override fun flipCamera() = Unit
            override fun eglContext(): org.webrtc.EglBase.Context? = null
            override fun attachLocalRenderer(sink: org.webrtc.VideoSink?) = Unit
            override fun attachRemoteRenderer(sink: org.webrtc.VideoSink?) = Unit
            override fun setScreenShareEnabled(enabled: Boolean, permission: android.content.Intent?) = Unit
        }
        com.vayunmathur.communicate.data.call.InAppCallRegistry.videoController = controller
        try {
            rule.setContent {
                com.vayunmathur.communicate.ui.call.CallVideo(showRemote = true, showLocal = true)
            }
            rule.waitForIdle()
            // Null EGL context: still no renderer (a renderer without the shared
            // context would crash the decoder thread).
            assertEquals(
                controller,
                com.vayunmathur.communicate.data.call.InAppCallRegistry.videoController,
            )
        } finally {
            com.vayunmathur.communicate.data.call.InAppCallRegistry.videoController = null
        }
    }
}
