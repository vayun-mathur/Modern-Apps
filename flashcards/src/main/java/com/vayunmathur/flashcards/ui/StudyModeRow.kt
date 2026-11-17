package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.flashcards.ui.StudyMode
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Text

@Composable
fun ModeRow(
    labelRes: Int,
    value: StudyMode,
    selected: StudyMode,
    onSelect: (StudyMode) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Text(stringResource(labelRes), modifier = Modifier.padding(start = 8.dp))
    }
}
