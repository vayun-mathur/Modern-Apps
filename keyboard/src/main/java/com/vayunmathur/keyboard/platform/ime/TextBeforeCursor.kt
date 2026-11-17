package com.vayunmathur.keyboard.platform.ime

/**
 * A local mirror of the text immediately before the cursor, and of whether anything is
 * selected.
 *
 * Asking the field for either means a synchronous round-trip into the target app's process.
 * An IME that does that on every keystroke — which reading it for auto-capitalisation and
 * the double-space period amounts to — stalls for as long as that app's main thread is busy,
 * which is why a keyboard can feel fine in one app and stutter in another. So the service
 * reports its own edits here instead and reads the answer locally; the field is only
 * consulted when the mirror genuinely cannot answer.
 *
 * Telling our own edits from everyone else's is what the cursor arithmetic is for: each edit
 * says where it leaves the cursor, and a reported selection anywhere else came from outside
 * (a tap elsewhere in the field, the app rewriting it) and discards the mirror. Predicting
 * wrong therefore costs one reread and never a wrong answer.
 *
 * The mirror holds *final* text only. Callers must not read it while a composition is open,
 * which none do: auto-capitalisation gives up while a word is composing, and the space key
 * settles the composition before it looks behind the cursor.
 */
internal class TextBeforeCursor {

    private val window = StringBuilder()

    /** True when [window] is short because the field starts there, not because it was trimmed. */
    private var atStart = false

    private var known = false

    /** Where the cursor should now be, or -1 when that is anyone's guess. */
    private var cursor = -1

    /** Length of the composition sitting just before the cursor, which is not final yet. */
    private var composingLength = 0

    /** Whether the field has a non-empty selection, as last reported by the framework. */
    var hasSelection = false
        private set

    /** Start over on a new field, told where its cursor and selection are. */
    fun onStartInput(selStart: Int, selEnd: Int) {
        invalidate()
        cursor = selStart
        hasSelection = selStart != selEnd
    }

    /**
     * The text before the cursor, or null when the field has to be asked for it instead.
     * Only valid until the next edit: this is the live buffer, not a copy of it.
     */
    fun peek(): CharSequence? {
        if (!known) return null
        return if (atStart || window.length >= NEEDED) window else null
    }

    /** Take what the field answered, once [peek] has come back null. */
    fun fill(text: CharSequence) {
        window.setLength(0)
        window.append(text)
        atStart = text.length < WINDOW
        known = true
        trim()
    }

    /** Text committed as final, replacing any open composition. */
    fun committed(text: CharSequence) {
        moveCursor(text.length - composingLength)
        composingLength = 0
        append(text)
    }

    /** Text shown as an open composition, replacing any earlier one. */
    fun composing(text: CharSequence) {
        moveCursor(text.length - composingLength)
        composingLength = text.length
    }

    /** The open composition, [text], became final as it stands. */
    fun composingFinished(text: CharSequence) {
        composingLength = 0
        append(text)
    }

    /** One code point deleted from in front of the cursor (an emoji is two UTF-16 units). */
    fun deleted() {
        hasSelection = false
        if (!known) {
            // Guessing one unit; a wrong guess only costs a reread (see class docs).
            moveCursor(-1)
            return
        }
        when {
            window.isNotEmpty() -> {
                var units = 1
                if (window.last().isLowSurrogate() && window.length >= 2 &&
                    window[window.length - 2].isHighSurrogate()
                ) {
                    units = 2
                }
                window.delete(window.length - units, window.length)
                moveCursor(-units)
            }
            // The window was already empty and the field did not start there, so what is
            // left in front of the cursor now is something we never saw.
            !atStart -> {
                known = false
                moveCursor(-1)
            }
            // Otherwise the field starts here and there was nothing to delete.
        }
    }

    /** An edit whose outcome we cannot predict; the field is the only authority now. */
    fun invalidate() {
        window.setLength(0)
        atStart = false
        known = false
        cursor = -1
        composingLength = 0
    }

    /** Where the framework says the cursor ended up. Anywhere unexpected discards the mirror. */
    fun onSelectionChanged(selStart: Int, selEnd: Int) {
        val ours = selStart == cursor && selStart == selEnd
        if (!ours) {
            invalidate()
            // Resynced, so the next run of our own edits can be followed again.
            cursor = selStart
        }
        hasSelection = selStart != selEnd
    }

    private fun append(text: CharSequence) {
        hasSelection = false
        if (!known) return
        window.append(text)
        trim()
    }

    private fun trim() {
        if (window.length <= WINDOW) return
        window.delete(0, window.length - WINDOW)
        atStart = false
    }

    private fun moveCursor(delta: Int) {
        if (cursor >= 0) cursor = (cursor + delta).coerceAtLeast(0)
    }

    companion object {
        /** How much text to keep, and to ask the field for when the mirror has run dry. */
        const val WINDOW = 16

        /** The furthest back any caller looks; below this the mirror cannot answer. */
        private const val NEEDED = 2
    }
}
