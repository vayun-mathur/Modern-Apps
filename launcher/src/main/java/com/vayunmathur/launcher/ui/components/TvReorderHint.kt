package com.vayunmathur.launcher.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.animatedFloat

/** Fixed slot for the reorder hint. Always composed at the same height. */
val TvHintSlot = 28.dp

/**
 * Contextual hint under the header. Always composed at [TvHintSlot] so showing or hiding
 * the text never moves the grid — the label cross-fades instead. Idle shows nothing
 * (alpha 0) rather than being removed from the tree.
 */
@Composable
fun TvReorderHint(
    moving: Boolean,
    armed: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TvHintSlot),
        contentAlignment = Alignment.CenterStart,
    ) {
        val text = when {
            moving -> "Arrows move • OK drops • Back cancels"
            armed -> "OK picks up • Back done"
            else -> ""
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xA8FFFFFF),
            maxLines = 1,
            modifier = Modifier.alpha(animatedFloat(if (text.isEmpty()) 0f else 1f)),
            overflow = TextOverflow.Ellipsis,
        )
    }
}

