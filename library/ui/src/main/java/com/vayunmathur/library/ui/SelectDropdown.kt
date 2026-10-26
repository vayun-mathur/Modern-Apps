package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

/**
 * A compact top-bar picker: the current value abbreviated as [label] with a drop arrow, opening a
 * menu of [options]. The same shape as the calendar's day/week/month switch.
 */
@Composable
fun <T> SelectDropdown(
    label: String,
    options: List<T>,
    selected: T,
    itemLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    DropdownTrigger(label, expanded, onExpandedChange = { expanded = it }, modifier) {
        for (option in options) {
            SelectableDropdownMenuItem(
                selected = option == selected,
                onClick = {
                    expanded = false
                    if (option != selected) onSelect(option)
                },
                text = { Text(itemLabel(option)) },
                selectedLeadingIcon = { IconCheck() },
            )
        }
    }
}

@Composable
internal fun DropdownTrigger(
    label: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier) {
        TextButton(onClick = { onExpandedChange(true) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                IconArrowDropDown(tint = MaterialTheme.colorScheme.primary)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }, content = menu)
    }
}
