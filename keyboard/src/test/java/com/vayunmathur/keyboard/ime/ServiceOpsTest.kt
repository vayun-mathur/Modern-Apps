package com.vayunmathur.keyboard.ime

import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.keyboard.util.ClipItem
import com.vayunmathur.keyboard.util.Dictionary
import com.vayunmathur.keyboard.util.KeyboardLayouts
import com.vayunmathur.keyboard.util.KeyboardPage
import com.vayunmathur.keyboard.util.ShiftState
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import android.os.Looper
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records every edit the service helpers ask the field to make. */
class FakeInputConnection : InputConnection {
    val committed = mutableListOf<String>()
    val composingTexts = mutableListOf<String>()
    var finishComposingCalls = 0
    var deletedCodePoints = 0
    var editorActions = mutableListOf<Int>()
    var textBefore = ""
    var editorActionResult = true

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = textBefore.takeLast(n)
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
    override fun getSelectedText(flags: Int): CharSequence? = null
    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = true
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        deletedCodePoints += beforeLength; return true
    }
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        composingTexts += text?.toString().orEmpty(); return true
    }
    override fun setComposingRegion(start: Int, end: Int): Boolean = true
    override fun finishComposingText(): Boolean { finishComposingCalls++; return true }
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        committed += text?.toString().orEmpty(); return true
    }
    override fun commitCompletion(text: CompletionInfo?): Boolean = true
    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = true
    override fun setSelection(start: Int, end: Int): Boolean = true
    override fun performEditorAction(editorAction: Int): Boolean {
        editorActions += editorAction; return editorActionResult
    }
    override fun performContextMenuAction(id: Int): Boolean = true
    override fun beginBatchEdit(): Boolean = true
    override fun endBatchEdit(): Boolean = true
    override fun sendKeyEvent(event: KeyEvent?): Boolean = true
    override fun clearMetaKeyStates(states: Int): Boolean = true
    override fun reportFullscreenMode(enabled: Boolean): Boolean = true
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = true
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = true
    override fun getHandler(): android.os.Handler? = null
    override fun closeConnection() {}
    override fun commitContent(
        inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?,
    ): Boolean = true
}

@RunWith(RobolectricTestRunner::class)
class ServiceOpsTest {

    private lateinit var service: KeyboardService
    private lateinit var controller: org.robolectric.android.controller.ServiceController<KeyboardService>

    @BeforeTest
    fun setUp() {
        controller = Robolectric.buildService(KeyboardService::class.java)
        service = controller.create().get()
        ShadowLooper.idleMainLooper()
    }

