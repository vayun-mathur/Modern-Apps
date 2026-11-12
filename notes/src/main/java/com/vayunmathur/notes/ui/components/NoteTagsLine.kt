package com.vayunmathur.notes.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconTag
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * A note card's tags, compact: one line, a small tag icon, names separated by
 * a middle dot, ellipsised when they run out of room. Draws nothing for an
 * untagged note, so untagged cards look exactly as before.
 */
@Composable
fun NoteTagsLine(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    Row(
        modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconTag(Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary, contentDescription = null)
        Text(
            tags.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
