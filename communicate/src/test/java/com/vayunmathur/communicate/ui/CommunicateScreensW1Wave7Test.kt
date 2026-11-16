package com.vayunmathur.communicate.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.Telephony
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.FakeMmsProvider
import com.vayunmathur.communicate.data.FakeMmsSmsProvider
import com.vayunmathur.communicate.data.FakeSmsProvider
import com.vayunmathur.communicate.data.FakeTelephonyState
import com.vayunmathur.communicate.data.SmsMessage
import com.vayunmathur.communicate.data.W1SmsManagerShadow
import com.vayunmathur.communicate.data.rcs.RcsConversation
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
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
 * W1 wave 7: the full conversation screen on the SIM line (load, render,
 * send through the repository), attachment bubbles, and RCS group-member
 * add/remove against an in-memory RCS database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [W1SmsManagerShadow::class])
class CommunicateScreensW1Wave7Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var rcsDb: RcsDatabase

    @BeforeTest
    fun setUp() {
        FakeTelephonyState.reset()
        Robolectric.setupContentProvider(FakeSmsProvider::class.java, "sms")
        Robolectric.setupContentProvider(FakeMmsProvider::class.java, "mms")
        Robolectric.setupContentProvider(FakeMmsSmsProvider::class.java, "mms-sms")
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
        )
        FakeTelephonyState.smsRows = listOf(
            mapOf(
                Telephony.Sms._ID to 1L, Telephony.Sms.THREAD_ID to 10L,
                Telephony.Sms.ADDRESS to "+14155550102", Telephony.Sms.BODY to "thread hello",
                Telephony.Sms.DATE to 2_000L, Telephony.Sms.TYPE to Telephony.Sms.MESSAGE_TYPE_INBOX,
                Telephony.Sms.READ to 1, Telephony.Sms.STATUS to Telephony.Sms.STATUS_NONE,
                Telephony.Sms.SUBSCRIPTION_ID to -1,
            ),
            mapOf(
                Telephony.Sms._ID to 2L, Telephony.Sms.THREAD_ID to 10L,
                Telephony.Sms.ADDRESS to "+14155550102", Telephony.Sms.BODY to "my reply",
                Telephony.Sms.DATE to 3_000L, Telephony.Sms.TYPE to Telephony.Sms.MESSAGE_TYPE_SENT,
                Telephony.Sms.READ to 1, Telephony.Sms.STATUS to Telephony.Sms.STATUS_COMPLETE,
                Telephony.Sms.SUBSCRIPTION_ID to -1,
            ),
        )
        val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
        val shadowRoles = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowRoleManager>(roleManager)
        shadowRoles.addHeldRole(android.app.role.RoleManager.ROLE_SMS)
        shadowRoles.addHeldRole(android.app.role.RoleManager.ROLE_DIALER)
        rcsDb = androidx.room3.Room.inMemoryDatabaseBuilder(context, RcsDatabase::class.java)
            .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        RcsDatabase.overrideForTest = rcsDb
    }

    @AfterTest
    fun tearDown() {
        val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
        val shadowRoles = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowRoleManager>(roleManager)
        shadowRoles.removeHeldRole(android.app.role.RoleManager.ROLE_SMS)
        shadowRoles.removeHeldRole(android.app.role.RoleManager.ROLE_DIALER)
        RcsDatabase.overrideForTest = null
        rcsDb.close()
    }

    @Test
    fun `conversation screen loads messages and sends a reply`() {
        val backs = CopyOnWriteArrayList<String>()
        rule.setContent {
            ConversationScreen(
                threadId = 10L, address = "+14155550102", line = CommunicateLine.Sim,
                remoteId = null, onBack = { backs += "back" },
            )
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("thread hello").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("thread hello").assertIsDisplayed()
        rule.onNodeWithText("my reply").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextInput("sent from test")
        rule.waitForIdle()
        // Send button is the last clickable in the compose row area.
        val clickables = rule.onAllNodes(hasClickAction())
        clickables[clickables.fetchSemanticsNodes().size - 1].performClick()
        rule.waitUntil(5_000) {
            FakeTelephonyState.inserts.any { it.first.toString().contains("sms/sent") }
        }
        val sent = FakeTelephonyState.inserts.first { it.first.toString().contains("sms/sent") }
        assertEquals("sent from test", sent.second?.getAsString(Telephony.Sms.BODY))
        assertEquals(emptyList(), backs.toList())
    }

    @Test
    fun `conversation screen shows the rcs encryption state area without crashing`() {
        rule.setContent {
            ConversationScreen(
                threadId = 20L, address = "+14155550102", line = CommunicateLine.Rcs,
                remoteId = "+14155550102", onBack = {},
            )
        }
        rule.waitForIdle()
        // No MLS group on this host: no banner, but the screen + compose row render.
        assertTrue(rule.onAllNodesWithText("Message", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `bubble renders document and image attachment fallbacks`() {
        val doc = SmsMessage(
            id = 1L, threadId = 1L, address = "+14155550102", body = "with files",
            timestampMillis = 1_785_566_000_000L, outgoing = false, read = true,
            attachments = listOf(
                CommunicateAttachment("content://mms/part/52", "application/pdf", "doc.pdf"),
                CommunicateAttachment("content://missing/img", "image/png", "img.png"),
            ),
        )
        rule.setContent { MessageBubble(doc) }
        rule.onNodeWithText("with files").assertIsDisplayed()
        rule.onNodeWithText("application/pdf").assertIsDisplayed()
    }

    @Test
    fun `rcs members add fails closed without capability`() = kotlinx.coroutines.test.runTest {
        rcsDb.conversationDao().upsert(
            RcsConversation(
                remoteId = "rcs-group:1", isGroup = true, name = "Team",
                participants = "+14155550102",
            ),
        )
        rule.setContent {
            com.vayunmathur.communicate.ui.rcs.RcsGroupMembersScreen(
                conversationId = "rcs-group:1", onBack = {},
            )
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("+14155550102").fetchSemanticsNodes().isNotEmpty()
        }
        // Adding a member on a host with no RCS capability fails closed:
        // the stored participant list is unchanged.
        rule.onNode(hasSetTextAction()).performTextInput("+14155550109")
        rule.onNodeWithText("Add").performClick()
        rule.waitForIdle()
        Thread.sleep(500)
        rule.waitForIdle()
        assertEquals(
            "+14155550102",
            kotlinx.coroutines.runBlocking {
                rcsDb.conversationDao().getConversation("rcs-group:1")!!.participants
            },
        )
    }
}
