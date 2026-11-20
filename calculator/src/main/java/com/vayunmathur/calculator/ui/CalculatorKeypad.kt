package com.vayunmathur.calculator.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.calculator.R
import com.vayunmathur.calculator.domain.AngleMode
import com.vayunmathur.calculator.platform.CalculatorActions
import com.vayunmathur.calculator.platform.CalculatorUiState
import com.vayunmathur.calculator.platform.HistoryEntry
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberHaptics

internal enum class KeyEmphasis { Digit, Operator, Primary, Function, Toggle }

/**
 * A keypad key. [second]/[secondPress] give an alternate label+action shown when the
 * "2nd" modifier is active (e.g. sin → sin⁻¹). [weight] lets a key span extra columns.
 *
 * [onPress] is last so the common single-action key reads as a trailing lambda:
 * `Key("7") { it.append("7") }`.
 */
internal class Key(
    val label: String,
    val emphasis: KeyEmphasis = KeyEmphasis.Digit,
    val weight: Float = 1f,
    val second: String? = null,
    val secondPress: ((CalculatorActions) -> Unit)? = null,
    val onPress: (CalculatorActions) -> Unit,
)

@Composable
internal fun RowScope.KeyButton(key: Key, actions: CalculatorActions, second: Boolean, onToggleSecond: () -> Unit) {
    val showSecond = second && key.second != null
    val label = if (showSecond) key.second else key.label
    val secondActive = key.emphasis == KeyEmphasis.Toggle && second
    // A light key tick on every press. Scoped to KeyButton so only the keypad keys tick —
    // the toolbar, dialogs and unit chips stay silent.
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
        modifier = Modifier.weight(key.weight).heightIn(min = 50.dp).padding(2.dp),
        shape = MaterialTheme.shapes.largeIncreased,
        colors = colors,
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = if (label.length >= 4) 13.sp else 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Builds the 8-row keypad for the given angle mode. */
internal fun buildCalculatorRows(angleMode: AngleMode): List<List<Key>> {
    fun ins(text: String): (CalculatorActions) -> Unit = { it.append(text) }
    return listOf(
        listOf(
            Key("2nd", KeyEmphasis.Toggle) {},
            Key("π", KeyEmphasis.Function, second = "φ", onPress = ins("π"), secondPress = ins("phi")),
            Key("e", KeyEmphasis.Function, second = "τ", onPress = ins("e"), secondPress = ins("tau")),
            Key("!", KeyEmphasis.Function) { it.append("!") },
            Key("AC", KeyEmphasis.Operator) { it.clear() },
        ),
        listOf(
            Key("sin", KeyEmphasis.Function, second = "sin⁻¹", onPress = ins("sin("), secondPress = ins("asin(")),
            Key("cos", KeyEmphasis.Function, second = "cos⁻¹", onPress = ins("cos("), secondPress = ins("acos(")),
            Key("tan", KeyEmphasis.Function, second = "tan⁻¹", onPress = ins("tan("), secondPress = ins("atan(")),
            Key("ln", KeyEmphasis.Function, second = "eˣ", onPress = ins("ln("), secondPress = ins("e^(")),
            Key("log", KeyEmphasis.Function, second = "10ˣ", onPress = ins("log("), secondPress = ins("10^(")),
        ),
        listOf(
            Key("^", KeyEmphasis.Function, second = "x²", onPress = ins("^"), secondPress = ins("^2")),
            Key("√", KeyEmphasis.Function, second = "∛", onPress = ins("√("), secondPress = ins("cbrt(")),
            Key("(", KeyEmphasis.Function) { it.append("(") },
            Key(")", KeyEmphasis.Function) { it.append(")") },
            Key(",", KeyEmphasis.Function, second = "EE", onPress = ins(","), secondPress = ins("E")),
        ),
        listOf(
            Key("MC", KeyEmphasis.Function) { it.memoryClear() },
            Key("MR", KeyEmphasis.Function) { it.memoryRecall() },
            Key("M+", KeyEmphasis.Function) { it.memoryAdd() },
            Key("M-", KeyEmphasis.Function) { it.memorySubtract() },
            Key(if (angleMode == AngleMode.DEGREES) "DEG" else "RAD", KeyEmphasis.Function) { it.toggleAngleMode() },
        ),
        listOf(
            Key("7") { it.append("7") },
            Key("8") { it.append("8") },
            Key("9") { it.append("9") },
            Key("÷", KeyEmphasis.Operator) { it.append("/") },
            Key("⌫", KeyEmphasis.Operator) { it.backspace() },
        ),
        listOf(
            Key("4") { it.append("4") },
            Key("5") { it.append("5") },
            Key("6") { it.append("6") },
            Key("×", KeyEmphasis.Operator) { it.append("*") },
            Key("%", KeyEmphasis.Operator) { it.append("%") },
        ),
        listOf(
            Key("1") { it.append("1") },
            Key("2") { it.append("2") },
            Key("3") { it.append("3") },
            Key("−", KeyEmphasis.Operator) { it.append("-") },
            Key("nCr", KeyEmphasis.Function, second = "nPr", onPress = ins("nCr("), secondPress = ins("nPr(")),
        ),
        listOf(
            Key("ans", KeyEmphasis.Function) { it.append("ans") },
            Key("0") { it.append("0") },
            Key(".") { it.append(".") },
            Key("+", KeyEmphasis.Operator) { it.append("+") },
            Key("=", KeyEmphasis.Primary) { it.evaluate() },
        ),
    )
}

/** The readout column: extracted so the compact (stacked) and expanded (side-by-side)
 * layouts share one implementation. Past calculations stack above the current input,
 * newest directly above it, each ringed by dividers. */
@Composable
internal fun DisplayColumn(state: CalculatorUiState, actions: CalculatorActions, modifier: Modifier = Modifier) {
    val verticalScroll = rememberScrollState()
    // New evaluations prepend to history; keep the current equation pinned in view.
    LaunchedEffect(state.history.size) { verticalScroll.scrollTo(verticalScroll.maxValue) }
    Column(
        modifier.padding(horizontal = 20.dp).verticalScroll(verticalScroll),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End,
    ) {
        if (state.memory != 0.0) {
            Text("M", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
        }
        if (state.history.isNotEmpty()) {
            // Stored newest-first; reversed iteration lands the newest entry
            // directly above the current equation.
            state.history.reversed().forEach { entry ->
                HorizontalDivider()
                HistoryRow(entry, actions)
                HorizontalDivider()
            }
        }
        Text(
            com.vayunmathur.calculator.domain.renderInputForDisplay(state.input).ifEmpty { "0" }
                .replace("*", "×").replace("/", "÷").replace("-", "−"),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            textAlign = TextAlign.End,
            fontSize = 38.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            state.preview,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            textAlign = TextAlign.End,
            fontSize = 24.sp,
            maxLines = 1,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.unitOptions.isNotEmpty()) {
            // A wrapping row of filter chips rather than a ButtonGroup: the group is a
            // single-row control whose measure policy throws IllegalArgumentException
            // (maxWidth < minWidth) when the options overflow the available width, which
            // crashes the app as soon as a unit-bearing result is previewed. Chips wrap
            // to a second line instead and tolerate the narrow display column on both
            // compact and expanded layouts.
            UnitOptionsRow(state, actions)
        }
    }
}

/** One past calculation in the inline history stack: tapping reuses its result. */
@Composable
private fun HistoryRow(entry: HistoryEntry, actions: CalculatorActions, modifier: Modifier = Modifier) {
    val historyDescription = stringResource(
        R.string.history_entry_content_description,
        entry.expression,
        entry.result,
    )
    Column(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = { actions.useHistory(entry) })
            .semantics { contentDescription = historyDescription }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            entry.expression,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            maxLines = 2,
            softWrap = true,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "= ${entry.result}",
            fontSize = 20.sp,
            maxLines = 2,
            softWrap = true,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The keypad column: extracted so the compact (stacked) and expanded (side-by-side)
 * layouts share one implementation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitOptionsRow(state: CalculatorUiState, actions: CalculatorActions) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            stringResource(R.string.convert_to),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.unitOptions.forEach { unit ->
                FilterChip(
                    selected = unit == state.selectedUnit,
                    onClick = { actions.selectOutputUnit(unit.token) },
                    label = { Text(unit.symbol) },
                )
            }
        }
    }
}

/** The keypad column: extracted so the compact (stacked) and expanded (side-by-side)
 * layouts share one implementation. */
@Composable
internal fun KeypadColumn(
    rows: List<List<Key>>,
    actions: CalculatorActions,
    second: Boolean,
    onToggleSecond: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(4.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { key -> KeyButton(key, actions, second) { onToggleSecond() } }
            }
        }
    }
}
