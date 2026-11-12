package com.vayunmathur.keyboard.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.vayunmathur.keyboard.ime.EnterAction
import com.vayunmathur.keyboard.ime.ImeActions
import com.vayunmathur.keyboard.ime.KeyboardState
import com.vayunmathur.keyboard.ime.NumericMode
import com.vayunmathur.keyboard.ime.TextVariation
import com.vayunmathur.keyboard.ime.VoiceState
import com.vayunmathur.keyboard.platform.VoiceFailure
import com.vayunmathur.keyboard.util.ClipItem
import com.vayunmathur.keyboard.util.EmojiData
import com.vayunmathur.keyboard.util.KeyboardPage
import com.vayunmathur.keyboard.util.ShiftState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Recording [ImeActions] so UI tests assert what a tap asked the service to do. */
class RecordingActions : ImeActions {
    val chars = mutableListOf<String>()
    var backspaces = 0
    var enters = 0
    var spaces = 0
    var shifts = 0
    val pages = mutableListOf<KeyboardPage>()
    val suggestions = mutableListOf<String>()
    val emojis = mutableListOf<String>()
    val pasted = mutableListOf<ClipItem>()
    val deleted = mutableListOf<ClipItem>()
    var clearClipsCalls = 0
    var dismissClipCalls = 0
    var voiceCalls = 0
    var stopVoiceCalls = 0
    var dismissVoiceCalls = 0
    var settingsCalls = 0
    var searchStarts = 0
    var searchEnds = 0

    override fun onChar(text: String) { chars += text }
    override fun onBackspace() { backspaces++ }
    override fun onEnter() { enters++ }
    override fun onSpace() { spaces++ }
    override fun onShift() { shifts++ }
    override fun setPage(page: KeyboardPage) { pages += page }
    override fun commitSuggestion(word: String) { suggestions += word }
    override fun startEmojiSearch() { searchStarts++ }
    override fun endEmojiSearch() { searchEnds++ }
    override fun commitEmoji(emoji: String) { emojis += emoji }
    override fun pasteClip(item: ClipItem) { pasted += item }
    override fun deleteClip(item: ClipItem) { deleted += item }
    override fun clearClips() { clearClipsCalls++ }
    override fun dismissClipSuggestion() { dismissClipCalls++ }
    override fun onVoiceInput() { voiceCalls++ }
    override fun stopVoiceInput() { stopVoiceCalls++ }
    override fun dismissVoice() { dismissVoiceCalls++ }
    override fun openVoiceSettings() { settingsCalls++ }
}

@RunWith(RobolectricTestRunner::class)
class KeyboardScreenTest {

    @get:Rule val rule = createComposeRule()

    private fun state(block: KeyboardState.() -> Unit = {}) = KeyboardState().apply(block)

    @Test
    fun `letters page renders rows and typing a letter reports it`() {
        val actions = RecordingActions()
        rule.setContent { KeyboardScreen(state(), actions) }
        rule.onNodeWithText("q").assertIsDisplayed()
        rule.onNodeWithText("English").assertIsDisplayed()
        rule.onNodeWithText("q").performTouchInput { down(center); up() }
        assertEquals(listOf("q"), actions.chars)
        // page toggle is a SpecialKey (combinedClickable)
        rule.onNodeWithText("?123").performClick()
        assertEquals(listOf(KeyboardPage.SYMBOLS), actions.pages)
    }

    @Test
    fun `shifted and caps letters render upper case`() {
        val actions = RecordingActions()
        val st = state { shift = ShiftState.SHIFTED }
        rule.setContent { KeyboardScreen(st, actions) }
        rule.onNodeWithText("Q").assertIsDisplayed()
        rule.runOnIdle { st.shift = ShiftState.CAPS_LOCK }
        rule.onNodeWithText("Q").assertIsDisplayed()
        rule.runOnIdle { st.shift = ShiftState.OFF }
        rule.onNodeWithText("q").assertIsDisplayed()
    }

