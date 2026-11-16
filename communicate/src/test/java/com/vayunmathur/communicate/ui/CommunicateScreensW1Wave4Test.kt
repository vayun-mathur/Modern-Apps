package com.vayunmathur.communicate.ui

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.FakeMmsSmsProvider
import com.vayunmathur.communicate.data.FakeTelephonyState
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * W1 wave 4: conversation top-bar actions (call + overflow delete flow),
 * line badges, and date formatting branches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunicateScreensW1Wave4Test {

    @get:Rule
    val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        FakeTelephonyState.reset()
        Robolectric.setupContentProvider(FakeMmsSmsProvider::class.java, "mms-sms")
        shadowOf(context as Application).denyPermissions(Manifest.permission.CALL_PHONE)
    }

    @Test
    fun `conversation actions show call for sim and video for whatsApp`() {
        rule.setContent {
            ConversationActions(
                threadId = 10L, address = "+14155550102", line = CommunicateLine.Sim,
                remoteId = null, isGroup = false, participants = emptyList(),
                groupTitle = null, isUnknownContact = false, onBack = {},
            )
        }
        // Call button + overflow menu (no video on a Telecom line). The
        // dial itself is covered by the repository placeCall tests.
        assertEquals(2, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        rule.onAllNodes(hasClickAction())[0].performClick()
        rule.waitForIdle()
    }

    @Test
    fun `conversation actions show video for whatsApp groups of one`() {
        rule.setContent {
            ConversationActions(
                threadId = 11L, address = "15550002@s.whatsapp.net", line = CommunicateLine.WhatsApp,
                remoteId = "15550002@s.whatsapp.net", isGroup = false, participants = emptyList(),
                groupTitle = null, isUnknownContact = false, onBack = {},
            )
        }
        // Call + video + overflow on the WebRTC line.
        assertEquals(3, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `conversation actions delete flow confirms and backs out`() {
        val backs = CopyOnWriteArrayList<String>()
        FakeTelephonyState.deleteResult = 1
        rule.setContent {
            ConversationActions(
                threadId = 10L, address = "+14155550102", line = CommunicateLine.Sim,
                remoteId = null, isGroup = false, participants = emptyList(),
                groupTitle = null, isUnknownContact = true, onBack = { backs += "back" },
            )
        }
        val clickables = rule.onAllNodes(hasClickAction())
        clickables[clickables.fetchSemanticsNodes().size - 1].performClick() // overflow
        rule.waitForIdle()
        rule.onNodeWithText("Create new contact").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick() // confirm
        // Deletion runs on IO; wait for the back-out callback.
        rule.waitUntil(5_000) { backs.isNotEmpty() }
        assertEquals(listOf("back"), backs.toList())
        assertTrue(FakeTelephonyState.deletes.any { it.toString().contains("conversations/10") })
    }

    @Test
    fun `line badges label every line`() {
        rule.setContent {
            LineBadge(CommunicateLine.GoogleVoice)
            LineBadge(CommunicateLine.WhatsApp)
            LineBadge(CommunicateLine.Signal)
            LineBadge(CommunicateLine.Rcs)
            LineBadge(CommunicateLine.Sim)
        }
        rule.onNodeWithText("WhatsApp").assertIsDisplayed()
        rule.onNodeWithText("Signal").assertIsDisplayed()
        // SIM with <=1 SIM renders no badge; the others carry text labels.
        assertTrue(rule.onAllNodesWithText("RCS").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `formatDateTime covers today week and old branches`() {
        val now = System.currentTimeMillis()
        assertTrue(formatDateTime(context, now).isNotBlank())
        assertTrue(formatDateTime(context, now - 2L * 24 * 3600 * 1000).isNotBlank())
        assertTrue(formatDateTime(context, 1_577_836_800_000L).isNotBlank()) // 2020-01-01
    }

    @Test
    fun `voicemail player renders the transport controls`() {
        var played = 0
        rule.setContent {
            VoicemailPlayer(audioUri = "https://invalid.example/vm.mp3", onPlayed = { played++ })
        }
        // Both the position and duration labels start at 0:00.
        assertEquals(2, rule.onAllNodesWithText("0:00").fetchSemanticsNodes().size)
        assertEquals(0, played)
    }
}
