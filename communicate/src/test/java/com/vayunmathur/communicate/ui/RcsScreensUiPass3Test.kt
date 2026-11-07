package com.vayunmathur.communicate.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UiPass3 coverage for the RCS status + safety-number screens. No carrier
 * is provisioned under Robolectric, so both screens must render their
 * honest "not checked / no key yet" states without touching the network.
 */
@RunWith(RobolectricTestRunner::class)
class RcsScreensUiPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun str(id: Int): String = context.getString(id)

    private lateinit var testDb: RcsDatabase

    @BeforeTest
    fun setUp() {
        testDb = androidx.room3.Room.inMemoryDatabaseBuilder(context, RcsDatabase::class.java)
            .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        RcsDatabase.overrideForTest = testDb
    }

    @AfterTest
    fun tearDown() {
        RcsDatabase.overrideForTest = null
        testDb.close()
    }

    @Test
    fun `rcs status screen renders the unchecked state`() {
        rule.setContent {
            com.vayunmathur.communicate.ui.rcs.RcsRegistrationScreen(onBack = {})
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.rcs_status_title)).assertIsDisplayed()
        rule.onNodeWithText("RCS").assertIsDisplayed()
        rule.onNodeWithText("Not checked yet").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.rcs_retry)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.rcs_probe)).assertIsDisplayed()
    }

    @Test
    fun `verify screen shows both parties and the unverified status`() {
        rule.setContent {
            com.vayunmathur.communicate.ui.rcs.RcsVerifyScreen(peerE164 = "+1555", onBack = {})
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.rcs_verify_title)).assertIsDisplayed()
        rule.onNodeWithText("Your safety number").assertIsDisplayed()
        rule.onNodeWithText("+1555").assertIsDisplayed()
        rule.onNodeWithText("Not verified").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.rcs_verify_mark)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.rcs_verify_clear)).assertIsDisplayed()
    }

    @Test
    fun `verify without a peer key reports instead of crashing`() {
        rule.setContent {
            com.vayunmathur.communicate.ui.rcs.RcsVerifyScreen(peerE164 = "+1555", onBack = {})
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.rcs_verify_mark)).performClick()
        rule.waitForIdle()
        // Still on the same screen (the no-key path only shows a message).
        rule.onNodeWithText("Not verified").assertIsDisplayed()
    }

    @Test
    fun `probe reports unavailable without a carrier config`() {
        rule.setContent {
            com.vayunmathur.communicate.ui.rcs.RcsRegistrationScreen(onBack = {})
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.rcs_probe)).performClick()
        rule.waitForIdle()
        // No config URL was ever probed: the diagnostics line says so.
        rule.onNodeWithText("No carrier RCS config URL", substring = true).assertExists()
    }

    @Test
    fun `encryption banner stays hidden without an mls group`() {
        rule.setContent {
            com.vayunmathur.communicate.ui.RcsEncryptionBanner(
                line = com.vayunmathur.communicate.data.CommunicateLine.Rcs,
                remoteId = null, address = "+1555", refresh = 0,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.rcs_encrypted_banner)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.rcs_encrypted_pending)).assertDoesNotExist()
    }

    @Test
    fun `peer typing is false for an idle rcs thread`() {
        var typing: Boolean? = null
        rule.setContent {
            typing = com.vayunmathur.communicate.ui.rememberRcsPeerTyping(
                line = com.vayunmathur.communicate.data.CommunicateLine.Rcs,
                isGroup = false, remoteId = null, address = "+1555",
            )
        }
        rule.waitForIdle()
        assertEquals(false, typing)
    }

    @Test
    fun `group members screen renders its title and empty state`() {
        rule.setContent {
            com.vayunmathur.communicate.ui.rcs.RcsGroupMembersScreen(
                conversationId = "rcs-group:+1555", onBack = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.rcs_group_members_title)).assertIsDisplayed()
        rule.onNodeWithText("No members yet").assertIsDisplayed()
    }

}
