package com.vayunmathur.keyboard.platform.ime

import android.view.inputmethod.InputConnection
import com.vayunmathur.keyboard.domain.ComposerKind
import com.vayunmathur.keyboard.domain.KeyboardPage
import com.vayunmathur.keyboard.data.PinyinDictionary
import kotlinx.coroutines.launch

/**
 * Composition-engine management split from KeyboardService (LargeClass/TooManyFunctions).
 * Behaviour identical.
 */

/** Point [KeyboardService.composer] at whatever the active layout needs, keeping it across no-op changes. */
internal fun KeyboardService.syncComposer() {
    // An ASCII-forced field falls back to plain Latin, so it never composes — even
    // when the user's own layout would (pinyin, kana, hangul).
    val kind = if (kbState.forceAscii) null else kbState.settings.activeLayout.composer
    if (kind == composerKind && (kind == null || composer != null)) return
    composerKind = kind
    composer = when (kind) {
        null -> null
        ComposerKind.HANGUL -> HangulComposer()
        ComposerKind.ROMAJI -> RomajiComposer()
        ComposerKind.KANA -> KanaKeyComposer()
        ComposerKind.ETHIOPIC -> EthiopicComposer()
        ComposerKind.PINYIN_SIMPLIFIED -> HanComposer(pinyinSimplified, SpellingScheme.Pinyin)
        ComposerKind.PINYIN_TRADITIONAL -> HanComposer(pinyinTraditional, SpellingScheme.Pinyin)
        ComposerKind.BOPOMOFO ->
            HanComposer(pinyinTraditional, SpellingScheme.Bopomofo(bopomofoSpellings))
    }
    if (kind?.needsChineseData == true) loadChineseData()
}

/**
 * Read the pinyin tables the first time a Chinese layout is chosen — they are ~130 KiB of
 * asset that a keyboard used for English should never touch. Until they arrive the engine
 * simply has no candidates, and the composer is rebuilt around them once it does.
 */
internal fun KeyboardService.loadChineseData() {
    if (loadingChinese || pinyinSimplified !== PinyinDictionary.EMPTY) return
    loadingChinese = true
    scope.launch {
        pinyinSimplified = PinyinDictionary.load(this@loadChineseData, "pinyin_sc")
        pinyinTraditional = PinyinDictionary.load(this@loadChineseData, "pinyin_tc")
        bopomofoSpellings = PinyinDictionary.loadBopomofo(this@loadChineseData)
        loadingChinese = false
        composerKind = null // force a rebuild now that there is something to look up in
        syncComposer()
    }
}

internal val ComposerKind.needsChineseData: Boolean
    get() = this == ComposerKind.PINYIN_SIMPLIFIED ||
        this == ComposerKind.PINYIN_TRADITIONAL ||
        this == ComposerKind.BOPOMOFO

/**
 * Apply one composition step: settled text (if any) replaces the composing region and
 * becomes final, then whatever is still being composed goes back under it.
 */
internal fun KeyboardService.applyComposition(ic: InputConnection, engine: Composer, result: ComposeResult) {
    if (result.commit.isNotEmpty()) commit(ic, result.commit)
    if (result.composing.isNotEmpty()) {
        setComposing(ic, result.composing)
    } else if (result.commit.isEmpty()) {
        // Nothing settled and nothing left: the last backspace emptied the composition.
        setComposing(ic, "")
        finishComposing(ic)
    }
    kbState.suggestions = engine.candidates
}

/** Make the composition final, giving the engine its last chance to rewrite it. */
internal fun KeyboardService.finishComposition(ic: InputConnection) {
    val engine = composer ?: return
    if (engine.isComposing) {
        val result = engine.finish()
        if (result != null && result.commit.isNotEmpty()) {
            commit(ic, result.commit)
        } else {
            finishComposing(ic)
        }
    }
    engine.reset()
    kbState.suggestions = emptyList()
}

/**
 * Composing (word tracking) is needed for either suggestions or autocorrect, and only
 * makes sense for plain text fields (never passwords, ASCII-only or no-suggestion
 * fields, or the numeric layout). The only dictionary we ship is English, so it also
 * stays off for every other layout rather than offering English words to someone
 * writing Greek.
 */
internal fun KeyboardService.useComposing(): Boolean =
    (kbState.settings.showSuggestions || kbState.settings.autoCorrect) &&
        !kbState.passwordField && !kbState.forceAscii &&
        kbState.textVariation != TextVariation.NO_SUGGESTIONS &&
        kbState.basePage == KeyboardPage.LETTERS &&
        kbState.effectiveLayout.englishDictionary
