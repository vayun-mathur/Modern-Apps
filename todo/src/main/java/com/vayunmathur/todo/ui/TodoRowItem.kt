package com.vayunmathur.todo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.CheckboxDefaults
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.todo.R
import com.vayunmathur.todo.domain.TodoRow

/** What a row needs beyond its data; grouped to keep the composable's parameter list short. */
class TodoRowCallbacks(
    val onToggle: () -> Unit,
    val onClick: () -> Unit,
    val onLongClick: () -> Unit,
    val accessibilityActions: List<CustomAccessibilityAction> = emptyList(),
)

/** One task: checkbox tinted by priority, title (struck through when done), meta and notes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TodoRowItem(
    row: TodoRow,
    query: String,
    now: Long,
    showList: Boolean,
    selected: Boolean,
    elevation: Dp,
    callbacks: TodoRowCallbacks,
    trailing: @Composable (() -> Unit)? = null,
) {
    val todo = row.todo
    val done = todo.done
    val stateLabel = stringResource(if (done) R.string.state_completed else R.string.state_not_completed)
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    ListItem(
        modifier = Modifier
            .combinedClickable(onClick = callbacks.onClick, onLongClick = callbacks.onLongClick)
            .semantics(mergeDescendants = true) {
                stateDescription = stateLabel
                customActions = callbacks.accessibilityActions
            }
            .alpha(if (row.blocked && !done) BLOCKED_ALPHA else 1f),
        leadingContent = {
            val tint = priorityColor(todo.priority)
            Checkbox(
                checked = done,
                onCheckedChange = { callbacks.onToggle() },
                colors = if (tint != null) {
                    CheckboxDefaults.colors(uncheckedColor = tint, checkedColor = tint)
                } else {
                    CheckboxDefaults.colors()
                },
            )
        },
        supportingContent = if (row.hasMeta(showList) || todo.notes.isNotBlank()) {
            {
                Column {
                    if (row.hasMeta(showList)) TodoRowMeta(row, now, showList)
                    if (todo.notes.isNotBlank()) {
                        Text(
                            todo.notes.lineSequence().first { it.isNotBlank() },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        } else {
            null
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = container),
        elevation = ListItemDefaults.elevation(elevation = elevation),
    ) {
        HighlightedText(
            text = todo.title.ifBlank { stringResource(R.string.untitled) },
            query = query,
            maxLines = 2,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            modifier = Modifier.alpha(if (done) DONE_ALPHA else 1f),
        )
    }
}

private const val DONE_ALPHA = 0.6f
private const val BLOCKED_ALPHA = 0.55f

internal val ROW_DRAG_ELEVATION = 8.dp
