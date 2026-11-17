package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.keyboard.R
import com.vayunmathur.keyboard.platform.ime.ImeActions
import com.vayunmathur.keyboard.platform.ime.KeyboardState
import com.vayunmathur.keyboard.platform.ime.NumericMode
import com.vayunmathur.keyboard.domain.KeyboardPage
import com.vayunmathur.keyboard.domain.Layouts
import com.vayunmathur.library.ui.IconBackspace
import com.vayunmathur.library.ui.IconSpaceBar
import com.vayunmathur.library.ui.Text

@Composable
internal fun RowScope.SymbolKey(
    c: Char,
    keyHeight: Dp,
    actions: ImeActions,
    weight: Float = 1f,
    alternates: String = "",
) {
    CharKey(
        label = c.toString(),
        height = keyHeight,
        weight = weight,
        alternates = alternates,
        onClick = { actions.onChar(c.toString()) },
        onAlternate = actions::onChar,
    )
}

@Composable
internal fun SymbolPage(
    state: KeyboardState,
    actions: ImeActions,
    keyHeight: Dp,
    rows: List<String>,
    otherPage: KeyboardPage,
    toggleLabel: String,
) {
    Row(Modifier.fillMaxWidth()) {
        rows[0].forEach { SymbolKey(it, keyHeight, actions, alternates = Layouts.alternatesFor(it)) }
    }
    Row(Modifier.fillMaxWidth()) {
        rows[1].forEach { SymbolKey(it, keyHeight, actions, alternates = Layouts.alternatesFor(it)) }
    }
    Row(Modifier.fillMaxWidth()) {
        SpecialKey(keyHeight, 1.5f, onClick = { actions.setPage(otherPage) }) {
            Text(toggleLabel, fontSize = 14.sp)
        }
        rows[2].forEach { SymbolKey(it, keyHeight, actions, alternates = Layouts.alternatesFor(it)) }
        RepeatKey(keyHeight, 1.5f, actions::onBackspace) { IconBackspace() }
    }
    BottomRow(state, actions, keyHeight, leftLabel = "ABC", leftTarget = state.basePage)
}

/**
 * Numeric page, mirroring FUTO's `number.yaml`: functional operator columns flank three
 * digit columns, and the bottom row uses proportional widths so `0` lands centred under
 * the 2/5/8 column and every row shares the same left/right edges. Column widths are
 * FUTO's fractions — side keys 0.15, digits 0.2333, comma/period 0.1, the two "grow"
 * keys splitting the remainder — used directly as (relative) row weights.
 *
 * Date/time fields swap the side-column operators for their separators (`/`, `-` for
 * dates, `:` for times) per [NumericMode]; the shared NUMBER pad already carries every
 * key the number/decimal/signed Compose types need, so it is unchanged.
 */
@Composable
internal fun NumericPage(state: KeyboardState, actions: ImeActions, keyHeight: Dp) {
    val side = 0.15f
    val digit = 0.2333f
    val reg = 0.1f
    val grow = 0.1333f
    // Left-column operators per mode. NUMBER keeps the calculator set; date/time swap
    // in their separators (both `/` and `-` occur in dates, `:` in times). The
    // displaced operators stay one tap away on ?123.
    val leftKeys = when (state.numericMode) {
        NumericMode.NUMBER -> listOf("+", "-", "*")
        NumericMode.DATE -> listOf("/", "-", "*")
        NumericMode.TIME -> listOf(":", "-", "*")
        NumericMode.DATE_TIME -> listOf("/", ":", "-")
    }
    Row(Modifier.fillMaxWidth()) {
        NumFunctionKey(leftKeys[0], side, keyHeight, actions)
        SymbolKey('1', keyHeight, actions, digit)
        SymbolKey('2', keyHeight, actions, digit)
        SymbolKey('3', keyHeight, actions, digit)
        NumFunctionKey("%", side, keyHeight, actions)
    }
    Row(Modifier.fillMaxWidth()) {
        NumFunctionKey(leftKeys[1], side, keyHeight, actions)
        SymbolKey('4', keyHeight, actions, digit)
        SymbolKey('5', keyHeight, actions, digit)
        SymbolKey('6', keyHeight, actions, digit)
        SpecialKey(keyHeight, side, onClick = actions::onSpace) { IconSpaceBar() }
    }
    Row(Modifier.fillMaxWidth()) {
        NumFunctionKey(leftKeys[2], side, keyHeight, actions)
        SymbolKey('7', keyHeight, actions, digit)
        SymbolKey('8', keyHeight, actions, digit)
        SymbolKey('9', keyHeight, actions, digit)
        RepeatKey(keyHeight, side, actions::onBackspace) { IconBackspace() }
    }
    Row(Modifier.fillMaxWidth()) {
        SpecialKey(keyHeight, side, onClick = { actions.setPage(KeyboardPage.LETTERS) }) {
            Text(stringResource(R.string.abc), fontSize = 14.sp)
        }
        NumFunctionKey(",", reg, keyHeight, actions)
        SpecialKey(keyHeight, grow, onClick = { actions.setPage(KeyboardPage.SYMBOLS) }) {
            Text("?123", fontSize = 14.sp)
        }
        SymbolKey('0', keyHeight, actions, digit)
        NumFunctionKey("=", grow, keyHeight, actions)
        NumFunctionKey(".", reg, keyHeight, actions)
        EnterKey(state, actions, keyHeight, side)
    }
}

