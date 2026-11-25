package com.vayunmathur.todo.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.todo.wear.R
import com.vayunmathur.todo.wear.data.WearTodoStore

/**
 * Quick add: one text field (system IME microphone = dictation). The raw
 * text is queued as a QUICK_ADD op; the PHONE parses it with its
 * QuickAddParser, so dates, lists, tags and priorities work exactly as on
 * the phone and the new task lands in the page it was added from.
 */
@Composable
fun WearTodoQuickAddScreen(
    store: WearTodoStore,
    pageKey: String,
    onDone: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item {
            Text(
                stringResource(R.string.wear_quick_add_hint),
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        item {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = MaterialTheme.colors.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colors.onSurface),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        item {
            Chip(
                onClick = {
                    if (text.isNotBlank()) store.quickAdd(text, pageKey)
                    onDone()
                },
                label = { Text(stringResource(R.string.wear_save), maxLines = 1) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                onClick = onDone,
                label = { Text(stringResource(R.string.wear_back), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
