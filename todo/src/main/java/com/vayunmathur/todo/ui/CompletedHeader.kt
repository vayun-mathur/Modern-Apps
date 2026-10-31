package com.vayunmathur.todo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.animatedFloat
import com.vayunmathur.todo.R

/** Collapsible "Completed (n)" header with a Clear action. */
@Composable
fun CompletedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit, onClear: () -> Unit) {
    val rotation = animatedFloat(if (expanded) 0f else -90f)
    Row(
        modifier = Modifier
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconKeyboardArrowDown(Modifier.graphicsLayer { rotationZ = rotation })
        Text(
            pluralStringResource(R.plurals.completed_count, count, count),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        TextButton(onClick = onClear) { Text(stringResource(R.string.clear_completed)) }
    }
}
