package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.ui.NoteRow
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.parseMarkdown

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteRowItem(
    row: NoteRow,
    selection: com.vayunmathur.library.ui.SelectionState<Long>,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    draggable: Boolean = false,
) {
    val fields = row.note.fieldList
    val muted = row.suspended
    ListItem(
        leadingContent = if (selection.isActive) {
            {
                Checkbox(
                    checked = selection.isSelected(row.note.id),
                    onCheckedChange = { selection.toggle(row.note.id) },
                )
            }
        } else {
            null
        },
        headlineContent = {
            Text(parseMarkdown(row.note.sortField.substringBefore('\n').take(60), showMarkers = false))
        },
        supportingContent = {
            Text(parseMarkdown(fields.getOrNull(1).orEmpty().substringBefore('\n').take(60), showMarkers = false))
        },
        trailingContent = {
            Row {
                if (muted) {
                    Text(
                        stringResource(R.string.suspended_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (row.cardCount != 1) {
                    Text(pluralStringResource(R.plurals.card_count_badge, row.cardCount, row.cardCount))
                }
                if (!selection.isActive && draggable) {
                    IconButton(onClick = {}, modifier = modifier) { IconDragHandle() }
                }
            }
        },
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    )
}
