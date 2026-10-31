package com.vayunmathur.todo.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.todo.R
import com.vayunmathur.todo.domain.DueBucket
import com.vayunmathur.todo.domain.GroupKey

@Composable
fun GroupHeader(key: GroupKey, modifier: Modifier = Modifier) {
    val label = when (key) {
        GroupKey.None -> return
        is GroupKey.Section -> key.name ?: stringResource(R.string.no_section)
        is GroupKey.Bucket -> stringResource(
            when (key.bucket) {
                DueBucket.OVERDUE -> R.string.bucket_overdue
                DueBucket.TODAY -> R.string.bucket_today
                DueBucket.TOMORROW -> R.string.bucket_tomorrow
                DueBucket.THIS_WEEK -> R.string.bucket_this_week
                DueBucket.LATER -> R.string.bucket_later
                DueBucket.NO_DATE -> R.string.bucket_no_date
            },
        )
    }
    val color = if (key == GroupKey.Bucket(DueBucket.OVERDUE)) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    Text(
        label,
        color = color,
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}
