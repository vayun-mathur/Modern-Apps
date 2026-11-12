package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.keyboard.ime.ImeActions
import com.vayunmathur.keyboard.ime.KeyboardState
import com.vayunmathur.keyboard.util.KeyboardPage
import com.vayunmathur.keyboard.util.Layouts
import com.vayunmathur.library.ui.IconPaste
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

/**
 * Root of the keyboard view: a suggestion strip (when enabled) above whichever page of keys
 * is active. All sizing is weight-based so it fills the width; key height scales with the
 * user's height-scale setting.
 *
 * The pieces below are split into their own composables along the lines of what changes
 * while typing: a keystroke produces new suggestions and usually flips shift, and reading
 * either of those here would redraw every key on the keyboard twice per keypress.
 */
@Composable
fun KeyboardScreen(state: KeyboardState, actions: ImeActions) {
    val scale = state.settings.keyHeightScale.coerceIn(0.8f, 1.4f)
    val keyHeight = (52 * scale).dp
    // The IME window doesn't reliably deliver insets to Compose, so pad the bottom by
    // the navigation-bar height the service measured from the window.
    // Clear the system nav bar / gesture area: measured inset plus a comfortable margin,
    // with a floor so there's breathing room even when the inset reads small (gesture nav).
    val bottomInset = with(LocalDensity.current) { state.bottomInsetPx.toDp() }
    val bottomPad = (bottomInset + 18.dp).coerceAtLeast(30.dp)
    // Derived rather than read straight: the query changes on every keystroke of a search,
    // and only arriving at or leaving search should rearrange the keyboard.
    val searching by remember(state) { derivedStateOf { state.emojiQuery != null } }
    val voice = state.voice
    // Key ticks are gated by the app's haptic setting; LocalHapticFeedback adds the system
    // setting on top. Provided here so no key or strip component needs the flag threaded in.
    CompositionLocalProvider(LocalKeyHapticsEnabled provides state.settings.haptic) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPad),
        ) {
            when {
                // Dictation outranks everything else in the strip: it is the only thing up
                // there that the user has to be able to see and stop.
                voice != null -> {
                    VoiceStrip(
                        height = StripHeight,
                        state = voice,
                        onStop = actions::stopVoiceInput,
                        onDismiss = actions::dismissVoice,
                        onOpenSettings = actions::openVoiceSettings,
                    )
                    KeyPage(state, actions, keyHeight)
                }
                // Search borrows the ordinary letter keys rather than shipping a second
                // keyboard: the query bar and the results sit on top of LettersPage, and the
                // service routes keystrokes into the query while this is showing.
                searching -> {
                    EmojiSearchStrip(state, actions)
                    LettersPage(state, actions, keyHeight)
                }
                else -> {
                    Strip(state, actions)
                    KeyPage(state, actions, keyHeight)
                }
            }
        }
    }
    }
}

/** The emoji-search query bar and its results, which change together on every keystroke. */
@Composable
private fun EmojiSearchStrip(state: KeyboardState, actions: ImeActions) {
    val query = state.emojiQuery ?: return
    EmojiSearchBar(height = StripHeight, query = query, onClose = actions::endEmojiSearch)
    EmojiSearchResults(
        height = StripHeight,
        results = state.emojiResults,
        onPick = actions::commitEmoji,
    )
}

