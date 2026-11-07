package com.vayunmathur.communicate.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.SmsThread
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UiPass3 coverage for the thread list (`MessagesScreen.kt` stateless
 * [MessagesListContent] + `MessagesThreadRow.kt`): titles, snippets, unread
 * badges, group fallbacks and the delete-confirm flow.
 */
@RunWith(RobolectricTestRunner::class)
class MessagesUiPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun str(id: Int): String = context.getString(id)

    private fun thread(
        id: Long = 1L,
        address: String = "+1555",
        name: String? = "Pat Lee",
        snippet: String = "see you soon",
        unread: Int = 0,
        line: CommunicateLine = CommunicateLine.Sim,
        isGroup: Boolean = false,
        participants: List<String> = emptyList(),
        groupTitle: String? = null,
    ) = SmsThread(
        threadId = id,
        address = address,
        displayName = name,
        snippet = snippet,
        timestampMillis = System.currentTimeMillis(),
        unreadCount = unread,
        line = line,
        isGroup = isGroup,
        participants = participants,
        groupTitle = groupTitle,
    )

    @Test
    fun `row shows title snippet and opens on tap`() {
        val opened = mutableListOf<SmsThread>()
        val t = thread()
        rule.setContent {
            MessagesListContent(threads = listOf(t), onOpenThread = { opened.add(it) }, onDeleteThread = {})
        }
        rule.onNodeWithText("Pat Lee").assertIsDisplayed()
        rule.onNodeWithText("see you soon").assertIsDisplayed()
        rule.onNodeWithText("Pat Lee").performClick()
        rule.waitForIdle()
        assertEquals(listOf(t), opened)
    }

    @Test
    fun `unread threads show a count badge`() {
        rule.setContent {
            MessagesListContent(
                threads = listOf(thread(unread = 4)),
                onOpenThread = {}, onDeleteThread = {},
            )
        }
        rule.onNodeWithText("4").assertIsDisplayed()
    }

    @Test
    fun `read threads show no badge`() {
        rule.setContent {
            MessagesListContent(
                threads = listOf(thread(unread = 0)),
                onOpenThread = {}, onDeleteThread = {},
            )
        }
        rule.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun `group rows prefer the explicit group title`() {
        rule.setContent {
            MessagesListContent(
                threads = listOf(thread(isGroup = true, groupTitle = "Family", name = null)),
                onOpenThread = {}, onDeleteThread = {},
            )
        }
        rule.onNodeWithText("Family").assertIsDisplayed()
    }

    @Test
    fun `group rows fall back to participant names`() {
        rule.setContent {
            MessagesListContent(
                threads = listOf(
                    thread(
                        isGroup = true, name = null, groupTitle = null,
                        participants = listOf("Alice", "Bob", "Cid"),
                    ),
                ),
                onOpenThread = {}, onDeleteThread = {},
            )
        }
        rule.onNodeWithText("Alice, Bob +1").assertIsDisplayed()
    }

    @Test
    fun `delete asks first and only then deletes`() {
        val deleted = mutableListOf<SmsThread>()
        val t = thread()
        rule.setContent {
            MessagesListContent(threads = listOf(t), onOpenThread = {}, onDeleteThread = { deleted.add(it) })
        }
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("More")).performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.delete)).performClick()
        rule.onNodeWithText(str(R.string.delete_conversation_title)).assertIsDisplayed()
        assertEquals(emptyList(), deleted)
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.delete)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(t), deleted)
    }

    @Test
    fun `delete can be cancelled`() {
        val deleted = mutableListOf<SmsThread>()
        rule.setContent {
            MessagesListContent(threads = listOf(thread()), onOpenThread = {}, onDeleteThread = { deleted.add(it) })
        }
        rule.onNode(hasClickAction() and hasContentDescriptionExactly("More")).performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.delete)).performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        rule.waitForIdle()
        assertEquals(emptyList(), deleted)
    }

    @Test
    fun `whatsapp and signal rows render line tags`() {
        rule.setContent {
            MessagesListContent(
                threads = listOf(
                    thread(id = 1L, name = "Wa Pal", line = CommunicateLine.WhatsApp),
                    thread(id = 2L, name = "Sig Pal", line = CommunicateLine.Signal),
                ),
                onOpenThread = {}, onDeleteThread = {},
            )
        }
        rule.onNodeWithText("Wa Pal").assertIsDisplayed()
        rule.onNodeWithText("Sig Pal").assertIsDisplayed()
        rule.onNodeWithText("WhatsApp").assertIsDisplayed()
        rule.onNodeWithText("Signal").assertIsDisplayed()
    }
}