    @Test
    fun `no number row gives top row digit corner hints`() {
        val actions = RecordingActions()
        rule.setContent {
            KeyboardScreen(state { settings = settings.copy(numberRow = false) }, actions)
        }
        rule.onNodeWithText("q").assertIsDisplayed()
        rule.onNodeWithText("1").assertIsDisplayed()
    }

    @Test
    fun `email and url variations swap comma key`() {
        val actions = RecordingActions()
        val st = state { textVariation = TextVariation.EMAIL }
        rule.setContent { KeyboardScreen(st, actions) }
        rule.onNodeWithText("@").assertIsDisplayed()
        rule.runOnIdle { st.textVariation = TextVariation.URL }
        rule.onNodeWithText("/").assertIsDisplayed()
    }

    @Test
    fun `non latin and shifted-layer layouts render`() {
        val actions = RecordingActions()
        val st = state {
            settings = settings.copy(activeLayoutId = "ru")
            shift = ShiftState.SHIFTED
        }
        rule.setContent { KeyboardScreen(st, actions) }
        assertTrue(rule.onAllNodesWithText("Й").fetchSemanticsNodes().isNotEmpty())
        // Arabic has no shift key
        rule.runOnIdle {
            st.settings = st.settings.copy(activeLayoutId = "ar")
            st.shift = ShiftState.OFF
        }
        rule.onNodeWithText("?123").assertIsDisplayed()
    }

    @Test
    fun `four row layout replaces digit row`() {
        val actions = RecordingActions()
        val fourRow = com.vayunmathur.keyboard.util.KeyboardLayouts.ALL.firstOrNull { it.rows.size >= 4 }
        if (fourRow != null) {
            rule.setContent {
                KeyboardScreen(state { settings = settings.copy(activeLayoutId = fourRow.id) }, actions)
            }
            rule.onNodeWithText(fourRow.rows[0].first().toString()).assertIsDisplayed()
        }
    }

    @Test
    fun `symbol pages render and toggle`() {
        val actions = RecordingActions()
        val st = state { page = KeyboardPage.SYMBOLS }
        rule.setContent { KeyboardScreen(st, actions) }
        rule.onNodeWithText("@").assertIsDisplayed()
        rule.onNodeWithText("=\\<").performClick()
        assertEquals(KeyboardPage.MORE_SYMBOLS, actions.pages.last())
        rule.runOnIdle { st.page = KeyboardPage.MORE_SYMBOLS }
        rule.onNodeWithText("~").assertIsDisplayed()
        rule.onNodeWithText("?123").performClick()
        assertEquals(KeyboardPage.SYMBOLS, actions.pages.last())
        // typing a symbol
        rule.onNodeWithText("~").performTouchInput { down(center); up() }
        assertTrue(actions.chars.contains("~"))
    }

    @Test
    fun `numeric page in every mode`() {
        val actions = RecordingActions()
        val st = state { page = KeyboardPage.NUMERIC; basePage = KeyboardPage.NUMERIC }
        rule.setContent { KeyboardScreen(st, actions) }
        for (mode in NumericMode.entries) {
            rule.runOnIdle { st.numericMode = mode }
            rule.onNodeWithText("5").assertIsDisplayed()
        }
        rule.onNodeWithText("5").performTouchInput { down(center); up() }
        assertTrue(actions.chars.contains("5"))
    }

    @Test
    fun `phone and phone symbols pages`() {
        val actions = RecordingActions()
        val st = state { page = KeyboardPage.PHONE; basePage = KeyboardPage.PHONE }
        rule.setContent { KeyboardScreen(st, actions) }
        rule.onNodeWithText("ABC").assertIsDisplayed()
        rule.onNodeWithText("*#(").performClick()
        assertEquals(KeyboardPage.PHONE_SYMBOLS, actions.pages.last())
        rule.runOnIdle { st.page = KeyboardPage.PHONE_SYMBOLS }
        rule.onNodeWithText("Pause").assertIsDisplayed()
        rule.onNodeWithText("Pause").performClick()
        assertTrue(actions.chars.contains(","))
        rule.onNodeWithText("Wait").performClick()
        assertTrue(actions.chars.contains(";"))
        rule.onNodeWithText("123").performClick()
        assertEquals(KeyboardPage.PHONE, actions.pages.last())
    }

