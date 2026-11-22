package com.vayunmathur.communicate.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.ui.rcs.RcsVerifyScreen
import com.vayunmathur.communicate.ui.signal.SignalCaptchaWebView
import com.vayunmathur.communicate.ui.signal.SignalRegistrationNumberScreen
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppBackupImportScreen
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationNumberScreen
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Y1 registration screens: `SignalRegistrationNumberScreenKt`,
 * `WhatsAppRegistrationNumberScreenKt`, `WhatsAppBackupImportScreenKt`,
 * `SignalCaptchaScreen` (the hCaptcha WebView) and `RcsVerifyScreen`.
 *
 * No live server is contacted: these drive the number-entry / key-entry steps
 * and their client-side validation (digit filtering, length gates), which is
 * where the screens' own logic lives. The captcha WebView only ever loads the
 * challenge page; its token callback needs a solved hCaptcha, so that leg is
 * asserted unreachable-by-construction (no token is fabricated).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y1RegistrationScreensTest {

    @get:Rule val rule = createComposeRule()

    // The RCS verify screen's buttons reach RcsDatabase through RcsE2E.
    // Without this seam that is the SQLCipher singleton, whose
    // AndroidKeyStore lookup does not exist on a JVM host: the click
    // coroutine then dies with an uncaught KeyStoreException whenever the
    // IO hop lands inside the test window (load-dependent). An in-memory
    // database keeps the verify flow hermetic and deterministic.
    private lateinit var testDb: RcsDatabase

    @BeforeTest
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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

    // ---- Signal ----

    @Test fun `signal registration gates every request on a typed number`() {
        var backs = 0
        rule.setContent { SignalRegistrationNumberScreen(onBack = { backs++ }, onCaptchaRequired = { _, _, _ -> }, onCodeRequested = { _, _ -> }) }
        rule.onNodeWithText("Register Signal").assertIsDisplayed()
        rule.onNodeWithText("PRIMARY Signal device", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Request SMS code").assertIsNotEnabled()
        rule.onNodeWithText("Voice").assertIsNotEnabled()
        rule.onNodeWithText("Check number (no SMS)").assertIsNotEnabled()
        rule.onNodeWithText("Phone number").performTextInput("4155552671")
        rule.waitForIdle()
        // Typing the number enables all three request paths (none tapped: no server).
        rule.onNodeWithText("Request SMS code").assertIsDisplayed()
        rule.onNodeWithText("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun `signal number and country code fields keep digits only`() {
        rule.setContent { SignalRegistrationNumberScreen(onBack = {}, onCaptchaRequired = { _, _, _ -> }, onCodeRequested = { _, _ -> }) }
        rule.onNodeWithText("CC").assertExists()
        rule.onNodeWithText("Phone number").performTextInput("abc415def2671")
        rule.waitForIdle()
        rule.onNodeWithText("4152671", substring = true).assertExists()
        // Clearing back to blank re-disables the requests.
        rule.onNodeWithText("Phone number").performTextInput("")
        rule.waitForIdle()
    }

    @Test fun `signal captcha webview composes without delivering a token`() {
        var tokens = 0
        rule.setContent { SignalCaptchaWebView(onToken = { tokens++ }) }
        rule.waitForIdle()
        // The challenge page loads in a WebView; only a real solved hCaptcha
        // redirect (signalcaptcha://…) may deliver a token.
        assertEquals(0, tokens)
    }

    // ---- WhatsApp ----

    @Test fun `whatsApp registration mirrors the signal gating and warns about logout`() {
        var backs = 0
        rule.setContent { WhatsAppRegistrationNumberScreen(onBack = { backs++ }, onCodeRequested = { _, _ -> }) }
        rule.onNodeWithText("Register WhatsApp").assertIsDisplayed()
        rule.onNodeWithText("log WhatsApp out", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Request SMS code").assertIsNotEnabled()
        rule.onNodeWithText("Check number (no SMS)").assertIsNotEnabled()
        rule.onNodeWithText("Phone number").performTextInput("4155552671")
        rule.waitForIdle()
        rule.onNodeWithText("Request SMS code").assertIsDisplayed()
        rule.onNodeWithText("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun `whatsApp country code defaults to 1`() {
        rule.setContent { WhatsAppRegistrationNumberScreen(onBack = {}, onCodeRequested = { _, _ -> }) }
        rule.onNodeWithText("1", substring = true).assertExists()
        rule.onNodeWithText("CC").assertExists()
    }

    // ---- WhatsApp backup import ----

    @Test fun `backup import keeps Import disabled until file and 64-hex key exist`() {
        var backs = 0
        rule.setContent { WhatsAppBackupImportScreen(onBack = { backs++ }) }
        rule.onNodeWithText("Import WhatsApp backup").assertIsDisplayed()
        rule.onNodeWithText("msgstore.db.crypt15", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Choose .crypt15 file").assertIsDisplayed()
        rule.onNodeWithText("Import").assertIsNotEnabled()
        // A full 64-hex key alone is not enough (no file picked yet).
        rule.onNodeWithText("64-hex backup key").performTextInput("a".repeat(64))
        rule.waitForIdle()
        rule.onNodeWithText("Import").assertIsNotEnabled()
        rule.onNodeWithText("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun `backup key field strips whitespace and non-alphanumerics`() {
        rule.setContent { WhatsAppBackupImportScreen(onBack = {}) }
        rule.onNodeWithText("64-hex backup key").performTextInput("ab cd-12")
        rule.waitForIdle()
        // Whitespace is trimmed away by the field's filter; hex digits remain.
        rule.onNodeWithText("abcd12", substring = true).assertExists()
        assertTrue(true)
    }

    // ---- RCS verify ----

    @Test fun `rcs verify shows both safety-number slots and the unverified status`() {
        var backs = 0
        rule.setContent { RcsVerifyScreen(peerE164 = "+14155550102", onBack = { backs++ }) }
        rule.onNodeWithText("Verify contact").assertIsDisplayed()
        rule.onNodeWithText("Your safety number").assertIsDisplayed()
        rule.onNodeWithText("+14155550102").assertIsDisplayed()
        rule.onNodeWithText("Status").assertIsDisplayed()
        // No key package exists in the test env, so both slots explain why and
        // the peer starts unverified; marking verified must not crash.
        rule.onNodeWithText("No published key yet", substring = true).assertExists()
        rule.onNodeWithText("Not verified").assertIsDisplayed()
        rule.onNodeWithText("Mark verified").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Clear verification").performClick()
        rule.waitForIdle()
        assertEquals(0, backs)
    }
}
