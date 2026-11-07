package com.vayunmathur.openassistant.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.openassistant.data.Memory
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.util.ChatActions
import com.vayunmathur.openassistant.util.ChatUiState
import com.vayunmathur.openassistant.util.InferenceService
import com.vayunmathur.openassistant.util.SettingsActions
import com.vayunmathur.openassistant.util.SettingsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Chat bubbles, composer, chat screen and settings screen under Robolectric. */
@RunWith(RobolectricTestRunner::class)
class ChatUiComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun userBubbleShowsTextAndAudioChip() {
        composeRule.setContent {
            DynamicTheme {
                ChatBubble(Message(1L, "my question", "user", hasAudio = true, id = 5L))
            }
        }
        composeRule.onNodeWithText("my question").assertIsDisplayed()
        composeRule.onNodeWithText("Voice Message").assertIsDisplayed()
    }

    @Test fun assistantBubbleRendersMarkdownText() {
        composeRule.setContent {
            DynamicTheme {
                ChatBubble(Message(1L, "Hello **there**", "assistant", id = 6L))
            }
        }
        composeRule.onNodeWithText("Hello", substring = true).assertExists()
    }

    @Test fun toolBubbleStripsLinkMarkupAndShowsButton() {
        composeRule.setContent {
            DynamicTheme {
                ChatBubble(Message(1L, "Opened [Notes](app://notes/1) for you", "tool", id = 7L))
            }
        }
        composeRule.onNodeWithText("Opened Notes for you", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Notes").assertIsDisplayed()
    }

    @Test fun chatInputTypingEnablesSend() {
        var text = ""
        var sends = 0
        composeRule.setContent {
            DynamicTheme {
                ChatInput(
                    modifier = Modifier,
                    inputText = text,
                    onTextChange = { text = it },
                    selectedImageUris = emptyList(),
                    isRecording = false,
                    onAddImage = {}, onRecord = {}, onSend = { sends++ },
                    onCancelMedia = {}, onRemoveImage = {})
            }
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("hello assistant")
        assertEquals("hello assistant", text)
        assertEquals(0, sends)
    }

    @Test fun chatInputRecordingChipCancels() {
        var cancels = 0
        composeRule.setContent {
            DynamicTheme {
                ChatInput(
                    modifier = Modifier,
                    inputText = "",
                    onTextChange = {},
                    selectedImageUris = emptyList(),
                    isRecording = true,
                    onAddImage = {}, onRecord = {}, onSend = {},
                    onCancelMedia = { cancels++ }, onRemoveImage = {})
            }
        }
        composeRule.onNodeWithText("Recording…").assertIsDisplayed()
    }

    private class RecordingActions : ChatActions {
        var sent = 0
        var settings = 0
        var newChat = 0
        var conversations = 0
        var lastText: String? = null
        override fun setInputText(text: String) { lastText = text }
        override fun send() { sent++ }
        override fun openSettings() { settings++ }
        override fun newConversation() { newChat++ }
        override fun openConversations() { conversations++ }
    }

    @Test fun chatScreenShowsConversationAndActions() {
        val actions = RecordingActions()
        val state = ChatUiState(
            title = "Groceries",
            messages = listOf(
                Message(1L, "hi", "user", id = 1L),
                Message(1L, "hello", "assistant", id = 2L)),
            showConversationsButton = true,
            showNewChatButton = true)
        composeRule.setContent {
            DynamicTheme { ChatScreen(state = state, actions = actions) }
        }
        composeRule.onNodeWithText("Groceries").assertIsDisplayed()
        composeRule.onNodeWithText("hi").assertIsDisplayed()
        composeRule.onNodeWithText("hello").assertIsDisplayed()
        assertTrue(actions.sent == 0)
    }

    @Test fun chatScreenNewConversationTitle() {
        composeRule.setContent {
            DynamicTheme { ChatScreen(state = ChatUiState(), actions = ChatActions.Noop) }
        }
        composeRule.onNodeWithText("New Conversation").assertIsDisplayed()
    }

    @Test fun settingsScreenEmptyMemoriesAndPrompt() {
        var prompt: String? = null
        val actions = object : SettingsActions {
            override fun setSystemPrompt(p: String) { prompt = p }
        }
        composeRule.setContent {
            DynamicTheme {
                SettingsScreen(
                    state = SettingsUiState(systemPrompt = InferenceService.DEFAULT_SYSTEM_PROMPT),
                    actions = actions)
            }
        }
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithText("System Prompt").fetchSemanticsNodes().size)
        assertEquals(null, prompt)
    }

    @Test fun settingsListsMemoriesAndDeletes() {
        var deleted: Memory? = null
        val actions = object : SettingsActions {
            override fun deleteMemory(memory: Memory) { deleted = memory }
        }
        val memory = Memory(content = "likes tea", id = 3L)
        composeRule.setContent {
            DynamicTheme {
                SettingsScreen(
                    state = SettingsUiState(memories = listOf(memory), systemPrompt = "be nice"),
                    actions = actions)
            }
        }
        composeRule.onNodeWithText("likes tea").assertIsDisplayed()
        assertEquals(null, deleted)
    }

    @Test fun memoryItemShowsContent() {
        composeRule.setContent {
            DynamicTheme { MemoryItem(memory = Memory("remember this", id = 1L), onDelete = {}) }
        }
        composeRule.onNodeWithText("remember this").assertIsDisplayed()
    }
}
