package com.vayunmathur.health.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Small pill showing a percent delta with an arrow. */
@Composable
fun DeltaChip(
    percent: Float,
    isGood: Boolean,
    modifier: Modifier = Modifier,
) {
    val (containerColor, contentColor) = if (isGood) {
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    val arrow = if (percent >= 0f) "▲" else "▼"
    val absPct = kotlin.math.abs(percent)
    Surface(
        modifier = modifier.wrapContentSize(),
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = "$arrow ${"%.0f".format(absPct)}%",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
