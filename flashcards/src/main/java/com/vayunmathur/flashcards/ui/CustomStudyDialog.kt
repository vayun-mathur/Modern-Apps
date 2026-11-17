package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.flashcards.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.flashcards.ui.StudyMode
import com.vayunmathur.flashcards.ui.StudyParams
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

@Composable
fun CustomStudyDialog(
    onStart: (StudyParams) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(StudyMode.AHEAD) }
    var count by remember { mutableStateOf("20") }
    var days by remember { mutableStateOf("3") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_study)) },
        text = {
            Column {
                ModeRow(R.string.custom_study_ahead, StudyMode.AHEAD, mode) { mode = it }
                ModeRow(R.string.custom_study_lapses, StudyMode.LAPSES, mode) { mode = it }
                ModeRow(R.string.custom_study_new, StudyMode.NEW_ONLY, mode) { mode = it }
                ModeRow(R.string.custom_study_cram, StudyMode.CRAM, mode) { mode = it }
                OutlinedTextField(
                    value = count,
                    onValueChange = { count = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.custom_study_count)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (mode == StudyMode.AHEAD) {
                    OutlinedTextField(
                        value = days,
                        onValueChange = { days = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.custom_study_days)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onStart(
                    StudyParams(
                        mode = mode,
                        count = count.toIntOrNull()?.coerceAtLeast(1) ?: 20,
                        daysAhead = days.toIntOrNull()?.coerceAtLeast(1) ?: 3,
                    ),
                )
            }) { Text(stringResource(R.string.start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}
