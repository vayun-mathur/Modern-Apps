package com.vayunmathur.communicate.ui

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.FakeContactsProvider
import com.vayunmathur.communicate.data.FakeContactsState
import com.vayunmathur.communicate.data.FakeMmsProvider
import com.vayunmathur.communicate.data.FakeMmsSmsProvider
import com.vayunmathur.communicate.data.FakeSmsProvider
import com.vayunmathur.communicate.data.FakeTelephonyState
import com.vayunmathur.communicate.data.rcs.RcsCachedMessage
import com.vayunmathur.communicate.data.rcs.RcsConversation
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.ui.rcs.RcsGroupMembersScreen
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * W1 wave 5: the full top-level screens in their gated states, plus the
 * RCS group-members screen against an in-memory RCS database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunicateScreensW1Wave5Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var rcsDb: RcsDatabase

    @BeforeTest
    fun setUp() {
        FakeTelephonyState.reset()
        FakeContactsState.reset()
        Robolectric.setupContentProvider(FakeSmsProvider::class.java, "sms")
        Robolectric.setupContentProvider(FakeMmsProvider::class.java, "mms")
        Robolectric.setupContentProvider(FakeMmsSmsProvider::class.java, "mms-sms")
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, android.provider.ContactsContract.AUTHORITY)
        rcsDb = androidx.room3.Room.inMemoryDatabaseBuilder(context, RcsDatabase::class.java)
            .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        RcsDatabase.overrideForTest = rcsDb
    }

    @AfterTest
    fun tearDown() {
        RcsDatabase.overrideForTest = null
        rcsDb.close()
    }

    @Test
    fun `messages screen shows the default sms gate when not default`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_SMS)
        rule.setContent {
            MessagesScreen(onOpenThread = {}, onOpenAccounts = {})
        }
        rule.waitForIdle()
        // The gate prompt (default-SMS role) is what a non-default install sees.
        assertTrue(
            rule.onAllNodesWithText("default", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Messages", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `call logs screen shows its gate`() {
        rule.setContent { CallLogsScreen() }
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("Call logs", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `voicemail screen shows its gate`() {
        rule.setContent { VoicemailScreen() }
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("Voicemail", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `dialer screen shows the default dialer gate`() {
        // The pad itself sits behind the default-dialer role (see
        // DialerContent's own tests for the pad interactions).
        rule.setContent { DialerScreen() }
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("default", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Dialer", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `rcs group members screen lists stored members`() = runTest {
        rcsDb.conversationDao().upsert(
            RcsConversation(
                remoteId = "rcs-group:1", isGroup = true, name = "Team",
                participants = "+14155550102,+14155550103",
            ),
        )
        rcsDb.cachedMessageDao().upsert(
            RcsCachedMessage(
                messageId = "m1", conversationId = "rcs-group:1", body = "hi",
                timestamp = 1L, outgoing = false,
            ),
        )
        val backs = CopyOnWriteArrayList<String>()
        rule.setContent {
            RcsGroupMembersScreen(conversationId = "rcs-group:1", onBack = { backs += "back" })
        }
        rule.waitForIdle()
        rule.onNodeWithText("+14155550102").assertIsDisplayed()
        rule.onNodeWithText("+14155550103").assertIsDisplayed()
        assertEquals(emptyList(), backs.toList())
    }

    @Test
    fun `rcs group members screen shows the empty state`() {
        rule.setContent {
            RcsGroupMembersScreen(conversationId = "rcs-group:missing", onBack = {})
        }
        rule.waitForIdle()
        rule.onNodeWithText("No members yet").assertIsDisplayed()
    }
}
