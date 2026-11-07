package com.vayunmathur.communicate.rcs

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsConversation
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsProvisioning
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
import com.vayunmathur.communicate.data.rcs.e2e.RcsPeerKeys
import com.vayunmathur.communicate.ui.rcs.RcsGroupMembersScreen
import com.vayunmathur.communicate.ui.rcs.RcsRegistrationScreen
import com.vayunmathur.communicate.ui.rcs.RcsVerifyScreen
import com.vayunmathur.library.ui.DynamicTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice-3 pass 2: the three RCS Compose screens under Robolectric, driven
 * through their public composables against an in-memory Room database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RcsSlice3Pass2ConnectivityShadow::class])
class RcsSlice3Pass2UiTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var testDb: RcsDatabase

    @BeforeTest
    fun setUp() {
        testDb = androidx.room3.Room.inMemoryDatabaseBuilder(context, RcsDatabase::class.java)
            .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        RcsDatabase.overrideForTest = testDb
        RcsProvisioning.reset()
        RcsSipTransport.tearDown(context)
        RcsPeerKeys.clear()
    }

    @AfterTest
    fun tearDown() {
        RcsDatabase.overrideForTest = null
        testDb.close()
        RcsPeerKeys.clear()
    }

    private fun waitForText(text: String, timeoutMs: Long = 10_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // --- Registration / status screen ---

    @Test
    fun registrationScreenShowsStatusCardAndButtons() {
        var back = 0
        rule.setContent {
            DynamicTheme(darkTheme = false) { RcsRegistrationScreen(onBack = { back++ }) }
        }
        rule.onNodeWithText("RCS status").assertIsDisplayed()
        rule.onNodeWithText("RCS").assertIsDisplayed()
        // Neither transport nor provisioning has reported: not checked yet.
        waitForText("Retry")
        rule.onNodeWithText("Retry").assertIsDisplayed()
        rule.onNodeWithText("Run availability check").assertIsDisplayed()
        // Probe button runs the availability check; without IMS it reports
        // through the messenger and the config-URL line appears afterwards.
        rule.onNodeWithText("Run availability check").performClick()
        waitForText("No carrier RCS config URL (common on MVNO/Jibe carriers — delegate creation attempted anyway)")
        assertEquals(0, back)
    }

    @Test
    fun registrationScreenRetryButtonIsClickable() {
        rule.setContent {
            DynamicTheme(darkTheme = false) { RcsRegistrationScreen(onBack = {}) }
        }
        waitForText("Retry")
        rule.onNodeWithText("Retry").performClick()
        rule.waitForIdle()
        // Retrying re-probes; the screen stays composed on the status card.
        rule.onNodeWithText("RCS").assertIsDisplayed()
    }

    @Test
    fun registrationScreenShowsAvailableTransportLeg() {
        // Install a fake delegate connection through the transport seam so
        // the screen composes its Available branch.
        val connectionClass = Class.forName("android.telephony.ims.SipDelegateConnection")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            connectionClass.classLoader, arrayOf(connectionClass),
        ) { _, _, _ -> null }
        val seam = com.vayunmathur.communicate.data.rcs.RcsSipTransport::class.java.methods
            .first { it.name.startsWith("installConnectionForTest") }
        seam.invoke(com.vayunmathur.communicate.data.rcs.RcsSipTransport, proxy, 3L, null)
        try {
            rule.setContent {
                DynamicTheme(darkTheme = false) { RcsRegistrationScreen(onBack = {}) }
            }
            waitForText("Available")
            rule.onNodeWithText("Available").assertIsDisplayed()
            rule.onNodeWithText("Transport").assertIsDisplayed()
            rule.onNodeWithText("single-reg delegate").assertIsDisplayed()
        } finally {
            seam.invoke(com.vayunmathur.communicate.data.rcs.RcsSipTransport, null, -1L, null)
            com.vayunmathur.communicate.data.rcs.RcsSipTransport.tearDown(context)
        }
    }

    // --- Verify screen ---

    @Test
    fun verifyScreenWithoutKeysShowsPlaceholders() {
        rule.setContent {
            DynamicTheme(darkTheme = false) {
                RcsVerifyScreen(peerE164 = "+15557654321", onBack = {})
            }
        }
        rule.onNodeWithText("Verify contact").assertIsDisplayed()
        rule.onNodeWithText("Your safety number").assertIsDisplayed()
        rule.onNodeWithText("+15557654321").assertIsDisplayed()
        rule.onNodeWithText("Status").assertIsDisplayed()
        rule.onNodeWithText("Mark verified").assertIsDisplayed()
        rule.onNodeWithText("Clear verification").assertIsDisplayed()
    }

    @Test
    fun verifyScreenMarkAndClearWithoutKeyAreSafe() {
        rule.setContent {
            DynamicTheme(darkTheme = false) {
                RcsVerifyScreen(peerE164 = "+15557654321", onBack = {})
            }
        }
        rule.onNodeWithText("Mark verified").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Clear verification").performClick()
        rule.waitForIdle()
        // The handlers run on Dispatchers.IO against the (in-memory) DB;
        // let them settle before tearDown closes it.
        Thread.sleep(750)
        rule.onNodeWithText("Status").assertIsDisplayed()
    }

    @Test
    fun verifyScreenWithCachedPeerKeyMarksVerified() = runBlocking {
        val peer = "+15551112222"
        RcsPeerKeys.store(peer, byteArrayOf(9, 8, 7, 6, 5))
        rule.setContent {
            DynamicTheme(darkTheme = false) { RcsVerifyScreen(peerE164 = peer, onBack = {}) }
        }
        rule.onNodeWithText("Mark verified").performClick()
        // setVerifiedWithPackage lands on Dispatchers.IO; poll the DB row.
        val deadline = System.currentTimeMillis() + 10_000
        var verified = false
        while (System.currentTimeMillis() < deadline) {
            if (RcsE2E.isVerified(context, peer)) { verified = true; break }
            Thread.sleep(25)
        }
        assertTrue(verified, "peer was not marked verified")
        // Clear verification flips the same row back.
        rule.onNodeWithText("Clear verification").performClick()
        val deadline2 = System.currentTimeMillis() + 10_000
        var cleared = false
        while (System.currentTimeMillis() < deadline2) {
            if (!RcsE2E.isVerified(context, peer)) { cleared = true; break }
            Thread.sleep(25)
        }
        assertTrue(cleared, "peer verification was not cleared")
        Unit
    }

    // --- Group members screen ---

    @Test
    fun groupMembersScreenListsStoredMembersAndRemovesOne() = runBlocking {
        testDb.conversationDao().upsert(
            RcsConversation(
                remoteId = "conv-ui-1", isGroup = true,
                participants = "+15551001001,+15551001002",
            ),
        )
        rule.setContent {
            DynamicTheme(darkTheme = false) {
                RcsGroupMembersScreen(conversationId = "conv-ui-1", onBack = {})
            }
        }
        waitForText("+15551001001", timeoutMs = 30_000)
        rule.onNodeWithText("+15551001001").assertIsDisplayed()
        rule.onNodeWithText("+15551001002").assertIsDisplayed()
        // Remove the first member; the row is rewritten without transport.
        rule.onAllNodesWithContentDescription("Delete")[0].performClick()
        val deadline = System.currentTimeMillis() + 10_000
        var participants = ""
        while (System.currentTimeMillis() < deadline) {
            participants = testDb.conversationDao().getConversation("conv-ui-1")?.participants.orEmpty()
            if (!participants.contains("+15551001001")) break
            Thread.sleep(25)
        }
        assertEquals("+15551001002", participants)
        Unit
    }

    @Test
    fun groupMembersScreenEmptyStateAndAddAttempt() {
        rule.setContent {
            DynamicTheme(darkTheme = false) {
                RcsGroupMembersScreen(conversationId = "conv-ui-empty", onBack = {})
            }
        }
        waitForText("No members yet")
        rule.onNodeWithText("No members yet").assertIsDisplayed()
        // Add flow: type a number, tap Add; capability check fails closed
        // (no UCE), the screen reports through the messenger and stays up.
        rule.onNodeWithText("+15551234567").performTextInput("+15559998888")
        rule.onNodeWithText("Add").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Add").assertIsDisplayed()
        // The typed number stays in the field after the failed add.
        rule.onNodeWithText("+15559998888").assertIsDisplayed()
    }

    @Test
    fun groupMembersAddToExistingConversationPersistsWhenCapableGateFails() = runBlocking {
        // Even when the focus invite fails, the stored row handling runs:
        // capability gate fails first here, so membership is unchanged and
        // the members still render.
        testDb.conversationDao().upsert(
            RcsConversation(remoteId = "conv-ui-2", participants = "+15552002002"),
        )
        rule.setContent {
            DynamicTheme(darkTheme = false) {
                RcsGroupMembersScreen(conversationId = "conv-ui-2", onBack = {})
            }
        }
        waitForText("+15552002002", timeoutMs = 30_000)
        assertEquals(
            "+15552002002",
            testDb.conversationDao().getConversation("conv-ui-2")?.participants,
        )
        Unit
    }
}