    @AfterTest
    fun tearDown() {
        runCatching { controller.destroy() }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `service creates state with default layout and no composer`() {
        assertNotNull(KeyboardLayouts.byId(service.kbState.settings.activeLayoutId))
        assertNull(service.composer)
        assertNotNull(service.kbState)
    }

    @Test
    fun `commit setComposing finish and delete mirror into before`() {
        val ic = FakeInputConnection()
        service.before.fill("")
        service.commit(ic, "hello")
        service.setComposing(ic, " wor")
        assertEquals(" wor", service.pendingComposition)
        service.finishComposing(ic)
        assertEquals("", service.pendingComposition)
        service.deleteBackward(ic)
        assertEquals(listOf("hello"), ic.committed)
        assertEquals(1, ic.deletedCodePoints)
        assertTrue(ic.finishComposingCalls >= 1)
    }

    @Test
    fun `textBeforeCursor asks field only when mirror empty`() {
        val ic = FakeInputConnection().apply { textBefore = "abc " }
        // currentInputConnection is null in Robolectric, so service falls back to ""
        assertEquals("", service.textBeforeCursor().toString())
        service.before.fill("cached ")
        assertEquals("cached ", service.textBeforeCursor().toString())
        assertEquals("abc ", ic.getTextBeforeCursor(10, 0).toString())
    }

    @Test
    fun `sentence and word start detection`() {
        service.before.fill("")
        assertTrue(service.isAtSentenceStart())
        assertTrue(service.isAtWordStart())
        service.before.fill("Hello. ")
        assertTrue(service.isAtSentenceStart())
        service.before.fill("Hello ")
        assertFalse(service.isAtSentenceStart())
        assertTrue(service.isAtWordStart())
        service.before.fill("Hello")
        assertFalse(service.isAtWordStart())
        service.before.fill("line\n")
        assertTrue(service.isAtSentenceStart())
        service.before.fill("x")
        assertFalse(service.isAtSentenceStart())
    }

    @Test
    fun `commitCurrentWord autocorrects known typo and finishes`() {
        service.dictionary = Dictionary.fromEntries(listOf("hello" to 100, "world" to 90, "help" to 80))
        val ic = FakeInputConnection()
        service.composing.append("helo")
        service.commitCurrentWord(ic, autoCorrect = true)
        assertEquals(0, service.composing.length)
        assertTrue(ic.finishComposingCalls >= 1)
        // no composing word: no-op
        val calls = ic.finishComposingCalls
        service.commitCurrentWord(ic, autoCorrect = true)
        assertEquals(calls, ic.finishComposingCalls)
    }

    @Test
    fun `updateSuggestions respects settings and composing`() {
        service.dictionary = Dictionary.fromEntries(listOf("hello" to 100, "helm" to 90, "help" to 80))
        service.composing.append("hel")
        service.updateSuggestions()
        assertTrue(service.kbState.suggestions.isNotEmpty())
        assertTrue(service.kbState.suggestions.all { it.startsWith("hel") })
        service.kbState.settings = service.kbState.settings.copy(showSuggestions = false)
        service.updateSuggestions()
        assertTrue(service.kbState.suggestions.isEmpty())
        service.kbState.settings = service.kbState.settings.copy(showSuggestions = true)
        service.composing.setLength(0)
        service.updateSuggestions()
        assertTrue(service.kbState.suggestions.isEmpty())
    }

    @Test
    fun `shift taps cycle and caps lock latches on rapid second tap`() {
        service.onShift()
        assertEquals(ShiftState.SHIFTED, service.kbState.shift)
        service.onShift()
        assertEquals(ShiftState.CAPS_LOCK, service.kbState.shift)
        service.onShift()
        assertEquals(ShiftState.OFF, service.kbState.shift)
        // consumeShift only clears one-shot shift
        service.kbState.shift = ShiftState.SHIFTED
        service.consumeShift()
        assertEquals(ShiftState.OFF, service.kbState.shift)
        service.kbState.shift = ShiftState.CAPS_LOCK
        service.consumeShift()
        assertEquals(ShiftState.CAPS_LOCK, service.kbState.shift)
    }

    @Test
    fun `emoji search lifecycle routes typing into query`() {
        service.startEmojiSearch()
        assertEquals("", service.kbState.emojiQuery)
        assertEquals(KeyboardPage.LETTERS, service.kbState.page)
        assertTrue(service.typeIntoSearch("cat"))
        assertEquals("cat", service.kbState.emojiQuery)
        service.onBackspace()
        assertEquals("ca", service.kbState.emojiQuery)
        service.setQuery("")
        service.onBackspace() // empty query leaves search
        assertNull(service.kbState.emojiQuery)
        assertEquals(KeyboardPage.EMOJI, service.kbState.page)
        service.startEmojiSearch()
        service.endEmojiSearch()
        assertNull(service.kbState.emojiQuery)
        assertEquals(KeyboardPage.EMOJI, service.kbState.page)
        assertFalse(service.typeIntoSearch("x"))
    }

    @Test
    fun `setPage clears emoji search`() {
        service.startEmojiSearch()
        service.setPage(KeyboardPage.SYMBOLS)
        assertNull(service.kbState.emojiQuery)
        assertEquals(KeyboardPage.SYMBOLS, service.kbState.page)
    }

    @Test
    fun `opening the clipboard page spends the fresh-clip chip`() {
        val item = ClipItem(id = 991101, text = "just copied")
        service.offerClip(item)
        // Other pages leave the offer standing; it only gives up after CLIP_CHIP_MS.
        service.setPage(KeyboardPage.SYMBOLS)
        assertEquals(item, service.kbState.clipSuggestion)
        // The clipboard page lists the clip itself, so the chip is spent (#555)...
        service.setPage(KeyboardPage.CLIPBOARD)
        assertNull(service.kbState.clipSuggestion)
        // ...and coming back to the letters does not resurrect it.
        service.setPage(KeyboardPage.LETTERS)
        assertNull(service.kbState.clipSuggestion)
    }

    @Test
    fun `commitEmoji remembers recent and leaves search`() {
        service.kbState.emojiData = com.vayunmathur.keyboard.util.EmojiData.BUILTIN
        service.startEmojiSearch()
        service.commitEmoji("😀")
        assertNull(service.kbState.emojiQuery)
        assertEquals("😀", service.kbState.recentEmoji.firstOrNull())
        // second commit of same emoji does not duplicate
        service.commitEmoji("😀")
        assertEquals(1, service.kbState.recentEmoji.count { it == "😀" })
        service.commitEmoji("🎉")
        assertEquals(listOf("🎉", "😀"), service.kbState.recentEmoji)
    }

    @Test
    fun `composer sync follows layout kind and ascii force`() {
        fun setLayout(id: String) {
            service.kbState.settings = service.kbState.settings.copy(activeLayoutId = id)
            service.composerKind = service.composerKind // keep
            // force rebuild
            service.syncComposer()
        }
        val hangul = KeyboardLayouts.ALL.first { it.composer == com.vayunmathur.keyboard.util.ComposerKind.HANGUL }
        setLayout(hangul.id)
        assertTrue(service.composer is HangulComposer)
        val romaji = KeyboardLayouts.ALL.first { it.composer == com.vayunmathur.keyboard.util.ComposerKind.ROMAJI }
        setLayout(romaji.id)
        assertTrue(service.composer is RomajiComposer)
        val kana = KeyboardLayouts.ALL.first { it.composer == com.vayunmathur.keyboard.util.ComposerKind.KANA }
        setLayout(kana.id)
        assertTrue(service.composer is KanaKeyComposer)
        val eth = KeyboardLayouts.ALL.first { it.composer == com.vayunmathur.keyboard.util.ComposerKind.ETHIOPIC }
        setLayout(eth.id)
        assertTrue(service.composer is EthiopicComposer)
        setLayout("en_qwerty")
        assertNull(service.composer)
        // ascii force disables composer
        setLayout(hangul.id)
        service.kbState.forceAscii = true
        service.syncComposer()
        assertNull(service.composer)
        service.kbState.forceAscii = false
    }

    @Test
    fun `applyComposition commits settled text and updates candidates`() {
        val ic = FakeInputConnection()
        val han = HanComposer(
            com.vayunmathur.keyboard.util.PinyinDictionary.fromEntries(listOf("ni" to "你尼", "hao" to "好")),
            SpellingScheme.Pinyin,
        )
        val r1 = han.accept("n")
        assertNotNull(r1)
        service.applyComposition(ic, han, r1)
        val r2 = han.accept("i")
        assertNotNull(r2)
        service.applyComposition(ic, han, r2)
        assertTrue(service.kbState.suggestions.isNotEmpty())
        // finishing applies final
        service.composer = han
        service.finishComposition(ic)
        assertTrue(service.kbState.suggestions.isEmpty())
    }

    @Test
    fun `configureForEditor maps field kinds to pages and actions`() {
        fun info(inputType: Int, imeOptions: Int = 0, actionLabel: CharSequence? = null, actionId: Int = 0) =
            EditorInfo().apply {
                this.inputType = inputType; this.imeOptions = imeOptions
                this.actionLabel = actionLabel; this.actionId = actionId
            }
        service.configureForEditor(info(InputType.TYPE_CLASS_PHONE))
        assertEquals(KeyboardPage.PHONE, service.kbState.page)
        service.configureForEditor(info(InputType.TYPE_CLASS_NUMBER))
        assertEquals(KeyboardPage.NUMERIC, service.kbState.page)
        assertEquals(NumericMode.NUMBER, service.kbState.numericMode)
        service.configureForEditor(
            info(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE),
        )
        assertEquals(NumericMode.DATE, service.kbState.numericMode)
        service.configureForEditor(
            info(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME),
        )
        assertEquals(NumericMode.TIME, service.kbState.numericMode)
        service.configureForEditor(info(InputType.TYPE_CLASS_DATETIME))
        assertEquals(NumericMode.DATE_TIME, service.kbState.numericMode)
        service.configureForEditor(
            info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        )
        assertEquals(TextVariation.EMAIL, service.kbState.textVariation)
        assertEquals(KeyboardPage.LETTERS, service.kbState.page)
        service.configureForEditor(
            info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
        )
        assertEquals(TextVariation.URL, service.kbState.textVariation)
        service.configureForEditor(
            info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME),
        )
        assertEquals(TextVariation.PERSON_NAME, service.kbState.textVariation)
        service.configureForEditor(
            info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
        )
        assertTrue(service.kbState.passwordField)
        service.configureForEditor(
            info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS),
        )
        assertTrue(service.kbState.forceAscii)
        assertEquals(TextVariation.NO_SUGGESTIONS, service.kbState.textVariation)
        service.configureForEditor(
            info(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
        )
        assertTrue(service.kbState.passwordField)
        // enter actions
        service.configureForEditor(info(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH))
        assertEquals(EnterAction.SEARCH, service.kbState.enterAction)
        assertTrue(service.enterSendsAction)
        service.configureForEditor(info(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND))
        assertEquals(EnterAction.SEND, service.kbState.enterAction)
        service.configureForEditor(info(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE))
        assertEquals(EnterAction.DONE, service.kbState.enterAction)
        service.configureForEditor(info(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_GO))
        assertEquals(EnterAction.GO, service.kbState.enterAction)
        service.configureForEditor(info(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT))
        assertEquals(EnterAction.NEXT, service.kbState.enterAction)
        service.configureForEditor(info(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_PREVIOUS))
        assertEquals(EnterAction.PREVIOUS, service.kbState.enterAction)
        service.configureForEditor(
            info(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_SEARCH),
        )
        assertEquals(EnterAction.RETURN, service.kbState.enterAction)
        assertFalse(service.enterSendsAction)
        service.configureForEditor(
            info(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED, "Join", 42),
        )
        assertEquals("Join", service.kbState.enterActionLabel)
        assertEquals(42, service.editorActionId)
        assertTrue(service.enterSendsAction)
    }

