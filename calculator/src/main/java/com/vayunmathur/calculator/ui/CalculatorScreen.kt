package com.vayunmathur.calculator.ui
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.calculator.platform.CalculatorActions
import com.vayunmathur.calculator.platform.CalculatorUiState
import com.vayunmathur.calculator.platform.CalculatorViewModel
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRuler
import com.vayunmathur.library.ui.VerticalDivider
import com.vayunmathur.library.ui.isCompactHeight
import com.vayunmathur.library.ui.isExpandedWidth

/**
 * The keypad screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@Composable
fun CalculatorScreen(
    state: CalculatorUiState,
    actions: CalculatorActions,
    /**
     * Seeds for the screen's own UI-only state (whether the "2nd" modifier is latched,
     * whether the unit picker sheet is open). The app always takes the defaults;
     * previews set them so a given screen can be captured without driving the UI to get there.
     */
    initialSecond: Boolean = false,
    initialShowUnitPicker: Boolean = false,
) {
    var second by remember { mutableStateOf(initialSecond) }
    var showUnitPicker by remember { mutableStateOf(initialShowUnitPicker) }

    val rows: List<List<Key>> = remember(state.angleMode) { buildCalculatorRows(state.angleMode) }

    AppScaffold(
        title = {},
        alignment = AppBarAlignment.Center,
        actions = {
            IconButton({ showUnitPicker = true }) { IconRuler() }
        },
    ) { padding ->
        // Three genuinely different windows, not two:
        //
        // - Compact height (landscape phone, <480dp tall): vertical space is the
        //   scarce axis. The portrait stack (display over an 8-row keypad) needs
        //   ~500dp+ of height and clips; the old side-by-side branch squeezed all
        //   8 rows into ~300dp of content height, so the nav inset (~24dp) plus
        //   the fixed 50dp keys overflowed and the bottom row rendered half-cut.
        //   Instead the keypad goes full-bleed edge to edge with weight-distributed
        //   keys (no fixed height, no outer 16dp padding), and the readout
        //   collapses to a single compact line above the keys. No scrolling, no
        //   clipping, no lost row.
        // - Expanded width (tablet/desktop): room for the sibling-tools split —
        //   display beside a capped keypad so keys stay usable.
        // - Everything else (portrait phone): the classic stacked layout.
        val compactHeight = isCompactHeight()
        val expandedWidth = isExpandedWidth()
        when {
            compactHeight -> {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    LandscapeDisplay(state, Modifier.fillMaxWidth())
                    LandscapeKeypad(
                        rows,
                        actions,
                        second,
                        { second = !second },
                        Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
            expandedWidth -> {
                Row(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                    DisplayColumn(state, actions, Modifier.weight(1f).fillMaxSize())
                    VerticalDivider()
                    KeypadColumn(rows, actions, second, { second = !second }, Modifier.weight(1f).widthIn(max = 560.dp).fillMaxSize())
                }
            }
            else -> {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    DisplayColumn(state, actions, Modifier.fillMaxWidth().weight(1f))
                    HorizontalDivider()
                    KeypadColumn(rows, actions, second, { second = !second }, Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (showUnitPicker) UnitPickerSheet(state, actions) { showUnitPicker = false }
}
