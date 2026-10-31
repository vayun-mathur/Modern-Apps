package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.ui.MaterialTheme

/** Priority 1 (highest) to 4, or null for no priority. */
@Composable
fun priorityColor(priority: Int): Color? = when (priority) {
    1 -> MaterialTheme.colorScheme.error
    2 -> MaterialTheme.colorScheme.tertiary
    3 -> MaterialTheme.colorScheme.primary
    4 -> MaterialTheme.colorScheme.outline
    else -> null
}
