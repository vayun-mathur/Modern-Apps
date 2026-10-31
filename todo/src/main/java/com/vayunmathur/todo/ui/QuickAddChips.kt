package com.vayunmathur.todo.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.IconEvent
import com.vayunmathur.library.ui.IconFlag
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconLocalOffer
import com.vayunmathur.library.ui.IconRepeat
import com.vayunmathur.library.ui.Text
import com.vayunmathur.todo.R
import com.vayunmathur.todo.domain.QuickAddParser

/** Read-only chips for what [QuickAddParser] recognised. */
@Composable
fun QuickAddChips(parsed: QuickAddParser.Result, lists: List<QuickAddParser.ListName>) {
    val icon = Modifier.size(18.dp)
    val now = System.currentTimeMillis()
    parsed.dueAt?.let { due ->
        AssistChip(onClick = {}, label = { Text(dueLabel(due, parsed.dueAllDay, now)) }, leadingIcon = { IconEvent(icon) })
    }
    parsed.recurrence?.let { rec ->
        AssistChip(onClick = {}, label = { Text(recurrenceLabel(rec)) }, leadingIcon = { IconRepeat(icon) })
    }
    if (parsed.priority != 0) {
        AssistChip(
            onClick = {},
            label = { Text(stringResource(R.string.priority_n, parsed.priority)) },
            leadingIcon = { IconFlag(icon, tint = priorityColor(parsed.priority) ?: androidx.compose.ui.graphics.Color.Unspecified) },
        )
    }
    parsed.listSyncId?.let { id ->
        lists.firstOrNull { it.syncId == id }?.let { list ->
            AssistChip(onClick = {}, label = { Text(list.name) }, leadingIcon = { IconList(icon) })
        }
    }
    parsed.tags.forEach { tag ->
        AssistChip(onClick = {}, label = { Text(tag) }, leadingIcon = { IconLocalOffer(icon) })
    }
}
