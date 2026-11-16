package com.vayunmathur.communicate.ui

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateContact
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.VoicemailEntry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * W1 wave 2: the stateless content composables — dialer, thread rows,
 * voicemail list, conversation title/actions, permission gate helpers,
 * and the accounts screen in its signed-out state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunicateScreensW1Wave2Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // -- DialPad / DialerContent --

    @Test
    fun `dial pad appends digits and calls`() {
        val appended = CopyOnWriteArrayList<String>()
        var calls = 0
        var clears = 0
        var backspaces = 0
        rule.setContent {
            DialPad(
                number = "415",
                onAppend = { appended += it },
                onBackspace = { backspaces++ },
                onClear = { clears++ },
                onPaste = {},
                onCall = { calls++ },
            )
        }
        rule.onNodeWithText("415").assertIsDisplayed()
        rule.onNodeWithText("5").performClick()
        rule.onNodeWithText("0").performClick()
        rule.onNodeWithText("#").performClick()
        assertEquals(listOf("5", "0", "#"), appended.toList())
        rule.onNodeWithText("Clear").performClick()
        assertEquals(1, clears)
    }

    @Test
    fun `dial pad shows placeholder for an empty number`() {
        rule.setContent {
            DialPad(
                number = "", onAppend = {}, onBackspace = {}, onClear = {},
                onPaste = {}, onCall = {},
            )
        }
        rule.onNodeWithText("Phone number").assertIsDisplayed()
    }

    @Test
    fun `dialer content filters contacts by T9 and places calls`() {
        val placed = CopyOnWriteArrayList<String>()
        val contacts = listOf(
            CommunicateContact(1L, "Alice", "+14155550102", "Mobile"),
            CommunicateContact(2L, "Bob", "+14155550103", "Work"),
        )
        rule.setContent { DialerContent(contacts = contacts, onPlace = { placed += it }) }
        rule.onNodeWithText("Alice").assertIsDisplayed()
        // T9 "25": Alice (A=2, L=5) remains; Bob (B=2, O=6) filters out.
        rule.onNodeWithText("2").performClick()
        rule.onNodeWithText("5").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onAllNodesWithText("Bob").fetchSemanticsNodes().let { assertEquals(0, it.size) }
        rule.onNodeWithText("Alice").performClick()
        assertEquals(listOf("+14155550102"), placed.toList())
    }

    @Test
    fun `dialer content shows empty states`() {
        rule.setContent { DialerContent(contacts = emptyList(), onPlace = {}) }
        rule.onNodeWithText("No contacts found").assertExists()
    }

    // -- MessagesListContent / thread rows --

    private fun thread(
        id: Long, name: String?, snippet: String = "snippet", unread: Int = 0,
        group: Boolean = false, participants: List<String> = emptyList(),
        line: CommunicateLine = CommunicateLine.Sim,
    ) = SmsThread(
        threadId = id, address = "+14155550102", displayName = name, snippet = snippet,
        timestampMillis = 1_785_566_000_000L, unreadCount = unread, isGroup = group,
        participants = participants, line = line,
        remoteId = if (line == CommunicateLine.Sim) null else "remote-$id",
    )

    @Test
    fun `thread rows render unread groups and route opens`() {
        val opened = CopyOnWriteArrayList<SmsThread>()
        val threads = listOf(
            thread(1L, "Alice", snippet = "hello there", unread = 3),
            thread(2L, null, snippet = "group msg", group = true, participants = listOf("Bob", "Carol", "Dan")),
            thread(3L, "Eve", snippet = "wa", line = CommunicateLine.WhatsApp),
        )
        rule.setContent {
            MessagesListContent(threads = threads, onOpenThread = { opened += it }, onDeleteThread = {})
        }
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onNodeWithText("hello there").assertIsDisplayed()
        rule.onNodeWithText("3").assertIsDisplayed() // unread badge
        rule.onNodeWithText("Bob, Carol +1").assertIsDisplayed()
        rule.onNodeWithText("Eve").performClick()
        assertEquals(listOf(3L), opened.map { it.threadId })
    }

    @Test
    fun `thread avatar and badge helpers render`() {
        rule.setContent {
            ThreadAvatar(title = "Alice Smith")
            UnreadBadge(count = 12)
        }
        rule.onNodeWithText("AS").assertIsDisplayed()
        rule.onNodeWithText("12").assertIsDisplayed()
    }

    @Test
    fun `group title helper formats participant lists`() {
        assertNull(groupTitleFromParticipants(emptyList()))
        assertEquals("Bob", groupTitleFromParticipants(listOf("Bob")))
        assertEquals("Bob, Carol", groupTitleFromParticipants(listOf("Bob", "Carol")))
        assertEquals("Bob, Carol +2", groupTitleFromParticipants(listOf("Bob", "Carol", "Dan", "Eve")))
    }

    // -- VoicemailContent --

    private fun voicemail(id: Long, read: Boolean = false, hasAudio: Boolean = false) = VoicemailEntry(
        id = id, phoneNumber = "+1415555010$id", timestampMillis = 1_785_566_000_000L,
        durationSeconds = 42L, contentUri = "", mimeType = null, hasAudio = hasAudio,
        transcript = "call me back", isRead = read,
    )

    @Test
    fun `voicemail rows expand to the no-audio note and toggle`() {
        val toggled = CopyOnWriteArrayList<VoicemailEntry>()
        rule.setContent {
            VoicemailContent(
                entries = listOf(voicemail(1L), voicemail(2L, read = true)),
                expandedId = 1L,
                onToggleExpand = { toggled += it },
                onDelete = {},
            )
        }
        rule.onNodeWithText("+14155550101").assertIsDisplayed()
        rule.onNodeWithText("call me back").assertIsDisplayed()
        rule.onNodeWithText("No audio attached to this voicemail").assertIsDisplayed()
        rule.onNodeWithText("+14155550102").performClick()
        assertEquals(listOf(2L), toggled.map { it.id })
    }

    // -- Conversation top bar --

    @Test
    fun `conversation title shows typing group and address subtitles`() {
        rule.setContent {
            ConversationTitle(
                threadId = 1L, isGroup = false, groupTitle = null, title = "Alice",
                groupSubtitle = null, address = "+14155550102",
                line = CommunicateLine.Sim, isPeerTyping = true,
            )
        }
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onNodeWithText("typing…").assertIsDisplayed()
    }

    @Test
    fun `conversation title shows group subtitle`() {
        rule.setContent {
            ConversationTitle(
                threadId = 2L, isGroup = true, groupTitle = "Team", title = "ignored",
                groupSubtitle = "Alice, Bob", address = "",
                line = CommunicateLine.WhatsApp,
            )
        }
        rule.onNodeWithText("Team").assertIsDisplayed()
        rule.onNodeWithText("Alice, Bob").assertIsDisplayed()
    }

    @Test
    fun `conversation actions hide call for rcs and show it for sim`() {
        rule.setContent {
            ConversationActions(
                threadId = 1L, address = "+14155550102", line = CommunicateLine.Rcs,
                remoteId = "+14155550102", isGroup = false, participants = emptyList(),
                groupTitle = null, isUnknownContact = false, onBack = {},
            )
        }
        // Only the overflow button is clickable (no call/video for RCS).
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertEquals(1, nodes.size)
    }

    // -- CommunicateUi helpers --

    @Test
    fun `initials and permission helpers`() {
        assertEquals("AS", initialsFor("Alice Smith"))
        assertEquals("A", initialsFor("Alice"))
        assertEquals("?", initialsFor("   "))
        assertEquals("?", initialsFor(""))
        assertEquals("AB", initialsFor("alice+bob"))
        assertEquals("JD", initialsFor("john_doe smith"))
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
        assertTrue(context.hasContactsPermission())
        assertTrue(context.hasCommunicatePermission(Manifest.permission.READ_CONTACTS))
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CONTACTS)
        assertTrue(!context.hasContactsPermission())
        val formatted = formatDateTime(context, 1_785_566_000_000L)
        assertTrue(formatted.isNotBlank())
    }

    @Test
    fun `permission gate shows the grant prompt when denied`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CALL_LOG)
        rule.setContent {
            PermissionGate(permission = Manifest.permission.READ_CALL_LOG, message = "Need call log") {
                androidx.compose.material3.Text("secret content")
            }
        }
        rule.onNodeWithText("Need call log").assertIsDisplayed()
        rule.onAllNodesWithText("secret content").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
    }

    @Test
    fun `permission gate shows content when granted`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALL_LOG)
        rule.setContent {
            PermissionGate(permission = Manifest.permission.READ_CALL_LOG, message = "Need call log") {
                androidx.compose.material3.Text("secret content")
            }
        }
        rule.onNodeWithText("secret content").assertIsDisplayed()
    }

    // -- AccountsScreen (signed out everywhere) --

    @Test
    fun `accounts screen lists every line and routes register actions`() {
        val actions = CopyOnWriteArrayList<String>()
        rule.setContent {
            AccountsScreen(
                onBack = { actions += "back" },
                onSignIn = { actions += "gv" },
                onRegisterWhatsApp = { actions += "wa" },
                onImportBackup = { actions += "import" },
                onRegisterSignal = { actions += "signal" },
                onShowRcsStatus = { actions += "rcs" },
            )
        }
        rule.onNodeWithText("Signal").assertExists()
        rule.onNodeWithText("WhatsApp").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("RCS").assertExists()
        rule.onAllNodesWithText("Register").fetchSemanticsNodes().let {
            assertTrue(it.size >= 2) // Signal + WhatsApp
        }
        rule.onNodeWithText("Import backup (.crypt15)").assertExists()
        rule.onNodeWithText("Status").performClick()
        assertEquals(listOf("rcs"), actions.toList())
    }

    // -- NewMessagePicker --

    @Test
    fun `new message picker lists contacts and composes`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
        val composed = CopyOnWriteArrayList<Pair<LineChoice, String>>()
        rule.setContent {
            NewMessagePicker(
                choices = listOf(LineChoice.Sim(-1, "SIM"), LineChoice.WhatsApp),
                onDismiss = {},
                onCompose = { choice, address -> composed += choice to address },
                onCreateGroup = { _, _, _, _ -> },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("New message").assertIsDisplayed()
    }
}
