package com.vayunmathur.keyboard.ime

import com.vayunmathur.keyboard.util.KeyboardPage
import com.vayunmathur.keyboard.util.KeyboardSettings
import com.vayunmathur.keyboard.util.RecentEmoji
import com.vayunmathur.keyboard.util.ShiftState
import kotlinx.coroutines.launch

/**
 * Emoji search and recents split from KeyboardService (LargeClass/TooManyFunctions).
 * Behaviour identical.
 */

internal fun KeyboardService.startEmojiSearch() {
    feedback()
    kbState.emojiQuery = ""
    kbState.emojiResults = emptyList()
    kbState.page = KeyboardPage.LETTERS
    kbState.shift = ShiftState.OFF
}

internal fun KeyboardService.endEmojiSearch() {
    feedback()
    kbState.emojiQuery = null
    kbState.emojiResults = emptyList()
    kbState.page = KeyboardPage.EMOJI
}

internal fun KeyboardService.commitEmoji(emoji: String) {
    if (kbState.emojiQuery != null) {
        // Picking a result is the end of the search; leave the emoji page behind too,
        // since the user came here to type one thing into their message.
        kbState.emojiQuery = null
        kbState.emojiResults = emptyList()
        kbState.page = kbState.basePage
    }
    rememberEmoji(emoji)
    onChar(emoji)
}

/** Keep the recents tab up to date so common emoji stop needing a search at all. */
internal fun KeyboardService.rememberEmoji(emoji: String) {
    val recents = RecentEmoji.add(kbState.recentEmoji, emoji)
    if (recents == kbState.recentEmoji) return
    kbState.recentEmoji = recents
    scope.launch {
        ds.setString(KeyboardSettings.Keys.EMOJI_RECENTS, RecentEmoji.encode(recents))
    }
}

/** Route a keystroke into the emoji query instead of the field. True if it was consumed. */
internal fun KeyboardService.typeIntoSearch(text: String): Boolean {
    val query = kbState.emojiQuery ?: return false
    setQuery(query + text)
    return true
}

internal fun KeyboardService.setQuery(query: String) {
    kbState.emojiQuery = query
    kbState.emojiResults = kbState.emojiData.search(query)
    consumeShift()
}

internal fun KeyboardService.setPage(page: KeyboardPage) {
    feedback()
    if (kbState.emojiQuery != null) {
        kbState.emojiQuery = null
        kbState.emojiResults = emptyList()
    }
    // The fresh-clip chip is an offer to open or paste that clip; opening the clipboard
    // page takes it up (the clip heads the list there), so the offer is spent rather than
    // left to reappear when the user comes back to the letters (#555).
    if (page == KeyboardPage.CLIPBOARD) dismissClipSuggestion()
    kbState.page = page
}
