package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One line of the translucent bottom panel on the Sun and Moon blocks: a small
 * leading icon and a time (or duration, or phase name). [style] exists for the
 * moon's phase names, which are far longer than a clock time and overflow the
 * narrowest grid cell at the default size.
 */
@Composable
fun RiseSetTimeRow(
    text: String,
    icon: @Composable (Modifier, Color) -> Unit,
    style: TextStyle = MaterialTheme.typography.labelLarge,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon(Modifier.size(18.dp), MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            style = style,
            maxLines = 1,
            softWrap = true,
        )
    }
}
