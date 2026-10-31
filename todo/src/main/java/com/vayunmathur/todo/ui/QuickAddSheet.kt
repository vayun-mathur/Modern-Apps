package com.vayunmathur.todo.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.todo.domain.QuickAddParser

/** Quick add over the list, for the share target, shortcut, tile, widget and voice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    initialText: String,
    lists: List<QuickAddParser.ListName>,
    onAdd: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        QuickAddBar(
            lists = lists,
            onAdd = {
                onAdd(it)
                onDismiss()
            },
            initialText = initialText,
            autoFocus = true,
            modifier = Modifier.navigationBarsPadding().padding(bottom = 16.dp),
        )
    }
}