    @Test
    fun `updateAutoCapShift capitalizes at sentence start only for cased layouts`() {
        service.kbState.basePage = KeyboardPage.LETTERS
        service.before.fill("")
        service.updateAutoCapShift()
        assertEquals(ShiftState.SHIFTED, service.kbState.shift)
        service.before.fill("Done. ")
        service.updateAutoCapShift()
        assertEquals(ShiftState.SHIFTED, service.kbState.shift)
        service.before.fill("mid word")
        service.updateAutoCapShift()
        assertEquals(ShiftState.OFF, service.kbState.shift)
        service.kbState.passwordField = true
        service.before.fill("")
        service.kbState.shift = ShiftState.OFF
        service.updateAutoCapShift()
        assertEquals(ShiftState.OFF, service.kbState.shift)
        service.kbState.passwordField = false
        service.kbState.shift = ShiftState.CAPS_LOCK
        service.updateAutoCapShift()
        assertEquals(ShiftState.CAPS_LOCK, service.kbState.shift)
    }

    @Test
    fun `clipboard ops offer dismiss delete and clear`() {
        val item = ClipItem(id = 991001, text = "copied text")
        service.offerClip(item)
        assertEquals(item, service.kbState.clipSuggestion)
        service.dismissClipSuggestion()
        assertNull(service.kbState.clipSuggestion)
        service.clipboard.add(item)
        service.kbState.clips = service.clipboard.items
        service.deleteClip(item)
        assertTrue(service.kbState.clips.none { it.id == item.id })
        val item2 = ClipItem(id = 991002, text = "again")
        service.clipboard.add(item2)
        service.kbState.clips = service.clipboard.items
        service.clearClips()
        assertTrue(service.kbState.clips.isEmpty())
        service.showNotice("note")
        assertEquals("note", service.kbState.notice)
        // paste with no input connection returns after feedback
        service.pasteClip(item2)
    }

