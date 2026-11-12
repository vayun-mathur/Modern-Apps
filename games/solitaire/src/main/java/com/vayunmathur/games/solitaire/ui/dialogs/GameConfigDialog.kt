package com.vayunmathur.games.solitaire.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.solitaire.R
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KLONDIKE_DIFFICULTY_ORDER
import com.vayunmathur.games.solitaire.data.KlondikeDifficulty
import com.vayunmathur.games.solitaire.data.passes
import com.vayunmathur.games.solitaire.ui.displayName
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameConfigDialog(mode: GameMode, onStart: (GameConfig) -> Unit, onDismiss: () -> Unit) {
    var drawMode by remember { mutableStateOf(DrawMode.DRAW_ONE) }
    var klondikeDifficulty by remember { mutableStateOf(KlondikeDifficulty.REGULAR) }
    var relaxed by remember { mutableStateOf(false) }
    var spiderSuits by remember { mutableStateOf(4) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(mode.displayName()) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (mode) {
                    GameMode.KLONDIKE -> {
                        SingleChoiceSegmentedButtonRow {
                            listOf(DrawMode.DRAW_ONE to R.string.draw_one, DrawMode.DRAW_THREE to R.string.draw_three).forEachIndexed { idx, (value, label) ->
                                SegmentedButton(shape = SegmentedButtonDefaults.itemShape(idx, 2), onClick = { drawMode = value }, selected = drawMode == value) { Text(stringResource(label)) }
                            }
                        }
                        KlondikePassesRow(klondikeDifficulty) { klondikeDifficulty = it }
                    }
                    GameMode.SPIDER -> {
                        SingleChoiceSegmentedButtonRow {
                            listOf(1 to UiR.string.easy, 2 to UiR.string.medium, 4 to UiR.string.hard).forEachIndexed { idx, (value, label) ->
                                SegmentedButton(shape = SegmentedButtonDefaults.itemShape(idx, 3), onClick = { spiderSuits = value }, selected = spiderSuits == value) { Text(stringResource(label)) }
                            }
                        }
                    }
                    GameMode.PYRAMID -> DifficultyRow(relaxed) { relaxed = it }
                    GameMode.FREECELL -> {}
                }
                Button(onClick = { onStart(GameConfig(drawMode = drawMode, klondikeDifficulty = klondikeDifficulty, relaxed = relaxed, spiderSuits = spiderSuits)) }, Modifier.fillMaxWidth()) { Text(stringResource(UiR.string.new_game)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.back)) } }
    )
}

/**
 * Klondike passes through the stock (#911). The caption carries the meaning so each
 * segment can be just the count, which keeps four options on one row in a phone
 * dialog; the spoken label is the full phrase ("3 passes", "Unlimited passes").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KlondikePassesRow(selected: KlondikeDifficulty, onChange: (KlondikeDifficulty) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.stock_passes), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow {
            KLONDIKE_DIFFICULTY_ORDER.forEachIndexed { idx, value ->
                val passes = value.passes()
                val spoken = if (passes == null) {
                    stringResource(R.string.passes_unlimited)
                } else {
                    pluralStringResource(R.plurals.passes_count, passes, passes)
                }
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(idx, KLONDIKE_DIFFICULTY_ORDER.size),
                    onClick = { onChange(value) },
                    selected = selected == value,
                    modifier = Modifier.semantics { contentDescription = spoken },
                ) {
                    Text(passes?.toString() ?: "\u221E")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DifficultyRow(relaxed: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        listOf(false to R.string.mode_original, true to R.string.mode_relaxed).forEachIndexed { idx, (value, label) ->
            SegmentedButton(shape = SegmentedButtonDefaults.itemShape(idx, 2), onClick = { onChange(value) }, selected = relaxed == value) { Text(stringResource(label)) }
        }
    }
}
