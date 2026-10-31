package com.vayunmathur.todo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SwipeActionSide
import com.vayunmathur.library.ui.SwipeActionsBox

/** Swipe right to toggle done, left to delete. */
@Composable
fun TodoSwipeRow(
    enabled: Boolean,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    SwipeActionsBox(
        modifier = modifier,
        enableStartToEnd = enabled,
        enableEndToStart = enabled,
        onStartToEnd = onComplete,
        onEndToStart = onDelete,
        background = { side ->
            val scheme = MaterialTheme.colorScheme
            when (side) {
                SwipeActionSide.StartToEnd -> Box(
                    Modifier.fillMaxSize().background(scheme.primaryContainer).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { IconCheck(tint = scheme.onPrimaryContainer) }
                SwipeActionSide.EndToStart -> Box(
                    Modifier.fillMaxSize().background(scheme.errorContainer).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) { IconDelete(tint = scheme.onErrorContainer) }
                SwipeActionSide.None -> Unit
            }
        },
        content = content,
    )
}
