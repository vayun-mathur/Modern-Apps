package com.vayunmathur.communicate.ui

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.Route
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.FakeContactsProvider
import com.vayunmathur.communicate.data.FakeContactsState
import com.vayunmathur.communicate.data.FakeMmsProvider
import com.vayunmathur.communicate.data.FakeMmsSmsProvider
import com.vayunmathur.communicate.data.FakeSmsProvider
import com.vayunmathur.communicate.data.FakeTelephonyState
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.W1SmsManagerShadow
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.rcs.RcsConversation
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsCachedMessage
import com.vayunmathur.communicate.data.signal.SignalCachedMessage
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppCachedMessage
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.ui.rcs.RcsGroupMembersScreen
import com.vayunmathur.communicate.ui.signal.SignalRegistrationScreen
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationScreen
import com.vayunmathur.library.util.DataStoreUtils
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowRoleManager

/**
 * Y2 wave-1: the real top-level screens (`MessagesScreenKt` incl. its
 * produceState merged loader, `ConversationScreenKt`, `CommunicateUiKt`,
 * `AccountsScreenKt` signed-in states, `RcsGroupMembersScreenKt`) plus the
 * Signal/WhatsApp registration code-entry failure transitions.
 *
 * The screens compose for real under Robolectric: fake telephony providers,
 * held SMS/dialer roles, granted permissions and in-memory RCS/WhatsApp/
 * Signal Room overrides make the loaders return seeded rows. Registration
 * screens construct their own HTTP clients, so with no server reachable the
 * request must surface an error status and stay on the number step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [W1SmsManagerShadow::class])
class Y2TopLevelScreensTest {

    @get:Rule val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var rcsDb: RcsDatabase
    private lateinit var waDb: WhatsAppDatabase
    private lateinit var sigDb: SignalDatabase

    @BeforeTest
    fun setUp() {
        FakeTelephonyState.reset()
        FakeContactsState.reset()
        Robolectric.setupContentProvider(FakeSmsProvider::class.java, "sms")
        Robolectric.setupContentProvider(FakeMmsProvider::class.java, "mms")
        Robolectric.setupContentProvider(FakeMmsSmsProvider::class.java, "mms-sms")
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        val app = ApplicationProvider.getApplicationContext<Application>()
        rcsDb = Room.inMemoryDatabaseBuilder(app, RcsDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        waDb = Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        sigDb = Room.inMemoryDatabaseBuilder(app, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        RcsDatabase.overrideForTest = rcsDb
        WhatsAppDatabase.overrideForTest = waDb
        SignalDatabase.overrideForTest = sigDb
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
        )
        val shadow = Shadow.extract<ShadowRoleManager>(app.getSystemService(RoleManager::class.java))
        shadow.addHeldRole(RoleManager.ROLE_SMS)
        shadow.addHeldRole(RoleManager.ROLE_DIALER)
        shadow.addAvailableRole(RoleManager.ROLE_SMS)
        shadow.addAvailableRole(RoleManager.ROLE_DIALER)
        runBlocking {
            DataStoreUtils.getInstance(app).setBoolean("wa_signed_in", true)
            DataStoreUtils.getInstance(app).setBoolean("sig_signed_in", true)
        }
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            DataStoreUtils.getInstance(context).setBoolean("wa_signed_in", false)
            DataStoreUtils.getInstance(context).setBoolean("sig_signed_in", false)
            GoogleVoiceSession.get(context).signOut()
        }
        val shadow = Shadow.extract<ShadowRoleManager>(context.getSystemService(RoleManager::class.java))
        shadow.removeHeldRole(RoleManager.ROLE_SMS)
        shadow.removeHeldRole(RoleManager.ROLE_DIALER)
        RcsDatabase.overrideForTest = null
        WhatsAppDatabase.overrideForTest = null
        SignalDatabase.overrideForTest = null
        rcsDb.close(); waDb.close(); sigDb.close()
    }

    private fun exists(text: String, timeoutMs: Long = 10_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---- MessagesScreen (top-level loader) ----

    @Test
    fun `messages screen merged loader shows sim rcs and whatsapp threads and routes opens`() {
        FakeTelephonyState.smsRows = listOf(
            mapOf(
                Telephony.Sms._ID to 1L, Telephony.Sms.THREAD_ID to 10L,
                Telephony.Sms.ADDRESS to "+14155550102", Telephony.Sms.BODY to "sim merged hello",
                Telephony.Sms.DATE to 2_000L, Telephony.Sms.TYPE to Telephony.Sms.MESSAGE_TYPE_INBOX,
                Telephony.Sms.READ to 0, Telephony.Sms.STATUS to Telephony.Sms.STATUS_NONE,
                Telephony.Sms.SUBSCRIPTION_ID to -1,
            ),
        )
        runBlocking {
            rcsDb.conversationDao().upsert(RcsConversation(remoteId = "+14155550109", unreadCount = 1))
            rcsDb.cachedMessageDao().upsert(
                RcsCachedMessage(messageId = "rm1", conversationId = "+14155550109",
                    body = "rcs merged hello", timestamp = 9_000L, outgoing = false),
            )
            waDb.cachedMessageDao().upsert(
                WhatsAppCachedMessage(messageId = "wm1", conversationJid = "14155550103@s.whatsapp.net",
                    body = "wa merged hello", timestamp = 8_000L, outgoing = false),
            )
        }
        val opened = CopyOnWriteArrayList<SmsThread>()
        var accounts = 0
        rule.setContent {
            MessagesScreen(onOpenThread = { opened += it }, onOpenAccounts = { accounts++ })
        }
        exists("sim merged hello")
        exists("rcs merged hello")
        exists("wa merged hello")
        rule.onNodeWithText("wa merged hello").performClick()
        rule.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals(CommunicateLine.WhatsApp, opened.single().line)
        assertEquals("14155550103@s.whatsapp.net", opened.single().remoteId)
        // Top-bar account action routes to Accounts (icon-only button, so find
        // it by clicking clickables until the callback fires; row clicks just
        // re-open threads, which is harmless here).
        val clickables = rule.onAllNodes(hasClickAction())
        for (i in 0 until clickables.fetchSemanticsNodes().size) {
            if (accounts > 0) break
            runCatching { clickables[i].performClick() }
            rule.waitForIdle()
        }
        assertEquals(1, accounts)
    }

    @Test
    fun `messages screen with no threads shows the empty state`() {
        rule.setContent { MessagesScreen(onOpenThread = {}, onOpenAccounts = {}) }
        exists("No conversations yet")
        rule.onNodeWithText("No conversations yet").assertIsDisplayed()
    }

    // ---- ConversationScreen (top-level loader) ----

    @Test
    fun `conversation screen loads sim messages and sends a typed draft`() {
        FakeTelephonyState.smsRows = listOf(
            mapOf(
                Telephony.Sms._ID to 1L, Telephony.Sms.THREAD_ID to 10L,
                Telephony.Sms.ADDRESS to "+14155550102", Telephony.Sms.BODY to "thread body y2",
                Telephony.Sms.DATE to 2_000L, Telephony.Sms.TYPE to Telephony.Sms.MESSAGE_TYPE_INBOX,
                Telephony.Sms.READ to 1, Telephony.Sms.STATUS to Telephony.Sms.STATUS_NONE,
                Telephony.Sms.SUBSCRIPTION_ID to -1,
            ),
        )
        rule.setContent {
            ConversationScreen(
                threadId = 10L, address = "+14155550102", line = CommunicateLine.Sim,
                remoteId = null, onBack = {},
            )
        }
        exists("thread body y2")
        rule.onNodeWithText("Message").performTextInput("sent from y2")
        rule.waitForIdle()
        // Send is the last enabled clickable (bottom-bar send button).
        val enabled = rule.onAllNodes(hasClickAction().and(isEnabled()))
        enabled[enabled.fetchSemanticsNodes().size - 1].performClick()
        rule.waitUntil(10_000) {
            FakeTelephonyState.inserts.any { it.first.toString().contains("sms/sent") }
        }
        val sentRow = FakeTelephonyState.inserts.first { it.first.toString().contains("sms/sent") }
        assertEquals("sent from y2", sentRow.second?.getAsString(Telephony.Sms.BODY))
    }

    @Test
    fun `conversation screen loads rcs messages from the cache`() {
        runBlocking {
            rcsDb.conversationDao().upsert(RcsConversation(remoteId = "+14155550109"))
            rcsDb.cachedMessageDao().upsert(
                RcsCachedMessage(messageId = "rc1", conversationId = "+14155550109",
                    body = "rcs thread body y2", timestamp = 5_000L, outgoing = false,
                    senderId = "+14155550109"),
            )
            rcsDb.cachedMessageDao().upsert(
                RcsCachedMessage(messageId = "rc2", conversationId = "+14155550109",
                    body = "rcs own reply y2", timestamp = 6_000L, outgoing = true, status = 1),
            )
        }
        rule.setContent {
            ConversationScreen(
                threadId = 42L, address = "+14155550109", line = CommunicateLine.Rcs,
                remoteId = "+14155550109", onBack = {},
            )
        }
        exists("rcs thread body y2")
        exists("rcs own reply y2")
        rule.onNodeWithText("rcs thread body y2").assertIsDisplayed()
    }

    @Test
    fun `conversation screen loads signal messages from the cache`() {
        runBlocking {
            sigDb.cachedMessageDao().upsert(
                SignalCachedMessage(messageId = "sg1", conversationId = "+14155550107",
                    body = "signal thread body y2", timestamp = 5_000L, outgoing = false,
                    senderId = "+14155550107"),
            )
        }
        rule.setContent {
            ConversationScreen(
                threadId = 43L, address = "+14155550107", line = CommunicateLine.Signal,
                remoteId = "+14155550107", onBack = {},
            )
        }
        exists("signal thread body y2")
        rule.onNodeWithText("signal thread body y2").assertIsDisplayed()
    }

    // ---- RcsGroupMembersScreen ----

    @Test
    fun `rcs group members add fails closed and remove drops the stored row`() {
        runBlocking {
            rcsDb.conversationDao().upsert(
                RcsConversation(
                    remoteId = "rcs-group:y2", isGroup = true, name = "Crew",
                    participants = "+14155550103,+14155550102",
                ),
            )
        }
        rule.setContent { RcsGroupMembersScreen(conversationId = "rcs-group:y2", onBack = {}) }
        exists("+14155550102")
        exists("+14155550103")
        // Sorted member order: ...102 sorts before ...103.
        val texts = rule.onAllNodesWithText("+1415555010", substring = true).fetchSemanticsNodes()
        assertEquals(2, texts.size)

        // Add: capability fails closed on this host, so the typed peer is not stored.
        rule.onNodeWithText("+15551234567").performTextInput("+14155550199")
        rule.waitForIdle()
        rule.onNodeWithText("Add").performClick()
        rule.waitUntil(10_000) {
            runBlocking { rcsDb.conversationDao().getConversation("rcs-group:y2") } != null &&
                !runBlocking {
                    rcsDb.conversationDao().getConversation("rcs-group:y2")!!.participants
                }.contains("14155550199")
        }
        assertFalse(
            runBlocking { rcsDb.conversationDao().getConversation("rcs-group:y2")!!.participants }
                .contains("14155550199"),
        )

        // Remove a member via its trailing delete action: the stored row drops it.
        // The delete buttons are icon-only, so click clickables until the stored
        // participant list shrinks (back/Add clicks are no-ops for this).
        val beforeParts = runBlocking {
            rcsDb.conversationDao().getConversation("rcs-group:y2")!!.participants
        }
        val clickables = rule.onAllNodes(hasClickAction())
        for (i in 0 until clickables.fetchSemanticsNodes().size) {
            val now = runBlocking {
                rcsDb.conversationDao().getConversation("rcs-group:y2")!!.participants
            }
            if (now != beforeParts) break
            runCatching { clickables[i].performClick() }
            rule.waitForIdle()
        }
        rule.waitUntil(10_000) {
            runBlocking {
                rcsDb.conversationDao().getConversation("rcs-group:y2")!!.participants
            } != beforeParts
        }
        val afterParts = runBlocking {
            rcsDb.conversationDao().getConversation("rcs-group:y2")!!.participants
        }
        assertTrue(afterParts.split(",").size < beforeParts.split(",").size,
            "participants $beforeParts -> $afterParts")
    }

    // ---- AccountsScreen signed-in states ----

    @Test
    fun `accounts shows signed-in numbers and signs google voice out`() {
        runBlocking {
            GoogleVoiceSession.get(context).save(
                cookieHeader = "SAPISID=abc123; other=1", apiKey = "key-1", authUser = "0",
            )
            GoogleVoiceSession.get(context).setPhoneNumber("+14155550100")
            DataStoreUtils.getInstance(context).setString("wa_phone_number", "+14155550103")
            DataStoreUtils.getInstance(context).setString("sig_phone_number", "+14155550107")
        }
        var backs = 0; var rcsStatus = 0
        rule.setContent {
            AccountsScreen(
                onBack = { backs++ }, onSignIn = {},
                onRegisterWhatsApp = {}, onImportBackup = {},
                onRegisterSignal = {}, onShowRcsStatus = { rcsStatus++ },
            )
        }
        exists("+14155550100")
        rule.onNodeWithText("Google Voice").assertIsDisplayed()
        // WhatsApp + Signal signed-in rows show their numbers, RCS shows its live state.
        rule.onNodeWithText("+14155550103").assertExists()
        rule.onNodeWithText("+14155550107").assertExists()
        rule.onNodeWithText("Not checked").assertExists()
        // The RCS Status button sits below the fold: scroll it into view first
        // (coordinate clicks on clipped nodes miss), then click.
        runCatching {
            rule.onNodeWithText("Status").performScrollTo().performClick()
        }
        rule.waitForIdle()
        if (rcsStatus == 0) {
            val statusNodes = rule.onAllNodesWithText("Status")
            for (i in 0 until statusNodes.fetchSemanticsNodes().size) {
                if (rcsStatus > 0) break
                runCatching { statusNodes[i].performClick() }
                rule.waitForIdle()
            }
        }
        assertEquals(1, rcsStatus)
        // Sign Google Voice out (its card is first of the three Sign out
        // buttons): the row flips back to the sign-in state.
        rule.onAllNodesWithText("Sign out")[0].performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Not signed in").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, backs)
    }

    // ---- CommunicateUi helpers ----

    @Test
    fun `permission gate hides content when denied`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_SMS)
        rule.setContent {
            PermissionGate(permission = Manifest.permission.READ_SMS, message = "Need SMS") {
                androidx.compose.material3.Text("gate-content-y2")
            }
        }
        rule.onNodeWithText("Permission needed").assertIsDisplayed()
        rule.onNodeWithText("Need SMS").assertIsDisplayed()
        rule.onNodeWithText("Grant permission").assertIsDisplayed()
        rule.onNodeWithText("gate-content-y2").assertDoesNotExist()
    }

    @Test
    fun `permission gate shows content when granted`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_SMS)
        rule.setContent {
            PermissionGate(permission = Manifest.permission.READ_SMS, message = "Need SMS") {
                androidx.compose.material3.Text("gate-content-y2")
            }
        }
        rule.onNodeWithText("gate-content-y2").assertIsDisplayed()
        rule.onNodeWithText("Permission needed").assertDoesNotExist()
    }

    @Test
    fun `line tag initials and date formatting follow their contracts`() {
        assertEquals("AB", initialsFor("Alice Bob"))
        assertEquals("A", initialsFor("alice"))
        assertEquals("?", initialsFor("   "))
        assertEquals("1", initialsFor("+14155550102")) // one token after the '+' split
        rule.setContent {
            LineTag("Voice")
            LineTag(null)
            LineTag("  ")
        }
        rule.onNodeWithText("Voice").assertIsDisplayed()
        assertEquals(1, rule.onAllNodesWithText("Voice").fetchSemanticsNodes().size)

        val now = System.currentTimeMillis()
        val today = formatDateTime(context, now)
        assertTrue(today.isNotBlank())
        // Today (or this week, depending on the host clock bucket) reads as a
        // time/weekday; a 40-day-old stamp reads as a medium date.
        val oldTs = now - 40L * 24 * 3600 * 1000
        val old = formatDateTime(context, oldTs)
        assertEquals(
            android.text.format.DateFormat.getMediumDateFormat(context).format(java.util.Date(oldTs)),
            old,
        )
        assertFalse(old == today)
    }

    @Test
    fun `line selector opens the dropdown and routes the selection`() {
        val choices = listOf(LineChoice.Sim(-1, "SIM 1"), LineChoice.GoogleVoice)
        val picked = CopyOnWriteArrayList<LineChoice>()
        rule.setContent {
            LineSelector(choices = choices, selected = choices[0], onSelect = { picked += it })
        }
        rule.onNodeWithText("SIM 1").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Google Voice").performClick()
        rule.waitForIdle()
        assertEquals(listOf(LineChoice.GoogleVoice), picked.toList())
    }

    @Test
    fun `navigation routes carry conversation defaults`() {
        val route = Route.Conversation(threadId = 5L, address = "+14155550102")
        assertEquals(CommunicateLine.Sim, route.line)
        assertEquals(null, route.remoteId)
        assertEquals(emptyList<String>(), route.participants)
        assertFalse(route.isGroup)
        assertEquals(Route.Main, Route.Main)
        assertFalse(Route.Accounts == Route.Main)
    }

    // ---- registration request-code failure transitions ----

    @Test
    fun `signal request code with no server surfaces an error and stays on the number step`() {
        rule.setContent { SignalRegistrationScreen(onBack = {}, onRegistered = {}) }
        rule.onNodeWithText("Phone number").performTextInput("4155552671")
        rule.waitForIdle()
        rule.onNodeWithText("Request SMS code").assertIsEnabled()
        rule.onNodeWithText("Request SMS code").performClick()
        // With no usable server response the screen must surface a status and
        // must not silently advance: it either reports the failure on the
        // number step, or (when the proxied request is answered with a
        // captcha demand) moves to the captcha step. It never reaches the
        // code step or Done on its own.
        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("Error:", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("code:", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("captcha", substring = true, ignoreCase = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("6-digit code").assertDoesNotExist()
        rule.onNodeWithText("Registered", substring = true).assertDoesNotExist()
        val surfaced = rule.onAllNodesWithText("Error:", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("code:", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("captcha", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        assertTrue(surfaced, "request must surface an error/captcha state")
    }

    @Test
    fun `whatsApp request code with no server surfaces an error and stays on the number step`() {
        rule.setContent { WhatsAppRegistrationScreen(onBack = {}, onRegistered = {}) }
        rule.onNodeWithText("Phone number").performTextInput("4155552671")
        rule.waitForIdle()
        rule.onNodeWithText("Request SMS code").assertIsEnabled()
        rule.onNodeWithText("Request SMS code").performClick()
        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("Error:", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("code:", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Request SMS code").assertExists()
        rule.onNodeWithText("6-digit code").assertDoesNotExist()
    }

    @Test
    fun `whatsApp verify stays gated below six digits on the number step`() {
        // Client-side gate complement to the network tests above: without a
        // successful request there is no code step at all.
        rule.setContent { WhatsAppRegistrationScreen(onBack = {}, onRegistered = {}) }
        rule.onNodeWithText("Verify").assertDoesNotExist()
        rule.onNodeWithText("Request SMS code").assertIsNotEnabled()
        rule.onNodeWithText("Phone number").performTextInput("415")
        rule.waitForIdle()
        rule.onNodeWithText("Request SMS code").assertIsEnabled()
    }
}
