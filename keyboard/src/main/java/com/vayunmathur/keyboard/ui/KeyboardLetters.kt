package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.vayunmathur.keyboard.platform.ime.ImeActions
import com.vayunmathur.keyboard.platform.ime.KeyboardState
import com.vayunmathur.keyboard.domain.KeyboardLayout
import com.vayunmathur.keyboard.domain.Layouts
import com.vayunmathur.keyboard.domain.ShiftState
import com.vayunmathur.library.ui.IconBackspace

/**
 * The letter page for whichever layout is active. Rows differ in length between layouts
 * (QWERTY is 10/9/7, ЙЦУКЕН is 12/11/9), so short rows are centred inside the widest one
 * and the shift/backspace pair takes whatever the bottom row leaves over — which reproduces
 * QWERTY's familiar 0.5 spacers and 1.5-wide shift without hard-coding them.
 *
 * A layout may also have four rows (注音, JIS kana). Those take the digit row's place rather
 * than stacking on top of it, which is what the physical keyboards they come from do.
 */
@Composable
internal fun LettersPage(state: KeyboardState, actions: ImeActions, keyHeight: Dp) {
    // ASCII-forced fields (Compose Ascii) fall back to plain Latin regardless of the
    // user's layout; the setting itself is untouched.
    val layout = state.effectiveLayout
    val rows = layout.rows
    if (state.settings.numberRow && rows.size < 4) {
        Row(Modifier.fillMaxWidth()) {
            DIGITS.forEach {
                SymbolKey(it, keyHeight, actions, alternates = Layouts.DIGIT_ALTERNATES[it].orEmpty())
            }
        }
    }
    // With no persistent number row, the top letter row doubles as one under a long press.
    // The two are mutually exclusive so a digit is never reachable two ways at once, and
    // layouts with four rows already spend that row on their own script.
    val topRowDigits = !state.settings.numberRow && rows.size < 4
    LetterRows(state, layout, actions, keyHeight, topRowDigits)
    // Email/URL fields surface @ or / where the comma usually sits.
    val commaChar = when (state.textVariation) {
        com.vayunmathur.keyboard.platform.ime.TextVariation.EMAIL -> "@"
        com.vayunmathur.keyboard.platform.ime.TextVariation.URL -> "/"
        else -> layout.comma
    }
    BottomRow(
        state, actions, keyHeight,
        leftLabel = "?123", leftTarget = com.vayunmathur.keyboard.domain.KeyboardPage.SYMBOLS,
        commaChar = commaChar, periodChar = layout.period,
    )
}

/**
 * The letter rows proper, which are the only part of the page whose labels depend on shift.
 * Separated from the rest so that a shift change — which auto-capitalisation makes on most
 * keystrokes — redraws these and leaves the number row and the bottom row alone.
 */
@Composable
internal fun LetterRows(
    state: KeyboardState,
    layout: KeyboardLayout,
    actions: ImeActions,
    keyHeight: Dp,
    topRowDigits: Boolean,
) {
    val rows = layout.rows
    val shift = state.shift
    val slack = { row: Int -> (layout.width - rows[row].length) / 2f }
    for (r in 0 until rows.size - 1) {
        Row(Modifier.fillMaxWidth()) {
            if (slack(r) > 0f) Spacer(Modifier.weight(slack(r)))
            rows[r].forEachIndexed { i, c ->
                LetterKey(
                    layout, r, i, c, shift, keyHeight, actions,
                    digit = if (topRowDigits && r == 0 && i < 10) DIGITS[i].toString() else null,
                )
            }
            if (slack(r) > 0f) Spacer(Modifier.weight(slack(r)))
        }
    }
    val bottom = rows.size - 1
    Row(Modifier.fillMaxWidth()) {
        val edge = slack(bottom).coerceAtLeast(1.25f)
        // Arabic, Hebrew and Persian have neither case nor a shift layer; the key would do
        // nothing, so the row keeps its alignment with a gap instead.
        if (layout.hasShift) {
            ShiftKey(keyHeight, edge, shift, actions::onShift)
        } else {
            Spacer(Modifier.weight(edge))
        }
        rows[bottom].forEachIndexed { i, c ->
            LetterKey(layout, bottom, i, c, shift, keyHeight, actions)
        }
        RepeatKey(keyHeight, edge, actions::onBackspace) { IconBackspace() }
    }
}

/**
 * One letter key. What shift produces is the layout's business — upper case for most, a
 * whole second character for Devanagari, Thai, Georgian and Turkish's dotted i — so the
 * label comes from [KeyboardLayout.charAt] rather than from `uppercaseChar()` here.
 *
 * [digit] is the number this key doubles as when there is no persistent number row. It goes
 * first in the alternates so it lands under the finger the moment the popup opens.
 */
@Composable
internal fun RowScope.LetterKey(
    layout: KeyboardLayout,
    row: Int,
    col: Int,
    c: Char,
    shift: ShiftState,
    keyHeight: Dp,
    actions: ImeActions,
    digit: String? = null,
) {
    val shifted = shift != ShiftState.OFF
    val display = layout.charAt(row, col, shifted)
    // Per character, not String.uppercase(): that maps ß to "SS", which lengthens the
    // string and puts a stray S in the popup where the ß the user wanted used to be.
    val accents = layout.alternates[c].orEmpty()
        .let { if (shifted) it.map(Char::uppercaseChar).joinToString("") else it }
    CharKey(
        label = display,
        height = keyHeight,
        cornerHint = digit,
        alternates = digit.orEmpty() + accents,
        onClick = { actions.onChar(display) },
        onAlternate = actions::onChar,
    )
}

internal const val DIGITS = "1234567890"
