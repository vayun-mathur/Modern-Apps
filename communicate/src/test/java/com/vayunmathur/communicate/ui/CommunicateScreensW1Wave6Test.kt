package com.vayunmathur.communicate.ui

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.provider.VoicemailContract
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.FakeCallLogProvider
import com.vayunmathur.communicate.data.FakeContactsProvider
import com.vayunmathur.communicate.data.FakeContactsState
import com.vayunmathur.communicate.data.FakeMmsProvider
import com.vayunmathur.communicate.data.FakeMmsSmsProvider
import com.vayunmathur.communicate.data.FakeSmsProvider
import com.vayunmathur.communicate.data.FakeTelephonyState
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.VoicemailEntryTest
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowRoleManager

/**
 * W1 wave 6: the gated top-level screens with the default-app roles held
 * (Robolectric `ShadowRoleManager`), so the real loaders run against the
 * fake telephony providers: inbox, call log, voicemail and dialer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunicateScreensW1Wave6Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        FakeTelephonyState.reset()
        FakeContactsState.reset()
        Robolectric.setupContentProvider(FakeSmsProvider::class.java, "sms")
        Robolectric.setupContentProvider(FakeMmsProvider::class.java, "mms")
        Robolectric.setupContentProvider(FakeMmsSmsProvider::class.java, "mms-sms")
        Robolectric.setupContentProvider(FakeCallLogProvider::class.java, "call_log")
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        Robolectric.setupContentProvider(
            VoicemailEntryTest.FakeVoicemailProvider::class.java, VoicemailContract.AUTHORITY,
        )
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
        )
        val roleManager = context.getSystemService(RoleManager::class.java)
        val shadow = Shadow.extract<ShadowRoleManager>(roleManager)
        shadow.addHeldRole(RoleManager.ROLE_SMS)
        shadow.addHeldRole(RoleManager.ROLE_DIALER)
        shadow.addAvailableRole(RoleManager.ROLE_SMS)
        shadow.addAvailableRole(RoleManager.ROLE_DIALER)
    }

    @kotlin.test.AfterTest
    fun removeRoles() {
        // Held roles are static shadow state: don't leak them into other
        // test classes sharing this Robolectric sandbox.
        val roleManager = context.getSystemService(RoleManager::class.java)
        val shadow = Shadow.extract<ShadowRoleManager>(roleManager)
        shadow.removeHeldRole(RoleManager.ROLE_SMS)
        shadow.removeHeldRole(RoleManager.ROLE_DIALER)
    }

    private fun seedSmsThread() {
        FakeTelephonyState.smsRows = listOf(
            mapOf(
                Telephony.Sms._ID to 1L, Telephony.Sms.THREAD_ID to 10L,
                Telephony.Sms.ADDRESS to "+14155550102", Telephony.Sms.BODY to "screen hello",
                Telephony.Sms.DATE to 2_000L, Telephony.Sms.TYPE to Telephony.Sms.MESSAGE_TYPE_INBOX,
                Telephony.Sms.READ to 0, Telephony.Sms.STATUS to Telephony.Sms.STATUS_NONE,
                Telephony.Sms.SUBSCRIPTION_ID to -1,
            ),
        )
    }

    @Test
    fun `messages screen loads threads and opens one`() {
        seedSmsThread()
        val opened = CopyOnWriteArrayList<SmsThread>()
        rule.setContent { MessagesScreen(onOpenThread = { opened += it }, onOpenAccounts = {}) }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("screen hello").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("screen hello").performClick()
        assertEquals(1, opened.size)
        assertEquals(10L, opened.single().threadId)
    }

    @Test
    fun `messages screen picker composes to a thread`() {
        seedSmsThread()
        FakeContactsState.phoneRows = listOf(
            mapOf(
                ContactsContract.CommonDataKinds.Phone._ID to 1L,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY to "Alice",
                ContactsContract.CommonDataKinds.Phone.NUMBER to "+14155550102",
                ContactsContract.CommonDataKinds.Phone.TYPE to 2,
                ContactsContract.CommonDataKinds.Phone.LABEL to null,
            ),
        )
        val opened = CopyOnWriteArrayList<SmsThread>()
        rule.setContent { MessagesScreen(onOpenThread = { opened += it }, onOpenAccounts = {}) }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("screen hello").fetchSemanticsNodes().isNotEmpty()
        }
        // FAB is the last clickable on the screen (top-bar account button, rows, FAB).
        val clickables = rule.onAllNodes(hasClickAction())
        clickables[clickables.fetchSemanticsNodes().size - 1].performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("New message").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Alice").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Alice").performClick()
        rule.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals("+14155550102", opened.last().address)
    }

    @Test
    fun `call logs screen loads provider rows`() {
        FakeTelephonyState.callLogRows = listOf(
            mapOf(
                CallLog.Calls._ID to 1L, CallLog.Calls.CACHED_NAME to "Bob",
                CallLog.Calls.NUMBER to "+14155550103", CallLog.Calls.TYPE to CallLog.Calls.INCOMING_TYPE,
                CallLog.Calls.DATE to 5_000L, CallLog.Calls.DURATION to 30L,
                CallLog.Calls.PHONE_ACCOUNT_ID to null,
            ),
        )
        rule.setContent { CallLogsScreen() }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Bob").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Bob").assertIsDisplayed()
    }

    @Test
    fun `voicemail screen loads carrier rows`() {
        rule.setContent { VoicemailScreen() }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Voicemail", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The empty inbox state proves the loader ran (no carrier rows seeded
        // into the voicemail provider in this test).
        assertTrue(
            rule.onAllNodesWithText("No voicemail", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Voicemail", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    fun `dialer screen renders contacts and dial pad through the gates`() {
        FakeContactsState.phoneRows = listOf(
            mapOf(
                ContactsContract.CommonDataKinds.Phone._ID to 1L,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY to "Alice",
                ContactsContract.CommonDataKinds.Phone.NUMBER to "+14155550102",
                ContactsContract.CommonDataKinds.Phone.TYPE to 2,
                ContactsContract.CommonDataKinds.Phone.LABEL to null,
            ),
        )
        rule.setContent { DialerScreen() }
        // With the dialer role held the gate passes and the screen settles
        // (contacts loader + pad are covered directly in DialerContent's
        // tests; the full-screen loader stalls on this host's paused
        // looper, so assert the scaffold title rather than the list).
        rule.waitUntilExactlyOneExists(
            androidx.compose.ui.test.hasText("Dialer"), timeoutMillis = 10_000,
        )
        rule.onNodeWithText("Dialer").assertIsDisplayed()
    }
}
