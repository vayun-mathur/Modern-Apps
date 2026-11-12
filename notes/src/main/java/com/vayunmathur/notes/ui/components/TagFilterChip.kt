package com.vayunmathur.notes.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

private val ChipShape = RoundedCornerShape(8.dp)

/**
 * One chip in the notes list's tag filter row: [label] plus its note [count],
 * filled with a check mark while [selected]. Single-select, so it carries
 * radio-button semantics.
 *
 * Drawn here rather than with the shared `FilterChip` because the row needs a
 * long-press (Rename / Delete menu) and `FilterChip` owns its click handling,
 * so an outer long-press modifier never sees the gesture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TagFilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .clip(ChipShape)
            .combinedClickable(
                role = Role.RadioButton,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics { this.selected = selected },
        shape = ChipShape,
        color = if (selected) colors.secondaryContainer else Color.Transparent,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(
            Modifier.height(32.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The check is decoration: `selected` semantics already say it.
            if (selected) IconCheck(Modifier.size(18.dp).clearAndSetSemantics {})
            Text(
                label,
                modifier = Modifier.widthIn(max = 160.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) colors.onSecondaryContainer else colors.outline,
            )
        }
    }
}