/** A character key with functional (dimmer) styling — used for the numeric page operators. */
@Composable
internal fun RowScope.NumFunctionKey(label: String, weight: Float, keyHeight: Dp, actions: ImeActions) {
    SpecialKey(keyHeight, weight, onClick = { actions.onChar(label) }) {
        Text(label, fontSize = 20.sp)
    }
}

/**
 * Phone dial-pad, mirroring FUTO's `phone.yaml`: an even 4-column grid with the digits
 * carrying their ABC/DEF letter hints, functional keys (−, space, ⌫) down the right edge,
 * and a toggle to the phone-symbols page. The enter key reflects the field's IME action.
 */
@Composable
internal fun PhonePage(state: KeyboardState, actions: ImeActions, keyHeight: Dp) {
    Row(Modifier.fillMaxWidth()) {
        PhoneKey('1', null, keyHeight, actions)
        PhoneKey('2', "ABC", keyHeight, actions)
        PhoneKey('3', "DEF", keyHeight, actions)
        NumFunctionKey("-", 1f, keyHeight, actions)
    }
    Row(Modifier.fillMaxWidth()) {
        PhoneKey('4', "GHI", keyHeight, actions)
        PhoneKey('5', "JKL", keyHeight, actions)
        PhoneKey('6', "MNO", keyHeight, actions)
        SpecialKey(keyHeight, 1f, onClick = actions::onSpace) { IconSpaceBar() }
    }
    Row(Modifier.fillMaxWidth()) {
        PhoneKey('7', "PQRS", keyHeight, actions)
        PhoneKey('8', "TUV", keyHeight, actions)
        PhoneKey('9', "WXYZ", keyHeight, actions)
        RepeatKey(keyHeight, 1f, actions::onBackspace) { IconBackspace() }
    }
    Row(Modifier.fillMaxWidth()) {
        SpecialKey(keyHeight, 1f, onClick = { actions.setPage(KeyboardPage.PHONE_SYMBOLS) }) {
            Text("*#(", fontSize = 16.sp)
        }
        PhoneKey('0', "+", keyHeight, actions)
        CharKey(".", keyHeight, 1f, onClick = { actions.onChar(".") })
        EnterKey(state, actions, keyHeight, 1f)
    }
}

/** A phone-page digit with its dial-pad letter hint (matches FUTO's phone layout). */
@Composable
internal fun RowScope.PhoneKey(c: Char, hint: String?, keyHeight: Dp, actions: ImeActions) {
    CharKey(label = c.toString(), height = keyHeight, hint = hint, onClick = { actions.onChar(c.toString()) })
}

/**
 * Phone symbols page, mirroring FUTO's `phone_shift.yaml`: brackets/slash, the dialer
 * pause (`,`) and wait (`;`) keys, `* # +`, and a toggle back to the dial pad.
 */
@Composable
internal fun PhoneSymbolsPage(state: KeyboardState, actions: ImeActions, keyHeight: Dp) {
    Row(Modifier.fillMaxWidth()) {
        SymbolKey('(', keyHeight, actions)
        SymbolKey('/', keyHeight, actions)
        SymbolKey(')', keyHeight, actions)
        NumFunctionKey("-", 1f, keyHeight, actions)
    }
    Row(Modifier.fillMaxWidth()) {
        SymbolKey('N', keyHeight, actions)
        PhoneWordKey("Pause", ",", keyHeight, actions)
        SymbolKey(',', keyHeight, actions)
        SpecialKey(keyHeight, 1f, onClick = actions::onSpace) { IconSpaceBar() }
    }
    Row(Modifier.fillMaxWidth()) {
        SymbolKey('*', keyHeight, actions)
        PhoneWordKey("Wait", ";", keyHeight, actions)
        SymbolKey('#', keyHeight, actions)
        RepeatKey(keyHeight, 1f, actions::onBackspace) { IconBackspace() }
    }
    Row(Modifier.fillMaxWidth()) {
        SpecialKey(keyHeight, 1f, onClick = { actions.setPage(KeyboardPage.PHONE) }) {
            Text("123", fontSize = 14.sp)
        }
        SymbolKey('+', keyHeight, actions)
        SymbolKey('.', keyHeight, actions)
        EnterKey(state, actions, keyHeight, 1f)
    }
}

/**
 * A word-labelled key (Pause/Wait) that commits the dialer control char. Rendered like a
 * normal (raised) key but with small text so the word fits.
 */
@Composable
internal fun RowScope.PhoneWordKey(label: String, commit: String, keyHeight: Dp, actions: ImeActions) {
    SpecialKey(keyHeight, 1f, containerColor = charKeyColor(), onClick = { actions.onChar(commit) }) {
        Text(label, fontSize = 13.sp)
    }
}