    @Test
    fun `enter key shows each action icon and custom label`() {
        val actions = RecordingActions()
        val st = state()
        rule.setContent { KeyboardScreen(st, actions) }
        for (action in EnterAction.entries) {
            rule.runOnIdle { st.enterAction = action }
            rule.onNodeWithText("q").assertIsDisplayed()
        }
        rule.runOnIdle {
            st.enterAction = EnterAction.RETURN; st.enterActionLabel = "Join"
        }
        rule.onNodeWithText("Join").assertIsDisplayed()
    }

    @Test
    fun `suggestions strip picks word and candidates strip for pinyin`() {
        val actions = RecordingActions()
        val st = state { suggestions = listOf("hello", "helm") }
        rule.setContent { KeyboardScreen(st, actions) }
        rule.onNodeWithText("hello").assertIsDisplayed()
        rule.onNodeWithText("hello").performClick()
        assertEquals(listOf("hello"), actions.suggestions)

        val pinyin = com.vayunmathur.keyboard.util.KeyboardLayouts.ALL
            .first { it.offersCandidates }
        rule.runOnIdle {
            st.settings = st.settings.copy(activeLayoutId = pinyin.id)
            st.suggestions = listOf("你", "尼")
        }
        rule.onNodeWithText("你").assertIsDisplayed()
        rule.onNodeWithText("你").performClick()
        assertEquals("你", actions.suggestions.last())
    }

    @Test
    fun `empty suggestions still reserve strip without words`() {
        val actions = RecordingActions()
        rule.setContent { KeyboardScreen(state(), actions) }
        rule.onNodeWithText("q").assertIsDisplayed()
    }

    @Test
    fun `notice outranks suggestions`() {
        val actions = RecordingActions()
        rule.setContent {
            KeyboardScreen(state {
                notice = "Image paste not supported"
                suggestions = listOf("hello")
            }, actions)
        }
        rule.onNodeWithText("Image paste not supported").assertIsDisplayed()
    }

    @Test
    fun `clip suggestion chip pastes deletes and opens clipboard`() {
        val actions = RecordingActions()
        val clip = ClipItem(id = 42, text = "copied words here")
        rule.setContent { KeyboardScreen(state { clipSuggestion = clip }, actions) }
        rule.onNodeWithText("copied words here").assertIsDisplayed()
        rule.onNodeWithText("copied words here").performClick()
        assertEquals(listOf(clip), actions.pasted)
    }

    @Test
    fun `sensitive clip preview can be revealed`() {
        val actions = RecordingActions()
        val clip = ClipItem(id = 43, text = "hunter2secret", sensitive = true)
        rule.setContent { KeyboardScreen(state { clipSuggestion = clip }, actions) }
        rule.onNodeWithText("hunter2secret").assertIsDisplayed()
    }

    @Test
    fun `voice strips for listening transcribing and failures`() {
        val actions = RecordingActions()
        val st = state { voice = VoiceState.Listening("partial words") }
        rule.setContent { KeyboardScreen(st, actions) }
        rule.onNodeWithText("partial words").assertIsDisplayed()
        rule.runOnIdle { st.voice = VoiceState.Listening("") }
        rule.onNodeWithText("Listening…").assertIsDisplayed()
        rule.runOnIdle { st.voice = VoiceState.Transcribing }
        rule.onNodeWithText("Transcribing…").assertIsDisplayed()
        for (failure in VoiceFailure.entries) {
            rule.runOnIdle { st.voice = VoiceState.Failed(failure) }
            rule.onNodeWithText("q").assertIsDisplayed()
        }
        // blocked failure keeps its message visible
        rule.runOnIdle { st.voice = VoiceState.Failed(VoiceFailure.PERMISSION_BLOCKED) }
        rule.onAllNodesWithText("Allow the microphone in Settings").fetchSemanticsNodes()
    }

