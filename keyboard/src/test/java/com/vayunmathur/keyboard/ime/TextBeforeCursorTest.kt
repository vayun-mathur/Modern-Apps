package com.vayunmathur.keyboard.ime

import com.vayunmathur.keyboard.util.KeyboardLayouts
import com.vayunmathur.keyboard.util.KeyboardPage
import com.vayunmathur.keyboard.util.ShiftState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextBeforeCursorTest {

    @Test
    fun `unknown mirror returns null until filled`() {
        val t = TextBeforeCursor()
        assertNull(t.peek())
        t.onStartInput(0, 0)
        assertNull(t.peek())
        t.fill("hi")
        assertEquals("hi", t.peek()?.toString())
    }

    @Test
    fun `short fill at start answers even below needed length`() {
        val t = TextBeforeCursor()
        t.fill("a")
        assertEquals("a", t.peek()?.toString())
    }

    @Test
    fun `committed and composing edits are mirrored`() {
        val t = TextBeforeCursor()
        t.onStartInput(0, 0)
        t.fill("")
        t.committed("hello")
        assertEquals("hello", t.peek()?.toString())
        t.composing(" wor")
        // composing text is not final, so peek still shows committed only
        assertEquals("hello", t.peek()?.toString())
        t.composingFinished(" wor")
        assertEquals("hello wor", t.peek()?.toString())
    }

    @Test
    fun `committed replaces open composition`() {
        val t = TextBeforeCursor()
        t.fill("ab")
        t.composing("cd")
        t.committed("X")
        assertEquals("abX", t.peek()?.toString())
    }

    @Test
    fun `delete removes code points including surrogate pairs`() {
        val t = TextBeforeCursor()
        t.fill("a\uD83D\uDE00b")
        t.deleted()
        assertEquals("a\uD83D\uDE00", t.peek()?.toString())
        t.deleted()
        assertEquals("a", t.peek()?.toString())
        t.deleted()
        assertEquals("", t.peek()?.toString())
        // At start with empty window: deleting is a no-op for the mirror.
        t.deleted()
        assertEquals("", t.peek()?.toString())
    }

    @Test
    fun `delete on unknown mirror only moves guessed cursor`() {
        val t = TextBeforeCursor()
        t.onStartInput(5, 5)
        t.deleted()
        assertNull(t.peek())
        assertFalse(t.hasSelection)
    }

    @Test
    fun `delete past known window invalidates`() {
        val t = TextBeforeCursor()
        // Fill with a full window then commit more so atStart becomes false after trim.
        t.fill("x".repeat(TextBeforeCursor.WINDOW))
        t.committed("y".repeat(4))
        // Delete everything known; next delete should invalidate.
        repeat(TextBeforeCursor.WINDOW) { t.deleted() }
        t.deleted()
        assertNull(t.peek())
    }

    @Test
    fun `selection change from outside invalidates and resyncs`() {
        val t = TextBeforeCursor()
        t.onStartInput(0, 0)
        t.fill("hello")
        t.committed("!")
        // Cursor reported where our edits left it (1, after the committed "!"): mirror kept.
        t.onSelectionChanged(1, 1)
        assertEquals("hello!", t.peek()?.toString())
        assertFalse(t.hasSelection)
        // Unexpected position: external edit.
        t.onSelectionChanged(2, 4)
        assertTrue(t.hasSelection)
        assertNull(t.peek())
    }

    @Test
    fun `invalidate clears everything`() {
        val t = TextBeforeCursor()
        t.fill("hello")
        t.invalidate()
        assertNull(t.peek())
        assertFalse(t.hasSelection)
    }

    @Test
    fun `long committed text is trimmed to window`() {
        val t = TextBeforeCursor()
        t.fill("start")
        t.committed("z".repeat(40))
        val peeked = t.peek()?.toString()
        assertEquals(TextBeforeCursor.WINDOW, peeked?.length)
        assertTrue(peeked!!.endsWith("z"))
    }

    @Test
    fun `start input records selection`() {
        val t = TextBeforeCursor()
        t.onStartInput(3, 7)
        assertTrue(t.hasSelection)
        t.onStartInput(3, 3)
        assertFalse(t.hasSelection)
    }
}

class KeyboardStateTest {

    @Test
    fun `defaults are letters page with suggestions-ready state`() {
        val s = KeyboardState()
        assertEquals(KeyboardPage.LETTERS, s.page)
        assertEquals(KeyboardPage.LETTERS, s.basePage)
        assertEquals(ShiftState.OFF, s.shift)
        assertTrue(s.suggestions.isEmpty())
        assertEquals(EnterAction.RETURN, s.enterAction)
        assertNull(s.enterActionLabel)
        assertFalse(s.passwordField)
        assertEquals(TextVariation.NORMAL, s.textVariation)
        assertEquals(NumericMode.NUMBER, s.numericMode)
        assertFalse(s.forceAscii)
        assertEquals(0, s.bottomInsetPx)
        assertNull(s.emojiQuery)
        assertNull(s.clipSuggestion)
        assertNull(s.voice)
        assertNull(s.notice)
    }

    @Test
    fun `effective layout is active layout when not ascii forced`() {
        val s = KeyboardState()
        val ru = KeyboardLayouts.byId("ru")!!
        s.settings = s.settings.copy(activeLayoutId = ru.id)
        assertEquals(ru, s.effectiveLayout)
        s.forceAscii = true
        // Cyrillic is not Latin script: falls back to English QWERTY.
        assertEquals("en_qwerty", s.effectiveLayout.id)
    }

    @Test
    fun `effective layout keeps plain latin layout under ascii force`() {
        val s = KeyboardState()
        s.settings = s.settings.copy(activeLayoutId = "de_qwertz")
        s.forceAscii = true
        assertEquals("de_qwertz", s.effectiveLayout.id)
    }

    @Test
    fun `effective layout rejects composing latin layout under ascii force`() {
        val s = KeyboardState()
        val romaji = KeyboardLayouts.ALL.first { it.composer != null && it.isLatinScript }
        s.settings = s.settings.copy(activeLayoutId = romaji.id)
        assertEquals(romaji, s.effectiveLayout)
        s.forceAscii = true
        assertEquals("en_qwerty", s.effectiveLayout.id)
    }

    @Test
    fun `noop actions do nothing but are callable`() {
        val a = ImeActions.Noop
        a.onChar("x"); a.onBackspace(); a.onEnter(); a.onSpace(); a.onShift()
        a.setPage(KeyboardPage.EMOJI); a.commitSuggestion("word")
        a.startEmojiSearch(); a.endEmojiSearch(); a.commitEmoji("😀")
        a.pasteClip(com.vayunmathur.keyboard.util.ClipItem(id = 1, text = "t"))
        a.deleteClip(com.vayunmathur.keyboard.util.ClipItem(id = 1, text = "t"))
        a.clearClips(); a.dismissClipSuggestion()
        a.onVoiceInput(); a.stopVoiceInput(); a.dismissVoice(); a.openVoiceSettings()
    }

    @Test
    fun `voice states carry their payloads`() {
        assertEquals("hey", VoiceState.Listening("hey").partial)
        assertEquals(VoiceState.Transcribing, VoiceState.Transcribing)
        assertEquals(
            com.vayunmathur.keyboard.platform.VoiceFailure.BUSY,
            VoiceState.Failed(com.vayunmathur.keyboard.platform.VoiceFailure.BUSY).failure,
        )
    }
}
