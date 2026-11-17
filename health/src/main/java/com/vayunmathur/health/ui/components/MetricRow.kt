package com.vayunmathur.health.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Metric row — extends HealthRow with a value+unit titleMedium on the right,
 * optional inline sparkline above the value, optional DeltaChip below.
 */
@Composable
fun MetricRow(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    labelModifier: Modifier = Modifier,
    leadingIcon: (@Composable (Modifier, Color) -> Unit)? = null,
    leadingTint: Color = MaterialTheme.colorScheme.primary,
    delta: Float? = null,
    sparkline: List<Float>? = null,
    deltaIsGood: (Float) -> Boolean = { it >= 0f },
    onClick: (() -> Unit)? = null,
) {
    HealthRow(
        headline = label,
        modifier = modifier,
        headlineModifier = labelModifier,
        leadingIcon = leadingIcon,
        leadingTint = leadingTint,
        onClick = onClick,
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                if (sparkline != null) {
                    Sparkline(
                        values = sparkline,
                        modifier = Modifier
                            .width(28.dp)
                            .height(16.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                    )
                    if (unit.isNotEmpty()) {
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = unit,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
                if (delta != null) {
                    Spacer(Modifier.height(2.dp))
                    DeltaChip(percent = delta, isGood = deltaIsGood(delta))
                }
            }
        },
    )
}