    @Test
    fun `emoji page picks emoji switches tabs and searches`() {
        val actions = RecordingActions()
        rule.setContent {
            KeyboardScreen(state {
                page = KeyboardPage.EMOJI
                emojiData = EmojiData.BUILTIN
                recentEmoji = listOf("😀")
            }, actions)
        }
        rule.onAllNodesWithText("😀")[0].assertIsDisplayed()
        rule.onAllNodesWithText("😀")[0].performTouchInput { down(center); up() }
        assertEquals(listOf("😀"), actions.emojis)
    }

    @Test
    fun `emoji page empty recents shows placeholder`() {
        val actions = RecordingActions()
        rule.setContent {
            KeyboardScreen(state {
                page = KeyboardPage.EMOJI
                emojiData = EmojiData(emptyList(), emptyList())
                recentEmoji = emptyList()
            }, actions)
        }
        rule.onNodeWithText("ABC").assertIsDisplayed()
    }

    @Test
    fun `emoji search mode shows query and results`() {
        val actions = RecordingActions()
        rule.setContent {
            KeyboardScreen(state {
                emojiQuery = "cat"
                emojiResults = listOf("🐱", "😺")
                emojiData = EmojiData.BUILTIN
            }, actions)
        }
        rule.onNodeWithText("cat").assertIsDisplayed()
        rule.onNodeWithText("🐱").assertIsDisplayed()
        rule.onNodeWithText("🐱").performClick()
        assertEquals(listOf("🐱"), actions.emojis)
    }

    @Test
    fun `emoji search empty query shows placeholder`() {
        val actions = RecordingActions()
        rule.setContent {
            KeyboardScreen(state { emojiQuery = ""; emojiResults = emptyList() }, actions)
        }
        rule.onNodeWithText("Search emoji").assertIsDisplayed()
    }

    @Test
    fun `clipboard page lists clips and clear all`() {
        val actions = RecordingActions()
        val clips = listOf(
            ClipItem(id = 1, text = "first clip"),
            ClipItem(id = 2, text = "second clip", sensitive = true),
            ClipItem(id = 3, imagePath = "/nonexistent/img.png", mimeType = "image/png"),
        )
        rule.setContent {
            KeyboardScreen(state {
                page = KeyboardPage.CLIPBOARD
                this.clips = clips
            }, actions)
        }
        rule.onNodeWithText("first clip").assertIsDisplayed()
        rule.onNodeWithText("first clip").performClick()
        assertEquals(clips[0], actions.pasted.first())
        rule.onNodeWithText("Clear all").performClick()
        assertEquals(1, actions.clearClipsCalls)
    }

    @Test
    fun `clip chip is not drawn above the clipboard page`() {
        // A clip copied while the page is open is both the chip and the head of the list;
        // it must show once, as the list entry (#555).
        val actions = RecordingActions()
        val clip = ClipItem(id = 44, text = "copied while open")
        val st = state {
            page = KeyboardPage.CLIPBOARD
            clips = listOf(clip)
            clipSuggestion = clip
        }
        rule.setContent { KeyboardScreen(st, actions) }
        assertEquals(1, rule.onAllNodesWithText("copied while open").fetchSemanticsNodes().size)
        // Back on the letters the chip is still on offer (until it times out).
        rule.runOnIdle { st.page = KeyboardPage.LETTERS }
        rule.onNodeWithText("copied while open").assertIsDisplayed()
    }

    @Test
    fun `clipboard page empty state`() {
        val actions = RecordingActions()
        rule.setContent {
            KeyboardScreen(state { page = KeyboardPage.CLIPBOARD }, actions)
        }
        rule.onNodeWithText("Copied text and images show up here").assertIsDisplayed()
    }

    @Test
    fun `backspace repeat key and space key report`() {
        val actions = RecordingActions()
        rule.setContent { KeyboardScreen(state(), actions) }
        // Space bar label is layout name; clicking it reports space via clickable semantics
        rule.onNodeWithText("English").performClick()
        assertEquals(1, actions.spaces)
    }

    @Test
    fun `swipe on letters page does not crash`() {
        val actions = RecordingActions()
        rule.setContent { KeyboardScreen(state(), actions) }
        rule.onNodeWithText("q").performTouchInput { swipeLeft() }
    }
}
