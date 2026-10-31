package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSort
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.todo.R
import com.vayunmathur.todo.domain.SortMode

@Composable
fun SortMenu(sort: SortMode, onSort: (SortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { IconSort() }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        SortMode.entries.forEach { mode ->
            SelectableDropdownMenuItem(
                selected = mode == sort,
                onClick = {
                    onSort(mode)
                    open = false
                },
                text = { Text(stringResource(sortLabel(mode))) },
            )
        }
    }
}

private fun sortLabel(mode: SortMode): Int = when (mode) {
    SortMode.MANUAL -> R.string.sort_manual
    SortMode.DUE -> R.string.sort_due
    SortMode.PRIORITY -> R.string.sort_priority
    SortMode.CREATED -> R.string.sort_created
    SortMode.TITLE -> R.string.sort_title
}