    @Test
    fun `commitImage refuses when editor or file missing`() {
        val ic = FakeInputConnection()
        val img = ClipItem(id = 991003, imagePath = "/nonexistent/x.png", mimeType = "image/png")
        service.commitImage(ic, img)
        assertNotNull(service.kbState.notice)
        val noMime = ClipItem(id = 991004, imagePath = "/nonexistent/x.png")
        service.commitImage(ic, noMime)
        assertNotNull(service.kbState.notice)
    }

    @Test
    fun `voice state transitions and failures`() {
        service.setVoiceState(VoiceState.Listening("par"))
        assertEquals(VoiceState.Listening("par"), service.kbState.voice)
        service.showVoiceFailure(com.vayunmathur.keyboard.platform.VoiceFailure.NO_SPEECH)
        assertTrue(service.kbState.voice is VoiceState.Failed)
        service.setVoiceState(VoiceState.Failed(com.vayunmathur.keyboard.platform.VoiceFailure.PERMISSION_BLOCKED))
        // blocked does not auto-clear: job is null
        assertNull(service.voiceMessageJob)
        service.dismissVoice()
        assertNull(service.kbState.voice)
        service.onVoicePermissionResult(com.vayunmathur.keyboard.platform.VoicePermissionResult.DENIED)
        assertTrue(service.kbState.voice is VoiceState.Failed)
        service.onVoicePermissionResult(com.vayunmathur.keyboard.platform.VoicePermissionResult.BLOCKED)
        assertEquals(
            com.vayunmathur.keyboard.platform.VoiceFailure.PERMISSION_BLOCKED,
            (service.kbState.voice as VoiceState.Failed).failure,
        )
        service.dismissVoice()
        // onVoiceInput when recognizer unavailable shows failure (Robolectric has none)
        service.onVoiceInput()
        assertTrue(service.kbState.voice is VoiceState.Failed || service.kbState.voice == null)
    }

