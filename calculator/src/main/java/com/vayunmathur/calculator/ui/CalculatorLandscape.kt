package com.vayunmathur.calculator.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.calculator.platform.CalculatorActions
import com.vayunmathur.calculator.platform.CalculatorUiState
import com.vayunmathur.calculator.domain.renderInputForDisplay
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberHaptics

/**
 * Compact readout for short (landscape-phone) windows: the equation with its live
 * preview beneath, stacked above the keypad. The full [DisplayColumn] — history
 * stack, unit chips, large type — assumes portrait height and would eat the keypad's rows here.
 */
@Composable
internal fun LandscapeDisplay(state: CalculatorUiState, modifier: Modifier = Modifier) {
    val equation = renderInputForDisplay(state.input)
        .ifEmpty { "0" }
        .replace("*", "×").replace("/", "÷").replace("-", "−")
    Column(modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalAlignment = Alignment.End) {
        Text(
            equation,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            textAlign = TextAlign.End,
            fontSize = 26.sp,
            maxLines = 1,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            state.preview,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
            fontSize = 18.sp,
            maxLines = 1,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Keypad for short (landscape-phone) windows. The 8 portrait rows are re-flowed
 * into two side-by-side 4-row halves — digits/operators on the right where thumbs
 * expect them, scientific functions on the left — so 4 weight-distributed rows fit
 * the ~300dp of content height where 8 fixed-height rows overflowed. Keys take
 * their share of the available height (`weight`, `fillMaxHeight`) instead of a
 * fixed 50dp, which is what left the bottom row half-cut under the nav inset.
 * No scrolling: every key is on screen with no gesture needed.
 */
@Composable
internal fun LandscapeKeypad(
    rows: List<List<Key>>,
    actions: CalculatorActions,
    second: Boolean,
    onToggleSecond: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Portrait order is [2nd/pi row, trig row, powers/parens row, memory row,
    // 7-8-9 row, 4-5-6 row, 1-2-3 row, ans-0 row]. Re-flowed as scientific
    // (first four rows) | basic (last four rows).
    val scientific = rows.take(4)
    val basic = rows.drop(4)
    Row(modifier.padding(horizontal = 4.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            scientific.forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    row.forEach { key ->
                        LandscapeKeyButton(key, actions, second) { onToggleSecond() }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            basic.forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    row.forEach { key ->
                        LandscapeKeyButton(key, actions, second) { onToggleSecond() }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.LandscapeKeyButton(
    key: Key,
    actions: CalculatorActions,
    second: Boolean,
    onToggleSecond: () -> Unit,
) {
    val showSecond = second && key.second != null
    val label = if (showSecond) key.second else key.label
    val secondActive = key.emphasis == KeyEmphasis.Toggle && second
    // A light key tick on every press, matching KeyButton.
    val haptics = rememberHaptics()
    val colors = when (key.emphasis) {
        KeyEmphasis.Digit -> ButtonDefaults.filledTonalButtonColors()
        KeyEmphasis.Operator -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        KeyEmphasis.Primary -> ButtonDefaults.buttonColors()
        KeyEmphasis.Function -> ButtonDefaults.textButtonColors()
        KeyEmphasis.Toggle -> if (secondActive) ButtonDefaults.buttonColors() else ButtonDefaults.textButtonColors()
    }
    Button(
        onClick = {
            haptics.keyPress()
            when {
                key.emphasis == KeyEmphasis.Toggle -> onToggleSecond()
                showSecond -> key.secondPress?.invoke(actions)
                else -> key.onPress(actions)
            }
        },
        modifier = Modifier.weight(key.weight).fillMaxHeight().padding(2.dp),
        shape = MaterialTheme.shapes.largeIncreased,
        colors = colors,
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = if (label.length >= 4) 13.sp else 18.sp, maxLines = 1)
    }
}
