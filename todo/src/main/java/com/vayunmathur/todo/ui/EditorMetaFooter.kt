package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.Todo
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** "Created … · Edited … · Completed …" */
@Composable
fun EditorMetaFooter(todo: Todo, modifier: Modifier = Modifier) {
    val zone = TimeZone.currentSystemDefault()
    val is24 = rememberIs24Hour()
    val fmt = { ms: Long -> DateString.dateTime(Instant.fromEpochMilliseconds(ms), is24, zone) }
    val parts = listOfNotNull(
        stringResource(R.string.created_at, fmt(todo.createdAt)),
        stringResource(R.string.edited_at, fmt(todo.updatedAt)).takeIf { todo.updatedAt - todo.createdAt > EDIT_GRACE_MS },
        todo.completedAt?.let { stringResource(R.string.completed_at, fmt(it)) },
    )
    Text(
        parts.joinToString(" · "),
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val EDIT_GRACE_MS = 60_000L
