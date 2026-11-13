package com.vayunmathur.games.wordmaker.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconInfo
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR

@Composable
fun DefinitionDialog(word: String, definition: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = word.replaceFirstChar { it.uppercase() }) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // The bundled dictionary does not cover every word the puzzles use, so an
                // empty list is a normal outcome and has to be said out loud — otherwise the
                // tap looks like it did nothing (#627).
                if (definition.isEmpty()) {
                    Text(text = stringResource(R.string.no_definition_available))
                } else {
                    Text(text = definition.joinToString("\n\n"))
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(UiR.string.close))
            }
        }
    )
}


@Composable
fun BonusWordsDialog(
    bonusWords: Set<String>,
    getDefinition: (String) -> List<String>,
    onDismiss: () -> Unit
) {
    var definitionDialog by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        // The count of this puzzle's bonus words found so far (#915).
        title = { Text(text = pluralStringResource(R.plurals.bonus_words_title, bonusWords.size, bonusWords.size)) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                // Full-height list rows (the old ~28 dp text lines were hard to hit) with a
                // visible info affordance, so it is clear a tap shows the word's definition.
                items(bonusWords.toList().sorted()) { word ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                definitionDialog = Pair(word, getDefinition(word))
                            },
                        trailingContent = {
                            // Decorative: the row itself is the button.
                            IconInfo(
                                modifier = Modifier.clearAndSetSemantics {},
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        Text(text = word)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss
            ) {
                Text(stringResource(UiR.string.close))
            }
        }
    )
    definitionDialog?.let { (w, d) ->
        DefinitionDialog(word = w, definition = d) {
            definitionDialog = null
        }
    }
}
