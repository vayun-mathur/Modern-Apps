package com.vayunmathur.email.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.email.FakeAndroidKeyStore
import com.vayunmathur.email.data.CredentialCrypto
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.imap.FakeImapServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [AddAccountScreen] submit flows: the custom-provider form must test
 * the entered server before saving (a saved-but-dead account is worse
 * than an error), persist the account with the password encrypted, and
 * fire onAccountAdded; the Outlook form's sign-in button must start
 * the PKCE flow (session stored, Microsoft authorize intent launched).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddAccountSubmitTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var server: FakeImapServer

    @BeforeTest
    fun open() {
        FakeAndroidKeyStore.install()
        FakeAndroidKeyStore.clear()
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        server = FakeImapServer(); server.start()
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
    }

    @AfterTest
    fun close() {
        com.vayunmathur.library.util.databases.remove(EmailDatabase::class)
        resetRepositorySingleton()
        db.close()
        server.close()
    }

    private fun resetRepositorySingleton() {
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun awaitAdded(added: () -> Int) {
        repeat(3000) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            if (added() > 0) return
            Thread.sleep(10)
        }
    }

    @Test
    fun `custom form tests the fake server and saves the account`() {
        var added = 0
        composeRule.setContent { AddAccountScreen(onBack = {}, onAccountAdded = { added++ }) }
        composeRule.onNodeWithText("Other (IMAP / SMTP)", substring = true).performScrollTo()
        composeRule.onNodeWithText("Other (IMAP / SMTP)", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Email address").performScrollTo()
        composeRule.onNodeWithText("Email address").performTextInput("me@x.com")
        composeRule.onNodeWithText("Password").performTextInput("app-pw")
        // IMAP + SMTP sections share Host/Port labels; IMAP comes first.
        composeRule.onAllNodesWithText("Host")[0].performScrollTo()
        composeRule.onAllNodesWithText("Host")[0].performTextInput("127.0.0.1")
        composeRule.onAllNodesWithText("Port")[0].performTextClearance()
        composeRule.onAllNodesWithText("Port")[0].performTextInput(server.port.toString())
        composeRule.onAllNodesWithText("Host")[1].performTextInput("127.0.0.1")
        composeRule.onAllNodesWithText("Port")[1].performTextClearance()
        composeRule.onAllNodesWithText("Port")[1].performTextInput("2525")
        // The fake server is plaintext: switch both sections to STARTTLS.
        composeRule.onAllNodesWithText("STARTTLS")[0].performScrollTo()
        composeRule.onAllNodesWithText("STARTTLS")[0].performClick()
        composeRule.onAllNodesWithText("STARTTLS")[1].performClick()
        composeRule.onNodeWithText("Test connection & save", substring = true).performScrollTo()
        composeRule.onNodeWithText("Test connection & save", substring = true).performClick()
        awaitAdded { added }
        assertEquals(1, added)
        val account = runBlocking { db.accountDao().getAccountByEmail("me@x.com") }
        assertNotNull(account)
        assertEquals(server.port, account.imapPort)
        assertEquals(false, account.imapUseSsl)
        assertEquals(
            "app-pw",
            CredentialCrypto.decrypt(account.passwordEncrypted!!, account.passwordIv!!),
        )
    }

    @Test
    fun `outlook sign-in stores the pkce session and launches microsoft`() {
        composeRule.setContent { AddAccountScreen(onBack = {}, onAccountAdded = {}) }
        composeRule.onNodeWithText("Outlook / Microsoft 365", substring = true).performScrollTo()
        composeRule.onNodeWithText("Outlook / Microsoft 365", substring = true).performClick()
        composeRule.waitForIdle()
        // The card heading shares the label but has no click action;
        // match the actual button.
        composeRule.onNode(hasClickAction() and hasText("Sign in with", substring = true))
            .performScrollTo()
        composeRule.onNode(hasClickAction() and hasText("Sign in with", substring = true))
            .performClick()
        composeRule.waitForIdle()
        val prefs = context.getSharedPreferences("outlook_oauth", android.content.Context.MODE_PRIVATE)
        assertTrue(!prefs.getString("verifier", null).isNullOrBlank(), "PKCE verifier was not stored")
        assertTrue(!prefs.getString("state", null).isNullOrBlank(), "OAuth state was not stored")
        val started = Shadows.shadowOf(context).nextStartedActivity
        assertTrue(
            started?.data?.toString()?.contains("login.microsoftonline.com") == true,
            "started: $started",
        )
    }
}