    @Test
    fun `layoutIdOf and applyLayout switch active layout`() {
        service.applyLayout("de_qwertz")
        ShadowLooper.idleMainLooper()
        assertEquals("de_qwertz", service.kbState.settings.activeLayoutId)
        assertNull(service.layoutIdOf(null))
        // unknown subtype with no registered map falls back by language tag
        val subtype = android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeMode("keyboard").setLanguageTag("de").build()
        val resolved = service.layoutIdOf(subtype)
        assertTrue(resolved == null || resolved.startsWith("de"))
    }

    @Test
    fun `onChar onEnter onSpace without connection are safe no-ops beyond state`() {
        service.onChar("a")
        service.onEnter()
        service.onSpace()
        service.onBackspace()
        service.commitSuggestion("word")
        // emoji search path still consumes chars
        service.startEmojiSearch()
        service.onChar("s")
        assertEquals("s", service.kbState.emojiQuery)
        service.onEnter() // ends search
        assertNull(service.kbState.emojiQuery)
    }

    @Test
    fun `useComposing requires english layout and suggestions or autocorrect`() {
        assertTrue(service.useComposing())
        service.kbState.settings = service.kbState.settings.copy(showSuggestions = false, autoCorrect = false)
        assertFalse(service.useComposing())
        service.kbState.settings = service.kbState.settings.copy(showSuggestions = true, activeLayoutId = "ru")
        assertFalse(service.useComposing())
        service.kbState.settings = service.kbState.settings.copy(activeLayoutId = "en_qwerty")
        service.kbState.passwordField = true
        assertFalse(service.useComposing())
    }

    @Test
    fun `action handler delegates`() {
        val handler = KeyboardActionHandler(service)
        handler.onShift()
        handler.setPage(KeyboardPage.NUMERIC)
        assertEquals(KeyboardPage.NUMERIC, service.kbState.page)
        handler.startEmojiSearch()
        assertEquals("", service.kbState.emojiQuery)
        handler.endEmojiSearch()
        handler.dismissClipSuggestion()
        handler.dismissVoice()
        assertNull(service.kbState.voice)
    }

    @Test
    fun `pinyin layout loads chinese data and rebuilds composer`() {
        val pinyin = KeyboardLayouts.ALL.first {
            it.composer == com.vayunmathur.keyboard.util.ComposerKind.PINYIN_SIMPLIFIED
        }
        service.kbState.settings = service.kbState.settings.copy(activeLayoutId = pinyin.id)
        service.syncComposer()
        assertTrue(service.composer is HanComposer)
        // The asset load runs on the service scope (main + IO); wait for it to land.
        val deadline = System.currentTimeMillis() + 10_000
        while (service.pinyinSimplified === com.vayunmathur.keyboard.util.PinyinDictionary.EMPTY &&
            System.currentTimeMillis() < deadline
        ) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(25)
        }
        assertTrue(service.pinyinSimplified !== com.vayunmathur.keyboard.util.PinyinDictionary.EMPTY)
        assertTrue(service.pinyinSimplified.candidates("ni").isNotEmpty())
        assertTrue(service.composer is HanComposer)
        service.kbState.settings = service.kbState.settings.copy(activeLayoutId = "en_qwerty")
        service.syncComposer()
    }

    @Test
    fun `context is application`() {
        assertNotNull(ApplicationProvider.getApplicationContext<android.content.Context>())
    }
}
