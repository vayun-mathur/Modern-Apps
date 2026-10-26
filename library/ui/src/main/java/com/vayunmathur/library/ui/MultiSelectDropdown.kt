package com.vayunmathur.library.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * [SelectDropdown] for several values at once: each option toggles in place and the menu stays
 * open, and the new selection is applied once, when the menu closes.
 *
 * Applying on close rather than per toggle is the point: picking three options should cost one
 * reload, not three. An empty selection is discarded, since "none of them" is never a valid filter.
 */
@Composable
fun <T> MultiSelectDropdown(
    label: String,
    options: List<T>,
    selected: List<T>,
    itemLabel: @Composable (T) -> String,
    onApply: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var staged by remember { mutableStateOf(emptySet<T>()) }
    DropdownTrigger(
        label = label,
        expanded = expanded,
        onExpandedChange = { open ->
            if (open) {
                staged = selected.toSet()
            } else if (staged.isNotEmpty() && staged != selected.toSet()) {
                onApply(options.filter { it in staged })
            }
            expanded = open
        },
        modifier = modifier,
    ) {
        for (option in options) {
            CheckableDropdownMenuItem(
                checked = option in staged,
                onCheckedChange = { checked -> staged = if (checked) staged + option else staged - option },
                text = { Text(itemLabel(option)) },
                checkedLeadingIcon = { IconCheck() },
            )
        }
    }
}