/** Whatever occupies the strip above the keys, or nothing. */
@Composable
private fun Strip(state: KeyboardState, actions: ImeActions) {
    // A paste refusal explains itself here: the strip is the IME's only channel —
    // there is no scaffold for a snackbar and Toast is banned repo-wide.
    state.notice?.let {
        NoticeStrip(text = it)
        return
    }
    // A fresh clip outranks suggestions because the two never really compete: the chip is
    // offered before anything has been typed and the service drops it on the first keypress,
    // which is exactly when suggestions appear. The chip carries its own open button, so it
    // stands in for the whole strip rather than sitting beside the clipboard key.
    // Never above the clipboard page itself, which already lists that clip first: a copy
    // made while the page is open would otherwise draw it twice (#555).
    val clip = state.clipSuggestion
    if (clip != null && state.page != KeyboardPage.CLIPBOARD) {
        ClipboardStrip(
            height = StripHeight,
            item = clip,
            onOpen = { actions.setPage(KeyboardPage.CLIPBOARD) },
            onPaste = { actions.pasteClip(clip) },
            onDelete = { actions.deleteClip(clip) },
        )
        return
    }
    // The strip belongs to text entry only; numeric/phone/emoji pages never compose words,
    // so (like FUTO) they show no strip.
    val page = state.page
    val textPage = page == KeyboardPage.LETTERS ||
        page == KeyboardPage.SYMBOLS ||
        page == KeyboardPage.MORE_SYMBOLS
    if (!textPage) return
    // Nor do layouts that have nothing to put in it: the one dictionary we ship is English.
    // The effective layout matters here, not the active one: an ASCII-forced field on a
    // CJK layout shows plain Latin, never candidates.
    val layout = state.effectiveLayout
    // Candidates are not a suggestion the user can decline — on a Chinese layout they
    // are the only way a character gets typed — so the "show suggestions" preference
    // does not apply to them.
    val candidates = layout.offersCandidates
    val words = layout.englishDictionary && state.settings.showSuggestions
    val clipboard = state.settings.clipboardEnabled
    if (!candidates && !words && !clipboard) return
    Row(Modifier.fillMaxWidth().height(StripHeight)) {
        Box(Modifier.weight(1f)) {
            when {
                candidates -> CandidateStrip(
                    height = StripHeight,
                    candidates = state.suggestions,
                    onPick = actions::commitSuggestion,
                )
                words -> SuggestionStrip(
                    height = StripHeight,
                    suggestions = state.suggestions,
                    onPick = actions::commitSuggestion,
                )
            }
        }
        // The clipboard lives here rather than on the bottom row (issue #515): it gives the
        // space bar and its neighbours back a key's width, and this row is present on the
        // symbol pages too, so the clipboard does not vanish behind ?123.
        if (clipboard) {
            val stripTick = rememberKeyHapticTick()
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        stripTick()
                        actions.setPage(KeyboardPage.CLIPBOARD)
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                IconPaste(
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One line of strip text for paste refusals and other notices the IME must surface inline. */
@Composable
private fun NoticeStrip(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(StripHeight),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** The active page of keys. */
@Composable
private fun KeyPage(state: KeyboardState, actions: ImeActions, keyHeight: Dp) {
    when (state.page) {
        KeyboardPage.LETTERS -> LettersPage(state, actions, keyHeight)
        KeyboardPage.SYMBOLS ->
            SymbolPage(state, actions, keyHeight, Layouts.SYMBOL_ROWS, KeyboardPage.MORE_SYMBOLS, "=\\<")
        KeyboardPage.MORE_SYMBOLS ->
            SymbolPage(state, actions, keyHeight, Layouts.MORE_SYMBOL_ROWS, KeyboardPage.SYMBOLS, "?123")
        KeyboardPage.NUMERIC -> NumericPage(state, actions, keyHeight)
        KeyboardPage.PHONE -> PhonePage(state, actions, keyHeight)
        KeyboardPage.PHONE_SYMBOLS -> PhoneSymbolsPage(state, actions, keyHeight)
        KeyboardPage.EMOJI -> EmojiPage(
            data = state.emojiData,
            recents = state.recentEmoji,
            keyHeight = keyHeight,
            rows = 4,
            onEmoji = actions::commitEmoji,
            onSearch = actions::startEmojiSearch,
            onBackspace = actions::onBackspace,
            onBack = { actions.setPage(state.basePage) },
        )
        KeyboardPage.CLIPBOARD -> ClipboardPage(
            clips = state.clips,
            keyHeight = keyHeight,
            rows = 4,
            onPaste = actions::pasteClip,
            onDelete = actions::deleteClip,
            onClearAll = actions::clearClips,
            onBackspace = actions::onBackspace,
            onBack = { actions.setPage(state.basePage) },
        )
    }
}

/** Height of the strip above the keys, shared by everything that can occupy it. */
internal val StripHeight = 44.dp
